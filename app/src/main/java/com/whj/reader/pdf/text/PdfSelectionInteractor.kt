package com.whj.reader.pdf.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.PdfReadingActivity
import com.whj.reader.R
import com.whj.reader.data.PdfTextExtractor
import com.whj.reader.databinding.ActivityPdfReadingBinding
import com.whj.reader.model.PdfPageMode
import com.whj.reader.pdf.coord.PdfViewMapper
import com.whj.reader.ui.PdfPageSurface
import com.whj.reader.ui.TextSelectionHandles
import com.whj.reader.util.ReaderLog
import com.whj.reader.util.Toasts
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 选字交互器：长按起选、拖动扩展、handle 调整、边缘滚选、跨页拖选翻页、
 * 命中检测（hitTestChar）、坐标变换（view<->page<->content<->container）、
 * 选区/高亮 overlay 刷新、浮动 ActionMode（复制/朗读）。
 *
 * 纯状态与文本逻辑由 [PdfTextSelectionController] 承担；本类负责与 binding/surface/zoom
 * 交互的几何与 UI 部分，直接持有 [com.whj.reader.PdfReadingActivity] 引用。
 */
class PdfSelectionInteractor(
    private val activity: PdfReadingActivity,
) {

    private val ctx: Context get() = activity
    private val b: ActivityPdfReadingBinding get() = activity.binding
    private val selCtrl: PdfTextSelectionController get() = activity.textSelCtrl
    private val textSel get() = selCtrl.state
    private val textCache get() = activity.textCache
    private val density get() = ctx.resources.displayMetrics.density

    private var textActionMode: ActionMode? = null

    fun compareDocPos(pageA: Int, charA: Int, pageB: Int, charB: Int): Int =
        PdfTextSelectionState.compareDocPos(pageA, charA, pageB, charB)

    fun hasTextSelection(): Boolean = selCtrl.hasSelection()

    fun hasTtsHighlight(): Boolean =
        activity.hlStartPage >= 0 && activity.hlEndPage >= 0 &&
            activity.hlStartChar >= 0 && activity.hlEndChar >= 0 &&
            compareDocPos(
                activity.hlStartPage, activity.hlStartChar,
                activity.hlEndPage, activity.hlEndChar,
            ) <= 0

    fun clearTtsHighlight() {
        activity.hlStartPage = -1
        activity.hlStartChar = -1
        activity.hlEndPage = -1
        activity.hlEndChar = -1
        b.pdfSelectionOverlay.clearHighlight()
    }

    fun clearTextSelection(fromActionModeDestroy: Boolean = false) {
        if (!fromActionModeDestroy) {
            textActionMode?.finish()
        }
        textActionMode = null
        selCtrl.clear()
        b.pdfSelectionOverlay.clearSelection()
    }

    fun setSelectionFromAnchorAndHit(hitPage: Int, hitChar: Int) {
        selCtrl.setFromAnchorAndHit(hitPage, hitChar)
    }

    fun normalizeSelectionOrder() {
        selCtrl.normalizeOrder()
    }

    fun clampSelectionToLoadedChars() {
        selCtrl.clampToLoadedChars(textCache.pageChars)
    }

    /** 选区覆盖的每一页上的字符闭区间 -> 容器矩形（跨页拼接） */
    fun multiPageCharRangeToContainerRects(
        startPage: Int,
        startChar: Int,
        endPage: Int,
        endChar: Int,
    ): List<RectF> {
        if (startPage < 0 || endPage < 0) return emptyList()
        if (compareDocPos(startPage, startChar, endPage, endChar) > 0) return emptyList()
        val out = ArrayList<RectF>()
        for (p in startPage..endPage) {
            val chars = textCache.pageChars[p] ?: continue
            if (chars.isEmpty()) continue
            val minIdx = chars.minOf { it.indexOnPage }
            val maxIdx = chars.maxOf { it.indexOnPage }
            val a = if (p == startPage) startChar.coerceIn(minIdx, maxIdx) else minIdx
            val bb = if (p == endPage) endChar.coerceIn(minIdx, maxIdx) else maxIdx
            if (a > bb) continue
            out.addAll(charRangeToContainerRects(p, a, bb))
        }
        return out
    }

    fun beginTextSelection(containerX: Float, containerY: Float) {
        val vis = activity.currentVisiblePage()
        val est = pageIndexAtContainerY(containerY) ?: vis
        ReaderLog.i(
            ReaderLog.Module.PDF_SELECT,
            "longPress xy=(${"%.0f".format(containerX)},${"%.0f".format(containerY)}) " +
                "mode=${activity.pageMode} vis=$vis est=$est " +
                "rawKeys=${textCache.rawPageCache.keys.sorted()} " +
                "textCache.pageChars=${textCache.pageChars.mapValues { it.value.size }.toSortedMap()} " +
                "rawSize(est)=${textCache.rawPageCache[est]?.size} textCache.pageChars(est)=${textCache.pageChars[est]?.size}",
        )
        val need = activity.pagesNear(est, before = 1, after = 2)
        val uncached = need.filter { it !in textCache.rawPageCache }
        fun afterExtract() {
            if (activity.isFinishing || activity.isDestroyed) return
            if (textCache.pageChars[est].isNullOrEmpty() && textCache.rawPageCache.isNotEmpty()) {
                runCatching { activity.rebuildTextFromCache(preserveTtsPosition = false) }
            }
            beginTextSelectionAfterReady(containerX, containerY)
            if (!hasTextSelection()) {
                val rawN = textCache.rawPageCache[est]?.size ?: -1
                val pcN = textCache.pageChars[est]?.size ?: -1
                ReaderLog.w(
                    ReaderLog.Module.PDF_SELECT,
                    "begin failed after extract est=$est rawN=$rawN pageCharsN=$pcN",
                )
                if (rawN <= 0 || pcN <= 0) {
                    Toasts.show(ctx, R.string.pdf_select_no_text)
                }
            }
        }
        if (uncached.isNotEmpty()) {
            activity.ensurePagesExtracted(
                pages = need,
                showToast = true,
                preserveTtsPosition = false,
            ) { afterExtract() }
            return
        }
        afterExtract()
    }

    /** 文字已就绪（或确认无字）后进入选区，禁止再触发提取递归 */
    fun beginTextSelectionAfterReady(containerX: Float, containerY: Float) {
        val hit = runCatching {
            hitTestChar(containerX, containerY, forSelection = true)
        }.getOrNull() ?: run {
            ReaderLog.w(
                ReaderLog.Module.PDF_SELECT,
                "begin miss xy=(${"%.0f".format(containerX)},${"%.0f".format(containerY)}) " +
                    "vis=${activity.currentVisiblePage()} " +
                    "est=${pageIndexAtContainerY(containerY)} " +
                    "pageCharsKeys=${textCache.pageChars.keys.sorted()}",
            )
            return
        }
        val page = hit.first
        var charIdx = hit.second
        val chars = textCache.pageChars[page]
        if (chars.isNullOrEmpty()) {
            ReaderLog.w(
                ReaderLog.Module.PDF_SELECT,
                "begin hit p=$page char=$charIdx but textCache.pageChars empty raw=${textCache.rawPageCache[page]?.size}",
            )
            return
        }
        val lo = chars.minOf { it.indexOnPage }
        val hi = chars.maxOf { it.indexOnPage }
        charIdx = charIdx.coerceIn(lo, hi)
        val endIdx = (charIdx + 1).coerceAtMost(hi)
        textSel.anchorPage = page
        textSel.anchorChar = charIdx
        textSel.startPage = page
        textSel.startChar = charIdx
        textSel.endPage = page
        textSel.endChar = endIdx
        val rects = multiPageCharRangeToContainerRects(page, charIdx, page, endIdx)
        ReaderLog.i(
            ReaderLog.Module.PDF_SELECT,
            "begin p=$page char=$charIdx..$endIdx chars=${chars.size} " +
                "rects=${rects.size} first=${rects.firstOrNull()} " +
                "samplePage=${chars.first().pageWidth}x${chars.first().pageHeight} " +
                "mode=${activity.pageMode}",
        )
        activity.ensurePagesExtracted(
            pages = activity.pagesNear(page, before = 1, after = 3),
            showToast = false,
            preserveTtsPosition = true,
        ) {
            if (!activity.isFinishing && !activity.isDestroyed && hasTextSelection()) {
                clampSelectionToLoadedChars()
                refreshSelectionOverlay()
            }
        }
        runCatching {
            refreshSelectionOverlay()
            if (rects.isEmpty()) {
                ReaderLog.w(ReaderLog.Module.PDF_SELECT, "begin empty rects, still show action mode")
            }
            showTextActionMode()
        }.onFailure {
            ReaderLog.e(ReaderLog.Module.PDF, "begin selection UI failed", it)
            clearTextSelection()
        }
    }

    fun extendTextSelection(containerX: Float, containerY: Float) {
        if (textSel.anchorPage < 0 || textSel.anchorChar < 0) return
        val hit = hitTestChar(containerX, containerY, forSelection = true) ?: run {
            ReaderLog.d(ReaderLog.Module.PDF_SELECT, "extend miss xy=($containerX,$containerY)")
            return
        }
        val before = "$textSel.startPage:$textSel.startChar-$textSel.endPage:$textSel.endChar"
        setSelectionFromAnchorAndHit(hit.first, hit.second)
        ReaderLog.i(
            ReaderLog.Module.PDF_SELECT,
            "extend hit=${hit.first}:${hit.second} anchor=$textSel.anchorPage:$textSel.anchorChar " +
                "range=$textSel.startPage:$textSel.startChar-$textSel.endPage:$textSel.endChar was=$before " +
                "charsPages=${textCache.pageChars.keys.sorted()}",
        )
        prefetchTextForSelectionRange()
        refreshSelectionOverlay()
    }

    /** 选区跨越的页若尚未抽字，后台补齐并回夹字符下标 */
    fun prefetchTextForSelectionRange() {
        if (!hasTextSelection()) return
        val from = min(textSel.startPage, textSel.endPage)
        val to = max(textSel.startPage, textSel.endPage)
        val expanded = ((from - 1)..(to + 1)).filter { it in 0 until activity.pageCount }
        val need = expanded.filter { it !in textCache.rawPageCache }
        if (need.isEmpty()) {
            clampSelectionToLoadedChars()
            return
        }
        ReaderLog.i(ReaderLog.Module.PDF_SELECT, "prefetch extract pages=$need")
        activity.ensurePagesExtracted(
            pages = need,
            showToast = false,
            preserveTtsPosition = true,
        ) {
            if (!activity.isFinishing && !activity.isDestroyed && hasTextSelection()) {
                clampSelectionToLoadedChars()
                refreshSelectionOverlay()
            }
        }
    }

    fun adjustPdfSelectionHandle(which: TextSelectionHandles.Which, x: Float, y: Float) {
        if (!hasTextSelection()) return
        val hit = hitTestChar(x, y, forSelection = true) ?: return
        when (which) {
            TextSelectionHandles.Which.START -> {
                textSel.startPage = hit.first
                textSel.startChar = hit.second
                textSel.anchorPage = textSel.endPage
                textSel.anchorChar = textSel.endChar
            }
            TextSelectionHandles.Which.END -> {
                textSel.endPage = hit.first
                textSel.endChar = hit.second
                textSel.anchorPage = textSel.startPage
                textSel.anchorChar = textSel.startChar
            }
        }
        normalizeSelectionOrder()
        ReaderLog.i(
            ReaderLog.Module.PDF_SELECT,
            "handle $which -> $textSel.startPage:$textSel.startChar-$textSel.endPage:$textSel.endChar",
        )
        prefetchTextForSelectionRange()
        refreshSelectionOverlay()
    }

    fun autoScrollPdfWhileSelecting(containerY: Float) {
        val container = b.pdfContainer
        val h = container.height.toFloat()
        if (h <= 1f) return
        val unit = 24f * density
        val step = TextSelectionHandles.edgeScrollStep(
            containerY,
            h,
            unit,
            density,
            selCtrl.edgeScrollState,
        )
        if (step == 0f) {
            selCtrl.resetEdgeScrollStuck()
            return
        }
        if (selCtrl.noteEdgeScrollAndShouldStop()) {
            ReaderLog.w(
                ReaderLog.Module.PDF_SELECT,
                "edgeScroll stuck end=${textSel.endPage}:${textSel.endChar} stop",
            )
            return
        }
        when (activity.pageMode) {
            PdfPageMode.CONTINUOUS -> {
                val dy = step.toInt().coerceIn(-80, 80)
                b.rvPdfPages.scrollBy(0, dy)
                activity.updateProgressLabel()
                extendSelectionByDocumentY(containerY, forward = step > 0f)
            }
            PdfPageMode.SINGLE -> {
                if (container.canPanContent()) {
                    val before = container.getPanY()
                    container.setTransform(
                        container.contentZoom,
                        container.getPanX(),
                        container.getPanY() + step,
                        false,
                    )
                    val moved = abs(container.getPanY() - before) > 0.5f
                    if (!moved) {
                        trySelectPageTurnWhileSelecting(forward = step > 0f)
                    }
                } else {
                    trySelectPageTurnWhileSelecting(forward = step > 0f)
                }
            }
        }
        if (hasTtsHighlight()) refreshHighlightOverlay()
        refreshSelectionOverlay()
    }

    /**
     * 连续模式：用页高表估算手指处文档页，强制推进选区终点（解决「只能选到有 child 的页」）。
     */
    fun extendSelectionByDocumentY(containerY: Float, forward: Boolean) {
        if (textSel.anchorPage < 0 || activity.pageMode != PdfPageMode.CONTINUOUS) return
        val page = pageIndexAtContainerY(containerY) ?: return
        activity.ensurePagesExtracted(
            pages = activity.pagesNear(page, before = 1, after = 2),
            showToast = false,
            preserveTtsPosition = true,
        ) {
            if (!activity.isFinishing && !activity.isDestroyed && hasTextSelection()) {
                clampSelectionToLoadedChars()
                refreshSelectionOverlay()
            }
        }
        val chars = textCache.pageChars[page]
        val charIdx = if (chars.isNullOrEmpty()) {
            if (forward) 999_999 else 0
        } else if (forward) {
            val frac = pageLocalYFraction(containerY, page).coerceIn(0f, 1f)
            val sorted = chars.filter { !it.char.isWhitespace() }
                .sortedWith(compareBy({ it.top }, { it.left }))
            if (sorted.isEmpty()) chars.maxOf { it.indexOnPage }
            else {
                val i = (frac * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)
                sorted[i].indexOnPage
            }
        } else {
            val frac = pageLocalYFraction(containerY, page).coerceIn(0f, 1f)
            val sorted = chars.filter { !it.char.isWhitespace() }
                .sortedWith(compareBy({ it.top }, { it.left }))
            if (sorted.isEmpty()) chars.minOf { it.indexOnPage }
            else {
                val i = (frac * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)
                sorted[i].indexOnPage
            }
        }
        setSelectionFromAnchorAndHit(page, charIdx)
        ReaderLog.d(
            ReaderLog.Module.PDF_SELECT,
            "extendByDocY page=$page char=$charIdx end=$textSel.endPage:$textSel.endChar fwd=$forward",
        )
    }

    /** 容器 Y -> 页高表估算的 0-based 页码 */
    fun pageIndexAtContainerY(containerY: Float): Int? {
        if (activity.pageCount <= 0) return null
        val content = b.pdfContainer.mapToContent(
            b.pdfContainer.width / 2f,
            containerY,
        )
        val rv = b.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return null
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return null
        val firstChild = lm.findViewByPosition(first)
        val absY = activity.scrollOffsetForPageTop(first) +
            (content.y - (firstChild?.top?.toFloat() ?: 0f))
        var acc = 0L
        for (i in 0 until activity.pageCount) {
            val hh = activity.itemHeightAt(i).toLong().coerceAtLeast(1L)
            if (absY < acc + hh) return i
            acc += hh
        }
        return activity.pageCount - 1
    }

    /** 手指在 [page] 页内的纵向比例 0..1（估） */
    fun pageLocalYFraction(containerY: Float, page: Int): Float {
        val content = b.pdfContainer.mapToContent(
            b.pdfContainer.width / 2f,
            containerY,
        )
        val top = activity.scrollOffsetForPageTop(page).toFloat()
        val hh = activity.itemHeightAt(page).toFloat().coerceAtLeast(1f)
        val rv = b.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager
        val first = lm?.findFirstVisibleItemPosition() ?: return 0.5f
        val firstChild = lm.findViewByPosition(first)
        val absY = activity.scrollOffsetForPageTop(first) +
            (content.y - (firstChild?.top?.toFloat() ?: 0f))
        return ((absY - top) / hh).coerceIn(0f, 1f)
    }

    /** 单页模式拖选到边缘时翻页，并把焦点落到新页首/末字 */
    fun trySelectPageTurnWhileSelecting(forward: Boolean) {
        if (activity.pageMode != PdfPageMode.SINGLE || activity.pageCount <= 1) return
        val target = if (forward) activity.pageIndex + 1 else activity.pageIndex - 1
        if (target !in 0 until activity.pageCount) return
        ReaderLog.i(ReaderLog.Module.PDF_SELECT, "select pageTurn -> $target forward=$forward")
        activity.showSinglePageForSelection(target, snapToTop = forward)
        activity.ensurePagesExtracted(
            pages = activity.pagesNear(target, before = 1, after = 1),
            showToast = false,
            preserveTtsPosition = true,
        ) {
            if (activity.isFinishing || activity.isDestroyed || !hasTextSelection()) return@ensurePagesExtracted
            val chars = textCache.pageChars[target]
            val charIdx = if (chars.isNullOrEmpty()) {
                if (forward) 0 else 999_999
            } else if (forward) {
                chars.minOf { it.indexOnPage }
            } else {
                chars.maxOf { it.indexOnPage }
            }
            setSelectionFromAnchorAndHit(target, charIdx)
            clampSelectionToLoadedChars()
            refreshSelectionOverlay()
        }
    }

    fun ensurePdfSelectionEdgeScrollLoop() {
        if (selCtrl.edgeScrollPosted) return
        selCtrl.edgeScrollPosted = true
        b.pdfContainer.postOnAnimation { runPdfSelectionEdgeScrollLoop() }
    }

    fun runPdfSelectionEdgeScrollLoop() {
        if (!selCtrl.dragActive || activity.isFinishing || activity.isDestroyed) {
            selCtrl.edgeScrollPosted = false
            return
        }
        autoScrollPdfWhileSelecting(selCtrl.dragY)
        val h = b.pdfContainer.height.toFloat().coerceAtLeast(1f)
        val edge = (h * 0.14f).coerceAtLeast(48f * density)
        val atEdge = selCtrl.dragY < edge || selCtrl.dragY > h - edge
        if (atEdge) {
            val handle = selCtrl.draggingHandle
            if (handle != null) {
                adjustPdfSelectionHandle(handle, selCtrl.dragX, selCtrl.dragY)
            } else {
                extendTextSelection(selCtrl.dragX, selCtrl.dragY)
            }
        }
        if (!selCtrl.dragActive) {
            selCtrl.edgeScrollPosted = false
            return
        }
        b.pdfContainer.postOnAnimation { runPdfSelectionEdgeScrollLoop() }
    }

    /**
     * 命中：pageIndex + charIndexOnPage。
     * [forSelection]=true 时：无字页也返回临时下标（并触发抽字），距离阈值放宽，保证可跨页拖选。
     */
    fun hitTestChar(
        containerX: Float,
        containerY: Float,
        forSelection: Boolean = false,
    ): Pair<Int, Int>? {
        val content = b.pdfContainer.mapToContent(containerX, containerY)
        return when (activity.pageMode) {
            PdfPageMode.SINGLE -> {
                val page = activity.pageIndex
                val pageXY = viewToPageCoords(b.ivPdfPage, content.x, content.y, page)
                val chars = textCache.pageChars[page]
                if (chars.isNullOrEmpty()) {
                    if (!forSelection) return null
                    activity.ensurePagesExtracted(
                        pages = activity.pagesNear(page, 1, 1),
                        showToast = false,
                        preserveTtsPosition = true,
                    ) {
                        if (!activity.isFinishing && !activity.isDestroyed && hasTextSelection()) {
                            clampSelectionToLoadedChars()
                            refreshSelectionOverlay()
                        }
                    }
                    val provisional = if ((pageXY?.get(1) ?: 0f) > 200f) 999_999 else 0
                    ReaderLog.d(
                        ReaderLog.Module.PDF_SELECT,
                        "hit SINGLE p=$page noChars provisional=$provisional",
                    )
                    return page to provisional
                }
                val xy = pageXY ?: return if (forSelection) {
                    val fallback = nearestCharIndex(chars, 0f, 0f, always = true)
                        ?: chars.firstOrNull { !it.char.isWhitespace() }?.indexOnPage
                    fallback?.let { page to it }
                } else {
                    null
                }
                nearestCharIndex(chars, xy[0], xy[1], always = forSelection)?.let { page to it }
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = b.rvPdfPages
                var child = rv.findChildViewUnder(content.x, content.y)
                if (child == null) {
                    var best: View? = null
                    var bestDist = Float.MAX_VALUE
                    for (i in 0 until rv.childCount) {
                        val c = rv.getChildAt(i) ?: continue
                        val mid = (c.top + c.bottom) / 2f
                        val dist = abs(content.y - mid)
                        if (dist < bestDist) {
                            bestDist = dist
                            best = c
                        }
                    }
                    child = best
                }
                val estPage = if (forSelection) {
                    pageIndexAtContainerY(containerY)
                } else {
                    null
                }
                val childView = child
                val posFromChild = childView?.let { rv.getChildAdapterPosition(it) }
                    ?.takeIf { it != RecyclerView.NO_POSITION }
                val pos = when {
                    estPage != null && posFromChild != null -> {
                        if (abs(estPage - posFromChild) > 0) estPage else posFromChild
                    }
                    estPage != null -> estPage
                    posFromChild != null -> posFromChild
                    else -> {
                        ReaderLog.d(
                            ReaderLog.Module.PDF_SELECT,
                            "hit CONT no page content=(${content.x},${content.y}) kids=${rv.childCount}",
                        )
                        return null
                    }
                }
                val surface = childView?.findViewById<PdfPageSurface>(R.id.ivPage)
                val chars = textCache.pageChars[pos]
                if (chars.isNullOrEmpty()) {
                    if (!forSelection) return null
                    activity.ensurePagesExtracted(
                        pages = activity.pagesNear(pos, before = 1, after = 2),
                        showToast = false,
                        preserveTtsPosition = true,
                    ) {
                        if (!activity.isFinishing && !activity.isDestroyed && hasTextSelection()) {
                            clampSelectionToLoadedChars()
                            refreshSelectionOverlay()
                        }
                    }
                    val provisional = if (
                        surface != null &&
                        content.y > (childView.top + surface.top + surface.height * 0.5f)
                    ) {
                        999_999
                    } else if (pageLocalYFraction(containerY, pos) > 0.5f) {
                        999_999
                    } else {
                        0
                    }
                    ReaderLog.i(
                        ReaderLog.Module.PDF_SELECT,
                        "hit CONT p=$pos noChars provisional=$provisional est=$estPage child=$posFromChild",
                    )
                    return pos to provisional
                }
                val pageXY = if (surface != null && childView != null &&
                    rv.getChildAdapterPosition(childView) == pos
                ) {
                    val localX = content.x - childView.left - surface.left
                    val localY = content.y - childView.top - surface.top
                    val clampedY = localY.coerceIn(
                        0f,
                        max(surface.height, surface.logicalHeight).toFloat().coerceAtLeast(1f),
                    )
                    surface.viewToPage(localX, clampedY)
                } else {
                    val frac = pageLocalYFraction(containerY, pos)
                    val sample = chars.first()
                    floatArrayOf(
                        sample.pageWidth * 0.5f,
                        sample.pageHeight * frac,
                    )
                }
                val idx = nearestCharIndex(
                    chars,
                    pageXY[0],
                    pageXY[1],
                    always = forSelection,
                )
                idx?.let { pos to it }
            }
        }
    }

    /**
     * 取页在 PDFBox 与 PdfRenderer 下的尺寸，字符坐标按 PDFBox 尺寸；
     * 映射到图时用「归一化 0~1」再乘到裁剪后的位图区域，避免两边尺寸不一致。
     */
    fun pageLogicalSize(pageIndex: Int): Pair<Float, Float> {
        val sample = textCache.pageChars[pageIndex]?.firstOrNull()
            ?: textCache.rawPageCache[pageIndex]?.firstOrNull()
        if (sample != null && sample.pageWidth > 1f && sample.pageHeight > 1f) {
            return sample.pageWidth to sample.pageHeight
        }
        activity.rendererPageSize[pageIndex]?.let { return it }
        val r = activity.renderer ?: return 1f to 1f
        return try {
            synchronized(activity.renderLock) {
                activity.currentPage?.close()
                activity.currentPage = null
                val page = r.openPage(pageIndex.coerceIn(0, r.pageCount - 1))
                activity.currentPage = page
                val sz = page.width.toFloat() to page.height.toFloat()
                page.close()
                activity.currentPage = null
                activity.rendererPageSize[pageIndex] = sz
                sz
            }
        } catch (_: Exception) {
            1f to 1f
        }
    }

    /**
     * ImageView 本地坐标 -> PDF 页坐标（左上原点、Y 向下，与 [PdfTextExtractor.PdfChar] 一致）。
     */
    fun viewToPageCoords(
        iv: ImageView,
        localX: Float,
        localY: Float,
        pageIndex: Int,
    ): FloatArray? {
        if (activity.singlePageUsesTiles && iv === b.ivPdfPage) {
            val surface = activity.singlePageSurface ?: return null
            return viewToPageCoordsOnSurface(surface, localX, localY, pageIndex)
        }
        val d = iv.drawable ?: return null
        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val vw = iv.width.toFloat().coerceAtLeast(1f)
        val vh = iv.height.toFloat().coerceAtLeast(1f)
        val fitByWidth = activity.singlePageFitByWidth(dw, dh, vw, vh)
        val scale = if (fitByWidth) vw / dw else min(vw / dw, vh / dh)
        val ox = if (fitByWidth) 0f else (vw - dw * scale) / 2f
        val oy = when {
            fitByWidth && dh * scale > vh -> 0f
            else -> (vh - dh * scale) / 2f
        }
        val bx = (localX - ox) / scale
        val by = (localY - oy) / scale
        if (bx < -4f || by < -4f || bx > dw + 4f || by > dh + 4f) return null

        val (pageW, pageH) = pageLogicalSize(pageIndex)
        val margins = activity.cropForPage(pageIndex)
        val cl = margins[0]
        val ct = margins[1]
        val cr = margins[2]
        val cb = margins[3]
        val srcW = pageW * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = pageH * (1f - ct - cb).coerceAtLeast(0.2f)
        val pageX = pageW * cl + (bx / dw) * srcW
        val pageY = pageH * ct + (by / dh) * srcH
        return floatArrayOf(pageX, pageY)
    }

    fun viewToPageCoordsOnSurface(
        surface: PdfPageSurface,
        localX: Float,
        localY: Float,
        pageIndex: Int,
    ): FloatArray? {
        val vw = surface.width.toFloat().coerceAtLeast(1f)
        val displayH = surface.logicalHeight.coerceAtLeast(1).toFloat()
        val (pageW, pageH) = pageLogicalSize(pageIndex)
        val margins = activity.cropForPage(pageIndex)
        val cl = margins[0]
        val ct = margins[1]
        val cr = margins[2]
        val cb = margins[3]
        val srcW = pageW * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = pageH * (1f - ct - cb).coerceAtLeast(0.2f)
        if (localX < -4f || localY < -4f || localX > vw + 4f || localY > displayH + 4f) return null
        val pageX = pageW * cl + (localX / vw) * srcW
        val pageY = pageH * ct + (localY / displayH) * srcH
        return floatArrayOf(pageX, pageY)
    }

    /**
     * @param always true=拖选模式：总是返回最近字符（不因距离阈值失败，否则跨页拖到页边会 miss）
     */
    fun nearestCharIndex(
        chars: List<PdfTextExtractor.PdfChar>,
        pageX: Float,
        pageY: Float,
        always: Boolean = false,
    ): Int? = selCtrl.nearestCharIndex(chars, pageX, pageY, always)

    fun selectedText(): String =
        selCtrl.selectedText(textCache.pageChars)

    fun refreshSelectionOverlay() {
        if (!hasTextSelection()) {
            b.pdfSelectionOverlay.clearSelection()
            return
        }
        val rects = multiPageCharRangeToContainerRects(
            textSel.startPage, textSel.startChar, textSel.endPage, textSel.endChar,
        )
        val handles = selectionHandlePoints(rects)
        if (rects.isEmpty()) {
            ReaderLog.w(
                ReaderLog.Module.PDF_SELECT,
                "refreshOverlay empty rects range=" +
                    "$textSel.startPage:$textSel.startChar-$textSel.endPage:$textSel.endChar " +
                    "charsN=${textCache.pageChars[textSel.startPage]?.size} " +
                    "child=${(b.rvPdfPages.layoutManager as? LinearLayoutManager)
                        ?.findViewByPosition(textSel.startPage) != null}",
            )
        }
        b.pdfSelectionOverlay.setSelectionRects(
            rects,
            handles?.first,
            handles?.second,
        )
        b.pdfSelectionOverlay.bringToFront()
        b.pdfSelectionOverlay.invalidate()
        invalidateTextSelectionActionMode()
    }

    fun fillTextSelectionContentRect(out: Rect): Boolean {
        if (!hasTextSelection()) return false
        val rects = multiPageCharRangeToContainerRects(
            textSel.startPage, textSel.startChar, textSel.endPage, textSel.endChar,
        )
        return selCtrl.fillContentRect(rects, out)
    }

    fun invalidateTextSelectionActionMode() {
        if (textActionMode == null || !hasTextSelection()) return
        textActionMode?.invalidateContentRect()
    }

    fun selectionHandlePoints(rects: List<RectF>): Pair<PointF, PointF>? =
        selCtrl.selectionHandlePoints(rects)

    fun refreshHighlightOverlay() {
        if (!hasTtsHighlight()) {
            b.pdfSelectionOverlay.clearHighlight()
            return
        }
        val rects = multiPageCharRangeToContainerRects(
            activity.hlStartPage, activity.hlStartChar, activity.hlEndPage, activity.hlEndChar,
        )
        b.pdfSelectionOverlay.setHighlightRects(rects)
    }

    /** 将页内字符区间映射为容器坐标系矩形列表（合并同行） */
    fun charRangeToContainerRects(
        page: Int,
        startIdx: Int,
        endIdx: Int,
    ): List<RectF> {
        val chars = textCache.pageChars[page] ?: return emptyList()
        var selected = chars.filter {
            it.indexOnPage in startIdx..endIdx && !it.char.isWhitespace()
        }
        if (selected.isEmpty()) {
            selected = chars.filter { it.indexOnPage in startIdx..endIdx }
            if (selected.isEmpty()) {
                val nearest = chars.minByOrNull { abs(it.indexOnPage - startIdx) }
                if (nearest != null) selected = listOf(nearest)
            }
        }
        if (selected.isEmpty()) return emptyList()
        val contentRects = ArrayList<RectF>()
        when (activity.pageMode) {
            PdfPageMode.SINGLE -> {
                val iv = b.ivPdfPage
                for (line in mergeLineRects(selected)) {
                    pageRectToContent(iv, page, line, 0f, 0f)?.let { contentRects.add(it) }
                }
                if (contentRects.isEmpty() && activity.singlePageUsesTiles) {
                    val surface = activity.singlePageSurface
                    if (surface != null) {
                        for (line in mergeLineRects(selected)) {
                            val local = mapPdfCharRectToSurfaceView(surface, page, line, selected)
                            contentRects.add(local)
                        }
                    }
                }
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = b.rvPdfPages
                val lm = rv.layoutManager as? LinearLayoutManager ?: return emptyList()
                val child = lm.findViewByPosition(page)
                if (child == null) {
                    ReaderLog.d(
                        ReaderLog.Module.PDF_SELECT,
                        "charRects no child page=$page firstVis=${lm.findFirstVisibleItemPosition()}",
                    )
                    return emptyList()
                }
                val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: return emptyList()
                val ox = child.left + surface.left.toFloat()
                val oy = child.top + surface.top.toFloat()
                for (line in mergeLineRects(selected)) {
                    val local = mapPdfCharRectToSurfaceView(surface, page, line, selected)
                    contentRects.add(
                        RectF(
                            ox + local.left,
                            oy + local.top,
                            ox + local.right,
                            oy + local.bottom,
                        ),
                    )
                }
            }
        }
        return contentRects.map { r ->
            val p0 = contentToContainer(r.left, r.top)
            val p1 = contentToContainer(r.right, r.bottom)
            RectF(min(p0[0], p1[0]), min(p0[1], p1[1]), max(p0[0], p1[0]), max(p0[1], p1[1]))
        }
    }

    /**
     * PDF 页坐标矩形 -> [PdfPageSurface] 本地坐标。
     * 页宽高优先用字符自带的 PDFBox 尺寸（与抽字一致）。
     */
    fun mapPdfCharRectToSurfaceView(
        surface: PdfPageSurface,
        pageIndex: Int,
        pageRect: RectF,
        sampleChars: List<PdfTextExtractor.PdfChar>,
    ): RectF {
        val (pw, ph) = PdfViewMapper.pageSizeFromChars(sampleChars)
            ?: pageLogicalSize(pageIndex)
        return PdfViewMapper.mapPageRectToSurfaceView(
            surface, pageRect, pw, ph, activity.cropForPage(pageIndex),
        )
    }

    fun mergeLineRects(chars: List<PdfTextExtractor.PdfChar>): List<RectF> =
        PdfViewMapper.mergeLineRects(chars)

    /**
     * zoomTarget 内容坐标 -> [pdfContainer] 子视图坐标（与选区/高亮 overlay 一致）。
     * 须计入 target 的 layout 位置（padding）与 scale/translation。
     */
    fun contentToContainer(x: Float, y: Float): FloatArray {
        val container = b.pdfContainer
        val target = container.zoomTarget
        val zoom = container.contentZoom.coerceAtLeast(0.01f)
        val panX = container.getPanX()
        val panY = container.getPanY()
        val tl = target?.left?.toFloat() ?: container.paddingLeft.toFloat()
        val tt = target?.top?.toFloat() ?: container.paddingTop.toFloat()
        return floatArrayOf(tl + x * zoom + panX, tt + y * zoom + panY)
    }

    /**
     * 页坐标矩形 -> 单页 ImageView 本地坐标。
     * **必须与 Activity.applySinglePageImageMatrix 一致**：横屏 fit-width 顶对齐，竖屏 fitCenter。
     */
    fun pageRectToContent(
        iv: ImageView,
        pageIndex: Int,
        pageRect: RectF,
        contentOffsetX: Float,
        contentOffsetY: Float,
    ): RectF? {
        val d = iv.drawable ?: return null
        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val vw = iv.width.toFloat().coerceAtLeast(1f)
        val vh = iv.height.toFloat().coerceAtLeast(1f)
        val landscape = vw > vh
        val scale = if (landscape) vw / dw else min(vw / dw, vh / dh)
        val ox = (vw - dw * scale) / 2f
        val oy = if (landscape && dh * scale > vh) 0f else (vh - dh * scale) / 2f
        val (pageW, pageH) = pageLogicalSize(pageIndex)
        val margins = activity.cropForPage(pageIndex)
        val cl = margins[0]
        val ct = margins[1]
        val cr = margins[2]
        val cb = margins[3]
        val srcW = pageW * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = pageH * (1f - ct - cb).coerceAtLeast(0.2f)
        val cropLeft = pageW * cl
        val cropTop = pageH * ct
        fun px(x: Float) = contentOffsetX + ox + ((x - cropLeft) / srcW) * dw * scale
        fun py(y: Float) = contentOffsetY + oy + ((y - cropTop) / srcH) * dh * scale
        return RectF(px(pageRect.left), py(pageRect.top), px(pageRect.right), py(pageRect.bottom))
    }

    fun showTextActionMode() {
        if (!hasTextSelection()) return
        if (textActionMode != null) {
            invalidateTextSelectionActionMode()
            return
        }
        val callback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, R.string.pdf_select_copy)
                menu.add(0, 2, 1, R.string.pdf_select_read)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    1 -> {
                        val text = selectedText()
                        if (text.isNotEmpty()) {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("pdf", text))
                            Toasts.show(ctx, R.string.pdf_text_copied)
                        }
                        mode.finish()
                        clearTextSelection()
                        return true
                    }
                    2 -> {
                        mode.finish()
                        activity.startTtsFromSelection()
                        return true
                    }
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                textActionMode = null
                if (hasTextSelection()) {
                    clearTextSelection(fromActionModeDestroy = true)
                }
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (!fillTextSelectionContentRect(outRect)) {
                    outRect.set(
                        (view.width / 2 - 60).coerceAtLeast(0),
                        (view.height / 3).coerceAtLeast(0),
                        (view.width / 2 + 60).coerceAtMost(view.width.coerceAtLeast(1)),
                        (view.height / 3 + 40).coerceAtMost(view.height.coerceAtLeast(1)),
                    )
                }
            }
        }
        textActionMode = runCatching {
            b.pdfContainer.startActionMode(callback, ActionMode.TYPE_FLOATING)
        }.getOrNull() ?: runCatching {
            b.pdfContainer.startActionMode(callback)
        }.getOrNull()
    }
}
