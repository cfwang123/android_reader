package com.whj.reader.pdf.mode

import com.whj.reader.PdfReadingActivity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.R
import com.whj.reader.data.AppSettings
import com.whj.reader.databinding.ActivityPdfReadingBinding
import com.whj.reader.pdf.layout.PdfPageHeightTable
import com.whj.reader.model.PdfPageMode
import com.whj.reader.pdf.render.PdfBitmapRenderer
import com.whj.reader.pdf.render.PdfRenderCache
import com.whj.reader.pdf.render.PdfRenderConfig
import com.whj.reader.pdf.render.PdfRenderPipeline
import com.whj.reader.pdf.render.PdfRenderTask
import com.whj.reader.pdf.render.PdfUiAttach
import com.whj.reader.pdf.render.PdfUiAttachQueue
import com.whj.reader.ui.PdfPageAdapter
import com.whj.reader.ui.PdfPageSurface
import com.whj.reader.util.ReaderLog
import com.whj.reader.util.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 模式控制器：单页/连续切换、PinchZoom 配置、单页渲染（含长图分块 tile）、
 * 旋转后重铺（relayoutAfterOrientationChange）。
 */
class PdfModeController(
    private val activity: PdfReadingActivity,
) {

    private data class SinglePageRenderResult(
        val index: Int,
        val bitmap: Bitmap,
        val fitByWidth: Boolean,
    )

    private val ctx: Context get() = activity
    private val b: ActivityPdfReadingBinding get() = activity.binding
    private val scope: CoroutineScope get() = activity.lifecycleScope
    private val renderCache: PdfRenderCache get() = activity.pdfRenderCache

    fun setupPinchZoom() {
        val zoomLayout = b.pdfContainer
        // 支持缩小到 25%（与 MOBI 连续图一致）
        zoomLayout.minZoom = 0.25f
        zoomLayout.maxZoom = 3.5f
        activity.rebindZoomTarget()
        // 缩放保留在 transform 上，支持平移；不重绘 bitmap
        zoomLayout.onZoomChanged = {
            activity.updatePdfZoomChrome()
            activity.clearTextSelection()
            // TTS 高亮随缩放更新屏幕位置
            if (activity.hasTtsHighlight()) activity.refreshHighlightOverlay()
            activity.refreshSelectionOverlay()
            // 页码角标反缩放，视觉大小不随 zoom 变
            updatePageBadgeZoomCompensation()
            // 缩小后列表视口变高，补拉可见/预取 tile
            if (activity.pageMode == PdfPageMode.CONTINUOUS) {
                activity.scheduleContinuousTileRefresh(
                    forceRender = true,
                    afterLayout = true,
                    reason = "onZoomChanged",
                )
            }
            // 缩放到文件记录（debounce 用 post，避免捏合过程狂写）
            if (activity.allowProgressSave && activity.fileKey.isNotEmpty()) {
                b.pdfContainer.removeCallbacks(activity.saveZoomRunnable)
                b.pdfContainer.postDelayed(activity.saveZoomRunnable, 280L)
            }
        }
        // 平移/缩放时：关菜单 + 刷新高亮位置；捏合过程中也要即时切换黑底
        zoomLayout.onTransformChanged = {
            activity.updatePdfZoomChrome()
            if (activity.chromeVisible &&
                (zoomLayout.isScaled() || zoomLayout.getPanX() != 0f || zoomLayout.getPanY() != 0f)
            ) {
                activity.hideChrome()
            }
            if (activity.hasTtsHighlight()) activity.refreshHighlightOverlay()
            if (activity.hasTextSelection()) activity.refreshSelectionOverlay()
            updatePageBadgeZoomCompensation()
            if (activity.pageMode == PdfPageMode.CONTINUOUS) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - activity.lastTileRefreshMs >= activity.tileRefreshMinIntervalMs) {
                    activity.lastTileRefreshMs = now
                    activity.scheduleContinuousTileRefresh(
                        forceRender = true,
                        afterLayout = zoomLayout.isPinching(),
                        reason = "onTransformChanged",
                    )
                }
            } else if (activity.pageMode == PdfPageMode.SINGLE) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - activity.lastProgressUiMs >= activity.progressUiMinIntervalMs) {
                    activity.lastProgressUiMs = now
                    activity.updateProgressLabelLight()
                }
                if (activity.singlePageUsesTiles) {
                    if (now - activity.lastTileRefreshMs >= activity.tileRefreshMinIntervalMs) {
                        activity.lastTileRefreshMs = now
                        activity.refreshSinglePageTiles(forceRender = true)
                    }
                }
            }
        }
        // 侧边立即翻页（无双击等待）
        zoomLayout.onSideTapImmediate = side@{ zone, x, y ->
            val gestureDown = zoomLayout.sideTapGestureDownTime
            if (gestureDown == activity.handledSideTapDownTime) {
                ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN,
                    "sideTap dup downTime=$gestureDown zone=$zone page=${activity.pageIndex}",
                )
                return@side
            }
            activity.handledSideTapDownTime = gestureDown
            ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                "sideTap zone=$zone x=$x y=$y downTime=$gestureDown mode=${activity.pageMode} " +
                    "chrome=${activity.chromeVisible} sel=${activity.hasTextSelection()} " +
                    "panel=${b.settingsPanelContainer.isVisible} " +
                    "page=${activity.pageIndex}/${activity.pageCount.coerceAtLeast(1)}",
            )
            if (b.settingsPanelContainer.isVisible) {
                b.settingsPanelContainer.isVisible = false
                return@side
            }
            if (activity.hasTextSelection()) {
                activity.clearTextSelection()
                return@side
            }
            // 菜单打开时只关菜单，不翻页
            if (activity.chromeVisible) {
                activity.hideChrome()
                return@side
            }
            pageTurn(forward = zone == 2, source = "sideTap")
        }
        // 左右滑翻页：左滑下一页，右滑上一页（单页 / 连续均可用）
        zoomLayout.onHorizontalSwipe = swipe@{ forward ->
            ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                "hSwipe forward=$forward mode=${activity.pageMode} chrome=${activity.chromeVisible}",
            )
            if (b.settingsPanelContainer.isVisible) {
                b.settingsPanelContainer.isVisible = false
                return@swipe
            }
            if (activity.hasTextSelection()) {
                activity.clearTextSelection()
                return@swipe
            }
            if (activity.chromeVisible) {
                activity.hideChrome()
                return@swipe
            }
            pageTurn(forward = forward, source = "hSwipe")
        }
        // 中部轻点：有选区则取消 → 链接 → 菜单 / 关面板
        zoomLayout.onSingleTap = tap@{ x, y ->
            if (activity.hasTextSelection()) {
                activity.clearTextSelection()
                return@tap
            }
            if (b.settingsPanelContainer.isVisible) {
                b.settingsPanelContainer.isVisible = false
            } else if (!activity.tryHandlePdfLinkTap(x, y)) {
                activity.handleTap(x, zoomLayout.width.toFloat().coerceAtLeast(1f))
            }
        }
        zoomLayout.onLongPress = { x, y -> activity.beginTextSelection(x, y) }
        zoomLayout.onSelectionDrag = { x, y, ended ->
            activity.textSelCtrl.dragX = x
            activity.textSelCtrl.dragY = y
            activity.extendTextSelection(x, y)
            if (!ended) {
                activity.textSelCtrl.markDragActive(true)
                activity.autoScrollPdfWhileSelecting(y)
                activity.ensurePdfSelectionEdgeScrollLoop()
            } else {
                activity.stopSelectionEdgeScroll("selectionDragEnd")
                activity.showTextActionMode()
            }
        }
        b.pdfSelectionOverlay.onHandleDrag = { which, x, y, ended ->
            activity.textSelCtrl.draggingHandle = if (ended) null else which
            activity.textSelCtrl.dragX = x
            activity.textSelCtrl.dragY = y
            activity.adjustPdfSelectionHandle(which, x, y)
            if (!ended) {
                activity.textSelCtrl.markDragActive(true)
                activity.autoScrollPdfWhileSelecting(y)
                activity.ensurePdfSelectionEdgeScrollLoop()
            } else {
                activity.stopSelectionEdgeScroll("handleDragEnd")
                activity.invalidateTextSelectionActionMode()
            }
        }

        // 连续模式缩放后竖滑 → 滚列表，从而可滑到下面页
        zoomLayout.onPanOverscroll = overscroll@{ _, overY ->
            if (activity.pageMode != PdfPageMode.CONTINUOUS) return@overscroll
            if (activity.chromeVisible) activity.hideChrome()
            val z = zoomLayout.contentZoom.coerceAtLeast(0.01f)
            // 屏幕位移 overY；RV 被 scale 后 scrollBy(s) 视觉位移约 s*z
            // 手指上滑 overY<0 → 看下方内容 → scroll 正方向
            val dy = (-overY / z).toInt()
            if (dy != 0) {
                b.rvPdfPages.scrollBy(0, dy)
                activity.updateProgressLabel()
                if (activity.hasTtsHighlight()) activity.refreshHighlightOverlay()
                if (activity.hasTextSelection()) activity.refreshSelectionOverlay()
            }
        }
        // 缩放后松手：列表 fling 惯性（与未缩放时一致）
        zoomLayout.onFlingScroll = fling@{ _, velocityY ->
            if (activity.pageMode != PdfPageMode.CONTINUOUS) return@fling
            if (!zoomLayout.isZoomed()) return@fling
            val z = zoomLayout.contentZoom.coerceAtLeast(0.01f)
            // 屏幕速度 → 内容速度；手指上滑 vy<0 → fling 向下（正）
            val vy = (-velocityY / z).toInt()
            if (vy != 0) {
                b.rvPdfPages.fling(0, vy)
            }
        }
        zoomLayout.onStopScroll = {
            b.rvPdfPages.stopScroll()
        }
    }

    internal fun showSinglePage(index: Int, tallPanSnap: PdfReadingActivity.TallPanSnap = PdfReadingActivity.TallPanSnap.PRESERVE) {
        val r = activity.renderer ?: return
        if (r.pageCount <= 0) return
        val i = index.coerceIn(0, r.pageCount - 1)

        if (activity.singlePageRendering) {
            activity.pendingSinglePage = i to tallPanSnap
            ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
                "showSinglePage coalesce page=$i snap=$tallPanSnap",
            )
            return
        }

        val container = b.pdfContainer
        if (container.width <= 0 || container.height <= 0) {
            ReaderLog.w(ReaderLog.Module.PDF_ORIENT,
                "showSinglePage defer page=$i container=${container.width}x${container.height}",
            )
            container.post { if (!activity.isFinishing && !activity.isDestroyed) showSinglePage(i, tallPanSnap) }
            return
        }

        val maxW = container.width.coerceAtLeast(1)
        val maxH = container.height.coerceAtLeast(1)
        activity.lastRenderW = maxW
        activity.lastRenderH = maxH
        val tw = maxW.coerceAtMost(activity.pdfMaxRenderWidth())
        activity.schedulePageSizeFetch(i)
        val (pw, ph) = activity.pageSizeForBind(i)
        val margins = activity.cropForPage(i)
        if (activity.isTallPage(pw, ph, margins, tw)) {
            activity.bindSinglePageTiled(i, tallPanSnap, tw)
            activity.prefetchPageSizesAround(i, radius = 3)
            return
        }

        activity.hideSinglePageSurface()
        activity.rebindZoomTarget()
        activity.singlePageRendering = true
        val gen = ++activity.singlePageRenderGen

        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    synchronized(activity.renderLock) {
                        activity.currentPage?.close()
                        activity.currentPage = null
                        val page = r.openPage(i)
                        val crop = activity.cropForPage(i)
                        val cl = crop[0].coerceIn(0f, 0.30f)
                        val ct = crop[1].coerceIn(0f, 0.30f)
                        val cr = crop[2].coerceIn(0f, 0.30f)
                        val cb = crop[3].coerceIn(0f, 0.30f)
                        val dw = page.width * (1f - cl - cr).coerceAtLeast(0.2f)
                        val dh = page.height * (1f - ct - cb).coerceAtLeast(0.2f)
                        val fitByWidth = activity.singlePageFitByWidth(dw, dh, maxW.toFloat(), maxH.toFloat())
                        val bmp = activity.renderPageBitmap(
                            page,
                            maxW,
                            if (fitByWidth) null else maxH,
                            pageIndexForMirror = i,
                        )
                        page.close()
                        activity.currentPage = null
                        SinglePageRenderResult(i, bmp, fitByWidth)
                    }
                }
            }
            if (activity.isFinishing || activity.isDestroyed || gen != activity.singlePageRenderGen) {
                result.getOrNull()?.bitmap?.let { bmp ->
                    if (!bmp.isRecycled) runCatching { bmp.recycle() }
                }
                finishSinglePageRender(gen)
                return@launch
            }
            result.onSuccess { applySinglePageBitmap(it, tallPanSnap, gen) }
                .onFailure { e ->
                    Toasts.show(
                        activity,
                        activity.getString(R.string.load_failed, e.message ?: ""),
                    )
                    finishSinglePageRender(gen)
                }
        }

        renderCache.bitmapCache.evictAll()
        if (activity.chromeVisible) activity.updatePdfBookmarkButton()
    }

    private fun applySinglePageBitmap(
        result: SinglePageRenderResult,
        tallPanSnap: PdfReadingActivity.TallPanSnap,
        gen: Long,
    ) {
        if (gen != activity.singlePageRenderGen || activity.isFinishing || activity.isDestroyed) {
            if (!result.bitmap.isRecycled) runCatching { result.bitmap.recycle() }
            finishSinglePageRender(gen)
            return
        }
        val i = result.index
        activity.pageIndex = i
        activity.hideSinglePageSurface()
        b.ivPdfPage.isVisible = true
        activity.rebindZoomTarget()
        val old = activity.singleBitmap
        activity.singleBitmap = result.bitmap
        b.ivPdfPage.layoutParams?.let { lp ->
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            b.ivPdfPage.layoutParams = lp
        }
        b.ivPdfPage.setImageBitmap(result.bitmap)
        activity.applyNightFilter(b.ivPdfPage)
        val container = b.pdfContainer
        ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
            "showSinglePage page=$i container=${activity.lastRenderW}x${activity.lastRenderH} " +
                "fitByWidth=${result.fitByWidth} bmp=${result.bitmap.width}x${result.bitmap.height} " +
                "zoom=${container.contentZoom} pan=(${container.getPanX()},${container.getPanY()})",
        )
        b.ivPdfPage.post {
            if (gen != activity.singlePageRenderGen || activity.isFinishing || activity.isDestroyed) return@post
            applySinglePageImageMatrix()
            b.pdfContainer.post {
                if (gen != activity.singlePageRenderGen || activity.isFinishing || activity.isDestroyed) return@post
                val host = b.pdfContainer
                val (minY, maxY) = host.verticalPanLimits()
                val panY = when (tallPanSnap) {
                    PdfReadingActivity.TallPanSnap.PRESERVE -> host.getPanY()
                    PdfReadingActivity.TallPanSnap.TOP -> maxY
                    PdfReadingActivity.TallPanSnap.BOTTOM -> minY
                }
                host.setTransform(host.contentZoom, host.getPanX(), panY, notify = false)
                updatePageBadge()
                activity.updateProgressLabel()
                if (activity.allowProgressSave) activity.saveProgress(activity.pageIndex)
                finishSinglePageRender(gen)
            }
        }
        if (old != null && old !== result.bitmap) {
            b.ivPdfPage.post {
                if (old !== activity.singleBitmap && !old.isRecycled) {
                    runCatching { old.recycle() }
                }
            }
        }
    }

    fun finishSinglePageRender(completedGen: Long) {
        if (completedGen != activity.singlePageRenderGen) return
        activity.singlePageRendering = false
        activity.pageTurnBusy = false
        activity.drainPendingSinglePageFlip()
    }

    fun tryCoalesceSinglePageFlip(forward: Boolean): Boolean {
        if (activity.pageMode != PdfPageMode.SINGLE || activity.pageCount <= 0) return false
        val base = activity.pendingSinglePage?.first ?: activity.pageIndex
        val next = if (forward) base + 1 else base - 1
        if (next !in 0 until activity.pageCount) return false
        val snap = if (b.pdfContainer.allowTallZoomTarget) {
            if (forward) PdfReadingActivity.TallPanSnap.TOP else PdfReadingActivity.TallPanSnap.BOTTOM
        } else {
            PdfReadingActivity.TallPanSnap.TOP
        }
        activity.pendingSinglePage = next to snap
        return true
    }

    fun pageTurn(
        forward: Boolean,
        closeMenu: Boolean = true,
        source: String = "unknown",
    ) {
        if (activity.pageTurnBusy || activity.singlePageRendering) {
            if (tryCoalesceSinglePageFlip(forward)) {
                ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                    "pageTurn coalesce fwd=$forward page=${activity.pageIndex} src=$source",
                )
            } else {
                ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN, "pageTurn busy skip src=$source")
            }
            return
        }
        activity.pageTurnBusy = true
        try {
            pageTurnInner(forward, closeMenu, source)
        } finally {
            if (!activity.singlePageRendering) activity.pageTurnBusy = false
        }
    }

    private fun pageTurnInner(
        forward: Boolean,
        closeMenu: Boolean = true,
        source: String = "unknown",
    ) {
        if (closeMenu && activity.chromeVisible) activity.hideChrome()
        if (closeMenu && b.settingsPanelContainer.isVisible) {
            b.settingsPanelContainer.isVisible = false
        }
        val dm = ctx.resources.displayMetrics
        if (activity.pageMode == PdfPageMode.CONTINUOUS) {
            val rv = b.rvPdfPages
            val viewportH = rv.height
            if (viewportH <= 0 || activity.pageCount <= 0) {
                ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN,
                    "cont abort src=$source viewportH=$viewportH activity.pageCount=${activity.pageCount}",
                )
                return
            }
            val est = activity.estimateCurrentPageHeightDetailed()
            val pageH = est.height.coerceAtLeast(1)
            val stepByScreen = pageH > viewportH
            val step = if (stepByScreen) {
                (viewportH * 0.8f).toInt().coerceAtLeast(1)
            } else {
                pageH
            }
            // forward=true 右边 → 向下；false 左边 → 向上
            val dy = if (forward) step else -step
            val before = rv.computeVerticalScrollOffset()
            // 无动画
            rv.stopScroll()
            rv.scrollBy(0, dy)
            val after = rv.computeVerticalScrollOffset()
            val lm = rv.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: activity.pageIndex
            val last = lm?.findLastVisibleItemPosition() ?: activity.pageIndex
            ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                "cont src=$source fwd=$forward mode=${activity.pageMode} " +
                    "screen=${dm.widthPixels}x${dm.heightPixels} dens=${dm.densityDpi} " +
                    "rv=${rv.width}x$viewportH pageH=$pageH step=$step byScreen=$stepByScreen " +
                    "dy=$dy scroll $before->$after delta=${after - before} " +
                    "pageIdx=${activity.pageIndex} first=$first last=$last " +
                    "est=${est.detail}",
            )
            if (after == before) {
                Toasts.show(ctx, if (forward) R.string.page_bottom else R.string.page_top)
                return
            }
            if (first >= 0) activity.pageIndex = first
            activity.updateProgressLabel()
            if (activity.allowProgressSave) activity.saveProgress(activity.pageIndex)
            rv.post { activity.refreshVisiblePageTiles(forceRender = true) }
            return
        }
        if (activity.needsTallSinglePageZoomHost()) {
            activity.ensureSinglePageTallPanReady()
            val host = b.pdfContainer
            val viewportH = host.height.coerceAtLeast(1)
            val step = viewportH * 0.8f
            val dy = if (forward) -step else step
            val panYBefore = host.getPanY()
            val (_, movedY) = host.panContentBy(0f, dy)
            val panY = host.getPanY()
            val (minY, maxY) = host.verticalPanLimits()
            val canPanVert = minY < maxY - 1f
            val scrollRange = (maxY - minY).coerceAtLeast(1f)
            val viewFrac = kotlin.math.abs(movedY) / viewportH
            val contentFrac = kotlin.math.abs(movedY) / scrollRange
            val atBottom = canPanVert && panY <= minY + 2f
            val atTop = canPanVert && panY >= maxY - 2f
            ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                "singleTall src=$source fwd=$forward page=${activity.pageIndex} " +
                    "screen=${dm.widthPixels}x${dm.heightPixels} host=${host.width}x$viewportH " +
                    "step=$step dy=$dy movedY=$movedY pan $panYBefore->$panY " +
                    "bounds=$minY..$maxY canPan=$canPanVert atTop=$atTop atBottom=$atBottom " +
                    "viewFrac=${"%.2f".format(viewFrac)} contentFrac=${"%.3f".format(contentFrac)} " +
                    "zoom=${host.contentZoom} iv=${b.ivPdfPage.width}x${b.ivPdfPage.height}",
            )
            if (kotlin.math.abs(movedY) > 0.5f) {
                activity.updateProgressLabel()
                activity.refreshSinglePageTiles(forceRender = true)
                return
            }
            if (!canPanVert) {
                ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN,
                    "singleTall blocked: vertical pan range collapsed, skip flip",
                )
                return
            }
            if (forward) {
                if (!atBottom) {
                    ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN,
                        "singleTall blocked: not at bottom (panY=$panY minY=$minY)",
                    )
                    return
                }
            } else if (!atTop) {
                ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN,
                    "singleTall blocked: not at top (panY=$panY maxY=$maxY)",
                )
                return
            }
            val next = if (forward) activity.pageIndex + 1 else activity.pageIndex - 1
            if (next !in 0 until activity.pageCount) {
                Toasts.show(ctx, if (forward) R.string.page_bottom else R.string.page_top)
                return
            }
            ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                "singleTall flip page=${activity.pageIndex} -> $next src=$source",
            )
            showSinglePage(
                next,
                if (forward) PdfReadingActivity.TallPanSnap.TOP else PdfReadingActivity.TallPanSnap.BOTTOM,
            )
            return
        }
        val next = if (forward) activity.pageIndex + 1 else activity.pageIndex - 1
        ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
            "single src=$source fwd=$forward page=${activity.pageIndex} -> $next " +
                "screen=${dm.widthPixels}x${dm.heightPixels} dens=${dm.densityDpi}",
        )
        if (next !in 0 until activity.pageCount) {
            Toasts.show(ctx, if (forward) R.string.page_bottom else R.string.page_top)
            return
        }
        showSinglePage(next, PdfReadingActivity.TallPanSnap.TOP)
    }

    fun relayoutAfterOrientationChange() {
        if (!activity.isBindingReady()) return
        val keepMenu = activity.chromeVisible
        val continuousSnap = if (activity.pageMode == PdfPageMode.CONTINUOUS) {
            b.pdfContainer.snapshotContinuousTransform()
        } else {
            null
        }
        b.pdfContainer.scheduleContinuousTransformRestore(continuousSnap)
        val cfg = ctx.resources.configuration
        ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
            "relayout start mode=${AppSettings.pdfOrientationMode(ctx)} " +
                "activity.pageMode=${activity.pageMode} page=${activity.pageIndex} " +
                "cfg=${cfg.screenWidthDp}x${cfg.screenHeightDp} " +
                "root=${b.root.width}x${b.root.height} " +
                "container=${b.pdfContainer.width}x${b.pdfContainer.height} " +
                "contSnap=$continuousSnap " +
                "isLand=${activity.isLandscape()} winLand=${activity.isWindowLandscape()}",
        )
        activity.sanitizeBottomChrome()
        // 先清左右 padding，再按模式重算竖栏（手机通常为 0）
        b.pdfContainer.setPadding(0, 0, 0, 0)
        b.pdfContainer.allowTallZoomTarget = activity.needsTallSinglePageZoomHost()
        activity.applyPortraitColumnLayout()
        if (keepMenu) activity.chromeVisible = true
        activity.applyChromeVisibility()
        if (activity.chromeVisible) activity.forceMenuLayout(preservePage = true)
        // 横竖屏切换：取消在途任务 + 废弃旧宽度缓存（必须先于 rebind）
        activity.cancelInFlightPdfRenders("orientRelayout")
        renderCache.evictAll()
        // 页高表按旧宽度记录的绝对像素，旋转后一律作废再按新宽写
        activity.pageHeightTable.clearHeights()
        activity.updatePdfZoomChrome()
        b.root.requestLayout()

        activity.runWhenPdfViewportSettled("orientRelayout") {
            if (activity.isFinishing || activity.isDestroyed) return@runWhenPdfViewportSettled
            // 视口稳定后再记页高，避免用到旋转中途的旧 width
            for (i in 0 until activity.pageCount) {
                activity.rendererPageSize[i]?.let { activity.recordPageItemHeight(i, it.first, it.second) }
            }
            when (activity.pageMode) {
                PdfPageMode.SINGLE -> {
                    // 单页：旋转后重渲 + 重置到 1x 顶对齐，避免竖屏 pan/zoom 带到横屏裁切
                    b.pdfContainer.resetZoom(notify = false)
                    if (activity.pageCount > 0) {
                        showSinglePage(activity.pageIndex)
                        b.ivPdfPage.post {
                            applySinglePageImageMatrix()
                            b.ivPdfPage.post {
                                b.pdfContainer.resetZoom(notify = true)
                                ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
                                    "relayout single done " +
                                        "container=${b.pdfContainer.width}x${b.pdfContainer.height} " +
                                        "iv=${b.ivPdfPage.width}x${b.ivPdfPage.height} " +
                                        "lpH=${b.ivPdfPage.layoutParams.height} " +
                                        "zoom=${b.pdfContainer.contentZoom} " +
                                        "pan=(${b.pdfContainer.getPanX()},${b.pdfContainer.getPanY()}) " +
                                        "canPan=${b.pdfContainer.canPanContent()}",
                                )
                            }
                        }
                    }
                }
                PdfPageMode.CONTINUOUS -> {
                    // 连续：保持 zoom 与水平 pan 比例；竖向滚动位置由页高表保留
                    b.rvPdfPages.adapter?.notifyDataSetChanged()
                    b.rvPdfPages.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        // 可见页立刻按真实 layout 宽校正高度（notify 时 item 宽可能仍旧）
                        val lm = b.rvPdfPages.layoutManager as? LinearLayoutManager
                        if (lm != null) {
                            val first = lm.findFirstVisibleItemPosition()
                            val last = lm.findLastVisibleItemPosition()
                            if (first != RecyclerView.NO_POSITION) {
                                for (pos in first..last.coerceAtLeast(first)) {
                                    val child = lm.findViewByPosition(pos) ?: continue
                                    val surface = child.findViewById<PdfPageSurface>(R.id.ivPage)
                                        ?: continue
                                    if (surface.pageIndex == pos) {
                                        surface.syncHeightToLaidOutWidth(
                                            surface.width.takeIf { it > 0 } ?: child.width,
                                        )
                                    }
                                }
                            }
                        }
                        continuousSnap?.let { b.pdfContainer.restoreContinuousTransform(it) }
                        activity.refreshVisiblePageTiles(forceRender = true)
                        activity.updatePdfZoomChrome()
                        activity.syncPdfContentBottomInset()
                        ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
                            "relayout continuous done " +
                                "container=${b.pdfContainer.width}x${b.pdfContainer.height} " +
                                "rv=${b.rvPdfPages.width}x${b.rvPdfPages.height} " +
                                "zoom=${b.pdfContainer.contentZoom} " +
                                "pan=(${b.pdfContainer.getPanX()},${b.pdfContainer.getPanY()})",
                        )
                    }
                }
            }
            activity.sanitizeBottomChrome()
            if (keepMenu) {
                activity.chromeVisible = true
                activity.applyChromeVisibility()
                activity.forceMenuLayout(preservePage = true)
            }
            activity.syncPdfContentBottomInset()
            b.pdfContainer.requestLayout()
            if (activity.hasTtsHighlight()) activity.refreshHighlightOverlay()
            if (activity.hasTextSelection()) activity.refreshSelectionOverlay()
            ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
                "relayout postChrome " +
                    "container=${b.pdfContainer.width}x${b.pdfContainer.height} " +
                    "iv=${b.ivPdfPage.width}x${b.ivPdfPage.height}",
            )
        }
    }

    fun applyPageModeUi() {
        when (activity.pageMode) {
            PdfPageMode.CONTINUOUS -> {
                b.rvPdfPages.isVisible = true
                b.rvPdfPages.isEnabled = true
                b.ivPdfPage.isVisible = false
                b.tvPageBadge.isVisible = false
            }
            PdfPageMode.SINGLE -> {
                b.rvPdfPages.isVisible = false
                b.rvPdfPages.isEnabled = false
                b.ivPdfPage.isVisible = !activity.singlePageUsesTiles
                b.ivPdfPage.isClickable = false
                b.ivPdfPage.isFocusable = false
                activity.singlePageSurface?.isVisible = activity.singlePageUsesTiles
                b.tvPageBadge.isVisible = true
                updatePageBadge()
            }
        }
        activity.rebindZoomTarget()
        activity.updateModeButtons()
        activity.refreshSelectionOverlay()
        activity.updateFastScrollEnabled()
    }

    fun updatePageBadge() {
        if (!activity.isBindingReady()) return
        if (activity.pageMode != PdfPageMode.SINGLE || activity.pageCount <= 0) {
            b.tvPageBadge.isVisible = false
            return
        }
        b.tvPageBadge.isVisible = true
        b.tvPageBadge.text = "${activity.pageIndex + 1}"
    }

    fun updatePageBadgeZoomCompensation() {
        if (!activity.isBindingReady()) return
        if (activity.pageMode != PdfPageMode.CONTINUOUS) return
        val z = b.pdfContainer.contentZoom.coerceAtLeast(0.01f)
        val inv = 1f / z
        val rv = b.rvPdfPages
        for (i in 0 until rv.childCount) {
            val badge = rv.getChildAt(i).findViewById<android.widget.TextView>(R.id.tvPageBadge)
                ?: continue
            badge.pivotX = 0f
            badge.pivotY = 0f
            badge.scaleX = inv
            badge.scaleY = inv
        }
    }

    fun setPageMode(mode: PdfPageMode) {
        if (activity.pageMode == mode) return
        val keep = activity.currentVisiblePage()
        activity.pageMode = mode
        AppSettings.setPdfPageMode(ctx, mode)
        activity.clearTextSelection()
        activity.invalidatePageBitmaps()
        applyPageModeUi()
        activity.restorePosition(keep)
        Toasts.show(
            ctx,
            if (mode == PdfPageMode.CONTINUOUS) {
                R.string.pdf_mode_switched_continuous
            } else {
                R.string.pdf_mode_switched_single
            },
        )
    }

    fun applySinglePageImageMatrix() {
        val iv = b.ivPdfPage
        val host = b.pdfContainer
        val d = iv.drawable ?: return
        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        // 用容器尺寸：ImageView 可能刚被加高，vw/vh 要以宿主视口为准
        val vw = host.width.toFloat().coerceAtLeast(1f)
        val vh = host.height.toFloat().coerceAtLeast(1f)
        if (vw <= 1f || vh <= 1f) {
            ReaderLog.w(ReaderLog.Module.PDF_ORIENT, "applyMatrix skip host=${vw}x$vh")
            return
        }
        val landscape = vw > vh
        val fitByWidth = activity.singlePageFitByWidth(dw, dh, vw, vh)
        val scale = if (fitByWidth) vw / dw else min(vw / dw, vh / dh)
        val contentH = dh * scale
        val needTall = fitByWidth && contentH > vh + 1f
        // 超长页：layout 高度与 matrix 只应用一次缩放（layoutH = dh×scale，matrix = scale）
        val matrixScale = scale
        val layoutH = if (needTall) {
            (dh * matrixScale).toInt().coerceAtLeast(1)
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        val lp = iv.layoutParams
        val wantW = ViewGroup.LayoutParams.MATCH_PARENT
        if (lp != null && (lp.height != layoutH || lp.width != wantW)) {
            lp.width = wantW
            lp.height = layoutH
            iv.layoutParams = lp
            ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
                "ivLayout tall=$needTall height=$layoutH bmp=${dw.toInt()}x${dh.toInt()} " +
                    "matrixScale=$matrixScale host=${vw.toInt()}x${vh.toInt()}",
            )
        }
        val m = Matrix()
        m.setScale(matrixScale, matrixScale)
        val contentWVis = dw * matrixScale
        val contentHVis = dh * matrixScale
        // 加高后 iv 高度=contentH，矩阵从 (0,0) 铺满内容即可；未加高则居中/顶对齐
        val dx = if (fitByWidth) 0f else (vw - contentWVis) / 2f
        val dy = when {
            needTall -> 0f
            fitByWidth && contentHVis > vh -> 0f
            else -> (vh - contentHVis) / 2f
        }
        m.postTranslate(dx, dy)
        iv.scaleType = ImageView.ScaleType.MATRIX
        iv.imageMatrix = m
        ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
            "applyMatrix land=$landscape matrixScale=$matrixScale " +
                "content=${contentWVis.toInt()}x${contentHVis.toInt()} " +
                "host=${vw.toInt()}x${vh.toInt()} " +
                "iv=${iv.width}x${iv.height} lpH=$layoutH dx=$dx dy=$dy " +
                "canPan=${host.canPanContent()} zoom=${host.contentZoom} " +
                "pan=(${host.getPanX()},${host.getPanY()}) " +
                "bounds=${host.verticalPanLimits()}",
        )
        activity.updateSinglePageTallHostFlag()
        activity.updatePdfZoomLimitsForSinglePage()
        // 边界变更后重夹 pan，避免旧 panY 落在错误区间
        host.setTransform(host.contentZoom, host.getPanX(), host.getPanY(), notify = false)
    }
}
