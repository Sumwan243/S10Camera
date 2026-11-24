package com.samsung.android.scan3d.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.hardware.camera2.params.MeteringRectangle
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Discovered camera representation.
 * Mapping for Samsung Galaxy S10+ (SM-G975U1):
 *   ID 0 -> 12MP Main Wide (LEVEL_3, Master Logical Multi-Camera driving 1x & 2x)
 *   ID 1 -> 10MP Front Primary (Dual Pixel AF)
 *   ID 2 -> 16MP Ultra Wide (Fixed Focus)
 *   ID 3 -> 8MP Front Wide / Depth (90° FoV)
 */
data class CameraInfo(
    val id: String,
    val facing: Int,
    val hardwareLevel: Int,
    val maxPhotoSize: Size,
    val focalLengths: FloatArray,
    val hasRawCapture: Boolean,
    val hasManualSensor: Boolean,
    val hasManualPostProcessing: Boolean,
    val hasBurstCapture: Boolean,
    val hasAutoFocus: Boolean,
    val hasOIS: Boolean,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val evCompensationRange: Range<Int>?,
    val evCompensationStep: Float,
    val physicalCameraIds: Set<String>,
    val lensType: LensType
) {
    override fun equals(other: Any?): Boolean = other is CameraInfo && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

enum class LensType(val label: String, val zoomLabel: String) {
    ULTRA_WIDE("Ultra Wide", ".5"),
    MAIN_WIDE("Main", "1x"),
    TELEPHOTO("Telephoto", "2"),
    FRONT_PRIMARY("Selfie", "1x"),
    FRONT_WIDE("Selfie Wide", ".6"),
    UNKNOWN("Unknown", "?")
}

enum class FlashMode {
    OFF,
    AUTO,
    ON,
    TORCH
}

/** Output aspect ratio (sensor/landscape orientation: width:height). Preview AND still use the same one. */
enum class AspectMode(val label: String, val w: Int, val h: Int) {
    RATIO_4_3("4:3", 4, 3),
    RATIO_16_9("16:9", 16, 9),
    RATIO_1_1("1:1", 1, 1);

    fun matches(size: Size): Boolean =
        abs(size.width.toFloat() / size.height - w.toFloat() / h) < 0.02f
}

class CameraEngine(private val context: Context) {

    companion object {
        private const val TAG = "S10CameraEngine"
        private const val MAIN_WIDE_WIDTH = 4032
        private const val ULTRA_WIDE_WIDTH = 4608
        private const val FRONT_WIDE_WIDTH = 2944

        /** Preview is capped well below sensor resolution; it must be a size the camera lists for SurfaceTexture. */
        private const val PREVIEW_MAX_WIDTH = 1920
        private const val READER_MAX_IMAGES = 8

        /** How long a tap-to-focus lock is held before returning to continuous autofocus. */
        private const val FOCUS_HOLD_MS = 3000L

        /** HDR burst frames are underexposed by this many AE-compensation steps to protect highlights. */
        private const val HDR_EV_STEPS = -3
        private const val DEFAULT_MANUAL_ISO = 100
        private const val DEFAULT_MANUAL_EXPOSURE_NS = 16_666_666L
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val _discoveredCameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val discoveredCameras: StateFlow<List<CameraInfo>> = _discoveredCameras.asStateFlow()

    private val _activeCameraId = MutableStateFlow<String?>(null)
    val activeCameraId: StateFlow<String?> = _activeCameraId.asStateFlow()

    private val _discoveryLog = MutableStateFlow<List<String>>(emptyList())
    val discoveryLog: StateFlow<List<String>> = _discoveryLog.asStateFlow()

    // Camera callbacks run on the background thread, public API is called from the main thread.
    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var captureSession: CameraCaptureSession? = null
    @Volatile private var previewRequestBuilder: CaptureRequest.Builder? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var activeChars: CameraCharacteristics? = null
    private var previewSurface: Surface? = null
    private var currentTexture: SurfaceTexture? = null

    // Aspect ratio: [aspectMode] is what the user asked for, [openedAspect] what the open session was built with.
    @Volatile private var aspectMode: AspectMode = AspectMode.RATIO_4_3
    @Volatile private var openedAspect: AspectMode? = null
    @Volatile private var previewBufferSize: Size? = null
    @Volatile private var captureSize: Size = Size(4032, 3024)

    /** Displayed (portrait) width/height of the preview, so the UI can size the viewfinder exactly. */
    private val _previewAspect = MutableStateFlow(3f / 4f)
    val previewAspect: StateFlow<Float> = _previewAspect.asStateFlow()

    // Tap-to-focus state
    @Volatile private var meteringRegion: MeteringRectangle? = null
    @Volatile private var focusReleaseRunnable: Runnable? = null

    /** Incremented every time a camera is closed; lets stale async callbacks recognise they are outdated. */
    @Volatile private var openToken = 0

    @Volatile var currentZoom: Float = 1.0f
        private set

    @Volatile var currentFlashMode: FlashMode = FlashMode.OFF
        private set

    // Control state. Kept in the engine so it survives camera switches and is applied identically
    // to the preview request and to the still-capture request.
    @Volatile private var manualIso: Int? = null
    @Volatile private var manualExposureNs: Long? = null
    @Volatile private var manualFocusDistance: Float? = null
    @Volatile private var evIndex: Int = 0
    @Volatile private var awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO

    fun discoverCameras(): List<CameraInfo> {
        val cameras = mutableListOf<CameraInfo>()
        val log = mutableListOf<String>()

        log.add("═══ S10+ Hardware Camera Map ═══")
        log.add("Model: ${Build.MODEL} | Package: ${context.packageName}")

        val visibleIds = try {
            cameraManager.cameraIdList.toList()
        } catch (t: Throwable) {
            log.add("Failed to enumerate camera IDs: ${t.message}")
            _discoveryLog.value = log
            return emptyList()
        }

        for (id in visibleIds) {
            try {
                val info = probeCameraId(id)
                if (info != null) {
                    cameras.add(info)
                    log.add("Cam $id: ${info.lensType.label} (${info.maxPhotoSize.width}x${info.maxPhotoSize.height})")
                }
            } catch (t: Throwable) {
                log.add("Cam $id probe error: ${t.message}")
            }
        }

        // Only report the logical multi-camera if the HAL actually exposes physical sub-cameras.
        cameras.firstOrNull { it.physicalCameraIds.isNotEmpty() }?.let {
            log.add("Cam ${it.id}: Logical multi-camera (physical IDs: ${it.physicalCameraIds.joinToString()})")
        }

        _discoveredCameras.value = cameras
        _discoveryLog.value = log
        return cameras
    }

    private fun probeCameraId(id: String): CameraInfo? {
        return try {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            val hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

            val streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val maxSize = outputSizes.maxByOrNull { it.width * it.height } ?: Size(4032, 3024)

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val hasRaw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
            val hasManualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
            val hasManualPost = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities
            val hasBurst = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities

            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val hasAF = afModes.any {
                it == CameraMetadata.CONTROL_AF_MODE_AUTO || it == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }

            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
            val hasOIS = oisModes.any { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON }

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.1f
            val physicalIds = chars.physicalCameraIds

            val lensType = when {
                facing == CameraCharacteristics.LENS_FACING_BACK -> {
                    when {
                        maxSize.width == ULTRA_WIDE_WIDTH || id == "2" -> LensType.ULTRA_WIDE
                        maxSize.width == MAIN_WIDE_WIDTH || id == "0" -> LensType.MAIN_WIDE
                        else -> LensType.UNKNOWN
                    }
                }
                facing == CameraCharacteristics.LENS_FACING_FRONT -> {
                    when {
                        maxSize.width == FRONT_WIDE_WIDTH || id == "3" -> LensType.FRONT_WIDE
                        else -> LensType.FRONT_PRIMARY
                    }
                }
                else -> LensType.UNKNOWN
            }

            CameraInfo(
                id = id,
                facing = facing,
                hardwareLevel = hardwareLevel,
                maxPhotoSize = maxSize,
                focalLengths = focalLengths,
                hasRawCapture = hasRaw,
                hasManualSensor = hasManualSensor,
                hasManualPostProcessing = hasManualPost,
                hasBurstCapture = hasBurst,
                hasAutoFocus = hasAF,
                hasOIS = hasOIS,
                isoRange = isoRange,
                exposureRange = exposureRange,
                evCompensationRange = evRange,
                evCompensationStep = evStep,
                physicalCameraIds = physicalIds,
                lensType = lensType
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed probing camera $id", t)
            null
        }
    }

    fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("S10CameraWorker").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (_: InterruptedException) {}
    }

    /**
     * Opens [cameraId] and starts a preview into [texture].
     * [onReady] fires once the capture session is configured and the preview is running.
     */
    @SuppressLint("MissingPermission")
    fun openCamera(
        cameraId: String,
        texture: SurfaceTexture,
        onReady: () -> Unit = {},
        onFailed: (String) -> Unit = {}
    ) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onFailed("Camera permission not granted")
            return
        }

        // Avoid re-opening the identical camera with the identical surface
        if (cameraDevice?.id == cameraId && captureSession != null && currentTexture === texture &&
            openedAspect == aspectMode) {
            onReady()
            return
        }

        closeCamera()
        val token = openToken

        val chars = try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (t: Throwable) {
            onFailed("Cannot read characteristics of camera $cameraId: ${t.message}")
            return
        }

        // The preview buffer size MUST be one of the sizes this particular camera lists for
        // SurfaceTexture outputs. A hard-coded 4032x3024 is not valid for the ultra-wide or front lenses
        // (their sensors are different sizes), which makes session configuration fail -> black preview.
        val (previewSize, stillSize) = resolveStreamSizes(chars, aspectMode)
        captureSize = stillSize
        previewBufferSize = previewSize
        openedAspect = aspectMode
        _previewAspect.value = displayedAspect(chars, previewSize)
        val surface = try {
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            Surface(texture)
        } catch (t: Throwable) {
            onFailed("Preview surface unavailable: ${t.message}")
            return
        }

        previewSurface = surface
        currentTexture = texture
        activeChars = chars

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (token != openToken) {
                        // Superseded by a newer open/close while this one was still pending.
                        try { camera.close() } catch (_: Throwable) {}
                        return
                    }
                    cameraDevice = camera
                    _activeCameraId.value = cameraId
                    createCombinedCaptureSession(camera, surface, chars, token, onReady, onFailed)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Throwable) {}
                    if (cameraDevice === camera) {
                        cameraDevice = null
                        captureSession = null
                        _activeCameraId.value = null
                    }
                    if (token == openToken) onFailed("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Throwable) {}
                    if (cameraDevice === camera) {
                        cameraDevice = null
                        captureSession = null
                        _activeCameraId.value = null
                    }
                    if (token == openToken) onFailed("Camera device error: $error")
                }
            }, backgroundHandler)
        } catch (t: Throwable) {
            onFailed("Cannot open camera $cameraId: ${t.message}")
        }
    }

    /**
     * Picks (preview size, still size) that share one aspect ratio, so what you see is what you get.
     * Uses the requested ratio when THIS camera offers it for both streams, otherwise falls back
     * (4:3, 16:9, 1:1 in that order) - e.g. a front camera that has no 1:1 mode.
     * Both sizes must come from the camera's own lists; an unlisted size makes session configuration fail.
     */
    private fun resolveStreamSizes(chars: CameraCharacteristics, requested: AspectMode): Pair<Size, Size> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()

        val candidates = listOf(requested, AspectMode.RATIO_4_3, AspectMode.RATIO_16_9, AspectMode.RATIO_1_1).distinct()
        for (aspect in candidates) {
            val previews = previewSizes.filter { aspect.matches(it) }
            val stills = jpegSizes.filter { aspect.matches(it) }
            if (previews.isEmpty() || stills.isEmpty()) continue

            val preview = previews.filter { it.width <= PREVIEW_MAX_WIDTH }.maxByOrNull { it.width * it.height }
                ?: previews.minByOrNull { it.width * it.height }
                ?: previews.first()
            val still = stills.maxByOrNull { it.width * it.height } ?: stills.first()
            return preview to still
        }

        // Unknown geometry: use the largest sizes we are allowed to.
        val preview = previewSizes.filter { it.width <= PREVIEW_MAX_WIDTH }.maxByOrNull { it.width * it.height }
            ?: Size(1440, 1080)
        val still = jpegSizes.maxByOrNull { it.width * it.height } ?: Size(4032, 3024)
        return preview to still
    }

    /**
     * Portrait (displayed) width/height of a stream. The sensor is mounted rotated (90 or 270 on the S10), so the
     * landscape buffer appears turned on its side when the phone is held upright.
     */
    private fun displayedAspect(chars: CameraCharacteristics, size: Size): Float {
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val swap = orientation % 180 == 90
        return if (swap) size.height.toFloat() / size.width else size.width.toFloat() / size.height
    }

    /** Requested aspect ratio; the caller must re-open the camera for it to take effect. */
    fun setAspectMode(mode: AspectMode) {
        aspectMode = mode
    }

    /**
     * Creates combined session for simultaneous preview and full-res image capture.
     */
    private fun createCombinedCaptureSession(
        camera: CameraDevice,
        surface: Surface,
        chars: CameraCharacteristics,
        token: Int,
        onReady: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        try {
            if (!surface.isValid) {
                onFailed("Preview surface is not valid")
                return
            }

            val maxSize = captureSize

            imageReader?.close()
            val reader = ImageReader.newInstance(maxSize.width, maxSize.height, ImageFormat.JPEG, READER_MAX_IMAGES)
            imageReader = reader

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyControls(this, chars, hdr = false)
            }
            previewRequestBuilder = builder

            camera.createCaptureSession(
                listOf(surface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (token != openToken || cameraDevice !== camera) {
                            try { session.close() } catch (_: Throwable) {}
                            return
                        }
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Repeating preview request failed", t)
                            onFailed("Preview failed: ${t.message}")
                            return
                        }
                        onReady()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        if (token == openToken) onFailed("Capture session configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Combined session setup failed", t)
            onFailed("Session setup failed: ${t.message}")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------------------------------------

    /**
     * Seamless Zoom (both Front Selfie and Rear lenses).
     */
    fun setZoomRatio(ratio: Float) {
        currentZoom = ratio.coerceAtLeast(1.0f)
        refreshPreview()
    }

    fun setFlashMode(mode: FlashMode) {
        currentFlashMode = mode
        refreshPreview()
    }

    /**
     * Pro-mode controls in one call. Pass nulls / 0 / AUTO to return to fully automatic behaviour.
     * ISO and shutter are combined because camera2 can only do manual exposure with AE fully off.
     */
    fun setManualControls(
        iso: Int?,
        exposureNs: Long?,
        focusDistance: Float?,
        evCompensationIndex: Int,
        whiteBalanceMode: Int
    ) {
        manualIso = iso
        manualExposureNs = exposureNs
        manualFocusDistance = focusDistance
        evIndex = evCompensationIndex
        awbMode = whiteBalanceMode
        refreshPreview()
    }

    /**
     * Tap-to-focus + tap-to-meter. (nx, ny) is the tap inside the DISPLAYED viewfinder, normalised 0..1 from
     * the top-left. The point is mapped back into sensor coordinates: undo the selfie mirror, undo the sensor
     * rotation, then account for the zoom crop and for the crop to the stream's aspect ratio.
     */
    fun focusAt(nx: Float, ny: Float) {
        val chars = activeChars ?: return
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val handler = backgroundHandler ?: return
        val bufferSize = previewBufferSize ?: return
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val orientation = ((chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) % 360 + 360) % 360
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

        // Displayed, un-mirrored, upright coordinates
        val u = if (front) 1f - nx.coerceIn(0f, 1f) else nx.coerceIn(0f, 1f)
        val v = ny.coerceIn(0f, 1f)
        // Rotate into the sensor's own frame
        val sx: Float
        val sy: Float
        when (orientation) {
            90 -> { sx = v; sy = 1f - u }
            180 -> { sx = 1f - u; sy = 1f - v }
            270 -> { sx = 1f - v; sy = u }
            else -> { sx = u; sy = v }
        }

        // The part of the sensor that is actually visible: zoom crop, then cropped to the stream aspect ratio.
        val zoom = currentZoom.coerceAtLeast(1f)
        var cw = active.width() / zoom
        var ch = active.height() / zoom
        val streamAspect = bufferSize.width.toFloat() / bufferSize.height
        if (cw / ch > streamAspect) cw = ch * streamAspect else ch = cw / streamAspect

        val px = active.exactCenterX() - cw / 2f + sx * cw
        val py = active.exactCenterY() - ch / 2f + sy * ch

        val side = (min(cw, ch) * 0.15f).toInt().coerceAtLeast(32)
        val left = (px - side / 2f).toInt().coerceIn(active.left, (active.right - side).coerceAtLeast(active.left))
        val top = (py - side / 2f).toInt().coerceIn(active.top, (active.bottom - side).coerceAtLeast(active.top))

        meteringRegion = MeteringRectangle(left, top, side, side, MeteringRectangle.METERING_WEIGHT_MAX)
        focusReleaseRunnable?.let { handler.removeCallbacks(it) }

        try {
            applyControls(builder, chars, hdr = false)

            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            // Fixed-focus lenses (ultra-wide, front) can't focus, but metering still follows the tap.
            val canFocus = manualFocusDistance == null && minFocus > 0f &&
                CameraMetadata.CONTROL_AF_MODE_AUTO in afModes
            if (canFocus) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                session.capture(builder.build(), null, handler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                session.capture(builder.build(), null, handler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "Tap-to-focus failed", t)
        }

        val release = Runnable { releaseFocusLock() }
        focusReleaseRunnable = release
        handler.postDelayed(release, FOCUS_HOLD_MS)
    }

    /** Returns to continuous autofocus / full-frame metering after a tap-to-focus. */
    private fun releaseFocusLock() {
        if (meteringRegion == null) return
        meteringRegion = null
        val builder = previewRequestBuilder ?: return
        val chars = activeChars ?: return
        val session = captureSession ?: return
        try {
            applyControls(builder, chars, hdr = false)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "Releasing focus lock failed", t)
        }
    }

    private fun refreshPreview() {
        val builder = previewRequestBuilder ?: return
        val chars = activeChars ?: return
        val session = captureSession ?: return
        try {
            applyControls(builder, chars, hdr = false)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed updating preview request", t)
        }
    }

    /**
     * Writes the complete control state (focus, exposure/flash, EV, WB, zoom) into [builder].
     * Used for BOTH the preview and the still capture so a photo always matches what the preview shows.
     */
    @SuppressLint("NewApi")
    private fun applyControls(builder: CaptureRequest.Builder, chars: CameraCharacteristics, hdr: Boolean) {
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        // --- Focus ---------------------------------------------------------------------------
        val focus = manualFocusDistance
        if (focus != null && minFocusDistance > 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, minFocusDistance))
        } else if (meteringRegion != null && CameraMetadata.CONTROL_AF_MODE_AUTO in afModes) {
            // Tap-to-focus in progress: single-shot AF so the lens holds where the user tapped.
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        } else if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else if (CameraMetadata.CONTROL_AF_MODE_AUTO in afModes) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        }

        // --- Exposure + flash ----------------------------------------------------------------
        val iso = manualIso
        val exposureNs = manualExposureNs
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
        val manualExposure = manualSensor && (iso != null || exposureNs != null)
        val torch = currentFlashMode == FlashMode.TORCH && hasFlash

        if (manualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoValue = (iso ?: DEFAULT_MANUAL_ISO).let {
                if (isoRange != null) it.coerceIn(isoRange.lower, isoRange.upper) else it
            }
            val expValue = (exposureNs ?: DEFAULT_MANUAL_EXPOSURE_NS).let {
                if (expRange != null) it.coerceIn(expRange.lower, expRange.upper) else it
            }
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expValue)
        } else {
            val aeMode = when {
                !hasFlash -> CameraMetadata.CONTROL_AE_MODE_ON
                currentFlashMode == FlashMode.AUTO &&
                    CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH in aeModes ->
                    CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                currentFlashMode == FlashMode.ON &&
                    CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH in aeModes ->
                    CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        }
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
        )

        // --- Exposure compensation (clamped to what the camera supports) -----------------------
        var ev = evIndex + if (hdr && !manualExposure) HDR_EV_STEPS else 0
        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (evRange != null) ev = ev.coerceIn(evRange.lower, evRange.upper)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)

        // --- White balance ---------------------------------------------------------------------
        if (awbMode in awbModes) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        }

        // --- Tap-to-focus / metering regions ----------------------------------------------------
        val region = meteringRegion
        val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        builder.set(
            CaptureRequest.CONTROL_AF_REGIONS,
            if (region != null && maxAfRegions > 0 && focus == null) arrayOf(region) else null
        )
        builder.set(
            CaptureRequest.CONTROL_AE_REGIONS,
            if (region != null && maxAeRegions > 0 && !manualExposure) arrayOf(region) else null
        )

        // --- Zoom ------------------------------------------------------------------------------
        applyZoomToBuilder(builder, chars, currentZoom)
    }

    @SuppressLint("NewApi")
    private fun applyZoomToBuilder(builder: CaptureRequest.Builder, chars: CameraCharacteristics, ratio: Float) {
        val zoomRange = if (Build.VERSION.SDK_INT >= 30) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else null

        if (zoomRange != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio.coerceIn(zoomRange.lower, zoomRange.upper))
        } else {
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (sensorRect != null) {
                // Use the rect's own centre: the active array does not always start at (0,0).
                val centerX = sensorRect.centerX()
                val centerY = sensorRect.centerY()
                val cropW = (sensorRect.width() / ratio).toInt()
                val cropH = (sensorRect.height() / ratio).toInt()
                val cropRect = Rect(
                    centerX - cropW / 2,
                    centerY - cropH / 2,
                    centerX + cropW / 2,
                    centerY + cropH / 2
                )
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Capture
    // ---------------------------------------------------------------------------------------------

    /**
     * Executes photo capture. Exactly one of [onFramesReady] / [onError] is invoked (on the camera thread).
     */
    fun captureFrames(
        burstCount: Int = 1,
        isHdr: Boolean = true,
        onFramesReady: (List<ByteArray>) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val camera = cameraDevice ?: run { onError("No camera device open"); return }
        val session = captureSession ?: run { onError("No session active"); return }
        val reader = imageReader ?: run { onError("Image reader unavailable"); return }
        val chars = activeChars ?: run { onError("Camera characteristics unavailable"); return }
        val count = burstCount.coerceIn(1, READER_MAX_IMAGES)

        val lock = Any()
        val frames = ArrayList<ByteArray>()
        var expected = count
        var finished = false

        // Must be called with `lock` held.
        fun completeIfReady() {
            if (finished) return
            if (expected <= 0 && frames.isEmpty()) {
                finished = true
                reader.setOnImageAvailableListener(null, null)
                onError("The camera failed to deliver any frames")
            } else if (frames.size >= expected) {
                finished = true
                reader.setOnImageAvailableListener(null, null)
                onFramesReady(ArrayList(frames))
            }
        }

        try {
            // Drop any stale frames left over from an earlier, aborted capture.
            try {
                while (true) {
                    val stale = reader.acquireNextImage() ?: break
                    stale.close()
                }
            } catch (_: IllegalStateException) {}

            reader.setOnImageAvailableListener({ ir ->
                // Drain everything that is queued: one callback can cover several images.
                while (true) {
                    val img = try { ir.acquireNextImage() } catch (_: IllegalStateException) { null }
                    if (img == null) break
                    try {
                        val buffer = img.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        synchronized(lock) {
                            if (!finished) {
                                frames.add(bytes)
                                completeIfReady()
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error acquiring frame", t)
                    } finally {
                        img.close()
                    }
                }
            }, backgroundHandler)

            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                applyControls(this, chars, hdr = isHdr)
                set(CaptureRequest.JPEG_QUALITY, 99.toByte())
                // The app is locked to portrait, so the JPEG must be rotated by the sensor orientation.
                // Without this every photo comes out sideways.
                set(CaptureRequest.JPEG_ORIENTATION, chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
            }

            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.e(TAG, "Capture failed, reason=${failure.reason}")
                    synchronized(lock) {
                        if (!finished) {
                            expected--
                            completeIfReady()
                        }
                    }
                }

                override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                    synchronized(lock) {
                        if (!finished) {
                            expected = 0
                            completeIfReady()
                        }
                    }
                }
            }

            if (count <= 1) {
                session.capture(captureBuilder.build(), callback, backgroundHandler)
            } else {
                val requests = List(count) { captureBuilder.build() }
                session.captureBurst(requests, callback, backgroundHandler)
            }
        } catch (t: Throwable) {
            val report = synchronized(lock) {
                if (finished) false else { finished = true; true }
            }
            reader.setOnImageAvailableListener(null, null)
            if (report) onError("Capture trigger failed: ${t.message}")
        }
    }

    fun closeCamera() {
        openToken++

        focusReleaseRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        focusReleaseRunnable = null
        meteringRegion = null
        openedAspect = null
        previewBufferSize = null

        try { captureSession?.stopRepeating() } catch (_: Throwable) {}
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null

        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null

        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null

        try { previewSurface?.release() } catch (_: Throwable) {}
        previewSurface = null
        currentTexture = null

        previewRequestBuilder = null
        activeChars = null
        _activeCameraId.value = null
    }
}
