package com.ankush.cinecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class MonitoringOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { NONE, ZEBRA, PEAKING, FALSE_COLOR }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mode: Mode = Mode.NONE
    private var level: Int = 60

    /**
     * Latest real frame stats from [ExposureAnalyzer], or null when the
     * analysis use case is not bound yet / was refused. Overlays modulate
     * their density/visibility from this data and fall back to the static
     * illustrative pattern only when it is null.
     */
    @Volatile private var stats: FrameStats? = null

    fun setOverlayMode(mode: Mode) {
        this.mode = mode
        invalidate()
    }

    fun setOverlayLevel(level: Int) {
        this.level = level.coerceIn(0, 100)
        invalidate()
    }

    fun setAnalysis(stats: FrameStats?) {
        this.stats = stats
        if (mode != Mode.NONE) invalidate()
    }

    fun clearAnalysis() {
        stats = null
        if (mode != Mode.NONE) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            Mode.NONE -> Unit
            Mode.ZEBRA -> drawZebra(canvas)
            Mode.PEAKING -> drawPeaking(canvas)
            Mode.FALSE_COLOR -> drawFalseColor(canvas)
        }
    }

    private fun drawZebra(canvas: Canvas) {
        val s = stats
        // Real behavior: stripes only where the frame actually clips. When
        // nothing clips, draw nothing (clean frame) instead of fake stripes.
        // Without analysis data, keep the old illustrative pattern.
        if (s != null) {
            if (s.overFrac < 0.002f && s.underFrac < 0.002f) return
            val density = (s.overFrac + s.underFrac).coerceIn(0f, 0.25f) / 0.25f
            val spacing = (60f - density * 46f).coerceAtLeast(10f)
            val alpha = (60 + (density * 140f).toInt()).coerceAtMost(200)
            paint.color = if (s.overFrac >= s.underFrac) Color.argb(alpha, 255, 60, 60)
            else Color.argb(alpha, 80, 140, 255)
            paint.strokeWidth = 4f
            var x = -height.toFloat()
            while (x < width + height) {
                canvas.drawLine(x, 0f, x + height, height.toFloat(), paint)
                x += spacing
            }
            return
        }
        val spacing = (14 + (100 - level) / 3f).coerceAtLeast(6f)
        paint.color = Color.argb(120, 255, 255, 255)
        paint.strokeWidth = 3f
        var x = -height.toFloat()
        while (x < width + height) {
            canvas.drawLine(x, 0f, x + height, height.toFloat(), paint)
            x += spacing
        }
    }

    private fun drawPeaking(canvas: Canvas) {
        val s = stats
        if (s != null) {
            // Real behavior: marker density follows measured sharpness. A
            // soft/out-of-focus frame shows sparse markers, a sharp frame
            // shows dense ones. Nothing measured -> nothing drawn.
            if (s.sharpness < 0.03f) return
            val density = s.sharpness.coerceIn(0f, 1f)
            val step = (64f - density * 48f).coerceAtLeast(12f)
            val alpha = (70 + (density * 130f).toInt()).coerceAtMost(200)
            paint.color = Color.argb(alpha, 50, 255, 50)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            var y = 0f
            while (y < height) {
                var x = ((y * 0.37f) % step)
                while (x < width) {
                    canvas.drawRect(x, y, x + step / 2.5f, y + step / 2.5f, paint)
                    x += step
                }
                y += step
            }
            paint.style = Paint.Style.FILL
            return
        }
        val step = (36 - level / 4f).coerceAtLeast(8f)
        paint.color = Color.argb(150, 50, 255, 50)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        var y = 0f
        while (y < height) {
            var x = 0f
            while (x < width) {
                canvas.drawRect(x, y, x + step / 2f, y + step / 2f, paint)
                x += step
            }
            y += step
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawFalseColor(canvas: Canvas) {
        val s = stats
        val alpha = (80 + level).coerceAtMost(200)
        val colors = intArrayOf(
            Color.argb(alpha, 20, 20, 180),
            Color.argb(alpha, 40, 180, 240),
            Color.argb(alpha, 50, 220, 90),
            Color.argb(alpha, 240, 220, 40),
            Color.argb(alpha, 240, 120, 30),
            Color.argb(alpha, 240, 40, 40)
        )
        if (s != null) {
            // Real behavior: each IRE band's height is proportional to the
            // fraction of the frame actually metering in that zone.
            var top = 0f
            for (i in colors.indices) {
                paint.color = colors[i]
                val bandH = height * s.zones[i].coerceIn(0f, 1f)
                canvas.drawRect(0f, top, width.toFloat(), top + bandH, paint)
                top += bandH
            }
            if (top < height) {
                paint.color = Color.argb(alpha, 10, 10, 10)
                canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
            }
            return
        }
        val bandHeight = height / colors.size.toFloat()
        colors.forEachIndexed { i, c ->
            paint.color = c
            val top = i * bandHeight
            canvas.drawRect(0f, top, width.toFloat(), top + bandHeight, paint)
        }
    }
}
