package com.whj.reader.pdf.coord

import android.graphics.RectF
import com.whj.reader.data.PdfTextExtractor
import com.whj.reader.ui.PdfPageSurface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 页坐标 ↔ 表面/视口映射（选区矩形、命中）。
 * 页宽高优先用 PDFBox 字符尺寸，与抽字坐标一致。
 */
object PdfViewMapper {

    /**
     * PDF 页坐标矩形 → [PdfPageSurface] 本地坐标。
     * @param margins L,T,R,B 切边比例
     */
    fun mapPageRectToSurfaceView(
        surface: PdfPageSurface,
        pageRect: RectF,
        pageW: Float,
        pageH: Float,
        margins: FloatArray,
    ): RectF {
        val pw = pageW.coerceAtLeast(1f)
        val ph = pageH.coerceAtLeast(1f)
        val cl = margins.getOrElse(0) { 0f }.coerceIn(0f, 0.30f)
        val ct = margins.getOrElse(1) { 0f }.coerceIn(0f, 0.30f)
        val cr = margins.getOrElse(2) { 0f }.coerceIn(0f, 0.30f)
        val cb = margins.getOrElse(3) { 0f }.coerceIn(0f, 0.30f)
        val srcW = pw * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = ph * (1f - ct - cb).coerceAtLeast(0.2f)
        val vw = surface.width.toFloat().coerceAtLeast(1f)
        val vh = max(surface.height, surface.logicalHeight).toFloat().coerceAtLeast(1f)
        val cropLeft = pw * cl
        val cropTop = ph * ct
        fun px(x: Float) = ((x - cropLeft) / srcW) * vw
        fun py(y: Float) = ((y - cropTop) / srcH) * vh
        return RectF(
            px(pageRect.left),
            py(pageRect.top),
            px(pageRect.right),
            py(pageRect.bottom),
        )
    }

    fun pageSizeFromChars(chars: List<PdfTextExtractor.PdfChar>): Pair<Float, Float>? {
        val s = chars.firstOrNull() ?: return null
        if (s.pageWidth <= 1f || s.pageHeight <= 1f) return null
        return s.pageWidth to s.pageHeight
    }

    /**
     * RV 上该页 item 在页内坐标的可见竖带；不可见返回 null。
     * @param childTop/Bottom item 相对 RV 的 top/bottom
     */
    fun pageVisibleBandInRv(
        childTop: Int,
        childBottom: Int,
        viewportH: Int,
        pageH: Int,
    ): Pair<Int, Int>? {
        val visibleH = (
            childBottom.coerceAtMost(viewportH) - childTop.coerceAtLeast(0)
            ).coerceAtLeast(0)
        if (visibleH <= 0) return null
        val visTop = if (childTop < 0) (-childTop).coerceIn(0, pageH) else 0
        val visBottom = (visTop + visibleH).coerceIn(visTop + 1, pageH)
        return visTop to visBottom
    }

    /** 同行字符合并为一条矩形，减少碎块 */
    fun mergeLineRects(chars: List<PdfTextExtractor.PdfChar>): List<RectF> {
        if (chars.isEmpty()) return emptyList()
        val sorted = chars.sortedWith(compareBy({ it.top }, { it.left }))
        val avgH = sorted.map { (it.bottom - it.top).coerceAtLeast(1f) }.average().toFloat()
        val lineTol = avgH * 0.55f
        val lines = ArrayList<RectF>()
        var cur = RectF(sorted[0].left, sorted[0].top, sorted[0].right, sorted[0].bottom)
        var curMidY = sorted[0].midY
        for (i in 1 until sorted.size) {
            val c = sorted[i]
            if (abs(c.midY - curMidY) <= lineTol) {
                cur.left = min(cur.left, c.left)
                cur.right = max(cur.right, c.right)
                cur.top = min(cur.top, c.top)
                cur.bottom = max(cur.bottom, c.bottom)
                curMidY = (cur.top + cur.bottom) / 2f
            } else {
                lines.add(RectF(cur))
                cur.set(c.left, c.top, c.right, c.bottom)
                curMidY = c.midY
            }
        }
        lines.add(cur)
        return lines
    }
}
