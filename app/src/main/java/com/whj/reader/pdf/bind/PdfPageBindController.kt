package com.whj.reader.pdf.bind

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.PdfReadingActivity
import com.whj.reader.R
import com.whj.reader.databinding.ActivityPdfReadingBinding
import com.whj.reader.model.PdfPageMode
import com.whj.reader.pdf.coord.PdfViewMapper
import com.whj.reader.pdf.render.PdfLayoutMetrics
import com.whj.reader.pdf.render.PdfRenderConfig
import com.whj.reader.pdf.render.PdfRenderTask
import com.whj.reader.pdf.render.PdfUiAttach
import com.whj.reader.ui.PdfPageSurface
import com.whj.reader.util.ReaderLog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 连续模式页表面绑定、tile 补渲/可见区刷新、绑定日志。
 * 直接持有 [PdfReadingActivity] 引用。
 */
class PdfPageBindController(
    private val activity: PdfReadingActivity,
) {
    private val ctx: Context get() = activity
    private val b: ActivityPdfReadingBinding get() = activity.binding
    private val scope get() = activity.lifecycleScope

    fun bindPageSurface(index: Int, surface: PdfPageSurface, targetWidth: Int) {
        val r = activity.renderer ?: return
        if (index !in 0 until r.pageCount) return
        val tw = targetWidth.coerceAtLeast(1)
            .coerceAtMost(activity.pdfMaxRenderWidth())
        val curW = surface.width.takeIf { it > 0 }
        val (pw, ph) = activity.pageSizeForBind(index)
        val margins = activity.cropForPage(index)
        val expectedH = activity.logicalDisplayHeight(pw, ph, margins, tw)
        // 宽对且有内容，但高度与当前宽度宽高比差很多 → 旋转后串台，必须重 bind
        val heightOk = abs(surface.logicalHeight - expectedH) <= max(4, expectedH / 50)
        if (surface.pageIndex == index && curW == tw && !surface.needsContent() && heightOk) {
            logPdfZoom(
                "bind skip page=$index mode=${surface.debugModeLabel()} " +
                    "tiles=${surface.installedTileCount()}/${surface.tileCount} " +
                    "h=${surface.height}",
                force = true,
            )
            surface.setNightMode(activity.night)
            surface.setPageBackground(if (activity.night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            wireSurfaceGeometryCallback(surface)
            return
        }
        logPdfZoom(
            "bind clear page=$index was=${surface.pageIndex} mode=${surface.debugModeLabel()} " +
                "tiles=${surface.installedTileCount()} tw=$tw curW=$curW " +
                "h=${surface.logicalHeight} expH=$expectedH",
            force = true,
        )
        val tall = activity.isTallPage(pw, ph, margins, tw)
        val tileH = tileHeightForDevice()
        // 固定列表项高度表，供手柄定位
        activity.recordPageItemHeight(index, pw, ph)
        for (b in surface.drainTiles()) activity.unpinTileBitmap(b)
        surface.drainFullBitmap()
        surface.bind(
            pageIndex = index,
            pageW = pw,
            pageH = ph,
            cropL = margins[0],
            cropT = margins[1],
            cropR = margins[2],
            cropB = margins[3],
            targetWidth = tw,
            tileHeightPx = tileH,
            useTiles = tall,
        )
        surface.setNightMode(activity.night)
        surface.setPageBackground(if (activity.night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        surface.onNeedTile = { pageIdx, surf, tileIdx, topPx, bottomPx, width, bindGen ->
            enqueueTileRender(pageIdx, surf, tileIdx, topPx, bottomPx, width, bindGen)
        }
        wireSurfaceGeometryCallback(surface)

        if (tall) {
            hydrateTilesFromCache(surface, index, tw)
            val displayH = activity.logicalDisplayHeight(pw, ph, margins, tw)
            val pref = if (activity.preferPreviewQuality()) 1 else PdfRenderConfig.TILE_PREFETCH
            ensureTallPageTilesForItem(surface, displayH, tw, pref)
            return
        }

        val cached = activity.pdfRenderCache.bitmapCache.get(index)
        val gen = surface.bindGeneration
        if (cached != null && !cached.isRecycled && isBitmapAspectUsable(cached, expectedH, tw)) {
            // 绝不在 onBind 同步 setFullBitmap（会卡 RV 布局 ~300ms）→ 帧回调贴
            activity.enqueueUiAttach(
                PdfUiAttach(surface, index, gen, cached, isTile = false),
            )
            if (activity.preferPreviewQuality() || isBitmapFullQuality(cached, tw)) {
                return
            }
            // 已是预览：继续排队升清
        }
        enqueueFullPageRender(index, surface, tw, gen)
    }

    fun isBitmapAspectUsable(bmp: Bitmap, expectedH: Int, targetWidth: Int): Boolean {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return false
        val tw = targetWidth.coerceAtLeast(1).toFloat()
        val eh = expectedH.coerceAtLeast(1).toFloat()
        val bmpAspect = bmp.height.toFloat() / bmp.width.toFloat()
        val expAspect = eh / tw
        return abs(bmpAspect - expAspect) / expAspect.coerceAtLeast(0.01f) < 0.08f
    }

    fun wireSurfaceGeometryCallback(surface: PdfPageSurface) {
        surface.onGeometryInvalidated = fun(surf: PdfPageSurface) {
            if (activity.isFinishing || activity.isDestroyed) return
            if (activity.pageMode != PdfPageMode.CONTINUOUS) return
            val page = surf.pageIndex
            if (page < 0) return
            val tw = surf.width.takeIf { it > 0 }
                ?: b.rvPdfPages.width.takeIf { it > 0 }
                ?: activity.pdfViewportWidth()
            val (pw, ph) = activity.pageSizeForBind(page)
            activity.recordPageItemHeight(page, pw, ph)
            when {
                surf.needsContent() -> {
                    // 分块几何变了或内容被清：按新宽度 rebind
                    bindPageSurface(page, surf, tw)
                }
                surf.isTileMode -> {
                    // 高度校正后补可见 tile（不必整页 drain rebind）
                    val displayH = surf.logicalHeight.coerceAtLeast(1)
                    ensureTallPageTilesForItem(
                        surf,
                        displayH,
                        tw,
                        if (activity.preferPreviewQuality()) 1 else PdfRenderConfig.TILE_PREFETCH,
                    )
                }
                surf.isFullMode -> {
                    val cached = activity.pdfRenderCache.bitmapCache.get(page)
                    if (cached == null || cached.isRecycled ||
                        !isBitmapAspectUsable(cached, surf.logicalHeight, tw)
                    ) {
                        enqueueFullPageRender(page, surf, tw, surf.bindGeneration)
                    } else if (!isBitmapFullQuality(cached, tw)) {
                        enqueueFullPageRender(page, surf, tw, surf.bindGeneration)
                    }
                }
            }
        }
    }

    fun enqueueFullPageRender(
        pageIndex: Int,
        surface: PdfPageSurface,
        targetWidth: Int,
        bindGen: Long,
    ) = activity.pdfRenderPipeline.enqueueFullPage(pageIndex, surface, targetWidth, bindGen)

    fun isBitmapFullQuality(bmp: Bitmap, targetWidth: Int): Boolean =
        activity.pdfRenderCache.isBitmapFullQuality(bmp, targetWidth)

    internal fun findSurfaceForPage(page: Int): PdfPageSurface? {
        if (activity.pageMode == PdfPageMode.SINGLE) {
            val s = activity.singlePageSurface
            return if (activity.singlePageUsesTiles && s != null && s.pageIndex == page) s else null
        }
        if (activity.pageMode != PdfPageMode.CONTINUOUS) return null
        val lm = b.rvPdfPages.layoutManager as? LinearLayoutManager ?: return null
        val child = lm.findViewByPosition(page) ?: return null
        val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: return null
        return if (surface.pageIndex == page) surface else null
    }

    fun hydrateTilesFromCache(surface: PdfPageSurface, pageIndex: Int, targetWidth: Int) {
        activity.pdfRenderCache.hydrateTilesFromCache(surface, pageIndex, targetWidth)
    }

    fun ensureTallPageTilesForItem(
        surface: PdfPageSurface,
        displayH: Int,
        tw: Int,
        prefetch: Int,
    ) {
        val apply = {
            val item = (surface.parent as? View) ?: surface
            val vh = b.rvPdfPages.height.takeIf { it > 0 }
                ?: (activity.resources.displayMetrics.heightPixels * 0.85f).toInt()
            val band = pageVisibleBandInRv(item, vh, displayH)
                ?: (0 to vh.coerceAtMost(displayH))
            surface.ensureTilesForVisible(band.first, band.second, tw, prefetch)
        }
        if (b.rvPdfPages.height > 0 && surface.height > 0) {
            apply()
        } else {
            b.rvPdfPages.post { apply() }
        }
    }

    fun pageVisibleBandInRv(child: View, viewportH: Int, pageH: Int): Pair<Int, Int>? =
        PdfViewMapper.pageVisibleBandInRv(child.top, child.bottom, viewportH, pageH)

    internal fun scheduleContinuousTileRefresh(
        forceRender: Boolean = true,
        afterLayout: Boolean = false,
        reason: String = "",
    ) {
        if (activity.pageMode != PdfPageMode.CONTINUOUS) return
        logPdfZoom(
            "scheduleRefresh reason=$reason force=$forceRender afterLayout=$afterLayout " +
                "z=${b.pdfContainer.contentZoom} pinching=${b.pdfContainer.isPinching()}",
            force = reason.isNotEmpty(),
        )
        activity.pendingContinuousTileRefresh?.let { b.rvPdfPages.removeCallbacks(it) }
        val r = Runnable {
            activity.pendingContinuousTileRefresh = null
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            val run = Runnable { refreshVisiblePageTiles(forceRender = forceRender) }
            if (afterLayout) {
                b.rvPdfPages.post { b.rvPdfPages.post(run) }
            } else {
                b.rvPdfPages.post(run)
            }
        }
        activity.pendingContinuousTileRefresh = r
        b.rvPdfPages.post(r)
    }

    internal fun refreshVisiblePageTiles(forceRender: Boolean = true) {
        if (activity.pageMode != PdfPageMode.CONTINUOUS) return
        val rv = b.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        activity.pdfRenderScheduler.visFirst = first
        activity.pdfRenderScheduler.visLast = last.coerceAtLeast(first)
        val viewportH = rv.height.coerceAtLeast(1)
        val scrolling = activity.preferPreviewQuality()
        val prefetch = if (scrolling) 1 else PdfRenderConfig.TILE_PREFETCH
        // 缩放态不摘 tile，避免捏合/松手布局突变时误删可见块
        val allowDropTiles = !b.pdfContainer.isPinching() &&
            abs(b.pdfContainer.contentZoom - 1f) < 0.02f
        val z = b.pdfContainer.contentZoom
        val rvLpH = rv.layoutParams?.height ?: -1
        // 可见 + 下方 1 页（惯性下滑时提前渲）
        val end = (last + if (scrolling) 1 else 0).coerceAtMost((activity.pageCount - 1).coerceAtLeast(0))
        logPdfZoom(
            "refresh first=$first last=$last end=$end viewportH=$viewportH rvLpH=$rvLpH " +
                "z=$z pinching=${b.pdfContainer.isPinching()} drop=$allowDropTiles scroll=$scrolling",
        )
        for (pos in first..end) {
            val child = lm.findViewByPosition(pos)
            val surface = if (child != null) {
                child.findViewById<PdfPageSurface>(R.id.ivPage)
            } else {
                null
            }
            // 已 bind 的可见页
            if (surface != null && surface.pageIndex == pos) {
                val tw = surface.width.takeIf { it > 0 }
                    ?: rv.width.takeIf { it > 0 }
                    ?: activity.resources.displayMetrics.widthPixels
                // 白页恢复：有 cache 立刻贴；无 cache 强制再入队（防渲染被取消后卡住）
                if (surface.needsContent()) {
                    logPdfZoom(
                        "page=$pos NEED childTop=${child?.top} childBot=${child?.bottom} " +
                            "mode=${surface.debugModeLabel()} tiles=${surface.installedTileCount()}/" +
                            "${surface.tileCount}",
                    )
                    val cached = activity.pdfRenderCache.bitmapCache.get(pos)
                    val expH = surface.logicalHeight.coerceAtLeast(1)
                    if (cached != null && !cached.isRecycled &&
                        !surface.isTileMode && surface.tileCount <= 0 &&
                        isBitmapAspectUsable(cached, expH, tw)
                    ) {
                        activity.enqueueUiAttach(
                            PdfUiAttach(surface, pos, surface.bindGeneration, cached, false),
                        )
                        if (forceRender && !scrolling && !isBitmapFullQuality(cached, tw)) {
                            enqueueFullPageRender(pos, surface, tw, surface.bindGeneration)
                        }
                    } else if (surface.isTileMode || surface.tileCount > 0) {
                        hydrateTilesFromCache(surface, pos, tw)
                        val pageH = surface.height.coerceAtLeast(surface.logicalHeight).coerceAtLeast(1)
                        val band = pageVisibleBandInRv(child!!, viewportH, pageH) ?: continue
                        if (forceRender) {
                            surface.ensureTilesForVisible(band.first, band.second, tw, prefetch)
                        }
                    } else if (forceRender) {
                        enqueueFullPageRender(pos, surface, tw, surface.bindGeneration)
                    }
                    continue
                }
                if (surface.isFullMode) {
                    val c = child!!
                    logPdfZoom(
                        "page=$pos FULL childTop=${c.top} childBot=${c.bottom} " +
                            "h=${surface.height} need=${surface.needsContent()}",
                    )
                    if (forceRender && !scrolling) {
                        val cached = activity.pdfRenderCache.bitmapCache.get(pos)
                        if (cached != null && !cached.isRecycled &&
                            !isBitmapFullQuality(cached, tw)
                        ) {
                            enqueueFullPageRender(pos, surface, tw, surface.bindGeneration)
                        }
                    }
                    continue
                }
                if (!surface.isTileMode && surface.tileCount <= 0) continue
                val pageH = surface.height.coerceAtLeast(surface.logicalHeight).coerceAtLeast(1)
                val band = pageVisibleBandInRv(child!!, viewportH, pageH) ?: continue
                hydrateTilesFromCache(surface, pos, tw)
                if (forceRender) {
                    surface.ensureTilesForVisible(band.first, band.second, tw, prefetch)
                }
                var dropped = 0
                if (allowDropTiles) {
                    for (b in surface.dropTilesOutside(band.first, band.second, prefetch)) {
                        activity.unpinTileBitmap(b)
                        dropped++
                    }
                }
                logPdfZoom(
                    "page=$pos childTop=${child.top} childBot=${child.bottom} " +
                        "band=${band.first}..${band.second} pageH=$pageH " +
                        "mode=${surface.debugModeLabel()} tiles=${surface.installedTileCount()}/" +
                        "${surface.tileCount} need=${surface.needsContent()} dropped=$dropped",
                )
            } else if (forceRender && pos > last) {
                // 下方预取页尚未 bind：只确保尺寸入队，等 bind 再渲
                activity.schedulePageSizeFetch(pos)
            }
        }
    }

    fun enqueueTileRender(
        pageIndex: Int,
        surface: PdfPageSurface,
        tileIndex: Int,
        tileTopPx: Int,
        tileBottomPx: Int,
        targetWidth: Int,
        bindGen: Long,
    ) = activity.pdfRenderPipeline.enqueueTile(
        pageIndex, surface, tileIndex, tileTopPx, tileBottomPx, targetWidth, bindGen,
    )

    fun trimBitmapCacheAround(center: Int, keepRadius: Int = PdfRenderConfig.CACHE_KEEP_RADIUS) {
        activity.pdfRenderCache.trimBitmapCacheAround(center, keepRadius)
    }

    fun tileHeightForDevice(): Int =
        PdfLayoutMetrics.tileHeightForDevice(activity.resources.displayMetrics.heightPixels)

    fun prefetchPageSizesUpTo(upTo: Int) {
        if (activity.pageCount <= 0) return
        val end = upTo.coerceIn(0, activity.pageCount - 1)
        for (i in 0..end) {
            activity.ensurePageSize(i)
        }
        activity.prefetchPageSizesAround(end.coerceAtMost(activity.pageCount - 1), radius = 2)
    }

    fun logPdfOpen(msg: String, force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - activity.lastPdfOpenLogMs < 100L) return
        activity.lastPdfOpenLogMs = now
        ReaderLog.i(ReaderLog.Module.PDF_OPEN, msg)
    }

    fun logPdfOpenVisible(tag: String) {
        if (activity.pageMode != PdfPageMode.CONTINUOUS || !activity.isBindingReady()) return
        val rv = b.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        val scrollY = rv.computeVerticalScrollOffset()
        val sb = StringBuilder("$tag scrollY=$scrollY first=$first last=$last rvH=${rv.height}")
        if (first != RecyclerView.NO_POSITION) {
            for (pos in first..last.coerceAtLeast(first)) {
                val child = lm.findViewByPosition(pos) ?: continue
                val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: continue
                val tableH = activity.itemHeightAt(pos)
                sb.append(
                    " | p$pos top=${child.top} bot=${child.bottom} " +
                        "surfH=${surface.height} tableH=$tableH " +
                        "tiles=${surface.installedTileCount()}/${surface.tileCount} " +
                        "need=${surface.needsContent()}",
                )
            }
        }
        logPdfOpen(sb.toString(), force = true)
    }

    fun logPdfZoom(msg: String, force: Boolean = false) {
        val zl = b.pdfContainer
        if (!activity.isBindingReady()) return
        val scaled = zl.isScaled() || zl.isPinching()
        if (!force && !scaled) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - activity.lastPdfZoomLogMs < 80L) return
        activity.lastPdfZoomLogMs = now
        ReaderLog.i(ReaderLog.Module.PDF_ZOOM, msg)
    }
}
