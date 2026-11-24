package com.samsung.android.scan3d.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The live preview. It simply fills whatever box the parent gives it: the PARENT is responsible for sizing
 * that box to [contentAspect] (see CameraScreen), so the picture is never stretched. As a safety net the
 * TextureView transform below also corrects any residual mismatch (e.g. for the frame or two between an
 * aspect-ratio change and the new camera session starting) by centre-cropping instead of distorting.
 *
 * @param contentAspect displayed (portrait) width / height of the camera stream, e.g. 0.75 for 4:3.
 * @param mirror        mirror horizontally (selfie camera), like every stock camera app does.
 * @param onTapToFocus  receives the tap position normalised to 0..1 within the viewfinder.
 */
@Composable
fun CameraViewfinder(
    onSurfaceReady: (SurfaceTexture) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onZoomChange: (Float) -> Unit,
    onTapToFocus: (Float, Float) -> Unit,
    currentZoom: Float,
    contentAspect: Float,
    mirror: Boolean,
    gridMode: GridMode = GridMode.OFF,
    countdownRemaining: Int? = null,
    modifier: Modifier = Modifier
) {
    // The TextureView is created once (AndroidView factory), so route callbacks/values through updated state.
    val latestOnSurfaceReady by rememberUpdatedState(onSurfaceReady)
    val latestOnSurfaceDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)
    val latestOnTapToFocus by rememberUpdatedState(onTapToFocus)
    val latestContentAspect by rememberUpdatedState(contentAspect)
    val latestMirror by rememberUpdatedState(mirror)

    var pinchZoom by remember { mutableFloatStateOf(currentZoom) }
    var tapOffset by remember { mutableStateOf<Offset?>(null) }
    var tapId by remember { mutableIntStateOf(0) }
    var showFocusRing by remember { mutableStateOf(false) }

    LaunchedEffect(currentZoom) {
        pinchZoom = currentZoom
    }

    // Keyed on a counter so tapping the same spot twice still re-triggers the ring.
    LaunchedEffect(tapId) {
        if (tapId > 0) {
            showFocusRing = true
            delay(1500)
            showFocusRing = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Pinch + tap are handled in Compose. The old View-level ScaleGestureDetector returned false from
            // its touch listener, so Android never delivered the second finger and pinch-zoom never worked.
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) {
                        pinchZoom = (pinchZoom * zoom).coerceIn(0.5f, 8.0f)
                        latestOnZoomChange(pinchZoom)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    tapOffset = offset
                    tapId++
                    if (size.width > 0 && size.height > 0) {
                        latestOnTapToFocus(offset.x / size.width, offset.y / size.height)
                    }
                }
            }
    ) {
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            // The engine picks the buffer size (it differs per camera and aspect ratio).
                            applyPreviewTransform(latestContentAspect, latestMirror)
                            latestOnSurfaceReady(st)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            applyPreviewTransform(latestContentAspect, latestMirror)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            // Stop the camera before the texture is released.
                            latestOnSurfaceDestroyed()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            update = { textureView ->
                textureView.applyPreviewTransform(contentAspect, mirror)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Real Grid Overlay (3x3 or Golden Ratio)
        if (gridMode != GridMode.OFF) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val gridColor = Color.White.copy(alpha = 0.28f)
                val stroke = 1.dp.toPx()

                if (gridMode == GridMode.GRID_3X3) {
                    // Rule of Thirds
                    drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
                    drawLine(gridColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), stroke)
                    drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
                    drawLine(gridColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), stroke)
                } else if (gridMode == GridMode.GOLDEN_RATIO) {
                    // Golden Ratio (0.382 and 0.618)
                    val gx1 = w * 0.382f
                    val gx2 = w * 0.618f
                    val gy1 = h * 0.382f
                    val gy2 = h * 0.618f
                    drawLine(gridColor, Offset(gx1, 0f), Offset(gx1, h), stroke)
                    drawLine(gridColor, Offset(gx2, 0f), Offset(gx2, h), stroke)
                    drawLine(gridColor, Offset(0f, gy1), Offset(w, gy1), stroke)
                    drawLine(gridColor, Offset(0f, gy2), Offset(w, gy2), stroke)
                }
            }
        }

        // Tap-to-focus ring. tapOffset is in pixels, so convert with the real density
        // (the old code divided by a hard-coded 2.7).
        val ring = tapOffset
        if (showFocusRing && ring != null) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            ring.x.roundToInt() - 32.dp.roundToPx(),
                            ring.y.roundToInt() - 32.dp.roundToPx()
                        )
                    }
                    .size(64.dp)
                    .border(1.5.dp, Color(0xFFFFD54F), CircleShape)
            )
        }

        // Timer Countdown Display (Large authentic GCam numerals)
        if (countdownRemaining != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = "$countdownRemaining",
                    color = Color.White,
                    fontSize = 100.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * A TextureView stretches the camera buffer to fill the view. If the view's aspect differs from the stream's,
 * undo that stretch by scaling (which centre-crops) so the image is never distorted; then mirror for selfies.
 */
private fun TextureView.applyPreviewTransform(contentAspect: Float, mirror: Boolean) {
    val vw = width.toFloat()
    val vh = height.toFloat()
    if (vw <= 0f || vh <= 0f) return

    val cx = vw / 2f
    val cy = vh / 2f
    val viewAspect = vw / vh
    val matrix = Matrix()

    if (contentAspect > 0f && abs(viewAspect - contentAspect) > 0.005f) {
        if (viewAspect > contentAspect) {
            matrix.postScale(1f, viewAspect / contentAspect, cx, cy)
        } else {
            matrix.postScale(contentAspect / viewAspect, 1f, cx, cy)
        }
    }
    if (mirror) {
        matrix.postScale(-1f, 1f, cx, cy)
    }
    setTransform(matrix)
}
