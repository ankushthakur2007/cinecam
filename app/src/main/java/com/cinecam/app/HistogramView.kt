package com.cinecam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Luma histogram (64 bins) driven by [FrameStats] from [ExposureAnalyzer].
 * Preview-only monitoring aid; never touches the recorded buffer.
 * Clipped highlight bins tint red, crushed shadow bins tint blue.
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = 1f
    }

    private var bins: IntArray? = null
    private var overFrac = 0f
    private var underFrac = 0f
    private var hasData = false

    fun setStats(stats: FrameStats) {
        bins = stats.histogram
        overFrac = stats.overFrac
        underFrac = stats.underFrac
        hasData = true
        invalidate()
    }

    fun clearStats() {
        hasData = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        // Mid line for scale reference.
        canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
        val data = bins
        if (!hasData || data == null) {
            gridPaint.color = Color.argb(90, 255, 255, 255)
            canvas.drawLine(0f, h - 1f, w, h - 1f, gridPaint)
            gridPaint.color = Color.argb(70, 255, 255, 255)
            return
        }
        var max = 1
        for (v in data) if (v > max) max = v
        val barW = w / data.size
        val showClip = overFrac > 0.004f
        val showCrush = underFrac > 0.004f
        for (i in data.indices) {
            val bh = (data[i].toFloat() / max) * (h - 2f)
            barPaint.color = when {
                i >= 60 && showClip -> Color.argb(220, 255, 70, 70)
                i < 4 && showCrush -> Color.argb(220, 90, 160, 255)
                else -> Color.argb(200, 255, 255, 255)
            }
            val left = i * barW
            canvas.drawRect(left, h - bh, left + barW - 0.5f, h, barPaint)
        }
    }
}
