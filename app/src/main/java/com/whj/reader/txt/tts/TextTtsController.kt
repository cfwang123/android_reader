package com.whj.reader.txt.tts
import com.whj.reader.ReadingActivity

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.whj.reader.R
import com.whj.reader.data.AppSettings
import com.whj.reader.data.BookChapterPatternStore
import com.whj.reader.data.BookChineseModeStore
import com.whj.reader.data.BookEncodingStore
import com.whj.reader.data.BookFileType
import com.whj.reader.data.BookLoader
import com.whj.reader.data.BookNotesFileStore
import com.whj.reader.data.BookmarkStore
import com.whj.reader.data.BookshelfStore
import com.whj.reader.data.ChineseConvert
import com.whj.reader.data.CustomChapterScanner
import com.whj.reader.data.CustomFontStore
import com.whj.reader.data.LoadedBook
import com.whj.reader.data.ReadingProgressStore
import com.whj.reader.data.TextLoader
import com.whj.reader.databinding.ActivityReadingBinding
import com.whj.reader.databinding.PanelReadMenuBinding
import com.whj.reader.databinding.PanelReadSettingsBinding
import com.whj.reader.databinding.PanelTtsExportBinding
import com.whj.reader.databinding.SheetTocBinding
import com.whj.reader.model.BookNotesDocument
import com.whj.reader.model.EdgeSwipeAction
import com.whj.reader.model.Highlight
import com.whj.reader.model.HighlightColorPresets
import com.whj.reader.model.HighlightKind
import com.whj.reader.model.HighlightMode
import com.whj.reader.model.HighlightStyle
import com.whj.reader.model.OrientationMode
import com.whj.reader.model.ReadStyle
import com.whj.reader.model.ReadTheme
import com.whj.reader.model.TextAnchor
import com.whj.reader.tts.Mp3Encoder
import com.whj.reader.tts.TtsExportHelper
import com.whj.reader.tts.TtsManager
import com.whj.reader.ui.AppTheme
import com.whj.reader.ui.HighlightNotePopup
import com.whj.reader.ui.HsvColorPickerDialog
import com.whj.reader.ui.ParagraphAdapter
import com.whj.reader.ui.TocAdapter
import com.whj.reader.ui.TocItem
import com.whj.reader.ui.TocVpScrollHelper
import com.whj.reader.ui.TtsExportProgressDialog
import com.whj.reader.ui.VirtualReaderView
import com.whj.reader.util.BgTextures
import com.whj.reader.util.KeepScreenController
import com.whj.reader.util.OpenFailGuide
import com.whj.reader.util.OrientationHelper
import com.whj.reader.util.ReaderFonts
import com.whj.reader.util.ReaderLog
import com.whj.reader.util.StorageAccess
import com.whj.reader.util.Toasts
import com.whj.reader.util.TtsVoicePicker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext



/**
 * TXT reading tts controller (extracted from ReadingActivity).
 */
class TextTtsController(
    private val activity: ReadingActivity,
) {

    private val b get() = activity.binding
    private val reader get() = activity.reader
    private val readMenu get() = activity.readMenu
    private val exportPanel get() = activity.exportPanel
    private val settingsPanel get() = activity.settingsPanel
    private val tts get() = activity.tts
    private val ctx get() = activity


    var ttsExport: com.whj.reader.tts.TtsExportHelper? = null
    var exportProgressDlg: com.whj.reader.ui.TtsExportProgressDialog? = null
    internal enum class ExportPickMode { NONE, START, END }
    internal var exportPickMode = ExportPickMode.NONE
    var exportStartPara = -1
    var exportEndPara = -1
    private val exportBitrateOptions = intArrayOf(32, 48, 64, 96, 128, 160, 192)
    private val ttsRateOptions = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)
    val sleepTimer = com.whj.reader.tts.TtsSleepTimer(
        onTick = { left ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                b.tvTtsSleepCountdown.text =
                    com.whj.reader.tts.TtsSleepTimer.formatCountdown(left)
            }
        },
        onFinished = { onSleepTimerFinished() },
    )

    fun bindTtsCallbacks() {
        tts.onStateChanged = { snapshot ->
            activity.runOnUiThread {
                if (!activity.isReaderReady() || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (snapshot.state == TtsManager.State.IDLE) {
                    reader.clearHighlight()
                }
                updateTtsUi(snapshot)
                if (activity.isKeepScreenReady()) activity.keepScreen.onTtsStateChanged()
            }
        }
        tts.onSentenceHighlight = { paragraphIndex, startOffset, endOffset ->
            activity.runOnUiThread {
                if (!activity.isReaderReady() || !activity.isTtsReady() || activity.isFinishing || activity.isDestroyed) {
                    return@runOnUiThread
                }
                val st = tts.currentState().state
                if (paragraphIndex < 0 || endOffset < 0 ||
                    st == TtsManager.State.IDLE
                ) {
                    reader.clearHighlight()
                    return@runOnUiThread
                }
                if (st == TtsManager.State.SPEAKING || st == TtsManager.State.PAUSED) {
                    reader.setHighlightRange(paragraphIndex, startOffset, endOffset)
                } else {
                    reader.clearHighlight()
                }
                if (AppSettings.autoScroll(activity) &&
                    st != TtsManager.State.IDLE
                ) {
                    // ä¸ä¸å¥æªå®å¨å¨å±å?â?ç¿»å°å¥é¦æ­£å¥½è´´æ­£æåºé¡¶ï¼å¨å¨å±åä¸å¨
                    // TTS æ¡é«åº¦è®¡å¥ä¸å¯è§å?
                    reader.post {
                        if (!activity.isReaderReady() || activity.isFinishing || activity.isDestroyed) return@post
                        activity.chromeController.syncReaderBottomObscured()
                        reader.scrollToHighlightIfNeeded(
                            paragraphIndex,
                            startOffset,
                            endOffset,
                        )
                    }
                }
                activity.saveProgress(paragraphIndex)
            }
        }
        tts.onError = { message ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                // åå§åå¤±è´?è¿è¡ä¸­ï¼ä»?UI ç¶æï¼ä¸?Toast
                if (isTtsInitNoise(message)) return@runOnUiThread
                Toasts.show(activity, message)
            }
        }
    }


    fun setupExportPanel() {
        exportPanel.btnExportClose.setOnClickListener { closeExportPanel() }
        exportPanel.btnExportVoice.setOnClickListener {
            TtsVoicePicker.show(activity, tts) {
                refreshExportVoiceLabel()
            }
        }
        exportPanel.btnPickStart.setOnClickListener {
            exportPickMode = ExportPickMode.START
            reader.paragraphPickEnabled = true
            exportPanel.tvExportHint.text = activity.getString(R.string.tts_export_pick_start_hint)
            Toasts.show(activity, R.string.tts_export_pick_start_hint)
        }
        exportPanel.btnPickEnd.setOnClickListener {
            exportPickMode = ExportPickMode.END
            reader.paragraphPickEnabled = true
            exportPanel.tvExportHint.text = activity.getString(R.string.tts_export_pick_end_hint)
            Toasts.show(activity, R.string.tts_export_pick_end_hint)
        }
        exportPanel.btnPickAll.setOnClickListener {
            selectExportAll()
            Toasts.show(activity, R.string.tts_export_all_set)
        }
        exportPanel.btnStartExport.setOnClickListener { startRangeExport() }
        exportPanel.btnCancelExport.setOnClickListener {
            ttsExport?.cancel()
        }
        setupExportBitrateSpinner()
        setupExportFormatOptions()
        exportPanel.rgExportFormat.setOnCheckedChangeListener { _, _ ->
            refreshExportBitrateEnabled()
        }
        refreshExportVoiceLabel()
        updateExportRangeUi()
        refreshExportBitrateEnabled()
    }


    fun setupExportFormatOptions() {
        val mp3Ok = Mp3Encoder.isAvailable()
        exportPanel.rbFormatMp3.isEnabled = mp3Ok
        if (mp3Ok) {
            exportPanel.rbFormatMp3.isChecked = true
        } else {
            exportPanel.rbFormatMp3.alpha = 0.45f
            exportPanel.rbFormatM4a.isChecked = true
        }
    }


    fun setupExportBitrateSpinner() {
        val labels = exportBitrateOptions.map {
            activity.getString(R.string.tts_export_bitrate_kbps, it)
        }
        exportPanel.spExportBitrate.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        val saved = AppSettings.ttsExportBitrateKbps(activity)
        val idx = exportBitrateOptions.indexOf(saved).takeIf { it >= 0 }
            ?: exportBitrateOptions.indexOf(64).coerceAtLeast(0)
        exportPanel.spExportBitrate.setSelection(idx)
        exportPanel.spExportBitrate.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val kbps = exportBitrateOptions.getOrNull(position) ?: 64
                    AppSettings.setTtsExportBitrateKbps(activity, kbps)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }


    fun refreshExportBitrateEnabled() {
        if (!activity.isExportPanelReady()) return
        // ç çå¯?MP3 / M4A ææï¼WAV å¿½ç¥
        val needBitrate = exportPanel.rbFormatMp3.isChecked || exportPanel.rbFormatM4a.isChecked
        exportPanel.spExportBitrate.isEnabled = needBitrate
        exportPanel.tvBitrateLabel.alpha = if (needBitrate) 1f else 0.4f
        exportPanel.spExportBitrate.alpha = if (needBitrate) 1f else 0.4f
    }


    fun selectedExportBitrateKbps(): Int {
        val pos = exportPanel.spExportBitrate.selectedItemPosition
        return exportBitrateOptions.getOrNull(pos)
            ?: AppSettings.ttsExportBitrateKbps(activity)
    }


    fun openExportPanel() {
        // éæ¾æè¯»å ç¨ç?TTS å¼æï¼é¿åä¸åæå®ä¾æ¢å¼æå¯¼è´å¡æ­?
        if (activity.isTtsReady()) {
            tts.stop()
        }
        activity.chromeVisible = false
        activity.ttsBarOpen = false
        activity.exportPanelOpen = true
        exportPickMode = ExportPickMode.NONE
        reader.paragraphPickEnabled = false
        // é»è®¤å¨æ
        selectExportAll()
        refreshExportVoiceLabel()
        setExportProgressUi(active = false)
        activity.chromeController.applyChromeVisibility()
        exportPanel.tvExportHint.text = activity.getString(R.string.tts_export_hint)
    }

    /** éæ©å¨ä¹¦ï¼é»è®¤ï¼ */

    fun selectExportAll() {
        exportPickMode = ExportPickMode.NONE
        reader.paragraphPickEnabled = false
        val last = activity.book?.paragraphs?.lastIndex ?: -1
        if (last < 0) {
            exportStartPara = -1
            exportEndPara = -1
        } else {
            exportStartPara = 0
            exportEndPara = last
        }
        exportPanel.tvExportHint.text = activity.getString(R.string.tts_export_hint)
        updateExportRangeUi()
    }


    fun closeExportPanel() {
        if (ttsExport?.isWorking() == true) {
            ttsExport?.cancel()
        }
        activity.exportPanelOpen = false
        exportPickMode = ExportPickMode.NONE
        reader.paragraphPickEnabled = false
        // å³é­æ¶å»ææ­£æèå´é«äº®ä¸éåºç¶æ?
        exportStartPara = -1
        exportEndPara = -1
        if (activity.isReaderReady()) {
            reader.clearExportRangeHighlight()
        }
        if (activity.isExportPanelReady()) {
            exportPanel.tvExportRange.text = activity.getString(R.string.tts_export_range_none)
            exportPanel.tvExportHint.text = activity.getString(R.string.tts_export_hint)
            setExportProgressUi(active = false)
        }
        activity.chromeController.applyChromeVisibility()
    }


    fun onExportParagraphPicked(para: Int) {
        val last = activity.book?.paragraphs?.lastIndex ?: return
        val p = para.coerceIn(0, last)
        when (exportPickMode) {
            ExportPickMode.START -> {
                exportStartPara = p
                if (exportEndPara < 0) exportEndPara = p
                Toasts.show(activity, activity.getString(R.string.tts_export_start_set, p + 1))
            }
            ExportPickMode.END -> {
                exportEndPara = p
                if (exportStartPara < 0) exportStartPara = p
                Toasts.show(activity, activity.getString(R.string.tts_export_end_set, p + 1))
            }
            ExportPickMode.NONE -> return
        }
        exportPickMode = ExportPickMode.NONE
        reader.paragraphPickEnabled = false
        exportPanel.tvExportHint.text = activity.getString(R.string.tts_export_hint)
        normalizeExportRange()
        updateExportRangeUi()
    }


    fun normalizeExportRange() {
        if (exportStartPara >= 0 && exportEndPara >= 0 && exportStartPara > exportEndPara) {
            val t = exportStartPara
            exportStartPara = exportEndPara
            exportEndPara = t
        }
    }

    /**
     * åæç¨ææ¬ï¼èå´åæ¯æ®µï¼ä¸è¡ï¼åç¬å¤çï¼?
     * æ®µæ«è¥æ å¥è¯»åé¡¿æ ç¹ï¼èªå¨è¡¥ãããã?
     */

    fun buildExportSpeechText(
        book: LoadedBook,
        start: Int,
        end: Int,
    ): String {
        val sb = StringBuilder()
        for (i in start..end) {
            val raw = book.paragraphs.getOrNull(i)?.text.orEmpty()
            // æ®µåè¥ä»å«æ¢è¡ï¼æè¡åæ
            for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val piece = ensureSentenceTerminator(line)
                if (piece.isEmpty()) continue
                sb.append(piece)
            }
        }
        return sb.toString()
    }

    /** è¡å°¾æ ãï¼ï¼ç­åè¡¥ä¸­æå¥å· */

    fun ensureSentenceTerminator(line: String): String {
        val t = line.trim()
        if (t.isEmpty()) return ""
        var i = t.lastIndex
        // 跳过尾部引号/括号
        while (i >= 0 && t[i] in setOf(
            '"', '\'', '\u201c', '\u201d', '\u2018', '\u2019',
            '」', '』', '》', '〉', '）', ')', ']', '｝', '}',
        )) i--
        if (i < 0) return "$t。"
        val c = t[i]
        if (c in "。！？!?;；…‥~～") return t
        return "$t。"
    }


    fun updateExportRangeUi() {
        if (!activity.isExportPanelReady() || !activity.isReaderReady()) return
        if (exportStartPara < 0 || exportEndPara < 0) {
            exportPanel.tvExportRange.text = activity.getString(R.string.tts_export_range_none)
            reader.clearExportRangeHighlight()
            return
        }
        normalizeExportRange()
        val b = activity.book
        val chars = if (b != null) {
            (exportStartPara..exportEndPara).sumOf { i ->
                b.paragraphs.getOrNull(i)?.text?.length ?: 0
            }
        } else {
            0
        }
        exportPanel.tvExportRange.text = activity.getString(
            R.string.tts_export_range,
            exportStartPara + 1,
            exportEndPara + 1,
            chars,
        )
        reader.setExportRangeHighlight(exportStartPara, exportEndPara)
    }


    fun refreshExportVoiceLabel() {
        if (!activity.isExportPanelReady() || !activity.isTtsReady()) return
        val name = tts.currentVoiceName()
            ?: AppSettings.voiceName(activity)
            ?: activity.getString(R.string.tts_voice)
        exportPanel.tvExportVoice.text = name
    }


    fun startRangeExport() {
        val b = activity.book
        if (b == null || exportStartPara < 0 || exportEndPara < 0) {
            Toasts.show(activity, R.string.tts_export_need_range)
            return
        }
        if (ttsExport?.isWorking() == true) return
        normalizeExportRange()
        // ææ®µæ¼æ¥ï¼æ®µæ«æ å¥å·ç­åè¡¥ãããï¼é¿åæ¢è¡å¤è¿è¯»ä¸æ¸?
        val text = buildExportSpeechText(b, exportStartPara, exportEndPara)
        if (text.isBlank()) {
            Toasts.show(activity, R.string.tts_export_need_range)
            return
        }
        var format = when {
            exportPanel.rbFormatWav.isChecked -> TtsExportHelper.Format.WAV
            exportPanel.rbFormatMp3.isChecked -> TtsExportHelper.Format.MP3
            else -> TtsExportHelper.Format.M4A
        }
        // éäº MP3 ä½æ¬æºæ  LAMEï¼é arm64 ç­ï¼â?èªå¨æ¹ç¨ M4A
        if (format == TtsExportHelper.Format.MP3 && !Mp3Encoder.isAvailable()) {
            format = TtsExportHelper.Format.M4A
            exportPanel.rbFormatM4a.isChecked = true
            Toasts.show(activity, R.string.tts_export_mp3_unsupported)
        }
        val bitRateKbps = selectedExportBitrateKbps()
        AppSettings.setTtsExportBitrateKbps(activity, bitRateKbps)
        val helper = TtsExportHelper(activity).also { ttsExport = it }
        setExportProgressUi(active = true, done = 0, total = 1)
        val dlg = TtsExportProgressDialog(activity) {
            helper.cancel()
        }.also { exportProgressDlg = it }
        dlg.show()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        helper.export(
            text = text,
            format = format,
            filePrefix = "activity.book",
            bitRateKbps = bitRateKbps,
            callbacks = TtsExportHelper.Callbacks(
                onProgress = progress@{ done, total, phase, doneChars, totalChars, partFraction ->
                    if (activity.isFinishing || activity.isDestroyed) return@progress
                    val t = total.coerceAtLeast(1)
                    val cur = if (phase == "synth" && done < t) done + 1 else done.coerceAtMost(t)
                    val label = when (phase) {
                        "prepare", "init" -> activity.getString(R.string.tts_export_phase_prepare)
                        "encode" -> activity.getString(R.string.tts_export_encoding)
                        "merge" -> activity.getString(R.string.tts_export_phase_merge)
                        else -> activity.getString(R.string.tts_export_progress, cur, t)
                    }
                    // é¢æ¿è¿åº¦æ¡ï¼æå­æ?0â?00ï¼å¦åææ®?æ®µå
                    val pct = progressPercent(done, t, phase, doneChars, totalChars, partFraction)
                    setExportProgressUi(
                        active = true,
                        done = pct,
                        total = 100,
                        label = label,
                    )
                    exportProgressDlg?.update(
                        done, total, phase, doneChars, totalChars, partFraction,
                    )
                },
                onSuccess = ok@{ file ->
                    if (activity.isFinishing || activity.isDestroyed) return@ok
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    dismissExportProgressDlg()
                    setExportProgressUi(active = false)
                    Toasts.show(
                        activity,
                        activity.getString(R.string.tts_export_ok, file.name),
                        android.widget.Toast.LENGTH_LONG,
                    )
                    shareExportedAudio(file)
                },
                onError = err@{ message ->
                    if (activity.isFinishing || activity.isDestroyed) return@err
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    dismissExportProgressDlg()
                    setExportProgressUi(active = false)
                    Toasts.show(
                        activity,
                        activity.getString(R.string.tts_export_fail, message),
                        android.widget.Toast.LENGTH_LONG,
                    )
                },
                onCancelled = cancel@{
                    if (activity.isFinishing || activity.isDestroyed) return@cancel
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    dismissExportProgressDlg()
                    setExportProgressUi(active = false)
                    Toasts.show(activity, R.string.tts_export_cancelled)
                },
            ),
        )
    }


    fun dismissExportProgressDlg() {
        exportProgressDlg?.dismiss()
        exportProgressDlg = null
    }

    /** å¯¼åºè¿åº¦ 0â?00ï¼ä¸è¿åº¦çªç®æ³ä¸è´ï¼ */

    fun progressPercent(
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
                    val within = if (done < t) partFraction.coerceIn(0f, 1f) * (92f / t) else 0f
                    (base + within).toInt().coerceIn(0, 92)
                }
            }
        }
    }


    fun setExportProgressUi(
        active: Boolean,
        done: Int = 0,
        total: Int = 1,
        label: String? = null,
    ) {
        if (!activity.isExportPanelReady()) return
        exportPanel.progressExport.isVisible = active
        exportPanel.tvExportProgress.isVisible = active
        exportPanel.btnCancelExport.isVisible = active
        exportPanel.btnStartExport.isEnabled = !active
        exportPanel.btnPickStart.isEnabled = !active
        exportPanel.btnPickEnd.isEnabled = !active
        exportPanel.btnPickAll.isEnabled = !active
        val needBitrate = exportPanel.rbFormatMp3.isChecked || exportPanel.rbFormatM4a.isChecked
        exportPanel.spExportBitrate.isEnabled = !active && needBitrate
        exportPanel.rbFormatMp3.isEnabled = !active && Mp3Encoder.isAvailable()
        exportPanel.rbFormatM4a.isEnabled = !active
        exportPanel.rbFormatWav.isEnabled = !active
        if (active) {
            val t = total.coerceAtLeast(1)
            exportPanel.progressExport.max = t
            exportPanel.progressExport.progress = done.coerceIn(0, t)
            exportPanel.tvExportProgress.text = label
                ?: activity.getString(R.string.tts_export_progress, done, t)
        }
    }


    fun shareExportedAudio(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.tts_export_share)))
        }
    }

    /** ä»å¯è§åºç¬¬ä¸ä¸ªå®æ´æ¾ç¤ºçå­å¼å§è¯»å°ææ?*/

    fun startTtsFromViewport() {
        val (para, off) = reader.firstFullyVisibleCharPosition()
        tts.playFromParagraphOffset(para, off)
    }

    /**
     * æè¯»åå°½éæ¿å°éç¥æéï¼Android 13+ æ éç¥æ¶åå°æå¡æè¢«ç³»ç»å¨éå±åææã?
     */

    fun withTtsNotificationPermission(then: () -> Unit) {
        if (TtsManager.hasNotificationPermission(activity)) {
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


    fun setupTtsBar() {
        b.btnTtsPlayPause.setOnClickListener {
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
        b.btnTtsPrev.setOnClickListener { tts.previousSentence() }
        b.btnTtsNext.setOnClickListener { tts.nextSentence() }
        b.btnTtsStop.setOnClickListener {
            tts.stop()
            sleepTimer.cancel()
            updateSleepUi()
            reader.clearHighlight()
            activity.ttsBarOpen = false
            updateTtsUi(tts.currentState())
            activity.chromeController.applyChromeVisibility()
        }
        b.btnVoice.setOnClickListener { showVoicePicker() }
        b.btnTtsRetry.setOnClickListener {
            tts.reinit()
            try {
                activity.startActivity(tts.openTtsSettingsIntent())
            } catch (_: Exception) {
                Toasts.show(activity, R.string.tts_check_system, android.widget.Toast.LENGTH_LONG)
            }
        }
        b.btnTtsRate.setOnClickListener { v -> showTtsRateMenu(v) }
        b.btnTtsSleep.setOnClickListener { v -> showTtsSleepMenu(v) }
        b.tvTtsSleepCountdown.setOnClickListener { confirmCancelSleepTimer() }
        updateTtsRateLabel(AppSettings.ttsRate(activity))
        updateSleepUi()
    }


    fun confirmCancelSleepTimer() {
        if (!sleepTimer.isActive()) {
            showTtsSleepMenu(b.btnTtsSleep)
            return
        }
        AlertDialog.Builder(activity)
            .setMessage(R.string.tts_sleep_cancel_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                sleepTimer.cancel()
                updateSleepUi()
                Toasts.show(activity, R.string.tts_sleep_cancelled)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    fun showTtsRateMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(activity, anchor)
        ttsRateOptions.forEachIndexed { i, rate ->
            popup.menu.add(0, i, i, formatRateLabel(rate))
        }
        popup.setOnMenuItemClickListener { item ->
            val rate = ttsRateOptions.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            AppSettings.setTtsRate(activity, rate)
            tts.setSpeechRate(rate, restartCurrent = true)
            updateTtsRateLabel(rate)
            true
        }
        popup.show()
    }


    fun formatRateLabel(rate: Float): String {
        val body = if (kotlin.math.abs(rate - rate.toInt()) < 0.001f) {
            rate.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", rate).trimEnd('0').trimEnd('.')
        }
        return body + "Ã"
    }


    fun updateTtsRateLabel(rate: Float) {
        b.btnTtsRate.text = formatRateLabel(rate)
    }


    fun showTtsSleepMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(activity, anchor)
        com.whj.reader.tts.TtsSleepTimer.OPTION_MINUTES.forEachIndexed { i, mins ->
            val title = if (mins == 0) {
                activity.getString(R.string.tts_sleep_off)
            } else {
                activity.getString(R.string.tts_sleep_minutes, mins)
            }
            popup.menu.add(0, i, i, title)
        }
        popup.setOnMenuItemClickListener { item ->
            val mins = com.whj.reader.tts.TtsSleepTimer.OPTION_MINUTES
                .getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            if (mins == 0) {
                sleepTimer.cancel()
                updateSleepUi()
                Toasts.show(activity, R.string.tts_sleep_cancelled)
            } else {
                sleepTimer.start(mins * 60_000L)
                updateSleepUi()
                Toasts.show(activity, activity.getString(R.string.tts_sleep_set, mins))
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
                com.whj.reader.tts.TtsSleepTimer.formatCountdown(sleepTimer.remainingMs())
        }
    }


    fun onSleepTimerFinished() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.isTtsReady()) tts.stop()
        if (activity.isReaderReady()) reader.clearHighlight()
        updateSleepUi()
        if (activity.isTtsReady()) updateTtsUi(tts.currentState())
        Toasts.show(activity, R.string.tts_sleep_finished)
    }


    fun adjustTtsRate(delta: Float) {
        val next = (AppSettings.ttsRate(activity) + delta).coerceIn(0.5f, 2.5f)
        val rounded = (kotlin.math.round(next * 10f) / 10f).coerceIn(0.5f, 2.5f)
        AppSettings.setTtsRate(activity, rounded)
        tts.setSpeechRate(rounded, restartCurrent = true)
        updateTtsRateLabel(rounded)
    }

    /** æ ·å¼é¢æ¿ï¼å¯æ»å¨ï¼é«åº¦ä¸è¶è¿å±é«çº?78%ï¼é¿ååºé¨è¢«è£å */

    fun isTtsInitNoise(message: String): Boolean {
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
        activity.chromeController.applyChromeVisibility()

        when (snapshot.state) {
            TtsManager.State.SPEAKING -> {
                b.btnTtsPlayPause.setImageResource(R.drawable.ic_pause)
                b.btnTtsPlayPause.contentDescription = activity.getString(R.string.tts_pause)
                // ä¸æ¾ç¤ºæ®µæ?å¥æ°ï¼åªæç¤ºç¶æ?
                b.tvTtsStatus.text = activity.getString(R.string.tts_speaking)
            }
            TtsManager.State.PAUSED -> {
                b.btnTtsPlayPause.setImageResource(R.drawable.ic_play)
                b.btnTtsPlayPause.contentDescription = activity.getString(R.string.tts_resume)
                b.tvTtsStatus.text = activity.getString(R.string.tts_paused)
            }
            TtsManager.State.IDLE -> {
                b.btnTtsPlayPause.setImageResource(R.drawable.ic_play)
                b.btnTtsPlayPause.contentDescription = activity.getString(R.string.tts_play)
                b.tvTtsStatus.text = when {
                    !snapshot.ready -> snapshot.statusMessage.ifBlank { activity.getString(R.string.tts_not_ready) }
                    else -> activity.getString(R.string.tts_idle)
                }
            }
        }
        b.btnTtsRetry.isVisible = !snapshot.ready
    }


    fun showVoicePicker() {
        TtsVoicePicker.show(activity, tts) {
            if (tts.currentState().state == TtsManager.State.SPEAKING) {
                val snap = tts.currentState()
                tts.playFrom(snap.paragraphIndex, snap.sentenceIndex)
            }
        }
    }

}
