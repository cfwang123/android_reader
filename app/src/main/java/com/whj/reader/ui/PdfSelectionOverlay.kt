package com.whj.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.whj.reader.model.HighlightMode
import com.whj.reader.model.HighlightStyle
import com.whj.reader.pdf.highlight.PdfHighlightPainter
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * PDF 页图上的文字选区 / TTS 句高亮（屏幕坐标矩形），带两端拖动手柄。
 */
class PdfSelectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class PersistentHighlightDraw(
        val id: String,
        val rects: List<RectF>,
        val style: HighlightStyle,
    )

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x663B82F6
        style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66E8A838
        style = Paint.Style.FILL
    }

    private val selectionRects = ArrayList<RectF>()
    private val highlightRects = ArrayList<RectF>()
    private val persistentHighlights = ArrayList<PersistentHighlightDraw>()
    private val startHandle = PointF()
    private val endHandle = PointF()
    private var handlesVisible = false
    private var draggingHandle: TextSelectionHandles.Which? = null

    /** which, overlayX, overlayY, ended */
    var onHandleDrag: ((TextSelectionHandles.Which, Float, Float, Boolean) -> Unit)? = null

    private val density get() = resources.displayMetrics.density

    private fun hostZoom(): ZoomableFrameLayout? {
        var p = parent
        while (p != null) {
            if (p is ZoomableFrameLayout) return p
            p = p.parent
        }
        return null
    }

    private fun setHandleDragActive(active: Boolean) {
        hostZoom()?.handleDragActive = active
    }

    fun setSelectionRects(list: List<RectF>, start: PointF? = null, end: PointF? = null) {
        selectionRects.clear()
        selectionRects.addAll(list)
        if (start != null && end != null) {
            startHandle.set(start)
            endHandle.set(end)
            handlesVisible = list.isNotEmpty()
        } else {
            handlesVisible = false
        }
        // 仅手柄需要接触摸；选区外必须放行，否则无法 pan/滚列表
        // 点击取消选区由 ZoomableFrameLayout 的 onSingleTap 处理
        isClickable = handlesVisible
        isFocusable = false
        invalidate()
    }

    fun setHighlightRects(list: List<RectF>) {
        highlightRects.clear()
        highlightRects.addAll(list)
        invalidate()
    }

    fun setPersistentHighlights(items: List<PersistentHighlightDraw>) {
        persistentHighlights.clear()
        persistentHighlights.addAll(items)
        invalidate()
    }

    fun clearPersistentHighlights() {
        if (persistentHighlights.isEmpty()) return
        persistentHighlights.clear()
        invalidate()
    }

    fun clearSelection() {
        val had = selectionRects.isNotEmpty() || handlesVisible
        selectionRects.clear()
        handlesVisible = false
        draggingHandle = null
        isClickable = false
        setHandleDragActive(false)
        if (had) invalidate()
    }

    fun clearHighlight() {
        if (highlightRects.isEmpty()) return
        highlightRects.clear()
        invalidate()
    }

    fun clear() {
        val any = selectionRects.isNotEmpty() || highlightRects.isNotEmpty() || handlesVisible
        selectionRects.clear()
        highlightRects.clear()
        handlesVisible = false
        draggingHandle = null
        if (any) {
            setHandleDragActive(false)
            invalidate()
        }
    }

    /** @deprecated 用 [setSelectionRects] */
    fun setRects(list: List<RectF>) = setSelectionRects(list)

    override fun onDraw(canvas: Canvas) {
        for (item in persistentHighlights) {
            drawPersistent(item, canvas)
        }
        for (r in highlightRects) canvas.drawRect(r, highlightPaint)
        for (r in selectionRects) canvas.drawRect(r, selectionPaint)
        if (handlesVisible) {
            TextSelectionHandles.draw(
                canvas,
                startHandle.x,
                startHandle.y,
                endHandle.x,
                endHandle.y,
                density,
            )
        }
    }

    /**
     * 只接管「手柄拖动」；其它触摸一律 return false，交给下层 pan / 列表滚动。
     * 取消选区仅响应轻点（Activity.onSingleTap），不在此处 DOWN 抢事件。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!handlesVisible) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingHandle = TextSelectionHandles.hitTest(
                    event.x,
                    event.y,
                    startHandle.x,
                    startHandle.y,
                    endHandle.x,
                    endHandle.y,
                    density,
                )
                if (draggingHandle != null) {
                    setHandleDragActive(true)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onHandleDrag?.invoke(draggingHandle!!, event.x, event.y, false)
                    return true
                }
                // 未点中手柄：不消费，pan/滚页由 ZoomableFrameLayout / RV 处理
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val h = draggingHandle ?: return false
                onHandleDrag?.invoke(h, event.x, event.y, false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val h = draggingHandle ?: return false
                onHandleDrag?.invoke(h, event.x, event.y, true)
                draggingHandle = null
                setHandleDragActive(false)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun drawPersistent(item: PersistentHighlightDraw, canvas: Canvas) {
        PdfHighlightPainter.draw(
            canvas,
            listOf(PdfHighlightPainter.DrawItem(item.rects, item.style)),
            density,
        )
    }
}
