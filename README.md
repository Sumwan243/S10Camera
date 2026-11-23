# S10 Pro Camera (`com.samsung.android.scan3d`)

Custom computational photography camera app designed specifically for the **Samsung Galaxy S10+ (SM-G975U1, Snapdragon 855)** to bypass Samsung's locked camera features and bring open-source HDR+ multi-frame image processing to all 5 sensors.

---

## 🎯 How It Solves the S10+ Locked Features

| Locked Feature | Samsung Stock Restriction | Our Solution in this App |
| :--- | :--- | :--- |
| **Telephoto (2x Optical)** | Whitelisted only for Samsung apps; hidden from third-party Camera2 enumeration | Uses the `com.samsung.android.scan3d` package whitelist trick to unlock ID `52` / physical sub-camera + high-res 12MP center crop fallback |
| **Front Wide Angle** | Third-party apps forced into locked, cropped 1x view | Directly opens **Camera ID 3** (the secondary 8MP front sensor, 90° FoV) alongside **Camera ID 1** (10MP primary) |
| **Ultra-Wide Manual & RAW** | Hidden from standard CameraX apps | Directly interfaces with **Camera ID 2** via Camera2 API with RAW and MANUAL_SENSOR exposure controls |
| **Computational Clarity** | Samsung's legacy post-processing is unmaintained post-EOL | Custom open-source HDR+ pipeline: multi-frame burst stacking, temporal noise reduction, S-curve tone mapping, and unsharp masking |
| **Pro Controls** | Locked resolution and limited manual access | LEVEL_3 manual ISO, exposure shutter duration (down to 1/4000s and up to 2s), manual focus diopters, EV compensation, and AWB |

---

## 📱 Hardware Mapping for SM-G975U1

- **Camera ID 0**: 12.2 MP Main Wide (LEVEL_3, Full Manual, RAW, AF, OIS)
- **Camera ID 1**: 9.98 MP Front Selfie (LIMITED, Burst Capable, Dual Pixel)
- **Camera ID 2**: 15.9 MP Ultra-Wide (LIMITED, RAW, MANUAL_SENSOR, Fixed Focus)
- **Camera ID 3**: 6.5 MP Front Wide / Depth (LIMITED, 90° FoV Wide Angle)
- **Camera ID 52 / Sub**: 12 MP Telephoto (Unlocked via package whitelist)

---

## 🚀 How to Build & Install

### Option A: Open in Android Studio (Recommended)
1. Open **Android Studio**.
2. Select **Open** and choose the `S10CameraApp` folder (`c:\Users\rafae\Downloads\Projects\S10CameraApp`).
3. Allow Gradle to sync.
4. Plug in your Galaxy S10+ with **USB Debugging** enabled.
5. Click **Run (Shift + F10)** to compile and install directly.

### Option B: Build via Command Line
```powershell
cd c:\Users\rafae\Downloads\Projects\S10CameraApp
.\gradlew assembleDebug
```
The generated APK will be at:
`app/build_bin/outputs/apk/debug/app-debug.apk` (the build dir is redirected to `build_bin` in app/build.gradle.kts)

Install to your S10+ via ADB:
```powershell
adb install -r app\build_bin\outputs\apk\debug\app-debug.apk
```

---

## 📸 In-App Features

1. **Lens Switcher**:
   - `0.5`: 16MP Ultra-Wide lens
   - `1`: 12MP Main lens
   - `2`: 2x Telephoto / Super-Resolution crop
   - `1x` & `Wide`: Toggle between sharp 10MP selfie and 90° wide selfie
2. **Capture Modes**:
   - `PHOTO`: Ultra-fast single shot with high dynamic range
   - `PRO`: Manual sliders for ISO, Shutter Speed, Focus, EV, and White Balance
   - `HDR+`: 7-frame burst alignment and temporal noise reduction
   - `NIGHT`: 12-frame multi-frame accumulation for low-light clarity
3. **Hardware Diagnostics**:
   - Tap the **Info (ℹ️)** button on the top-left of the viewfinder to inspect the real-time HAL probe log and verify all discovered camera sensors.
