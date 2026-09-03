package com.cinecam.app

import android.graphics.ImageFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Real per-frame exposure stats computed from the Y plane of YUV_420_888
 * frames delivered by CameraX ImageAnalysis.
 *
 * Runs on the analysis executor at ~8Hz on a downsampled grid so it stays
 * far inside frame budget on old devices (verified target: SDK 29+).
 * Every overlay/histogram consumer must tolerate null stats (analyzer not
 * bound yet or device refused the use case) and fall back gracefully.
 */
data class FrameStats(
    val histogram: IntArray, // 64 luma bins
    val overFrac: Float, // fraction of sampled px >= overThreshold
    val underFrac: Float, // fraction of sampled px <= underThreshold
    val sharpness: Float, // 0..1 mean abs horizontal luma gradient, normalized
    val zones: FloatArray, // 6 equal luma bands, fractions summing to ~1
    val pixelCount: Int
)

class ExposureAnalyzer(
    @Volatile var overThreshold: Int = 231,
    @Volatile var underThreshold: Int = 28,
    private val listener: (FrameStats) -> Unit
) : ImageAnalysis.Analyzer {

    private val main = Handler(Looper.getMainLooper())
    private var lastTs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTs < 120) return
            if (image.format != ImageFormat.YUV_420_888) return
            lastTs = now

            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride.coerceAtLeast(1)

            val stepX = maxOf(1, width / 160)
            val stepY = maxOf(1, height / 90)
            val over = overThreshold
            val under = underThreshold

            val hist = IntArray(64)
            val zones = IntArray(6)
            var overCount = 0
            var underCount = 0
            var gradSum = 0L
            var gradN = 0L
            var total = 0

            var y = 0
            while (y < height) {
                var prev = -1
                var x = 0
                while (x < width) {
                    val idx = y * rowStride + x * pixelStride
                    if (idx >= buf.limit()) break
                    val v = buf.get(idx).toInt() and 0xFF
                    hist[(v * 64) ushr 8]++
                    zones[(v * 6) ushr 8]++
                    if (v >= over) overCount++
                    if (v <= under) underCount++
                    if (prev >= 0) {
                        gradSum += kotlin.math.abs(v - prev)
                        gradN++
                    }
                    prev = v
                    total++
                    x += stepX
                }
                y += stepY
            }
            if (total == 0) return

            val stats = FrameStats(
                histogram = hist,
                overFrac = overCount.toFloat() / total,
                underFrac = underCount.toFloat() / total,
                sharpness = if (gradN > 0) ((gradSum.toFloat() / gradN) / 255f * 8f).coerceIn(0f, 1f) else 0f,
                zones = FloatArray(6) { zones[it].toFloat() / total },
                pixelCount = total
            )
            main.post { runCatching { listener(stats) } }
        } catch (_: Throwable) {
            // Analysis must never crash the camera pipeline; overlays keep last stats.
        } finally {
            try { image.close() } catch (_: Throwable) { }
        }
    }
}
