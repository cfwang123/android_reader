package com.whj.reader.pdf.highlight

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import com.whj.reader.model.HighlightMode
import com.whj.reader.model.HighlightStyle
import com.whj.reader.model.UnderlineShape

/** PDF 页内高亮绘制（在页图之下，与 TXT 一致）。 */
object PdfHighlightPainter {

    data class DrawItem(
        val rects: List<RectF>,
        val style: HighlightStyle,
    )

    /** 略扩水平边距、收紧竖向，避免文字超出色块。 */
    fun padRect(r: RectF, density: Float): RectF {
        val padX = 2f * density
        val h = r.height().coerceAtLeast(4f * density)
        return RectF(
            r.left - padX,
            r.top + h * 0.05f,
            r.right + padX,
            r.top + h * 0.92f,
        )
    }

    fun draw(canvas: Canvas, items: List<DrawItem>, density: Float) {
        if (items.isEmpty()) return
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        for (item in items) {
            when (item.style.mode) {
                HighlightMode.BACKGROUND -> {
                    val a = ((item.style.opacity.coerceIn(0, 100) / 100f) * 255f)
                        .toInt().coerceIn(0, 255)
                    fillPaint.color = (item.style.colorArgb and 0x00FFFFFF) or (a shl 24)
                    for (r in item.rects) {
                        canvas.drawRect(padRect(r, density), fillPaint)
                    }
                }
                HighlightMode.UNDERLINE -> {
                    linePaint.color = item.style.colorArgb or 0xFF000000.toInt()
                    linePaint.strokeWidth = 1f * density
                    linePaint.pathEffect = when (item.style.underlineShape) {
                        UnderlineShape.DASHED ->
                            DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
                        else -> null
                    }
                    for (r in item.rects) {
                        val pr = padRect(r, density)
                        val y = pr.top + pr.height() * 0.88f
                        canvas.drawLine(pr.left, y, pr.right, y, linePaint)
                    }
                }
            }
        }
    }
}
