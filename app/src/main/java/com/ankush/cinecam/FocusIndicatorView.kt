package com.ankush.cinecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Tap-to-focus feedback: a corner-bracket square at the tapped point that
 * fades after ~1.2s. Pure indicator — focus itself is driven by
 * [androidx.camera.core.CameraControl.startFocusAndMetering] in
 * MainActivity, followed by an AF_MODE_OFF lock (single-shot AF, never
 * continuous). Preview-only, never touches recordings.
 */
class FocusIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var fx = -1f
    private var fy = -1f
    private var visible = false
    private val hide = Runnable {
        visible = false
        invalidate()
    }

    /** White = AF scanning. Call [setResult] when the scan completes. */
    fun showAt(x: Float, y: Float) {
        fx = x.coerceIn(0f, width.toFloat().coerceAtLeast(1f))
        fy = y.coerceIn(0f, height.toFloat().coerceAtLeast(1f))
        paint.color = Color.argb(230, 255, 255, 255)
        visible = true
        removeCallbacks(hide)
        postDelayed(hide, 2500L)
        invalidate()
    }

    /** Green = focus locked, red = scan failed. Restarts the fade timer. */
    fun setResult(ok: Boolean) {
        paint.color = if (ok) Color.argb(230, 50, 220, 90) else Color.argb(230, 255, 70, 70)
        visible = true
        removeCallbacks(hide)
        postDelayed(hide, 1200L)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible || fx < 0 || fy < 0) return
        val half = 44f * resources.displayMetrics.density
        val arm = half * 0.45f
        val l = fx - half
        val t = fy - half
        val r = fx + half
        val b = fy + half
        // Four corner brackets.
        canvas.drawLine(l, t + arm, l, t, paint)
        canvas.drawLine(l, t, l + arm, t, paint)
        canvas.drawLine(r - arm, t, r, t, paint)
        canvas.drawLine(r, t, r, t + arm, paint)
        canvas.drawLine(l, b - arm, l, b, paint)
        canvas.drawLine(l, b, l + arm, b, paint)
        canvas.drawLine(r - arm, b, r, b, paint)
        canvas.drawLine(r, b, r, b - arm, paint)
    }
}
