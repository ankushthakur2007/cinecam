package com.cinecam.app

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Parallel monitoring-path audio meter. Reads a low-rate mono stream and
 * reports display-ready 0..100 peak/RMS levels ~8x/sec via [onLevel]
 * (invoked on the meter thread; caller posts to UI).
 *
 * This intentionally does NOT alter the CameraX Recorder audio path, so a
 * meter failure can never break recording — [start] returns false and the
 * caller keeps the simulated fallback.
 */
class AudioMeter(private val onLevel: (peak: Int, rms: Int) -> Unit) {

    @Volatile var gain: Int = 10
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(preferredDevice: AudioDeviceInfo?): Boolean {
        stop()
        val sampleRate = 44100
        val minBuf = try {
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (_: Throwable) { return false }
        if (minBuf <= 0) return false
        val rec = try {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (_: SecurityException) {
            return false
        } catch (_: Throwable) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return false
        }
        if (preferredDevice != null && Build.VERSION.SDK_INT >= 23) {
            runCatching { rec.preferredDevice = preferredDevice }
        }
        try {
            rec.startRecording()
        } catch (_: Throwable) {
            runCatching { rec.release() }
            return false
        }
        running = true
        thread = Thread({ loop(rec) }, "CineCamMeter").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(2048)
        var smoothRms = 0f
        while (running) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (_: Throwable) {
                break
            }
            if (n <= 0) continue
            var peak = 0
            var sum = 0.0
            for (i in 0 until n) {
                val v = abs(buf[i].toInt())
                if (v > peak) peak = v
                sum += (v * v).toDouble()
            }
            val mult = 1f + gain / 10f
            val p = ((peak / 32768f) * 100f * mult).toInt().coerceIn(0, 100)
            val inst = sqrt(sum / n).toFloat() / 32768f * 100f * mult
            smoothRms = smoothRms * 0.6f + inst * 0.4f
            val r = smoothRms.toInt().coerceIn(0, 100)
            try {
                onLevel(p, r)
            } catch (_: Throwable) {
                break
            }
        }
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    fun stop() {
        running = false
        try { thread?.join(600) } catch (_: Throwable) { }
        thread = null
    }
}
