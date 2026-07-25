package com.whj.reader.pdf.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.PdfReadingActivity
import com.whj.reader.R
import com.whj.reader.data.PdfLinkIndex
import com.whj.reader.data.PdfTextExtractor
import com.whj.reader.databinding.ActivityPdfReadingBinding
import com.whj.reader.model.PdfPageMode
import com.whj.reader.pdf.link.PdfLinkNavigator
import com.whj.reader.ui.PdfPageSurface
import com.whj.reader.util.ReaderLog
import com.whj.reader.util.Toasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.isVisible

/**
 * PDF 导航/书签/链接/目录/触摸控制器：
 * - 大纲预加载与 TOC+书签 BottomSheet
 * - 书内链接加载/hitTest/点击跳转
 * - 导航历史（前进/后退）
 * - 页面触摸设置（滚动跟随选区/高亮、单页尺寸变化重渲染）
 * - 外部 URI 打开确认
 * - 页文字预览（书签列表用）
 */
class PdfNavBookmarkController(
    private val activity: PdfReadingActivity,
) {

    private val ctx: Context get() = activity
    private val b: ActivityPdfReadingBinding get() = activity.binding
    private val textCache get() = activity.textCache

    var pageLinks: Map<Int, List<PdfLinkIndex.Link>> = emptyMap()
        private set
    private var outlineRoots: List<com.whj.reader.data.PdfOutlineLoader.Node>? = null
    private var outlineLoading = false
    private val linkNav = PdfLinkNavigator()

    fun reset() {
        pageLinks = emptyMap()
        outlineRoots = null
        outlineLoading = false
        linkNav.clear()
    }

    fun previewFromCache(page: Int): String? {
        fun fromChars(chars: List<PdfTextExtractor.PdfChar>?): String? {
            if (chars.isNullOrEmpty()) return null
            val s = buildString {
                for (c in chars) {
                    if (c.char == '\n' || c.char == '\r') append(' ') else append(c.char)
                    if (length >= 160) break
                }
            }.replace(Regex("\\s+"), " ").trim()
            return s.take(120).ifBlank { null }
        }
        return fromChars(textCache.pageChars[page]) ?: fromChars(textCache.rawPageCache[page])
    }

    /** 本页文字预览（约 120 字）；可能触发 PDFBox 抽字，勿在主线程调用 */
    fun extractPagePreview(page: Int): String {
        previewFromCache(page)?.let { return it }
        val uriStr = activity.intent.getStringExtra(EXTRA_URI) ?: return ctx.getString(R.string.pdf_bookmark_no_text)
        val extracted = runCatching {
            PdfTextExtractor.extractPagesRaw(ctx, Uri.parse(uriStr), listOf(page))[page]
        }.getOrNull()
        fun fromChars(chars: List<PdfTextExtractor.PdfChar>?): String? {
            if (chars.isNullOrEmpty()) return null
            val s = buildString {
                for (c in chars) {
                    if (c.char == '\n' || c.char == '\r') append(' ') else append(c.char)
                    if (length >= 160) break
                }
            }.replace(Regex("\\s+"), " ").trim()
            return s.take(120).ifBlank { null }
        }
        return fromChars(extracted) ?: ctx.getString(R.string.pdf_bookmark_no_text)
    }

    /**
     * 打开 PDF 后预加载目录到 [outlineRoots]（磁盘缓存优先，否则从会话 PDFBox 解析）。
     */
    fun preloadOutlineAsync(uri: Uri) {
        if (outlineLoading) return
        outlineRoots?.let { return }
        val hit = com.whj.reader.data.PdfOutlineCache.get(ctx, uri)
        if (hit != null) {
            outlineRoots = hit
            ReaderLog.i(ReaderLog.Module.PDF, "outline memory from cache nodes=${hit.size}")
            return
        }
        outlineLoading = true
        activity.lifecycleScope.launch {
            val roots = withContext(Dispatchers.IO) {
                try {
                    val fromSession = PdfTextExtractor.withSessionDocument { doc ->
                        com.whj.reader.data.PdfOutlineLoader.loadFromDocument(doc)
                    }
                    val list = fromSession
                        ?: com.whj.reader.data.PdfOutlineCache.loadOrParse(
                            ctx,
                            uri,
                        )
                    com.whj.reader.data.PdfOutlineCache.put(ctx, uri, list)
                    list
                } catch (t: Throwable) {
                    ReaderLog.e(ReaderLog.Module.PDF, "preload outline", t)
                    emptyList()
                }
            }
            outlineLoading = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            outlineRoots = roots
            ReaderLog.i(ReaderLog.Module.PDF, "outline preloaded nodes=${roots.size}")
        }
    }

    /** 目录（树）+ 书签，可滑动切换；优先用打开时已缓存的大纲 */
    fun showPageToc() {
        val uriStr = activity.intent.getStringExtra(EXTRA_URI)
        if (uriStr.isNullOrBlank()) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.pdf_toc_title)
                .setMessage(R.string.pdf_toc_empty)
                .setPositiveButton(R.string.confirm, null)
                .show()
            return
        }
        val uri = Uri.parse(uriStr)
        outlineRoots?.let { roots ->
            try {
                showPdfTocAndBookmarkSheet(roots)
            } catch (t: Throwable) {
                ReaderLog.e(ReaderLog.Module.PDF, "show toc UI failed", t)
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.pdf_toc_title)
                    .setMessage(R.string.pdf_toc_empty)
                    .setPositiveButton(R.string.confirm, null)
                    .show()
            }
            return
        }
        if (outlineLoading) {
            Toasts.show(ctx, R.string.pdf_toc_loading)
        } else {
            preloadOutlineAsync(uri)
            Toasts.show(ctx, R.string.pdf_toc_loading)
        }
        activity.lifecycleScope.launch {
            var wait = 0
            while (outlineRoots == null && wait < 80) {
                kotlinx.coroutines.delay(50)
                wait++
            }
            val roots = outlineRoots
                ?: withContext(Dispatchers.IO) {
                    runCatching {
                        com.whj.reader.data.PdfOutlineCache.loadOrParse(
                            ctx,
                            uri,
                        )
                    }.getOrDefault(emptyList())
                }.also { outlineRoots = it }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            try {
                showPdfTocAndBookmarkSheet(roots)
            } catch (t: Throwable) {
                ReaderLog.e(ReaderLog.Module.PDF, "show toc UI failed", t)
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.pdf_toc_title)
                    .setMessage(R.string.pdf_toc_empty)
                    .setPositiveButton(R.string.confirm, null)
                    .show()
            }
        }
    }

    fun showPdfTocAndBookmarkSheet(roots: List<com.whj.reader.data.PdfOutlineLoader.Node>) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val sheet = com.whj.reader.databinding.SheetTocBinding.inflate(activity.layoutInflater)
        dialog.setContentView(sheet.root)

        val cur = activity.mostVisiblePage()
        fun jumpPage(page: Int) {
            dialog.dismiss()
            navigateToPageWithHistory(page.coerceIn(0, (activity.pageCount - 1).coerceAtLeast(0)))
        }

        val outlineAdapter = if (roots.isNotEmpty()) {
            com.whj.reader.ui.PdfTocAdapter(
                roots = roots,
                expanded = com.whj.reader.data.PdfOutlineLoader.defaultExpanded(roots, cur),
                currentPage = cur,
                onOpenPage = { page -> jumpPage(page) },
            )
        } else {
            null
        }

        lateinit var bookmarkAdapter: com.whj.reader.ui.TocAdapter
        bookmarkAdapter = com.whj.reader.ui.TocAdapter(
            onClick = { item ->
                val page = (item as? com.whj.reader.ui.TocItem.BookmarkItem)
                    ?.bookmark?.paragraphIndex ?: return@TocAdapter
                jumpPage(page)
            },
            onDeleteBookmark = { bm ->
                com.whj.reader.data.BookmarkStore.remove(ctx, bm.fileKey, bm.paragraphIndex)
                val items = com.whj.reader.data.BookmarkStore.list(ctx, activity.fileKey)
                    .map { com.whj.reader.ui.TocItem.BookmarkItem(it) }
                bookmarkAdapter.submit(items, cur, activity.pageCount)
                activity.updatePdfBookmarkButton()
                Toasts.show(ctx, R.string.bookmark_removed)
            },
            totalParagraphs = activity.pageCount,
            bookmarkAsPage = true,
        )
        bookmarkAdapter.submit(
            com.whj.reader.data.BookmarkStore.list(ctx, activity.fileKey)
                .map { com.whj.reader.ui.TocItem.BookmarkItem(it) },
            cur,
            activity.pageCount,
        )

        val titles = listOf(ctx.getString(R.string.toc), ctx.getString(R.string.bookmark))
        sheet.vpToc.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = 2

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val page = activity.layoutInflater.inflate(R.layout.page_toc_list, parent, false)
                return object : RecyclerView.ViewHolder(page) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val page = holder.itemView
                val rv = page.findViewById<RecyclerView>(R.id.rvList)
                val empty = page.findViewById<android.widget.TextView>(R.id.tvEmpty)
                if (rv.layoutManager == null) {
                    rv.layoutManager = LinearLayoutManager(ctx)
                }
                com.whj.reader.ui.TocVpScrollHelper.attachVerticalList(rv, sheet.vpToc)
                if (position == 0) {
                    if (outlineAdapter != null) {
                        rv.adapter = outlineAdapter
                        empty.isVisible = false
                        rv.isVisible = true
                    } else {
                        rv.adapter = null
                        empty.isVisible = true
                        rv.isVisible = false
                        empty.setText(R.string.pdf_toc_empty)
                    }
                } else {
                    rv.adapter = bookmarkAdapter
                    fun sync() {
                        val n = bookmarkAdapter.itemCount
                        empty.isVisible = n == 0
                        rv.isVisible = n > 0
                        empty.setText(R.string.bookmark_empty)
                    }
                    sync()
                    if (page.getTag(R.id.rvList) !== bookmarkAdapter) {
                        page.setTag(R.id.rvList, bookmarkAdapter)
                        bookmarkAdapter.registerAdapterDataObserver(
                            object : RecyclerView.AdapterDataObserver() {
                                override fun onChanged() = sync()
                            },
                        )
                    }
                }
            }
        }
        com.google.android.material.tabs.TabLayoutMediator(sheet.tabLayout, sheet.vpToc) { tab, pos ->
            tab.text = titles[pos]
        }.attach()

        dialog.setOnShowListener {
            runCatching {
                val bottomSheet = dialog.findViewById<View>(
                    com.google.android.material.R.id.design_bottom_sheet,
                ) ?: return@setOnShowListener
                val maxH = (ctx.resources.displayMetrics.heightPixels * 0.92f).toInt()
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply { height = maxH }
                bottomSheet.requestLayout()
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior
                    .from(bottomSheet)
                behavior.skipCollapsed = true
                behavior.isFitToContents = false
                behavior.expandedOffset =
                    (ctx.resources.displayMetrics.heightPixels - maxH).coerceAtLeast(0)
                behavior.state =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    // ─── 触摸 ─────────────────────────────────────────────

    fun setupPageTouch() {
        b.rvPdfPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (activity.hasTextSelection()) activity.refreshSelectionOverlay()
                if (activity.hasTtsHighlight()) activity.refreshHighlightOverlay()
            }
        })

        b.pdfContainer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            val h = v.height
            if (activity.pageCount > 0 && w > 0 && h > 0 &&
                activity.pageMode == PdfPageMode.SINGLE &&
                !activity.singlePageRendering &&
                (w != activity.lastRenderW || h != activity.lastRenderH)
            ) {
                activity.lastRenderW = w
                activity.lastRenderH = h
                activity.showSinglePage(activity.pageIndex)
            }
            activity.refreshSelectionOverlay()
        }
    }

    /** 中部轻点：开关菜单（侧边翻页已由 onSideTapImmediate 处理） */
    fun handleTap(x: Float, width: Float) {
        if (activity.hasTextSelection()) {
            activity.clearTextSelection()
            return
        }
        if (b.settingsPanelContainer.isVisible) {
            b.settingsPanelContainer.isVisible = false
            return
        }
        when {
            x < width / 3f -> {
                if (activity.chromeVisible) {
                    activity.hideChrome()
                    return
                }
                activity.pageTurn(false)
            }
            x > width * 2f / 3f -> {
                if (activity.chromeVisible) {
                    activity.hideChrome()
                    return
                }
                activity.pageTurn(true)
            }
            else -> activity.toggleChrome()
        }
    }

    // ─── 书内链接 ─────────────────────────────────────────

    fun loadPdfLinksAsync(uri: Uri) {
        activity.lifecycleScope.launch {
            val links = withContext(Dispatchers.IO) {
                if (!PdfTextExtractor.hasSession(uri)) {
                    PdfTextExtractor.openSession(ctx, uri)
                }
                PdfTextExtractor.extractLinksFromSession()
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            pageLinks = links
            ReaderLog.i(ReaderLog.Module.PDF,
                "links ready pages=${links.size} total=${links.values.sumOf { it.size }}",
            )
        }
    }

    /**
     * 点击是否命中书内/外部链接。
     * @return true 已处理（不再开关菜单）
     */
    fun tryHandlePdfLinkTap(containerX: Float, containerY: Float): Boolean {
        if (pageLinks.isEmpty()) return false
        if (activity.hasTextSelection()) return false
        val hit = hitTestLink(containerX, containerY) ?: return false
        when {
            hit.targetPage != null -> {
                val target = hit.targetPage.coerceIn(0, (activity.pageCount - 1).coerceAtLeast(0))
                if (target == activity.currentVisiblePage()) return true
                navigateToPageWithHistory(target)
                Toasts.show(ctx, ctx.getString(R.string.pdf_link_jumped, target + 1))
                return true
            }
            !hit.uri.isNullOrBlank() -> {
                confirmOpenExternalUri(hit.uri)
                return true
            }
        }
        return false
    }

    fun hitTestLink(containerX: Float, containerY: Float): PdfLinkIndex.Link? {
        val content = b.pdfContainer.mapToContent(containerX, containerY)
        return when (activity.pageMode) {
            PdfPageMode.SINGLE -> {
                val page = activity.pageIndex
                val links = pageLinks[page] ?: return null
                val pageXY = activity.viewToPageCoords(b.ivPdfPage, content.x, content.y, page)
                    ?: return null
                links.firstOrNull { it.contains(pageXY[0], pageXY[1]) }
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = b.rvPdfPages
                val child = rv.findChildViewUnder(content.x, content.y) ?: return null
                val pos = rv.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) return null
                val links = pageLinks[pos] ?: return null
                val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: return null
                val localX = content.x - child.left - surface.left
                val localY = content.y - child.top - surface.top
                val pageXY = surface.viewToPage(localX, localY)
                links.firstOrNull { it.contains(pageXY[0], pageXY[1]) }
            }
        }
    }

    fun navigateToPageWithHistory(targetPage: Int) {
        val from = activity.currentVisiblePage()
        if (!linkNav.pushJump(from, targetPage)) return
        if (activity.chromeVisible) activity.hideChrome()
        activity.restorePosition(targetPage.coerceIn(0, (activity.pageCount - 1).coerceAtLeast(0)))
        updateHistNavButtons()
        activity.updateProgressLabel()
        activity.updatePdfBookmarkButton()
    }

    fun navigateHistoryBack() {
        val cur = activity.currentVisiblePage()
        val target = linkNav.goBack(cur) ?: return
        activity.restorePosition(target.coerceIn(0, (activity.pageCount - 1).coerceAtLeast(0)))
        updateHistNavButtons()
        activity.updateProgressLabel()
        activity.updatePdfBookmarkButton()
    }

    fun navigateHistoryForward() {
        val cur = activity.currentVisiblePage()
        val target = linkNav.goForward(cur) ?: return
        activity.restorePosition(target.coerceIn(0, (activity.pageCount - 1).coerceAtLeast(0)))
        updateHistNavButtons()
        activity.updateProgressLabel()
        activity.updatePdfBookmarkButton()
    }

    fun updateHistNavButtons() {
        val binding = try { b } catch (_: UninitializedPropertyAccessException) { return }
        val canBack = linkNav.canGoBack
        val canFwd = linkNav.canGoForward
        binding.btnHistBack.isEnabled = canBack
        binding.btnHistBack.alpha = if (canBack) 1f else 0.35f
        binding.btnHistForward.isEnabled = canFwd
        binding.btnHistForward.alpha = if (canFwd) 1f else 0.35f
    }

    fun confirmOpenExternalUri(uriStr: String) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.pdf_link_external)
            .setMessage(uriStr)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                        ),
                    )
                }.onFailure {
                    Toasts.show(ctx, it.message ?: "error")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_URI = "uri"
    }
}
