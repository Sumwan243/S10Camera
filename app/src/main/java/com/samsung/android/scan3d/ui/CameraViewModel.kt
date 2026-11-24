package com.samsung.android.scan3d.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.samsung.android.scan3d.camera.CameraEngine
import com.samsung.android.scan3d.camera.AspectMode
import com.samsung.android.scan3d.camera.CameraInfo
import com.samsung.android.scan3d.camera.FlashMode
import com.samsung.android.scan3d.camera.LensType
import com.samsung.android.scan3d.processing.ImageProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CaptureMode(val label: String) {
    NIGHT("NIGHT SIGHT"),
    PORTRAIT("PORTRAIT"),
    CAMERA("CAMERA"),
    PRO("PRO")
}

enum class TimerMode(val label: String, val seconds: Int) {
    OFF("Off", 0),
    SEC_3("3s", 3),
    SEC_10("10s", 10)
}

enum class GridMode(val label: String) {
    OFF("Off"),
    GRID_3X3("3x3"),
    GOLDEN_RATIO("Golden")
}

data class CameraUiState(
    val isInitialized: Boolean = false,
    val cameras: List<CameraInfo> = emptyList(),
    val activeCamera: CameraInfo? = null,
    val selectedLens: LensType = LensType.MAIN_WIDE,
    // Requested output aspect ratio, and the real displayed (portrait) w/h of the stream the engine is using.
    val aspectMode: AspectMode = AspectMode.RATIO_4_3,
    val previewAspect: Float = 3f / 4f,
    val zoomRatio: Float = 1.0f,
    val captureMode: CaptureMode = CaptureMode.CAMERA,
    // Real Controls (Zero Placeholders)
    val flashMode: FlashMode = FlashMode.AUTO,
    val timerMode: TimerMode = TimerMode.OFF,
    val countdownRemaining: Int? = null,
    val gridMode: GridMode = GridMode.OFF,
    val isHdrAutoOn: Boolean = true,
    // Manual Settings (Pro mode)
    val isManualControlsVisible: Boolean = false,
    val iso: Int? = null,
    val shutterSpeedNs: Long? = null,
    val evCompensation: Int = 0,
    val focusDistance: Float? = null,
    val whiteBalanceMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    // Processing & Gallery State
    val isCapturing: Boolean = false,
    val processingProgress: Float = 0f,
    val lastCapturedUri: Uri? = null,
    val lastCapturedThumbnail: Bitmap? = null,
    val statusMessage: String? = null,
    val showDiagnostics: Boolean = false
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"
        private const val STATUS_DURATION_MS = 3000L
    }

    private val engine = CameraEngine(application)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    val discoveryLog: StateFlow<List<String>> = engine.discoveryLog

    private var currentTexture: SurfaceTexture? = null
    private var countdownJob: Job? = null
    private var statusJob: Job? = null

    // NOTE: camera callbacks (camera thread) and processing progress (Default dispatcher) also write
    // the UI state, so every update goes through MutableStateFlow.update { } to stay atomic. The previous
    // `_uiState.value = _uiState.value.copy(..)` pattern silently lost updates under concurrency.

    init {
        engine.startBackgroundThread()
        // Preload latest photo from DCIM/S10ProCamera so gallery button is immediately ready!
        refreshLatestPhoto()

        // The engine knows the real stream size; the UI sizes the viewfinder from it so it's never stretched.
        viewModelScope.launch {
            engine.previewAspect.collect { aspect ->
                _uiState.update { it.copy(previewAspect = aspect) }
            }
        }
    }

    private fun refreshLatestPhoto() {
        viewModelScope.launch {
            val mediaItem = ImageProcessor.loadLatestPhoto(getApplication())
            if (mediaItem != null) {
                _uiState.update {
                    // Never overwrite a photo that was taken while this query was running.
                    if (it.lastCapturedUri != null) it
                    else it.copy(lastCapturedUri = mediaItem.uri, lastCapturedThumbnail = mediaItem.thumbnail)
                }
            }
        }
    }

    /**
     * Called whenever the viewfinder's SurfaceTexture becomes available (first start AND every time the
     * app returns from the background). Re-opens the lens the user had selected instead of resetting to 1x.
     */
    fun initializeCameras(texture: SurfaceTexture) {
        currentTexture = texture
        if (_uiState.value.lastCapturedThumbnail == null) {
            // Permission may only have been granted after the ViewModel was created.
            refreshLatestPhoto()
        }
        viewModelScope.launch {
            try {
                var cameras = _uiState.value.cameras
                if (cameras.isEmpty()) {
                    cameras = withContext(Dispatchers.Default) { engine.discoverCameras() }
                    _uiState.update { it.copy(cameras = cameras) }
                }

                val target = cameraForLens(cameras, _uiState.value.selectedLens) ?: cameras.firstOrNull()
                _uiState.update { it.copy(isInitialized = true, activeCamera = target) }

                if (target != null) {
                    openCameraById(target.id)
                } else {
                    showStatus("No cameras found")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                showStatus("Init error: ${t.message}")
            }
        }
    }

    /** The surface was destroyed (app backgrounded / view removed): release the camera so others can use it. */
    fun onPreviewSurfaceDestroyed() {
        currentTexture = null
        engine.closeCamera()
    }

    private fun cameraForLens(cameras: List<CameraInfo>, lens: LensType): CameraInfo? {
        // The "2" telephoto button is a zoom on the main camera.
        val physical = if (lens == LensType.TELEPHOTO) LensType.MAIN_WIDE else lens
        return cameras.find { it.lensType == physical }
    }

    /**
     * Seamless lens selection:
     * Rear:
     *   .5 -> Ultra-Wide (ID 2)
     *   1x -> Main Wide (ID 0 at 1.0x)
     *   2  -> Main Wide at 2.0x (zoom on ID 0)
     * Front:
     *   .6 -> Front Wide (ID 3)
     *   1x -> Front Primary (ID 1)
     */
    fun selectLens(lensType: LensType) {
        if (currentTexture == null) return
        val camera = cameraForLens(_uiState.value.cameras, lensType) ?: return

        // Zoom shown in the UI vs. zoom sent to the engine (an ultra-wide/front-wide lens has no <1.0 zoom).
        val uiZoom = when (lensType) {
            LensType.ULTRA_WIDE -> 0.5f
            LensType.MAIN_WIDE -> 1.0f
            LensType.TELEPHOTO -> 2.0f
            LensType.FRONT_PRIMARY -> 1.0f
            LensType.FRONT_WIDE -> 0.6f
            else -> return
        }

        // Set zoom first so the freshly created session starts with it.
        engine.setZoomRatio(uiZoom.coerceAtLeast(1.0f))
        _uiState.update { it.copy(selectedLens = lensType, zoomRatio = uiZoom) }
        openCameraById(camera.id)
    }

    /**
     * Seamless Pinch-to-Zoom (supports both Front and Rear cameras).
     */
    fun setZoom(ratio: Float) {
        val currentLens = _uiState.value.selectedLens
        val isFront = currentLens == LensType.FRONT_PRIMARY || currentLens == LensType.FRONT_WIDE

        val clamped: Float
        val targetLens: LensType
        if (isFront) {
            clamped = ratio.coerceIn(0.6f, 5.0f)
            targetLens = if (clamped < 0.9f) LensType.FRONT_WIDE else LensType.FRONT_PRIMARY
        } else {
            clamped = ratio.coerceIn(0.5f, 8.0f)
            targetLens = when {
                clamped < 0.85f -> LensType.ULTRA_WIDE
                clamped < 2.0f -> LensType.MAIN_WIDE
                else -> LensType.TELEPHOTO
            }
        }

        if (targetLens != currentLens) {
            selectLens(targetLens)
        }
        // selectLens() resets the zoom to that lens' default; re-apply the pinch value afterwards so the
        // zoom doesn't jump back to 1.0x / 2.0x in the middle of a pinch.
        engine.setZoomRatio(clamped.coerceAtLeast(1.0f))
        _uiState.update { it.copy(zoomRatio = clamped) }
    }

    /** Tap-to-focus; (x, y) are normalised 0..1 positions inside the displayed viewfinder. */
    fun onTapToFocus(x: Float, y: Float) {
        engine.focusAt(x, y)
    }

    // Aspect Ratio Cycling (4:3 -> 16:9 -> 1:1 -> 4:3). Needs a new capture session, so the camera is re-opened.
    fun cycleAspectMode() {
        if (_uiState.value.isCapturing) return
        val next = when (_uiState.value.aspectMode) {
            AspectMode.RATIO_4_3 -> AspectMode.RATIO_16_9
            AspectMode.RATIO_16_9 -> AspectMode.RATIO_1_1
            AspectMode.RATIO_1_1 -> AspectMode.RATIO_4_3
        }
        _uiState.update { it.copy(aspectMode = next) }
        engine.setAspectMode(next)
        val cameraId = _uiState.value.activeCamera?.id
            ?: cameraForLens(_uiState.value.cameras, _uiState.value.selectedLens)?.id
        if (cameraId != null) openCameraById(cameraId)
    }

    fun toggleFrontBack() {
        val current = _uiState.value.selectedLens
        if (current == LensType.FRONT_PRIMARY || current == LensType.FRONT_WIDE) {
            selectLens(LensType.MAIN_WIDE)
        } else {
            selectLens(LensType.FRONT_PRIMARY)
        }
    }

    // Flash Mode Cycling (Auto -> On -> Torch -> Off -> Auto)
    fun cycleFlashMode() {
        val next = when (_uiState.value.flashMode) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.AUTO
        }
        _uiState.update { it.copy(flashMode = next) }
        engine.setFlashMode(next)
    }

    // Timer Mode Cycling (Off -> 3s -> 10s -> Off)
    fun cycleTimerMode() {
        _uiState.update {
            val next = when (it.timerMode) {
                TimerMode.OFF -> TimerMode.SEC_3
                TimerMode.SEC_3 -> TimerMode.SEC_10
                TimerMode.SEC_10 -> TimerMode.OFF
            }
            it.copy(timerMode = next)
        }
    }

    // Grid Mode Cycling (Off -> 3x3 -> Golden Ratio -> Off)
    fun cycleGridMode() {
        _uiState.update {
            val next = when (it.gridMode) {
                GridMode.OFF -> GridMode.GRID_3X3
                GridMode.GRID_3X3 -> GridMode.GOLDEN_RATIO
                GridMode.GOLDEN_RATIO -> GridMode.OFF
            }
            it.copy(gridMode = next)
        }
    }

    fun toggleHdrAuto() {
        _uiState.update { it.copy(isHdrAutoOn = !it.isHdrAutoOn) }
    }

    private fun openCameraById(id: String) {
        val texture = currentTexture ?: return
        val targetCam = _uiState.value.cameras.find { it.id == id }

        // Engine keeps its own copy of the controls; make sure it matches the UI before the session starts.
        engine.setFlashMode(_uiState.value.flashMode)
        syncManualControls()

        engine.openCamera(
            cameraId = id,
            texture = texture,
            onReady = {
                _uiState.update { it.copy(activeCamera = targetCam) }
            },
            onFailed = { err ->
                showStatus("Camera error: $err")
            }
        )
    }

    fun setCaptureMode(mode: CaptureMode) {
        _uiState.update {
            it.copy(
                captureMode = mode,
                isManualControlsVisible = (mode == CaptureMode.PRO)
            )
        }
        // Leaving PRO must hand exposure/focus/WB back to the camera; entering it applies the manual values.
        syncManualControls()
    }

    fun toggleManualControls() {
        _uiState.update { it.copy(isManualControlsVisible = !it.isManualControlsVisible) }
    }

    fun setIso(iso: Int?) {
        _uiState.update { it.copy(iso = iso) }
        syncManualControls()
    }

    fun setShutterSpeed(shutterNs: Long?) {
        _uiState.update { it.copy(shutterSpeedNs = shutterNs) }
        syncManualControls()
    }

    fun setEvCompensation(ev: Int) {
        _uiState.update { it.copy(evCompensation = ev) }
        syncManualControls()
    }

    fun setFocusDistance(distance: Float?) {
        _uiState.update { it.copy(focusDistance = distance) }
        syncManualControls()
    }

    fun setWhiteBalance(wbMode: Int) {
        _uiState.update { it.copy(whiteBalanceMode = wbMode) }
        syncManualControls()
    }

    fun toggleDiagnostics() {
        _uiState.update { it.copy(showDiagnostics = !it.showDiagnostics) }
    }

    fun clearStatusMessage() {
        statusJob?.cancel()
        _uiState.update { it.copy(statusMessage = null) }
    }

    /** Shows a message in the status pill and clears it again (previously error messages stayed forever). */
    private fun showStatus(message: String) {
        statusJob?.cancel()
        _uiState.update { it.copy(statusMessage = message) }
        statusJob = viewModelScope.launch {
            delay(STATUS_DURATION_MS)
            _uiState.update { it.copy(statusMessage = null) }
        }
    }

    private fun syncManualControls() {
        val state = _uiState.value
        if (state.captureMode == CaptureMode.PRO) {
            engine.setManualControls(
                iso = state.iso,
                exposureNs = state.shutterSpeedNs,
                focusDistance = state.focusDistance,
                evCompensationIndex = state.evCompensation,
                whiteBalanceMode = state.whiteBalanceMode
            )
        } else {
            engine.setManualControls(
                iso = null,
                exposureNs = null,
                focusDistance = null,
                evCompensationIndex = 0,
                whiteBalanceMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
            )
        }
    }

    /**
     * User tapped the Shutter Button.
     * Handles Timer countdown (if active) or immediate capture.
     */
    fun onShutterPressed() {
        if (_uiState.value.isCapturing) return

        // If countdown is already in progress, tapping shutter cancels it!
        if (countdownJob?.isActive == true) {
            countdownJob?.cancel()
            _uiState.update { it.copy(countdownRemaining = null) }
            return
        }

        val timer = _uiState.value.timerMode
        if (timer.seconds > 0) {
            countdownJob = viewModelScope.launch {
                for (sec in timer.seconds downTo 1) {
                    _uiState.update { it.copy(countdownRemaining = sec) }
                    triggerHapticPulse()
                    delay(1000)
                }
                _uiState.update { it.copy(countdownRemaining = null) }
                executeCapture()
            }
        } else {
            executeCapture()
        }
    }

    private fun executeCapture() {
        val state = _uiState.value
        val mode = state.captureMode
        val burstCount = when (mode) {
            CaptureMode.CAMERA -> 3
            CaptureMode.PORTRAIT -> 2
            CaptureMode.NIGHT -> 7
            CaptureMode.PRO -> 1
        }

        // The status pill already shows "Processing HDR+..." while isCapturing is true.
        _uiState.update { it.copy(isCapturing = true, processingProgress = 0.05f) }

        engine.captureFrames(
            burstCount = burstCount,
            isHdr = (state.isHdrAutoOn && mode == CaptureMode.CAMERA),
            onFramesReady = { frames ->
                viewModelScope.launch { processFrames(frames, mode) }
            },
            onError = { error ->
                _uiState.update { it.copy(isCapturing = false) }
                showStatus("Capture failed: $error")
            }
        )
    }

    private suspend fun processFrames(frames: List<ByteArray>, mode: CaptureMode) {
        try {
            val (savedUri, thumb) = ImageProcessor.processComputationalPhoto(
                context = getApplication(),
                rawFrames = frames,
                isPortrait = (mode == CaptureMode.PORTRAIT),
                isNight = (mode == CaptureMode.NIGHT),
                onProgress = { progress ->
                    _uiState.update { it.copy(processingProgress = progress) }
                }
            )

            _uiState.update {
                it.copy(
                    isCapturing = false,
                    lastCapturedUri = savedUri ?: it.lastCapturedUri,
                    lastCapturedThumbnail = thumb ?: it.lastCapturedThumbnail
                )
            }
            if (savedUri == null) showStatus("Couldn't save the photo")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Includes OutOfMemoryError. Without this the shutter stayed disabled forever (or the app died).
            Log.e(TAG, "Photo processing failed", t)
            _uiState.update { it.copy(isCapturing = false) }
            showStatus("Processing failed: ${t.javaClass.simpleName}")
        }
    }

    private fun triggerHapticPulse() {
        try {
            val app = getApplication<Application>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(50)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Opens the photo in Samsung Gallery or Google Photos.
     */
    fun openGallery(context: Context) {
        val uri = _uiState.value.lastCapturedUri
        val intents = mutableListOf<Intent>()

        if (uri != null) {
            intents.add(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        // Direct Samsung Gallery launch intent
        val samsungGallery = context.packageManager.getLaunchIntentForPackage("com.sec.android.gallery3d")
        if (samsungGallery != null) {
            intents.add(samsungGallery.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }

        // Generic photo view intent
        intents.add(Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

        // Main gallery activity intent
        intents.add(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (_: Throwable) {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        engine.closeCamera()
        engine.stopBackgroundThread()
    }
}
