package com.whj.reader.pdf.ocr

import com.whj.reader.PdfReadingActivity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.whj.reader.R
import com.whj.reader.data.PdfOcrCacheStore
import com.whj.reader.databinding.ActivityPdfReadingBinding
import com.whj.reader.databinding.DialogPdfOcrBinding
import com.whj.reader.ocr.TfliteOcrEngine
import com.whj.reader.pdf.ocr.PdfPageOcrRunner
import com.whj.reader.util.ReaderLog
import com.whj.reader.util.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PDF OCR UI 控制器：扫描版识图对话框、进度对话框、adb 调试轮询。
 * 单页 OCR 流水线由 [PdfPageOcrRunner] 负责。
 */
class PdfOcrUiController(
    private val activity: PdfReadingActivity,
) {

    private val ctx: Context get() = activity
    private val b: ActivityPdfReadingBinding get() = activity.binding
    private val scope: CoroutineScope get() = activity.lifecycleScope
    private val runner: PdfPageOcrRunner get() = activity.pdfOcrRunner

    private var ocrDebugPoll: Runnable? = null
    private val ocrDebugWatchdog = object : Runnable {
        override fun run() {
            if (activity.isFinishing || activity.isDestroyed) return
            if (java.io.File(activity.filesDir, "debug_pdf_ocr").exists()) {
                schedulePdfOcrDebugPoll()
            }
            activity.binding.root.post(this)
        }
    }

    fun startWatchdog(delayMs: Long = 800L) {
        activity.binding.root.removeCallbacks(ocrDebugWatchdog)
        activity.binding.root.post(ocrDebugWatchdog)
    }

    fun schedulePdfOcrDebugPoll() {
        val flag = java.io.File(activity.filesDir, "debug_pdf_ocr")
        if (!flag.exists()) {
            ocrDebugPoll?.let { activity.binding.root.removeCallbacks(it) }
            ocrDebugPoll = null
            return
        }
        if (ocrDebugPoll != null) return
        val r = Runnable {
            ocrDebugPoll = null
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            maybeRunPdfOcrDebugFromFile()
            if (java.io.File(activity.filesDir, "debug_pdf_ocr").exists()) {
                schedulePdfOcrDebugPoll()
            }
        }
        ocrDebugPoll = r
        activity.binding.root.post(r)
        activity.binding.root.post(Runnable { /* no-op, keep same pattern */ })
        activity.binding.root.removeCallbacks(r)
        activity.binding.root.postDelayed(r, 400L)
    }

    fun maybeRunPdfOcrDebugFromFile() {
        val flag = java.io.File(activity.filesDir, "debug_pdf_ocr")
        if (!flag.exists()) return
        val raw = runCatching { flag.readText().trim() }.getOrDefault("")
        ocrDebugPoll?.let { activity.binding.root.removeCallbacks(it) }
        ocrDebugPoll = null
        if (activity.fileKey.isEmpty() || activity.pageCount <= 0) {
            runCatching { flag.delete() }
            activity.logPdfOcr("debug skip: pdf not open raw='$raw'")
            Toasts.show(ctx, "adb OCR: 请先打开 PDF")
            return
        }
        if (activity.ocrJob?.isActive == true) {
            activity.logPdfOcr("debug skip: ocr busy raw='$raw'")
            activity.showToast(R.string.pdf_ocr_busy)
            schedulePdfOcrDebugPoll()
            return
        }
        val targetPage = parsePdfOcrDebugPage(raw)
        if (targetPage < 0) {
            runCatching { flag.delete() }
            activity.logPdfOcr("debug ignore raw='$raw' (use: current | page=N | N)")
            return
        }
        runCatching { flag.delete() }
        val p = targetPage.coerceIn(0, (activity.pageCount - 1).coerceAtLeast(0))
        activity.logPdfOcr(
            "debug trigger page=${p + 1}/${activity.pageCount} visible=${activity.currentVisiblePage() + 1} " +
                "raw='$raw'",
        )
        activity.showToast(R.string.pdf_ocr_debug_start, p + 1)
        startPdfOcrJob(fromPage0 = p, toPage0 = p, skipDone = false)
    }

    private fun parsePdfOcrDebugPage(raw: String): Int {
        if (raw.isBlank()) return -1
        if (raw == "current") return activity.currentVisiblePage()
        val re = Regex("""page\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        val m = re.matchEntire(raw)
        if (m != null) {
            val n = m.groupValues[1].toIntOrNull() ?: return -1
            return (n - 1).coerceAtLeast(0)
        }
        val plain = raw.toIntOrNull() ?: return -1
        return (plain - 1).coerceAtLeast(0)
    }

    fun showPdfOcrDialog() {
        if (activity.pageCount <= 0 || activity.fileKey.isEmpty()) return
        if (activity.ocrJob?.isActive == true) {
            activity.showToast(R.string.pdf_ocr_busy)
            return
        }
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_pdf_ocr, null)
        val dlgBind = DialogPdfOcrBinding.bind(view)
        val cur = activity.currentVisiblePage() + 1
        dlgBind.tvOcrHint.text = activity.getString(R.string.pdf_ocr_hint)
        dlgBind.etFrom.setText(cur.toString())
        dlgBind.etTo.setText(activity.pageCount.toString())

        val withText =
            PdfOcrCacheStore.listRecognizedWithText(ctx, activity.fileKey).sorted()
        val partial = (0 until activity.pageCount).filter {
            PdfOcrCacheStore.ocrQuality(ctx, activity.fileKey, it) ==
                PdfOcrCacheStore.OcrQuality.PARTIAL
        }.sorted()
        val emptyOnly = PdfOcrCacheStore.listRecognized(ctx, activity.fileKey)
            .filter { it !in withText.toSet() && it !in partial.toSet() }
            .sorted()
        dlgBind.tvOcrRecognized.text = when {
            withText.isEmpty() && emptyOnly.isEmpty() && partial.isEmpty() ->
                activity.getString(R.string.pdf_ocr_recognized_none)
            emptyOnly.isEmpty() && partial.isEmpty() ->
                activity.getString(
                    R.string.pdf_ocr_recognized_list,
                    formatPageList(withText.map { it + 1 }),
                )
            withText.isEmpty() && partial.isEmpty() ->
                activity.getString(
                    R.string.pdf_ocr_empty_result_list,
                    formatPageList(emptyOnly.map { it + 1 }),
                )
            else -> buildString {
                if (withText.isNotEmpty()) {
                    append(
                        activity.getString(
                            R.string.pdf_ocr_recognized_list,
                            formatPageList(withText.map { it + 1 }),
                        ),
                    )
                }
                if (partial.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(
                        activity.getString(
                            R.string.pdf_ocr_partial_result_list,
                            formatPageList(partial.map { it + 1 }),
                        ),
                    )
                }
                if (emptyOnly.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(
                        activity.getString(
                            R.string.pdf_ocr_empty_result_list,
                            formatPageList(emptyOnly.map { it + 1 }),
                        ),
                    )
                }
            }
        }
        dlgBind.cbSkipDone.isChecked = withText.isNotEmpty() && partial.isEmpty() &&
            (emptyOnly.isEmpty() || withText.isNotEmpty())

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.pdf_ocr_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pdf_ocr_start, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = dlgBind.etFrom.text.toString().toIntOrNull()
                val to = dlgBind.etTo.text.toString().toIntOrNull()
                if (from == null || to == null || from < 1 || to < from || to > activity.pageCount) {
                    activity.showToast(R.string.pdf_ocr_invalid_range)
                    return@setOnClickListener
                }
                val skipDone = dlgBind.cbSkipDone.isChecked
                dialog.dismiss()
                startPdfOcrJob(
                    fromPage0 = from - 1,
                    toPage0 = to - 1,
                    skipDone = skipDone,
                )
            }
        }
        dialog.show()
    }

    private fun formatPageList(pages1Based: List<Int>): String =
        PdfPageOcrRunner.formatPageList(pages1Based) { n ->
            activity.getString(R.string.pdf_ocr_and_more, n)
        }

    fun startPdfOcrJob(fromPage0: Int, toPage0: Int, skipDone: Boolean) {
        val pages = (fromPage0..toPage0).toList()
        if (pages.isEmpty()) return

        activity.hideChrome()
        activity.prepareBottomChromeForBlockingModal()

        val progressView = LayoutInflater.from(ctx)
            .inflate(android.R.layout.simple_list_item_1, null)
        val progressTv = progressView.findViewById<TextView>(android.R.id.text1)
        progressTv.setPadding(48, 36, 48, 24)
        progressTv.text = activity.getString(R.string.pdf_ocr_preparing)
        val progressDlg = AlertDialog.Builder(ctx)
            .setTitle(R.string.pdf_ocr_title)
            .setView(progressView)
            .setCancelable(false)
            .setNegativeButton(R.string.pdf_ocr_cancel) { _, _ ->
                activity.ocrJob?.cancel()
            }
            .create()
        progressDlg.setOnShowListener {
            activity.prepareBottomChromeForBlockingModal()
        }
        progressDlg.show()

        activity.ocrJob?.cancel()
        val job = scope.launch {
            val pageSizes = withContext(Dispatchers.IO) {
                pages.associateWith { p ->
                    activity.rendererPageSize[p] ?: activity.ensurePageSize(p)
                }
            }
            val skippedPages = ArrayList<Int>()
            val partialPages = ArrayList<Int>()
            val queue = if (skipDone) {
                pages.filter { p ->
                    val (pw, ph) = pageSizes[p] ?: (1f to 1f)
                    val q = PdfOcrCacheStore.ocrQuality(
                        ctx, activity.fileKey, p, pw, ph,
                    )
                    when (q) {
                        PdfOcrCacheStore.OcrQuality.COMPLETE -> {
                            skippedPages += p
                            false
                        }
                        PdfOcrCacheStore.OcrQuality.PARTIAL -> {
                            partialPages += p
                            true
                        }
                        else -> true
                    }
                }
            } else {
                pages
            }
            val skippedPre = skippedPages.size
            activity.logPdfOcr(
                "start range=${fromPage0 + 1}..${toPage0 + 1} skipDone=$skipDone " +
                    "pages=${pages.size} queue=${queue.size} skipped=$skippedPre " +
                    "partial=${partialPages.map { it + 1 }} " +
                    "skippedPages=${skippedPages.map { it + 1 }}",
            )
            if (queue.isEmpty()) {
                activity.showToast(R.string.pdf_ocr_done, 0, pages.size, 0)
                activity.mergeOcrCacheFromDisk()
                activity.rebuildTextFromCache(preserveTtsPosition = true)
                if (progressDlg.isShowing) progressDlg.dismiss()
                activity.refreshBottomChromeAfterModal("ocrEmpty")
                return@launch
            }

            progressTv.text = activity.getString(
                R.string.pdf_ocr_progress,
                0,
                queue.size,
                queue.first() + 1,
            )

            var ok = 0
            var fail = 0
            try {
                val eng = withContext(Dispatchers.Default) {
                    runCatching { activity.ocrEngine?.close() }
                    activity.ocrEngine = null
                    runCatching { activity.ocrCpuFallback?.close() }
                    activity.ocrCpuFallback = null
                    TfliteOcrEngine(
                        ctx,
                        TfliteOcrEngine.Backend.GPU,
                    ).also { activity.ocrEngine = it }
                }
                for ((i, page) in queue.withIndex()) {
                    if (!isActive || activity.isFinishing || activity.isDestroyed) break
                    progressTv.text = activity.getString(
                        R.string.pdf_ocr_progress,
                        i + 1,
                        queue.size,
                        page + 1,
                    )
                    val success = withContext(Dispatchers.IO) {
                        runCatching {
                            runner.ocrOnePage(page, eng)
                        }.onFailure {
                            ReaderLog.e(ReaderLog.Module.PDF, "ocr page $page", it)
                        }.isSuccess
                    }
                    if (success) ok++ else fail++
                }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                activity.mergeOcrCacheFromDisk()
                for (p in queue) {
                    val chars = PdfOcrCacheStore.loadPage(ctx, activity.fileKey, p)
                    if (chars != null) {
                        activity.textCache.rawPageCache[p] = chars
                    }
                }
                activity.rebuildTextFromCache(preserveTtsPosition = true)
                val msg = if (!isActive) {
                    activity.getString(R.string.pdf_ocr_cancelled, ok)
                } else {
                    activity.getString(R.string.pdf_ocr_done, ok, skippedPre, fail)
                }
                activity.logPdfOcr("done ok=$ok skipped=$skippedPre fail=$fail queue=${queue.size}")
                Toasts.show(ctx, msg)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    Toasts.show(ctx, activity.getString(R.string.pdf_ocr_cancelled, ok))
                } else {
                    ReaderLog.e(ReaderLog.Module.PDF, "ocr job", t)
                    Toasts.show(
                        ctx,
                        t.message ?: activity.getString(R.string.pdf_ocr_engine_fail),
                    )
                }
            } finally {
                if (progressDlg.isShowing) progressDlg.dismiss()
                activity.refreshBottomChromeAfterModal("ocrDone")
            }
        }
        activity.ocrJob = job
    }
}
