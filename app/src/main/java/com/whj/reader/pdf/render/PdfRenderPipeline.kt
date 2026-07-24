package com.whj.reader.pdf.render
import com.whj.reader.PdfReadingActivity
import com.whj.reader.pdf.render.PdfLayoutMetrics

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import com.whj.reader.ui.PdfPageSurface
import com.whj.reader.util.ReaderLog

/**
 * Full / Tile 渲染执行体：入队、后台开页渲染、回主线程贴图。
 * 调度仍由 [PdfRenderScheduler]；本类只关心「怎么渲」。
 */
class PdfRenderPipeline(
    private val cache: PdfRenderCache,
    private val activity: PdfReadingActivity,
) {

    private val pendingFullPages =
        java.util.concurrent.ConcurrentHashMap<Int, PdfRenderTask.Full>()
    private val pendingTiles =
        java.util.concurrent.ConcurrentHashMap<Long, PdfRenderTask.Tile>()
    private val pendingPageSizes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>(),
    )

    fun clearPending() {
        pendingFullPages.clear()
        pendingTiles.clear()
        pendingPageSizes.clear()
    }

    /** 标记并清空所有 pending，使已在跑的任务在贴图前失效 */
    fun cancelAllPending() {
        for (t in pendingFullPages.values) t.cancelled = true
        for (t in pendingTiles.values) t.cancelled = true
        clearPending()
    }

    fun pendingPageSizes(): MutableSet<Int> = pendingPageSizes

    fun onTaskFinished(task: PdfRenderTask) {
        when (task) {
            is PdfRenderTask.Full -> pendingFullPages.remove(task.page, task)
            is PdfRenderTask.Tile -> {
                val key = activity.tileCacheKey(task.page, task.tileIndex, task.targetWidth)
                pendingTiles.remove(key, task)
            }
            is PdfRenderTask.PageSize -> pendingPageSizes.remove(task.page)
        }
    }

    fun tryAddPageSize(page: Int): Boolean = pendingPageSizes.add(page)

    fun enqueueFullPage(
        pageIndex: Int,
        surface: PdfPageSurface,
        targetWidth: Int,
        bindGen: Long,
    ) {
        if (pageIndex !in 0 until activity.pageCount) return
        val cached = cache.bitmapCache.get(pageIndex)
        if (cached != null && !cached.isRecycled) {
            val needUpgrade = !activity.preferPreviewQuality() &&
                !cache.isBitmapFullQuality(cached, targetWidth)
            if (!needUpgrade) {
                activity.enqueueUiAttach(
                    PdfUiAttach(surface, pageIndex, bindGen, cached, isTile = false),
                )
                return
            }
        }
        pendingFullPages[pageIndex]?.let { old ->
            if (!old.cancelled) old.cancelled = true
        }
        val task = PdfRenderTask.Full(pageIndex, surface, targetWidth, bindGen)
        pendingFullPages[pageIndex] = task
        activity.offerTask(task)
    }

    fun enqueueTile(
        pageIndex: Int,
        surface: PdfPageSurface,
        tileIndex: Int,
        tileTopPx: Int,
        tileBottomPx: Int,
        targetWidth: Int,
        bindGen: Long,
    ) {
        val cacheKey = activity.tileCacheKey(pageIndex, tileIndex, targetWidth)
        val cached = cache.tileCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            if (surface.pageIndex == pageIndex && surface.bindGeneration == bindGen) {
                activity.deliverTile(surface, tileIndex, cached, bindGen)
            }
            return
        }
        if (!activity.isPageInRenderWindow(pageIndex)) return
        pendingTiles[cacheKey]?.let { it.cancelled = true }
        val task = PdfRenderTask.Tile(
            page = pageIndex,
            surface = surface,
            tileIndex = tileIndex,
            tileTopPx = tileTopPx,
            tileBottomPx = tileBottomPx,
            targetWidth = targetWidth,
            bindGen = bindGen,
        )
        pendingTiles[cacheKey] = task
        activity.offerTask(task)
    }

    fun runFullPageTask(task: PdfRenderTask.Full) {
        if (task.cancelled) return
        val pageIndex = task.page
        if (!activity.isPageInRenderWindow(pageIndex) && !task.cancelled) {
            if (kotlin.math.abs(pageIndex - activity.pageIndex) >
                PdfRenderConfig.CACHE_KEEP_RADIUS + 2
            ) {
                return
            }
        }
        val wantFull = !activity.preferPreviewQuality()
        val hit = cache.bitmapCache.get(pageIndex)
        if (hit != null && !hit.isRecycled) {
            // 宽与任务目标差太多（旋转前后缓存）→ 不复用，重渲
            val widthOk = hit.width >= task.targetWidth * 0.55f &&
                hit.width <= task.targetWidth * 1.55f + 64
            if (!widthOk) {
                // fall through re-render
            } else if (wantFull && !cache.isBitmapFullQuality(hit, task.targetWidth)) {
                // 缓存是预览，继续渲高清
            } else {
                postFullBitmap(task, hit)
                return
            }
        }
        if (task.cancelled) return
        val r = activity.renderer ?: return
        if (pageIndex !in 0 until r.pageCount) return
        val renderW = if (wantFull) {
            task.targetWidth
        } else {
            (task.targetWidth * PdfRenderConfig.PREVIEW_WIDTH_FACTOR).toInt()
                .coerceIn(320, task.targetWidth)
        }
        val bmp = try {
            synchronized(activity.renderLock) {
                if (task.cancelled) return
                activity.currentPage?.close()
                activity.currentPage = null
                val page = r.openPage(pageIndex)
                activity.currentPage = page
                try {
                    activity.renderPageBitmap(page, renderW, pageIndexForMirror = pageIndex)
                } finally {
                    page.close()
                    activity.currentPage = null
                }
            }
        } catch (t: Throwable) {
            ReaderLog.e(ReaderLog.Module.PDF, "full page render p=$pageIndex", t)
            return
        }
        if (bmp.isRecycled) return
        // 旋转取消后的结果不得写入缓存，否则宽图/窄图串台导致后续页被错误复用
        if (task.cancelled) return
        val old = cache.bitmapCache.get(pageIndex)
        if (old == null || old.isRecycled || bmp.width >= (old.width * 0.9f)) {
            cache.bitmapCache.put(pageIndex, bmp)
        }
        postFullBitmap(task, bmp)
    }

    fun runTileTask(task: PdfRenderTask.Tile) {
        if (task.cancelled) return
        val cacheKey = activity.tileCacheKey(task.page, task.tileIndex, task.targetWidth)
        val hit = cache.tileCache.get(cacheKey)
        if (hit != null && !hit.isRecycled) {
            postTile(task, hit)
            return
        }
        if (task.cancelled) return
        if (!activity.isPageInRenderWindow(task.page) &&
            kotlin.math.abs(task.page - activity.pageIndex) >
            PdfRenderConfig.CACHE_KEEP_RADIUS + 2
        ) {
            return
        }
        val r = activity.renderer ?: return
        if (task.page !in 0 until r.pageCount) return
        val (pw, ph) = try {
            activity.ensurePageSize(task.page)
        } catch (t: Throwable) {
            ReaderLog.e(ReaderLog.Module.PDF, "tile ensurePageSize p=${task.page}", t)
            return
        }
        if (task.cancelled) return
        val margins = activity.cropForPage(task.page)
        val displayH = PdfLayoutMetrics.logicalDisplayHeight(pw, ph, margins, task.targetWidth)
            .toFloat().coerceAtLeast(1f)
        val srcH = ph * (1f - margins[1] - margins[3]).coerceAtLeast(0.2f)
        val srcY0 = (task.tileTopPx / displayH) * srcH
        val srcY1 = (task.tileBottomPx / displayH) * srcH
        val bmp = try {
            synchronized(activity.renderLock) {
                if (task.cancelled) return
                activity.currentPage?.close()
                activity.currentPage = null
                val page = r.openPage(task.page)
                activity.currentPage = page
                try {
                    activity.renderPageStripBitmap(page, task.targetWidth, srcY0, srcY1, pageIndexForMirror = task.page)
                } finally {
                    page.close()
                    activity.currentPage = null
                }
            }
        } catch (t: Throwable) {
            ReaderLog.e(
                ReaderLog.Module.PDF,
                "tile render p=${task.page} t=${task.tileIndex}",
                t,
            )
            return
        }
        if (bmp.isRecycled) return
        if (task.cancelled) return
        cache.tileCache.put(cacheKey, bmp)
        activity.pinTileBitmap(bmp)
        postTile(task, bmp)
    }

    private fun postFullBitmap(task: PdfRenderTask.Full, bmp: Bitmap) {
        if (bmp.isRecycled) return
        activity.runOnUiThread {
            if (!activity.isAlive() || bmp.isRecycled || task.cancelled) return@runOnUiThread
            // 只贴到发起任务时的 bindGen。旋转/ rebind 后禁止「找新 Surface 硬贴」，
            // 否则旧 targetWidth 位图会画进新宽高比容器 → 压扁/拉长。
            val surf = task.surface
            if (surf.pageIndex != task.page || surf.bindGeneration != task.bindGen) {
                return@runOnUiThread
            }
            if (!surfaceWidthMatchesTask(surf, task.targetWidth)) return@runOnUiThread
            activity.enqueueUiAttach(
                PdfUiAttach(
                    surface = surf,
                    page = task.page,
                    bindGen = task.bindGen,
                    bmp = bmp,
                    isTile = false,
                ),
            )
        }
    }

    private fun postTile(task: PdfRenderTask.Tile, bmp: Bitmap) {
        if (bmp.isRecycled) {
            activity.unpinTileBitmap(bmp)
            return
        }
        activity.runOnUiThread {
            if (!activity.isAlive() || bmp.isRecycled || task.cancelled) {
                activity.unpinTileBitmap(bmp)
                return@runOnUiThread
            }
            val surf = task.surface
            if (surf.pageIndex != task.page || surf.bindGeneration != task.bindGen) {
                activity.unpinTileBitmap(bmp)
                return@runOnUiThread
            }
            if (!surfaceWidthMatchesTask(surf, task.targetWidth)) {
                activity.unpinTileBitmap(bmp)
                return@runOnUiThread
            }
            activity.enqueueUiAttach(
                PdfUiAttach(
                    surface = surf,
                    page = task.page,
                    bindGen = task.bindGen,
                    bmp = bmp,
                    isTile = true,
                    tileIndex = task.tileIndex,
                ),
            )
        }
    }

    /** Surface 已 layout 的宽度与任务 targetWidth 偏差过大则拒绝贴图 */
    private fun surfaceWidthMatchesTask(surface: PdfPageSurface, targetWidth: Int): Boolean {
        val sw = surface.width
        if (sw <= 0) return true // 尚未 layout，交给后续 onSizeChanged / 再 bind
        val tw = targetWidth.coerceAtLeast(1)
        val tol = maxOf(32, tw / 10)
        return kotlin.math.abs(sw - tw) <= tol
    }
}
