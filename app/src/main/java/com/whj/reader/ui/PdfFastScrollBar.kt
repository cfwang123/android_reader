package com.whj.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 右侧快速滚动手柄（对齐 WPF/Office PDF 自动滚动条）：
 * - 仅短胶囊拇指，无全高轨道背景
 * - 拇指高度 ≈ 可视比例（内容越长拇指越短）
 * - 滚动立刻显示，停滚 1s 后消失（无淡入淡出）
 */
class PdfFastScrollBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 0f..1f 文档进度 */
    var progress: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.0005f && !dragging) return
            field = v
            if (!dragging) invalidate()
        }

    /**
     * 可视高度 / 总内容高度（0~1）。
     * WPF 滚动条拇指长度与此成正比，长文档拇指短，不再是固定长矩形。
     */
    var visibleFraction: Float = 0.12f
        set(value) {
            val v = value.coerceIn(0.04f, 1f)
            if (abs(field - v) < 0.002f) return
            field = v
            invalidate()
        }

    /** 拖动中回调进度；ended=true 表示松手 */
    var onSeek: ((progress: Float, ended: Boolean) -> Unit)? = null

    var seekEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) hideImmediate()
        }

    private val density = resources.displayMetrics.density
    /** WPF 风格竖条加粗 1.5 倍（原 4.5dp → 6.75dp），两端半圆 → 胶囊 */
    private val thumbWidthPx = 6.75f * density
    private val thumbMinH = 28f * density
    private val thumbMaxHFactor = 0.35f
    private val marginEnd = 2.5f * density
    private val padV = 6f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val hitSlop = 18f * density

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Windows/WPF 默认滚动条拇指：中灰
        color = 0xFF9A9A9A.toInt()
        style = Paint.Style.FILL
    }
    private val thumbRect = RectF()

    private var dragging = false
    private var downY = 0f
    private var moved = false

    private val hideRunnable = Runnable {
        if (!dragging) {
            alpha = 0f
            visibility = INVISIBLE
        }
    }
    private val hideDelayMs = 1000L

    init {
        alpha = 0f
        visibility = INVISIBLE
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var nightMode = false
    /** 缩小 &lt;100% 时外侧为黑底，拇指需提亮才能看见 */
    private var onDarkExterior = false

    fun setNight(night: Boolean) {
        nightMode = night
        applyThumbColor()
    }

    fun setOnDarkExterior(dark: Boolean) {
        if (onDarkExterior == dark) return
        onDarkExterior = dark
        applyThumbColor()
    }

    private fun applyThumbColor() {
        // 白底：中灰；黑底（夜间或缩小露黑边）：浅灰/近白，保证对比度
        thumbPaint.color = when {
            onDarkExterior || nightMode -> 0xFFE0E0E0.toInt()
            else -> 0xFF9A9A9A.toInt()
        }
        invalidate()
    }

    /** 一次更新进度 + 可视比例（由 RecyclerView range/extent 算出） */
    fun setScrollMetrics(progress01: Float, visibleFraction01: Float) {
        val p = progress01.coerceIn(0f, 1f)
        val f = visibleFraction01.coerceIn(0.04f, 1f)
        var dirty = false
        if (abs(progress - p) >= 0.0005f) {
            progress = p
            dirty = true
        }
        if (abs(visibleFraction - f) >= 0.002f) {
            visibleFraction = f
            dirty = true
        }
        if (dirty && !dragging) invalidate()
    }

    fun onScrollActivity() {
        if (!seekEnabled) return
        removeCallbacks(hideRunnable)
        visibility = VISIBLE
        alpha = 1f
        if (!dragging) {
            postDelayed(hideRunnable, hideDelayMs)
        }
    }

    val isDragging: Boolean get() = dragging

    fun hideImmediate() {
        removeCallbacks(hideRunnable)
        alpha = 0f
        visibility = INVISIBLE
        dragging = false
    }

    private fun trackTop(): Float = padV
    private fun trackBottom(): Float = (height - padV).coerceAtLeast(trackTop() + 1f)
    private fun trackLen(): Float = (trackBottom() - trackTop()).coerceAtLeast(1f)

    private fun thumbHeight(): Float {
        // 与 WPF 一致：拇指长度 ≈ 可视区占比；长文档 → 短拇指（不是固定长矩形）
        val maxH = trackLen() * thumbMaxHFactor
        val h = trackLen() * visibleFraction
        return h.coerceIn(thumbMinH, maxH.coerceAtLeast(thumbMinH))
    }

    private fun thumbTopForProgress(p: Float): Float {
        val th = thumbHeight()
        val travel = (trackLen() - th).coerceAtLeast(0f)
        return trackTop() + travel * p.coerceIn(0f, 1f)
    }

    private fun progressForY(y: Float): Float {
        val th = thumbHeight()
        val travel = (trackLen() - th).coerceAtLeast(1f)
        val top = (y - th / 2f - trackTop()).coerceIn(0f, travel)
        return top / travel
    }

    private fun layoutThumb() {
        val tw = thumbWidthPx
        val th = thumbHeight()
        val right = width - marginEnd
        val left = right - tw
        val top = thumbTopForProgress(progress)
        thumbRect.set(left, top, right, top + th)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        if (visibility != VISIBLE && !dragging) return
        layoutThumb()
        // 胶囊：圆角半径 = 宽度一半 → 两端半圆
        val r = thumbRect.width() * 0.5f
        canvas.drawRoundRect(thumbRect, r, r, thumbPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    private fun hitThumb(x: Float, y: Float): Boolean {
        layoutThumb()
        // 略放大热区，方便拖动手柄；轨道空白不响应（交给侧边翻页）
        return x >= thumbRect.left - hitSlop &&
            x <= width + hitSlop &&
            y >= thumbRect.top - hitSlop &&
            y <= thumbRect.bottom + hitSlop
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!seekEnabled) return false
        if (visibility != VISIBLE && !dragging && event.actionMasked == MotionEvent.ACTION_DOWN) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 只能拖动手柄；点轨道/空白不拦截 → 右侧点按仍走上/下翻页
                if (!hitThumb(event.x, event.y)) {
                    return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(hideRunnable)
                visibility = VISIBLE
                alpha = 1f
                dragging = true
                moved = false
                downY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                if (abs(event.y - downY) > touchSlop) moved = true
                // 未拖动时不 seek，避免轻点手柄就跳页
                if (!moved) return true
                progress = progressForY(event.y)
                invalidate()
                onSeek?.invoke(progress, false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                // 仅真正拖动过才提交定位；轻点手柄不跳转
                if (moved && event.actionMasked == MotionEvent.ACTION_UP) {
                    onSeek?.invoke(progress.coerceIn(0f, 1f), true)
                }
                postDelayed(hideRunnable, hideDelayMs)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRunnable)
        super.onDetachedFromWindow()
    }
}
