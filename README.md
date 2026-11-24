# S10 Pro Camera (`com.samsung.android.scan3d`)

A dedicated open-source computational photography camera app tailored for the **Samsung Galaxy S10+ (SM-G975U1, Snapdragon 855)** on Android 12 (SDK 31 / One UI 4.1).

The app uses the `com.samsung.android.scan3d` package identity to pass Samsung's HAL whitelist and access available physical sensor streams via the Camera2 API.

---

## 📸 Features

* **Lens Switching**:
  * **Rear**:
    * `0.5x`: 16 MP Ultra-Wide (Camera ID `2`, fixed focus)
    * `1.0x`: 12 MP Main Wide (Camera ID `0`, dual-pixel AF, OIS)
    * `2.0x`: High-quality crop zoom driven through the main logical camera stream (Camera ID `0`)
  * **Front**:
    * `0.6x`: 8 MP Front Wide (Camera ID `3`, 90° FoV)
    * `1.0x`: 10 MP Front Primary (Camera ID `1`, dual-pixel AF)
* **Viewfinder & Framing**:
  * Aspect ratio toggle: `4:3`, `16:9`, `1:1` (sets matching preview stream and full-res capture sizes)
  * Viewfinder composition grids: `Off`, `3x3`, `Golden Ratio`
  * Tap-to-focus and metering: Single-tap sets AF/AE metering rectangles with temporary focus lock
  * Smooth pinch-to-zoom gesture on the viewfinder
  * Mirrored preview for front-facing lenses
* **Capture Modes**:
  * `CAMERA`: Daily point-and-shoot with optional computational HDR+ burst
  * `PORTRAIT`: Defocus rendering with foreground subject retention
  * `NIGHT SIGHT`: Multi-frame exposure accumulation for low-light noise reduction
  * `PRO`: Manual sliders for ISO, Shutter Duration (ns), Focus Distance (diopters), EV Compensation, and Auto White Balance modes
* **Processing & Storage**:
  * Multi-frame burst stacking with highlight roll-off and shadow lifting
  * Saves directly to a dedicated gallery album: `DCIM/S10ProCamera`
  * In-app gallery thumbnail with direct launch into Samsung Gallery / Google Photos
* **Hardware Diagnostics**:
  * Tap the info (**ⓘ**) icon in the top bar to inspect Camera2 HAL capabilities, hardware levels, RAW sensor availability, and discovered stream resolutions.

---

## 🛠️ Building & Installing

### Requirements
* **JDK 17** (Ensure `JAVA_HOME` points to a JDK 17 installation)
* **Android SDK** with build-tools `34.0.0` and SDK 34 installed

### Build via Command Line

**Linux / macOS:**
```bash
./gradlew assembleDebug
```

**Windows (PowerShell):**
```powershell
$env:JAVA_HOME = "C:\Path\To\Jdk-17"
.\gradlew.bat assembleDebug
```

The compiled APK will be generated at:
```
app/build_bin/outputs/apk/debug/app-debug.apk
```
*(Note: Build outputs are redirected to `app/build_bin` to avoid file-lock issues on Windows.)*

### Sideloading to Device
You can install via ADB:
```bash
adb install -r app/build_bin/outputs/apk/debug/app-debug.apk
```
Or copy the APK directly to the device storage via USB / wireless file transfer and install it using Samsung's "My Files" app.

---

## 📂 Project Architecture

```
app/src/main/java/com/samsung/android/scan3d/
├── camera/
│   └── CameraEngine.kt        # Low-level Camera2 API session, surface, and HAL management
├── processing/
│   └── ImageProcessor.kt      # Computational photography pipeline, stacking, and storage
├── ui/
│   ├── CameraScreen.kt        # Main Compose layout container
│   ├── CameraViewfinder.kt    # TextureView preview with Compose gesture overlays
│   ├── CameraViewModel.kt     # Unidirectional state flow (CameraUiState)
│   ├── Components.kt          # GCam top bar, zoom switcher, bottom bar, and dialogs
│   └── theme/                 # Jetpack Compose dark theme and styling
└── MainActivity.kt            # Permission gateway and lifecycle orchestration
```

---

## 📄 License & Collaboration

See [`docs/GAME_PLAN.md`](docs/GAME_PLAN.md) for architectural phases, technical roadmap, and multi-agent division of labor.
