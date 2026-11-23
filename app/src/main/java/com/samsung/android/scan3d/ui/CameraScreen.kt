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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        // 1. Full Powerhouse Native Sensor Viewfinder (Portrait 3:4, Zero Stretch)
        CameraViewfinder(
            onSurfaceReady = { texture ->
                viewModel.initializeCameras(texture)
            },
            onSurfaceDestroyed = {
                viewModel.onPreviewSurfaceDestroyed()
            },
            onZoomChange = { zoom ->
                viewModel.setZoom(zoom)
            },
            currentZoom = uiState.zoomRatio,
            gridMode = uiState.gridMode,
            countdownRemaining = uiState.countdownRemaining
        )

        // 2. Real Working GCam Top Controls Bar (Flash, Timer, HDR+, Grid, Diagnostics)
        GCamTopBar(
            flashMode = uiState.flashMode,
            timerMode = uiState.timerMode,
            gridMode = uiState.gridMode,
            isHdrAutoOn = uiState.isHdrAutoOn,
            onCycleFlash = { viewModel.cycleFlashMode() },
            onCycleTimer = { viewModel.cycleTimerMode() },
            onCycleGrid = { viewModel.cycleGridMode() },
            onToggleHdr = { viewModel.toggleHdrAuto() },
            onToggleDiagnostics = { viewModel.toggleDiagnostics() },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 3. Subtle Processing Toast / Status Pill
        AnimatedVisibility(
            visible = uiState.statusMessage != null || uiState.isCapturing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 68.dp)
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

        // 4. Floating GCam Zoom Switcher (.5 | 1x | 2 on Rear, .6 | 1x on Front)
        GCamZoomSwitcher(
            selectedLens = uiState.selectedLens,
            currentZoom = uiState.zoomRatio,
            isFrontCamera = isFrontCamera,
            onLensSelected = { lens -> viewModel.selectLens(lens) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // In PRO mode the slider drawer (150dp offset + ~105dp tall) sits above the shutter row,
                // so the zoom pill must clear it instead of overlapping it.
                .padding(bottom = if (uiState.captureMode == CaptureMode.PRO) 266.dp else 168.dp)
        )

        // 5. Pro Mode Sliders (Clean drawer, visible in PRO mode)
        AnimatedVisibility(
            visible = uiState.captureMode == CaptureMode.PRO,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 150.dp)
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

        // 6. Bottom Controls: Mode Carousel + Shutter + Gallery + Flip
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x80000000))
        ) {
            // Mode Carousel (NIGHT SIGHT · PORTRAIT · CAMERA · PRO)
            GCamModeCarousel(
                currentMode = uiState.captureMode,
                onModeSelected = { mode -> viewModel.setCaptureMode(mode) },
                modifier = Modifier.padding(top = 10.dp)
            )

            // Shutter row with live gallery preview and flip
            GCamBottomBar(
                thumbnail = uiState.lastCapturedThumbnail,
                isCapturing = uiState.isCapturing,
                progress = uiState.processingProgress,
                onShutterClick = { viewModel.onShutterPressed() },
                onGalleryClick = { viewModel.openGallery(context) },
                onFlipCamera = { viewModel.toggleFrontBack() }
            )
        }

        // 7. Diagnostics Dialog
        if (uiState.showDiagnostics) {
            DiagnosticsDialog(
                logs = discoveryLog,
                onDismiss = { viewModel.toggleDiagnostics() }
            )
        }
    }
}
