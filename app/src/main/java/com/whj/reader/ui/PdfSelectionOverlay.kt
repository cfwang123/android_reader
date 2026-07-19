package com.whj.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * PDF 页图上的文字选区 / TTS 句高亮（屏幕坐标矩形）。
 */
class PdfSelectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

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

    fun setSelectionRects(list: List<RectF>) {
        selectionRects.clear()
        selectionRects.addAll(list)
        invalidate()
    }

    fun setHighlightRects(list: List<RectF>) {
        highlightRects.clear()
        highlightRects.addAll(list)
        invalidate()
    }

    fun clearSelection() {
        if (selectionRects.isEmpty()) return
        selectionRects.clear()
        invalidate()
    }

    fun clearHighlight() {
        if (highlightRects.isEmpty()) return
        highlightRects.clear()
        invalidate()
    }

    fun clear() {
        val any = selectionRects.isNotEmpty() || highlightRects.isNotEmpty()
        selectionRects.clear()
        highlightRects.clear()
        if (any) invalidate()
    }

    /** @deprecated 用 [setSelectionRects] */
    fun setRects(list: List<RectF>) = setSelectionRects(list)

    override fun onDraw(canvas: Canvas) {
        for (r in highlightRects) canvas.drawRect(r, highlightPaint)
        for (r in selectionRects) canvas.drawRect(r, selectionPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false
}
