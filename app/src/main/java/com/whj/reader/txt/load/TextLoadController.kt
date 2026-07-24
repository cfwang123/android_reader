package com.whj.reader.txt.load
import com.whj.reader.PdfReadingActivity
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
 * TXT reading load controller (extracted from ReadingActivity).
 */
class TextLoadController(
    private val activity: ReadingActivity,
) {

    private val b get() = activity.binding
    private val reader get() = activity.reader
    private val readMenu get() = activity.readMenu
    private val exportPanel get() = activity.exportPanel
    private val settingsPanel get() = activity.settingsPanel
    private val tts get() = activity.tts
    private val ctx get() = activity


    fun isChapterProgressBook(b: LoadedBook): Boolean {
        if (BookFileType.isTxt(b.uri) || BookFileType.isTxt(activity.displayTitle) ||
            BookFileType.isTxt(activity.fileKey)
        ) {
            return false
        }
        return BookFileType.isEpub(b.uri) || BookFileType.isMobi(b.uri) ||
            BookFileType.isEpub(activity.displayTitle) || BookFileType.isMobi(activity.displayTitle) ||
            BookFileType.isEpub(activity.fileKey) || BookFileType.isMobi(activity.fileKey)
    }

    /**
     * @return (Ă§ÂŤÂ ĂĽÂşÂĂĽÂ?1-based, ĂŚÂÂťĂ§ÂŤÂ ĂŚÂ? Ă§ÂŤÂ ĂĽÂÂ 0Ă˘Â?00%)
     */

    fun chapterProgressOf(
        para: Int,
        chapters: List<com.whj.reader.model.Chapter>,
        totalParas: Int,
    ): Triple<Int, Int, Float> {
        if (chapters.isEmpty()) {
            return Triple(1, 1, 0f)
        }
        val idx = chapters.indexOfLast { it.paragraphIndex >= 0 && it.paragraphIndex <= para }
            .coerceAtLeast(0)
            .let { i ->
                if (chapters.getOrNull(i)?.paragraphIndex?.let { it >= 0 } == true) i
                else chapters.indexOfFirst { it.paragraphIndex >= 0 }.coerceAtLeast(0)
            }
        val start = chapters[idx].paragraphIndex.coerceAtLeast(0)
        val endRaw = chapters.drop(idx + 1).firstOrNull { it.paragraphIndex > start }?.paragraphIndex
        val end = endRaw?.coerceIn(start + 1, totalParas.coerceAtLeast(start + 1))
            ?: totalParas.coerceAtLeast(start + 1)
        val span = (end - start).coerceAtLeast(1)
        val within = ((para - start).toFloat() / span * 100f).coerceIn(0f, 100f)
        return Triple(idx + 1, chapters.size, within)
    }

    /** ĂŚÂťÂĂ¨ÂżÂĂĽÂˇÂ˛ĂĽÂÂ Ă¨Â˝Â˝ĂĽÂÂĂĽÂŽÂšĂŚÂÂŤĂĽÂ°ÂžĂŻÂźÂĂŚÂÂĂŚÂÂ˘ĂĽÂ¤?Ă¨ÂˇÂłĂ¨Â˝ÂŹĂ§ÂÂŽĂŚÂ ÂĂĽÂ°ÂĂŚÂÂŞĂ¨Â˝Â˝ĂĽÂÂĽĂŚÂÂśĂŻÂźÂĂĽÂÂĂ¨Â§ÂŁĂŚÂÂĂ¤Â¸ÂĂ¤Â¸ÂĂŚÂÂšĂŻÂźÂĂĽÂÂ¨Ă¤ÂšÂŚĂĽÂÂĂĽÂÂ°ĂŠÂ˘ÂĂ¨Â˝Â˝ĂŚÂÂśĂŠÂÂĂĽÂ¸Â¸ĂĽÂˇÂ˛ĂĽÂÂ¨Ă§ÂťÂ­Ă¨Â˝Â˝ĂŻÂź?*/

    fun maybeRequestMoreContent(firstVisiblePara: Int = -1) {
        if (activity.bookStreamer == null) return
        val b = activity.book ?: return
        if (b.isComplete) return
        if (prefetchesFullBookInBackground(b) && activity.streamerLoading) return
        val last = b.paragraphs.lastIndex.coerceAtLeast(0)
        val needForRestore = activity.pendingRestorePara > last
        val nearEnd = if (firstVisiblePara >= 0) {
            firstVisiblePara >= (last - 30).coerceAtLeast(0)
        } else {
            false
        }
        if (!needForRestore && !nearEnd) return
        requestStreamBatch()
    }


    fun isEpubBook(b: LoadedBook): Boolean {
        return BookFileType.isEpub(b.uri) || BookFileType.isEpub(activity.displayTitle) ||
            BookFileType.isEpub(activity.fileKey)
    }

    /** EPUB/MOBIĂŻÂźÂĂŚÂÂĂĽÂźÂĂĽÂÂĂĽÂÂĂĽÂÂ°ĂŚÂÂĂ§ÂťÂ­ĂŠÂ˘ÂĂ¨Â˝Â˝ĂĽÂÂ¨Ă¤ÂšÂŚĂĽÂšÂśĂĽÂÂĂ§ÂŁÂĂ§ÂÂĂ§ÂźÂĂĽÂ­ÂĂŻÂźÂĂ¤Â¸ÂĂĽÂżÂĂ§Â­ÂĂ§ÂÂ¨ĂŚÂÂˇĂŚÂťÂĂĽÂÂ°ĂŚÂÂŤĂĽÂ°?*/

    fun prefetchesFullBookInBackground(b: LoadedBook): Boolean {
        return isEpubBook(b) || BookFileType.isMobi(b.uri) ||
            BookFileType.isMobi(activity.displayTitle) || BookFileType.isMobi(activity.fileKey)
    }


    fun requestStreamBatch() {
        val streamer = activity.bookStreamer ?: return
        val needSeek = activity.pendingRestorePara > streamLastIdx
        if (activity.streamerLoading) {
            if (!needSeek) return
            activity.streamerJob?.cancel()
        }
        activity.streamerLoading = true
        activity.streamerJob?.cancel()
        activity.streamerJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefetchAll = activity.book?.let { prefetchesFullBookInBackground(it) } == true
                while (isActive && activity.bookStreamer != null && !streamComplete) {
                    val target = activity.pendingRestorePara
                    if (target > streamLastIdx) {
                        // ĂŚÂÂ˘ĂĽÂ¤Â/Ă¨ÂˇÂłĂ¨Â˝ÂŹĂŻÂźÂĂ¤ÂźÂĂĽÂÂĂĽÂÂ Ă¨Â˝Â˝ĂĽÂÂ°Ă§ÂÂŽĂŚÂ ÂĂŚÂŽÂľĂŻÂźÂEPUB Ă¤ÂźÂĂĽÂÂĂ¨ÂŻ?spine Ă§ÂŁÂĂ§ÂÂĂ§ÂźÂĂĽÂ­ÂĂŻÂź?
                        val hasMore = streamer.loadUntilParagraphBlocking(target)
                        if (!hasMore || streamComplete || streamLastIdx >= target) {
                            if (!prefetchAll) break
                            continue
                        }
                        continue
                    }
                    if (!prefetchAll) {
                        streamer.loadNextBatchBlocking()
                        break
                    }
                    // EPUB/MOBIĂŻÂźÂĂĽÂÂĂĽÂÂ°ĂŚÂÂ˘ĂŚÂÂ˘ĂŠÂ˘ÂĂ¨Â˝Â˝ĂĽÂÂ¨Ă¤ÂšÂŚĂŻÂźÂĂ¤Â¸ÂĂĽÂżÂĂ§Â­ÂĂ§ÂÂ¨ĂŚÂÂˇĂŚÂťÂĂĽÂÂ°ĂŚÂÂŤĂĽÂ°?
                    val hasMore = streamer.loadNextBatchBlocking()
                    if (!hasMore || streamComplete) break
                    delay(60)
                }
            } finally {
                activity.streamerLoading = false
            }
        }
    }


    fun attachBookStreamer(streamer: com.whj.reader.data.BookStreamer) {
        activity.bookStreamer = streamer
        streamLastIdx = activity.book?.paragraphs?.lastIndex ?: -1
        streamComplete = false
        streamer.start(
            onUpdate = { loaded ->
                // Ă¤Â¸?loadNextBatchBlocking ĂĽÂÂĂ§ÂşÂżĂ§Â¨ÂĂŻÂźÂĂ¤ÂžÂĂ§ÂťÂ­Ă¨Â˝Â˝ĂĽÂžÂŞĂ§ÂÂŻĂĽÂÂ¤ĂŚÂ?
                streamLastIdx = loaded.paragraphs.lastIndex
                streamComplete = loaded.isComplete
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    applyLoadedBook(loaded, isInitial = false)
                    maybeRevealReaderAfterRestore()
                }
            },
            onComplete = { loaded ->
                streamLastIdx = loaded.paragraphs.lastIndex
                streamComplete = true
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    activity.bookStreamer = null
                    activity.streamerLoading = false
                    applyLoadedBook(loaded, isInitial = false)
                    updateStreamTitle(loaded)
                    // ĂĽÂÂ¨Ă¤ÂšÂŚĂ§ÂťÂĂŚÂÂĂ¤ÂťÂĂŚÂÂŞĂĽÂÂ°Ă§ÂÂŽĂŚÂ ÂĂĽÂÂĂ¨ÂÂ˝ĂĽÂÂ¨ĂŚÂÂŤĂĽÂ°Âž
                    if (activity.pendingRestorePara > 0) {
                        val last = loaded.paragraphs.lastIndex
                        if (last >= 0) {
                            reader.scrollToParagraph(activity.pendingRestorePara.coerceAtMost(last))
                        }
                        activity.pendingRestorePara = -1
                    }
                    maybeRevealReaderAfterRestore()
                }
            },
            onProgress = { msg, cur, tot ->
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    updateStreamTitle(
                        activity.book?.copy(
                            streamCurrent = cur,
                            streamTotal = tot.coerceAtLeast(1),
                            isComplete = false,
                        ) ?: return@runOnUiThread,
                        msg,
                    )
                }
            },
        )
        // EPUB/MOBI ĂŠÂŚÂĂĽÂąÂĂĽÂÂĂĽÂÂĂĽÂÂ°ĂŚÂÂ˘ĂŚÂÂ˘ĂŠÂ˘ÂĂ¨Â˝Â˝ĂĽÂÂ¨Ă¤ÂšÂŚĂĽÂšÂśĂĽÂÂĂ§ÂźÂĂĽÂ­?
        requestStreamBatch()
    }

    /** Ă¤Â¸?streamer ĂŚÂÂšĂĽÂ¤ÂĂ§ÂÂĂĽÂÂĂ§ÂşÂżĂ§Â¨ÂĂŚÂÂ´ĂŚÂÂ°Ă§ÂÂĂŚÂŽÂľĂ¨ÂÂ˝ĂŚÂÂŤĂ§Â´Â˘ĂĽÂźÂ / ĂĽÂŽÂĂŚÂÂĂŚÂ ÂĂ¨ÂŽÂ° */
    @Volatile
    private var streamLastIdx: Int = -1
    @Volatile
    private var streamComplete: Boolean = false


    fun showLoadOverlay(message: String) {
        if (!activity.isBindingReady()) return
        b.loadOverlay.isVisible = true
        b.tvLoadMessage.text = message
        b.tvLoadDetail.text = ""
        b.progressLoad.isIndeterminate = true
    }


    fun updateLoadOverlay(message: String, current: Int, total: Int) {
        if (!activity.isBindingReady()) return
        b.loadOverlay.isVisible = true
        b.tvLoadMessage.text = message.ifBlank { activity.getString(R.string.loading_book) }
        if (total > 0) {
            b.progressLoad.isIndeterminate = false
            b.progressLoad.max = total
            b.progressLoad.progress = current.coerceIn(0, total)
            b.tvLoadDetail.text = activity.getString(R.string.load_progress_detail, current, total)
        } else {
            b.progressLoad.isIndeterminate = true
            b.tvLoadDetail.text = ""
        }
    }


    fun hideLoadOverlay() {
        if (!activity.isBindingReady()) return
        b.loadOverlay.isVisible = false
    }

    /** ĂŠÂÂżĂŚÂÂĂĽÂÂžĂ§ÂÂ Ă˘Â?ĂĽÂÂ¨ĂĽÂąÂĂ§ÂÂĂĽÂÂžĂŻÂźÂĂ¤ÂšÂŚĂĽÂÂĂĽÂÂ¨ĂŠÂÂ¨ĂĽÂÂžĂ§ÂÂĂĽÂÂŻĂŚÂťÂĂĽÂÂ¨ĂĽÂÂĂŚÂÂ˘ĂŻÂź?*/

    fun isImageOnlyMobi(loaded: LoadedBook): Boolean {
        if (loaded.imagePaths.isEmpty()) return false
        if (!BookFileType.isMobi(loaded.uri) && !activity.settingsController.isMobiBook()) return false
        return loaded.paragraphs.none { p ->
            !p.isBlockImage && p.text.any { !it.isWhitespace() }
        }
    }


    fun progressFileExt(): String {
        val fromUri = BookFileType.extensionOf(activity.fileKey)
        if (fromUri != null) return fromUri
        val title = activity.displayTitle
        val fromTitle = BookFileType.extensionOf(title)
        if (fromTitle != null) return fromTitle
        return when {
            activity.settingsController.isMobiBook() -> ".mobi"
            BookFileType.isEpub(activity.fileKey) || BookFileType.isEpub(title) -> ".epub"
            BookFileType.isPdf(activity.fileKey) || BookFileType.isPdf(title) -> ".pdf"
            else -> ".txt"
        }
    }


    fun loadContent() {
        val asset = activity.intent.getStringExtra(ReadingActivity.EXTRA_ASSET)
        val uriStr = activity.intent.getStringExtra(ReadingActivity.EXTRA_URI)
        val titleExtra = activity.intent.getStringExtra(ReadingActivity.EXTRA_TITLE)
        val encodingExtra = activity.intent.getStringExtra(ReadingActivity.EXTRA_ENCODING)
        val preferredEncoding = encodingExtra?.takeIf { it.isNotBlank() }
            ?: uriStr?.let { BookEncodingStore.get(activity, it) }
            ?: asset?.let { BookEncodingStore.get(activity, "asset://$it") }
        val bookKey = uriStr ?: asset?.let { "asset://$it" }.orEmpty()
        val chineseMode = if (bookKey.isNotBlank()) {
            BookChineseModeStore.get(activity, bookKey)
        } else {
            ChineseConvert.Mode.OFF
        }

        activity.streamerJob?.cancel()
        activity.bookStreamer?.cancel()
        activity.bookStreamer = null
        activity.streamerLoading = false
        showLoadOverlay(activity.getString(R.string.loading_book))
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
                                activity.runOnUiThread { updateLoadOverlay(msg, cur, tot) }
                            },
                        )
                        else -> error("未指定文件")
                    }
                }
            }
            result.onSuccess { open ->
                // ĂŠÂÂŽĂ§Â˝ÂŠĂ¤ÂżÂĂŚÂÂĂĽÂÂ°ĂĽÂŽÂĂ¤Â˝ÂĂĽÂŽÂĂŚÂÂĂŻÂźÂĂ¨Â§?maybeRevealReaderAfterRestoreĂŻÂźÂĂŻÂźÂĂŠÂÂżĂĽÂÂĂŠÂÂŞĂŠÂŚÂĂŠÂĄ?
                applyLoadedBook(open.book, isInitial = true)
                val streamer = open.streamer
                if (streamer != null) {
                    attachBookStreamer(streamer)
                } else {
                    maybeRevealReaderAfterRestore()
                }
            }.onFailure { e ->
                hideLoadOverlay()
                showOpenFailGuide(
                    reason = OpenFailGuide.reasonFrom(e),
                    detail = e.message,
                    exitOnClose = true,
                )
            }
        }
    }


    fun applyLoadedBook(loaded: LoadedBook, isInitial: Boolean) {
        val resolved = activity.navController.applyCustomChapterPatternIfSaved(loaded)
        activity.book = resolved
        activity.fileKey = resolved.uri
        activity.displayTitle = resolved.title
        updateStreamTitle(resolved)
        if (resolved.imagePaths.isNotEmpty()) {
            activity.mangaPaths = resolved.imagePaths.filter { File(it).isFile }
        }
        activity.settingsController.updateMobiModeButtons()

        if (isInitial) {
            val usedEnc = resolved.encoding
            if (!usedEnc.equals("UTF-8", ignoreCase = true)) {
                if (BookEncodingStore.get(activity, loaded.uri) == null) {
                    BookEncodingStore.set(activity, loaded.uri, usedEnc)
                }
            }
            activity.allowProgressSave = false
            // Ă¤ÂťÂĂŚÂÂ ĂŚÂ­ÂŁĂŚÂÂĂ§ÂşÂŻĂĽÂÂž MOBI Ă¨ÂÂŞĂĽÂÂ¨Ă¨ÂżÂĂŚÂźÂŤĂ§ÂÂťĂŻÂźÂĂŚÂÂĂŚÂ­ÂŁĂŚÂÂĂ§ÂÂ MOBI ĂŚÂÂĂĽÂźÂĂŚÂÂśĂŠÂťÂĂ¨ÂŽÂ¤ĂŚÂ­ÂŁĂŚÂÂĂŚÂ¨ÂĄĂĽÂź?
            val imageOnly = isImageOnlyMobi(resolved)
            val viewMode = AppSettings.mobiViewMode(activity)
            val wantManga = activity.settingsController.isMobiBook() &&
                activity.mangaPaths.isNotEmpty() &&
                imageOnly &&
                viewMode != AppSettings.MobiViewMode.TEXT
            if (wantManga) {
                activity.mangaContinuousPref = viewMode == AppSettings.MobiViewMode.CONTINUOUS
                if (viewMode == AppSettings.MobiViewMode.TEXT) {
                    AppSettings.setMobiViewMode(activity, AppSettings.MobiViewMode.MANGA)
                }
            }
            val saved = AppSettings.progressFor(activity, loaded.uri)
            val shelfPara = BookshelfStore.findBookByUri(activity, loaded.uri)?.lastParagraph ?: 0
            val mangaView = if (wantManga) {
                AppSettings.loadMangaViewState(activity, loaded.uri)
            } else {
                null
            }
            activity.pendingRestorePara = if (wantManga) {
                // ĂŚÂźÂŤĂ§ÂÂťĂ¨ÂżÂĂĽÂşÂŚĂĽÂ?enterMangaMode ĂŠÂÂĂŚÂÂĂĽÂÂžĂ§ÂÂĂ§Â´Â˘ĂĽÂźÂĂŚÂÂ˘ĂĽÂ¤Â
                -1
            } else {
                maxOf(saved, shelfPara)
            }
            // Ă¤ÂšÂŚĂŚÂÂśĂ¨ÂżÂĂĽÂşÂŚĂŻÂźÂĂŚÂźÂŤĂ§ÂÂťĂ§ÂÂ¨ĂĽÂÂžĂ§ÂÂĂ§Â´Â˘ĂĽÂźÂĂŻÂźÂĂŠÂÂżĂĽÂÂĂŚÂÂĂĽÂźÂĂŚÂÂśĂŚÂÂĂ¨ÂżÂĂĽÂşÂŚĂĽÂÂ˛ĂŚÂÂ 0
            val shelfProgressHint = if (wantManga) {
                val rp = ReadingProgressStore.get(activity, loaded.uri)
                when {
                    mangaView != null && mangaView.index >= 0 -> mangaView.index
                    rp != null && rp.position >= 0 -> rp.position
                    else -> shelfPara
                }
            } else {
                activity.pendingRestorePara.coerceAtLeast(0)
            }

            // ĂŚÂÂ˘ĂĽÂ¤ÂĂĽÂŽÂĂŚÂÂĂĽÂÂĂŠÂÂĂ¨ÂÂĂŚÂ­ÂŁĂŚÂ?+ Ă¤ÂżÂĂŚÂÂĂĽÂÂ Ă¨Â˝Â˝ĂŠÂÂŽĂ§Â˝ÂŠĂŻÂźÂĂŠÂÂżĂĽÂÂĂŠÂÂŞĂŠÂŚÂĂŠÂĄÂľ 1 Ă§Â§?
            reader.visibility = android.view.View.INVISIBLE
            if (activity.pendingRestorePara > 0) {
                updateLoadOverlay(
                    activity.getString(R.string.loading_locate_progress),
                    0,
                    0,
                )
            }
            reader.setContent(resolved.paragraphs)
            activity.highlightController.reloadBookHighlights()
            activity.settingsController.applyStyleToUi(keepAnchor = false)
            tts.setDocument(
                resolved.paragraphs,
                TextLoader.SentenceLineBreakMode.NEWLINE,
            )
            tts.setSessionTitle(activity.displayTitle.ifBlank { resolved.title })
            activity.chromeController.applyChromeVisibility()

            BookshelfStore.updateIfExists(
                activity,
                uri = resolved.uri,
                displayName = resolved.title,
                lastParagraph = shelfProgressHint,
            )
            if (!wantManga) {
                ReadingProgressStore.saveTxt(
                    activity,
                    resolved.uri,
                    activity.pendingRestorePara.coerceIn(0, resolved.paragraphs.lastIndex.coerceAtLeast(0)),
                    resolved.paragraphs.size,
                    fileExt = progressFileExt(),
                )
            }
            AppSettings.setLastBook(activity, resolved.uri, resolved.title)

            // ĂĽÂ¸ÂĂĽÂąÂĂĽÂÂĂĽÂÂĂĽÂ°ÂĂ¨ÂŻÂĂĽÂŽÂĂ¤Â˝ÂĂŻÂźÂĂŚÂÂŞĂĽÂÂ°Ă§ÂÂŽĂŚÂ ÂĂĽÂÂĂ¤Â¸?reveal
            reader.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (wantManga) {
                    activity.mangaController.enterMangaMode(restoreIndex = true)
                } else {
                    tryRestoreProgress(resolved)
                    maybeRevealReaderAfterRestore()
                }
            }
        } else {
            if (activity.mangaMode) {
                // ĂŚÂźÂŤĂ§ÂÂťĂŚÂ¨ÂĄĂĽÂźÂĂ¤Â¸ÂĂ¤ÂžÂĂ¨ÂľÂĂŚÂľÂĂĽÂźÂĂŚÂ­ÂŁĂŚÂÂĂŻÂźÂĂ¤ÂťÂĂĽÂÂˇĂŚÂÂ°Ă¨ÂżÂĂĽÂşÂŚĂŚÂÂĂŚÂĄ?
                activity.updateProgressLabel()
                return
            }
            reader.updateContent(resolved.paragraphs, keepScroll = true)
            if (activity.isTtsReady()) {
                tts.updateDocumentKeepPosition(
                    resolved.paragraphs,
                    TextLoader.SentenceLineBreakMode.NEWLINE,
                )
            }
            tryRestoreProgress(resolved)
            maybeRevealReaderAfterRestore()
            activity.updateProgressLabel()
            // ĂŚÂÂťĂŚÂŽÂľĂŚÂÂ°ĂĽÂÂĂĽÂÂĂŚÂÂśĂĽÂÂˇĂŚÂÂ°Ă¨ÂżÂĂĽÂşÂŚĂĽÂ­ÂĂĽÂÂ¨Ă§Â?total
            ReadingProgressStore.saveTxt(
                activity,
                resolved.uri,
                reader.firstVisibleParagraph(),
                resolved.paragraphs.size,
                fileExt = progressFileExt(),
            )
        }
    }


    fun tryRestoreProgress(loaded: LoadedBook) {
        val target = activity.pendingRestorePara
        if (target <= 0) {
            activity.updateProgressLabel()
            return
        }
        if (target in loaded.paragraphs.indices) {
            reader.scrollToParagraph(target)
            // Ă§ÂÂŽĂŚÂ ÂĂĽÂˇÂ˛ĂĽÂÂ¨ĂĽÂ˝ÂĂĽÂÂĂĽÂˇÂ˛Ă¨Â˝Â˝ĂĽÂÂĽĂŚÂ­ÂŁĂŚÂÂĂĽÂÂ Ă˘Â?ĂŚÂ¸ÂĂŠÂÂ¤ĂĽÂžÂĂŚÂÂ˘ĂĽÂ¤?
            if (loaded.isComplete || target <= loaded.paragraphs.lastIndex) {
                activity.pendingRestorePara = -1
            }
        } else {
            // Ă¤ÂťÂĂĽÂÂ¨ĂĽÂÂ Ă¨Â˝Â˝ĂŻÂźÂĂŚÂÂ´ĂŚÂÂ°ĂŠÂÂŽĂ§Â˝ÂŠĂ¨ÂżÂĂĽÂş?
            val tot = loaded.streamTotal.coerceAtLeast(1)
            val cur = loaded.streamCurrent.coerceIn(0, tot)
            updateLoadOverlay(
                activity.getString(R.string.loading_locate_progress),
                cur,
                tot,
            )
        }
        activity.updateProgressLabel()
    }

    /**
     * ĂŚÂÂĂĽÂźÂĂ¤ÂšÂŚĂŚÂÂśĂŻÂźÂĂ§ÂÂŽĂŚÂ ÂĂŚÂŽÂľĂĽÂˇÂ˛ĂĽÂ°ÂąĂ§ÂťÂŞĂŻÂźÂĂŚÂÂĂŚÂÂ ĂŠÂÂĂŚÂÂ˘ĂĽÂ¤ÂĂŻÂźÂĂŚÂÂĂŚÂÂžĂ§Â¤ÂşĂŚÂ­ÂŁĂŚÂÂĂĽÂšÂśĂĽÂÂłĂŚÂÂĂŠÂÂŽĂ§Â˝ÂŠĂŁÂ?
     * ĂŠÂÂżĂĽÂÂĂĽÂÂĂŠÂÂŞĂŠÂŚÂĂŠÂĄÂľĂĽÂÂĂ¨ÂˇÂłĂ¨Â˝ÂŹĂŁÂ?
     */

    fun maybeRevealReaderAfterRestore() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!activity.isReaderReady()) return
        if (activity.mangaMode) {
            hideLoadOverlay()
            activity.allowProgressSave = true
            activity.updateProgressLabel()
            return
        }
        // Ă¤ÂťÂĂĽÂÂ¨Ă§Â­ÂĂ§ÂÂŽĂŚÂ ÂĂŚÂŽÂľ
        if (activity.pendingRestorePara > 0) {
            val last = activity.book?.paragraphs?.lastIndex ?: -1
            if (activity.pendingRestorePara > last) {
                // Ă§ÂťÂ§Ă§ÂťÂ­ĂĽÂÂ Ă¨Â˝Â˝
                if (activity.bookStreamer != null) {
                    requestStreamBatch()
                } else {
                    // ĂŚÂ?streamer ĂĽÂÂ´Ă¤ÂťÂĂ¨ÂśÂĂ¨ÂÂĂĽÂÂ´ĂŻÂźÂĂ¨ÂÂ˝ĂĽÂÂ°ĂŚÂÂŤĂĽÂ°ÂžĂĽÂšÂśĂŚÂÂžĂ§Â¤?
                    activity.pendingRestorePara = -1
                }
                return
            }
            // ĂĽÂˇÂ˛ĂĽÂÂ¨Ă¨ÂÂĂĽÂÂ´ĂĽÂÂĂ¤Â˝Â flag ĂŚÂÂŞĂŚÂ¸Â
            activity.book?.let { tryRestoreProgress(it) }
            if (activity.pendingRestorePara > 0) return
        }
        if (reader.visibility != android.view.View.VISIBLE) {
            reader.visibility = android.view.View.VISIBLE
        }
        hideLoadOverlay()
        activity.allowProgressSave = true
        activity.saveProgress(reader.firstVisibleParagraph())
        activity.updateProgressLabel()
        activity.updateChapterTitleBar(reader.firstVisibleParagraph())
        activity.navController.updateBookmarkButton()
    }


    fun updateStreamTitle(loaded: LoadedBook, progressMsg: String? = null) {
        if (!activity.isBindingReady()) return
        activity.displayTitle = loaded.title
        val base = loaded.title
        // ĂĽÂÂ Ă¨Â˝Â˝Ă¤Â¸Â­ĂŚÂÂśĂĽÂˇÂŚĂ¤ÂžÂ§ĂŚÂÂĂ¤ÂťÂśĂĽÂÂĂĽÂÂĂŠÂÂĂĽÂÂ Ă§ÂÂžĂĽÂÂĂŚÂŻ?
        val left = when {
            loaded.isComplete -> base
            progressMsg != null -> "$base ĂÂˇ $progressMsg"
            else -> {
                val tot = loaded.streamTotal.coerceAtLeast(1)
                val cur = loaded.streamCurrent.coerceIn(0, tot)
                val pct = (cur * 100 / tot).coerceIn(0, 99)
                activity.getString(R.string.load_stream_title, base, pct)
            }
        }
        b.tvBookName.text = left
        b.tvReadTitle.text = left // ĂĽÂÂźĂĽÂŽÂš
        if (activity.isReaderReady()) {
            activity.updateChapterTitleBar(reader.firstVisibleParagraph())
        } else {
            b.tvChapterTitle.text = ""
        }
    }

    /** ĂŠÂĄÂśĂŠÂÂ¨ĂĽÂÂłĂ¤ÂžÂ§ĂŻÂźÂĂĽÂ˝ÂĂĽÂÂĂ§ÂŤÂ Ă¨ÂÂĂŚÂ ÂĂŠÂ˘ÂĂŻÂźÂĂŚÂ ÂšĂŚÂÂŽĂĽÂÂŻĂ¨Â§ÂĂŚÂŽÂľĂĽÂÂĂĽÂÂĂŚÂÂžĂŚÂÂĂ¨ÂżÂĂ§ÂŤÂ Ă¨ÂÂĂŻÂźÂ */

    fun showOpenFailGuide(
        reason: OpenFailGuide.Reason,
        detail: String?,
        exitOnClose: Boolean = true,
    ) {
        val title = activity.intent.getStringExtra(ReadingActivity.EXTRA_TITLE)
        val isAsset = !activity.intent.getStringExtra(ReadingActivity.EXTRA_ASSET).isNullOrBlank()
        OpenFailGuide.show(
            activity = activity,
            reason = reason,
            detail = detail,
            bookTitle = title,
            onGrantPermission = if (isAsset) {
                null
            } else {
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        activity.openFailPermissionLauncher.launch(
                            StorageAccess.manageAllFilesIntent(activity),
                        )
                    } else {
                        // ĂŚÂÂ§Ă§ÂÂĂŻÂźÂĂĽÂźÂĂĽÂŻÂźĂĽÂÂĂ§ÂÂ´ĂŚÂÂĽĂŠÂÂĂ¨ÂŻÂ
                        loadContent()
                    }
                }
            },
            onReselect = if (isAsset) {
                null
            } else {
                {
                    activity.reselectDocLauncher.launch(
                        arrayOf(
                            "text/plain",
                            "text/*",
                            "application/pdf",
                            "application/octet-stream",
                        ),
                    )
                }
            },
            onClose = {
                if (exitOnClose) activity.finish()
            },
        )
    }


    fun applyReselectedUri(uri: Uri) {
        val oldUri = activity.intent.getStringExtra(ReadingActivity.EXTRA_URI)
        activity.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    }
                }.getOrNull()
                    ?: uri.lastPathSegment
                    ?: activity.intent.getStringExtra(ReadingActivity.EXTRA_TITLE)
                    ?: activity.getString(R.string.unnamed)
            }
            // Ă¨ÂÂĽĂ§ÂÂ¨ĂŚÂÂˇĂŠÂÂĂŠÂÂĂ¤ÂşÂ PDFĂŻÂźÂĂ¨ÂˇÂłĂ¨Â˝?PDF ĂŠÂÂĂ¨ÂŻÂť
            if (BookFileType.isPdfUri(activity, uri, name) ||
                BookFileType.isPdf(name)
            ) {
                val stable = withContext(Dispatchers.IO) {
                    OpenFailGuide.bindReselectedFile(
                        activity,
                        oldUri = oldUri,
                        newUri = uri,
                        displayName = name,
                    )
                }
                Toasts.show(activity, R.string.open_failed_reselect_done)
                activity.startActivity(
                    Intent(activity, PdfReadingActivity::class.java)
                        .putExtra(PdfReadingActivity.EXTRA_URI, stable)
                        .putExtra(PdfReadingActivity.EXTRA_TITLE, BookFileType.stripBookExt(name)),
                )
                activity.finish()
                return@launch
            }
            val stable = withContext(Dispatchers.IO) {
                OpenFailGuide.bindReselectedFile(
                    activity,
                    oldUri = oldUri,
                    newUri = uri,
                    displayName = name,
                )
            }
            activity.intent.putExtra(ReadingActivity.EXTRA_URI, stable)
            activity.intent.putExtra(ReadingActivity.EXTRA_TITLE, BookFileType.stripBookExt(name))
            activity.intent.removeExtra(ReadingActivity.EXTRA_ASSET)
            Toasts.show(activity, R.string.open_failed_reselect_done)
            loadContent()
        }
    }

    /** TTS ĂĽÂÂĂĽÂ§ÂĂĽÂ?ĂŚÂÂŞĂĽÂ°ÂąĂ§ÂťÂŞĂŻÂźÂĂ¤Â¸ÂĂĽÂźÂš ToastĂŻÂźÂĂ§ÂÂśĂŚÂÂĂŚÂÂĂŚÂĄÂĂĽÂˇÂ˛ĂĽÂ?TTS ĂŠÂÂ˘ĂŚÂÂżĂŚÂÂžĂ§Â¤ÂşĂŻÂź?*/
}
