package com.whj.reader.pdf.textload

import android.content.Context
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import com.whj.reader.PdfReadingActivity
import com.whj.reader.R
import com.whj.reader.data.PdfOcrCacheStore
import com.whj.reader.data.PdfTextExtractor
import com.whj.reader.databinding.ActivityPdfReadingBinding
import com.whj.reader.util.ReaderLog
import com.whj.reader.util.Toasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PDF 文字懒加载：附近页抽取、ensurePagesExtracted、OCR 合并、rebuild 段落。
 * 直接持有 [PdfReadingActivity] 引用。
 */
class PdfTextLoadController(
    private val activity: PdfReadingActivity,
) {
    private val ctx: Context get() = activity
    private val b: ActivityPdfReadingBinding get() = activity.binding
    private val scope get() = activity.lifecycleScope

    fun startNearbyTextExtraction(uri: Uri) {
        activity.extractJob?.cancel()
        activity.pendingAfterExtract = null
        activity.lastTextPrefetchAnchor = -1
        activity.ttsExtracting = true
        val anchor = activity.pageIndex.coerceIn(0, (activity.pageCount - 1).coerceAtLeast(0))
        val nearby = pagesNear(anchor, before = 1, after = 2)
        activity.extractJob = scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val opened = withContext(Dispatchers.IO) {
                    runCatching {
                        PdfTextExtractor.openSession(activity, uri)
                    }.getOrDefault(false)
                }
                if (!opened) {
                    ReaderLog.w(ReaderLog.Module.PDF, "nearby extract: openSession failed")
                    return@launch
                }
                // 会话就绪后立刻预加载目录到内存（不挡首屏）
                activity.preloadOutlineAsync(uri)
                val extracted = withContext(Dispatchers.IO) {
                    runCatching {
                        PdfTextExtractor.extractPagesRaw(
                            activity,
                            uri,
                            nearby,
                        )
                    }.getOrElse {
                        ReaderLog.e(ReaderLog.Module.PDF, "nearby extract failed", it)
                        emptyMap()
                    }
                }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                // 磁盘 OCR 缓存优先填入空页，再合并 PDF 原生文字
                mergeOcrCacheFromDisk()
                for (p in nearby) {
                    val pdfChars = extracted[p] ?: emptyList()
                    val existing = activity.textCache.rawPageCache[p]
                    activity.textCache.rawPageCache[p] = when {
                        pdfChars.isNotEmpty() -> pdfChars
                        !existing.isNullOrEmpty() -> existing
                        else -> emptyList()
                    }
                }
                activity.lastTextPrefetchAnchor = anchor
                rebuildTextFromCache(preserveTtsPosition = false)
                val ms = System.currentTimeMillis() - t0
                ReaderLog.i(ReaderLog.Module.PDF,
                    "nearby text extract done pages=$nearby ${ms}ms",
                )
            } finally {
                activity.ttsExtracting = false
                val queued = activity.pendingAfterExtract
                activity.pendingAfterExtract = null
                if (queued != null && !activity.isFinishing && !activity.isDestroyed) {
                    b.pdfContainer.post {
                        if (!activity.isFinishing && !activity.isDestroyed) queued.invoke()
                    }
                }
            }
        }
    }

    internal fun pagesNear(anchor: Int, before: Int = 1, after: Int = 2): List<Int> {
        if (activity.pageCount <= 0) return emptyList()
        val a = anchor.coerceIn(0, activity.pageCount - 1)
        return ((a - before)..(a + after)).filter { it in 0 until activity.pageCount }
    }

    internal fun prefetchNearbyText(anchor: Int = activity.currentVisiblePage()) {
        if (activity.pageCount <= 0 || activity.fileKey.isEmpty()) return
        val a = anchor.coerceIn(0, activity.pageCount - 1)
        if (a == activity.lastTextPrefetchAnchor) {
            // 同页也检查是否仍有空洞
            val holes = pagesNear(a, 1, 2).any { it !in activity.textCache.rawPageCache }
            if (!holes) return
        } else {
            activity.lastTextPrefetchAnchor = a
        }
        val need = pagesNear(a, before = 1, after = 2).filter { it !in activity.textCache.rawPageCache }
        if (need.isEmpty()) return
        ensurePagesExtracted(
            pages = need,
            showToast = false,
            preserveTtsPosition = true,
            onReady = null,
        )
    }

    internal fun ensurePagesExtracted(
        pages: Collection<Int>,
        showToast: Boolean = false,
        preserveTtsPosition: Boolean = false,
        onReady: ((added: Boolean) -> Unit)? = null,
    ) {
        val wanted = pages.filter { it in 0 until activity.pageCount }.distinct().sorted()
        if (wanted.isEmpty()) {
            onReady?.invoke(false)
            return
        }
        val missing = wanted.filter { it !in activity.textCache.rawPageCache }
        if (missing.isEmpty()) {
            if (activity.textCache.paragraphs.isEmpty() || activity.textCache.pageChars.isEmpty()) {
                rebuildTextFromCache(preserveTtsPosition = preserveTtsPosition)
            }
            onReady?.invoke(false)
            return
        }
        // 合并并发：提取结束后再补缺
        if (activity.ttsExtracting) {
            if (onReady != null) {
                val prev = activity.pendingAfterExtract
                activity.pendingAfterExtract = {
                    prev?.invoke()
                    ensurePagesExtracted(wanted, showToast = false, preserveTtsPosition, onReady)
                }
            }
            if (showToast) Toasts.show(ctx, R.string.pdf_tts_extracting)
            return
        }
        val uriStr = activity.intent.getStringExtra(PdfReadingActivity.EXTRA_URI) ?: run {
            onReady?.invoke(false)
            return
        }
        activity.ttsExtracting = true
        if (showToast) Toasts.show(ctx, R.string.pdf_tts_extracting)
        val uri = Uri.parse(uriStr)
        val missingSnap = missing.toList()
        activity.extractJob = scope.launch {
            val extracted = try {
                withContext(Dispatchers.IO) {
                    try {
                        PdfTextExtractor.extractPagesRaw(activity, uri, missingSnap)
                    } catch (t: Throwable) {
                        ReaderLog.e(ReaderLog.Module.PDF, "extractPagesRaw failed", t)
                        emptyMap()
                    }
                }
            } catch (t: Throwable) {
                ReaderLog.e(ReaderLog.Module.PDF, "extract job failed", t)
                emptyMap()
            }
            activity.ttsExtracting = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            try {
                var added = false
                for ((p, chars) in extracted) {
                    val old = activity.textCache.rawPageCache[p]
                    when {
                        chars.isNotEmpty() -> {
                            activity.textCache.rawPageCache[p] = chars
                            added = true
                        }
                        old == null -> {
                            // PDF 无字：尝试 OCR 缓存
                            val ocr = PdfOcrCacheStore.loadPage(activity, activity.fileKey, p)
                            activity.textCache.rawPageCache[p] = ocr ?: emptyList()
                            added = true
                        }
                    }
                }
                // 空页也标记已尝试，避免反复抽 / 无限回调
                for (p in missingSnap) {
                    if (p !in activity.textCache.rawPageCache) {
                        activity.textCache.rawPageCache[p] =
                            PdfOcrCacheStore.loadPage(activity, activity.fileKey, p)
                                ?: emptyList()
                        added = true
                    }
                }
                rebuildTextFromCache(preserveTtsPosition = preserveTtsPosition)
                val queued = activity.pendingAfterExtract
                activity.pendingAfterExtract = null
                onReady?.invoke(added)
                // 排队任务延后一帧，避免深层同步回调栈溢出
                if (queued != null) {
                    b.pdfContainer.post {
                        if (!activity.isFinishing && !activity.isDestroyed) queued.invoke()
                    }
                }
            } catch (t: Throwable) {
                ReaderLog.e(ReaderLog.Module.PDF, "after extract failed", t)
                activity.pendingAfterExtract = null
                onReady?.invoke(false)
            }
        }
    }

    internal fun rebuildTextFromCache(preserveTtsPosition: Boolean = false) {
        if (activity.textCache.rawPageCache.isEmpty()) {
            activity.textCache.applyEmpty()
            return
        }
        val built = runCatching {
            PdfTextExtractor.buildFromCachedPages(activity.textCache.rawPageCache) { page -> activity.cropForPage(page) }
        }.getOrElse {
            PdfTextExtractor.Extracted(emptyList(), emptyMap(), emptyList(), activity.textCache.rawPageCache.toMap())
        }
        activity.textCache.applyBuilt(built)
        if (activity.isTtsReady()) {
            if (preserveTtsPosition && activity.textCache.paragraphs.isNotEmpty()) {
                activity.tts.updateDocumentKeepPosition(
                    activity.textCache.paragraphs,
                    com.whj.reader.data.TextLoader.SentenceLineBreakMode.NONE,
                )
            } else {
                activity.tts.setDocument(
                    activity.textCache.paragraphs,
                    com.whj.reader.data.TextLoader.SentenceLineBreakMode.NONE,
                )
            }
            activity.tts.setSessionTitle(activity.displayTitle)
        }
        if (!preserveTtsPosition) {
            activity.clearTtsHighlight()
            b.pdfSelectionOverlay.clearHighlight()
        }
    }

    fun applyCropToExtractedText() {
        rebuildTextFromCache(preserveTtsPosition = false)
    }

    internal fun mergeOcrCacheFromDisk() {
        if (activity.fileKey.isEmpty()) return
        val all = runCatching {
            PdfOcrCacheStore.loadAllPages(ctx, activity.fileKey)
        }.getOrDefault(emptyMap())
        for ((p, chars) in all) {
            val old = activity.textCache.rawPageCache[p]
            if (old.isNullOrEmpty() && chars.isNotEmpty()) {
                activity.textCache.rawPageCache[p] = chars
            }
        }
    }

    fun hasExtractedRaw(): Boolean = activity.textCache.rawPageCache.isNotEmpty()

    fun maxCachedPage(): Int = activity.textCache.rawPageCache.keys.maxOrNull() ?: -1
}
