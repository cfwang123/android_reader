package com.whj.reader.pdf.render

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * PDF 渲染调度：单工作线程 + 可取消近优先队列。
 * 具体 Full/Tile/PageSize 执行由 [Host.executeTask] 完成（持有 PdfRenderer 等）。
 */
class PdfRenderScheduler(
    private val host: Host,
) {
    interface Host {
        /** 当前页（0-based），作任务优先级锚点 */
        fun currentPageIndex(): Int

        /** 执行任务（worker 线程）；PageSize 完成后由宿主切主线程 */
        fun executeTask(task: PdfRenderTask)

        /** 任务结束（含取消）时清 pending 映射 */
        fun onTaskFinished(task: PdfRenderTask)
    }

    private val renderQueueLock = Object()
    private val renderQueue = ArrayList<PdfRenderTask>(48)

    @Volatile
    private var renderWorkerStop = false

    /** 当前可见页闭区间（含），供后台取消判定 */
    @Volatile
    var visFirst: Int = 0

    @Volatile
    var visLast: Int = 0

    private var executor: ExecutorService? = null

    fun start() {
        if (executor != null) return
        renderWorkerStop = false
        val ex = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pdf-tile-render").apply { isDaemon = true }
        }
        executor = ex
        ex.execute {
            while (!renderWorkerStop && !Thread.currentThread().isInterrupted) {
                val task = pollBestRenderTask() ?: continue
                if (task.cancelled || !isPageInRenderWindow(task.page)) {
                    host.onTaskFinished(task)
                    continue
                }
                try {
                    host.executeTask(task)
                } finally {
                    host.onTaskFinished(task)
                }
            }
        }
    }

    fun stop() {
        renderWorkerStop = true
        synchronized(renderQueueLock) {
            for (t in renderQueue) t.cancelled = true
            renderQueue.clear()
            renderQueueLock.notifyAll()
        }
        executor?.shutdownNow()
        executor = null
    }

    /**
     * 取消队列中尚未执行的任务（不停止 worker）。
     * 旋转后必须调用，避免旧 targetWidth 结果贴到新布局上压扁/拉长。
     */
    fun cancelAllQueued() {
        synchronized(renderQueueLock) {
            for (t in renderQueue) {
                t.cancelled = true
                host.onTaskFinished(t)
            }
            renderQueue.clear()
            renderQueueLock.notifyAll()
        }
    }

    fun offer(task: PdfRenderTask) {
        if (renderWorkerStop) return
        synchronized(renderQueueLock) {
            renderQueue.add(task)
            renderQueueLock.notify()
        }
    }

    /** 可见邻域：前后各多预渲 1～2 页 */
    fun isPageInRenderWindow(page: Int): Boolean {
        val f = visFirst
        val l = visLast
        if (l < f) {
            return kotlin.math.abs(page - host.currentPageIndex()) <=
                PdfRenderConfig.CACHE_KEEP_RADIUS
        }
        return page in (f - 1)..(l + 2)
    }

    fun updateVisibleRange(first: Int, last: Int, setPageIndex: Boolean = true): Int? {
        if (first < 0) return null
        visFirst = first
        visLast = last.coerceAtLeast(first)
        return if (setPageIndex && first >= 0) first else null
    }

    private fun pollBestRenderTask(): PdfRenderTask? {
        synchronized(renderQueueLock) {
            while (!renderWorkerStop) {
                val it = renderQueue.iterator()
                while (it.hasNext()) {
                    val t = it.next()
                    if (t.cancelled || !isPageInRenderWindow(t.page)) {
                        t.cancelled = true
                        it.remove()
                        host.onTaskFinished(t)
                    }
                }
                if (renderQueue.isEmpty()) {
                    try {
                        renderQueueLock.wait(500)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                    continue
                }
                val anchor = host.currentPageIndex()
                var bestIdx = 0
                var bestScore = Int.MAX_VALUE
                for (i in renderQueue.indices) {
                    val t = renderQueue[i]
                    val score = kotlin.math.abs(t.page - anchor) * 10 + t.kind
                    if (score < bestScore) {
                        bestScore = score
                        bestIdx = i
                    }
                }
                return renderQueue.removeAt(bestIdx)
            }
        }
        return null
    }
}
