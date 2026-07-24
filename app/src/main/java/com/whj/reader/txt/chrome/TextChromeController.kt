package com.whj.reader.txt.chrome
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
 * TXT reading chrome controller (extracted from ReadingActivity).
 */
class TextChromeController(
    private val activity: ReadingActivity,
) {

    private val b get() = activity.binding
    private val reader get() = activity.reader
    private val readMenu get() = activity.readMenu
    private val exportPanel get() = activity.exportPanel
    private val settingsPanel get() = activity.settingsPanel
    private val tts get() = activity.tts
    private val ctx get() = activity


    fun setupBottomChromeInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.bottomChrome) { v, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav.bottom)
            insets
        }
        b.bottomChrome.requestApplyInsets()
    }


    fun updateOrientMenuIcon() {
        if (!activity.isReadMenuReady()) return
        val mode = AppSettings.orientationMode(activity)
        val iv = readMenu.menuOrient.getChildAt(0) as? android.widget.ImageView ?: return
        iv.setImageResource(OrientationHelper.menuIconRes(mode))
        val label = when (mode) {
            OrientationMode.LANDSCAPE -> activity.getString(R.string.orient_landscape)
            else -> activity.getString(R.string.orient_portrait)
        }
        (readMenu.menuOrient.getChildAt(1) as? android.widget.TextView)?.text = label
    }


    fun applyImmersive() {
        applyLandscapeFullscreenUi()
        activity.settingsController.applyStyleToUi()
    }

    /** åå¹¶åå¸§åå¤æ¬¡æ¹åééºï¼é¿åéªç */

    /** æç´¢è·³è½¬åçä¸´æ¶é«äº®ï¼ç¹æãæ»å¨ãç¿»é¡µæ¶æ¸é¤ï¼æè¯»ä¸­é«äº®ç?TTS æ¥ç®¡ï¼?*/

    fun collapseBottomChromeHard() {
        if (!activity.isBindingReady()) return
        activity.chromeVisible = false
        b.readMenuHost.visibility = View.GONE
        b.ttsExportHost.visibility = View.GONE
        if (!activity.ttsBarOpen) {
            b.ttsBar.visibility = View.GONE
        }
        b.readMenuHost.layoutParams = b.readMenuHost.layoutParams.apply {
            width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        b.bottomChrome.translationY = 0f
        b.readStatusBar.translationY = 0f
        b.bottomChrome.minimumHeight = 0
        val lp = b.bottomChrome.layoutParams
        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
            lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        }
        b.bottomChrome.layoutParams = lp
        // ç¶ææ éå¨åºæ ä¸æ¹ãç¶å¸å±åºé¨é¾è·¯
        val slp = b.readStatusBar.layoutParams
        if (slp is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
            slp.bottomToTop = b.bottomChrome.id
            slp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            b.readStatusBar.layoutParams = slp
        }
        b.bottomChrome.requestLayout()
        b.readStatusBar.requestLayout()
    }


    fun toggleChrome() {
        if (activity.chromeVisible) hideChrome() else showChrome()
    }


    fun showChrome() {
        activity.chromeVisible = true
        activity.chromeShownAtMs = android.os.SystemClock.uptimeMillis()
        updateOrientMenuIcon()
        applyChromeVisibility()
        // ç­é¡¶æ å¸å±ååç®ä¹¦ç­¾éç¹ï¼é¿å height=0 / æ§å¾æ ç¶æï¼
        b.topBar.post { activity.navController.updateBookmarkButton() }
    }


    fun hideChrome() {
        activity.chromeVisible = false
        applyChromeVisibility()
    }


    fun hasDisplayCutout(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 28) return false
        val cutout = activity.window.decorView.rootWindowInsets?.displayCutout
            ?: return false
        return cutout.boundingRects.isNotEmpty()
    }

    /** åå®¹æçæ¯å¦æ¨ªå±ï¼è·è§è§æ¨¡å¼ï¼?*/

    fun isLandscape(): Boolean {
        val mode = AppSettings.orientationMode(activity)
        val root = if (activity.isBindingReady()) b.root else null
        return OrientationHelper.isEffectiveLandscape(activity, mode, root)
    }


    fun isWindowLandscape(): Boolean {
        val root = if (activity.isBindingReady()) b.root else null
        return OrientationHelper.isWindowLandscape(activity, root)
    }

    /** å¤§å±ç«å±ï¼æ­£æ?æ¼«ç»æ¶æå±ä¸­ç«æ  */
    /** æ¨ªç«æ¨¡å¼åå æ»¡çªå£ï¼æ¸é¤åå²ä¸çãä¸­é´ç«æ ãå·¦å?padding */

    fun applyPortraitColumnLayout() {
        if (!activity.isBindingReady()) return
        if (b.readerView.paddingLeft != 0 || b.readerView.paddingRight != 0) {
            b.readerView.setPadding(0, 0, 0, 0)
        }
        if (b.mangaHost.paddingLeft != 0 || b.mangaHost.paddingRight != 0) {
            b.mangaHost.setPadding(0, 0, 0, 0)
        }
    }


    fun sanitizeBottomChrome() {
        if (!activity.isBindingReady()) return
        if (!activity.chromeVisible) {
            collapseBottomChromeHard()
            return
        }
        if (!activity.exportPanelOpen) {
            b.ttsExportHost.visibility = View.GONE
        }
        if (!activity.ttsBarOpen) {
            b.ttsBar.visibility = View.GONE
        }
        b.bottomChrome.translationY = 0f
        b.readStatusBar.translationY = 0f
        b.bottomChrome.minimumHeight = 0
        val lp = b.bottomChrome.layoutParams
        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        b.bottomChrome.layoutParams = lp
        b.bottomChrome.requestLayout()
    }

    /**
     * æ¨ªå±æ¨¡å¼ï¼å¨å±æ²æµ?+ èæ é¢?åºç¶ææ ï¼ç«å±æ¨¡å¼ï¼æ¢å¤ã?
     * å¤§å±ä»¥ç¨æ·éæ©çæ¨¡å¼ä¸ºåï¼[isLandscape]ï¼ï¼ä¸è·ç³»ç» letterbox çªå£è¯¯å¤ã?
     */

    fun applyLandscapeFullscreenUi() {
        if (!activity.isBindingReady()) return
        val landUi = isLandscape()
        b.readTitleBar.isVisible = !landUi
        b.readStatusBar.isVisible = !landUi
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (landUi) {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else if (activity.immersive) {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * èåï¼é¡¶æ ?å¾æ ï¼? åæé¢æ¿ / TTS æ¡äºæ¥ï¼
     * - æèå?â?éè TTS ä¸åæé¢æ?
     * - åæé¢æ¿ â?éèèåä¸?TTS
     * - æ èåä¸å·²æå¼æè¯» â?æ¾ç¤º TTS æ?
     */

    fun applyChromeVisibility() {
        applyLandscapeFullscreenUi()
        b.topBar.isVisible = activity.chromeVisible && !activity.exportPanelOpen
        b.ttsBar.isVisible = !activity.chromeVisible && !activity.exportPanelOpen && activity.ttsBarOpen
        val menuHost = b.readMenuHost
        val exportHost = b.ttsExportHost
        if (activity.exportPanelOpen) {
            menuHost.visibility = View.GONE
            exportHost.visibility = View.VISIBLE
            exportPanel.root.visibility = View.VISIBLE
            exportHost.bringToFront()
            if (b.readStatusBar.isVisible) b.readStatusBar.bringToFront()
            b.bottomChrome.bringToFront()
        } else if (activity.chromeVisible) {
            exportHost.visibility = View.GONE
            menuHost.visibility = View.VISIBLE
            readMenu.root.visibility = View.VISIBLE
            val lp = menuHost.layoutParams
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            menuHost.layoutParams = lp
            menuHost.bringToFront()
            if (b.readStatusBar.isVisible) b.readStatusBar.bringToFront()
            b.bottomChrome.bringToFront()
            b.topBar.bringToFront()
            menuHost.post { if (activity.chromeVisible && !activity.exportPanelOpen) forceMenuLayout() }
        } else {
            menuHost.visibility = View.GONE
            exportHost.visibility = View.GONE
        }
        // TTS/åºæ é«åº¦ååååæ­¥ç»éè¯»åºï¼è·è¯»å¯è§å¤å®ï¼?
        b.bottomChrome.post { syncReaderBottomObscured() }
    }


    fun syncReaderBottomObscured() {
        if (!activity.isReaderReady() || !activity.isBindingReady()) return
        var h = 0
        if (b.readStatusBar.isVisible) {
            h += b.readStatusBar.height.coerceAtLeast(0)
        }
        if (b.bottomChrome.isVisible) {
            // ttsBar / menu å?bottomChrome å?
            if (b.ttsBar.isVisible) {
                h += b.ttsBar.height.coerceAtLeast(0)
            }
            if (b.readMenuHost.isVisible && activity.chromeVisible) {
                h += b.readMenuHost.height.coerceAtLeast(0)
            }
            if (b.ttsExportHost.isVisible && activity.exportPanelOpen) {
                h += b.ttsExportHost.height.coerceAtLeast(0)
            }
        }
        reader.bottomObscuredPx = h.toFloat()
    }


    fun premeasureReadMenu() {
        val host = b.readMenuHost
        host.visibility = View.INVISIBLE
        host.post {
            forceMenuLayout()
            if (!activity.chromeVisible) host.visibility = View.GONE
        }
    }

    /** ä¸¤å±åé¡µï¼æ¯æ?fling è½å°ç®æ å±ï¼æ¢æ»å¸éæè¿å± */

    fun setupMenuPagerSnap() {
        val pager = readMenu.menuPager
        pager.pageCount = 2
        pager.onPageSettled = { page -> updateMenuPageDots(page) }
        pager.setOnScrollChangeListener { _, _, _, _, _ ->
            updateMenuPageDots()
        }
    }


    fun updateMenuPageDots(page: Int? = null) {
        if (!activity.isReadMenuReady()) return
        val pager = readMenu.menuPager
        val pageW = pager.width.coerceAtLeast(1)
        val p = page ?: ((pager.scrollX + pageW / 2f) / pageW).toInt().coerceIn(0, 1)
        readMenu.menuDot0.setBackgroundResource(
            if (p == 0) R.drawable.bg_menu_dot_on else R.drawable.bg_menu_dot_off,
        )
        readMenu.menuDot1.setBackgroundResource(
            if (p == 1) R.drawable.bg_menu_dot_on else R.drawable.bg_menu_dot_off,
        )
    }

    /**
     * åºé¨èåï¼ä¸¤å±åé¡µï¼ç¬?1 å±åºå®?**2 è¡?Ã 4 å?*ã?
     * æè½¬åææ°å±å®½éè®¾æ¯é¡µå®½åº¦ï¼ä¿æ 2Ã4ï¼ä¸å³èåæ¶å?[preservePage]ã?
     */

    fun forceMenuLayout(preservePage: Boolean = false) {
        if (!activity.isBindingReady() || !activity.isReadMenuReady()) return
        val host = b.readMenuHost
        val screenW = activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val laidOutW = b.bottomChrome.width.takeIf { it > 0 }
            ?: b.root.width.takeIf { it > 0 }
        val parentW = when {
            laidOutW == null -> screenW
            kotlin.math.abs(laidOutW - screenW) > screenW * 0.15f -> screenW
            else -> laidOutW
        }
        if (parentW <= 0) return
        val prevPage = if (preservePage) {
            val pw = readMenu.menuPager.width.coerceAtLeast(1)
            ((readMenu.menuPager.scrollX + pw / 2f) / pw).toInt().coerceIn(0, 1)
        } else {
            0
        }
        for (page in listOf(readMenu.menuPage0, readMenu.menuPage1)) {
            val lp = page.layoutParams
            lp.width = parentW
            page.layoutParams = lp
        }
        val content = readMenu.menuPagerContent
        val contentLp = content.layoutParams
        contentLp.width = parentW * 2
        content.layoutParams = contentLp
        val wSpec = View.MeasureSpec.makeMeasureSpec(parentW, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        readMenu.root.measure(wSpec, hSpec)
        host.measure(wSpec, hSpec)
        host.requestLayout()
        readMenu.menuPager.requestLayout()
        content.requestLayout()
        b.bottomChrome.requestLayout()
        readMenu.menuPager.settleToPage(prevPage, smooth = false)
        updateMenuPageDots(prevPage)
    }

}
