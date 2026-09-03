package com.ankush.cinecam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.view.MotionEvent
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Range
import android.util.Size
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ankush.cinecam.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sin

class MainActivity : AppCompatActivity() {
    private enum class HighlightField { NONE, ISO, SHUTTER, WB, FOCUS, FPS, BITRATE, OVERLAY }
    companion object {
        private const val TAG = "CineCam"
    }

    private lateinit var binding: ActivityMainBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val exposureAnalyzer = ExposureAnalyzer { stats -> onFrameStats(stats) }

    private var manualSupported = true
    private var cubeLut: CubeLut? = null

    private var agslEnabled = false
    private var hdr10Enabled = false
    private var agslSupported = false
    private var hdr10Supported = false
    private var hdrApiAvailable = false

    private var micSourceIndex = 0 // 0 internal, 1 external if available
    private var micGain = 10
    private var hasExternalMic = false
    private var externalMicDevice: AudioDeviceInfo? = null
    private val audioMeter = AudioMeter { peak, rms ->
        runOnUiThread {
            binding.meterPeak.progress = peak
            binding.meterRms.progress = rms
        }
    }
    private var useRealMeter = false
    private val meterHandler = Handler(Looper.getMainLooper())
    private var meterLoopRunning = false
    // Simulated-meter fallback state (no mic permission or AudioRecord refused).
    private var simWavePhase = 0L
    private var highlightField: HighlightField = HighlightField.NONE
    private var recordingAudioEnabled = false

    private val isoValues = listOf(100, 200, 400, 800, 1200, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000)
    private val shutterTimesNs = listOf(
        41_666_666L,
        20_833_333L,
        10_416_666L,
        8_333_333L,
        5_555_555L,
        4_166_666L,
        2_777_777L,
        2_083_333L,
        1_388_888L
    )
    private val wbKelvinValues = listOf(2800, 3200, 3800, 4300, 4800, 5200, 5600, 6000, 6500, 7000, 7600, 8200, 9000)
    private val bitrateMbpsValues = listOf(12, 20, 40, 60, 80)

    private var isoIndex = 0
    private var shutterIndex = 1
    private var wbIndex = 6
    private var focusProgress = 0
    private var fps = 24
    private var bitrateMbps = 40

    // P1 capture state.
    private var useFrontLens = false
    private var uhdEnabled = false
    private var aspectRatio = AspectRatio.RATIO_16_9
    private var zoomProgress = 0 // 0..100 mapped onto 1.0..maxZoom
    private var torchOn = false
    private var stabOn = false
    private var eisSupported = false
    private var tintProgress = 0 // -50..+50 green/magenta
    private var aeLocked = false
    private var focusA = 0
    private var focusB = 100
    private var focusPullRunning = false
    private val pullHandler = Handler(Looper.getMainLooper())
    private var pullRunnable: Runnable? = null

    // P1 monitoring state.
    private var guidesMode = FrameGuidesView.Guide.OFF
    private var gridEnabled = false
    private var lastStats: FrameStats? = null
    private var codecCaps = ""
    private var freeGb = -1f

    // Recording timecode.
    private var recordingStartMs = 0L
    private val tcHandler = Handler(Looper.getMainLooper())
    private var tcRunnable: Runnable? = null

    // Multi-slot LUT library (app Documents dir, *.cube).
    private var lutFiles: List<File> = emptyList()
    private var lutFileIndex = -1
    private var lutName = "internal"

    private var overlayMode = MonitoringOverlayView.Mode.NONE
    private var overlayLevel = 60

    private var logPreviewEnabled = true
    private var lutPreviewEnabled = true
    private var lutStrength = 100

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cameraGranted = result[Manifest.permission.CAMERA] == true
            val micGranted = result[Manifest.permission.RECORD_AUDIO] == true
            if (cameraGranted) {
                if (!micGranted) showMicPermissionRecoveryToast()
                restartAudioMonitoring()
                startCamera()
            } else {
                showCameraPermissionRecoveryToast()
                openAppSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreState(savedInstanceState)
        detectCapabilities()
        detectAudioDevices()
        detectCodecCaps()
        logHalVideoSizes()
        scanLutLibrary()
        applyWindowInsets()
        setupUi()
        applyStatusTypography()
        gateUnsupportedSwitches()
        loadDefaultCube()
        reloadSavedLut()
        applyOverlayState()
        applyGuidesState()
        applyPreviewLutState()
        updateStorageInfo()
        updateReadouts()
        startAudioMonitoring()

        if (hasPermissions()) startCamera() else requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Mic permission or plugged-in USB mic may have changed while away.
        val wantReal = hasMicPermission()
        if (wantReal != useRealMeter) restartAudioMonitoring()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("isoIndex", isoIndex)
        outState.putInt("shutterIndex", shutterIndex)
        outState.putInt("wbIndex", wbIndex)
        outState.putInt("focusProgress", focusProgress)
        outState.putInt("fps", fps)
        outState.putInt("bitrateMbps", bitrateMbps)
        outState.putInt("overlayMode", overlayMode.ordinal)
        outState.putInt("overlayLevel", overlayLevel)
        outState.putBoolean("logPreviewEnabled", logPreviewEnabled)
        outState.putBoolean("lutPreviewEnabled", lutPreviewEnabled)
        outState.putInt("lutStrength", lutStrength)
        outState.putBoolean("agslEnabled", agslEnabled)
        outState.putBoolean("hdr10Enabled", hdr10Enabled)
        outState.putInt("micSourceIndex", micSourceIndex)
        outState.putInt("micGain", micGain)
        outState.putBoolean("useFrontLens", useFrontLens)
        outState.putBoolean("uhdEnabled", uhdEnabled)
        outState.putInt("aspectRatio", aspectRatio)
        outState.putInt("zoomProgress", zoomProgress)
        outState.putBoolean("torchOn", torchOn)
        outState.putBoolean("stabOn", stabOn)
        outState.putInt("tintProgress", tintProgress)
        outState.putBoolean("aeLocked", aeLocked)
        outState.putInt("focusA", focusA)
        outState.putInt("focusB", focusB)
        outState.putInt("guidesMode", guidesMode.ordinal)
        outState.putBoolean("gridEnabled", gridEnabled)
        outState.putInt("lutFileIndex", lutFileIndex)
        outState.putString("lutName", lutName)
    }

    override fun onDestroy() {
        recording?.stop()
        stopMeterLoop()
        stopTimecode()
        stopFocusPull()
        audioMeter.stop()
        analysisExecutor.shutdown()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun restoreState(saved: Bundle?) {
        if (saved == null) return
        isoIndex = saved.getInt("isoIndex", isoIndex)
        shutterIndex = saved.getInt("shutterIndex", shutterIndex)
        wbIndex = saved.getInt("wbIndex", wbIndex)
        focusProgress = saved.getInt("focusProgress", focusProgress)
        fps = saved.getInt("fps", fps)
        bitrateMbps = saved.getInt("bitrateMbps", bitrateMbps)
        overlayMode = MonitoringOverlayView.Mode.entries[saved.getInt("overlayMode", overlayMode.ordinal)]
        overlayLevel = saved.getInt("overlayLevel", overlayLevel)
        logPreviewEnabled = saved.getBoolean("logPreviewEnabled", logPreviewEnabled)
        lutPreviewEnabled = saved.getBoolean("lutPreviewEnabled", lutPreviewEnabled)
        lutStrength = saved.getInt("lutStrength", lutStrength)
        agslEnabled = saved.getBoolean("agslEnabled", agslEnabled)
        hdr10Enabled = saved.getBoolean("hdr10Enabled", hdr10Enabled)
        micSourceIndex = saved.getInt("micSourceIndex", micSourceIndex)
        micGain = saved.getInt("micGain", micGain)
        useFrontLens = saved.getBoolean("useFrontLens", useFrontLens)
        uhdEnabled = saved.getBoolean("uhdEnabled", uhdEnabled)
        aspectRatio = saved.getInt("aspectRatio", aspectRatio)
        zoomProgress = saved.getInt("zoomProgress", zoomProgress)
        torchOn = saved.getBoolean("torchOn", torchOn)
        stabOn = saved.getBoolean("stabOn", stabOn)
        tintProgress = saved.getInt("tintProgress", tintProgress)
        aeLocked = saved.getBoolean("aeLocked", aeLocked)
        focusA = saved.getInt("focusA", focusA)
        focusB = saved.getInt("focusB", focusB)
        guidesMode = FrameGuidesView.Guide.entries[saved.getInt("guidesMode", guidesMode.ordinal)]
        gridEnabled = saved.getBoolean("gridEnabled", gridEnabled)
        lutFileIndex = saved.getInt("lutFileIndex", lutFileIndex)
        lutName = saved.getString("lutName", lutName) ?: lutName
    }

    private fun detectCapabilities() {
        agslSupported = Build.VERSION.SDK_INT >= 33
        hdrApiAvailable = runCatching {
            Class.forName("androidx.camera.video.DynamicRange")
            Recorder.Builder::class.java.methods.any { it.name == "setDynamicRange" }
        }.getOrDefault(false)
        hdr10Supported = Build.VERSION.SDK_INT >= 33 && hdrApiAvailable

        if (!agslSupported) agslEnabled = false
        if (!hdr10Supported) hdr10Enabled = false

        Log.d(
            TAG,
            "caps agslSupported=$agslSupported hdr10Supported=$hdr10Supported hdrApiAvailable=$hdrApiAvailable sdk=${Build.VERSION.SDK_INT}"
        )
    }

    private fun detectAudioDevices() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        hasExternalMic = inputs.any { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
        externalMicDevice = inputs.firstOrNull { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (!hasExternalMic && micSourceIndex == 1) {
            micSourceIndex = 0
        }
        Log.d(TAG, "audio inputs=${inputs.joinToString { it.type.toString() }} externalMic=$hasExternalMic")
    }

    /** Query hardware encoder support for display only — the Recorder path stays AVC. */
    private fun detectCodecCaps() {
        var hevcHw = false
        var av1Hw = false
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                val hw = runCatching { info.isHardwareAccelerated }.getOrDefault(false)
                if (!hw) continue
                for (type in info.supportedTypes) {
                    when {
                        type.equals("video/hevc", ignoreCase = true) -> hevcHw = true
                        type.equals("video/av01", ignoreCase = true) -> av1Hw = true
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "codec caps query failed: ${t.message}")
        }
        // Per-encoder max resolution: tells us definitively whether 4K-AVC
        // exists (CameraX Recorder can use it) or only 4K-HEVC (it can't).
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (!type.startsWith("video/", ignoreCase = true)) continue
                    val caps = try {
                        info.getCapabilitiesForType(type)
                    } catch (_: Throwable) {
                        continue
                    }
                    val vcaps = caps.videoCapabilities ?: continue
                    val maxW = try { vcaps.supportedWidths.upper } catch (_: Throwable) { -1 }
                    val maxH = try { vcaps.supportedHeights.upper } catch (_: Throwable) { -1 }
                    val hw = runCatching { info.isHardwareAccelerated }.getOrDefault(false)
                    Log.d(TAG, "encoder $type hw=$hw max=${maxW}x${maxH} ${info.name}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "encoder size query failed: ${t.message}")
        }
        codecCaps = "AVC-HW" +
            (if (hevcHw) " · HEVC-HW" else " · HEVC-SW?") +
            (if (av1Hw) " · AV1-HW" else "")
        runCatching {
            binding.textCodecInfo.text =
                "Encoders (HW): $codecCaps. Recording is AVC via CameraX; HEVC/AV1 switch needs an encoder-factory path. Unsupported options fall back to safe mode."
        }
        Log.d(TAG, "codec caps: $codecCaps")
    }

    /** Honest gating: on SDK <33 these are labels for a future pipeline, not switches. */
    private fun gateUnsupportedSwitches() {
        if (!agslSupported) {
            binding.switchAgsl.isEnabled = false
            binding.switchAgsl.alpha = 0.4f
        }
        if (!hdr10Supported) {
            binding.switchHdr10.isEnabled = false
            binding.switchHdr10.alpha = 0.4f
        }
    }

    private fun setupUi() {
        binding.sliderIso.progress = isoIndex
        binding.sliderEdgeIso.progress = isoIndex
        binding.sliderShutter.progress = shutterIndex
        binding.sliderWb.progress = wbIndex
        binding.sliderFocus.progress = focusProgress
        binding.sliderEdgeFocus.progress = focusProgress
        binding.sliderFps.progress = fpsToProgress(fps)
        binding.sliderBitrate.progress = bitrateMbpsValues.indexOf(bitrateMbps).coerceAtLeast(0)
        binding.sliderOverlayMode.progress = overlayMode.ordinal
        binding.sliderOverlayLevel.progress = overlayLevel
        binding.switchLogPreview.isChecked = logPreviewEnabled
        binding.switchLutPreview.isChecked = lutPreviewEnabled
        binding.sliderLutStrength.progress = lutStrength
        binding.switchAgsl.isChecked = agslEnabled
        binding.switchHdr10.isChecked = hdr10Enabled
        binding.sliderMicSource.max = if (hasExternalMic) 1 else 0
        binding.sliderMicSource.progress = micSourceIndex
        binding.sliderMicGain.progress = micGain
        binding.buttonLens.text = getString(if (useFrontLens) R.string.lens_front else R.string.lens_back)
        binding.buttonQuality.text = getString(if (uhdEnabled) R.string.quality_uhd else R.string.quality_fhd)
        binding.buttonAspect.text = getString(if (aspectRatio == AspectRatio.RATIO_4_3) R.string.aspect_43 else R.string.aspect_169)
        binding.buttonTorch.text = getString(if (torchOn) R.string.torch_on else R.string.torch_off)
        binding.sliderZoom.progress = zoomProgress.coerceIn(0, 100)
        binding.switchStab.isChecked = stabOn
        binding.sliderTint.progress = (tintProgress + 50).coerceIn(0, 100)
        binding.readoutTint.text = tintProgress.toString()
        binding.buttonAeLock.text = getString(if (aeLocked) R.string.ae_locked else R.string.ae_unlocked)
        binding.sliderGuides.progress = guidesMode.ordinal
        binding.readoutGuides.text = guideName(guidesMode)
        binding.switchGrid.isChecked = gridEnabled

        binding.sliderIso.setOnSeekBarChangeListener(simpleSeek {
            if (aeLocked) {
                binding.sliderIso.progress = isoIndex
                Toast.makeText(this, getString(R.string.ae_locked), Toast.LENGTH_SHORT).show()
                return@simpleSeek
            }
            isoIndex = it
            binding.sliderEdgeIso.progress = it
            highlightField = HighlightField.ISO
            updateReadouts()
            applyManualCameraState()
        })
        binding.sliderShutter.setOnSeekBarChangeListener(simpleSeek {
            if (aeLocked) {
                binding.sliderShutter.progress = shutterIndex
                Toast.makeText(this, getString(R.string.ae_locked), Toast.LENGTH_SHORT).show()
                return@simpleSeek
            }
            shutterIndex = it
            highlightField = HighlightField.SHUTTER
            updateReadouts()
            applyManualCameraState()
        })
        binding.sliderWb.setOnSeekBarChangeListener(simpleSeek {
            wbIndex = it
            highlightField = HighlightField.WB
            updateReadouts()
            applyManualCameraState()
        })
        binding.sliderFocus.setOnSeekBarChangeListener(simpleSeek {
            focusProgress = it
            binding.sliderEdgeFocus.progress = it
            highlightField = HighlightField.FOCUS
            updateReadouts()
            applyManualCameraState()
        })
        binding.sliderFps.setOnSeekBarChangeListener(simpleSeek {
            val want = progressToFps(it)
            if (want == 60 && !is60FpsSupported()) {
                binding.sliderFps.progress = fpsToProgress(fps)
                Toast.makeText(this, getString(R.string.fps60_unsupported_fallback), Toast.LENGTH_SHORT).show()
                Log.w(TAG, "60fps requested but no FPS range covers 60 -> keeping $fps")
                return@simpleSeek
            }
            fps = want
            highlightField = HighlightField.FPS
            updateReadouts()
            applyManualCameraState()
            requestRecorderRebind()
        })
        binding.sliderBitrate.setOnSeekBarChangeListener(simpleSeek {
            bitrateMbps = bitrateMbpsValues[it]
            highlightField = HighlightField.BITRATE
            updateReadouts()
            requestRecorderRebind()
        })

        binding.sliderOverlayMode.setOnSeekBarChangeListener(simpleSeek {
            overlayMode = MonitoringOverlayView.Mode.entries[it]
            highlightField = HighlightField.OVERLAY
            applyOverlayState()
            updateReadouts()
        })
        binding.sliderOverlayLevel.setOnSeekBarChangeListener(simpleSeek {
            overlayLevel = it
            applyOverlayState()
            updateReadouts()
        })

        binding.switchLogPreview.setOnCheckedChangeListener { _, checked ->
            logPreviewEnabled = checked
            applyPreviewLutState()
            updateReadouts()
        }
        binding.switchLutPreview.setOnCheckedChangeListener { _, checked ->
            lutPreviewEnabled = checked
            applyPreviewLutState()
            updateReadouts()
        }
        binding.sliderLutStrength.setOnSeekBarChangeListener(simpleSeek {
            lutStrength = it
            applyPreviewLutState()
            updateReadouts()
        })
        binding.buttonLoadCube.setOnClickListener { loadCubeFromStorage() }

        binding.switchAgsl.setOnCheckedChangeListener { _, checked ->
            if (checked && !agslSupported) {
                binding.switchAgsl.isChecked = false
                Toast.makeText(this, getString(R.string.agsl_unsupported_fallback), Toast.LENGTH_SHORT).show()
                Log.w(TAG, "AGSL requested but unsupported -> GLSL fallback")
            } else {
                agslEnabled = checked
                updateReadouts()
                Log.d(TAG, "AGSL ${if (agslEnabled) "enabled" else "fallback GLSL"}")
            }
        }

        binding.switchHdr10.setOnCheckedChangeListener { _, checked ->
            if (checked && !hdr10Supported) {
                binding.switchHdr10.isChecked = false
                Toast.makeText(this, getString(R.string.hdr10_unsupported_fallback), Toast.LENGTH_SHORT).show()
                Log.w(TAG, "HDR10 requested but unsupported -> SDR fallback")
            } else {
                hdr10Enabled = checked
                updateReadouts()
                requestRecorderRebind()
                Log.d(TAG, "HDR10 ${if (hdr10Enabled) "requested" else "SDR fallback"}")
            }
        }

        binding.sliderMicSource.setOnSeekBarChangeListener(simpleSeek {
            micSourceIndex = if (hasExternalMic) it else 0
            updateReadouts()
            if (!hasExternalMic && it == 1) {
                Log.w(TAG, "external mic requested but unavailable -> internal mic fallback")
            }
            Log.d(TAG, "mic source=${if (micSourceIndex == 1) "EXTERNAL" else "INTERNAL"} supported=$hasExternalMic")
            restartAudioMonitoring()
            requestRecorderRebind()
        })

        binding.sliderMicGain.setOnSeekBarChangeListener(simpleSeek {
            micGain = it
            audioMeter.gain = micGain
            updateReadouts()
            Log.d(TAG, "mic gain=$micGain")
        })

        binding.buttonLens.setOnClickListener {
            if (recording != null) {
                Toast.makeText(this, getString(R.string.stop_recording_before_apply), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            useFrontLens = !useFrontLens
            binding.buttonLens.text = getString(if (useFrontLens) R.string.lens_front else R.string.lens_back)
            Log.d(TAG, "lens -> ${if (useFrontLens) "FRONT" else "BACK"}")
            requestRecorderRebind()
        }

        binding.buttonQuality.setOnClickListener {
            if (recording != null) {
                Toast.makeText(this, getString(R.string.stop_recording_before_apply), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val wantUhd = !uhdEnabled
            if (wantUhd && !isUhdSupported()) {
                Toast.makeText(this, getString(R.string.uhd_unsupported_fallback), Toast.LENGTH_SHORT).show()
                Log.w(TAG, "UHD requested but unsupported -> staying FHD")
                return@setOnClickListener
            }
            uhdEnabled = wantUhd
            binding.buttonQuality.text = getString(if (uhdEnabled) R.string.quality_uhd else R.string.quality_fhd)
            updateReadouts()
            requestRecorderRebind()
        }

        binding.buttonAspect.setOnClickListener {
            if (recording != null) {
                Toast.makeText(this, getString(R.string.stop_recording_before_apply), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            aspectRatio = if (aspectRatio == AspectRatio.RATIO_16_9) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
            binding.buttonAspect.text = getString(if (aspectRatio == AspectRatio.RATIO_4_3) R.string.aspect_43 else R.string.aspect_169)
            updateReadouts()
            requestRecorderRebind()
        }

        binding.buttonTorch.setOnClickListener {
            val want = !torchOn
            if (want && camera?.cameraInfo?.hasFlashUnit() != true) {
                Toast.makeText(this, getString(R.string.no_flash_unit), Toast.LENGTH_SHORT).show()
                Log.w(TAG, "torch requested but no flash unit on this lens")
                return@setOnClickListener
            }
            torchOn = want
            binding.buttonTorch.text = getString(if (torchOn) R.string.torch_on else R.string.torch_off)
            applyTorchState()
        }

        binding.sliderZoom.setOnSeekBarChangeListener(simpleSeek {
            zoomProgress = it.coerceIn(0, 100)
            applyZoomState()
            updateReadouts()
        })

        binding.switchStab.setOnCheckedChangeListener { _, checked ->
            if (checked && !eisSupported) {
                binding.switchStab.isChecked = false
                Toast.makeText(this, getString(R.string.stab_unsupported_fallback), Toast.LENGTH_SHORT).show()
                Log.w(TAG, "stabilization requested but EIS modes lack ON -> staying off")
            } else {
                if (checked == stabOn) return@setOnCheckedChangeListener
                if (recording != null) {
                    binding.switchStab.isChecked = stabOn
                    Toast.makeText(this, getString(R.string.stop_recording_before_apply), Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                stabOn = checked
                requestRecorderRebind()
                Log.d(TAG, "preview stabilization=$stabOn")
            }
        }

        binding.sliderTint.setOnSeekBarChangeListener(simpleSeek {
            tintProgress = (it - 50).coerceIn(-50, 50)
            binding.readoutTint.text = tintProgress.toString()
            highlightField = HighlightField.WB
            updateReadouts()
            applyManualCameraState()
        })

        binding.buttonAeLock.setOnClickListener {
            aeLocked = !aeLocked
            binding.buttonAeLock.text = getString(if (aeLocked) R.string.ae_locked else R.string.ae_unlocked)
            setManualControlsEnabled(manualSupported)
            Log.d(TAG, "AE lock=$aeLocked ISO=${isoValues[isoIndex]} shutterNs=${shutterTimesNs[shutterIndex]}")
        }

        binding.buttonFocusA.setOnClickListener {
            focusA = focusProgress
            Toast.makeText(this, "A=$focusA", Toast.LENGTH_SHORT).show()
        }
        binding.buttonFocusB.setOnClickListener {
            focusB = focusProgress
            Toast.makeText(this, "B=$focusB", Toast.LENGTH_SHORT).show()
        }
        binding.buttonFocusPull.setOnClickListener { startFocusPull() }

        binding.sliderGuides.setOnSeekBarChangeListener(simpleSeek {
            guidesMode = FrameGuidesView.Guide.entries[it.coerceIn(0, 5)]
            binding.readoutGuides.text = guideName(guidesMode)
            applyGuidesState()
        })
        binding.switchGrid.setOnCheckedChangeListener { _, checked ->
            gridEnabled = checked
            applyGuidesState()
        }

        binding.buttonPreset1.setOnClickListener { recallPreset(1) }
        binding.buttonPreset2.setOnClickListener { recallPreset(2) }
        binding.buttonPreset3.setOnClickListener { recallPreset(3) }
        binding.buttonPreset1.setOnLongClickListener { savePreset(1); true }
        binding.buttonPreset2.setOnLongClickListener { savePreset(2); true }
        binding.buttonPreset3.setOnLongClickListener { savePreset(3); true }

        // Single-shot tap-to-focus: one AF scan on the tapped subject, then
        // an immediate AF_MODE_OFF lock. Tapping elsewhere refocuses there.
        // Never continuous AF — no hunting during the shot.
        //
        // The listener sits on the topmost preview layer and fires on
        // ACTION_DOWN: with all overlays non-clickable, nothing consumes
        // ACTION_DOWN, so the system would never route ACTION_UP to us.
        binding.focusIndicator.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleFocusTap(event.x, event.y)
                true
            } else {
                false
            }
        }

        binding.sliderEdgeIso.setOnSeekBarChangeListener(simpleSeek {
            if (aeLocked) {
                binding.sliderEdgeIso.progress = isoIndex
                Toast.makeText(this, getString(R.string.ae_locked), Toast.LENGTH_SHORT).show()
                return@simpleSeek
            }
            isoIndex = it
            binding.sliderIso.progress = it
            highlightField = HighlightField.ISO
            updateReadouts()
            applyManualCameraState()
        })

        binding.sliderEdgeFocus.setOnSeekBarChangeListener(simpleSeek {
            focusProgress = it
            binding.sliderFocus.progress = it
            highlightField = HighlightField.FOCUS
            updateReadouts()
            applyManualCameraState()
        })

        binding.buttonFn.setOnClickListener {
            overlayMode = MonitoringOverlayView.Mode.entries[(overlayMode.ordinal + 1) % MonitoringOverlayView.Mode.entries.size]
            binding.sliderOverlayMode.progress = overlayMode.ordinal
            applyOverlayState()
            updateReadouts()
            Log.d(TAG, "FN mapped to overlay mode cycle -> $overlayMode")
        }

        binding.recordButton.setOnClickListener {
            if (recording == null) {
                if (!hasPermissions()) {
                    requestPermissions()
                } else {
                    startRecording()
                }
            } else {
                stopRecording()
            }
        }
    }

    private fun applyStatusTypography() {
        val monos = Typeface.MONOSPACE
        listOf(
            binding.readoutIso,
            binding.readoutShutter,
            binding.readoutWb,
            binding.readoutFocus,
            binding.readoutLog,
            binding.readoutLut,
            binding.readoutHdr,
            binding.readoutAgsl,
            binding.readoutMic,
            binding.readoutFps,
            binding.readoutBitrate,
            binding.readoutOverlayMode,
            binding.readoutOverlayLevel,
            binding.readoutMicSource,
            binding.readoutMicGain,
            binding.readoutState,
            binding.readoutClip,
            binding.readoutCodec,
            binding.readoutStorage,
            binding.readoutTc,
            binding.readoutZoom,
            binding.readoutAspect
        ).forEach { it.typeface = monos }
    }

    private fun updateReadouts() {
        val shutterNs = shutterTimesNs[shutterIndex]
        val shutterDenominator = max(1, 1_000_000_000L / shutterNs)
        val shutterAngle = (shutterNs / 1_000_000_000f) * fps * 360f
        binding.readoutIso.text = getString(R.string.readout_iso, isoValues[isoIndex])
        binding.readoutShutter.text = getString(R.string.readout_shutter, shutterDenominator, shutterAngle.toInt())
        binding.readoutWb.text = getString(R.string.readout_wb, wbKelvinValues[wbIndex])
        binding.readoutFocus.text = getString(R.string.readout_focus, "%.2f".format(Locale.US, focusProgress / 100f))
        binding.readoutFps.text = fps.toString()
        binding.readoutBitrate.text = bitrateMbps.toString()

        binding.readoutOverlayMode.text = when (overlayMode) {
            MonitoringOverlayView.Mode.NONE -> getString(R.string.overlay_mode_off)
            MonitoringOverlayView.Mode.ZEBRA -> getString(R.string.overlay_mode_zebra)
            MonitoringOverlayView.Mode.PEAKING -> getString(R.string.overlay_mode_peak)
            MonitoringOverlayView.Mode.FALSE_COLOR -> getString(R.string.overlay_mode_false)
        }
        binding.readoutOverlayLevel.text = overlayLevel.toString()
        binding.readoutLog.text = if (logPreviewEnabled) getString(R.string.log_mode_log) else getString(R.string.log_mode_lin)
        binding.readoutLut.text = if (lutPreviewEnabled && cubeLut != null) {
            "$lutName $lutStrength%"
        } else {
            getString(R.string.readout_lut_off)
        }

        binding.readoutAgsl.text = if (agslEnabled && agslSupported) getString(R.string.readout_agsl) else getString(R.string.readout_glsl)
        binding.readoutHdr.text = if (hdr10Enabled && hdr10Supported) getString(R.string.readout_hlg10) else getString(R.string.readout_sdr)
        binding.readoutMic.text = if (micSourceIndex == 1 && hasExternalMic) getString(R.string.readout_mic_ext) else getString(R.string.readout_mic_int)
        binding.readoutMicSource.text = if (micSourceIndex == 1 && hasExternalMic) getString(R.string.mic_source_ext) else getString(R.string.mic_source_int)
        binding.readoutMicGain.text = micGain.toString()

        val s = lastStats
        binding.readoutClip.text = if (s == null) {
            getString(R.string.clip_none)
        } else {
            val over = (s.overFrac * 100f)
            val under = (s.underFrac * 100f)
            if (over >= 0.2f || under >= 0.5f) "CLIP H %.1f%% L %.1f%%".format(Locale.US, over, under)
            else getString(R.string.clip_none)
        }
        binding.readoutCodec.text = (if (uhdEnabled) "UHD" else "FHD") + "·AVC"
        binding.readoutAspect.text = if (aspectRatio == AspectRatio.RATIO_4_3) "4:3" else "16:9"
        binding.readoutTint.text = tintProgress.toString()
        binding.readoutZoom.text = "%.1fx".format(Locale.US, ratioFromProgress(zoomProgress))
        binding.readoutStorage.text = if (freeGb < 0) {
            "-- GB"
        } else {
            val mins = (freeGb * 8000f / bitrateMbps.coerceAtLeast(1)).toInt()
            "%.1fG~%dm".format(Locale.US, freeGb, mins)
        }
        if (recording == null && binding.readoutTc.text.toString().startsWith("REC")) {
            binding.readoutTc.text = getString(R.string.idle)
        }

        applyAccentHighlight()
    }

    private fun applyAccentHighlight() {
        val accent = ContextCompat.getColor(this, R.color.cinecam_accent)
        val normal = ContextCompat.getColor(this, R.color.cinecam_text_primary)

        val targets = listOf(
            HighlightField.ISO to binding.readoutIso,
            HighlightField.SHUTTER to binding.readoutShutter,
            HighlightField.WB to binding.readoutWb,
            HighlightField.FOCUS to binding.readoutFocus,
            HighlightField.FPS to binding.readoutFps,
            HighlightField.BITRATE to binding.readoutBitrate,
            HighlightField.OVERLAY to binding.readoutOverlayMode
        )
        targets.forEach { (field, view) ->
            view.setTextColor(if (field == highlightField) accent else normal)
        }
    }

    /**
     * Monitoring-path metering. Prefers the real [AudioMeter] (parallel
     * AudioRecord, never touches the Recorder path); falls back to the
     * legacy simulated animation when mic permission is missing or the
     * device refuses the stream. The meters are monitoring aids only.
     */
    private fun startAudioMonitoring() {
        audioMeter.gain = micGain
        useRealMeter = hasMicPermission() && audioMeter.start(preferredMeterDevice())
        if (!useRealMeter) startMeterLoop()
        Log.d(TAG, "audio metering real=$useRealMeter")
    }

    private fun restartAudioMonitoring() {
        audioMeter.stop()
        stopMeterLoop()
        startAudioMonitoring()
    }

    private fun preferredMeterDevice(): AudioDeviceInfo? {
        return if (micSourceIndex == 1) externalMicDevice else null
    }

    private fun updateAudioMeters() {
        simWavePhase += 120L
        val base = (micGain * 2).coerceIn(5, 90)
        val wave = if (recording != null) ((sin(simWavePhase / 170.0) + 1.0) * 0.5) else 0.2
        val peak = (base + wave * 30.0).toInt().coerceIn(0, 100)
        val rms = (base * 0.75 + wave * 20.0).toInt().coerceIn(0, 100)
        binding.meterPeak.progress = peak
        binding.meterRms.progress = rms
    }

    private fun startMeterLoop() {
        if (meterLoopRunning) return
        meterLoopRunning = true
        meterHandler.post(object : Runnable {
            override fun run() {
                if (!meterLoopRunning) return
                updateAudioMeters()
                meterHandler.postDelayed(this, 120L)
            }
        })
    }

    private fun stopMeterLoop() {
        meterLoopRunning = false
        meterHandler.removeCallbacksAndMessages(null)
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun applyWindowInsets() {
        val statusInitialTop = binding.statusBar.paddingTop
        val controlInitialBottom = binding.controlPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.statusBar.setPadding(
                binding.statusBar.paddingLeft,
                statusInitialTop + bars.top,
                binding.statusBar.paddingRight,
                binding.statusBar.paddingBottom
            )
            binding.controlPanel.setPadding(
                binding.controlPanel.paddingLeft,
                binding.controlPanel.paddingTop,
                binding.controlPanel.paddingRight,
                controlInitialBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun showCameraPermissionRecoveryToast() {
        Toast.makeText(this, getString(R.string.camera_permission_required_open_settings), Toast.LENGTH_LONG).show()
    }

    private fun showMicPermissionRecoveryToast() {
        Toast.makeText(this, getString(R.string.mic_permission_missing_video_only), Toast.LENGTH_LONG).show()
    }

    private fun openAppSettings() {
        runCatching {
            val uri: Uri = "package:$packageName".toUri()
            startActivity(android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        }.onFailure {
            Log.w(TAG, "failed to open app settings: ${it.message}")
        }
    }

    private fun requestRecorderRebind() {
        if (recording != null) {
            Toast.makeText(this, getString(R.string.stop_recording_before_apply), Toast.LENGTH_SHORT).show()
            return
        }
        bindCameraUseCases()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ bindCameraUseCases(future.get()) }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(provider: ProcessCameraProvider? = null) {
        val cameraProvider = provider ?: ProcessCameraProvider.getInstance(this).get()
        val lensSelector = if (useFrontLens) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA
        // Per-lens EIS support *before* building: never request stabilization
        // the hardware lacks, or the bind throws and the preview goes black.
        eisSupported = queryEisForLens(useFrontLens)
        if (stabOn && !eisSupported) {
            stabOn = false
            binding.switchStab.isChecked = false
            Toast.makeText(this, getString(R.string.stab_unsupported_fallback), Toast.LENGTH_SHORT).show()
            Log.w(TAG, "stab requested on lens without EIS -> off")
        }
        val preview = Preview.Builder()
            .setTargetAspectRatio(aspectRatio)
            .setPreviewStabilizationEnabled(stabOn)
            .build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

        val qualitySelector = if (uhdEnabled) {
            QualitySelector.fromOrderedList(listOf(Quality.UHD, Quality.FHD))
        } else {
            QualitySelector.from(Quality.FHD)
        }
        val recorderBuilder = Recorder.Builder()
            .setExecutor(cameraExecutor)
            .setQualitySelector(qualitySelector)
            .setAspectRatio(aspectRatio)
            // Derive qualities from MediaCodec capabilities, not camcorder
            // profiles: on several devices (incl. this Realme) the profiles
            // hide 4K the hardware encoder can actually do.
            .setVideoCapabilitiesSource(Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)
            .setTargetVideoEncodingBitRate(bitrateMbps * 1_000_000)
            .setAudioSource(
                if (micSourceIndex == 1 && hasExternalMic) MediaRecorder.AudioSource.MIC
                else MediaRecorder.AudioSource.CAMCORDER
            )

        if (hdr10Enabled) {
            val applied = tryApplyHdr10DynamicRange(recorderBuilder)
            if (!applied) {
                hdr10Enabled = false
                Log.w(TAG, "HDR10 requested but unavailable in current CameraX/runtime, using SDR fallback")
            }
        }

        videoCapture = VideoCapture.withOutput(recorderBuilder.build())

        // Frame-analysis use case is best-effort: if the device refuses the
        // combination, capture must still work without monitoring stats.
        val analysis: ImageAnalysis? = try {
            exposureAnalyzer.overThreshold = overThresholdForLevel(overlayLevel)
            exposureAnalyzer.underThreshold = underThresholdForLevel(overlayLevel)
            ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, exposureAnalyzer) }
        } catch (t: Throwable) {
            Log.w(TAG, "analysis use case build failed, monitoring stats off: ${t.message}")
            null
        }
        imageAnalysis = analysis

        try {
            cameraProvider.unbindAll()
            camera = if (analysis != null) {
                try {
                    cameraProvider.bindToLifecycle(this, lensSelector, preview, videoCapture, analysis)
                } catch (t: Throwable) {
                    Log.w(TAG, "bind with analysis failed, retrying capture-only: ${t.message}")
                    binding.monitoringOverlay.clearAnalysis()
                    binding.histogramView.clearStats()
                    lastStats = null
                    cameraProvider.bindToLifecycle(this, lensSelector, preview, videoCapture)
                }
            } else {
                cameraProvider.bindToLifecycle(this, lensSelector, preview, videoCapture)
            }
            evaluateManualSupport()
            applyManualCameraState()
            applyZoomState()
            applyTorchState()
            try {
                val quals = QualitySelector.getSupportedQualities(camera!!.cameraInfo)
                val uhdRes = QualitySelector.getResolution(camera!!.cameraInfo, Quality.UHD)
                Log.d(TAG, "qualities=$quals uhdRes=$uhdRes aspect=$aspectRatio")
            } catch (t: Throwable) {
                Log.w(TAG, "quality introspection failed: ${t.message}")
            }
            updateReadouts()
        } catch (t: Throwable) {
            if (aspectRatio != AspectRatio.RATIO_16_9) {
                // 4:3 has no encoder-compatible size on this lens: fall back
                // to 16:9 and rebind instead of leaving a black preview.
                Log.w(TAG, "bind failed at 4:3, retrying 16:9: ${t.message}")
                aspectRatio = AspectRatio.RATIO_16_9
                binding.buttonAspect.text = getString(R.string.aspect_169)
                try {
                    bindCameraUseCases(cameraProvider)
                } catch (t2: Throwable) {
                    Toast.makeText(this, getString(R.string.camera_bind_failed, t2.message ?: "unknown"), Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.camera_bind_failed, t.message ?: "unknown"), Toast.LENGTH_LONG).show()
                Log.w(TAG, "camera bind failed, fallback likely active: ${t.message}")
            }
        }
    }

    private fun onFrameStats(stats: FrameStats) {
        lastStats = stats
        binding.monitoringOverlay.setAnalysis(stats)
        binding.histogramView.setStats(stats)
        runOnUiThread { updateClipReadout(stats) }
    }

    private fun updateClipReadout(stats: FrameStats) {
        val over = (stats.overFrac * 100f)
        val under = (stats.underFrac * 100f)
        binding.readoutClip.text = if (over >= 0.2f || under >= 0.5f) {
            "CLIP H %.1f%% L %.1f%%".format(Locale.US, over, under)
        } else {
            getString(R.string.clip_none)
        }
    }

    private fun overThresholdForLevel(level: Int): Int = (255 - (100 - level) * 0.6).toInt().coerceIn(180, 255)
    private fun underThresholdForLevel(level: Int): Int = (20 + (100 - level) * 0.2).toInt().coerceIn(8, 60)

    private fun fpsToProgress(value: Int): Int = when (value) {
        30 -> 1
        60 -> 2
        else -> 0
    }

    private fun progressToFps(progress: Int): Int = when (progress) {
        1 -> 30
        2 -> 60
        else -> 24
    }

    private fun isUhdSupported(): Boolean {
        val info = camera?.cameraInfo
        return if (info != null) {
            runCatching { QualitySelector.getResolution(info, Quality.UHD) != null }.getOrDefault(true)
        } else true
    }

    private fun is60FpsSupported(): Boolean {
        return try {
            val cam = camera ?: return true
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val ranges = info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ) ?: return false
            Log.d(TAG, "AE fps ranges=${ranges.joinToString()}")
            ranges.any { it.upper >= 60 }
        } catch (_: Throwable) {
            false
        }
    }

    /** HAL truth: which sizes the camera exposes to MediaRecorder vs others. */
    private fun logHalVideoSizes() {
        try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            for (id in mgr.cameraIdList) {
                val chars = mgr.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val mr = map.getOutputSizes(MediaRecorder::class.java)
                    ?.take(12)?.joinToString { "${it.width}x${it.height}" }
                Log.d(TAG, "cam $id facing=$facing MediaRecorder top sizes=[$mr]")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hal sizes query failed: ${t.message}")
        }
    }

    /** Direct Camera2 query so we never request EIS the lens lacks. */
    private fun queryEisForLens(front: Boolean): Boolean {
        return try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            for (id in mgr.cameraIdList) {
                val chars = mgr.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val match = if (front) facing == CameraCharacteristics.LENS_FACING_FRONT
                else facing == CameraCharacteristics.LENS_FACING_BACK
                if (match) {
                    val modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    if (modes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) return true
                }
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "EIS query failed: ${t.message}")
            false
        }
    }

    private fun maxZoom(): Float {
        return try {
            camera?.cameraInfo?.zoomState?.value?.maxZoomRatio?.coerceAtLeast(1f) ?: 4f
        } catch (_: Throwable) {
            4f
        }
    }

    private fun ratioFromProgress(progress: Int): Float {
        val max = maxZoom()
        return (1f + (max - 1f) * progress.coerceIn(0, 100) / 100f).coerceIn(1f, max)
    }

    /** Live zoom, no rebind. Clamps to the bound lens's real max zoom. */
    private fun applyZoomState() {
        val cam = camera ?: return
        val ratio = ratioFromProgress(zoomProgress)
        try {
            cam.cameraControl.setZoomRatio(ratio)
        } catch (t: Throwable) {
            Log.w(TAG, "setZoomRatio failed: ${t.message}")
        }
    }

    /** Torch resets on unbind, so every bind re-applies it. */
    private fun applyTorchState() {
        val cam = camera ?: return
        if (!torchOn) {
            runCatching { cam.cameraControl.enableTorch(false) }
            return
        }
        if (cam.cameraInfo.hasFlashUnit() != true) {
            torchOn = false
            binding.buttonTorch.text = getString(R.string.torch_off)
            return
        }
        try {
            cam.cameraControl.enableTorch(true)
        } catch (t: Throwable) {
            Log.w(TAG, "enableTorch failed: ${t.message}")
        }
    }
    /** Single-shot tap-to-focus with lock. Safe to use while recording. */
    private fun handleFocusTap(x: Float, y: Float) {
        val cam = camera ?: return
        val point = try {
            binding.previewView.meteringPointFactory.createPoint(x, y)
        } catch (t: Throwable) {
            Log.w(TAG, "focus tap: bad point: ${t.message}")
            return
        }
        binding.focusIndicator.showAt(x, y)
        runCatching { cam.cameraControl.cancelFocusAndMetering() }
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .disableAutoCancel()
            .build()
        val done: (Boolean) -> Unit = { ok ->
            runOnUiThread {
                binding.focusIndicator.setResult(ok)
                Log.d(TAG, "tap-focus ${if (ok) "locked" else "failed"} at $x,$y")
            }
        }
        if (!manualSupported) {
            // Auto-fallback device: trigger one AF scan, leave the
            // continuous mode alone so preview keeps working.
            val f = cam.cameraControl.startFocusAndMetering(action)
            f.addListener({
                val ok = try { f.get()?.isFocusSuccessful == true } catch (_: Exception) { false }
                done(ok)
            }, ContextCompat.getMainExecutor(this))
            Log.d(TAG, "tap-focus scan (auto device) at $x,$y")
            return
        }
        // Manual device: momentary AF_MODE_AUTO so the scan runs. On
        // completion the mode is deliberately LEFT at AUTO: a completed
        // AUTO scan holds the lens position (that IS the lock). Re-applying
        // the manual state here would slew the lens back to the focus
        // slider's distance — the focus snap-back bug. The slider takes over
        // again only when the user next drags it.
        val control = Camera2CameraControl.from(cam.cameraControl)
        control.addCaptureRequestOptions(
            CaptureRequestOptionsBuilder()
                .set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                .build()
        ).addListener({
            val f = cam.cameraControl.startFocusAndMetering(action)
            f.addListener({
                val ok = try { f.get()?.isFocusSuccessful == true } catch (_: Exception) { false }
                done(ok)
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun guideName(guide: FrameGuidesView.Guide): String = when (guide) {
        FrameGuidesView.Guide.OFF -> getString(R.string.guide_off)
        FrameGuidesView.Guide.SIXTEEN_NINE -> getString(R.string.guide_16x9)
        FrameGuidesView.Guide.TWO_THREE_NINE -> getString(R.string.guide_239)
        FrameGuidesView.Guide.ONE_ONE -> getString(R.string.guide_1x1)
        FrameGuidesView.Guide.FOUR_THREE -> getString(R.string.guide_43)
        FrameGuidesView.Guide.NINE_SIXTEEN -> getString(R.string.guide_916)
    }

    private fun applyGuidesState() {
        binding.frameGuides.setGuide(guidesMode)
        binding.frameGuides.setGrid(gridEnabled)
    }

    private fun evaluateManualSupport() {
        val cam = camera ?: return
        val cameraInfo = Camera2CameraInfo.from(cam.cameraInfo)
        val capabilities = cameraInfo.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val hardwareLevel = cameraInfo.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

        val hasManualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val hardwareLevelOk = hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
            hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3

        manualSupported = hasManualSensor && hardwareLevelOk
        setManualControlsEnabled(manualSupported)
        try {
            val ranges = cameraInfo.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )
            Log.d(TAG, "AE fps ranges=${ranges?.joinToString()}")
        } catch (_: Throwable) { }
        if (manualSupported) {
            if (binding.readoutState.text == getString(R.string.manual_unsupported)) {
                binding.readoutState.text = getString(R.string.idle)
            }
            Log.d(TAG, "manual controls supported level=${hardwareLevelName(hardwareLevel)}")
        } else {
            binding.readoutState.text = getString(R.string.manual_unsupported)
            Toast.makeText(this, getString(R.string.manual_unsupported), Toast.LENGTH_LONG).show()
            Log.w(TAG, "manual controls unsupported level=${hardwareLevelName(hardwareLevel)} manualSensor=$hasManualSensor")
        }
    }

    private fun setManualControlsEnabled(enabled: Boolean) {
        binding.sliderIso.isEnabled = enabled && !aeLocked
        binding.sliderShutter.isEnabled = enabled && !aeLocked
        binding.sliderWb.isEnabled = enabled
        binding.sliderFocus.isEnabled = enabled
        binding.sliderFps.isEnabled = enabled
        binding.sliderEdgeIso.isEnabled = enabled && !aeLocked
        binding.sliderEdgeFocus.isEnabled = enabled
    }

    /** Controls that force a recorder rebind are refused while recording. */
    private fun setRebindControlsEnabled(enabled: Boolean) {
        binding.sliderFps.isEnabled = enabled && manualSupported
        binding.sliderBitrate.isEnabled = enabled
        binding.buttonLens.isEnabled = enabled
        binding.buttonQuality.isEnabled = enabled
        binding.buttonAspect.isEnabled = enabled
        binding.switchStab.isEnabled = enabled
        binding.sliderMicSource.isEnabled = enabled
    }

    private fun applyManualCameraState() {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)

        if (!manualSupported) {
            val autoFallback = CaptureRequestOptionsBuilder()
                .set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                .set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                .set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                .build()
            control.setCaptureRequestOptions(autoFallback)
            Log.w(TAG, "manual controls unsupported, auto fallback active")
            return
        }

        val options = CaptureRequestOptionsBuilder()
            .set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            .set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            .set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            .set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToGains(wbKelvinValues[wbIndex], tintProgress))
            .set(CaptureRequest.SENSOR_SENSITIVITY, isoValues[isoIndex])
            .set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterTimesNs[shutterIndex])
            .set(CaptureRequest.LENS_FOCUS_DISTANCE, focusProgress / 100f)
            .set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            .build()

        control.setCaptureRequestOptions(options)
        Log.d(TAG, "manual ISO=${isoValues[isoIndex]} shutterNs=${shutterTimesNs[shutterIndex]} wbK=${wbKelvinValues[wbIndex]} tint=$tintProgress focus=${focusProgress / 100f} fps=$fps bitrate=${bitrateMbps}Mbps")
    }

    private fun applyOverlayState() {
        binding.monitoringOverlay.setOverlayMode(overlayMode)
        binding.monitoringOverlay.setOverlayLevel(overlayLevel)
        exposureAnalyzer.overThreshold = overThresholdForLevel(overlayLevel)
        exposureAnalyzer.underThreshold = underThresholdForLevel(overlayLevel)
        Log.d(TAG, "overlay mode=$overlayMode level=$overlayLevel")
    }

    private fun loadDefaultCube() {
        runCatching {
            val text = resources.openRawResource(R.raw.cinelog_to_rec709).bufferedReader().use { it.readText() }
            cubeLut = CubeLut.parse(text)
            Log.d(TAG, "default cube LUT loaded size=${cubeLut?.size}")
        }.onFailure {
            cubeLut = null
            Log.e(TAG, "default cube LUT load failed: ${it.message}")
        }
    }

    /** Re-apply the external LUT selected before rotation (cubeLut itself is memory-only). */
    private fun reloadSavedLut() {
        if (lutFileIndex < 0 || lutFileIndex >= lutFiles.size) {
            lutFileIndex = -1
            lutName = "internal"
            return
        }
        val file = lutFiles[lutFileIndex]
        cameraExecutor.execute {
            runCatching { CubeLut.parse(file.readText()) }
                .onSuccess { loaded ->
                    runOnUiThread {
                        cubeLut = loaded
                        lutName = file.nameWithoutExtension.take(10)
                        applyPreviewLutState()
                        updateReadouts()
                    }
                }
                .onFailure {
                    runOnUiThread {
                        lutFileIndex = -1
                        lutName = "internal"
                        updateReadouts()
                    }
                }
        }
    }

    /** Scan app Documents dir for *.cube files (back-compat: cinecam.cube first). */
    private fun scanLutLibrary() {
        lutFiles = try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val all = dir?.listFiles { f -> f.extension.equals("cube", ignoreCase = true) }?.toList().orEmpty()
            val legacy = all.firstOrNull { it.name == "cinecam.cube" }
            val rest = all.filter { it.name != "cinecam.cube" }.sortedBy { it.name }
            listOfNotNull(legacy) + rest
        } catch (t: Throwable) {
            Log.w(TAG, "LUT scan failed: ${t.message}")
            emptyList()
        }
        if (lutFileIndex >= lutFiles.size) {
            lutFileIndex = -1
            lutName = "internal"
        }
        Log.d(TAG, "LUT library: ${lutFiles.map { it.name }}")
    }

    private fun loadCubeFromStorage() {
        scanLutLibrary()
        if (lutFiles.isEmpty()) {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            Toast.makeText(this, getString(R.string.put_lut_at_path, File(dir, "cinecam.cube").absolutePath), Toast.LENGTH_LONG).show()
            return
        }
        // Cycle through available LUTs so every .cube in Documents is reachable.
        lutFileIndex = (lutFileIndex + 1) % lutFiles.size
        val file = lutFiles[lutFileIndex]
        cameraExecutor.execute {
            runCatching { CubeLut.parse(file.readText()) }
                .onSuccess { loaded ->
                    runOnUiThread {
                        cubeLut = loaded
                        lutName = file.nameWithoutExtension.take(10)
                        applyPreviewLutState()
                        updateReadouts()
                        Toast.makeText(this, getString(R.string.loaded_file, file.name), Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "external cube LUT loaded path=${file.absolutePath} size=${cubeLut?.size}")
                    }
                }
                .onFailure {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.invalid_cube_file), Toast.LENGTH_LONG).show()
                        Log.e(TAG, "external cube LUT parse failed: ${it.message}")
                    }
                }
        }
    }

    private fun applyPreviewLutState() {
        if (!logPreviewEnabled && !lutPreviewEnabled) {
            binding.previewGradeOverlay.setBackgroundColor(Color.TRANSPARENT)
            Log.d(TAG, "preview grade off")
            return
        }

        var alpha = if (logPreviewEnabled) 52 else 20
        var r = if (logPreviewEnabled) 28 else 0
        var g = if (logPreviewEnabled) 24 else 0
        var b = if (logPreviewEnabled) 20 else 0

        if (lutPreviewEnabled && cubeLut != null) {
            // Honest approximation: a View overlay cannot run a per-pixel 3D
            // LUT (that needs the GPU pipeline from PRD section 8). Instead of
            // sampling a single midpoint, average the LUT's transform along
            // the neutral axis so the tint tracks the actual file contents.
            val lut = cubeLut!!
            var dr = 0f
            var dg = 0f
            var db = 0f
            val steps = 8
            for (i in 0..steps) {
                val v = i.toFloat() / steps
                val s = lut.sample(v, v, v)
                dr += s[0] - v
                dg += s[1] - v
                db += s[2] - v
            }
            val strength = lutStrength / 100f
            r += ((dr / (steps + 1)) * 255f * 2f * strength).toInt()
            g += ((dg / (steps + 1)) * 255f * 2f * strength).toInt()
            b += ((db / (steps + 1)) * 255f * 2f * strength).toInt()
            alpha = (alpha + 30 * strength).toInt()
            Log.d(TAG, "preview LUT approx name=$lutName strength=$lutStrength")
        }

        binding.previewGradeOverlay.setBackgroundColor(
            Color.argb(alpha.coerceIn(0, 120), r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        )
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return
        val output = buildMediaStoreOutput()
        val pending = capture.output.prepareRecording(this, output)

        recordingAudioEnabled = hasMicPermission()
        val ready = if (recordingAudioEnabled) {
            pending.withAudioEnabled()
        } else {
            Toast.makeText(this, getString(R.string.mic_permission_missing_video_only), Toast.LENGTH_SHORT).show()
            pending
        }

        recording = ready.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.readoutState.text = getString(R.string.record)
                    binding.recordButton.text = getString(R.string.stop)
                    binding.statusBar.setBackgroundColor(ContextCompat.getColor(this, R.color.cinecam_status_recording))
                    setRebindControlsEnabled(false)
                    stopFocusPull()
                    startTimecode()
                    updateStorageInfo()
                    updateReadouts()
                    Log.d(TAG, "recording started audioEnabled=$recordingAudioEnabled mic=${if (micSourceIndex == 1 && hasExternalMic) "EXTERNAL" else "INTERNAL"} gain=$micGain")
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        Toast.makeText(this, getString(R.string.record_failed, event.error), Toast.LENGTH_LONG).show()
                        Log.e(TAG, "record finalize error=${event.error}")
                    } else {
                        Log.d(TAG, "recording finalized uri=${event.outputResults.outputUri}")
                    }
                    binding.readoutState.text = getString(R.string.idle)
                    binding.recordButton.text = getString(R.string.record)
                    binding.statusBar.setBackgroundColor(ContextCompat.getColor(this, R.color.cinecam_panel_bg))
                    highlightField = HighlightField.NONE
                    recording = null
                    recordingAudioEnabled = false
                    setRebindControlsEnabled(true)
                    stopTimecode()
                    binding.readoutTc.text = getString(R.string.idle)
                    updateStorageInfo()
                    updateReadouts()
                }
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
    }

    private fun startTimecode() {
        stopTimecode()
        recordingStartMs = SystemClock.elapsedRealtime()
        tcRunnable = object : Runnable {
            override fun run() {
                val s = ((SystemClock.elapsedRealtime() - recordingStartMs) / 1000).toInt()
                binding.readoutTc.text = "REC %02d:%02d".format(Locale.US, s / 60, s % 60)
                tcHandler.postDelayed(this, 500L)
            }
        }
        tcHandler.post(tcRunnable!!)
    }

    private fun stopTimecode() {
        tcRunnable?.let { tcHandler.removeCallbacks(it) }
        tcRunnable = null
    }

    /** Free space + estimated recording minutes at the current bitrate. */
    private fun updateStorageInfo() {
        freeGb = try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            stat.availableBytes / 1_073_741_824f
        } catch (_: Throwable) {
            -1f
        }
        if (freeGb in 0f..1f) {
            runCatching {
                Toast.makeText(this, getString(R.string.storage_low_warn, freeGb), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startFocusPull() {
        if (focusPullRunning || !manualSupported) return
        val from = focusA.coerceIn(0, 100)
        val to = focusB.coerceIn(0, 100)
        if (from == to) {
            Toast.makeText(this, "A=B", Toast.LENGTH_SHORT).show()
            return
        }
        focusPullRunning = true
        val steps = 20
        var i = 0
        pullRunnable = object : Runnable {
            override fun run() {
                if (!focusPullRunning) return
                i++
                focusProgress = (from + (to - from) * i / steps).coerceIn(0, 100)
                binding.sliderFocus.progress = focusProgress
                binding.sliderEdgeFocus.progress = focusProgress
                highlightField = HighlightField.FOCUS
                updateReadouts()
                applyManualCameraState()
                if (i >= steps) {
                    focusPullRunning = false
                    pullRunnable = null
                    Log.d(TAG, "focus pull done A=$from B=$to")
                } else {
                    pullHandler.postDelayed(this, 100L)
                }
            }
        }
        pullHandler.post(pullRunnable!!)
        Log.d(TAG, "focus pull start A=$from B=$to")
    }

    private fun stopFocusPull() {
        focusPullRunning = false
        pullRunnable?.let { pullHandler.removeCallbacks(it) }
        pullRunnable = null
    }

    private fun prefs(): SharedPreferences =
        getSharedPreferences("cinecam_presets", Context.MODE_PRIVATE)

    private fun savePreset(slot: Int) {
        prefs().edit()
            .putInt("p${slot}_iso", isoIndex)
            .putInt("p${slot}_shutter", shutterIndex)
            .putInt("p${slot}_wb", wbIndex)
            .putInt("p${slot}_focus", focusProgress)
            .putInt("p${slot}_fps", fps)
            .putInt("p${slot}_bitrate", bitrateMbps)
            .putInt("p${slot}_overlay", overlayMode.ordinal)
            .putInt("p${slot}_level", overlayLevel)
            .putInt("p${slot}_tint", tintProgress)
            .putInt("p${slot}_lut", lutStrength)
            .putInt("p${slot}_aspect", aspectRatio)
            .putInt("p${slot}_zoom", zoomProgress)
            .putBoolean("p${slot}_stab", stabOn)
            .apply()
        Toast.makeText(this, getString(R.string.preset_saved, slot), Toast.LENGTH_SHORT).show()
        Log.d(TAG, "preset $slot saved")
    }

    private fun recallPreset(slot: Int) {
        val p = prefs()
        if (!p.contains("p${slot}_iso")) {
            Toast.makeText(this, getString(R.string.preset_saved, slot) + "?", Toast.LENGTH_SHORT).show()
            return
        }
        isoIndex = p.getInt("p${slot}_iso", isoIndex)
        shutterIndex = p.getInt("p${slot}_shutter", shutterIndex)
        wbIndex = p.getInt("p${slot}_wb", wbIndex)
        focusProgress = p.getInt("p${slot}_focus", focusProgress)
        val wantFps = p.getInt("p${slot}_fps", fps)
        fps = if (wantFps == 60 && !is60FpsSupported()) 30 else wantFps
        bitrateMbps = p.getInt("p${slot}_bitrate", bitrateMbps)
        overlayMode = MonitoringOverlayView.Mode.entries[p.getInt("p${slot}_overlay", overlayMode.ordinal)]
        overlayLevel = p.getInt("p${slot}_level", overlayLevel)
        tintProgress = p.getInt("p${slot}_tint", tintProgress)
        lutStrength = p.getInt("p${slot}_lut", lutStrength)
        aspectRatio = p.getInt("p${slot}_aspect", aspectRatio)
        zoomProgress = p.getInt("p${slot}_zoom", zoomProgress).coerceIn(0, 100)
        stabOn = p.getBoolean("p${slot}_stab", stabOn)
        // Push to UI widgets, then to hardware.
        binding.sliderIso.progress = isoIndex
        binding.sliderEdgeIso.progress = isoIndex
        binding.sliderShutter.progress = shutterIndex
        binding.sliderWb.progress = wbIndex
        binding.sliderFocus.progress = focusProgress
        binding.sliderEdgeFocus.progress = focusProgress
        binding.sliderFps.progress = fpsToProgress(fps)
        binding.sliderBitrate.progress = bitrateMbpsValues.indexOf(bitrateMbps).coerceAtLeast(0)
        binding.sliderOverlayMode.progress = overlayMode.ordinal
        binding.sliderOverlayLevel.progress = overlayLevel
        binding.sliderTint.progress = (tintProgress + 50).coerceIn(0, 100)
        binding.readoutTint.text = tintProgress.toString()
        binding.sliderLutStrength.progress = lutStrength
        binding.buttonAspect.text = getString(if (aspectRatio == AspectRatio.RATIO_4_3) R.string.aspect_43 else R.string.aspect_169)
        binding.sliderZoom.progress = zoomProgress
        binding.switchStab.isChecked = stabOn
        applyOverlayState()
        applyPreviewLutState()
        updateReadouts()
        applyManualCameraState()
        applyZoomState()
        requestRecorderRebind()
        Toast.makeText(this, getString(R.string.preset_loaded, slot), Toast.LENGTH_SHORT).show()
        Log.d(TAG, "preset $slot recalled")
    }

    private fun buildMediaStoreOutput(): MediaStoreOutputOptions {
        val q = if (uhdEnabled) "UHD" else "FHD"
        val lens = if (useFrontLens) "front" else "back"
        val ar = if (aspectRatio == AspectRatio.RATIO_4_3) "43" else "169"
        val iso = isoValues[isoIndex]
        val wb = wbKelvinValues[wbIndex]
        val name = "cinecam_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_${fps}fps_${bitrateMbps}mbps_${q}_${ar}_${lens}_iso${iso}_${wb}k"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CineCam")
            }
        }
        return MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
    }

    private fun tryApplyHdr10DynamicRange(builder: Recorder.Builder): Boolean {
        if (!hdr10Supported) return false
        return runCatching {
            val dynamicRangeClass = Class.forName("androidx.camera.video.DynamicRange")
            val hlgField = dynamicRangeClass.getField("HLG_10_BIT").get(null)
            val setDynamicRange = Recorder.Builder::class.java.getMethod("setDynamicRange", dynamicRangeClass)
            setDynamicRange.invoke(builder, hlgField)
            Log.d(TAG, "HDR10 dynamic range applied")
        }.isSuccess
    }

    private fun hardwareLevelName(level: Int?): String {
        return when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
    }

    private fun kelvinToGains(kelvin: Int, tint: Int = 0): RggbChannelVector {
        val temperature = kelvin / 100f
        val red = (255f.coerceAtMost(329.698727446f * Math.pow(temperature.toDouble() - 60.0, -0.1332047592).toFloat()) / 255f)
            .coerceIn(0.5f, 3f)
        val blue = if (temperature >= 66f) {
            1f
        } else {
            (255f.coerceAtMost(138.5177312231f * kotlin.math.ln((temperature - 10f).coerceAtLeast(1f)) - 305.0447927307f) / 255f)
                .coerceIn(0.5f, 3f)
        }
        // Green/magenta tint: positive tint pushes green, negative pulls magenta.
        val green = (1f + tint / 200f).coerceIn(0.5f, 3f)
        return RggbChannelVector(red, green, green, blue)
    }

    private fun simpleSeek(onStop: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                onStop(seekBar?.progress ?: 0)
            }
        }
    }
}

private class CaptureRequestOptionsBuilder {
    private val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()

    fun <T : Any> set(key: CaptureRequest.Key<T>, value: T): CaptureRequestOptionsBuilder {
        builder.setCaptureRequestOption(key, value)
        return this
    }

    fun build(): androidx.camera.camera2.interop.CaptureRequestOptions = builder.build()
}
