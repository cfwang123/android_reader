package com.whj.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * HSV 选色器：S-V 平面（当前色相固定）。
 */
class HsvSvPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onSvChanged: ((sat: Float, value: Float) -> Unit)? = null

    private var hue = 0f
    private var sat = 1f
    private var value = 1f

    private var panelBmp: Bitmap? = null
    private var panelHue = Float.NaN
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private val cursorOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0xFF000000.toInt()
    }

    fun setHue(h: Float) {
        val nh = h.coerceIn(0f, 360f)
        if (nh == hue) return
        hue = nh
        panelBmp = null
        invalidate()
    }

    fun setSv(s: Float, v: Float) {
        sat = s.coerceIn(0f, 1f)
        value = v.coerceIn(0f, 1f)
        invalidate()
    }

    fun hue(): Float = hue
    fun sat(): Float = sat
    fun value(): Float = value

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        panelBmp = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        ensurePanel(w, h)
        panelBmp?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
        val cx = sat * (w - 1)
        val cy = (1f - value) * (h - 1)
        val r = 8f * resources.displayMetrics.density
        canvas.drawCircle(cx, cy, r, cursorOuter)
        canvas.drawCircle(cx, cy, r, cursorPaint)
    }

    private fun ensurePanel(w: Int, h: Int) {
        if (panelBmp != null && panelBmp!!.width == w && panelBmp!!.height == h && panelHue == hue) {
            return
        }
        panelHue = hue
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // 底：纯色相
        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        // 水平：白 → 色相
        val paintH = Paint()
        paintH.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            Color.WHITE, hueColor, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paintH)
        // 垂直：透明 → 黑
        val paintV = Paint()
        paintV.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paintV)
        panelBmp = bmp
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val w = max(1, width - 1).toFloat()
                val h = max(1, height - 1).toFloat()
                sat = (event.x / w).coerceIn(0f, 1f)
                value = (1f - event.y / h).coerceIn(0f, 1f)
                onSvChanged?.invoke(sat, value)
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
