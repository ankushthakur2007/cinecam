# CineCam

A personally-owned, manual-control-first cinema camera for Android. Offline-first, single-purpose: locked exposure, real monitoring aids, LOG-style preview with `.cube` LUTs, and controlled encoding — no cloud, no account, no subscription.

## What it does

- **Full manual capture** — ISO, shutter speed (+angle readout), white balance (Kelvin + green/magenta tint), manual focus with A/B focus pull, 24/30/60 fps request, AE lock. Auto fallback with a clear state readout on unsupported hardware.
- **Tap-to-focus** — single AF scan on the tapped subject, then the lens holds (never continuous AF). White box while scanning, green on lock, red on failure.
- **Real monitoring** — data-driven zebra / focus peaking / false color (from per-frame luma analysis), live histogram with clip/crush flags, `CLIP H/L %` readout, frame guides (16:9, 2.39:1, 1:1, 4:3, 9:16) + thirds grid.
- **Preview grade** — bundled CineLog→Rec.709 LUT plus a multi-file `.cube` library (drop files in the app's Documents folder, cycle with one button). Preview-only; the recorded buffer is untouched.
- **Encoding control** — FHD/UHD request, 16:9/4:3 recording aspect, 12–80 Mbps bitrate, hardware-encoder capability report. Filenames preserve shooting parameters for grading (`cinecam_<ts>_<fps>fps_<br>mbps_<quality>_<aspect>_<lens>_iso<iso>_<wb>k.mp4`).
- **Audio** — live peak/RMS meters from a parallel monitor path, internal/external mic routing, manual gain.
- **Shooting extras** — front/back lens, 1x–maxZoom slider, torch, preview stabilization (hardware-gated), P1–P3 presets (tap recall, long-press save), storage/timecode readouts, UI lock-out of rebind settings while recording.
- **Viewfinder-first UI** — slim live-readout status bar, preview always visible, capped settings panel, one red REC action. First-class portrait and landscape layouts (`res/layout`, `res/layout-land`, same view IDs), dark chrome throughout.

## Build

Prerequisites: JDK 17, Android SDK (API 35), `local.properties` with `sdk.dir`.

```sh
./gradlew assembleDebug      # installable APK at app/build/outputs/apk/debug/
./gradlew assembleRelease
```

No unit/instrumentation tests yet. Single module (`:app`, `com.cinecam.app`), CameraX 1.4.0 + Camera2 interop, XML views + viewBinding (no Compose), `minSdk 29`, `targetSdk/compileSdk 35`, AGP 8.5.2, Kotlin 2.4.10, Java 17.

## Releases & updates

- Every push to `main` builds a debug APK via GitHub Actions (`.github/workflows/auto-apk.yml`) and publishes it as a **prerelease** (`auto-<sha>`).
- Stable releases (e.g. `v0.1.0`) are cut manually with `gh release create`.
- The app checks `releases/latest` on launch and offers the release page when a newer **stable** build exists (CI prereleases never trigger it). Offline or API failure is silent.
- Tag contract (the updater depends on it): stable releases are always `vX.Y.Z`; automation tags are always `auto-<sha>` and always prereleases. Don't flip a prerelease to stable, and don't invent tag shapes — non-version tags are ignored by the app.

## Device notes

Developed against a Realme RMX2061 (Android 11, Camera2 `LEVEL_3`). Honest hardware gating throughout: unsupported options (UHD, 60 fps, HDR10/AGSL on older OS, EIS per lens) fall back with a toast instead of failing. Measured on-device: the HW AVC encoder does 4096×2160 and the HAL lists 3840×2160 outputs, yet CameraX's quality table (both capability sources) maxes at FHD and AE ranges cap at `[30,30]` — so 4K/60 are unreachable through CameraX 1.4.0 here and need a custom MediaCodec pipeline (future work, see PRD §8).

## Docs

- `cinecam-android-prd.md` — full PRD (goals, Filmic/Blackmagic research, milestones)
- `PRODUCT.md` — product constraints
- `AGENTS.md` — contributor/agent guide: architecture, gotchas, commands
