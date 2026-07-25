package com.whj.reader.pdf.tts

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.PdfReadingActivity
import com.whj.reader.R
import com.whj.reader.data.AppSettings
import com.whj.reader.data.PdfTextExtractor
import com.whj.reader.databinding.ActivityPdfReadingBinding
import com.whj.reader.databinding.PanelPdfTtsExportBinding
import com.whj.reader.model.PdfPageMode
import com.whj.reader.pdf.text.PdfTextCache
import com.whj.reader.pdf.text.PdfTextSelectionController
import com.whj.reader.tts.Mp3Encoder
import com.whj.reader.tts.TtsExportHelper
import com.whj.reader.tts.TtsManager
import com.whj.reader.tts.TtsSleepTimer
import com.whj.reader.ui.PdfPageSurface
import com.whj.reader.ui.TtsExportProgressDialog
import com.whj.reader.util.KeepScreenController
import com.whj.reader.util.ReaderLog
import com.whj.reader.util.Toasts
import com.whj.reader.util.TtsVoicePicker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * PDF TTS 控制器：朗读控制、底部 TTS 栏、MP3/WAV/M4A 导出面板、睡眠计时、TTS listener。
 * 直接持有 [com.whj.reader.PdfReadingActivity] 引用，访问页面信息、chrome 控制、文本抽取等能力。
 */
class PdfTtsController(
    private val activity: PdfReadingActivity,
) {

    private val ctx: Context get() = activity
    private val b: ActivityPdfReadingBinding get() = activity.binding
    private val ep: PanelPdfTtsExportBinding get() = activity.exportPanel
    private val tts: TtsManager get() = activity.tts
    private val sleepTimer: TtsSleepTimer get() = activity.sleepTimer
    private val textCache: PdfTextCache get() = activity.textCache
    private val textSelCtrl: PdfTextSelectionController get() = activity.textSelCtrl
    private val scope: CoroutineScope get() = activity.lifecycleScope

    private val exportBitrateOptions = intArrayOf(32, 48, 64, 96, 128, 160, 192)
    private val ttsRateOptions =
        floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)

    fun bindTtsCallbacks() {
        tts.onStateChanged = { snapshot ->
            activity.lifecycleScope.launch(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@launch
                updateTtsUi(snapshot)
                activity.keepScreen.onTtsStateChanged()
                if (snapshot.state == TtsManager.State.IDLE) {
                    activity.clearTtsHighlight()
                    b.pdfSelectionOverlay.clearHighlight()
                }
            }
        }
        tts.onSentenceHighlight = { paragraphIndex, startOffset, endOffset ->
            activity.lifecycleScope.launch(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@launch
                activity.applyTtsSentenceHighlight(paragraphIndex, startOffset, endOffset)
                prefetchNextPdfPagesForTts(paragraphIndex)
            }
        }
        tts.onError = { message ->
            activity.lifecycleScope.launch(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@launch
                if (isTtsInitNoise(message)) return@launch
                Toasts.show(ctx, message)
            }
        }
        tts.onNeedMoreContent = more@{ _ ->
            val maxCached = textCache.rawPageCache.keys.maxOrNull() ?: return@more false
            val next = maxCached + 1
            if (next >= activity.pageCount) return@more false
            activity.ensurePagesExtracted(
                pages = listOf(next, next + 1),
                showToast = false,
                preserveTtsPosition = true,
            ) { added ->
                if (activity.isFinishing || activity.isDestroyed) return@ensurePagesExtracted
                if (added) {
                    tts.continueAfterMoreContent()
                } else {
                    tts.finishWaitingNoMore()
                }
            }
            true
        }
    }

    fun setupTtsBar() {
        b.btnTtsPrev.setOnClickListener { tts.previousSentence() }
        b.btnTtsPlayPause.setOnClickListener {
            withTtsNotificationPermission {
                val snap = tts.currentState()
                if (snap.state == TtsManager.State.IDLE) {
                    startTtsFromViewport()
                } else {
                    tts.playPauseToggle()
                }
            }
        }
        b.btnTtsNext.setOnClickListener { tts.nextSentence() }
        b.btnTtsStop.setOnClickListener {
            tts.stop()
            sleepTimer.cancel()
            updateSleepUi()
            activity.ttsBarOpen = false
            activity.applyChromeVisibility()
            activity.syncPdfContentBottomInset()
        }
        b.btnTtsRate.setOnClickListener { v -> showTtsRateMenu(v) }
        b.btnTtsSleep.setOnClickListener { v -> showTtsSleepMenu(v) }
        b.tvTtsSleepCountdown.setOnClickListener { confirmCancelSleepTimer() }
        b.btnVoice.setOnClickListener { showVoicePicker() }
        updateTtsRateLabel(AppSettings.ttsRate(ctx))
        updateSleepUi()
    }

    fun setupPdfExportPanel() {
        ep.btnExportClose.setOnClickListener { closePdfExportPanel() }
        ep.btnExportVoice.setOnClickListener {
            TtsVoicePicker.show(activity, tts) { refreshPdfExportVoiceLabel() }
        }
        ep.btnPageAll.setOnClickListener { setPdfExportAllPages() }
        ep.btnStartExport.setOnClickListener { startPdfPageExport() }
        ep.btnCancelExport.setOnClickListener { activity.ttsExport?.cancel() }
        val labels = exportBitrateOptions.map {
            activity.getString(R.string.tts_export_bitrate_kbps, it)
        }
        ep.spExportBitrate.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        val saved = AppSettings.ttsExportBitrateKbps(ctx)
        val idx = exportBitrateOptions.indexOf(saved).takeIf { it >= 0 }
            ?: exportBitrateOptions.indexOf(64).coerceAtLeast(0)
        ep.spExportBitrate.setSelection(idx)
        val mp3Ok = Mp3Encoder.isAvailable()
        ep.rbFormatMp3.isEnabled = mp3Ok
        if (mp3Ok) {
            ep.rbFormatMp3.isChecked = true
        } else {
            ep.rbFormatMp3.alpha = 0.45f
            ep.rbFormatM4a.isChecked = true
        }
        ep.rgExportFormat.setOnCheckedChangeListener { _, _ ->
            refreshPdfExportBitrateEnabled()
        }
        fun onPageEdit() = updatePdfExportRangeLabel()
        ep.etPageFrom.setOnFocusChangeListener { _, has -> if (!has) onPageEdit() }
        ep.etPageTo.setOnFocusChangeListener { _, has -> if (!has) onPageEdit() }
        refreshPdfExportVoiceLabel()
        refreshPdfExportBitrateEnabled()
    }

    fun openPdfExportPanel() {
        tts.stop()
        activity.chromeVisible = false
        activity.ttsBarOpen = false
        activity.exportPanelOpen = true
        b.readMenuHost.visibility = View.GONE
        b.ttsBar.isVisible = false
        b.topBar.isVisible = false
        val cur = (activity.currentVisiblePage() + 1).coerceAtLeast(1)
        val max = activity.pageCount.coerceAtLeast(1)
        ep.etPageFrom.setText(cur.toString())
        ep.etPageTo.setText(max.toString())
        updatePdfExportRangeLabel()
        refreshPdfExportVoiceLabel()
        setPdfExportProgressUi(active = false)
        activity.applyChromeVisibility()
    }

    fun startPdfTts() {
        val pages = pagesForTtsStart()
        activity.ensurePagesExtracted(
            pages = pages,
            showToast = true,
            preserveTtsPosition = false,
        ) { _ ->
            if (textCache.paragraphs.isEmpty()) {
                exitTtsWithMessage(R.string.pdf_tts_unavailable)
            } else {
                Toasts.show(
                    ctx,
                    activity.getString(R.string.pdf_tts_ready, textCache.paragraphs.size),
                )
                openTtsAndPlay()
            }
        }
    }

    fun startTtsFromSelection() {
        if (!activity.hasTextSelection()) return
        val state = textSelCtrl.state
        val page = state.startPage
        val charIdx = state.startChar
        activity.chromeVisible = false
        activity.ttsBarOpen = true
        activity.applyChromeVisibility()
        activity.ensurePagesExtracted(
            pages = activity.pagesNear(page, before = 1, after = 2),
            showToast = true,
            preserveTtsPosition = false,
        ) {
            if (activity.isFinishing || activity.isDestroyed) return@ensurePagesExtracted
            activity.clampSelectionToLoadedChars()
            val p = page.coerceIn(0, (activity.pageCount - 1).coerceAtLeast(0))
            val c = textCache.pageChars[p]?.let { chars ->
                if (chars.isEmpty()) charIdx
                else charIdx.coerceIn(chars.minOf { it.indexOnPage }, chars.maxOf { it.indexOnPage })
            } ?: charIdx
            val mapped = activity.mapPageCharToParaOffset(p, c)
            val link = mapped?.let { textCache.paraLinks.getOrNull(it.first) }
            ReaderLog.i(
                ReaderLog.Module.PDF_SELECT,
                "ttsFromSel page=$p char=$c mapped=$mapped " +
                    "linkPage=${link?.pageIndex} paras=${textCache.paragraphs.size} " +
                    "linksOnPage=${textCache.paraLinks.count { it.pageIndex == p }}",
            )
            if (mapped == null || link == null || link.pageIndex != p) {
                activity.showToast(R.string.pdf_tts_sel_map_fail)
                activity.clearTextSelection()
                return@ensurePagesExtracted
            }
            tts.setDocument(textCache.paragraphs)
            tts.setSessionTitle(activity.displayTitle)
            if (!tts.isReady()) {
                tts.reinit()
                updateTtsUi(tts.currentState())
            }
            tts.playFromParagraphOffset(mapped.first, mapped.second)
            activity.clearTextSelection()
        }
    }

    fun withTtsNotificationPermission(then: () -> Unit) {
        if (TtsManager.hasNotificationPermission(ctx)) {
            then()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            activity.pendingTtsAfterNotif = then
            activity.ttsNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            then()
        }
    }

    fun startTtsFromViewport() {
        if (textCache.paragraphs.isEmpty() || textCache.paraLinks.isEmpty()) {
            exitTtsWithMessage(R.string.pdf_tts_unavailable)
            return
        }
        if (!activity.currentPageHasText()) {
            exitTtsWithMessage(R.string.pdf_tts_page_no_text)
            return
        }
        val pos = findFirstFullyVisiblePdfChar()
        if (pos == null) {
            val page = activity.currentVisiblePage()
            val para = textCache.paraLinks.indexOfFirst { it.pageIndex == page }
            if (para < 0) {
                exitTtsWithMessage(R.string.pdf_tts_page_no_text)
                return
            }
            tts.playFromParagraphOffset(para, 0)
            return
        }
        val (paraIdx, charOffInPara) = pos
        tts.playFromParagraphOffset(paraIdx, charOffInPara)
    }

    private fun findFirstFullyVisiblePdfChar(): Pair<Int, Int>? {
        if (textCache.pageChars.isEmpty() || textCache.paraLinks.isEmpty()) return null
        when (activity.pageMode) {
            PdfPageMode.SINGLE -> {
                val page = activity.pageIndex
                val chars = textCache.pageChars[page] ?: return null
                val first = chars.firstOrNull { !it.char.isWhitespace() } ?: return null
                return activity.mapPageCharToParaOffset(page, first.indexOnPage)
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = b.rvPdfPages
                val lm = rv.layoutManager as? LinearLayoutManager ?: return null
                val firstPos = lm.findFirstVisibleItemPosition()
                if (firstPos == RecyclerView.NO_POSITION) return null
                val lastPos = lm.findLastVisibleItemPosition().coerceAtLeast(firstPos)
                val viewportTop = 0f
                val viewportBottom = rv.height.toFloat()
                for (pos in firstPos..lastPos) {
                    val child = lm.findViewByPosition(pos) ?: continue
                    val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: continue
                    val chars = textCache.pageChars[pos] ?: continue
                    val itemTop = child.top.toFloat()
                    val itemBottom = child.bottom.toFloat()
                    val fullyInView =
                        itemTop >= viewportTop - 2f && itemBottom <= viewportBottom + 2f
                    val sorted = chars.filter { !it.char.isWhitespace() }
                        .sortedWith(compareBy({ it.top }, { it.left }))
                    for (c in sorted) {
                        val local = surface.pageRectToView(RectF(c.left, c.top, c.right, c.bottom))
                        val rect = RectF(
                            child.left + surface.left + local.left,
                            child.top + surface.top + local.top,
                            child.left + surface.left + local.right,
                            child.top + surface.top + local.bottom,
                        )
                        if (rect.top >= viewportTop - 1f && rect.bottom <= viewportBottom + 1f) {
                            return activity.mapPageCharToParaOffset(pos, c.indexOnPage)
                        }
                    }
                    if (itemTop >= viewportTop - 2f && sorted.isNotEmpty()) {
                        return activity.mapPageCharToParaOffset(pos, sorted.first().indexOnPage)
                    }
                    if (fullyInView && sorted.isNotEmpty()) {
                        return activity.mapPageCharToParaOffset(pos, sorted.first().indexOnPage)
                    }
                }
                return null
            }
        }
    }

    private fun pagesForTtsStart(anchorPage: Int = activity.currentVisiblePage()): List<Int> =
        activity.pagesNear(anchorPage, before = 1, after = 2)

    private fun prefetchNextPdfPagesForTts(paragraphIndex: Int) {
        val link = textCache.paraLinks.getOrNull(paragraphIndex) ?: return
        activity.prefetchNearbyText(link.pageIndex)
    }

    private fun exitTtsWithMessage(msgRes: Int) {
        tts.stop()
        sleepTimer.cancel()
        updateSleepUi()
        activity.ttsBarOpen = false
        activity.chromeVisible = false
        activity.applyChromeVisibility()
        activity.syncPdfContentBottomInset()
        activity.showToast(msgRes)
    }

    private fun openTtsAndPlay() {
        if (!activity.currentPageHasText()) {
            exitTtsWithMessage(R.string.pdf_tts_page_no_text)
            return
        }
        activity.chromeVisible = false
        activity.ttsBarOpen = true
        activity.applyChromeVisibility()
        b.ttsBar.post { activity.syncPdfContentBottomInset() }
        withTtsNotificationPermission {
            if (!tts.isReady()) {
                tts.reinit()
                updateTtsUi(tts.currentState())
            }
            val snap = tts.currentState()
            if (snap.state == TtsManager.State.IDLE) {
                startTtsFromViewport()
            } else {
                tts.playPauseToggle()
            }
        }
    }

    private fun confirmCancelSleepTimer() {
        if (!sleepTimer.isActive()) {
            showTtsSleepMenu(b.btnTtsSleep)
            return
        }
        AlertDialog.Builder(ctx)
            .setMessage(R.string.tts_sleep_cancel_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                sleepTimer.cancel()
                updateSleepUi()
                activity.showToast(R.string.tts_sleep_cancelled)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTtsRateMenu(anchor: View) {
        val popup = android.widget.PopupMenu(ctx, anchor)
        ttsRateOptions.forEachIndexed { i, rate ->
            popup.menu.add(0, i, i, formatRateLabel(rate))
        }
        popup.setOnMenuItemClickListener { item ->
            val rate = ttsRateOptions.getOrNull(item.itemId)
                ?: return@setOnMenuItemClickListener false
            AppSettings.setTtsRate(ctx, rate)
            tts.setSpeechRate(rate, restartCurrent = true)
            updateTtsRateLabel(rate)
            true
        }
        popup.show()
    }

    private fun formatRateLabel(rate: Float): String {
        val body = if (kotlin.math.abs(rate - rate.toInt()) < 0.001f) {
            rate.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", rate).trimEnd('0').trimEnd('.')
        }
        return "$body×"
    }

    private fun updateTtsRateLabel(rate: Float) {
        b.btnTtsRate.text = formatRateLabel(rate)
    }

    private fun showTtsSleepMenu(anchor: View) {
        val popup = android.widget.PopupMenu(ctx, anchor)
        TtsSleepTimer.OPTION_MINUTES.forEachIndexed { i, mins ->
            val title = if (mins == 0) {
                activity.getString(R.string.tts_sleep_off)
            } else {
                activity.getString(R.string.tts_sleep_minutes, mins)
            }
            popup.menu.add(0, i, i, title)
        }
        popup.setOnMenuItemClickListener { item ->
            val mins = TtsSleepTimer.OPTION_MINUTES
                .getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            if (mins == 0) {
                sleepTimer.cancel()
                updateSleepUi()
                activity.showToast(R.string.tts_sleep_cancelled)
            } else {
                sleepTimer.start(mins * 60_000L)
                updateSleepUi()
                activity.showToast(R.string.tts_sleep_set, mins)
            }
            true
        }
        popup.show()
    }

    fun updateSleepUi() {
        val active = sleepTimer.isActive()
        b.btnTtsSleep.isVisible = !active
        b.tvTtsSleepCountdown.isVisible = active
        if (active) {
            b.tvTtsSleepCountdown.text =
                TtsSleepTimer.formatCountdown(sleepTimer.remainingMs())
        }
    }

    fun onSleepTimerFinished() {
        if (activity.isFinishing || activity.isDestroyed) return
        tts.stop()
        updateSleepUi()
        updateTtsUi(tts.currentState())
        activity.showToast(R.string.tts_sleep_finished)
    }

    private fun showVoicePicker() {
        TtsVoicePicker.show(activity, tts) {
            if (tts.currentState().state == TtsManager.State.SPEAKING) {
                val snap = tts.currentState()
                tts.playFrom(snap.paragraphIndex, snap.sentenceIndex)
            }
        }
    }

    private fun isTtsInitNoise(message: String): Boolean {
        if (message.isBlank()) return false
        return message == activity.getString(R.string.tts_init_failed) ||
            message == activity.getString(R.string.tts_initializing) ||
            message == activity.getString(R.string.tts_init_pending) ||
            message == activity.getString(R.string.tts_still_not_ready) ||
            message == activity.getString(R.string.tts_not_ready) ||
            message == activity.getString(R.string.tts_reinit) ||
            message.startsWith(activity.getString(R.string.tts_init_failed))
    }

    fun updateTtsUi(snapshot: TtsManager.Snapshot) {
        activity.applyChromeVisibility()
        when (snapshot.state) {
            TtsManager.State.SPEAKING -> {
                b.btnTtsPlayPause.setImageResource(R.drawable.ic_pause)
                b.tvTtsStatus.text = activity.getString(R.string.tts_speaking)
            }
            TtsManager.State.PAUSED -> {
                b.btnTtsPlayPause.setImageResource(R.drawable.ic_play)
                b.tvTtsStatus.text = activity.getString(R.string.tts_paused)
            }
            TtsManager.State.IDLE -> {
                b.btnTtsPlayPause.setImageResource(R.drawable.ic_play)
                b.tvTtsStatus.text = when {
                    !snapshot.ready -> snapshot.statusMessage.ifBlank {
                        activity.getString(R.string.tts_not_ready)
                    }
                    else -> activity.getString(R.string.tts_idle)
                }
            }
        }
    }

    fun closePdfExportPanel() {
        if (activity.ttsExport?.isWorking() == true) activity.ttsExport?.cancel()
        activity.exportPanelOpen = false
        setPdfExportProgressUi(active = false)
        activity.applyChromeVisibility()
    }

    private fun setPdfExportAllPages() {
        val max = activity.pageCount.coerceAtLeast(1)
        ep.etPageFrom.setText("1")
        ep.etPageTo.setText(max.toString())
        updatePdfExportRangeLabel()
    }

    private fun parsePdfExportRange(): Pair<Int, Int>? {
        if (activity.pageCount <= 0) return null
        val from1 = ep.etPageFrom.text?.toString()?.toIntOrNull() ?: return null
        val to1 = ep.etPageTo.text?.toString()?.toIntOrNull() ?: return null
        var a = from1.coerceIn(1, activity.pageCount)
        var b = to1.coerceIn(1, activity.pageCount)
        if (a > b) {
            val t = a; a = b; b = t
        }
        return (a - 1) to (b - 1)
    }

    private fun updatePdfExportRangeLabel() {
        val range = parsePdfExportRange()
        if (range == null) {
            ep.tvExportRange.text =
                activity.getString(R.string.pdf_tts_export_invalid_pages, activity.pageCount.coerceAtLeast(1))
            return
        }
        val (from0, to0) = range
        val n = to0 - from0 + 1
        var chars = 0
        for (p in from0..to0) {
            chars += textCache.pageChars[p]?.count { !it.char.isWhitespace() }
                ?: textCache.rawPageCache[p]?.count { !it.char.isWhitespace() }
                ?: 0
        }
        ep.tvExportRange.text = activity.getString(
            R.string.pdf_tts_export_range,
            from0 + 1,
            to0 + 1,
            n,
            chars,
        )
    }

    private fun refreshPdfExportVoiceLabel() {
        val voice = if (activity.isTtsReady()) tts.currentVoiceName() else null
        ep.tvExportVoice.text = voice
            ?: AppSettings.voiceName(ctx)
            ?: activity.getString(R.string.tts_voice)
    }

    private fun refreshPdfExportBitrateEnabled() {
        val need = ep.rbFormatMp3.isChecked || ep.rbFormatM4a.isChecked
        ep.spExportBitrate.isEnabled = need
        ep.tvBitrateLabel.alpha = if (need) 1f else 0.4f
        ep.spExportBitrate.alpha = if (need) 1f else 0.4f
    }

    private fun selectedPdfExportBitrateKbps(): Int {
        val pos = ep.spExportBitrate.selectedItemPosition
        return exportBitrateOptions.getOrNull(pos)
            ?: AppSettings.ttsExportBitrateKbps(ctx)
    }

    private fun startPdfPageExport() {
        val range = parsePdfExportRange()
        if (range == null) {
            Toasts.show(
                ctx,
                activity.getString(R.string.pdf_tts_export_invalid_pages, activity.pageCount.coerceAtLeast(1)),
            )
            return
        }
        if (activity.ttsExport?.isWorking() == true) return
        val (from0, to0) = range
        val pages = (from0..to0).toList()
        ep.tvExportProgress.isVisible = true
        ep.tvExportProgress.text = activity.getString(R.string.pdf_tts_export_extracting)
        activity.ensurePagesExtracted(
            pages = pages,
            showToast = false,
            preserveTtsPosition = true,
        ) { _ ->
            if (activity.isFinishing || activity.isDestroyed) return@ensurePagesExtracted
            val text = buildExportTextForPages(from0, to0)
            if (text.isBlank()) {
                setPdfExportProgressUi(active = false)
                activity.showToast(R.string.pdf_tts_export_no_text)
                return@ensurePagesExtracted
            }
            var format = when {
                ep.rbFormatWav.isChecked -> TtsExportHelper.Format.WAV
                ep.rbFormatMp3.isChecked -> TtsExportHelper.Format.MP3
                else -> TtsExportHelper.Format.M4A
            }
            if (format == TtsExportHelper.Format.MP3 && !Mp3Encoder.isAvailable()) {
                format = TtsExportHelper.Format.M4A
                ep.rbFormatM4a.isChecked = true
                activity.showToast(R.string.tts_export_mp3_unsupported)
            }
            val kbps = selectedPdfExportBitrateKbps()
            AppSettings.setTtsExportBitrateKbps(ctx, kbps)
            val helper = TtsExportHelper(ctx).also { activity.ttsExport = it }
            setPdfExportProgressUi(active = true, done = 0, total = 1)
            val dlg = TtsExportProgressDialog(activity) {
                helper.cancel()
            }.also { activity.exportProgressDlg = it }
            dlg.show()
            activity.addWindowFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            helper.export(
                text = text,
                format = format,
                filePrefix = "pdf",
                bitRateKbps = kbps,
                callbacks = TtsExportHelper.Callbacks(
                    onProgress = progress@{ done, total, phase, doneChars, totalChars, partFraction ->
                        if (activity.isFinishing || activity.isDestroyed) return@progress
                        val t = total.coerceAtLeast(1)
                        val cur =
                            if (phase == "synth" && done < t) done + 1 else done.coerceAtMost(t)
                        val label = when (phase) {
                            "prepare", "init" -> activity.getString(R.string.tts_export_phase_prepare)
                            "encode" -> activity.getString(R.string.tts_export_encoding)
                            "merge" -> activity.getString(R.string.tts_export_phase_merge)
                            else -> activity.getString(R.string.tts_export_progress, cur, t)
                        }
                        val pct = pdfExportProgressPercent(
                            done, t, phase, doneChars, totalChars, partFraction,
                        )
                        setPdfExportProgressUi(true, pct, 100, label)
                        activity.exportProgressDlg?.update(
                            done, total, phase, doneChars, totalChars, partFraction,
                        )
                    },
                    onSuccess = ok@{ file ->
                        if (activity.isFinishing || activity.isDestroyed) return@ok
                        activity.clearWindowFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        dismissExportProgressDlg()
                        setPdfExportProgressUi(false)
                        activity.showToastLong(
                            activity.getString(R.string.tts_export_ok, file.name),
                        )
                        sharePdfExportAudio(file)
                    },
                    onError = err@{ message ->
                        if (activity.isFinishing || activity.isDestroyed) return@err
                        activity.clearWindowFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        dismissExportProgressDlg()
                        setPdfExportProgressUi(false)
                        activity.showToastLong(
                            activity.getString(R.string.tts_export_fail, message),
                        )
                    },
                    onCancelled = cancel@{
                        if (activity.isFinishing || activity.isDestroyed) return@cancel
                        activity.clearWindowFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        dismissExportProgressDlg()
                        setPdfExportProgressUi(false)
                        activity.showToast(R.string.tts_export_cancelled)
                    },
                ),
            )
        }
    }

    fun dismissExportProgressDlg() {
        activity.exportProgressDlg?.dismiss()
        activity.exportProgressDlg = null
    }

    private fun pdfExportProgressPercent(
        done: Int,
        total: Int,
        phase: String,
        doneChars: Int,
        totalChars: Int,
        partFraction: Float,
    ): Int {
        return when (phase) {
            "prepare", "init" -> 1
            "merge" -> 94
            "encode" -> 98
            else -> {
                if (totalChars > 0) {
                    ((doneChars.toFloat() / totalChars) * 92f).toInt().coerceIn(0, 92)
                } else {
                    val t = total.coerceAtLeast(1)
                    val base = (done.toFloat() / t) * 92f
                    val within =
                        if (done < t) partFraction.coerceIn(0f, 1f) * (92f / t) else 0f
                    (base + within).toInt().coerceIn(0, 92)
                }
            }
        }
    }

    private fun buildExportTextForPages(from0: Int, to0: Int): String {
        val sb = StringBuilder()
        if (textCache.paraLinks.isNotEmpty() && textCache.paragraphs.isNotEmpty()) {
            for (i in textCache.paraLinks.indices) {
                val link = textCache.paraLinks[i]
                if (link.pageIndex !in from0..to0) continue
                val t = textCache.paragraphs.getOrNull(i)?.text?.trim().orEmpty()
                if (t.isEmpty()) continue
                sb.append(ensurePdfExportSentenceEnd(t))
            }
        }
        if (sb.isNotEmpty()) return sb.toString()
        for (p in from0..to0) {
            val chars = textCache.pageChars[p] ?: textCache.rawPageCache[p] ?: continue
            val pageText = chars.joinToString("") { it.char.toString() }.trim()
            if (pageText.isEmpty()) continue
            sb.append(ensurePdfExportSentenceEnd(pageText))
        }
        return sb.toString()
    }

    private fun ensurePdfExportSentenceEnd(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return ""
        var i = t.lastIndex
        while (i >= 0 && t[i] in "\"'”’」』》〉）)]｝}") i--
        if (i < 0) return "$t。"
        if (t[i] in "。！？.!?;；…‥~～") return t
        return "$t。"
    }

    private fun setPdfExportProgressUi(
        active: Boolean,
        done: Int = 0,
        total: Int = 1,
        label: String? = null,
    ) {
        ep.progressExport.isVisible = active
        ep.tvExportProgress.isVisible = active
        ep.btnCancelExport.isVisible = active
        ep.btnStartExport.isEnabled = !active
        ep.etPageFrom.isEnabled = !active
        ep.etPageTo.isEnabled = !active
        ep.btnPageAll.isEnabled = !active
        val needBitrate = ep.rbFormatMp3.isChecked || ep.rbFormatM4a.isChecked
        ep.spExportBitrate.isEnabled = !active && needBitrate
        ep.rbFormatMp3.isEnabled = !active && Mp3Encoder.isAvailable()
        ep.rbFormatM4a.isEnabled = !active
        ep.rbFormatWav.isEnabled = !active
        if (active) {
            val t = total.coerceAtLeast(1)
            ep.progressExport.max = t
            ep.progressExport.progress = done.coerceIn(0, t)
            ep.tvExportProgress.text = label
                ?: activity.getString(R.string.tts_export_progress, done, t)
        }
    }

    private fun sharePdfExportAudio(file: File) {
        val uri = FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            file,
        )
        activity.shareIntent(uri, "audio/*", R.string.tts_export_share)
    }
}
