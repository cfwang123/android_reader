package com.whj.reader.pdf.text

import android.graphics.PointF
import android.graphics.RectF
import com.whj.reader.data.PdfTextExtractor
import com.whj.reader.ui.TextSelectionHandles
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 选区控制器：状态、文档序、选中文本、段落下标映射、边缘滚选卡住检测。
 * 坐标 hit-test / overlay 刷新仍由宿主完成。
 */
class PdfTextSelectionController {

    val state = PdfTextSelectionState()
    val edgeScrollState = TextSelectionHandles.EdgeScrollState()

    private var stuckFrames = 0
    private var lastEndPage = -1
    private var lastEndChar = -1

    var dragActive = false
        private set
    var edgeScrollPosted = false
    var draggingHandle: TextSelectionHandles.Which? = null
    var dragX = 0f
    var dragY = 0f

    fun hasSelection(): Boolean = state.hasSelection()

    fun clear() {
        state.clear()
        stopEdgeScroll()
        draggingHandle = null
    }

    fun stopEdgeScroll() {
        dragActive = false
        edgeScrollPosted = false
        draggingHandle = null
        edgeScrollState.reset()
        stuckFrames = 0
    }

    fun markDragActive(active: Boolean) {
        dragActive = active
        if (!active) {
            edgeScrollState.reset()
            stuckFrames = 0
        }
    }

    fun setPoint(page: Int, char: Int, endChar: Int = char) {
        state.anchorPage = page
        state.anchorChar = char
        state.startPage = page
        state.startChar = char
        state.endPage = page
        state.endChar = endChar
    }

    fun setFromAnchorAndHit(hitPage: Int, hitChar: Int) {
        state.setFromAnchorAndHit(hitPage, hitChar)
    }

    fun normalizeOrder() {
        state.normalizeOrder()
    }

    fun clampToLoadedChars(pageChars: Map<Int, List<PdfTextExtractor.PdfChar>>) {
        fun clamp(page: Int, char: Int): Int {
            val chars = pageChars[page] ?: return char
            if (chars.isEmpty()) return char
            val lo = chars.minOf { it.indexOnPage }
            val hi = chars.maxOf { it.indexOnPage }
            return char.coerceIn(lo, hi)
        }
        if (state.startPage >= 0) state.startChar = clamp(state.startPage, state.startChar)
        if (state.endPage >= 0) state.endChar = clamp(state.endPage, state.endChar)
        if (state.anchorPage >= 0) state.anchorChar = clamp(state.anchorPage, state.anchorChar)
        state.normalizeOrder()
    }

    fun selectedText(pageChars: Map<Int, List<PdfTextExtractor.PdfChar>>): String {
        if (!state.hasSelection()) return ""
        val sb = StringBuilder()
        for (p in state.startPage..state.endPage) {
            val chars = pageChars[p] ?: continue
            if (chars.isEmpty()) continue
            val minIdx = chars.minOf { it.indexOnPage }
            val maxIdx = chars.maxOf { it.indexOnPage }
            val a = if (p == state.startPage) state.startChar.coerceIn(minIdx, maxIdx) else minIdx
            val b = if (p == state.endPage) state.endChar.coerceIn(minIdx, maxIdx) else maxIdx
            if (a > b) continue
            if (sb.isNotEmpty()) sb.append('\n')
            for (c in chars) {
                if (c.indexOnPage in a..b) sb.append(c.char)
            }
        }
        return sb.toString()
    }

    /**
     * 页内字符 → (段落下标, 段内偏移)。**只映射同页**，避免跨页误读。
     */
    fun mapPageCharToParaOffset(
        paraLinks: List<PdfTextExtractor.ParaLink>,
        page: Int,
        charIndexOnPage: Int,
    ): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestDist = Int.MAX_VALUE
        for ((i, link) in paraLinks.withIndex()) {
            if (link.pageIndex != page) continue
            val endExcl = link.charEnd.coerceAtLeast(link.charStart)
            if (charIndexOnPage in link.charStart until endExcl) {
                return i to (charIndexOnPage - link.charStart)
            }
            val nearestOff = when {
                charIndexOnPage < link.charStart -> 0
                else -> (endExcl - link.charStart - 1).coerceAtLeast(0)
            }
            val dist = when {
                charIndexOnPage < link.charStart -> link.charStart - charIndexOnPage
                else -> charIndexOnPage - (endExcl - 1).coerceAtLeast(link.charStart)
            }
            if (dist < bestDist) {
                bestDist = dist
                best = i to nearestOff
            }
        }
        return best
    }

    /**
     * 边缘滚选：终点长时间不推进则应停滚。
     * @return true = 应停止本次滚动
     */
    fun noteEdgeScrollAndShouldStop(): Boolean {
        if (!state.hasSelection()) {
            stuckFrames = 0
            return false
        }
        if (state.endPage == lastEndPage && state.endChar == lastEndChar) {
            stuckFrames++
            if (stuckFrames >= 36) {
                edgeScrollState.reset()
                stuckFrames = 0
                return true
            }
        } else {
            stuckFrames = 0
            lastEndPage = state.endPage
            lastEndChar = state.endChar
        }
        return false
    }

    fun resetEdgeScrollStuck() {
        stuckFrames = 0
    }

    fun pagesInSelectionExpanded(pageCount: Int): List<Int> {
        if (!state.hasSelection()) return emptyList()
        val from = min(state.startPage, state.endPage)
        val to = max(state.startPage, state.endPage)
        return ((from - 1)..(to + 1)).filter { it in 0 until pageCount }
    }

    fun selectionHandlePoints(rects: List<RectF>): Pair<PointF, PointF>? {
        if (rects.isEmpty()) return null
        val first = rects.first()
        val last = rects.last()
        return PointF(first.left, first.bottom) to PointF(last.right, last.bottom)
    }

    fun fillContentRect(rects: List<RectF>, out: android.graphics.Rect): Boolean {
        if (rects.isEmpty()) return false
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = Float.MIN_VALUE
        var b = Float.MIN_VALUE
        for (rf in rects) {
            l = min(l, rf.left)
            t = min(t, rf.top)
            r = max(r, rf.right)
            b = max(b, rf.bottom)
        }
        out.set(
            l.toInt().coerceAtLeast(0),
            t.toInt().coerceAtLeast(0),
            r.toInt().coerceAtLeast(l.toInt() + 1),
            b.toInt().coerceAtLeast(t.toInt() + 1),
        )
        return true
    }

    fun charIndexByPageFraction(
        chars: List<PdfTextExtractor.PdfChar>,
        frac: Float,
        preferEnd: Boolean,
    ): Int {
        if (chars.isEmpty()) return if (preferEnd) 999_999 else 0
        val sorted = chars.filter { !it.char.isWhitespace() }
            .sortedWith(compareBy({ it.top }, { it.left }))
        if (sorted.isEmpty()) {
            return if (preferEnd) chars.maxOf { it.indexOnPage } else chars.minOf { it.indexOnPage }
        }
        val f = frac.coerceIn(0f, 1f)
        val i = (f * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[i].indexOnPage
    }

    fun nearestCharIndex(
        chars: List<PdfTextExtractor.PdfChar>,
        pageX: Float,
        pageY: Float,
        always: Boolean = false,
    ): Int? {
        if (chars.isEmpty()) return null
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (c in chars) {
            if (c.char.isWhitespace()) continue
            if (c.contains(pageX, pageY, pad = 8f)) {
                return c.indexOnPage
            }
            val dx = c.midX - pageX
            val dy = c.midY - pageY
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = c.indexOnPage
            }
        }
        if (best < 0) return null
        if (always) return best
        val pageW = chars.first().pageWidth.coerceAtLeast(1f)
        val thr = (pageW * 0.08f).coerceIn(24f, 80f)
        return if (bestDist < thr * thr) best else null
    }
}
