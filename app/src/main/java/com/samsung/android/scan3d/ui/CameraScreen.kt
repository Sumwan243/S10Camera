package com.samsung.android.scan3d.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samsung.android.scan3d.camera.LensType

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val discoveryLog by viewModel.discoveryLog.collectAsState()

    val isFrontCamera = uiState.selectedLens == LensType.FRONT_PRIMARY ||
            uiState.selectedLens == LensType.FRONT_WIDE

    // Layout: top bar / viewfinder / bottom controls are stacked instead of overlaid, so nothing covers the
    // picture. The viewfinder gets all remaining height and is sized to the REAL stream aspect ratio
    // (4:3, 16:9 or 1:1); if the screen is too short for it, it shrinks (letterboxes) instead of stretching.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        // 1. Top Controls Bar (Flash, Timer, HDR+, Aspect, Grid, Diagnostics)
        GCamTopBar(
            flashMode = uiState.flashMode,
            timerMode = uiState.timerMode,
            gridMode = uiState.gridMode,
            aspectMode = uiState.aspectMode,
            isHdrAutoOn = uiState.isHdrAutoOn,
            onCycleFlash = { viewModel.cycleFlashMode() },
            onCycleTimer = { viewModel.cycleTimerMode() },
            onCycleGrid = { viewModel.cycleGridMode() },
            onCycleAspect = { viewModel.cycleAspectMode() },
            onToggleHdr = { viewModel.toggleHdrAuto() },
            onToggleDiagnostics = { viewModel.toggleDiagnostics() },
            modifier = Modifier.fillMaxWidth()
        )

        // 2. Viewfinder area (takes all the space between the bars, preview centred in it)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.aspectRatio(uiState.previewAspect)) {

                CameraViewfinder(
                    onSurfaceReady = { texture -> viewModel.initializeCameras(texture) },
                    onSurfaceDestroyed = { viewModel.onPreviewSurfaceDestroyed() },
                    onZoomChange = { zoom -> viewModel.setZoom(zoom) },
                    onTapToFocus = { x, y -> viewModel.onTapToFocus(x, y) },
                    currentZoom = uiState.zoomRatio,
                    contentAspect = uiState.previewAspect,
                    mirror = isFrontCamera,
                    gridMode = uiState.gridMode,
                    countdownRemaining = uiState.countdownRemaining
                )

                // Processing toast / status pill
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.statusMessage != null || uiState.isCapturing,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x99000000))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = uiState.statusMessage ?: "Processing HDR+...",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }

                // Floating zoom switcher (.5 | 1x | 2 on Rear, .6 | 1x on Front), anchored to the picture's bottom edge
                GCamZoomSwitcher(
                    selectedLens = uiState.selectedLens,
                    currentZoom = uiState.zoomRatio,
                    isFrontCamera = isFrontCamera,
                    onLensSelected = { lens -> viewModel.selectLens(lens) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // In PRO mode the slider drawer occupies the bottom ~105dp of the picture.
                        .padding(bottom = if (uiState.captureMode == CaptureMode.PRO) 116.dp else 14.dp)
                )

                // Pro Mode Sliders (drawer over the bottom of the picture, visible in PRO mode)
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.captureMode == CaptureMode.PRO,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it }),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    GCamProControls(
                        iso = uiState.iso,
                        onIsoChange = { viewModel.setIso(it) },
                        shutterSpeedNs = uiState.shutterSpeedNs,
                        onShutterChange = { viewModel.setShutterSpeed(it) },
                        evCompensation = uiState.evCompensation,
                        evStep = uiState.activeCamera?.evCompensationStep ?: 1f,
                        onEvChange = { viewModel.setEvCompensation(it) },
                        focusDistance = uiState.focusDistance,
                        onFocusChange = { viewModel.setFocusDistance(it) }
                    )
                }
            }
        }

        // 3. Bottom Controls: Mode Carousel + Shutter + Gallery + Flip
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            GCamModeCarousel(
                currentMode = uiState.captureMode,
                onModeSelected = { mode -> viewModel.setCaptureMode(mode) },
                modifier = Modifier.padding(top = 10.dp)
            )

            GCamBottomBar(
                thumbnail = uiState.lastCapturedThumbnail,
                isCapturing = uiState.isCapturing,
                progress = uiState.processingProgress,
                onShutterClick = { viewModel.onShutterPressed() },
                onGalleryClick = { viewModel.openGallery(context) },
                onFlipCamera = { viewModel.toggleFrontBack() }
            )
        }
    }

    // Diagnostics Dialog
    if (uiState.showDiagnostics) {
        DiagnosticsDialog(
            logs = discoveryLog,
            onDismiss = { viewModel.toggleDiagnostics() }
        )
    }
}
