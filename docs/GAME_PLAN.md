# S10 Pro Camera – Game Plan

Goal: a dedicated camera app for the Galaxy S10+ whose **post-processing** (sharpness, dynamic range, low light)
is the main selling point, now that Samsung has stopped shipping updates.

---

## 1. Ground rules

### Who owns what (so two AIs don't collide)

| Area | Owner | Files |
|---|---|---|
| Camera engine, capture, RAW, session management | **Claude** | `camera/CameraEngine.kt` |
| Image pipeline (align, merge, denoise, tone, sharpen), OpenCV / NDK | **Claude** | `processing/**`, `app/src/main/cpp/**` |
| UI polish, settings, tests, CI, docs, housekeeping | **Gemini** (briefs in section 5) | `ui/**` (except viewfinder gesture/transform code), `settings/**`, `.github/**`, `docs/**`, tests |

Rules for the non-Claude contributor:
1. **Do not edit** `CameraEngine.kt` or anything in `processing/`. If you need something from them, add a
   `// TODO(claude): need X` comment and note it in the PR description.
2. One task = one branch = one PR. Branch names: `gemini/<task-id>`. Claude uses `claude/<topic>`.
3. Every PR must build (`./gradlew assembleDebug`) and must not add warnings about unresolved references.
4. Don't reformat files you aren't otherwise changing (keeps diffs reviewable).
5. UI state lives in `CameraUiState` / `CameraViewModel`. Add fields there; never keep camera state in composables.

### The contract between UI and pipeline
The pipeline is a black box to the UI. Claude will keep this signature stable:

```kotlin
ImageProcessor.processComputationalPhoto(
    context, rawFrames: List<ByteArray>, isPortrait: Boolean, isNight: Boolean, onProgress: (Float) -> Unit
): Pair<Uri?, Bitmap?>
```
New options (e.g. sharpening strength) are added as extra parameters **with defaults**, so UI code never breaks.

---

## 2. Reality check on "borrowing from GCam"

* **Google Camera / Pixel Camera is closed source.** "GCam mods/ports" are patched copies of Google's proprietary APK.
  There is no source to borrow, and shipping modified Google binaries inside your own app is not an option.
* **What *is* public:** the algorithms.
  * Hasinoff et al., *Burst photography for high dynamic range and low-light imaging on mobile cameras* (HDR+, SIGGRAPH Asia 2016).
  * Wronski et al., *Handheld Multi-Frame Super-Resolution* (Super Res Zoom, 2019).
  * A reference implementation exists: the IPOL paper and `amonod/hdrplus-python` (Python, works on RAW DNG bursts).
    Its licence is **AGPL-3.0** and the README mentions a possibly related patent (US9077913B2).
    Copying code from it would force this app to be AGPL; re-implementing from the papers avoids that. Not legal advice.
* **Why GCam looks "crystal sharp"** (it's not one trick):
  1. It merges **RAW** frames (before the phone's noise reduction / sharpening destroys detail),
  2. aligns them per tile and merges robustly (no ghosting),
  3. demosaics and tone-maps with locally adaptive curves, then
  4. applies tuned sharpening, plus Super-Res for zoom, plus ML for portrait.
  Our current app does none of 1-4. It averages already-processed JPEGs.

---

## 3. Gate 0 – find out what the hardware gives us (do this first)

Open the app's diagnostics panel (the info icon) on the S10+ and note, per camera ID (0, 1, 2, 3):
`hasRawCapture`, `hasManualSensor`, `hasBurstCapture`, hardware level, and the logical/physical camera IDs.

* If **RAW_SENSOR is available** on ID 0 (and ideally 2): Phase 2 (real HDR+) is possible.
* If **not**: we stay on the JPEG path and compensate with better alignment, ghost rejection and sharpening (Phase 1 + 3),
  and the expectation must be "better than now", not "Pixel".

---

## 4. Phases (Claude)

**Phase 0 – Foundation (done, needs on-device verification)**
Crash/OOM fix, orientation, per-camera preview sizes, aspect ratios (4:3 / 16:9 / 1:1), mirrored selfie preview,
real tap-to-focus, pinch zoom, atomic UI state. *Exit criterion: builds, previews on all four cameras, photos upright.*

**Phase 1 – OpenCV JPEG-burst pipeline** (`org.opencv:opencv` from Maven Central; restrict to `arm64-v8a`)
1. Pick the **sharpest frame** as reference (variance of Laplacian), not frame 0.
2. **Align** the others to it (downscaled ECC / feature-based affine, then warp at full resolution).
3. **Robust merge**: per-pixel weight falls off with difference to the reference (ghost rejection); trimmed mean for static areas.
4. **Detail recovery**: edge-aware denoise + unsharp/guided sharpening, strength tied to frame count and ISO.
5. **Local tone mapping** (CLAHE on luminance / exposure fusion) replacing the single global curve.
6. All big buffers are OpenCV `Mat`s (native memory), so no Java-heap pressure.
*Exit criterion: a handheld 3-7 frame burst is visibly sharper and less noisy than a single frame, with no ghost edges.*

**Phase 2 – RAW HDR+ (only if Gate 0 says RAW works)**
Capture `RAW_SENSOR` + `CaptureResult` metadata (black level, CCM, WB gains), tile-based alignment and merge in the Bayer
domain (NDK C++ via OpenCV's prefab), own demosaic/finishing, write DNG + JPEG.

**Phase 3 – Features that need the pipeline**
Real Night mode (longer per-frame exposures, not 7 short ones), exposure-bracketed HDR, Super-Res zoom for the "2x" button
(there is no usable telephoto, so this is where real 2x quality comes from), ML subject segmentation for portrait.

**Phase 4 – Video / extras**: only after photo quality is good.

---

## 5. Briefs for Gemini (small, independent, UI/housekeeping)

Each brief lists the goal, the files you may touch, and **acceptance criteria**.

### G1 – Repo hygiene  *(touch: `.gitignore`, `README.md`, repo root only)*
* Keep the `.gitignore` provided in this repo. Run `git rm --cached local.properties` and anything under `build_bin/` if tracked.
* Rewrite `README.md` so it matches reality: remove hard-coded Windows paths, APK output is `app/build_bin/outputs/apk/debug/app-debug.apk`,
  remove the claim that the telephoto lens is unlocked (the "2" button is a digital zoom on the main camera),
  list the real features: lens switching, aspect ratios, Pro mode, tap-to-focus, burst stacking.
* **Done when:** a fresh clone builds with `./gradlew assembleDebug` and the README steps are accurate.

### G2 – CI  *(touch: `.github/workflows/build.yml`)*
* GitHub Actions: JDK 17, `android-actions/setup-android`, run `./gradlew assembleDebug` and `./gradlew lintDebug` on every push/PR; upload the debug APK as an artifact.
* **Done when:** the workflow is green on `main` and the APK is downloadable from the run.

### G3 – Permission UX  *(touch: `MainActivity.kt`, `ui/PermissionScreen*` only)*
* If the camera permission was permanently denied, the button must open the app's system settings (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`)
  instead of silently doing nothing.
* Stop requesting `RECORD_AUDIO` until video exists. Show the saved crash log (currently only visible on the permission screen) in a dismissible dialog on next launch.
* **Done when:** deny-twice flow lands in settings; after granting there, returning to the app shows the camera.

### G4 – Settings screen  *(touch: new `settings/**`, `ui/SettingsScreen.kt`, `CameraUiState` additions)*
* Jetpack DataStore. Options: JPEG quality (90-100), default grid, default timer, shutter sound on/off, save-selfie-mirrored on/off, volume-key = shutter.
* Reachable from a gear icon in the top bar. Expose values through the ViewModel as a `StateFlow`; **do not** read DataStore in composables.
* Pipeline hooks: `jpegQuality` and `mirrorSelfie` are consumed by Claude later; just expose them in a `UserSettings` data class.
* **Done when:** values persist across app restarts and the screen has no hard-coded strings (use `strings.xml`).

### G5 – On-screen aids  *(touch: new `ui/overlay/**`)*
* Horizon level indicator from the rotation-vector sensor (green when within 1 degree), toggled with the grid button cycle.
* Optional histogram overlay (needs a small `ImageAnalysis`-style tap on the preview; coordinate with Claude before touching camera code: just stub an interface `PreviewFrameListener`).
* **Done when:** the level works in portrait, costs no visible jank, and is off by default.

### G6 – Unit tests  *(touch: `app/src/test/**`)*
* Pure-Kotlin tests for `ImageProcessor` tone curve behaviour (white maps to white, black stays black, curve is monotonic) and `AspectMode.matches`.
* **Done when:** `./gradlew testDebugUnitTest` is green in CI.

### G7 – Accessibility & strings  *(touch: `ui/**` text only, `res/values/strings.xml`)*
* Move all user-visible strings to `strings.xml`; add `contentDescription` to every icon button; minimum touch target 48dp.
* **Done when:** no hard-coded `Text("...")` literals remain in `ui/`.

---

## 6. Risks

| Risk | Mitigation |
|---|---|
| RAW not exposed to third-party apps on this device | Gate 0; JPEG pipeline is still a big improvement |
| OpenCV adds tens of MB to the APK | `abiFilters += "arm64-v8a"` only (the S10+ Snapdragon is arm64) |
| Processing takes too long | Downscaled alignment, strip processing, progress UI, background service |
| Code that has never been compiled/run here | Each phase ends with an on-device check; CI (G2) catches compile errors early |
