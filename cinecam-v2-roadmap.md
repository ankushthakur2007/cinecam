# CineCam V2 — Roadmap to production-grade mobile cinematography

**Status:** Proposal v1, 2026-09-24
**Owner:** Ankush Thakur
**Decisions this document assumes:** shoot on the Realme RMX2061 today and a flagship later · staged architecture (max out CameraX first, then a custom GPU/encoder path) · fix all three departments — picture, monitoring, sound — plus output ceiling, ergonomics and reliability.

Companion docs: [`cinecam-android-prd.md`](cinecam-android-prd.md) (v1 PRD), [`PRODUCT.md`](PRODUCT.md), [`AGENTS.md`](AGENTS.md).

---

## 1. Verdict

CineCam today is a competent *control-surface prototype*, not a camera you can put on a shoot. The reason is not missing features — it is that three of them are not what they claim to be, and a fourth does not exist:

1. **The monitoring lies.** Zebra, peaking and false color are Android `View` drawings whose *density and colour* are modulated by aggregate frame statistics ([MonitoringOverlayView.kt:59-163](app/src/main/java/com/cinecam/app/MonitoringOverlayView.kt#L59-L163)). Zebra stripes cover the whole frame when *anything* clips; peaking draws a grid of squares whose spacing follows mean sharpness; false color paints six stacked bands sized by zone population. None of them tell you *where* in the frame the problem is. A cinematographer cannot expose or focus to them, so they are decorative telemetry, and the `CLIP H/L %` readout is the only honest number on screen.
2. **The grade is a tint.** `applyPreviewLutState()` averages the LUT along the neutral axis and sets a translucent background colour up to alpha 120 on a `View` ([MainActivity.kt:1433-1473](app/src/main/java/com/cinecam/app/MainActivity.kt#L1433-L1473)). Swapping `.cube` files changes the cast of one flat rectangle. LOG/LIN is a different fixed rectangle. Nothing on screen predicts the file.
3. **The meters are real, the track is not yours.** `AudioMeter` samples a parallel `AudioRecord` honestly, but the recording path gets `MediaRecorder.AudioSource.CAMCORDER` with no gain staging, no limiter, no 48 kHz/PCM choice — `micGain` scales the *display* only ([AudioMeter.kt](app/src/main/java/com/cinecam/app/AudioMeter.kt), [MainActivity.kt:978-981](app/src/main/java/com/cinecam/app/MainActivity.kt#L978-L981)). Metered clipping cannot be prevented.
4. **The workflow stops at "it saved to Movies/CineCam".** `.cube` files must be pushed to an app-private Documents path (no picker), there is no playback/review, no pause/resume, no take metadata, no proxy, no storage target choice, no thermal or storage-runway guard, and no way to know before you press REC what the device can actually do.

Plus the ceiling everyone hits: FHD/AVC/8-bit via CameraX's quality table, 60 fps refused, and on this specific phone that is genuinely a CameraX limitation (documented in `AGENTS.md`) — **not** a hardware one.

The good news from research (§2, §4): most of what separates CineCam from Blackmagic Camera and MotionCam Pro is *honesty, monitoring and workflow*, and a large part of the encoding gap is now reachable with APIs that already exist. That is Phase A. The genuinely hard 15 % — per-pixel GPU grade, 4K/60, RAW/LOG — is Phase B and only needs to be built once, behind a flag.

---

## 2. Market reality, September 2026

| App | State now | What it sets as the bar |
|---|---|---|
| **Blackmagic Camera** (free, Android) | v3.4 July 2026, updated 3 Sep 2026; 1 M+ installs; **Android 13+, restricted to specific Samsung Galaxy / Pixel models** | Full manual HUD (tap a value → dial), ISO/shutter-angle/WB/tint/FPS/Lens/zoom, false color + zebras + peaking + **waveform** + RGB histogram, frame guides, LUT import/export, presets import/export, colour spaces (709 / 2020 / P3 / BT2020-HLG10 / HDR10 / Log / ACES), codec + quality ladder (H.264 / HEVC / ProRes on iOS) with auto-generated 1080p HEVC **proxies**, PCM audio option, pause/continue recording, 4K HDMI out, WearOS + REST-API multi-camera control |
| **MotionCam Pro** (paid, V5 Aug 2026) | Android-only, RAW-capable devices | **RAW video (MCRAW)**, **DirectLog** (real-time RAW→LOG), APV + HEVC, ACES pipeline, **RAW histogram**, **per-RGB-channel sensor clipping map**, false color, peaking, **"Direct Preview" — see the exact pipeline before recording**, binning mode, automated rack focus, desktop MotionCam Tools for render |
| **Filmic Pro** (Bending Spoons) | iOS 7.5.35 (8 Sep 2026) = maintenance only; **Android 7.6.4, not updated since 5 Nov 2025** | Still the UX reference for draggable focus/exposure reticles, dedicated wheels, in-app gallery + trim + favourites, icon-first chrome. Effectively stalled on Android |
| **Open Camera / Cinema FV-5 / MCPro** | alive, dated UI | External-SSD and SD-card recording, USB mic selection, huge format matrix |
| **CameraW** (GPLv3, new) | research-grade OSS | Proof the "CameraX can't" wall is a *library* wall: custom GLES pipeline, 10-bit HEVC, **HDR10+ with per-frame dynamic metadata**, log profiles (Apple Log / S-Log3 / V-Log / Log-C3) with baked LUT, and **hardware-encoder "desqueeze"** via custom sample-aspect-ratio to record sensors that exceed MediaCodec's 4096-wide cap |

Sources: [ProVideo Coalition on BMD Android 3.4](https://www.provideocoalition.com/blackmagic-camera-for-android-3-4-update-released/) · [BMD tech specs](https://www.blackmagicdesign.com/products/blackmagiccamera/techspecs/W-APP-02) · [9to5Google BMD Android hands-on](https://9to5google.com/2024/07/31/blackmagic-camera-for-android-hands-on/) · [motioncamapp.com](https://motioncamapp.com/) + [Play listing](https://play.google.com/store/apps/details?id=com.motioncam.pro) · [what-happened-to-filmic-pro (2026-09-21)](https://contentcam.app/blog/what-happened-to-filmic-pro/) · [CameraW](https://github.com/Amachua-Appure/CameraW) · [Filmic UI breakdown](https://screensdesign.com/showcase/filmic-pro-video-camera) · [external-SSD recording practice](https://dongknows.com/sandisk-creator-phone-ssd-review/)

**The strategic read:** Filmic is stalled on Android and Blackmagic refuses to install on mid-tier and Android 10–12 devices. CineCam's real niche is *"the only serious cinema camera app that runs properly on the phone you already own, offline, forever free"* — a mid-tier/older-device floor nobody serves, with flagship features lit up opportunistically. That only works if the fundamentals (monitoring truth, exposure, audio, file hygiene) are excellent — which is exactly the list in §1.

---

## 3. Root causes in this codebase

| Symptom | Cause in code | Fix belongs in |
|---|---|---|
| Overlays not actionable | Aggregates → density, never per-pixel masks; single 320×240 sample grid at ~8 Hz with `stepX = width/160` | A1 |
| Preview ≠ file | Grade is a `View` background colour; LUT never applied to pixels | A2 (honest labels) → B2 (real) |
| Soft, "video-ish" image | CameraX → OEM ISP: denoise + sharpen + tone-map; 8-bit AVC; no LOG profile; FHD max on this device | A3 (bitrate/codec/exposure discipline) → B3 |
| HDR10 never engages even on API 33+ | `tryApplyHdr10DynamicRange()` reaches `setDynamicRange` **by reflection** ([MainActivity.kt:1691-1700](app/src/main/java/com/cinecam/app/MainActivity.kt#L1691-L1700)) — the API has been public since 1.4.0 | A3, trivial |
| 4K/60 unreachable | `QualitySelector` + `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` caps; CameraX cannot open a high-speed session | B1/B4 |
| Track audio unprotected | `Recorder` audio source set at bind time; no device pinning on the record path, no limiter/normalisation, no sample-rate/format control | A4 |
| `.cube` install requires adb | Library is `getExternalFilesDir(DIRECTORY_DOCUMENTS)` scan + a cycle button ([MainActivity.kt:1384-1431](app/src/main/java/com/cinecam/app/MainActivity.kt#L1384-L1431)) | A5 |
| Settings form, not a viewfinder | ~24 labelled rows in one `ScrollView` + two `rotation="-90"` SeekBars; both edge sliders carry `importantForAccessibility="no"`; panel capped at 360 dp | A2 |
| Can't trust a long take | No `PowerManager.getCurrentThermalStatus()` (PRD NFR-3 unimplemented), storage checked once at start/end, no interruption recovery beyond `MediaStore` | A6 |
| Docs mislead the next agent | `AGENTS.md` points at `com/ankush/cinecam/`, says "~1300 lines", "No CI", "no network", Kotlin 2.0.21 vs actual 2.4.10 | A0 |
| Release APK cannot install | No `signingConfig` in [app/build.gradle.kts](app/build.gradle.kts) → `assembleRelease` emits unsigned output | A0 |

---

## 4. Two findings that cheaply change the plan

**4.1 — 10-bit HLG is already available to you; codec selection is not.** [CameraX 1.4.0's own release notes](https://developer.android.com/jetpack/androidx/releases/camera) headline "10-bit HDR: supports HLG and 10-bit HEVC encoding" — the version this app is on. Consequences, with the version each claim depends on:

- **Available today on 1.4.0:** `Recorder.Builder.setDynamicRange(DynamicRange.HLG_10_BIT)` is a compile-time API, so `tryApplyHdr10DynamicRange()`'s reflection is dead weight, and `Preview.Builder.setDynamicRange()` (added 1.4.0-alpha04) can drive an HDR viewfinder without binding video at all. `Recorder.getVideoCapabilities(cameraInfo).supportedDynamicRanges` can *ask the device* instead of guessing from `Build.VERSION.SDK_INT` (today: `hdr10Supported = SDK_INT >= 33 && reflection-found` — [MainActivity.kt:298-313](app/src/main/java/com/cinecam/app/MainActivity.kt#L298-L313)).
- **Not available on any stable CameraX:** explicit codec choice. `Recorder.getSupportedVideoMimeTypes()` and `Recorder.Builder.setVideoMimeType()` / `setAudioMimeType()`, plus per-codec `getVideoCapabilities(cameraInfo, mimeType)`, landed in **1.7.0-alpha02** and are marked experimental. So on stable, SDR recording stays on CameraX's chosen AVC path and **HEVC-on-demand is a Phase-B reason to leave CameraX, not a checkbox** — unless you knowingly build against a 1.7 alpha for a personal sideload app. Decide explicitly in §13.
- **Upgrade 1.4.0 → 1.6.2 anyway:** CameraX ≤1.5.1 throws `NullPointerException` / `IllegalArgumentException` inside `bindToLifecycle()` on devices exposing dynamic-range profiles it does not recognise (e.g. Android 17/API 37 `STANDARD_SMPTE_2094_50`) — fixed in 1.5.2/1.6.0. Since the plan is "flagship later", that is a future-crash fix, not a nicety. `camera-feature-combination-query` also only exists from 1.5. (Verify the AGP/`compileSdk` pairing on build; current toolchain is AGP 8.5.2 / SDK 35.)

**4.2 — Stop guessing at capability; query the combination.** `androidx.camera:camera-feature-combination-query` (alpha) exists precisely to answer "can this device do HDR *and* 60 fps *and* this size at once" (a compatible wrapper over `CameraManager.isSessionConfigurationSupported`). Even without that artifact, one hidden **Diagnostics screen** that dumps, per lens: `SCALER_STREAM_CONFIGURATION_MAP` for `MediaRecorder`/`ImageFormat` surfaces, `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`, `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, `REQUEST_AVAILABLE_CAPABILITIES`, `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`, every encoder's `supportedWidths/Heights` + profiles (incl. `hevc_main10`), and `Recorder.getVideoCapabilities(cameraInfo)` (the per-MIME overload only exists on 1.7 alpha) — converts every "unsupported, falling back" toast into an explainable, exportable fact. The code already logs most of this and throws it away ([MainActivity.kt:327-377](app/src/main/java/com/cinecam/app/MainActivity.kt#L327-L377), [1109-1144](app/src/main/java/com/cinecam/app/MainActivity.kt#L1109-L1144)).

---

## 5. Phase 0 — Ground truth and hygiene (half a day, do first)

Nothing below is measurable without the phone attached, and today `adb devices` is empty.

- **P0.1** Connect the Realme; run the capability audit (`/cinecam-capability-audit`) → save the report as `docs/capability-rmx2061.md`. This settles, with evidence: HEVC present? `hevc_main10`? 4K in `CamcorderProfile`/stream map? any `[60,60]` range? EIS per lens? HLG capture?
- **P0.2** Baseline the current output: 3 × 60-second takes at the settings you actually use; `ffprobe` each (codec, profile, pix_fmt, bit rate vs requested, frame rate actual vs requested, audio codec/rate, container length). Record dropped-frame evidence from logcat. This is the number every later phase has to beat.
- **P0.3** Fix `AGENTS.md` + `README.md` drift (§3) and add a `signingConfig` so `assembleRelease`
  yields an installable artifact — debug keystore for now, and add `*.jks` / `*.keystore` /
  `keystore.properties` to [`.gitignore`](.gitignore), which currently ignores none of them.
- **P0.3b** Pin **one** debug keystore across local and CI builds. Each machine (and each
  GitHub runner) creates its own `~/.android/debug.keystore`, so a CI `auto-<sha>` APK
  cannot be installed over a locally built one — Android refuses with a signature mismatch
  and the workaround today is uninstall, which silently wipes the app's LUT library,
  presets and `cinecam_presets` state. Commit a debug keystore (or store one as a CI
  secret) and sign `debug` with it.
- **P0.4** Establish the CI loop you don't have: `.github/workflows/auto-apk.yml` builds a debug APK only. Add `./gradlew lint` + a build-matrix sanity job, and keep the `vX.Y.Z` / `auto-<sha>` tag contract intact (the in-app updater depends on it).

**Exit criterion:** a written capability matrix + a baseline ffprobe/log report + accurate docs. Every subsequent phase cites against them.

---

## 6. Phase A — Production-ready inside CameraX (the 80 %)

Order matters: A1/A2 make you trust the picture, A3/A4 make the picture and sound good, A5/A6 make it usable on a shoot, A7 makes it look like a 2026 app.

### A1 — Monitoring that tells the truth
Replace aggregate-driven drawing with real per-pixel statistics at the analysis resolution, and draw per-pixel masks.

- Compute, in `ExposureAnalyzer`, a **downsampled luma map** (not just a 64-bin histogram) plus a **gradient map** for peaking, and publish it as a bitmap/`FloatArray` the overlay renders — zebra marks *only the pixels above threshold*, peaking outlines *only high-gradient edges*, false color maps *each pixel's IRE band*. Keep 8–15 Hz; 320×240 is already smaller than one 1080p tile.
- Thresholds become real: independent over/under for zebra, sensitivity + colour for peaking, correct IRE band mapping (70 % / 90 % / 100 % markers) instead of six equal-luma population bands.
- Add **waveform / luma parade** next to the histogram — the single most-used exposure instrument on real camera OSDs, present in Blackmagic, absent everywhere in CineCam.
- Report the analysis latency and resolution honestly ("monitoring at 320×240 / 8 Hz — does not represent recorded detail"), because the analyzer sees the *preview* stream, not the encoder's.
- **Accept:** point the camera at a frame that is half sun, half shadow, and be able to name the over-exposed region by looking at the overlay. Verify against a still exported from the recorded file.

### A2 — Viewfinder-first UI rebuild (replaces the settings form)
The PRD already researched this (§5) and the implementation drifted from it. A prior design audit scored the layout **17/40** with three P0s: fake edge rails, unreliable state visibility, cognitive overload — none of which are ignored in code today.

- **HUD strip, not readout soup.** One row of *tappable values* (ISO · shutter/angle · WB · f · FPS · codec/quality · free space · TC). Tap → that parameter's dial/wheel opens over the frame with a live numeric readout and haptic detents; done → it collapses. 24 label+row pairs become 8 HUD fields + 4 collapsible panels (Exposure, Focus, Look, Audio/File).
- **Real edge rails**: custom vertical drag targets on the left/right thirds (exposure on left, focus on right) with visible value bubbles, replacing `rotation="-90"` SeekBars. Keep them accessibility-actionable (`announceForAccessibility`, `AccessibilityNodeProvider`) instead of `importantForAccessibility="no"`.
- **Record state unmissable**: red border/tally while recording (the icon already implies it), timecode + remaining minutes next to REC, and the status bar turning red *is* the record indicator.
- **Progressive disclosure for the rare stuff** (AGSL/HDR/codec/bitrate/LUT library/presets) in a bottom sheet; keep REC, rails, HUD, Fn in thumb reach in both orientations; respect cutout/insets on both.
- Monospaced tabular digits on *every* changing readout (today `readoutTint` and `readoutGuides` are excluded — [MainActivity.kt:727-753](app/src/main/java/com/cinecam/app/MainActivity.kt#L727-L753)), accent hue reserved for "being adjusted" + REC, per PRD §6.
- Stay XML + custom Views for A2 (Compose would help the panel shell; open decision in §13). Reuse `AGENTS.md`'s twin-layout rule; consider `<merge>`/`<include>` so the two 1.25 k-line layouts stop diverging.
- **Accept:** from cold open to first armed take under 3 s; every parameter reachable in ≤2 touches; nothing hidden behind a scroll during a take; TalkBack can set ISO, focus and start recording.

### A3 — Encoding and image quality inside the CameraX ceiling
- **Upgrade to CameraX 1.6.2** (§4.1) and re-derive every gate from `Recorder.getVideoCapabilities(cameraInfo)` (supported qualities + dynamic ranges) rather than `SDK_INT` heuristics.
- **HDR10/HLG that is real, now:** replace the reflection with public `setDynamicRange()`, gate it on `Recorder.getVideoCapabilities()` instead of `SDK_INT`, and use `Preview` dynamic range for an HDR viewfinder where the panel supports it. HEVC *selection* waits for §7/B3 (or a deliberate 1.7-alpha dependency — decision in §13).
- **The HUD reports the negotiated result, never the request** — actual codec, resolution, frame rate and dynamic range as CameraX resolved them, so "UHD · HLG10" can never sit above a file that is FHD · SDR.
- **Bitrate ladder per codec and tier**, with the requested-vs-achieved delta verified by `ffprobe` in Phase 0 style; 80 Mbps AVC at FHD is already beyond most phones' detail — the ceiling is the ISP and the sensor readout, so say so in-app instead of implying more.
- **Stop the honest-label lies in the UI**: "AGSL/GLSL" and "LOG/LIN" readouts must not appear as if a shader or a capture profile is active when it is a tint (§1.2) — until B2 exists, expose them as *preview looks* and label the file as what it is.
- **Exposure discipline features** (this is how you beat the ISP without leaving it): ETTR assistant from the real histogram/waveform, 180°-rule shutter↔FPS linking with an explicit override, a dynamic-range readout (stops between crush and clip), per-channel clipping warnings, and a "protect highlights" quick action that pulls ISO down before it touches shutter.
- **Rolling-shutter and crop honesty**: warn when shutter angle > frame interval, when zoom is digital (crop past sensor), and when EIS is engaged (crop factor). Cheap, and the single biggest "why does this look like a phone" complaint driver.
- **Accept:** on the Realme, the same 60 s take in AVC vs HEVC at equal Mbps, `ffprobe`-verified, plus a documented detail/noise comparison; on a flagship tier, a real HLG10 file Resolve tags as BT.2020/HLG 10-bit.

### A4 — Sound you can put in a program
- **Pin the device on the record path**, not just the meter: enumerate `AudioDeviceInfo` and use the USB/`AudioRecord` routing that actually feeds the encoder; expose input type (`CAMCORDER` vs `MIC` vs `UNPROCESSED`, which kills AGC on many HALs).
- **48 kHz and (where offered) PCM/24-bit**: `Recorder.Builder.setAudioMimeType()` exists only in 1.7.0-alpha02, so on stable CameraX the audio format is whatever the recorder negotiates — surface the *actual* sample rate/channels in the HUD, and take real format control in Phase B's `MediaMuxer` path.
- **Levels that mean something**: dBFS scale (not 0–100 progress bars), peak-hold + clipping latch that *tells you the take is compromised*, target-alignment helper (dialogue peaking ≈ −12 dBFS / RMS ≈ −20).
- **Protection**: optional soft limiter / auto-normalisation as a documented, opt-in DSP step (currently deliberately absent — that is a *choice* to revisit, not an oversight), and a **second audio source**: record a sync reference (a 1 kHz beep + slate in a sidecar, or a separate `AudioRecord` file) so dual-system with a Zoom/Tascam stays possible; timecode-accurate sync is not achievable on Android, so build the workflow that admits it.
- Headphone monitoring path and latency number, with the honest "Android audio out is not zero-latency; use a wired monitor" note.
- **Accept:** clapping at −20 dBFS target does not clip the file (ffprobe + loudness scan), and unplugging a USB mic mid-take degrades audibly rather than silently killing audio.

### A5 — File, LUT and shot workflow
- **Storage Access Framework for LUTs** (`ACTION_OPEN_DOCUMENT`, `*.cube`), a visible LUT list with the loaded name + strength on the HUD, plus **persisted permission** (`takePersistableUriPermission`) and an in-app **preset import/export** (JSON) — Blackmagic's preset/LUT exchange is table stakes.
- **Choose the storage target**: internal, SD card, or **USB drive** (`StorageVolume`/`UsbStorageManager`, `MediaStore` on the secondary volume). This is the feature that makes long 4K takes real and is well established in the Android pro-app scene.
- **Pause/resume within one take** (PRD-adjacent, and the most-requested BMD 3.4 feature) — with CameraX that means sequential recordings plus a documented "stitch these" affordance unless you land it in Phase B properly.
- **Take logging**: scene/take/notes per clip, written as a sidecar JSON (+ optional `.csv`/EDL-ish) with the full settings snapshot you already have in the filename; **in-app review**: last-take thumbnail, scrub, favourites, delete, "reveal in Files", and per-clip ffprobe-equivalent detail. Reading back your own take on set is what makes an app feel professional.
- **Proxy option** where the encoder can sustain it (BMD auto-generates a 1080p HEVC proxy), otherwise document it as Phase B.
- **Accept:** a 20-take shoot day completed without touching a laptop, and a graded edit importing your sidecars with the settings intact.

### A6 — Never lose the take (reliability and edge cases)
- **Thermal**: poll `PowerManager.getCurrentThermalStatus()`; implement PRD NFR-3's degrade order (overlays → preview resolution → prompt to stop), with a visible heat indicator and pre-take "estimated safe duration" built from measured history.
- **Storage runway**: continuous free-space check (not just at REC start), auto-stop threshold, and remaining-minutes in the HUD (the maths exists at [MainActivity.kt:799-804](app/src/main/java/com/cinecam/app/MainActivity.kt#L799-L804)).
- **Interruption recovery**: incoming call, camera2 client eviction, other camera app grabbing the HAL, screen off, app backgrounding, process death — each needs a defined behaviour and a *resume with settings intact* path. Today `onDestroy` calls `recording?.stop()` unconditionally, which will truncate on config-change edge cases.
- **Fail-safe recording**: on `Finalize` error, immediately re-arm and log the loss rather than leaving the UI in a state that looks like recording; verify the file length actually matches the timecode.
- **Permission and hardware loss mid-session**: mic revoked mid-take, USB mic pulled, lens switch denied, torch unavailable — surface all as state, not toasts.
- **NFR-4** cold start to first frame <1.5 s, measured.
- **Accept:** a scripted torture run (the `/cinecam-device-verify` skill drives exactly this: 10-minute take, call screen-off, pull mic, kill storage, background the app) with zero silent data loss and every failure explained on screen.

### A7 — Finish and feel
- Edge-to-edge + insets done once properly (the manual `paddingTop += bars.top` accumulation in [MainActivity.kt:888-908](app/src/main/java/com/cinecam/app/MainActivity.kt#L888-L908) double-counts on repeated inset dispatches), predictive back, gesture nav, dark-chrome retention; haptics on rails/dials; first-run interactive tour (set exposure, pull focus, arm REC — the known Filmic onboarding weakness); a real `res/values` icon pass so Fn/REC/panels read as one system.
- Split `MainActivity.kt` (1748 lines) into `CaptureCoordinator` / `ManualControlState` / `MonitoringController` / `RecordingController` / `UiBinder` **during** A2 — not as a separate refactor epic.
- Replace the current launch-time `UpdateChecker` network call with an opt-in check (PRODUCT.md says offline is a requirement, not a mode; AGENTS.md currently claims "no network").
- Fix the small correctness bugs found in the last read: `isUhdSupported()` fails *open* ([MainActivity.kt:1087-1092](app/src/main/java/com/cinecam/app/MainActivity.kt#L1087-L1092)); `restoreState` indexes `Mode.entries[…]`/`Guide.entries[…]` unguarded; `CubeLut.approximateColorMatrix()` and two strings are dead; the bundled `cinelog_to_rec709.cube` is a 2×2×2 stub and must be replaced with a real transform or renamed to what it is.

---

## 7. Phase B — The GPU/encoder pipeline (only for what CameraX cannot do)

Build this *after* A ships, behind a `FeatureFlags.pipelineV2` switch, keeping the CameraX path as fallback — that is how you keep the app shippable while rewriting its heart.

- **B1 Camera2 + MediaCodec zero-copy**: `CameraDevice` → `SurfaceTexture`/`ImageReader` → `GLES3` `GL_OES_egl_image` → `eglSwapBuffers` into `MediaCodec.createInputSurface()`; audio via `AudioRecord` → AAC/PCM encoder → `MediaMuxer`. Reference: `nekdenis/camera2mediacodec`, CameraW's GLES encoder.
- **B2 One shader pass, forked outputs**: LUT + zebra + peaking + false color + guides in a single fragment shader; **two render targets** — screen (graded, overlaid) and encoder (grade optional, overlays never) — which finally makes PRD FR-6/9/10/12 literal and gives MotionCam-style "see exactly what you record".
- **B3 Colour and depth**: BT.709/BT.2020, HLG/PQ transfer, `.cube` (and `.dat`/`.3dl`) sampling in shaders, correct `ColorInfo`/atom metadata so Resolve reads the file tagged; bake-LUT-to-file as an explicit mode; 10-bit paths only where `hevc_main10` + capture support exist (Phase 0 answers this).
- **B4 Resolution/frame-rate truth**: pick surface sizes from `StreamConfigurationMap` + `SENSOR_INFO_TIMESTAMP_SOURCE` + real `isSessionConfigurationSupported()` probes instead of `QualitySelector`'s table; this is where 4K/60 either appears or is proven absent on the Realme. Include CameraW's **desqueeze trick** (encode at half-width, set SAR 2:1) for sensors wider than the encoder's 4096 cap — cheap, and it is how mid-range hardware records 4K-ish.
- **B5 Stabilisation and readout**: real recording EIS via `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`/`REPROCESS`-free session config, with the crop factor displayed; rolling-shutter readout per lens; per-lens calibration profiles.
- **B6 Performance**: frame-budget telemetry (actual fps vs requested, dropped-frame count, encode latency, thermal status) logged per take and shown on review. NFR-1/2 are unmeasurable today.
- **B7 Stretch, flagship-only**: RAW10/RAW_SENSOR capture → own debayer/log transform (MotionCam's territory). Only attempt on a device that proves `RAW10` + fast storage in Phase 0.

**Accept:** a 10-minute 4K take on the flagship tier at the target codec/bitrate with measured zero dropped frames, thermally survived, and a graded preview whose on-screen histogram equals the recorded file's.

---

## 8. Phase C — Where CineCam can beat the incumbents

1. **Own the abandoned floor**: flawless on Android 10–12 and mid-tier devices where Blackmagic won't install and Filmic is rotting. Device-profile-aware defaults, "this phone can genuinely do X" messaging, and a public per-device capability database built from the Diagnostics export.
2. **Resolve round-trip as a first-class feature**: sidecar metadata (settings, LUT name + hash, take notes, colour tags) exported as `.json`/`.csv`/EDL-ish, a LUT library that travels with the project, and a documented "grade in Resolve, reload the same LUT here" loop. Nobody owns the indie-DP workflow on Android.
3. **Offline-first as a promise, not a slogan**: no account, no telemetry, no subscription, preset/LUT sharing over local files or QR, and a "field mode" that provably never touches the network (make `UpdateChecker` opt-in, §A7).
4. **Two-phone / multi-cam later**: Blackmagic does REST + WearOS control. A LAN-only, zero-cloud companion (phone A records, phone B monitors with a real WYSIWYG feed) is a differentiator in the same niche.

---

## 9. Device tiers — the honest ceiling

| | **Floor: Realme RMX2061** (Android 11, SD665-class, Camera2 LEVEL_3) | **Target: flagship tier** (Android 14+, Pixel 9 / S25 class) |
|---|---|---|
| Manual exposure | Yes — FULL/LEVEL_3 + `MANUAL_SENSOR`, real ISO/shutter/WB/focus | Yes |
| Resolution/fps via CameraX | FHD, AE ranges capped `[30,30]` → 60 fps refused; UHD refused by quality table even though the AVC encoder does 4096×2160 | 4K, 4K60 likely via `setVideoMimeType`/`QualitySelector` on modern HALs; verify |
| Codec | AVC only today; **HEVC possibly available in HW — unproven** | HEVC + `hevc_main10`, HLG10 10-bit |
| LOG / RAW | No LOG profile in HAL; RAW support unproven | Some devices expose LOG profiles / RAW10; MotionCam territory |
| Stabilisation | Preview-only EIS flag, gated by lens; no recording-stab API in CameraX 1.4 | OIS + EIS, `Preview` stabilization, real recording stab in B5 |
| Audio | Built-in + external mic routing; no DSP | USB/XLR interfaces, 48 kHz/24-bit PCM |
| What CineCam should promise here | **FHD 8-bit, perfectly exposed, monitored honestly, logged and safe — better than stock on old phones** | 4K, 10-bit HLG, HEVC, proxies, long takes to SSD |

Buying criteria for the flagship (validate against Phase 0's audit before purchase): Camera2 `FULL`/`LEVEL_3` · `MANUAL_SENSOR` + `MANUAL_POST_PROCESSING` · a `[60,60]` AE range at the target size · `hevc_main10` *encoder* with BT.2020/HLG · HLG10 in `Recorder.getVideoCapabilities()` · UAB/USB-OTG storage write throughput ≥ your target bitrate · `RAW10` if B7 ever matters · and being on Blackmagic's supported list is a decent proxy for "Camera2 behaves properly".

---

## 10. Explicit non-goals

Photo/night/portrait/computational modes · cloud sync, accounts, telemetry, anything subscription · in-app editing or LUT authoring (loading only) · Play Store compliance work · iOS · gimbal/SDK hardware integration · live streaming. Each of these is a fork in the road that would delay the camera being a camera.

---

## 11. Risks

| Risk | Mitigation |
|---|---|
| Phase B becomes a two-year rewrite and the app stalls | A ships as a release on its own; B behind a flag; CameraX path never deleted |
| A2 redesign makes it slower to operate mid-take | Prototype in the real layout with the phone in hand, run the 3-second-to-arm test, keep the Fn/rails muscle memory |
| "HEVC/10-bit available" assumptions turn out false on the Realme | Phase 0 capability audit decides the copy before it's written |
| Per-pixel monitoring at 320×240 still misleads at 4K | Label the analysis resolution and latency on screen; B2 replaces it |
| Thermal throttling makes 4K unusable in the field | Measure in Phase 0; publish honest durations; A6's degrade order |
| Docs keep drifting and mislead agents | `/cinecam-release-hygiene` makes doc sync part of every release |

---

## 12. Definition of "production ready"

The app is usable on a paid shoot when all of these are true and evidenced: monitoring is per-pixel and you would expose to it · what the grade shows is labelled as preview-only or is genuinely WYSIWYG · the codec, resolution, frame rate and colour space in the HUD are the negotiated reality, verified by `ffprobe` · audio levels mean dBFS and clipping is preventable or at least latched · LUTs/presets import via a picker, clips can record to USB/SD, takes are logged and reviewable · a 10-minute take survives a call, a screen-off, a pulled mic and 80 % storage with nothing lost silently · cold start <1.5 s · the UI operates one-handed in both orientations with no settings hunt during a take · the docs and skills say what the code does.

---

## 13. Suggested next actions

1. `/cinecam-capability-audit` with the Realme connected → Phase 0.1 report, which rewrites §9's floor column with facts.
2. `/cinecam-device-verify` baseline run → §P0.2 numbers to beat.
3. Then pick A1 (monitoring truth) or A2 (UI rebuild) as the first implementation slice; A3's CameraX 1.6.2 upgrade is independent and can land the same day as A1.
4. Two open decisions to settle before A2/A3 code lands:
   - **XML views vs Compose** for the A2 panel shell (recommendation: keep XML + custom Views, since the viewfinder chrome is already custom-drawn and one-person codebases pay for the migration twice).
   - **HEVC route**: depend on CameraX `1.7.0-alpha02` for `setVideoMimeType()` (fast, experimental, fine for a sideload build, risky against the API-37 bind crash class), or hold stable 1.6.2 and get codec choice from Phase B's own `MediaCodec` path (slow, permanent). Everything else in A3 works either way.
