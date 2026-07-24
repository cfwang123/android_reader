package com.whj.reader.txt.highlight
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
 * TXT reading highlight controller (extracted from ReadingActivity).
 */
class TextHighlightController(
    private val activity: ReadingActivity,
) {

    private val b get() = activity.binding
    private val reader get() = activity.reader
    private val readMenu get() = activity.readMenu
    private val exportPanel get() = activity.exportPanel
    private val settingsPanel get() = activity.settingsPanel
    private val tts get() = activity.tts
    private val ctx get() = activity


    var bookHighlights: List<com.whj.reader.model.Highlight> = emptyList()
        private set
    private var notesMirrorWarned = false

    fun reloadBookHighlights() {
        if (activity.fileKey.isBlank()) {
            bookHighlights = emptyList()
            return
        }
        bookHighlights = sortHighlightsByProgress(
            BookNotesFileStore.load(activity, activity.fileKey).highlights,
        )
        if (activity.isReaderReady()) {
            reader.setPersistentHighlights(bookHighlights)
        }
    }


    fun sortHighlightsByProgress(highlights: List<Highlight>): List<Highlight> =
        highlights.sortedWith(
            compareBy(
                { it.anchor.startParagraph },
                { it.anchor.startOffset },
                { it.createdAt },
            ),
        )


    fun highlightProgressPercent(hl: Highlight, totalParagraphs: Int): Float {
        val para = hl.anchor.startParagraph.coerceAtLeast(0)
        if (activity.isReaderReady()) {
            return reader.progressPercentForParagraph(para).coerceIn(0f, 100f)
        }
        if (totalParagraphs <= 1) return 0f
        return (para.toFloat() / (totalParagraphs - 1).toFloat() * 100f).coerceIn(0f, 100f)
    }


    fun highlightTocItems(totalParagraphs: Int): List<TocItem.HighlightItem> {
        val sorted = sortHighlightsByProgress(bookHighlights)
        return sorted.mapIndexed { index, hl ->
            TocItem.HighlightItem(
                highlight = hl,
                sequence = index + 1,
                progressPercent = highlightProgressPercent(hl, totalParagraphs),
            )
        }
    }


    fun applyHighlightList(highlights: List<Highlight>) {
        bookHighlights = sortHighlightsByProgress(highlights)
    }


    fun saveBookHighlights() {
        if (activity.fileKey.isBlank()) return
        val loc = BookNotesFileStore.save(
            activity,
            BookNotesDocument(bookUri = activity.fileKey, highlights = bookHighlights),
        )
        if (loc.isMirror && !notesMirrorWarned) {
            notesMirrorWarned = true
            Toasts.show(activity, R.string.highlight_mirror_hint)
        }
        if (activity.isReaderReady()) {
            reader.setPersistentHighlights(bookHighlights)
        }
    }


    fun defaultHighlightStyle(): HighlightStyle {
        val color = if (ParagraphAdapter.isLightColor(activity.style.textColor)) {
            HighlightColorPresets.defaultForDarkText()
        } else {
            HighlightColorPresets.defaultForLightText()
        }
        return HighlightStyle(
            mode = HighlightMode.BACKGROUND,
            colorArgb = color,
            opacity = 80,
        )
    }


    fun addHighlightFromSelection() {
        if (!activity.isReaderReady()) return
        val sel = reader.getSelectionRange() ?: return
        if (sel.text.isBlank()) return
        val anchor = TextAnchor(
            startParagraph = sel.startParagraph,
            startOffset = sel.startOffset,
            endParagraph = sel.endParagraph,
            endOffset = sel.endOffset,
        )
        val hl = Highlight.create(
            kind = HighlightKind.TXT,
            anchor = anchor,
            selectedText = sel.text,
            style = defaultHighlightStyle(),
        )
        applyHighlightList(bookHighlights + hl)
        saveBookHighlights()
        reader.dismissTextSelection()
        showHighlightEdit(hl.id)
    }


    fun showHighlightView(highlightId: String) {
        val hl = bookHighlights.find { it.id == highlightId } ?: return
        HighlightNotePopup.showView(
            activity,
            hl,
            onEdit = { showHighlightEdit(it.id) },
            onDelete = { deleted ->
                applyHighlightList(bookHighlights.filter { it.id != deleted.id })
                saveBookHighlights()
                Toasts.show(activity, R.string.highlight_deleted)
            },
        )
    }


    fun showHighlightEdit(highlightId: String) {
        val hl = bookHighlights.find { it.id == highlightId } ?: return
        HighlightNotePopup.showEdit(
            activity,
            hl,
            onSave = { updated ->
                applyHighlightList(
                    bookHighlights.map { if (it.id == updated.id) updated else it },
                )
                saveBookHighlights()
                Toasts.show(activity, R.string.highlight_saved)
            },
            onDelete = { deleted ->
                applyHighlightList(bookHighlights.filter { it.id != deleted.id })
                saveBookHighlights()
                Toasts.show(activity, R.string.highlight_deleted)
            },
        )
    }
}
