package com.whj.reader.txt.nav
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
 * TXT reading nav controller (extracted from ReadingActivity).
 */
class TextNavBookmarkController(
    private val activity: ReadingActivity,
) {

    private val b get() = activity.binding
    private val reader get() = activity.reader
    private val readMenu get() = activity.readMenu
    private val exportPanel get() = activity.exportPanel
    private val settingsPanel get() = activity.settingsPanel
    private val tts get() = activity.tts
    private val ctx get() = activity


    fun bookmarkAnchorParagraph(): Int {
        if (activity.book == null || !activity.isReaderReady()) return 0
        val density = activity.resources.displayMetrics.density
        val inset = if (activity.chromeVisible) {
            // é??? ?????°?? measure??ç¨????é??????52dp ???
            val h = b.topBar.height
            if (h > 0) h.toFloat() else 52f * density
        } else {
            0f
        }
        return reader.topScreenParagraph(inset)
            .coerceIn(0, activity.book!!.paragraphs.lastIndex.coerceAtLeast(0))
    }

    /** ???é???ä?ç?????? /???ä??ç­????????ä¸??ç??ä¸????? */

    fun toggleBookmarkAtCurrent() {
        if (activity.fileKey.isBlank() || activity.book == null) return
        // ?¸?????éç???é??é???  height=0
        b.topBar.post {
            val para = bookmarkAnchorParagraph()
            if (BookmarkStore.has(activity, activity.fileKey, para)) {
                BookmarkStore.remove(activity, activity.fileKey, para)
                Toasts.show(activity, R.string.bookmark_off)
            } else {
                val preview = activity.book!!.paragraphs.getOrNull(para)?.text
                    ?.take(80)
                    ?.replace('\n', ' ')
                    .orEmpty()
                val pct = reader.progressPercentForParagraph(para)
                BookmarkStore.add(
                    activity,
                    com.whj.reader.model.Bookmark(
                        fileKey = activity.fileKey,
                        paragraphIndex = para,
                        preview = preview,
                        progressPercent = pct,
                    ),
                )
                Toasts.show(activity, R.string.bookmark_on)
            }
            updateBookmarkButton()
        }
    }


    fun updateBookmarkButton() {
        if (!activity.isBindingReady() || !activity.isReaderReady()) return
        if (activity.fileKey.isBlank() || activity.book == null) {
            b.btnBookmark.setImageResource(R.drawable.ic_bookmark_border)
            return
        }
        val para = bookmarkAnchorParagraph()
        val on = BookmarkStore.has(activity, activity.fileKey, para)
        b.btnBookmark.setImageResource(
            if (on) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
        )
        b.btnBookmark.contentDescription = if (on) {
            activity.getString(R.string.bookmark_on)
        } else {
            activity.getString(R.string.add_bookmark)
        }
    }

    /** ç?ç ??RadioGroup??? ç?ç??????*/

    fun showEncodingPicker() {
        val key = activity.fileKey.ifBlank {
            activity.intent.getStringExtra(ReadingActivity.EXTRA_URI)
                ?: activity.intent.getStringExtra(ReadingActivity.EXTRA_ASSET)?.let { "asset://$it" }
                ?: return
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_text_options, null)
        val rgEnc = view.findViewById<android.widget.RadioGroup>(R.id.rgEncoding)
        val rgZh = view.findViewById<android.widget.RadioGroup>(R.id.rgChinese)
        val rbOff = view.findViewById<android.widget.RadioButton>(R.id.rbZhOff)
        val rbSimple = view.findViewById<android.widget.RadioButton>(R.id.rbZhToSimple)
        val rbTrad = view.findViewById<android.widget.RadioButton>(R.id.rbZhToTrad)

        val ids = BookEncodingStore.OPTION_IDS
        val currentEnc = BookEncodingStore.get(activity, key) ?: BookEncodingStore.ENCODING_AUTO
        val encRadioIds = IntArray(ids.size)
        for ((i, code) in ids.withIndex()) {
            val rb = android.widget.RadioButton(activity).apply {
                id = View.generateViewId()
                text = if (code == BookEncodingStore.ENCODING_AUTO) {
                    activity.getString(R.string.encoding_auto)
                } else {
                    code
                }
                minHeight = (40 * activity.resources.displayMetrics.density).toInt()
            }
            encRadioIds[i] = rb.id
            rgEnc.addView(rb)
            if (code == currentEnc ||
                (code == BookEncodingStore.ENCODING_AUTO && currentEnc == BookEncodingStore.ENCODING_AUTO)
            ) {
                rb.isChecked = true
            }
        }
        if (rgEnc.checkedRadioButtonId == -1 && encRadioIds.isNotEmpty()) {
            rgEnc.check(encRadioIds[0])
        }

        when (BookChineseModeStore.get(activity, key)) {
            ChineseConvert.Mode.TO_SIMPLE -> rbSimple.isChecked = true
            ChineseConvert.Mode.TO_TRADITIONAL -> rbTrad.isChecked = true
            else -> rbOff.isChecked = true
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.text_options_title)
            .setView(view)
            .setPositiveButton(R.string.apply) { dialog, _ ->
                val checkedEncId = rgEnc.checkedRadioButtonId
                val encIdx = encRadioIds.indexOf(checkedEncId).coerceAtLeast(0)
                val code = ids.getOrElse(encIdx) { BookEncodingStore.ENCODING_AUTO }
                val enc = if (code == BookEncodingStore.ENCODING_AUTO) null else code
                BookEncodingStore.set(activity, key, enc)
                if (enc != null) activity.intent.putExtra(ReadingActivity.EXTRA_ENCODING, enc)
                else activity.intent.removeExtra(ReadingActivity.EXTRA_ENCODING)

                val zhMode = when (rgZh.checkedRadioButtonId) {
                    R.id.rbZhToSimple -> ChineseConvert.Mode.TO_SIMPLE
                    R.id.rbZhToTrad -> ChineseConvert.Mode.TO_TRADITIONAL
                    else -> ChineseConvert.Mode.OFF
                }
                BookChineseModeStore.set(activity, key, zhMode)

                val encLabel = enc ?: activity.getString(R.string.encoding_auto)
                val zhLabel = when (zhMode) {
                    ChineseConvert.Mode.TO_SIMPLE -> activity.getString(R.string.chinese_convert_to_simple)
                    ChineseConvert.Mode.TO_TRADITIONAL -> activity.getString(R.string.chinese_convert_to_trad)
                    ChineseConvert.Mode.OFF -> activity.getString(R.string.chinese_convert_off)
                }
                Toasts.show(
                    activity,
                    activity.getString(R.string.encoding_set, encLabel) + " Â? " + zhLabel,
                )
                dialog.dismiss()
                reloadTextOptions(enc, zhMode)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    fun reloadTextOptions(
        preferredEncoding: String?,
        chineseMode: ChineseConvert.Mode,
    ) {
        val asset = activity.intent.getStringExtra(ReadingActivity.EXTRA_ASSET)
        val uriStr = activity.intent.getStringExtra(ReadingActivity.EXTRA_URI)
        val titleExtra = activity.intent.getStringExtra(ReadingActivity.EXTRA_TITLE)
        val keepPara = if (activity.isReaderReady()) {
            reader.firstVisibleParagraph()
        } else {
            0
        }
        if (activity.isTtsReady()) {
            tts.stop()
        }
        activity.streamerJob?.cancel()
        activity.bookStreamer?.cancel()
        activity.bookStreamer = null
        activity.streamerLoading = false
        activity.loadController.showLoadOverlay(activity.getString(R.string.loading_book))
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        asset != null -> com.whj.reader.data.BookOpenResult(
                            book = TextLoader.loadFromAssets(
                                activity,
                                asset,
                                titleExtra ?: activity.getString(R.string.unnamed),
                                preferredEncoding = preferredEncoding,
                                chineseMode = chineseMode,
                            ),
                            streamer = null,
                        )
                        uriStr != null -> BookLoader.openFromUri(
                            activity,
                            Uri.parse(uriStr),
                            titleExtra,
                            preferredEncoding = preferredEncoding,
                            chineseMode = chineseMode,
                            onProgress = { msg, cur, tot ->
                                activity.runOnUiThread { activity.loadController.updateLoadOverlay(msg, cur, tot) }
                            },
                        )
                        else -> error("?????")
                    }
                }
            }
            result.onSuccess { open ->
                activity.pendingRestorePara = keepPara
                activity.loadController.applyLoadedBook(open.book, isInitial = true)
                // é?????ä????°??çä?ç??
                if (keepPara > 0) activity.pendingRestorePara = maxOf(activity.pendingRestorePara, keepPara)
                val streamer = open.streamer
                if (streamer != null) {
                    activity.loadController.attachBookStreamer(streamer)
                } else {
                    updateBookmarkButton()
                    // ? ç?­???????? éç­??ç?? ????????°???ç¤?
                    activity.loadController.maybeRevealReaderAfterRestore()
                }
            }.onFailure { e ->
                activity.loadController.hideLoadOverlay()
                // é????¤??´?????????é?éé???????­????ä¸? ??é­????????é??
                activity.loadController.showOpenFailGuide(
                    reason = OpenFailGuide.reasonFrom(e),
                    detail = e.message,
                    exitOnClose = activity.book == null,
                )
            }
        }
    }


    fun handleLinkClick(href: String) {
        val raw = href.trim()
        if (raw.isEmpty()) return
        val lower = raw.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("mailto:")
        ) {
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(raw)).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                    ),
                )
            }.onFailure {
                Toasts.show(activity, activity.getString(R.string.link_open_fail))
            }
            return
        }
        val target = resolveInternalLink(raw)
        if (target < 0) {
            Toasts.show(activity, activity.getString(R.string.link_not_found))
            return
        }
        // ?éç?­??????°ç?? ??????°ä¸?????¤ä?ç?????ç?§ç?­?§??
        val maxIdx = activity.book?.paragraphs?.lastIndex ?: -1
        if (target > maxIdx) {
            activity.pendingRestorePara = target
            activity.loadController.requestStreamBatch()
            Toasts.show(activity, activity.getString(R.string.link_loading_target))
            return
        }
        activity.chromeController.hideChrome()
        reader.scrollToParagraph(target)
        if (activity.allowProgressSave) activity.saveProgress(target)
        activity.updateProgressLabel()
        activity.updateChapterTitleBar(target)
    }


    fun resolveInternalLink(href: String): Int {
        val map = activity.book?.linkTargets.orEmpty()
        if (map.isEmpty()) return -1
        var h = href.trim()
        h = runCatching {
            java.net.URLDecoder.decode(h, Charsets.UTF_8.name())
        }.getOrDefault(h)
        if (h.startsWith("mobi:filepos:", ignoreCase = true)) {
            map[h]?.let { return it }
            map[h.lowercase(Locale.ROOT)]?.let { return it }
            return -1
        }
        h = h.replace('\\', '/').trim()
        // ??????¤?./
        while (h.startsWith("./")) h = h.removePrefix("./")
        val hash = h.substringAfter('#', missingDelimiterValue = "").trim()
        var path = h.substringBefore('#', missingDelimiterValue = h).trim()
        // ç??éç?#id
        if (path.isEmpty() && hash.isNotEmpty()) {
            map[hash]?.let { return it }
            map[hash.lowercase(Locale.ROOT)]?.let { return it }
            return -1
        }
        // ?§???../ ä¸?.
        if (path.contains("..") || path.contains("./") || path.contains('/')) {
            val parts = path.split('/')
            val stack = ArrayList<String>()
            for (p in parts) {
                when {
                    p.isEmpty() || p == "." -> Unit
                    p == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    else -> stack.add(p)
                }
            }
            path = stack.joinToString("/")
        }
        val fileName = path.substringAfterLast('/')
        val candidates = ArrayList<String>(16)
        // ä??ç??ç????path#id / file#id / id??ä¸ EpubLoader putLinkTarget é?ä¸?´??
        if (hash.isNotEmpty()) {
            if (path.isNotEmpty()) {
                candidates += "$path#$hash"
                candidates += "$fileName#$hash"
                // OEBPS/text/foo.xhtml#id ç­?
                if (!path.startsWith("OEBPS/", ignoreCase = true)) {
                    candidates += "OEBPS/$path#$hash"
                    candidates += "OEBPS/text/$path#$hash"
                    candidates += "OEBPS/Text/$path#$hash"
                }
            }
            candidates += hash
        }
        if (path.isNotEmpty()) {
            candidates += path
            candidates += fileName
            candidates += path.trimStart('/')
            if (path.startsWith("OEBPS/", ignoreCase = true)) {
                candidates += path.removePrefix("OEBPS/").removePrefix("oebps/")
            } else {
                candidates += "OEBPS/$path"
                candidates += "OEBPS/text/$path"
                candidates += "OEBPS/Text/$path"
            }
            // text/ch1.xhtml
            if (path.startsWith("text/", ignoreCase = true)) {
                candidates += path.removePrefix("text/").removePrefix("Text/")
            }
        }
        for (c in candidates) {
            if (c.isEmpty()) continue
            map[c]?.let { return it }
            map[c.lowercase(Locale.ROOT)]?.let { return it }
        }
        // ???????ä??ä????????ç?ç???????????key ?ç???é path#hash
        if (fileName.isNotEmpty()) {
            val suffix = if (hash.isNotEmpty()) "$fileName#$hash" else fileName
            val lower = suffix.lowercase(Locale.ROOT)
            for ((k, v) in map) {
                val kl = k.lowercase(Locale.ROOT)
                if (kl == lower || kl.endsWith("/$lower") || kl.endsWith(lower)) {
                    return v
                }
            }
        }
        return -1
    }


    fun jumpChapter(delta: Int) {
        if (activity.mangaMode) {
            // ???ç???ä¸ä¸ç? =ç??????ä????ä¸??
            activity.mangaController.mangaGo(delta)
            return
        }
        val b = activity.book ?: return
        val chapters = b.chapters
        if (chapters.isEmpty()) {
            Toasts.show(activity, R.string.toc_empty)
            return
        }
        val cur = reader.firstVisibleParagraph()
        val idx = chapters.indexOfLast { it.paragraphIndex <= cur }.coerceAtLeast(0)
        val target = idx + delta
        when {
            target < 0 -> Toasts.show(activity, R.string.no_prev_chapter)
            target > chapters.lastIndex -> {
                // ç? ???¨?°?????¨?§??????ç?§ç?­? ???????
                if (activity.bookStreamer != null && !b.isComplete) {
                    activity.pendingRestorePara = (b.paragraphs.size + 50).coerceAtLeast(activity.pendingRestorePara)
                    activity.loadController.requestStreamBatch()
                    Toasts.show(activity, R.string.link_loading_target)
                } else {
                    Toasts.show(activity, R.string.no_next_chapter)
                }
            }
            else -> {
                val ch = chapters[target]
                val p = ch.paragraphIndex
                if (p < 0 || p > b.paragraphs.lastIndex) {
                    // ç????ç??­???ä?ä??­????????????éç?­???
                    if (p >= 0) activity.pendingRestorePara = p
                    else if (ch.spineIndex >= 0) {
                        // ?°? ???ç´???????spine é?ä?°ç?? ???¤???? ??
                        activity.pendingRestorePara =
                            (b.paragraphs.size + (ch.spineIndex + 1) * 80).coerceAtLeast(0)
                    } else {
                        activity.pendingRestorePara = b.paragraphs.size + 50
                    }
                    activity.loadController.requestStreamBatch()
                    Toasts.show(activity, R.string.link_loading_target)
                    return
                }
                // ä¸?ä¸ä¸ç? ???¨??ä¸????é¨????ä¸ PDF ä¸ä¸é??ä¸ä¸é??ä¸?´??
                activity.ignoreScrollChromeHideUntilMs =
                    android.os.SystemClock.uptimeMillis() + 1_200L
                reader.scrollToParagraph(p)
                activity.saveProgress(p)
                activity.updateProgressLabel()
                activity.updateChapterTitleBar(p)
                if (tts.currentState().state != TtsManager.State.IDLE) {
                    tts.jumpToParagraph(p, autoPlay = true)
                } else {
                    reader.clearHighlight()
                }
            }
        }
    }

    /**
     * ??????????????~100% ???¨??????¨????????????ä?ç????
     * ???ç??¨??????ç????????????
     */

    fun showProgressJumpSheet() {
        if (activity.book == null) return
        if (activity.mangaMode && activity.mangaPaths.isNotEmpty()) {
            showMangaProgressJumpSheet()
            return
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }
        val tvPercent = android.widget.TextView(activity).apply {
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(activity.getColor(R.color.text_primary))
            setPadding(0, 0, 0, pad / 2)
        }
        // 0.01% ç????????..10000
        val seek = android.widget.SeekBar(activity).apply {
            max = 10_000
        }
        container.addView(
            tvPercent,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.addView(
            seek,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        fun applyPercent(raw: Int, fromUser: Boolean) {
            val pct = raw / 100f // 0.00 ~ 100.00
            tvPercent.text = String.format(Locale.US, "%.2f%%", pct)
            if (fromUser) {
                reader.scrollToProgressPercent(pct)
                activity.updateProgressLabel()
                activity.saveProgress(reader.firstVisibleParagraph())
                if (tts.currentState().state != TtsManager.State.IDLE) {
                    val idx = reader.firstVisibleParagraph()
                    tts.jumpToParagraph(idx, autoPlay = true)
                } else {
                    reader.clearHighlight()
                }
            }
        }

        val cur = (reader.progressPercent() * 100f).toInt().coerceIn(0, 10_000)
        seek.progress = cur
        applyPercent(cur, fromUser = false)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyPercent(progress, fromUser)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                activity.saveProgress(reader.firstVisibleParagraph())
                activity.updateProgressLabel()
            }
        })

        AlertDialog.Builder(activity)
            .setTitle(R.string.menu_jump)
            .setView(container)
            .setPositiveButton(R.string.close, null)
            .show()
    }


    fun showMangaProgressJumpSheet() {
        if (activity.mangaPaths.isEmpty()) return
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }
        val tvLabel = android.widget.TextView(activity).apply {
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(activity.getColor(R.color.text_primary))
            setPadding(0, 0, 0, pad / 2)
        }
        val max = (activity.mangaPaths.size - 1).coerceAtLeast(0)
        val seek = android.widget.SeekBar(activity).apply {
            this.max = max
            progress = activity.mangaIndex.coerceIn(0, max)
        }
        fun refreshLabel(idx: Int) {
            tvLabel.text = activity.getString(R.string.mobi_manga_progress, idx + 1, activity.mangaPaths.size)
        }
        refreshLabel(activity.mangaIndex)
        container.addView(
            tvLabel,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.addView(
            seek,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshLabel(progress)
                if (fromUser) {
                    activity.mangaController.showMangaIndex(progress)
                    activity.updateProgressLabel()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (activity.allowProgressSave) activity.saveProgress(activity.mangaIndex)
                activity.updateProgressLabel()
            }
        })
        AlertDialog.Builder(activity)
            .setTitle(R.string.menu_jump)
            .setView(container)
            .setPositiveButton(R.string.close, null)
            .show()
    }


    fun applyCustomChapterPatternIfSaved(loaded: LoadedBook): LoadedBook {
        val pattern = BookChapterPatternStore.get(activity, loaded.uri) ?: return loaded
        val ignoreCase = BookChapterPatternStore.getIgnoreCase(activity, loaded.uri)
        return runCatching {
            CustomChapterScanner.apply(loaded, pattern, ignoreCase)
        }.getOrElse { loaded }
    }


    fun applyCustomChapterPattern(
        pattern: String,
        ignoreCase: Boolean,
    ): LoadedBook? {
        val b = activity.book ?: return null
        val updated = runCatching {
            CustomChapterScanner.apply(b, pattern, ignoreCase)
        }.getOrElse { e ->
            val msg = when (e) {
                is IllegalStateException -> activity.getString(R.string.custom_toc_no_match)
                else -> activity.getString(R.string.custom_toc_invalid, e.message ?: e.toString())
            }
            Toasts.show(activity, msg)
            return null
        }
        BookChapterPatternStore.set(activity, b.uri, pattern, ignoreCase)
        activity.loadController.applyLoadedBook(updated, isInitial = false)
        activity.updateChapterTitleBar(reader.firstVisibleParagraph())
        val toast = activity.getString(R.string.custom_toc_applied, updated.chapters.size)
        if (!updated.isComplete) {
            Toasts.show(
                activity,
                "$toast\n${activity.getString(R.string.custom_toc_incomplete, updated.paragraphs.size)}",
            )
        } else {
            Toasts.show(activity, toast)
        }
        return updated
    }


    fun showCustomChapterPatternDialog(onApplied: (LoadedBook) -> Unit = {}) {
        val b = activity.book ?: return
        val uri = b.uri
        val dlgView = activity.layoutInflater.inflate(R.layout.dialog_custom_chapter_pattern, null)
        val spPreset = dlgView.findViewById<Spinner>(R.id.spPreset)
        val etPattern = dlgView.findViewById<TextInputEditText>(R.id.etPattern)
        val cbIgnoreCase = dlgView.findViewById<CheckBox>(R.id.cbIgnoreCase)
        val presets = CustomChapterScanner.PRESETS
        spPreset.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            presets.map { it.label },
        )
        // ?????Spinner ä??§??ä¸???onItemSelected??????ç????ä??ä??­ç?¨???
        var suppressPresetFill = true
        spPreset.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressPresetFill) return
                if (position in presets.indices) {
                    etPattern.setText(presets[position].pattern)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        // ?ä?????¤ä¸???????ä??¨?????ä??ä?é?°ç???
        val saved = BookChapterPatternStore.get(activity, uri)
        if (saved != null) {
            etPattern.setText(saved)
            val matchIdx = presets.indexOfFirst { it.pattern == saved }
            if (matchIdx >= 0) {
                spPreset.setSelection(matchIdx, false)
            }
        } else {
            etPattern.setText(presets.first().pattern)
            spPreset.setSelection(0, false)
        }
        cbIgnoreCase.isChecked = BookChapterPatternStore.getIgnoreCase(activity, uri)
        spPreset.post { suppressPresetFill = false }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.custom_toc_title)
            .setView(dlgView)
            .setPositiveButton(R.string.apply) { _, _ ->
                val pattern = etPattern.text?.toString()?.trim().orEmpty()
                if (pattern.isEmpty()) {
                    Toasts.show(activity, R.string.custom_toc_no_match)
                    return@setPositiveButton
                }
                applyCustomChapterPattern(pattern, cbIgnoreCase.isChecked)?.let(onApplied)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.custom_toc_clear) { _, _ ->
                BookChapterPatternStore.clear(activity, uri)
                Toasts.show(activity, R.string.custom_toc_cleared)
            }
            .show()
    }


    fun showTocSheet() {
        val b = activity.book ?: return
        val dialog = BottomSheetDialog(activity)
        val sheet = SheetTocBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(sheet.root)

        val curPara = bookmarkAnchorParagraph()
        val totalParas = b.paragraphs.size

        fun jumpTo(index: Int, spineIndex: Int = -1) {
            dialog.dismiss()
            val last = activity.book?.paragraphs?.lastIndex ?: -1
            if (index < 0 || index > last) {
                // ?¤?EPUB??ç??????ä?????ç??­???­???éç?­????????
                activity.pendingRestorePara = when {
                    index >= 0 -> index
                    spineIndex >= 0 ->
                        (last + 1 + (spineIndex + 1) * 80).coerceAtLeast(0)
                    else -> last + 50
                }
                activity.loadController.requestStreamBatch()
                Toasts.show(activity, R.string.link_loading_target)
                return
            }
            reader.scrollToParagraph(index)
            activity.saveProgress(index)
            activity.updateProgressLabel()
            activity.updateChapterTitleBar(index)
            if (tts.currentState().state != TtsManager.State.IDLE) {
                tts.jumpToParagraph(index, autoPlay = true)
            } else {
                reader.clearHighlight()
            }
        }

        val chapterAdapter = TocAdapter(
            onClick = { item ->
                val ch = (item as? TocItem.ChapterItem)?.chapter ?: return@TocAdapter
                jumpTo(ch.paragraphIndex, ch.spineIndex)
            },
        )
        lateinit var bookmarkAdapter: TocAdapter
        bookmarkAdapter = TocAdapter(
            onClick = { item ->
                val index = (item as? TocItem.BookmarkItem)?.bookmark?.paragraphIndex ?: return@TocAdapter
                jumpTo(index)
            },
            onDeleteBookmark = { bm ->
                BookmarkStore.remove(activity, bm.fileKey, bm.paragraphIndex)
                val items = BookmarkStore.list(activity, activity.fileKey).map { TocItem.BookmarkItem(it) }
                bookmarkAdapter.submit(items, curPara, totalParas)
                updateBookmarkButton()
                Toasts.show(activity, R.string.bookmark_removed)
            },
        )
        chapterAdapter.submit(
            b.chapters.map { TocItem.ChapterItem(it) },
            curPara,
            totalParas,
        )
        bookmarkAdapter.submit(
            BookmarkStore.list(activity, activity.fileKey).map { TocItem.BookmarkItem(it) },
            curPara,
            totalParas,
        )
        lateinit var notesAdapter: TocAdapter
        notesAdapter = TocAdapter(
            onClick = { item ->
                val hl = (item as? TocItem.HighlightItem)?.highlight ?: return@TocAdapter
                dialog.dismiss()
                reader.scrollToTextAnchor(hl.anchor)
                activity.saveProgress(hl.anchor.startParagraph.coerceAtLeast(0))
                activity.updateProgressLabel()
                activity.updateChapterTitleBar(hl.anchor.startParagraph)
                reader.post { activity.highlightController.showHighlightView(hl.id) }
            },
            onDeleteHighlight = { hl ->
                activity.highlightController.applyHighlightList(activity.highlightController.bookHighlights.filter { it.id != hl.id })
                activity.highlightController.saveBookHighlights()
                notesAdapter.submit(
                    activity.highlightController.highlightTocItems(totalParas),
                    curPara,
                    totalParas,
                )
                Toasts.show(activity, R.string.highlight_deleted)
            },
        )
        notesAdapter.submit(
            activity.highlightController.highlightTocItems(totalParas),
            curPara,
            totalParas,
        )

        sheet.btnCustomTocScan.isVisible = true
        sheet.btnCustomTocScan.setOnClickListener {
            showCustomChapterPatternDialog { updated ->
                chapterAdapter.submit(
                    updated.chapters.map { TocItem.ChapterItem(it) },
                    bookmarkAnchorParagraph(),
                    updated.paragraphs.size,
                )
            }
        }

        val titles = listOf(
            activity.getString(R.string.toc),
            activity.getString(R.string.bookmark),
            activity.getString(R.string.notes),
        )
        val adapters = listOf(chapterAdapter, bookmarkAdapter, notesAdapter)
        val emptyMsgs = listOf(R.string.toc_empty, R.string.bookmark_empty, R.string.notes_empty)

        sheet.vpToc.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = 3

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val page = LayoutInflater.from(parent.context)
                    .inflate(R.layout.page_toc_list, parent, false)
                return object : RecyclerView.ViewHolder(page) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val page = holder.itemView
                val rv = page.findViewById<RecyclerView>(R.id.rvList)
                val empty = page.findViewById<android.widget.TextView>(R.id.tvEmpty)
                val ad = adapters[position]
                if (rv.layoutManager == null) {
                    rv.layoutManager = LinearLayoutManager(activity)
                }
                TocVpScrollHelper.attachVerticalList(rv, sheet.vpToc)
                if (rv.adapter !== ad) {
                    rv.adapter = ad
                }
                empty.setText(emptyMsgs[position])
                fun syncEmpty() {
                    val n = ad.itemCount
                    empty.isVisible = n == 0
                    rv.isVisible = n > 0
                }
                syncEmpty()
                // ç???é???????°???ç? ?
                if (position == 0 && ad is TocAdapter) {
                    val chIdx = ad.indexOfActiveChapter()
                    if (chIdx >= 0) {
                        rv.post {
                            val lm = rv.layoutManager as? LinearLayoutManager
                            if (lm != null) {
                                lm.scrollToPositionWithOffset(chIdx, rv.height / 3)
                            } else {
                                rv.scrollToPosition(chIdx)
                            }
                        }
                    }
                }
                // ??ä¸? page ???ä¸???observer??é??é?¤??¨??
                if (page.getTag(R.id.rvList) !== ad) {
                    page.setTag(R.id.rvList, ad)
                    ad.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                        override fun onChanged() = syncEmpty()
                    })
                }
            }
        }

        TabLayoutMediator(sheet.tabLayout, sheet.vpToc) { tab, pos ->
            tab.text = titles[pos]
        }.attach()
        sheet.btnCustomTocScan.isVisible = sheet.vpToc.currentItem == 0
        sheet.vpToc.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                sheet.btnCustomTocScan.isVisible = position == 0
            }
        })

        // ?????ä¸???¤§é??????ä¸???????ä¸?ä¸¤???
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet,
            ) ?: return@setOnShowListener
            val maxH = (activity.resources.displayMetrics.heightPixels * 0.92f).toInt()
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = maxH
            }
            sheet.root.layoutParams = sheet.root.layoutParams?.apply {
                height = maxH
            } ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxH,
            )
            bottomSheet.requestLayout()
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior
                .from(bottomSheet)
            behavior.skipCollapsed = true
            behavior.isFitToContents = false
            behavior.expandedOffset = (activity.resources.displayMetrics.heightPixels - maxH)
                .coerceAtLeast(0)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

}
