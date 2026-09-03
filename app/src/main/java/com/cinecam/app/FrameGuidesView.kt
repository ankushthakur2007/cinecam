package com.cinecam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Preview-only framing aid: aspect guides (letterbox shade outside the
 * target frame) plus an optional thirds grid. Never touches recordings.
 */
class FrameGuidesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Guide { OFF, SIXTEEN_NINE, TWO_THREE_NINE, ONE_ONE, FOUR_THREE, NINE_SIXTEEN }

    private val shade = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private var guide: Guide = Guide.OFF
    private var grid = false

    fun setGuide(guide: Guide) {
        this.guide = guide
        invalidate()
    }

    fun setGrid(enabled: Boolean) {
        grid = enabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (guide == Guide.OFF && !grid) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        var left = 0f
        var top = 0f
        var right = w
        var bottom = h
        if (guide != Guide.OFF) {
            val target = when (guide) {
                Guide.SIXTEEN_NINE -> 16f / 9f
                Guide.TWO_THREE_NINE -> 2.39f
                Guide.ONE_ONE -> 1f
                Guide.FOUR_THREE -> 4f / 3f
                Guide.NINE_SIXTEEN -> 9f / 16f
                Guide.OFF -> 0f
            }
            val viewAspect = w / h
            if (target > 0f) {
                if (viewAspect > target) {
                    val fw = h * target
                    left = (w - fw) / 2f
                    right = left + fw
                } else {
                    val fh = w / target
                    top = (h - fh) / 2f
                    bottom = top + fh
                }
                // Shade outside the frame.
                canvas.drawRect(0f, 0f, w, top, shade)
                canvas.drawRect(0f, bottom, w, h, shade)
                canvas.drawRect(0f, top, left, bottom, shade)
                canvas.drawRect(right, top, w, bottom, shade)
                canvas.drawRect(left, top, right, bottom, line)
            }
        }
        if (grid) {
            val gl = if (guide == Guide.OFF) 0f else left
            val gt = if (guide == Guide.OFF) 0f else top
            val gr = if (guide == Guide.OFF) w else right
            val gb = if (guide == Guide.OFF) h else bottom
            for (i in 1..2) {
                val x = gl + (gr - gl) * i / 3f
                canvas.drawLine(x, gt, x, gb, line)
                val yy = gt + (gb - gt) * i / 3f
                canvas.drawLine(gl, yy, gr, yy, line)
            }
        }
    }
}
