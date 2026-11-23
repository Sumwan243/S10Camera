package com.samsung.android.scan3d

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.samsung.android.scan3d.ui.CameraScreen
import com.samsung.android.scan3d.ui.CameraViewModel
import com.samsung.android.scan3d.ui.theme.DarkSurface
import com.samsung.android.scan3d.ui.theme.DarkSurfaceElevated
import com.samsung.android.scan3d.ui.theme.GoldAccent
import com.samsung.android.scan3d.ui.theme.S10ProCameraTheme
import com.samsung.android.scan3d.ui.theme.ShutterRed
import com.samsung.android.scan3d.ui.theme.WhiteText
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // Declared in the manifest but never requested before, so the gallery thumbnail could not see
        // photos from earlier installs / other apps.
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private var hasPermissionsState by mutableStateOf(false)
    private var previousCrashLog by mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        hasPermissionsState = cameraGranted
        if (!cameraGranted) {
            Toast.makeText(this, "Camera permission is required to operate this app", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()

        // Check for previous crash log
        try {
            val crashFile = File(filesDir, "latest_crash.txt")
            if (crashFile.exists()) {
                previousCrashLog = crashFile.readText()
            }
        } catch (_: Exception) {}

        hasPermissionsState = checkCameraPermission()

        setContent {
            S10ProCameraTheme {
                if (!hasPermissionsState) {
                    PermissionScreen(
                        previousCrash = previousCrashLog,
                        onClearCrashLog = {
                            try {
                                File(filesDir, "latest_crash.txt").delete()
                                previousCrashLog = null
                            } catch (_: Exception) {}
                        },
                        onRequestPermissions = {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    )
                } else {
                    CameraScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        hasPermissionsState = checkCameraPermission()
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }
        } catch (_: Exception) {}
    }
}

@Composable
private fun PermissionScreen(
    previousCrash: String?,
    onClearCrashLog: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "S10 Pro Camera",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = GoldAccent
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Unlocking 5 Sensors + HDR+ Pipeline",
                fontSize = 13.sp,
                color = WhiteText.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(28.dp))

            if (previousCrash != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "⚠ Previous Crash Detected",
                            color = ShutterRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = previousCrash,
                            color = WhiteText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            maxLines = 10
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(previousCrash))
                                copied = true
                            }) {
                                Text(if (copied) "COPIED!" else "COPY LOG", color = GoldAccent, fontSize = 11.sp)
                            }
                            TextButton(onClick = onClearCrashLog) {
                                Text("DISMISS", color = WhiteText.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            Text(
                text = "Camera permission is required to stream from Samsung S10+ lenses.",
                color = WhiteText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = GoldAccent, contentColor = Color.Black),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(text = "GRANT PERMISSIONS & START", fontWeight = FontWeight.Bold)
            }
        }
    }
}
