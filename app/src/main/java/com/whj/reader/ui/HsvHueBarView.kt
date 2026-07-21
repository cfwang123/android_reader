package com.whj.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * HSV 色相横条 0–360°。
 */
class HsvHueBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onHueChanged: ((hue: Float) -> Unit)? = null

    private var hue = 0f
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private val thumbOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0xFF000000.toInt()
    }

    fun setHue(h: Float) {
        hue = h.coerceIn(0f, 360f)
        invalidate()
    }

    fun hue(): Float = hue

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val colors = IntArray(7) { i ->
            Color.HSVToColor(floatArrayOf(i * 60f, 1f, 1f))
        }
        barPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            colors, null, Shader.TileMode.CLAMP,
        )
        val r = h / 2f
        canvas.drawRoundRect(0f, 0f, w, h, r, r, barPaint)
        val cx = (hue / 360f) * (w - 1)
        val cy = h / 2f
        val tr = h * 0.42f
        canvas.drawCircle(cx, cy, tr, thumbOuter)
        canvas.drawCircle(cx, cy, tr, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val w = (width - 1).coerceAtLeast(1).toFloat()
                hue = (event.x / w * 360f).coerceIn(0f, 360f)
                onHueChanged?.invoke(hue)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
