package com.whj.reader.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * 文本选区两端拖动手柄：绘制、命中测试、边缘滚动步长。
 */
object TextSelectionHandles {

    enum class Which { START, END }

    /** 边缘停留时逐步加速（0→1，约半秒拉满） */
    class EdgeScrollState(
        var ramp: Float = 0f,
        var lastTickMs: Long = 0L,
    ) {
        fun reset() {
            ramp = 0f
            lastTickMs = 0L
        }
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt()
        style = Paint.Style.FILL
    }
    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt()
        style = Paint.Style.STROKE
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
    }

    private data class Metrics(val radius: Float, val stem: Float, val touch: Float)

    private fun metrics(density: Float): Metrics {
        val r = 8f * density
        return Metrics(r, 14f * density, 30f * density)
    }

    /** @param startY/endY 选区行底（view 坐标），手柄圆点在其下方 */
    fun draw(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        density: Float,
    ) {
        val m = metrics(density)
        drawOne(canvas, startX, startY, m)
        drawOne(canvas, endX, endY, m)
    }

    private fun drawOne(canvas: Canvas, x: Float, lineBottom: Float, m: Metrics) {
        val cy = lineBottom + m.radius * 1.2f
        stemPaint.strokeWidth = 3f * densityScale(m.radius)
        canvas.drawLine(x, lineBottom - m.stem, x, cy, stemPaint)
        canvas.drawCircle(x, cy, m.radius, fillPaint)
        ringPaint.strokeWidth = 1.5f * densityScale(m.radius)
        canvas.drawCircle(x, cy, m.radius, ringPaint)
    }

    private fun densityScale(radius: Float): Float = radius / 8f

    fun hitTest(
        x: Float,
        y: Float,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        density: Float,
    ): Which? {
        val m = metrics(density)
        val startCy = startY + m.radius * 1.2f
        val endCy = endY + m.radius * 1.2f
        val dStart = dist(x, y, startX, startCy)
        val dEnd = dist(x, y, endX, endCy)
        if (dStart <= m.touch && dStart <= dEnd) return Which.START
        if (dEnd <= m.touch) return Which.END
        return null
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * @param y 手指 view Y
     * @param unit 单步基准（如行高）
     * @return 内容滚动量（正=向下滚 / scrollY 增大）
     */
    fun edgeScrollStep(
        y: Float,
        viewHeight: Float,
        unit: Float,
        density: Float,
        state: EdgeScrollState,
        nowMs: Long = SystemClock.uptimeMillis(),
    ): Float {
        if (viewHeight <= 1f) return 0f
        val edge = (viewHeight * 0.14f).coerceAtLeast(48f * density)
        val inTop = y < edge
        val inBottom = y > viewHeight - edge
        if (!inTop && !inBottom) {
            state.reset()
            return 0f
        }
        val dt = if (state.lastTickMs == 0L) {
            0L
        } else {
            (nowMs - state.lastTickMs).coerceIn(0L, 48L)
        }
        state.lastTickMs = nowMs
        // 贴边停留后约 520ms 线性拉满，避免一贴边就猛滚
        state.ramp = (state.ramp + dt / 520f).coerceAtMost(1f)
        val proximity = if (inTop) {
            1f - (y / edge).coerceIn(0f, 1f)
        } else {
            1f - ((viewHeight - y) / edge).coerceIn(0f, 1f)
        }
        val maxStep = unit * 2.5f
        val step = maxStep * proximity * state.ramp
        return if (inTop) -step else step
    }
}
