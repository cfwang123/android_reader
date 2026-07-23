package com.whj.reader.pdf.render

import com.whj.reader.ui.PdfPageSurface

/**
 * PDF 渲染调度任务：单工作线程 + 可取消优先队列。
 * - 滑动中也渲染可见页
 * - 离开可见邻域的任务在开工前丢弃
 * - 始终先做离当前页最近的任务（避免 FIFO 堆积导致卡顿）
 */
sealed class PdfRenderTask {
    abstract val page: Int

    /** full=0 优先于 tile=1（同距离时） */
    abstract val kind: Int

    @Volatile
    var cancelled: Boolean = false

    class Full(
        override val page: Int,
        val surface: PdfPageSurface,
        val targetWidth: Int,
        val bindGen: Long,
    ) : PdfRenderTask() {
        override val kind: Int = 0
    }

    class Tile(
        override val page: Int,
        val surface: PdfPageSurface,
        val tileIndex: Int,
        val tileTopPx: Int,
        val tileBottomPx: Int,
        val targetWidth: Int,
        val bindGen: Long,
    ) : PdfRenderTask() {
        override val kind: Int = 1
    }

    class PageSize(override val page: Int) : PdfRenderTask() {
        override val kind: Int = 2
    }
}
