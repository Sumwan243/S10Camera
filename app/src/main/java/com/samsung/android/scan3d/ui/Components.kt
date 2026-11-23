@file:OptIn(ExperimentalMaterial3Api::class)

package com.samsung.android.scan3d.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.samsung.android.scan3d.camera.FlashMode
import com.samsung.android.scan3d.camera.LensType

// GCam Design Palette
private val GCamPillBg = Color(0x73000000)
private val GCamTextWhite = Color(0xFFFFFFFF)
private val GCamTextDim = Color(0x99FFFFFF)
private val GCamYellow = Color(0xFFFFD54F)

/**
 * Authentic Google Camera Zoom Switcher Pill (.5 | 1x | 2 on rear, .6 | 1x on front).
 */
@Composable
fun GCamZoomSwitcher(
    selectedLens: LensType,
    currentZoom: Float,
    isFrontCamera: Boolean,
    onLensSelected: (LensType) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GCamPillBg)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isFrontCamera) {
                GCamZoomItem(
                    label = ".5",
                    isActive = selectedLens == LensType.ULTRA_WIDE || currentZoom < 0.85f,
                    onClick = { onLensSelected(LensType.ULTRA_WIDE) }
                )
                GCamZoomItem(
                    label = "1x",
                    isActive = (selectedLens == LensType.MAIN_WIDE && currentZoom in 0.85f..1.9f),
                    onClick = { onLensSelected(LensType.MAIN_WIDE) }
                )
                GCamZoomItem(
                    label = "2",
                    isActive = selectedLens == LensType.TELEPHOTO || currentZoom >= 1.9f,
                    onClick = { onLensSelected(LensType.TELEPHOTO) }
                )
            } else {
                GCamZoomItem(
                    label = ".6",
                    isActive = selectedLens == LensType.FRONT_WIDE || currentZoom < 0.9f,
                    onClick = { onLensSelected(LensType.FRONT_WIDE) }
                )
                GCamZoomItem(
                    label = "1x",
                    isActive = selectedLens == LensType.FRONT_PRIMARY && currentZoom >= 0.9f,
                    onClick = { onLensSelected(LensType.FRONT_PRIMARY) }
                )
            }
        }
    }
}

@Composable
private fun GCamZoomItem(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isActive) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = if (isActive) Color.Black else GCamTextWhite
        )
    }
}

/**
 * GCam Mode Carousel (NIGHT SIGHT · PORTRAIT · CAMERA · PRO).
 */
@Composable
fun GCamModeCarousel(
    currentMode: CaptureMode,
    onModeSelected: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CaptureMode.values().forEach { mode ->
            val isSelected = currentMode == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) Color(0x33FFFFFF) else Color.Transparent)
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = mode.label,
                    color = if (isSelected) GCamTextWhite else GCamTextDim,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

/**
 * Clean GCam Bottom Bar: Gallery Thumbnail, White Shutter Ring, and Flip Button.
 */
@Composable
fun GCamBottomBar(
    thumbnail: Bitmap?,
    isCapturing: Boolean,
    progress: Float,
    onShutterClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onFlipCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Live Gallery Thumbnail (from dedicated album)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color(0xFF202124))
                .border(2.dp, Color(0x33FFFFFF), CircleShape)
                .clickable(onClick = onGalleryClick)
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = "Open Gallery",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = "Gallery",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 2. Google Pixel Classic White Shutter Ring
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .border(4.dp, Color.White, CircleShape)
                .padding(6.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(enabled = !isCapturing, onClick = onShutterClick)
        ) {
            if (isCapturing) {
                CircularProgressIndicator(
                    progress = progress,
                    color = Color.Black,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        // 3. Camera Flip Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color(0xFF202124))
                .clickable(onClick = onFlipCamera)
        ) {
            Icon(
                imageVector = Icons.Outlined.FlipCameraAndroid,
                contentDescription = "Flip Camera",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * Functional GCam Top Bar:
 * - Flash Mode (Auto / On / Torch / Off)
 * - Timer Mode (Off / 3s / 10s)
 * - Grid Overlay (Off / 3x3 / Golden)
 * - HDR+ Auto Indicator
 * - Hardware Diagnostics
 */
@Composable
fun GCamTopBar(
    flashMode: FlashMode,
    timerMode: TimerMode,
    gridMode: GridMode,
    isHdrAutoOn: Boolean,
    onCycleFlash: () -> Unit,
    onCycleTimer: () -> Unit,
    onCycleGrid: () -> Unit,
    onToggleHdr: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flash Button
        IconButton(
            onClick = onCycleFlash,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0x33000000))
        ) {
            val icon = when (flashMode) {
                FlashMode.AUTO -> Icons.Default.FlashAuto
                FlashMode.ON -> Icons.Default.FlashOn
                FlashMode.TORCH -> Icons.Default.Highlight
                FlashMode.OFF -> Icons.Default.FlashOff
            }
            val tint = if (flashMode != FlashMode.OFF) GCamYellow else Color.White.copy(alpha = 0.7f)
            Icon(
                imageVector = icon,
                contentDescription = "Flash: ${flashMode.name}",
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }

        // Timer Button
        IconButton(
            onClick = onCycleTimer,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0x33000000))
        ) {
            val icon = when (timerMode) {
                TimerMode.OFF -> Icons.Default.TimerOff
                TimerMode.SEC_3 -> Icons.Default.Timer3
                TimerMode.SEC_10 -> Icons.Default.Timer10
            }
            val tint = if (timerMode != TimerMode.OFF) GCamYellow else Color.White.copy(alpha = 0.7f)
            Icon(
                imageVector = icon,
                contentDescription = "Timer: ${timerMode.label}",
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }

        // HDR+ Auto Badge (Full powerhouse, always active)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (isHdrAutoOn) Color(0x33FFD54F) else Color(0x33000000))
                .clickable(onClick = onToggleHdr)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = if (isHdrAutoOn) "HDR+ AUTO" else "HDR+ OFF",
                color = if (isHdrAutoOn) GCamYellow else Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }

        // Grid Button
        IconButton(
            onClick = onCycleGrid,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0x33000000))
        ) {
            val icon = when (gridMode) {
                GridMode.OFF -> Icons.Default.GridOff
                GridMode.GRID_3X3 -> Icons.Default.Grid3x3
                GridMode.GOLDEN_RATIO -> Icons.Default.GridGoldenratio
            }
            val tint = if (gridMode != GridMode.OFF) GCamYellow else Color.White.copy(alpha = 0.7f)
            Icon(
                imageVector = icon,
                contentDescription = "Grid: ${gridMode.label}",
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }

        // Diagnostics Info Button
        IconButton(
            onClick = onToggleDiagnostics,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0x33000000))
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "Hardware Info",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Clean Pro Mode Slider Drawer (for ISO, Shutter, Focus, EV).
 */
@Composable
fun GCamProControls(
    iso: Int?,
    onIsoChange: (Int?) -> Unit,
    shutterSpeedNs: Long?,
    onShutterChange: (Long?) -> Unit,
    evCompensation: Int,
    onEvChange: (Int) -> Unit,
    focusDistance: Float?,
    onFocusChange: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    evStep: Float = 1f
) {
    var selectedParam by remember { mutableStateOf("ISO") }
    val params = listOf("ISO", "SHUTTER", "FOCUS", "EV")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            params.forEach { param ->
                val isSelected = selectedParam == param
                Text(
                    text = param,
                    color = if (isSelected) GCamYellow else GCamTextDim,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { selectedParam = param }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (selectedParam) {
            "ISO" -> {
                val isoList = listOf(null, 50, 100, 200, 400, 800, 1600, 3200)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    isoList.forEach { value ->
                        val isSel = iso == value
                        AssistChip(
                            onClick = { onIsoChange(value) },
                            label = { Text(value?.toString() ?: "Auto") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isSel) Color.White else Color(0x33FFFFFF),
                                labelColor = if (isSel) Color.Black else Color.White
                            )
                        )
                    }
                }
            }
            "SHUTTER" -> {
                val shutters = listOf(
                    null to "Auto",
                    1_000_000L to "1/1000",
                    2_000_000L to "1/500",
                    4_000_000L to "1/250",
                    8_000_000L to "1/125",
                    16_666_666L to "1/60",
                    33_333_333L to "1/30",
                    100_000_000L to "1/10",
                    500_000_000L to "1/2",
                    1_000_000_000L to "1s"
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    shutters.forEach { (ns, label) ->
                        val isSel = shutterSpeedNs == ns
                        AssistChip(
                            onClick = { onShutterChange(ns) },
                            label = { Text(label) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isSel) Color.White else Color(0x33FFFFFF),
                                labelColor = if (isSel) Color.Black else Color.White
                            )
                        )
                    }
                }
            }
            "FOCUS" -> {
                val focuses = listOf(
                    null to "AF",
                    0.0f to "Infinity",
                    2.0f to "0.5m",
                    5.0f to "0.2m",
                    10.0f to "Macro (10cm)"
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    focuses.forEach { (dist, label) ->
                        val isSel = focusDistance == dist
                        AssistChip(
                            onClick = { onFocusChange(dist) },
                            label = { Text(label) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isSel) Color.White else Color(0x33FFFFFF),
                                labelColor = if (isSel) Color.Black else Color.White
                            )
                        )
                    }
                }
            }
            "EV" -> {
                val evList = listOf(-6, -4, -2, 0, 2, 4, 6)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    evList.forEach { ev ->
                        val isSel = evCompensation == ev
                        AssistChip(
                            onClick = { onEvChange(ev) },
                            // ev is a raw AE-compensation index; show it in real EV stops.
                            label = {
                                Text(
                                    if (ev == 0) "0"
                                    else String.format(java.util.Locale.US, "%+.1f", ev * evStep)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isSel) Color.White else Color(0x33FFFFFF),
                                labelColor = if (isSel) Color.Black else Color.White
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Diagnostics & Hardware Map Dialog.
 */
@Composable
fun DiagnosticsDialog(
    logs: List<String>,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Hardware Camera Map",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF121212), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (line.startsWith("Cam")) GCamYellow else Color(0xFFD0D0D0)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        val fullLog = logs.joinToString("\n")
                        clipboardManager.setText(AnnotatedString(fullLog))
                    }) {
                        Text("COPY LOG", color = GCamYellow)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("CLOSE", color = Color.White)
                    }
                }
            }
        }
    }
}
