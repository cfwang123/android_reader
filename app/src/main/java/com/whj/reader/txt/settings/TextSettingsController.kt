package com.whj.reader.txt.settings
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
import com.whj.reader.model.BgImageScaleMode
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
 * TXT reading settings controller (extracted from ReadingActivity).
 */
class TextSettingsController(
    private val activity: ReadingActivity,
) {

    private val b get() = activity.binding
    private val reader get() = activity.reader
    private val readMenu get() = activity.readMenu
    private val exportPanel get() = activity.exportPanel
    private val settingsPanel get() = activity.settingsPanel
    private val tts get() = activity.tts
    private val ctx get() = activity


    private val customFontChips = mutableListOf<com.google.android.material.button.MaterialButton>()
    private val textureChips = mutableListOf<com.google.android.material.button.MaterialButton>()
    private val textColorSwatches = mutableListOf<android.view.View>()
    private val bgColorSwatches = mutableListOf<android.view.View>()
    private val textColorPresets = intArrayOf(
        0xFF2C2C2C.toInt(), 0xFF1A1A1A.toInt(), 0xFF3E3224.toInt(), 0xFF1E3A24.toInt(),
        0xFF1A3344.toInt(), 0xFF4A148C.toInt(), 0xFFB71C1C.toInt(), 0xFF666666.toInt(),
        0xFFC8C8C8.toInt(), 0xFFFFFFFF.toInt(),
    )
    private val bgColorPresets = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFF7F4ED.toInt(), 0xFFFFF8E7.toInt(), 0xFFF4ECD8.toInt(),
        0xFFC7EDCC.toInt(), 0xFFDCEEF8.toInt(), 0xFFF0E8F5.toInt(), 0xFFECEFF1.toInt(),
        0xFF1A1A1A.toInt(), 0xFF263238.toInt(),
    )

    fun openStyleSettingsPanel() {
        val panel = b.settingsPanel.root
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
        val lp = panel.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.height = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        lp.gravity = android.view.Gravity.BOTTOM
        panel.layoutParams = lp
        b.settingsPanelContainer.isVisible = true
        panel.post {
            if (!b.settingsPanelContainer.isVisible) return@post
            val natural = panel.height
            if (natural > maxH) {
                val lp2 = panel.layoutParams as android.widget.FrameLayout.LayoutParams
                lp2.height = maxH
                lp2.gravity = android.view.Gravity.BOTTOM
                panel.layoutParams = lp2
            }
            scrollSettingsPanelToCurrentSelections()
        }
    }

    /** ????çé??????ç??????°?????????¨???????°???éä¸­é??*/

    fun scrollSettingsPanelToCurrentSelections() {
        if (!activity.isSettingsPanelReady()) return
        refreshTextureChips()
        refreshBgColorSwatches()
        refreshTextColorSwatches()
        refreshFontChips()
        refreshImportBgSettings()
        val scroll = settingsPanel.root
        scroll.post {
            if (!b.settingsPanelContainer.isVisible) return@post
            val margin = (scroll.height * 0.06f).toInt().coerceAtLeast(0)
            scrollNestedToChild(scroll, settingsPanel.textureRow, margin)
            scrollHorizontalRowToSelected(settingsPanel.textureRow)
            scrollHorizontalRowToSelected(settingsPanel.bgColorRow)
            scrollHorizontalRowToSelected(settingsPanel.textColorRow)
            scrollHorizontalRowToSelected(settingsPanel.fontRow)
        }
    }


    fun scrollNestedToChild(scroll: NestedScrollView, target: View, marginTop: Int) {
        val rect = android.graphics.Rect()
        scroll.offsetDescendantRectToMyCoords(target, rect)
        scroll.smoothScrollTo(0, (rect.top - marginTop).coerceAtLeast(0))
    }


    fun scrollHorizontalRowToSelected(row: LinearLayout) {
        val selected = findSelectedSettingsChip(row) ?: return
        val hsv = row.parent as? HorizontalScrollView ?: return
        row.post {
            val x = (selected.left + selected.width / 2 - hsv.width / 2).coerceAtLeast(0)
            hsv.smoothScrollTo(x, 0)
        }
    }


    fun findSelectedSettingsChip(row: ViewGroup): View? {
        val strokeSel = (2f * activity.resources.displayMetrics.density).toInt().coerceAtLeast(2)
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            when (child) {
                is MaterialButton -> {
                    if (child.alpha >= 0.99f && child.strokeWidth >= strokeSel) return child
                }
                else -> {
                    if (child.elevation > 0.5f || child.scaleX > 1.05f) return child
                }
            }
        }
        return null
    }


    fun setupSettingsPanel() {
        b.settingsScrim.setOnClickListener {
            b.settingsPanelContainer.isVisible = false
        }

        fun bindSeekers() {
            settingsPanel.seekFontSize.progress = (activity.style.fontSizeSp - 12f).toInt().coerceIn(0, 24)
            settingsPanel.tvFontSize.text = activity.style.fontSizeSp.toInt().toString()
            settingsPanel.seekLineSpacing.progress =
                ((activity.style.lineSpacingMult - 1.0f) * 10).toInt().coerceIn(0, 20)
            settingsPanel.tvLineSpacing.text = String.format("%.1f", activity.style.lineSpacingMult)
            settingsPanel.seekParaSpacing.progress = activity.style.paraSpacingDp.coerceIn(0, 32)
            settingsPanel.tvParaSpacing.text = activity.style.paraSpacingDp.toString()
        }
        bindSeekers()

        settingsPanel.seekFontSize.setOnSeekBarChangeListener(simpleSeek { p ->
            activity.style = activity.style.copy(fontSizeSp = 12f + p)
            settingsPanel.tvFontSize.text = activity.style.fontSizeSp.toInt().toString()
            // ??¨??????é??§???é?????ä?ç??
            persistAndApplyStyle(keepAnchor = true)
        })
        settingsPanel.seekLineSpacing.setOnSeekBarChangeListener(simpleSeek { p ->
            activity.style = activity.style.copy(lineSpacingMult = 1.0f + p / 10f)
            settingsPanel.tvLineSpacing.text = String.format("%.1f", activity.style.lineSpacingMult)
            persistAndApplyStyle(keepAnchor = true)
        })
        settingsPanel.seekParaSpacing.setOnSeekBarChangeListener(simpleSeek { p ->
            activity.style = activity.style.copy(paraSpacingDp = p)
            settingsPanel.tvParaSpacing.text = p.toString()
            persistAndApplyStyle(keepAnchor = true)
        })
        settingsPanel.seekBgImageAlpha.setOnSeekBarChangeListener(simpleSeek { p ->
            val alpha = p.coerceIn(0, 100)
            activity.style = activity.style.copy(customBgImageAlpha = alpha)
            settingsPanel.tvBgImageAlpha.text =
                activity.getString(R.string.bg_image_opacity_value, alpha)
            persistAndApplyStyle(keepAnchor = true)
        })
        settingsPanel.btnBgImageStretch.setOnClickListener {
            setBgImageScaleMode(BgImageScaleMode.STRETCH)
        }
        settingsPanel.btnBgImageFitCenter.setOnClickListener {
            setBgImageScaleMode(BgImageScaleMode.FIT_CENTER)
        }

        rebuildTextureChips()
        rebuildTextColorSwatches()
        rebuildBgColorSwatches()

        settingsPanel.chipFontDefault.setOnClickListener { setFont(ReaderFonts.ID_DEFAULT) }
        settingsPanel.chipFontSans.setOnClickListener { setFont(ReaderFonts.ID_SANS) }
        settingsPanel.chipFontSerif.setOnClickListener { setFont(ReaderFonts.ID_SERIF) }
        settingsPanel.chipFontMono.setOnClickListener { setFont(ReaderFonts.ID_MONO) }
        settingsPanel.chipFontInstall.setOnClickListener { launchInstallFont() }
        rebuildCustomFontChips()

        settingsPanel.btnLayoutCompact.setOnClickListener {
            activity.style = activity.style.copy(fontSizeSp = 16f, lineSpacingMult = 1.2f, paraSpacingDp = 4)
            bindSeekers()
            persistAndApplyStyle(keepAnchor = true)
        }
        settingsPanel.btnLayoutDefault.setOnClickListener {
            activity.style = activity.style.copy(fontSizeSp = 18f, lineSpacingMult = 1.4f, paraSpacingDp = 8)
            bindSeekers()
            persistAndApplyStyle(keepAnchor = true)
        }
        settingsPanel.btnLayoutLoose.setOnClickListener {
            activity.style = activity.style.copy(fontSizeSp = 20f, lineSpacingMult = 1.7f, paraSpacingDp = 16)
            bindSeekers()
            persistAndApplyStyle(keepAnchor = true)
        }

        settingsPanel.btnMobiModeText.setOnClickListener {
            setMobiViewMode(AppSettings.MobiViewMode.TEXT)
        }
        settingsPanel.btnMobiModeManga.setOnClickListener {
            setMobiViewMode(AppSettings.MobiViewMode.MANGA)
        }
        settingsPanel.btnMobiModeContinuous.setOnClickListener {
            setMobiViewMode(AppSettings.MobiViewMode.CONTINUOUS)
        }
        updateMobiModeButtons()
    }


    fun isMobiBook(): Boolean {
        val b = activity.book
        return BookFileType.isMobi(activity.fileKey) ||
            BookFileType.isMobi(activity.displayTitle) ||
            (b != null && BookFileType.isMobi(b.uri)) ||
            BookFileType.isMobi(activity.intent.getStringExtra(ReadingActivity.EXTRA_TITLE)) ||
            BookFileType.isMobi(activity.intent.getStringExtra(ReadingActivity.EXTRA_URI))
    }

    /**
     * ? ???­??ç MOBI??ä?
ç?????/???/? ä?????????????¨???ç??¨?????
     */

    fun currentMobiViewMode(): AppSettings.MobiViewMode {
        return when {
            !activity.mangaMode -> AppSettings.MobiViewMode.TEXT
            activity.mangaContinuousPref -> AppSettings.MobiViewMode.CONTINUOUS
            else -> AppSettings.MobiViewMode.MANGA
        }
    }


    fun updateMobiModeButtons() {
        if (!activity.isSettingsPanelReady()) return
        val show = isMobiBook()
        settingsPanel.rowMobiViewMode.isVisible = show
        if (!show) return
        val mode = currentMobiViewMode()
        val primary = AppTheme.primary(activity)
        val density = activity.resources.displayMetrics.density
        val stroke = (1.5f * density).toInt().coerceAtLeast(2)
        fun styleToggle(btn: MaterialButton, selected: Boolean, labelRes: Int) {
            btn.alpha = 1f
            btn.text = activity.getString(labelRes)
            btn.isSelected = selected
            if (selected) {
                btn.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(primary)
                btn.setTextColor(0xFFFFFFFF.toInt())
                btn.strokeWidth = 0
                btn.strokeColor = android.content.res.ColorStateList.valueOf(primary)
            } else {
                btn.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                btn.setTextColor(0xFF666666.toInt())
                btn.strokeWidth = stroke
                btn.strokeColor =
                    android.content.res.ColorStateList.valueOf(0xFFCCCCCC.toInt())
            }
        }
        styleToggle(
            settingsPanel.btnMobiModeText,
            mode == AppSettings.MobiViewMode.TEXT,
            R.string.mobi_mode_text,
        )
        styleToggle(
            settingsPanel.btnMobiModeManga,
            mode == AppSettings.MobiViewMode.MANGA,
            R.string.mobi_mode_manga,
        )
        styleToggle(
            settingsPanel.btnMobiModeContinuous,
            mode == AppSettings.MobiViewMode.CONTINUOUS,
            R.string.mobi_mode_continuous,
        )
        val modeLabel = activity.getString(
            when (mode) {
                AppSettings.MobiViewMode.TEXT -> R.string.mobi_mode_text
                AppSettings.MobiViewMode.MANGA -> R.string.mobi_mode_manga
                AppSettings.MobiViewMode.CONTINUOUS -> R.string.mobi_mode_continuous
            },
        )
        settingsPanel.tvMobiModeCurrent.text = activity.getString(R.string.mobi_mode_current, modeLabel)
    }


    fun setMobiViewMode(mode: AppSettings.MobiViewMode) {
        if (!isMobiBook()) return
        if (mode == currentMobiViewMode()) {
            updateMobiModeButtons()
            return
        }
        when (mode) {
            AppSettings.MobiViewMode.TEXT -> {
                AppSettings.setMobiViewMode(activity, mode)
                if (activity.mangaMode) activity.mangaController.exitMangaMode()
                activity.mangaContinuousPref = false
            }
            AppSettings.MobiViewMode.MANGA,
            AppSettings.MobiViewMode.CONTINUOUS,
            -> {
                val paths = activity.mangaPaths.ifEmpty { activity.book?.imagePaths.orEmpty() }
                    .filter { File(it).isFile }
                if (paths.isEmpty()) {
                    Toasts.show(activity, R.string.mobi_manga_no_images)
                    updateMobiModeButtons()
                    return
                }
                activity.mangaPaths = paths
                val wantContinuous = mode == AppSettings.MobiViewMode.CONTINUOUS
                AppSettings.setMobiViewMode(activity, mode)
                if (activity.mangaMode) {
                    // 已在漫画模式：切换连续/单图
                    activity.mangaController.switchMangaImageLayout(wantContinuous)
                } else {
                    activity.mangaContinuousPref = wantContinuous
                    activity.mangaController.enterMangaMode(restoreIndex = true)
                }
            }
        }
        updateMobiModeButtons()
    }

    /**
     * ??? â???ç?­????
     * - ???â??ç?­??????????°é??é?
     * - ??ç?­â???????§???
???§é?ç§???¤§çä¸?? ??ç??­??ä¸­?? é??????
     */

    fun simpleSeek(onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    /** ???¤é´???????ä????/ ?¤??é?ç? ??? */

    fun toggleNightStyle() {
        val isNight = activity.style.bgTextureId == BgTextures.NIGHT_GRAIN ||
            activity.style.theme == ReadTheme.NIGHT ||
            !ParagraphAdapter.isLightColor(activity.style.customBgColor)
        if (isNight) {
            activity.style = activity.style.copy(
                theme = ReadTheme.DEFAULT,
                bgTextureId = "",
                customBgColor = 0xFFF7F4ED.toInt(),
                textColor = 0xFF2C2C2C.toInt(),
            )
        } else {
            activity.style = activity.style.copy(
                theme = ReadTheme.NIGHT,
                bgTextureId = BgTextures.NIGHT_GRAIN,
                customBgColor = 0xFF1C1C1E.toInt(),
                textColor = 0xFFC8C8C8.toInt(),
            )
        }
        persistAndApplyStyle(keepAnchor = true)
        refreshTextureChips()
        refreshTextColorSwatches()
        refreshBgColorSwatches()
    }


    fun setBgTexture(id: String) {
        when (id) {
            BgTextures.NONE -> {
                // 纯色：清除纹理与导入图
                activity.style = activity.style.copy(
                    bgTextureId = "",
                    customBgImageFile = "",
                    theme = ReadTheme.CUSTOM,
                )
                persistAndApplyStyle(keepAnchor = true)
                refreshTextureChips()
                refreshBgColorSwatches()
            }
            BgTextures.IMPORT -> {
                activity.importBgImageLauncher.launch(arrayOf("image/*"))
            }
            else -> {
                val base = BgTextures.baseColor(id) ?: activity.style.customBgColor
                val autoText = ParagraphAdapter.textColorForBackground(base)
                activity.style = activity.style.copy(
                    bgTextureId = id,
                    theme = if (id == BgTextures.NIGHT_GRAIN) ReadTheme.NIGHT else ReadTheme.CUSTOM,
                    textColor = autoText,
                    customBgColor = base,
                    customBgImageFile = "",
                )
                persistAndApplyStyle(keepAnchor = true)
                refreshTextureChips()
                refreshTextColorSwatches()
                refreshBgColorSwatches()
            }
        }
    }


    fun rebuildTextureChips() {
        if (!activity.isSettingsPanelReady()) return
        val row = settingsPanel.textureRow
        row.removeAllViews()
        textureChips.clear()
        val density = activity.resources.displayMetrics.density
        val marginEnd = (8 * density).toInt()
        fun addChip(id: String, label: String, tint: Int?) {
            val btn = MaterialButton(
                activity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * density).toInt(),
                ).also { lp -> lp.marginEnd = marginEnd }
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                text = label
                tag = id
                if (tint != null && tint != 0) {
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(tint)
                    setTextColor(ParagraphAdapter.textColorForBackground(tint))
                }
                setOnClickListener { setBgTexture(id) }
            }
            row.addView(btn)
            textureChips.add(btn)
        }
        for (spec in BgTextures.PRESETS) {
            addChip(spec.id, activity.getString(spec.labelRes), spec.baseColor)
        }
        // 导入图片
        addChip(BgTextures.IMPORT, activity.getString(R.string.bg_texture_import), null)
        refreshTextureChips()
    }


    fun refreshTextureChips() {
        if (!activity.isSettingsPanelReady()) return
        val cur = when {
            activity.style.bgTextureId == BgTextures.IMPORT -> BgTextures.IMPORT
            activity.style.bgTextureId.isBlank() -> BgTextures.NONE
            else -> activity.style.bgTextureId
        }
        fun mark(btn: MaterialButton, selected: Boolean) {
            btn.alpha = if (selected) 1f else 0.55f
            btn.strokeWidth = if (selected) (2 * activity.resources.displayMetrics.density).toInt() else 1
        }
        textureChips.forEach { btn ->
            mark(btn, (btn.tag as? String) == cur)
        }
    }

    /** ?¸¸ç¨?­ä???????????ç????*/

    fun rebuildTextColorSwatches() {
        if (!activity.isSettingsPanelReady()) return
        val row = settingsPanel.textColorRow
        row.removeAllViews()
        textColorSwatches.clear()
        val density = activity.resources.displayMetrics.density
        // ??????ç?ç??? 36dp ç?2/3
        val size = (24 * density).toInt()
        val gap = (8 * density).toInt()
        for (c in textColorPresets) {
            val v = makeColorSwatchView(size, gap, c) {
                applyTextColor(c)
            }
            row.addView(v)
            textColorSwatches.add(v)
        }
        // ?°?é¨??????ä? â?HSV
        val custom = makeCustomColorChip(size, gap) {
            HsvColorPickerDialog.show(
                activity,
                activity.getString(R.string.color_picker_text_title),
                activity.style.textColor,
            ) { c -> applyTextColor(c) }
        }
        row.addView(custom)
        textColorSwatches.add(custom)
        refreshTextColorSwatches()
    }


    fun rebuildBgColorSwatches() {
        if (!activity.isSettingsPanelReady()) return
        val row = settingsPanel.bgColorRow
        row.removeAllViews()
        bgColorSwatches.clear()
        val density = activity.resources.displayMetrics.density
        val size = (24 * density).toInt()
        val gap = (8 * density).toInt()
        for (c in bgColorPresets) {
            val v = makeColorSwatchView(size, gap, c) {
                applyBgColor(c)
            }
            row.addView(v)
            bgColorSwatches.add(v)
        }
        val custom = makeCustomColorChip(size, gap) {
            HsvColorPickerDialog.show(
                activity,
                activity.getString(R.string.color_picker_bg_title),
                activity.style.customBgColor,
            ) { c -> applyBgColor(c) }
        }
        row.addView(custom)
        bgColorSwatches.add(custom)
        refreshBgColorSwatches()
    }


    fun makeColorSwatchView(
        size: Int,
        marginEnd: Int,
        color: Int,
        onClick: () -> Unit,
    ): View {
        return View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).also {
                it.marginEnd = marginEnd
            }
            background = androidx.core.content.ContextCompat.getDrawable(
                activity,
                R.drawable.bg_color_swatch,
            )?.mutate()
            backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            tag = color
            setOnClickListener { onClick() }
            contentDescription = String.format("#%06X", color and 0xFFFFFF)
        }
    }


    fun makeCustomColorChip(size: Int, marginEnd: Int, onClick: () -> Unit): View {
        return MaterialButton(
            activity,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                size,
            ).also { it.marginEnd = marginEnd }
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            text = activity.getString(R.string.color_custom)
            tag = "custom"
            setOnClickListener { onClick() }
        }
    }


    fun applyTextColor(c: Int) {
        activity.style = activity.style.copy(textColor = c or 0xFF000000.toInt())
        persistAndApplyStyle(keepAnchor = true)
        refreshTextColorSwatches()
    }


    fun isImportBgActive(): Boolean =
        activity.style.bgTextureId == BgTextures.IMPORT && activity.style.customBgImageFile.isNotBlank()


    fun refreshImportBgSettings() {
        if (!activity.isSettingsPanelReady()) return
        val show = isImportBgActive()
        settingsPanel.rowImportBgAlpha.isVisible = show
        settingsPanel.rowImportBgScale.isVisible = show
        settingsPanel.tvBgColorImportHint.isVisible = show
        if (show) {
            val alpha = activity.style.customBgImageAlpha.coerceIn(0, 100)
            settingsPanel.seekBgImageAlpha.progress = alpha
            settingsPanel.tvBgImageAlpha.text =
                activity.getString(R.string.bg_image_opacity_value, alpha)
            updateBgImageScaleButtons()
        }
    }


    fun setBgImageScaleMode(mode: BgImageScaleMode) {
        if (activity.style.customBgImageScaleMode == mode) return
        activity.style = activity.style.copy(customBgImageScaleMode = mode)
        persistAndApplyStyle(keepAnchor = true)
        updateBgImageScaleButtons()
    }


    fun updateBgImageScaleButtons() {
        if (!activity.isSettingsPanelReady()) return
        if (!isImportBgActive()) return
        val mode = activity.style.customBgImageScaleMode
        val primary = AppTheme.primary(activity)
        val density = activity.resources.displayMetrics.density
        val stroke = (1.5f * density).toInt().coerceAtLeast(2)
        fun styleToggle(btn: MaterialButton, selected: Boolean, labelRes: Int) {
            btn.alpha = 1f
            btn.text = activity.getString(labelRes)
            btn.isSelected = selected
            if (selected) {
                btn.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(primary)
                btn.setTextColor(0xFFFFFFFF.toInt())
                btn.strokeWidth = 0
                btn.strokeColor = android.content.res.ColorStateList.valueOf(primary)
            } else {
                btn.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                btn.setTextColor(0xFF666666.toInt())
                btn.strokeWidth = stroke
                btn.strokeColor =
                    android.content.res.ColorStateList.valueOf(0xFFCCCCCC.toInt())
            }
        }
        styleToggle(
            settingsPanel.btnBgImageStretch,
            mode == BgImageScaleMode.STRETCH,
            R.string.bg_image_scale_stretch,
        )
        styleToggle(
            settingsPanel.btnBgImageFitCenter,
            mode == BgImageScaleMode.FIT_CENTER,
            R.string.bg_image_scale_fit_center,
        )
    }


    fun applyBgColor(c: Int) {
        val color = c or 0xFF000000.toInt()
        val keepImport = isImportBgActive()
        activity.style = if (keepImport) {
            activity.style.copy(
                theme = ReadTheme.CUSTOM,
                customBgColor = color,
            )
        } else {
            activity.style.copy(
                theme = ReadTheme.CUSTOM,
                customBgColor = color,
                bgTextureId = "",
                customBgImageFile = "",
            )
        }
        persistAndApplyStyle(keepAnchor = true)
        if (!keepImport) refreshTextureChips()
        refreshBgColorSwatches()
    }


    fun refreshTextColorSwatches() {
        if (!activity.isSettingsPanelReady()) return
        val cur = activity.style.textColor or 0xFF000000.toInt()
        textColorSwatches.forEach { v ->
            val selected = when (val t = v.tag) {
                is Int -> (t or 0xFF000000.toInt()) == cur
                else -> {
                    // ????ä????????ä¸?¨é????ä¸­
                    textColorPresets.none { (it or 0xFF000000.toInt()) == cur }
                }
            }
            markSwatchSelected(v, selected)
        }
    }


    fun refreshBgColorSwatches() {
        if (!activity.isSettingsPanelReady()) return
        val solidMode = activity.style.bgTextureId.isBlank() || activity.style.bgTextureId == BgTextures.NONE
        val importMode = isImportBgActive()
        val selectable = solidMode || importMode
        val cur = activity.style.customBgColor or 0xFF000000.toInt()
        bgColorSwatches.forEach { v ->
            val selected = if (!selectable) {
                false
            } else {
                when (val t = v.tag) {
                    is Int -> (t or 0xFF000000.toInt()) == cur
                    else -> bgColorPresets.none { (it or 0xFF000000.toInt()) == cur }
                }
            }
            markSwatchSelected(v, selected)
        }
    }


    fun markSwatchSelected(v: View, selected: Boolean) {
        if (v is MaterialButton) {
            v.alpha = if (selected) 1f else 0.55f
            v.strokeWidth = if (selected) (2 * activity.resources.displayMetrics.density).toInt() else 1
            return
        }
        val dens = activity.resources.displayMetrics.density
        v.scaleX = if (selected) 1.12f else 1f
        v.scaleY = if (selected) 1.12f else 1f
        v.foreground = if (selected) {
            androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.bg_color_swatch_ring)
        } else {
            null
        }
        // ???????¤??éä¸­??
        v.elevation = if (selected) 3f * dens else 0f
    }


    fun importBackgroundImage(uri: Uri) {
        activity.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                BgTextures.importFromUri(activity, uri)
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (name.isNullOrBlank()) {
                Toasts.show(activity, R.string.bg_texture_import_fail)
                return@launch
            }
            activity.style = activity.style.copy(
                bgTextureId = BgTextures.IMPORT,
                customBgImageFile = name,
                theme = ReadTheme.CUSTOM,
            )
            persistAndApplyStyle(keepAnchor = true)
            refreshTextureChips()
            refreshBgColorSwatches()
            Toasts.show(activity, R.string.bg_texture_import_ok)
        }
    }


    fun setFont(id: String) {
        activity.style = activity.style.copy(fontFamily = id)
        persistAndApplyStyle(keepAnchor = true)
        refreshFontChips()
    }


    fun launchInstallFont() {
        // */*??é¨??OEM ä¸?´é?font/* MIME
        activity.installFontLauncher.launch(arrayOf("*/*"))
    }


    fun installCustomFont(uri: Uri) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                CustomFontStore.installFromUri(activity, uri)
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when (result) {
                is CustomFontStore.InstallResult.Ok -> {
                    Toasts.show(
                        activity,
                        activity.getString(R.string.font_install_ok, result.entry.name),
                    )
                    rebuildCustomFontChips()
                    setFont(result.entry.id)
                }
                is CustomFontStore.InstallResult.Fail -> {
                    val msg = when (result.reason) {
                        CustomFontStore.FailReason.BAD_FORMAT ->
                            activity.getString(R.string.font_install_bad_format)
                        CustomFontStore.FailReason.TOO_LARGE ->
                            activity.getString(R.string.font_install_too_large)
                        CustomFontStore.FailReason.LIMIT ->
                            activity.getString(R.string.font_install_limit, CustomFontStore.MAX_COUNT)
                        CustomFontStore.FailReason.INVALID_FONT,
                        CustomFontStore.FailReason.IO,
                        -> activity.getString(R.string.font_install_fail)
                    }
                    Toasts.show(activity, msg)
                }
            }
        }
    }


    fun confirmDeleteCustomFont(entry: CustomFontStore.Entry) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.font_delete_title)
            .setMessage(activity.getString(R.string.font_delete_msg, entry.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                CustomFontStore.delete(activity, entry.id)
                ReaderFonts.invalidate(entry.id)
                if (activity.style.fontFamily == entry.id) {
                    activity.style = activity.style.copy(fontFamily = ReaderFonts.ID_DEFAULT)
                    persistAndApplyStyle(keepAnchor = true)
                }
                rebuildCustomFontChips()
                Toasts.show(activity, activity.getString(R.string.font_deleted, entry.name))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** ??mono ä¸?????
?ä?é´??¨?
??????
?­ä??chip */

    fun rebuildCustomFontChips() {
        if (!activity.isSettingsPanelReady()) return
        val row = settingsPanel.fontRow
        customFontChips.forEach { row.removeView(it) }
        customFontChips.clear()

        val installChip = settingsPanel.chipFontInstall
        val insertAt = row.indexOfChild(installChip).coerceAtLeast(0)
        val density = activity.resources.displayMetrics.density
        val marginEnd = (8 * density).toInt()
        val entries = CustomFontStore.list(activity)
        entries.forEachIndexed { i, entry ->
            val btn = MaterialButton(
                activity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * density).toInt(),
                ).also { lp -> lp.marginEnd = marginEnd }
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                text = entry.name
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                tag = entry.id
                setOnClickListener { setFont(entry.id) }
                setOnLongClickListener {
                    confirmDeleteCustomFont(entry)
                    true
                }
            }
            row.addView(btn, insertAt + i)
            customFontChips.add(btn)
        }
        // ??????­ä???°?????ä¸?­?¨???éé???¤
        if (ReaderFonts.isCustom(activity.style.fontFamily) &&
            entries.none { it.id == activity.style.fontFamily }
        ) {
            activity.style = activity.style.copy(fontFamily = ReaderFonts.ID_DEFAULT)
            persistAndApplyStyle(keepAnchor = true)
        }
        refreshFontChips()
    }


    fun refreshFontChips() {
        if (!activity.isSettingsPanelReady()) return
        val id = activity.style.fontFamily
        fun mark(btn: MaterialButton, selected: Boolean) {
            btn.alpha = if (selected) 1f else 0.55f
            btn.strokeWidth = if (selected) (2 * activity.resources.displayMetrics.density).toInt() else 1
        }
        mark(
            settingsPanel.chipFontDefault,
            ReaderFonts.normalizeId(id) == ReaderFonts.ID_DEFAULT && !ReaderFonts.isCustom(id),
        )
        mark(settingsPanel.chipFontSans, id == ReaderFonts.ID_SANS)
        mark(settingsPanel.chipFontSerif, id == ReaderFonts.ID_SERIF)
        mark(settingsPanel.chipFontMono, id == ReaderFonts.ID_MONO)
        customFontChips.forEach { btn ->
            mark(btn, btn.tag == id)
        }
        // 安装按钮不高亮
        settingsPanel.chipFontInstall.alpha = 0.85f
    }


    fun persistAndApplyStyle(keepAnchor: Boolean = true) {
        AppSettings.saveStyle(activity, activity.style)
        applyStyleToUi(keepAnchor = keepAnchor)
    }


    fun applyStyleToUi(keepAnchor: Boolean = true) {
        val textureId = activity.style.bgTextureId
        val textureBase = BgTextures.baseColor(textureId)
        val solidBg = activity.style.customBgColor or 0xFF000000.toInt()
        val bgForChrome = when {
            textureId == BgTextures.IMPORT -> solidBg
            textureBase != null -> textureBase
            else -> solidBg
        }
        val textColor = activity.style.textColor
        val hl = if (ParagraphAdapter.isLightColor(textColor)) {
            // 浅色字→深蓝高亮；深色字→偏黄高亮
            0x884A90C0.toInt()
        } else {
            0x66FFE082.toInt()
        }

        val isImportBg = textureId == BgTextures.IMPORT && activity.style.customBgImageFile.isNotBlank()
        val bgDrawable: android.graphics.drawable.Drawable? = when {
            isImportBg ->
                BgTextures.importedDrawable(
                    activity,
                    activity.style.customBgImageFile,
                    activity.style.customBgImageScaleMode,
                )
            textureId.isNotBlank() && textureId != BgTextures.NONE ->
                BgTextures.tiledDrawable(activity, textureId)
            else -> null
        }

        if (bgDrawable != null) {
            if (isImportBg) {
                // 导入图：垫色 + 半透明图片叠在根布局
                val alpha = (activity.style.customBgImageAlpha.coerceIn(0, 100) * 255) / 100
                bgDrawable.alpha = alpha
                b.rootReading.background = LayerDrawable(
                    arrayOf(ColorDrawable(solidBg), bgDrawable),
                )
                reader.background = null
                reader.setBackgroundColor(0x00000000)
                // 底栏透出根布局背景；正文按 bottomObscuredPx 裁剪
                b.readStatusBar.background = null
                b.readStatusBar.setBackgroundColor(0x00000000)
                b.readTitleBar.background = null
                b.readTitleBar.setBackgroundColor(0x00000000)
            } else {
                fun copyBg(): android.graphics.drawable.Drawable? =
                    BgTextures.tiledDrawable(activity, textureId)
                b.rootReading.background = bgDrawable
                reader.background = copyBg()
                b.readStatusBar.background = copyBg()
                b.readTitleBar.background = copyBg()
            }
            b.tvReadTitle.background = null
            b.tvReadTitle.setBackgroundColor(0x00000000)
        } else {
            b.rootReading.setBackgroundColor(bgForChrome)
            reader.setBackgroundColor(bgForChrome)
            b.readStatusBar.setBackgroundColor(bgForChrome)
            b.readTitleBar.setBackgroundColor(bgForChrome)
            b.tvReadTitle.setBackgroundColor(bgForChrome)
        }
        activity.window.statusBarColor = bgForChrome
        activity.window.navigationBarColor = if (isImportBg) {
            android.graphics.Color.TRANSPARENT
        } else {
            bgForChrome
        }
        val darkChrome = !ParagraphAdapter.isLightColor(bgForChrome) ||
            textureId == BgTextures.NIGHT_GRAIN ||
            activity.style.theme == ReadTheme.NIGHT
        val metaColor = if (darkChrome) 0xFF9A9A9A.toInt() else 0xFF888888.toInt()
        b.tvBookName.setTextColor(metaColor)
        b.tvChapterTitle.setTextColor(metaColor)
        b.tvReadTitle.setTextColor(metaColor)
        b.tvBattery.setTextColor(metaColor)
        b.tvClock.setTextColor(metaColor)
        b.tvProgress.setTextColor(metaColor)
        @Suppress("DEPRECATION")
        if (darkChrome || activity.immersive) {
            activity.window.decorView.systemUiVisibility = 0
        } else {
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        reader.applyStyle(activity.style, textColor, hl, keepAnchor = keepAnchor)
        activity.updateProgressLabel()
        if (activity.isSettingsPanelReady()) {
            refreshTextureChips()
            refreshTextColorSwatches()
            refreshBgColorSwatches()
            refreshImportBgSettings()
        }
        if (activity.isReaderReady()) {
            b.readStatusBar.post { activity.chromeController.syncReaderBottomObscured() }
        }
    }

    /**
     * ?
¨?????
???ç???é??????? ?ä?çç??ç?ç??? ???¨????§ç??
¨?????§?[applyLandscapeFullscreenUi]????
     */

    fun formatFontSizeLabel(sp: Float): String {
        val rounded = kotlin.math.round(sp * 2f) / 2f
        return if (abs(rounded - rounded.toInt()) < 0.01f) {
            rounded.toInt().toString()
        } else {
            String.format("%.1f", rounded)
        }
    }

}
