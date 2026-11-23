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
import kotlin.math.roundToInt

@Composable
fun CameraViewfinder(
    onSurfaceReady: (SurfaceTexture) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onZoomChange: (Float) -> Unit,
    currentZoom: Float,
    gridMode: GridMode = GridMode.OFF,
    countdownRemaining: Int? = null,
    modifier: Modifier = Modifier
) {
    // The TextureView is created once (AndroidView factory), so route callbacks through updated state.
    val latestOnSurfaceReady by rememberUpdatedState(onSurfaceReady)
    val latestOnSurfaceDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)

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
            delay(1200)
            showFocusRing = false
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize()
    ) {
        // 1. Native 4:3 viewfinder (3:4 in portrait orientation)
        // Pinch + tap are handled in Compose. The old View-level ScaleGestureDetector returned false from
        // its touch listener, so Android never delivered the second finger and pinch-zoom never worked.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
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
                    }
                }
        ) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: SurfaceTexture,
                                w: Int,
                                h: Int
                            ) {
                                // The engine picks the buffer size (it differs per camera).
                                setTransform(Matrix())
                                latestOnSurfaceReady(st)
                            }

                            override fun onSurfaceTextureSizeChanged(
                                st: SurfaceTexture,
                                w: Int,
                                h: Int
                            ) {
                                setTransform(Matrix())
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
                    if (textureView.isAvailable) {
                        textureView.setTransform(Matrix())
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Real Grid Overlay (3x3 or Golden Ratio)
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

            // 3. Tap-to-Focus Ring Indicator
            // tapOffset is in pixels; the old code divided by a hard-coded 2.7 "density", which put the ring
            // in the wrong place on every screen that isn't exactly 2.7x.
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

            // 4. Timer Countdown Display (Large authentic GCam numerals)
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
}
