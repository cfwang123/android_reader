package com.whj.reader.pdf.session

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.whj.reader.PdfReadingActivity
import com.whj.reader.R
import com.whj.reader.ReadingActivity
import com.whj.reader.data.AppSettings
import com.whj.reader.data.BookFileType
import com.whj.reader.data.BookshelfStore
import com.whj.reader.data.PdfTextExtractor
import com.whj.reader.databinding.ActivityPdfReadingBinding
import com.whj.reader.model.PdfPageMode
import com.whj.reader.util.OpenFailGuide
import com.whj.reader.util.ReaderLog
import com.whj.reader.util.StorageAccess
import com.whj.reader.util.Toasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PDF 文档会话：打开/关闭、打开失败引导、URI 重选、内容遮罩。
 * 直接持有 [PdfReadingActivity] 引用。
 */
class PdfDocumentController(
    private val activity: PdfReadingActivity,
) {
    private val ctx: Context get() = activity
    private val b: ActivityPdfReadingBinding get() = activity.binding
    private val scope get() = activity.lifecycleScope

    fun loadPdf() {
        val uriStr = activity.intent.getStringExtra(PdfReadingActivity.EXTRA_URI)
        val titleExtra = activity.intent.getStringExtra(PdfReadingActivity.EXTRA_TITLE)
        if (uriStr.isNullOrBlank()) {
            showOpenFailGuide(
                OpenFailGuide.Reason.UNAVAILABLE,
                detail = "no uri",
            )
            return
        }
        val uri = Uri.parse(uriStr)
        activity.displayTitle = titleExtra?.ifBlank { null }
            ?: uri.lastPathSegment
            ?: activity.getString(R.string.unnamed)
        activity.fileKey = uriStr
        b.tvReadTitle.text = activity.displayTitle
        // 按本书加载切边（各 PDF 独立，不共通）
        val cropM = AppSettings.pdfCropMargins(ctx, activity.fileKey)
        activity.cropL = cropM[0]; activity.cropT = cropM[1]; activity.cropR = cropM[2]; activity.cropB = cropM[3]
        activity.updateCropSummary()
        // 遮罩 + 隐藏内容，防止恢复位置前先画出第 1 页
        setPdfContentHidden(true)
        b.tvLoading.isVisible = true

        scope.launch {
            val fdResult = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("无法打开 PDF")
                }
            }
            fdResult.onFailure { e ->
                b.tvLoading.isVisible = false
                setPdfContentHidden(false)
                showOpenFailGuide(
                    reason = OpenFailGuide.reasonFrom(e),
                    detail = e.message,
                )
            }
            fdResult.onSuccess { fd ->
                try {
                    closePdfLocked()
                    activity.pfd = fd
                    val r = PdfRenderer(fd)
                    activity.renderer = r
                    activity.pageCount = r.pageCount
                    if (activity.pageCount <= 0) error("PDF 无页面")
                    activity.initPageHeightTable(activity.pageCount)

                    activity.allowProgressSave = false
                    // 恢复页码 / 缩放 / 平移 / 滚动（切边已按 fileKey 加载）
                    val viewState = AppSettings.loadPdfViewState(activity, activity.fileKey)
                    val shelf = BookshelfStore.findBookByUri(activity, activity.fileKey)
                        ?.lastParagraph ?: 0
                    val progressPage = com.whj.reader.data.ReadingProgressStore
                        .get(activity, activity.fileKey)
                        ?.takeIf { it.kind == com.whj.reader.data.ReadingProgressStore.Kind.PDF }
                        ?.position ?: 0
                    activity.pageIndex = maxOf(viewState.page, shelf, progressPage)
                        .coerceIn(0, (activity.pageCount - 1).coerceAtLeast(0))
                    // 打开前预取 0..目标页 真尺寸，避免长图 PDF 用 A4 估算高度 → 滚动错位、上一页尾部空白
                    withContext(Dispatchers.IO) {
                        activity.prefetchPageSizesUpTo(activity.pageIndex)
                    }
                    activity.logPdfOpen(
                        "prefetch done targetPage=${activity.pageIndex} scrollY=${viewState.scrollY} " +
                            "heights0..3=${activity.pageHeightTable.snapshotPrefix(4).joinToString()}",
                        force = true,
                    )
                    activity.navBookmarkController.reset()
                    activity.updateHistNavButtons()

                    // 仅更新已在书架上的书，不自动新增（绑定文件夹打开不进主书架）
                    BookshelfStore.updateIfExists(
                        activity,
                        uri = activity.fileKey,
                        displayName = BookFileType.stripBookExt(activity.displayTitle),
                        lastParagraph = activity.pageIndex,
                    )
                    com.whj.reader.data.ReadingProgressStore.savePdf(
                        activity,
                        activity.fileKey,
                        activity.pageIndex,
                        activity.pageCount,
                    )
                    // 缓存文件大小（书架列表用，避免反复 query）
                    activity.cachePdfFileSize(activity.fileKey)
                    // 不写 TXT 的 lastBook，只写 PDF 上次书
                    AppSettings.setLastPdfBook(activity, activity.fileKey, activity.displayTitle)

                    // 勿在 post 前 setPageCount：会先绑定第 0 页造成闪一下
                    b.pdfContainer.post {
                        activity.applyPageModeUi()
                        activity.restorePdfViewState(viewState.copy(page = activity.pageIndex))
                        // 再等一帧：连续模式 scrollToPosition 需布局完成后才稳定
                        b.pdfContainer.post {
                            if (activity.isFinishing || activity.isDestroyed) return@post
                            setPdfContentHidden(false)
                            b.tvLoading.isVisible = false
                            activity.allowProgressSave = true
                            activity.updateProgressLabel()
                            activity.updateFastScrollEnabled()
                            // 布局/滚动稳定后再刷一次长图条带，避免首帧空白
                            activity.refreshVisiblePageTiles(forceRender = true)
                            b.rvPdfPages.post {
                                activity.refreshVisiblePageTiles(forceRender = true)
                                activity.logPdfOpenVisible("afterOpenRefresh")
                            }
                        }
                    }
                    // 后台预取当前附近页尺寸，避免 onBind 主线程抢 renderLock
                    activity.prefetchPageSizesAround(activity.pageIndex)
                    // 打开后立即后台：PDFBox 进内存 + 当前页附近文字缓存，之后按需预取
                    activity.startNearbyTextExtraction(uri)
                    // 后台加载书内链接
                    activity.loadPdfLinksAsync(uri)
                } catch (e: Exception) {
                    b.tvLoading.isVisible = false
                    setPdfContentHidden(false)
                    showOpenFailGuide(
                        reason = OpenFailGuide.reasonFrom(e),
                        detail = e.message,
                    )
                }
            }
        }
    }

    fun showOpenFailGuide(reason: OpenFailGuide.Reason, detail: String?) {
        val title = activity.intent.getStringExtra(PdfReadingActivity.EXTRA_TITLE) ?: activity.displayTitle
        OpenFailGuide.show(
            activity = activity,
            reason = reason,
            detail = detail,
            bookTitle = title,
            onGrantPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.openFailPermissionLauncher.launch(
                        StorageAccess.manageAllFilesIntent(ctx),
                    )
                } else {
                    loadPdf()
                }
            },
            onReselect = {
                activity.reselectDocLauncher.launch(
                    arrayOf(
                        "application/pdf",
                        "application/octet-stream",
                        "text/plain",
                        "text/*",
                    ),
                )
            },
            onClose = { activity.finish() },
        )
    }

    fun applyReselectedUri(uri: Uri) {
        val oldUri = activity.intent.getStringExtra(PdfReadingActivity.EXTRA_URI)
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    }
                }.getOrNull()
                    ?: uri.lastPathSegment
                    ?: activity.intent.getStringExtra(PdfReadingActivity.EXTRA_TITLE)
                    ?: activity.getString(R.string.unnamed)
            }
            // 重选为 TXT 时切到文本阅读
            val isPdf = BookFileType.isPdfUri(activity, uri, name) ||
                BookFileType.isPdf(name)
            val stable = withContext(Dispatchers.IO) {
                OpenFailGuide.bindReselectedFile(
                    activity,
                    oldUri = oldUri,
                    newUri = uri,
                    displayName = name,
                )
            }
            Toasts.show(activity, R.string.open_failed_reselect_done)
            if (!isPdf) {
                activity.startActivity(
                    Intent(activity, ReadingActivity::class.java)
                        .putExtra(ReadingActivity.EXTRA_URI, stable)
                        .putExtra(ReadingActivity.EXTRA_TITLE, BookFileType.stripBookExt(name)),
                )
                activity.finish()
                return@launch
            }
            activity.intent.putExtra(PdfReadingActivity.EXTRA_URI, stable)
            activity.intent.putExtra(PdfReadingActivity.EXTRA_TITLE, BookFileType.stripBookExt(name))
            activity.displayTitle = BookFileType.stripBookExt(name)
            loadPdf()
        }
    }

    fun setPdfContentHidden(hidden: Boolean) {
        if (!activity.isBindingReady()) return
        val a = if (hidden) 0f else 1f
        b.rvPdfPages.alpha = a
        b.ivPdfPage.alpha = a
        activity.singlePageSurface?.alpha = a
        b.tvPageBadge.alpha = a
    }

    fun closePdf() {
        try {
            closePdfLocked()
        } catch (_: Exception) {
        }
    }

    fun closePdfLocked() {
        activity.extractJob?.cancel()
        activity.extractJob = null
        activity.ttsExtracting = false
        activity.pendingAfterExtract = null
        activity.currentPage?.close()
        activity.currentPage = null
        activity.renderer?.close()
        activity.renderer = null
        activity.pfd?.close()
        activity.pfd = null
        // 释放内存中的 PDFBox 文档与文字缓存
        PdfTextExtractor.closeSession()
        activity.textCache.clear()
        activity.navBookmarkController.reset()
        activity.singleBitmap?.recycle()
        activity.singleBitmap = null
        activity.singlePageSurface?.let { s ->
            for (b in s.drainTiles()) activity.unpinTileBitmap(b)
            s.clearContent()
            s.isVisible = false
        }
        activity.singlePageUsesTiles = false
        activity.pdfRenderCache.clearTileCache()
    }
}
