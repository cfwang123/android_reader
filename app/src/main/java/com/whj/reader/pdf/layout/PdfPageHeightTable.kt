package com.whj.reader.pdf.layout

/**
 * 连续模式稳定页高表：用已知高度累计定位，避免 RV 变高估算导致拖动手柄跳动。
 *
 * UI 侧滚动补偿（上方页变高后 scrollBy）由宿主处理；本类只维护高度数组与数学。
 */
class PdfPageHeightTable {

    data class SeekTarget(val page: Int, val offsetInPage: Int)

    /** 与 item_pdf_page 的 pageDivider 一致 */
    var pageDividerPx: Int = 5

    /** 未知页尺寸时的估算宽高比 H/W */
    @Volatile
    var estimatedPageAspect: Float = 1.414f

    private var heights: IntArray = IntArray(0)

    val size: Int get() = heights.size

    fun snapshotPrefix(n: Int): List<Int> = heights.take(n)

    fun init(count: Int) {
        pageDividerPx = 5
        heights = IntArray(count.coerceAtLeast(0))
    }

    /** 旋转后清零已记录高度，强制按新 contentWidth 重算，避免旧宽高比串台 */
    fun clearHeights() {
        heights.fill(0)
    }

    /**
     * 写入页高（含分隔线）。
     * @return Pair(oldHeight, newHeight)；未变化时 new==old 且可能 old 已是目标值
     */
    fun putHeight(pageIndex: Int, heightWithDivider: Int): Pair<Int, Int>? {
        if (pageIndex !in heights.indices || heightWithDivider <= 0) return null
        val oldH = heights[pageIndex]
        if (oldH == heightWithDivider) return oldH to heightWithDivider
        heights[pageIndex] = heightWithDivider
        return oldH to heightWithDivider
    }

    fun computeHeightWithDivider(
        pageIndex: Int,
        pageCount: Int,
        displayHeightPx: Int,
    ): Int {
        val withDiv = displayHeightPx + if (pageIndex < pageCount - 1) pageDividerPx else 0
        return withDiv.coerceAtLeast(0)
    }

    fun averageKnownItemHeight(contentWidthPx: Int): Int {
        var sum = 0
        var n = 0
        for (h in heights) {
            if (h > 0) {
                sum += h
                n++
            }
        }
        if (n > 0) return (sum / n).coerceAtLeast(1)
        val tw = contentWidthPx.coerceAtLeast(1)
        return (tw * estimatedPageAspect).toInt().coerceAtLeast(200) + pageDividerPx
    }

    fun itemHeightAt(index: Int, contentWidthPx: Int): Int {
        if (index !in heights.indices) return averageKnownItemHeight(contentWidthPx)
        val h = heights[index]
        return if (h > 0) h else averageKnownItemHeight(contentWidthPx)
    }

    fun totalContentHeightPx(pageCount: Int, contentWidthPx: Int): Long {
        if (pageCount <= 0) return 0L
        var sum = 0L
        for (i in 0 until pageCount) sum += itemHeightAt(i, contentWidthPx)
        return sum
    }

    fun scrollOffsetForPageTop(page: Int, pageCount: Int, contentWidthPx: Int): Int {
        var acc = 0
        val p = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        for (i in 0 until p) acc += itemHeightAt(i, contentWidthPx)
        return acc
    }

    fun scrollOffsetFitsPage(
        page: Int,
        scrollY: Int,
        pageCount: Int,
        contentWidthPx: Int,
    ): Boolean {
        if (scrollY <= 0) return true
        val p = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val top = scrollOffsetForPageTop(p, pageCount, contentWidthPx)
        val bottom = top + itemHeightAt(p, contentWidthPx)
        return scrollY in top..bottom
    }

    /**
     * 进度 0..1 → 目标页 + 页内 offset（页高表坐标）。
     * [extent] = 视口高度；[total] = 内容总高。
     */
    fun seekTargetByProgress(
        progress: Float,
        pageCount: Int,
        contentWidthPx: Int,
        extent: Long,
    ): SeekTarget? {
        if (pageCount <= 0) return null
        val total = totalContentHeightPx(pageCount, contentWidthPx)
        val scrollable = (total - extent.coerceAtLeast(1L)).coerceAtLeast(0L)
        val targetY = if (scrollable <= 0L) {
            0L
        } else {
            (scrollable.toDouble() * progress.coerceIn(0f, 1f).toDouble()).toLong()
                .coerceIn(0L, scrollable)
        }
        return seekTargetByY(targetY, pageCount, contentWidthPx)
    }

    fun seekTargetByScrollY(
        scrollY: Int,
        pageCount: Int,
        contentWidthPx: Int,
        extent: Int,
        fallbackPage: Int,
    ): SeekTarget {
        val p = fallbackPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (scrollY <= 0) return SeekTarget(p, 0)
        val total = totalContentHeightPx(pageCount, contentWidthPx)
        val scrollable = (total - extent.coerceAtLeast(1)).coerceAtLeast(1)
        val targetY = scrollY.coerceIn(0, scrollable.toInt()).toLong()
        return seekTargetByY(targetY, pageCount, contentWidthPx)
    }

    private fun seekTargetByY(
        targetY: Long,
        pageCount: Int,
        contentWidthPx: Int,
    ): SeekTarget {
        var acc = 0L
        var page = 0
        while (page < pageCount - 1) {
            val h = itemHeightAt(page, contentWidthPx).toLong()
            if (acc + h > targetY) break
            acc += h
            page++
        }
        val offsetInPage = (targetY - acc).toInt().coerceAtLeast(0)
        return SeekTarget(page, offsetInPage)
    }

    /**
     * 连续模式：首可见项 + item.top → 页高表绝对 scrollY。
     */
    fun heightTableScrollY(
        firstVisible: Int,
        firstChildTop: Int?,
        contentWidthPx: Int,
    ): Int {
        if (firstVisible < 0) return 0
        var y = 0L
        for (i in 0 until firstVisible) y += itemHeightAt(i, contentWidthPx)
        if (firstChildTop != null) {
            y += (-firstChildTop).coerceAtLeast(0).toLong()
        }
        return y.toInt().coerceAtLeast(0)
    }
}
