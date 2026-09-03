# AGENTS.md — CineCam

Single-module native Android app (`:app`, `com.cinecam.app`). Offline-first, sideloaded personal build, no backend/network. CameraX 1.4.0 + Camera2Interop, XML views + viewBinding (no Compose). PRD: `cinecam-android-prd.md`; product constraints: `PRODUCT.md`.

## Build & release

- `./gradlew assembleDebug` locally. Push to `main` triggers `.github/workflows/auto-apk.yml` → debug APK published as prerelease `auto-<sha>` (stable releases stay manual; the in-app updater in `UpdateChecker.kt` only prompts for stable via `releases/latest`).

## Build

- `./gradlew assembleDebug` (AGP 8.5.2, Kotlin 2.0.21, Java 17, `compileSdk/targetSdk 35`, `minSdk 29`). Needs `local.properties` `sdk.dir`; install/run needs a device or emulator — no unit (`app/src/test`) or instrumentation tests exist.
- `./gradlew assembleRelease` works but `isMinifyEnabled=false`; quality toggle FHD/UHD (falls back with toast if unsupported — test Realme maxes at FHD), bitrate from UI (`12/20/40/60/80` Mbps).- No CI, lint, or formatting config beyond `kotlin.code.style=official`.

## Where things live

- `app/src/main/java/com/ankush/cinecam/MainActivity.kt` (~1300 lines) — nearly all logic: permissions, CameraX bind (Preview + VideoCapture + ImageAnalysis), manual capture state, recording, LUT grade, readouts. Edit here for behavior.
- `MonitoringOverlayView.kt` — preview-only overlay (`NONE/ZEBRA/PEAKING/FALSE_COLOR`); density/visibility driven by real `FrameStats` when available, illustrative fallback when null. Never touches recordings.
- `FrameStats.kt` — `ExposureAnalyzer` (ImageAnalysis, 320x240, ~8Hz): luma histogram, over/under fractions, sharpness, 6 IRE zones. Best-effort: bind retries capture-only if analysis is refused.
- `HistogramView.kt` — preview-only luma histogram (red=clip, blue=crush).
- `FrameGuidesView.kt` — preview-only framing guides (OFF/16:9/2.39:1/1:1/4:3/9:16) + thirds grid.
- `FocusIndicatorView.kt` — tap-to-focus brackets (fades 1.2s). Focus itself: single `startFocusAndMetering(FLAG_AF)` under momentary `AF_MODE_AUTO`, then the mode is LEFT at AUTO because a completed AUTO scan holds the lens (that is the lock) — re-applying manual state would slew back to the slider distance (snap-back bug). Never continuous AF. Works while recording. Touch listener lives on `focusIndicator` (topmost layer) for `ACTION_DOWN` — `ACTION_UP` never arrives through non-clickable overlays. `AF_MODE_AUTO` future is awaited before triggering (triggering under `OFF` is a no-op). Box is white while scanning, green on lock, red on failure.
- Guides are framing-only; only 16:9/4:3 change the actual encode. Recording 2.39:1/1:1/9:16 needs a custom crop/encoder path that doesn't exist.
- Guides are framing-only (OFF/16:9/2.39:1/1:1/4:3/9:16); only 16:9/4:3 change the actual encode. Recording 2.39:1/1:1/9:16 needs a custom crop/encoder path that doesn't exist.
- `AudioMeter.kt` — parallel AudioRecord monitor (peak/RMS ~8Hz, gain-scaled, prefers external device); never touches `Recorder` audio. Falls back to `sin()` sim when permission/stream refused.
- `CubeLut.kt` — `.cube` parser + trilinear `sample()`; default `res/raw/cinelog_to_rec709.cube`. Library = all `*.cube` in `getExternalFilesDir(DOCUMENTS)` (`cinecam.cube` first), Load button cycles. Preview grade is a neutral-axis-average tint approximation, not per-pixel — full GPU LUT still needs the PRD §8 pipeline.
- Layout/strings: `res/layout/activity_main.xml`, `res/layout-land/activity_main.xml` (twin with identical IDs+types — keep in sync), `res/values/`. Theme (`themes.xml`) is dark-chrome only: neutral chips, white sliders, red reserved for REC; audio meters are green.

## Gotchas agents miss

- **Preview-only vs recorded:** overlays (`monitoringOverlay`) and grade tint (`previewGradeOverlay`) never touch the recorded buffer. The PRD's "single GPU shader pass" is aspirational — current code is CameraX `Preview` + `VideoCapture<Recorder>` with `View` overlays. Don't describe it as GPU/zero-copy.
- **Meters are real, recording path untouched:** `AudioMeter` drives the UI meters; `micGain` scales them and mic source sets `Recorder.setAudioSource(CAMCORDER/MIC)` + monitoring preferred device — but there is deliberately no DSP/limiter on the recorded track. AGSL/HDR switches are disabled + dimmed where unsupported (SDK <33); HDR10 uses reflection on `DynamicRange.HLG_10_BIT`, SDR fallback otherwise.
- **Manual-control gating:** requires `MANUAL_SENSOR` capability + `FULL`/`LEVEL_3` hardware level (`evaluateManualSupport`); else sliders disabled + auto fallback (`AE_ON/AWB_AUTO/AF_CONTINUOUS_VIDEO`) with `MANUAL UNSUPPORTED` state. Never assume manual mode is active.
- **SeekBars commit on release:** `simpleSeek` applies only in `onStopTrackingTouch`. FPS/bitrate/quality/lens/mic-source/HDR force a rebind, refused while recording (toast + those controls disabled via `setRebindControlsEnabled(false)`).
- **Recording:** `Recorder` → `MediaStoreOutputOptions` to `Movies/CineCam`, filename `cinecam_<ts>_<fps>fps_<br>mbps_<FHD|UHD>_<169|43>_<front|back>_iso<iso>_<wb>k`. 60fps/UHD validated before apply (`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`, `QualitySelector.getResolution`), toast + revert if unsupported. Aspect 16:9/4:3 sets both `Recorder` and `Preview` aspect (real rebind). Zoom is live `setZoomRatio` (clamped to lens max); torch re-applied every bind + flash-unit gated. Preview EIS switch gated by `queryEisForLens` (Camera2 stabilization modes) — never request what the lens lacks or bind throws. No recording-stabilization API exists in CameraX 1.4.0 (only a getter), so don't add that toggle. Camera denial deep-links to settings; mic denial records video-only. State (incl. aspect/zoom/torch/stab/tint/guides/grid/AE-lock/LUT index) survives rotation; P1–P3 presets live in `cinecam_presets` SharedPreferences (tap recall, long-press save).
- **4K/60fps are CameraX-blocked on the test Realme, not app bugs:** HW AVC encoder does 4096x2160 and HAL lists 3840x2160, but `QualitySelector` (both `CAMCORDER_PROFILE` and `CODEC_CAPABILITIES` sources) maxes at FHD; AE ranges cap at `[30,30]` (60fps needs a high-speed session CameraX 1.4.0 can't create). Fallback toasts are correct behavior; real 4K/60 needs a custom MediaCodec pipeline.
