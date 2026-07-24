package com.whj.reader.pdf.chrome

import com.whj.reader.PdfReadingActivity

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.whj.reader.R
import com.whj.reader.data.AppSettings
import com.whj.reader.databinding.ActivityPdfReadingBinding
import com.whj.reader.databinding.PanelPdfSettingsBinding
import com.whj.reader.databinding.PanelReadMenuBinding
import com.whj.reader.model.OrientationMode
import com.whj.reader.model.PdfPageMode
import com.whj.reader.util.OrientationHelper
import com.whj.reader.util.ReaderLog
import kotlin.math.abs
import kotlin.math.max

/**
 * PDF 阅读 Chrome 控制器：顶部标题栏、底部 8 图标菜单、沉浸/横屏全屏、昼夜主题、横竖屏切换、
 * 底部 inset/padding 同步（避免尾页被控制栏挡住）。
 */
class PdfChromeController(
    private val activity: PdfReadingActivity,
) {

    private val ctx: Context get() = activity
    private val b: ActivityPdfReadingBinding get() = activity.binding
    private val readMenu: PanelReadMenuBinding get() = activity.readMenu
    private val pdfSettings: PanelPdfSettingsBinding get() = activity.pdfSettings
    private val window: Window get() = activity.window

    private var pendingPdfOrientRelayout: Runnable? = null

    fun setupBottomChromeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.bottomChrome) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav.bottom)
            insets
        }
        b.bottomChrome.requestApplyInsets()
        ViewCompat.setOnApplyWindowInsetsListener(
            pdfSettings.root,
        ) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav.bottom)
            insets
        }
        pdfSettings.root.requestApplyInsets()
    }

    fun isLandscape(): Boolean {
        val mode = AppSettings.pdfOrientationMode(ctx)
        val root = b.root
        return OrientationHelper.isEffectiveLandscape(activity, mode, root)
    }

    fun isWindowLandscape(): Boolean {
        val root = b.root
        return OrientationHelper.isWindowLandscape(activity, root)
    }

    fun applyPortraitColumnLayout() {
        val bottom = b.pdfContainer.paddingBottom
        if (b.pdfContainer.paddingLeft != 0 || b.pdfContainer.paddingRight != 0) {
            b.pdfContainer.setPadding(0, 0, 0, bottom)
        }
        activity.updatePdfZoomChrome()
        applyNightUi()
    }

    fun collapseBottomChromeLayout(hideMenuHost: Boolean) {
        if (hideMenuHost) {
            b.readMenuHost.visibility = View.GONE
            b.readMenuHost.layoutParams = b.readMenuHost.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        if (!activity.exportPanelOpen) {
            b.ttsExportHost.visibility = View.GONE
            b.ttsExportHost.visibility = View.GONE
        }
        if (!activity.ttsBarOpen) {
            b.ttsBar.visibility = View.GONE
        }
        b.bottomChrome.translationY = 0f
        b.readStatusBar.translationY = 0f
        b.bottomChrome.minimumHeight = 0
        val lp = b.bottomChrome.layoutParams
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp is ConstraintLayout.LayoutParams) {
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            lp.topToTop = ConstraintLayout.LayoutParams.UNSET
        }
        b.bottomChrome.layoutParams = lp
        val slp = b.readStatusBar.layoutParams
        if (slp is ConstraintLayout.LayoutParams) {
            slp.bottomToTop = b.bottomChrome.id
            slp.topToTop = ConstraintLayout.LayoutParams.UNSET
            b.readStatusBar.layoutParams = slp
        }
        b.bottomChrome.requestLayout()
        b.readStatusBar.requestLayout()
    }

    fun sanitizeBottomChrome() {
        collapseBottomChromeLayout(hideMenuHost = !activity.chromeVisible)
    }

    fun refreshBottomChromeAfterModal(reason: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        b.bottomChrome.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            b.bottomChrome.requestApplyInsets()
            collapseBottomChromeLayout(hideMenuHost = !activity.chromeVisible)
            applyChromeVisibility()
            activity.logPdfChrome(reason)
        }
    }

    fun prepareBottomChromeForBlockingModal() {
        if (activity.isFinishing || activity.isDestroyed) return
        collapseBottomChromeLayout(hideMenuHost = true)
        syncPdfContentBottomInset()
        b.bottomChrome.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            collapseBottomChromeLayout(hideMenuHost = true)
            syncPdfContentBottomInset()
            activity.logPdfChrome("modalPrepare")
        }
    }

    fun applyLandscapeFullscreenUi() {
        val landUi = isLandscape()
        b.readStatusBar.isVisible = !landUi
        if (landUi) {
            b.tvReadTitle.isVisible = false
        } else if (!activity.immersive) {
            b.tvReadTitle.isVisible = true
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (landUi) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            activity.updatePdfZoomChrome()
        } else if (activity.immersive) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            b.tvReadTitle.isVisible = false
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
            b.tvReadTitle.isVisible = true
        }
    }

    fun applyChromeVisibility() {
        applyLandscapeFullscreenUi()
        b.topBar.isVisible = activity.chromeVisible && !activity.exportPanelOpen
        b.ttsBar.isVisible = !activity.chromeVisible && !activity.exportPanelOpen && activity.ttsBarOpen
        val menuHost = b.readMenuHost
        val exportHost = b.ttsExportHost
        if (activity.exportPanelOpen) {
            menuHost.visibility = View.GONE
            readMenu.root.visibility = View.GONE
            exportHost.visibility = View.VISIBLE
            exportHost.bringToFront()
            if (b.readStatusBar.isVisible) b.readStatusBar.bringToFront()
            b.bottomChrome.bringToFront()
        } else if (activity.chromeVisible) {
            exportHost.visibility = View.GONE
            menuHost.visibility = View.VISIBLE
            readMenu.root.visibility = View.VISIBLE
            val lp = menuHost.layoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            menuHost.layoutParams = lp
            menuHost.bringToFront()
            if (b.readStatusBar.isVisible) b.readStatusBar.bringToFront()
            b.bottomChrome.bringToFront()
            b.topBar.bringToFront()
        } else {
            menuHost.visibility = View.GONE
            exportHost.visibility = View.GONE
            collapseBottomChromeLayout(hideMenuHost = true)
        }
        syncPdfContentBottomInset()
        b.bottomChrome.post { syncPdfContentBottomInset() }
    }

    fun syncPdfContentBottomInset() {
        var pad = 0
        if (b.readStatusBar.isVisible) {
            pad += b.readStatusBar.height.coerceAtLeast(0)
        }
        val bc = b.bottomChrome
        if (bc.visibility == View.VISIBLE) {
            val ttsH = if (b.ttsBar.isVisible) b.ttsBar.height.coerceAtLeast(0) else 0
            val menuH = if (b.readMenuHost.isVisible && activity.chromeVisible) {
                b.readMenuHost.height.coerceAtLeast(0)
            } else {
                0
            }
            val expH = if (b.ttsExportHost.isVisible && activity.exportPanelOpen) {
                b.ttsExportHost.height.coerceAtLeast(0)
            } else {
                0
            }
            val inner = max(ttsH, max(menuH, expH))
            if (inner > 0) {
                pad += inner + bc.paddingBottom.coerceAtLeast(0)
            } else if (activity.ttsBarOpen && !activity.chromeVisible) {
                pad += (56f * ctx.resources.displayMetrics.density).toInt() +
                    bc.paddingBottom.coerceAtLeast(0)
            }
        }
        val rv = b.rvPdfPages
        if (rv.paddingBottom != pad) {
            rv.setPadding(rv.paddingLeft, rv.paddingTop, rv.paddingRight, pad)
            rv.clipToPadding = false
        }
        val sideL = b.pdfContainer.paddingLeft
        val sideR = b.pdfContainer.paddingRight
        if (activity.pageMode == PdfPageMode.SINGLE) {
            if (b.pdfContainer.paddingBottom != pad) {
                b.pdfContainer.setPadding(sideL, 0, sideR, pad)
            }
        } else if (b.pdfContainer.paddingBottom != 0) {
            b.pdfContainer.setPadding(sideL, 0, sideR, 0)
        }
    }

    fun applyNightUi() {
        val bg = if (activity.night) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        val bar = if (activity.night) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
        val meta = if (activity.night) 0xFF888888.toInt() else 0xFF666666.toInt()
        val contentBg = if (activity.night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        b.rootPdf.setBackgroundColor(bg)
        b.readStatusBar.setBackgroundColor(bar)
        b.bottomChrome.setBackgroundColor(bar)
        b.tvReadTitle.setBackgroundColor(bar)
        b.tvReadTitle.setTextColor(meta)
        b.tvBattery.setTextColor(meta)
        b.tvClock.setTextColor(meta)
        b.tvProgress.setTextColor(meta)
        b.tvLoading.setTextColor(if (activity.night) 0xFFCCCCCC.toInt() else 0xFF666666.toInt())
        b.tvLoading.setBackgroundColor(contentBg)
        window.statusBarColor = bar
        window.navigationBarColor = bar
        b.pdfFastScroll.setNight(activity.night)
        activity.updatePdfZoomChrome()
        activity.applyNightFilterToVisibleSurfaces()
    }

    fun updatePdfZoomChrome() {
        val z = b.pdfContainer.contentZoom
        val darkExterior = z < 0.99f || activity.night
        val contentBg = when {
            z < 0.99f -> 0xFF000000.toInt()
            activity.night -> 0xFF000000.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        b.pdfContainer.setBackgroundColor(contentBg)
        if (activity.pageMode == PdfPageMode.CONTINUOUS) {
            b.rvPdfPages.setBackgroundColor(
                if (z < 0.99f) 0xFF000000.toInt() else contentBg,
            )
        }
        b.pdfFastScroll.setOnDarkExterior(darkExterior)
    }

    fun applyNightFilter(iv: ImageView) {
        if (activity.night) {
            val m = ColorMatrix(
                floatArrayOf(
                    -0.8f, 0f, 0f, 0f, 255f,
                    0f, -0.8f, 0f, 0f, 255f,
                    0f, 0f, -0.8f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            iv.colorFilter = ColorMatrixColorFilter(m)
        } else {
            iv.colorFilter = null
        }
    }

    fun hasDisplayCutout(): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val cutout = window.decorView.rootWindowInsets?.displayCutout
            ?: return false
        return cutout.boundingRects.isNotEmpty()
    }

    fun applyImmersive() {
        applyLandscapeFullscreenUi()
        applyNightUi()
    }

    fun applyOrientationMode(
        mode: OrientationMode,
        allowSensor: Boolean = true,
        force: Boolean = false,
    ) {
        val fixed = if (mode == OrientationMode.AUTO) OrientationMode.PORTRAIT else mode
        val changed = OrientationHelper.apply(
            activity,
            fixed,
            allowSensor = false,
            force = force,
        )
        pendingPdfOrientRelayout?.let { activity.binding.root.removeCallbacks(it) }
        val r = Runnable {
            pendingPdfOrientRelayout = null
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            sanitizeBottomChrome()
            activity.relayoutAfterOrientationChange()
        }
        pendingPdfOrientRelayout = r
        activity.binding.root.postDelayed(r, if (changed) 16L else 0L)
    }

    fun toggleChrome() {
        if (activity.chromeVisible) hideChrome() else showChrome()
    }

    fun showChrome() {
        activity.chromeVisible = true
        activity.chromeShownAtMs = android.os.SystemClock.uptimeMillis()
        activity.updateOrientMenuIcon()
        applyChromeVisibility()
        b.topBar.post { activity.updatePdfBookmarkButton() }
    }

    fun hideChrome() {
        if (!activity.chromeVisible && !b.readMenuHost.isVisible && !b.topBar.isVisible) return
        activity.chromeVisible = false
        applyChromeVisibility()
    }

    fun cancelInFlightPdfRenders(reason: String) {
        activity.cancelInFlightPdfRenders(reason)
    }

    fun runWhenPdfViewportSettled(
        reason: String,
        maxTries: Int = 12,
        block: () -> Unit,
    ) {
        val metricsW = ctx.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val metricsH = ctx.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val wantLandscape = metricsW > metricsH
        var tries = 0
        fun attempt() {
            if (activity.isFinishing || activity.isDestroyed) return
            val cw = b.pdfContainer.width
            val ch = b.pdfContainer.height
            val rvW = b.rvPdfPages.width
            val ready = when {
                cw <= 0 || ch <= 0 -> false
                wantLandscape -> cw >= ch * 0.85f
                else -> ch >= cw * 0.85f || abs(cw - metricsW) <= metricsW * 0.12f
            }
            val rvReady = activity.pageMode != PdfPageMode.CONTINUOUS ||
                rvW <= 0 || abs(rvW - metricsW) <= max(48, metricsW / 8)
            if ((ready && rvReady) || tries >= maxTries) {
                ReaderLog.i(
                    ReaderLog.Module.PDF_ORIENT,
                    "viewportSettled reason=$reason tries=$tries " +
                        "container=${cw}x${ch} rvW=$rvW metrics=${metricsW}x${metricsH} " +
                        "ready=$ready rvReady=$rvReady",
                )
                block()
            } else {
                tries++
                activity.binding.root.postDelayed(Runnable { attempt() }, 16L)
            }
        }
        attempt()
    }
}
