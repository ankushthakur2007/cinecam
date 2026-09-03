# PRD — Professional Cinema Camera App for Android

**Status:** Draft v1
**Owner:** Ankush Thakur
**Platform:** Android, personal-use build (not slated for Play Store distribution unless scope changes later)

---

## 0. Read this before anything else

One thing in the brief needs to be flagged before the rest of this document makes sense: **you asked for Android 10 through the latest version, but this whole project started as "something for myself."** Those two goals pull in opposite directions, and it's worth being honest about the cost before you commit to it in writing.

- Supporting API 29 (Android 10) through API 36+ (Android 16 and whatever ships next) is *distribution* thinking — it's what you do when strangers with unknown devices will install your app. It roughly triples your testing matrix, forces you to hand-maintain a GLSL fallback path for every place AGSL would otherwise save you a day of work, and means writing capability-detection code for hardware you don't own and can't test.
- If this app only ever runs on your phone, the correct `minSdk` is *your phone's current Android version*, full stop. You get to delete the entire compatibility-tier section of this document and go straight to the newest APIs.

This PRD is written to support the wider range **because you asked for it**, with an explicit tiering system so the added complexity is contained instead of leaking into every feature. But the recommendation stands: narrow `minSdk` to your own device unless you already know a second person is going to install this. Re-open this decision before Milestone 1 starts.

---

## 1. Problem statement

Stock Android camera apps optimize for point-and-shoot convenience: auto-everything, no persistent manual state, no monitoring tools, aggressive default bitrate/denoise that destroys detail in motion. There's no first-party way to get locked manual exposure, a LOG-style flat capture profile with a graded preview, real-time exposure/focus monitoring, and encoder control on a personal Android device without buying into a subscription app (Filmic Pro, MoviePro) whose UI and feature set you don't control and whose backend/business model you're dependent on.

**Goal:** a personally-owned, professional-grade manual video capture tool — locked exposure control, real-time monitoring overlays, LOG+LUT preview pipeline, and encoder control — built on the zero-copy GPU pipeline architecture already scoped in prior technical discussion, running well on your own hardware.

---

## 2. Goals

1. Full manual capture control: ISO, shutter speed/angle, white balance, focus — locked and persistent across the recording session, not per-frame auto-adjusted.
2. Real-time monitoring overlays: zebra stripes, focus peaking, false color — cheap enough to run every frame without dropping below target fps.
3. LOG-style flat capture with a graded LUT preview (preview only — recording buffer stays untouched, per the split pipeline architecture already scoped).
4. Controlled encoding: manual bitrate, codec choice (AVC/HEVC, plus AV1 where hardware supports it), 10-bit HDR (HLG10) capture where the device and OS support it.
5. Zero-copy GPU pipeline end to end: camera → GPU texture → shader pass → simultaneous screen + hardware encoder surface, no CPU round-trip on the hot path.
6. A UI that a working cinematographer would recognize as a tool, not a phone camera app — see Section 5 for the research this claim is based on.

## 3. Non-goals

- Photo mode, portrait mode, night mode, or any computational-photography feature. This is a video-only tool.
- Cloud sync, social sharing, editing, or any post-production feature. Files land on-device; you take them into DaVinci Resolve or whatever you already use.
- Multi-device parity. Every device-specific quirk is a "does my phone support X" question, not a "does every Android phone support X" question, unless you reverse the decision in Section 0.
- App Store distribution polish (privacy nutrition labels, onboarding funnels, monetization) — irrelevant for a personal build.

---

## 4. Target platform

- **minSdk:** 29 (Android 10) — per your stated requirement; **revisit per Section 0.**
- **targetSdk:** 36 (Android 16) at time of writing. Google Play now requires new app submissions to target API 36 as of August 31, 2026 — irrelevant for a sideloaded personal APK, but worth knowing if distribution scope ever changes.
- **compileSdk:** latest stable at build time (API 36 or newer — check before each build; Android 17/API 37 is expected to reach stable in the same window this PRD is being written).
- **Distribution:** signed debug/release APK, sideloaded. No Play Store listing planned.

---

## 5. Visual design research — findings before conclusions

You specifically asked for the "looks" to be researched before being designed. Here's what was actually found, from the apps that already solved this problem, before any UI decision below is made.

### 5.1 Filmic Pro v7 (redesigned 2022, still the reference version)

Filmic Pro's v7 redesign is documented in detail by CineD's editorial coverage of the release. The concrete, sourced findings:

- **QAM system ("Quick Action Modal"):** controls are grouped into categorized shortcut panels — Focus/Exposure Mode, Audio, Lens, Remote Control & Monitoring, Live Analytics — each reachable without leaving the live camera view. This is a deliberate rejection of a single flat settings menu; controls are organized by *shooting concern*, not alphabetically or by feature-addition order.
- **Action Slider (persistent top bar):** a bar showing live numeric readouts of ISO, shutter speed, white balance, remaining recording time, codec, and gamma curve — visible even *while actively recording*, and it visually indicates which parameters are currently locked. Each value is adjustable three ways: drag a slider, pick from a preset list, or dial in a precise number. This is the single most load-bearing finding: professional users are shown their current state at all times, not just when they open a menu.
- **Edge-anchored manual controls, not center-frame dials:** the pre-v7 design used a circular arc control wrapped around the live image. v7 explicitly replaced this with two simple vertical sliders anchored to the screen edges — left slider for ISO/shutter/zoom, right slider for focus distance (with automated focus-pull support). The redesign rationale, per the coverage, was that the arc design obstructed the frame; the edge sliders don't.
- **User-assignable function button**, top-right corner, mapped to whatever single control the shooter uses most.
- **Landscape and portrait are both first-class layouts**, not one primary orientation with the other bolted on.

### 5.2 Blackmagic Camera (Android + iOS, Blackmagic Design)

Blackmagic's own product description is explicit about design intent, not incidental: the app is built to give **"the same intuitive and user friendly interface as Blackmagic Design's award-winning cameras… just like using a professional digital film camera."** This is a different design philosophy than Filmic's from-scratch mobile UI — Blackmagic is deliberately porting the control layout and interaction language of their physical cinema cameras onto a phone screen, so that someone who already operates a Blackmagic URSA or Pocket Cinema Camera has near-zero relearning cost. Their focus/zoom control also explicitly targets rig-based operation — precise enough to pull focus without taking a hand off a tripod or rig handle, i.e. the touch target and drag sensitivity are tuned for physical-accessory use, not just bare-thumb tapping.

### 5.3 Cross-cutting pattern across both

Both tools converge on the same underlying principle even though they arrived by different paths (Filmic: redesigned from mobile-first UX research; Blackmagic: ported from physical hardware): **the live image is the primary UI element, and every control either lives at the edge of the frame or collapses out of the way until touched.** Neither app puts a dashboard on top of the footage you're trying to judge exposure on.

### 5.4 What this rules out

- No bottom tab bar with icon+label navigation (standard consumer-app pattern) — it eats vertical frame space and neither reference app uses it.
- No settings buried in a hamburger menu — both surface shooting-relevant controls immediately, categorized, not nested behind generic "Settings."
- No auto-hiding chrome that requires a tap-to-reveal gesture before you can see your exposure values — the Action Slider pattern shows live readouts *unconditionally* while shooting.

---

## 6. UI/UX requirements (derived directly from Section 5)

| Requirement | Source finding |
|---|---|
| Persistent top status bar with live ISO / shutter / WB / codec / remaining-time readout, visible during recording | Filmic Action Slider |
| Manual parameter controls as edge-anchored vertical sliders (left: exposure triangle, right: focus), not overlaid on the center frame | Filmic v7 redesign away from arc dials |
| Controls grouped into categorized quick-access panels (Exposure, Focus, Audio, Monitoring) rather than one flat menu | Filmic QAM system |
| One user-assignable quick-action button | Filmic Fn button |
| First-class landscape and portrait layouts, built and tested separately, not scaled from one to the other | Filmic dual-orientation support |
| Dark, near-black chrome around the live image; UI elements never brighter than necessary to read | Standard practice on both reference apps and consistent with why physical cinema camera OSDs are dark — a bright UI skews your perception of the image's actual exposure |
| Monitoring overlays (zebra, peaking, false color) render as translucent layers directly on the image, never as a separate panel that covers it | Consistent with the "live image is primary" pattern in 5.3 |
| Touch targets on manual sliders sized and drag-tuned for precision under thumb, not optimized for glanceable tapping | Blackmagic's rig-precision focus/zoom control design goal |
| No bottom nav bar, no hamburger settings menu | 5.4 |

**Visual language, concretely:**
- Background: `#000000`–`#0A0A0A`, true black or near-black. Not a dark gray — every reference in the "dark UI reduces exposure misjudgment" space uses true black specifically because gray backgrounds still cast a visible tint against a dim shadow-heavy frame.
- Live numeric readouts use a monospaced or tabular-figure font — cinema camera OSDs use fixed-width digits so values don't jitter horizontally as they change; carry that into the Action Slider equivalent.
- Accent color: single accent hue used only for "this is currently being adjusted" state (e.g. the slider you have your thumb on) and record-state (red dot / red border while recording). Everything else stays grayscale so the accent stays meaningful instead of decorative.
- Icons: outline-style, no filled/skeuomorphic icons, no text labels next to icons in the always-visible chrome (labels are fine inside expanded QAM panels where space isn't as tight).

---

## 7. Functional requirements

### 7.1 Capture & manual control
- FR-1: Lock ISO and shutter speed independently via `CONTROL_AE_MODE_OFF` + `SENSOR_SENSITIVITY` / `SENSOR_EXPOSURE_TIME`. Gate this feature behind a runtime check of `INFO_SUPPORTED_HARDWARE_LEVEL` — require `FULL` or better; show a clear "not supported on this device" state on `LIMITED`/`LEGACY` rather than failing silently.
- FR-2: Manual white balance via `CONTROL_AWB_MODE_OFF` + explicit gains, exposed as both a Kelvin-temperature control and a tint control.
- FR-3: Manual focus via lens position, with a focus-pull mode that interpolates between two saved positions over a set duration (Filmic's automated pull is the reference behavior).
- FR-4: Frame rate lock via `activeVideoMinFrameDuration`/`activeVideoMaxFrameDuration` equivalents on Camera2, with shutter angle displayed as a derived value (angle = shutter speed × frame rate × 360, per the 180° rule convention) alongside raw shutter speed — Filmic surfaces both, so should this.
- FR-5: All manual state persists across app backgrounding within a session (don't silently drop to auto on resume).

### 7.2 Monitoring overlays
- FR-6: Zebra stripes with independently adjustable over/under-exposure thresholds, rendered as a diagonal-stripe GPU shader mask on the shader-pass output, not burned into the recorded buffer.
- FR-7: Focus peaking using gradient-magnitude edge detection (Tenengrad or Sobel), colored overlay on high-gradient pixels, adjustable sensitivity.
- FR-8: False color exposure map as a selectable overlay mode, replacing zebra/peaking when active (avoid stacking all three at once — visually incoherent and adds shader cost for no benefit).
- FR-9: All three overlays must run as part of the single GPU shader pass described in Section 8 — not as separate CPU-side post-processing steps.

### 7.3 Color pipeline
- FR-10: Flat/LOG-style capture profile as the recording default, with a 3D LUT applied *only* to the preview path (screen output), leaving the recorded buffer untouched — per the dual-path architecture already scoped.
- FR-11: Support loading `.cube` LUT files from device storage for the preview grade.
- FR-12: LUT application implemented as a single GPU shader pass (color-cube lookup), combined with the overlay shaders in FR-9 into one draw call where feasible, to stay inside frame budget.

### 7.4 Encoding
- FR-13: Manual bitrate control exposed as a direct number (Mbps), not vague quality presets.
- FR-14: Codec selection: AVC (baseline compatibility), HEVC (default for quality/size), AV1 where `MediaCodecList` reports hardware AV1 encode support.
- FR-15: 10-bit HDR (HLG10) capture path, gated behind `REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT` + `DynamicRangeProfiles.HLG10` support check — API 33+ only, must degrade cleanly to 8-bit SDR on devices/OS versions that don't support it.
- FR-16: Verify the selected encoder is hardware-backed (`MediaCodecInfo.isHardwareAccelerated()`) before configuring; refuse to silently fall back to a software encoder without surfacing that to the user, since software encode will not hold frame budget.
- FR-17: Encoder input via `MediaCodec.createInputSurface()` exclusively — no `queueInputBuffer()` byte-array path in the production pipeline.

### 7.5 Audio
- FR-18: External microphone support via `AudioDeviceInfo` enumeration, with a manual input source picker (don't rely on OS default routing).
- FR-19: Live peak/RMS audio meter in the persistent status bar area.
- FR-20: Manual gain control, independent of any AGC.

### 7.6 File output
- FR-21: Scoped-storage-compliant file writes via `MediaStore` (mandatory since API 29 target floor) — no direct external storage path assumptions.
- FR-22: Filename/metadata convention that preserves shooting parameters (resolution, fps, codec, LOG profile used) for later grading reference.

---

## 8. Technical architecture summary

This reuses the zero-copy pipeline already scoped in prior discussion — restated here as the PRD's architectural baseline, not re-derived:

`Camera2 → SurfaceTexture (GPU_SAMPLED_IMAGE) → single GPU shader pass (LUT + zebra + peaking + false color) → forked output: (a) GLSurfaceView for screen, (b) MediaCodec input Surface for hardware encoder → encoded file via MediaStore`

Audio runs as a parallel branch: `AudioRecord (or external mic via AudioDeviceInfo) → gain/metering → MediaCodec audio track → muxed into the same output container`.

No stage in the video path touches CPU-side pixel buffers. This is the hard requirement the frame-budget targets in Section 9 depend on.

---

## 9. Non-functional requirements

- NFR-1: Sustain 30fps (33ms/frame budget) with all three overlays + LUT active, at the device's max supported manual-control resolution, for a continuous 10-minute recording with zero dropped frames.
- NFR-2: Sustain 60fps (16ms/frame budget) with overlays active at 1080p, if the target device supports 60fps capture.
- NFR-3: Detect thermal throttling via `PowerManager.getCurrentThermalStatus()` and degrade gracefully in a defined order: disable false-color/peaking overlays first, then reduce preview resolution, then (last resort) prompt to stop recording — never let the OS silently kill the app mid-record.
- NFR-4: Cold start to first frame on viewfinder under 1.5s.
- NFR-5: Battery: no specific numeric target without device-specific measurement, but instrument and log battery drain per minute of 4K recording as a baseline metric from Milestone 1 onward.
- NFR-6: Storage write throughput must be validated against the highest bitrate the encoder config allows — flag if the target device's storage can't sustain the configured bitrate rather than silently dropping frames at write time.

---

## 10. OS/API compatibility tiers

Since Section 0's recommendation may not be taken, here's the tiering needed to make API 29–36+ actually tractable instead of a single sprawling if/else tree.

| Tier | API range | Capabilities available | Capabilities gated off |
|---|---|---|---|
| **A — baseline** | 29 (Android 10) – 32 (Android 12L) | Camera2 manual control (device-dependent on hardware level), GLES 3.0 shader pipeline for LUT/overlays, 8-bit SDR encode via `MediaCodec` Surface input, scoped storage via `MediaStore` | No AGSL (arrives API 33) — shader pipeline must be hand-written GLSL, not `RuntimeShader`. No 10-bit HDR capture (`DynamicRangeProfiles` arrives API 33). |
| **B — modern** | 33 (Android 13) – 35 (Android 15) | Everything in Tier A, plus optional AGSL (`RuntimeShader`) as an alternative shader authoring path, 10-bit HDR HLG10 capture where device hardware also supports it, HDR viewfinder preview | RAW/DNG still gated on hardware `LEVEL_3`, independent of OS version |
| **C — current** | 36 (Android 16) and newer | Everything in Tier B, plus whatever the current OS adds (predictive back navigation, edge-to-edge enforcement affect UI chrome layout, not capture) | — |

**Practical implication:** build the shader pipeline in GLSL ES 3.0 first — it's the one that runs everywhere in your stated range. Treat AGSL as an optional, later-stage rewrite for devices on API 33+, not the primary implementation, or you'll be maintaining two shader pipelines from day one instead of adding a second one later.

**Hardware-level gating is a second, independent axis from OS version** — a brand-new Android 16 phone can still report Camera2 `LIMITED`, and a five-year-old Android 10 phone can report `FULL`. Never assume OS version implies hardware capability; query `CameraCharacteristics` directly, every time, per device.

---

## 11. Phased milestones

Unchanged in substance from the earlier scoping conversation, restated here as PRD-owned milestones:

1. **M1 — Manual capture core:** ISO/shutter/WB/focus lock + frame rate/shutter angle lock, Tier A GLES pipeline, no overlays yet, 8-bit SDR encode with manual bitrate. This alone is already a better camera than stock.
2. **M2 — Monitoring overlays:** zebra, peaking, false color, all in the single shader pass, plus the Action-Slider-equivalent persistent status bar from Section 6.
3. **M3 — Color pipeline:** LOG capture default + LUT preview loading, dual-path architecture live.
4. **M4 — Tier B capabilities:** AGSL path (optional), 10-bit HDR capture where supported, audio external-mic + metering.
5. **M5 — Polish pass on the UI/UX requirements in Section 6**, once the functional pipeline is proven stable — don't reorder this ahead of M1–M4, a beautiful UI wrapped around a pipeline that drops frames is worse than a plain one that doesn't.

---

## 12. Risks & open questions

- **Open — target device unspecified.** This PRD can't finalize the hardware-level assumptions in Section 7.1 or the resolution/fps ceiling in NFR-1/2 until you specify the actual phone. Fill this in before M1 starts.
- **Open — Section 0 decision not yet made.** Confirm whether `minSdk` 29 stays or narrows to your own device's OS version. This changes whether Tier A (hand-written GLSL) is your *only* pipeline or a fallback you can defer.
- **Risk — thermal throttling on sustained 4K/HDR recording** is the single most likely cause of "works in testing, drops frames in real use." Budget real test time for 10+ minute continuous recording sessions, not just short clips.
- **Risk — AGSL is a genuine convenience but creates a second pipeline to maintain** if you support Tier A at all. Decide up front whether that maintenance cost is worth it versus just shipping GLSL everywhere including Tier B/C devices.

---

## 13. Out of scope (explicit)

- Photo capture, night mode, portrait mode, any computational photography feature
- Cloud backup/sync
- In-app editing or LUT creation tools (LUT *loading* is in scope; LUT *authoring* is not)
- Multi-camera simultaneous capture
- Any Play Store compliance work (privacy labels, data safety forms) unless distribution scope changes

---

## 14. Reference codebases (unchanged from prior research, restated for this document's completeness)

- `google/jetpack-camera-app` — Google's own current open-source reference: CameraX + Camera2Interop manual controls, 10-bit HDR capture and HDR viewfinder preview, actively maintained. Closest available skeleton to Tier B/C of this PRD.
- `nekdenis/camera2mediacodec` — working reference for the zero-copy shader-to-encoder pattern in Section 8.
- Android `HDR video capture` developer documentation — working code for the `DynamicRangeProfiles` path including color-transfer metadata handling.
