package com.whj.reader.txt.manga
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
 * TXT reading manga controller (extracted from ReadingActivity).
 */
class TextMangaController(
    private val activity: ReadingActivity,
) {

    private val b get() = activity.binding
    private val reader get() = activity.reader
    private val readMenu get() = activity.readMenu
    private val exportPanel get() = activity.exportPanel
    private val settingsPanel get() = activity.settingsPanel
    private val tts get() = activity.tts
    private val ctx get() = activity


    var mangaMode = false
    var mangaPaths: List<String> = emptyList()
    var mangaIndex = 0
    var mangaLoadJob: kotlinx.coroutines.Job? = null
    var mangaContinuousSetup = false
    var mangaContinuousAdapter: MangaContinuousAdapter? = null
    var pendingMangaTransform: Triple<Float, Float, Float>? = null
    var suppressMangaViewSave = false
    var mangaGapDecoration: androidx.recyclerview.widget.RecyclerView.ItemDecoration? = null
    var mangaContinuousPref = false

    private var debugPinchRegistered = false
    private val debugMangaPinchReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != ReadingActivity.ACTION_DEBUG_MANGA_PINCH) return
            if (!mangaMode || isMangaContinuousLayout()) {
                com.whj.reader.util.ReaderLog.w(com.whj.reader.util.ReaderLog.Module.MANGA_ZOOM, "debug pinch: need manga single mode")
                return
            }
            if (!activity.isBindingReady()) return
            b.mangaImageView.post {
                b.mangaImageView.debugSimulateFastSidePinch()
            }
        }
    }

    var pendingMangaScrollIndex: Int = -1
    var pendingMangaScrollOffset: Int = 0
    var pendingMangaScrollY: Int = 0
    val mangaBitmapCache = object : android.util.LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(8 * 1024 * 1024, 48 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    fun flushMangaViewStateBeforeLeave() {
        if (!activity.isBindingReady()) return
        b.root.removeCallbacks(saveMangaViewRunnable)
        if (mangaMode && mangaPaths.isNotEmpty() && activity.fileKey.isNotEmpty()) {
            val best = bestMangaTransformForSave()
            ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
                "flushLeave best=$best live=${readLiveMangaTransform()} " +
                    "pending=$pendingMangaTransform suppress=$suppressMangaViewSave " +
                    "cont=${isMangaContinuousLayout()} idx=$mangaIndex",
            )
            // ç???????§ç??????ä???ç????ä¸?  suppress ????????
            writeMangaViewState(best)
        }
    }


    fun registerDebugMangaPinch() {
        if (debugPinchRegistered) return
        val filter = IntentFilter(ReadingActivity.ACTION_DEBUG_MANGA_PINCH)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(debugMangaPinchReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(debugMangaPinchReceiver, filter)
        }
        debugPinchRegistered = true
    }


    fun unregisterDebugMangaPinch() {
        if (!debugPinchRegistered) return
        runCatching { activity.unregisterReceiver(debugMangaPinchReceiver) }
        debugPinchRegistered = false
    }

    /**
     * adb ?§????ä¸ä???????­????
     *   adb shell run-as com.whj.reader sh -c "echo 1 > files/debug_manga_pinch"
     *   adb shell am start -n com.whj.reader/.MainActivity  # ????°
     * ??????adb logcat -s MangaZoom:I
     */

    fun maybeRunMangaPinchDebugFromFile() {
        val flag = java.io.File(activity.filesDir, "debug_manga_pinch")
        if (!flag.exists()) return
        runCatching { flag.delete() }
        if (!mangaMode) {
            ReaderLog.w(ReaderLog.Module.MANGA_ZOOM, "debug file: not mangaMode")
            return
        }
        if (isMangaContinuousLayout()) {
            ReaderLog.w(ReaderLog.Module.MANGA_ZOOM, "debug file: continuous layout, need single")
            return
        }
        if (!activity.isBindingReady()) return
        b.mangaImageView.post {
            ReaderLog.i(ReaderLog.Module.MANGA_ZOOM, "debug file: run simulate")
            b.mangaImageView.debugSimulateFastSidePinch()
        }
    }


    fun switchMangaImageLayout(wantContinuous: Boolean) {
        if (wantContinuous == mangaContinuousPref) return
        // ?? ???????¸????­?ç´???
        if (isMangaContinuousLayout()) {
            mangaIndex = pickMostCompleteVisibleMangaIndex()
        } else {
            mangaIndex = mangaIndex.coerceIn(0, mangaPaths.lastIndex)
        }
        mangaContinuousPref = wantContinuous
        pendingMangaScrollIndex = mangaIndex
        pendingMangaScrollOffset = 0
        pendingMangaScrollY = 0
        pendingMangaTransform = Triple(1f, 0f, 0f)
        ReaderLog.i(ReaderLog.Module.MANGA_ZOOM, "switchLayout cont=$wantContinuous idx=$mangaIndex")
        activity.allowProgressSave = true
        if (wantContinuous) {
            // ???â??ç?­??é?ç????ä???é??é??°???¨?¤´
            suppressMangaViewSave = true
            showMangaLocateUi()
            updateMangaLayoutForOrientation(preservePending = true)
            scheduleRestoreMangaZoom(revealWhenReady = true)
        } else {
            // ??ç?­â?????ç??­??ä¸­?ç´???????? é????
            switchContinuousToSingleSeamless(mangaIndex)
        }
        activity.updateProgressLabel()
    }

    /** ??ç?­â?????ä??ç??­ç´????????¨??ç?­??ä¸???????§?ç ???? */

    fun switchContinuousToSingleSeamless(index: Int) {
        val i = index.coerceIn(0, mangaPaths.lastIndex)
        mangaIndex = i
        mangaContinuousPref = false
        val path = mangaPaths[i]
        val cached = mangaBitmapCache.get(path)
        if (cached != null && !cached.isRecycled) {
            applySingleMangaLayoutWithBitmap(cached)
            suppressMangaViewSave = false
            if (activity.allowProgressSave) activity.saveProgress(i)
            return
        }
        // ?§?ç ?é´??ä????ç?­?????§??ä???ç¤????????
        b.mangaProgress.isVisible = true
        mangaLoadJob?.cancel()
        mangaLoadJob = activity.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                decodeMangaSampled(path, mangaMaxSide())
            }
            if (activity.isFinishing || activity.isDestroyed) {
                bmp?.recycle()
                return@launch
            }
            if (mangaIndex != i || isMangaContinuousLayout()) {
                // ç¨??????°ä?
                if (bmp != null) mangaBitmapCache.put(path, bmp)
                b.mangaProgress.isVisible = false
                return@launch
            }
            if (bmp == null) {
                b.mangaProgress.isVisible = false
                // ä???°???ç????
                applySingleMangaLayoutWithBitmap(null)
                Toasts.show(activity, R.string.image_gallery_load_fail)
                return@launch
            }
            mangaBitmapCache.put(path, bmp)
            applySingleMangaLayoutWithBitmap(bmp)
            suppressMangaViewSave = false
            if (activity.allowProgressSave) activity.saveProgress(i)
        }
    }


    fun applySingleMangaLayoutWithBitmap(bmp: android.graphics.Bitmap?) {
        b.mangaContinuousHost.isVisible = false
        b.mangaContinuousHost.resetZoom(notify = false)
        b.mangaContinuousHost.alpha = 1f
        b.mangaImageView.isVisible = true
        b.mangaImageView.alpha = 1f
        b.mangaImageView.setImageBitmap(bmp)
        if (bmp != null) {
            afterMangaBitmapReady()
        }
        b.mangaProgress.isVisible = false
        activity.updateProgressLabel()
    }

    /** ???ç???ç´????â??­???????????????????é?/ ç??N ?? ???/ ??ä??é???*/

    fun paragraphIndexForMangaImage(imageIndex: Int): Int {
        val paras = activity.book?.paragraphs.orEmpty()
        if (paras.isEmpty()) return 0
        val idx = imageIndex.coerceIn(0, (mangaPaths.size - 1).coerceAtLeast(0))
        val path = mangaPaths.getOrNull(idx)
        if (!path.isNullOrBlank()) {
            val exact = paras.indexOfFirst { it.imagePath == path }
            if (exact >= 0) return exact
            val name = File(path).name
            if (name.isNotBlank()) {
                val byName = paras.indexOfFirst { p ->
                    val ip = p.imagePath ?: return@indexOfFirst false
                    File(ip).name == name || ip.endsWith(name)
                }
                if (byName >= 0) return byName
            }
        }
        // ???????????é??
        var n = 0
        for (i in paras.indices) {
            if (paras[i].isBlockImage) {
                if (n == idx) return i
                n++
            }
        }
        // ??ä??é
        if (mangaPaths.isEmpty()) return 0
        return ((idx.toFloat() / mangaPaths.size.coerceAtLeast(1)) * paras.lastIndex)
            .toInt()
            .coerceIn(0, paras.lastIndex)
    }

    /**
     * ??ç?­???¨?§????é?????¤?çä¸?? ??é???????§?ç´ ??¤§????
     */

    fun pickMostCompleteVisibleMangaIndex(): Int {
        if (!isMangaContinuousLayout() || mangaPaths.isEmpty()) {
            return mangaIndex.coerceIn(0, (mangaPaths.size - 1).coerceAtLeast(0))
        }
        val rv = b.mangaRecycler
        val lm = rv.layoutManager as? LinearLayoutManager
            ?: return mangaIndex.coerceIn(0, mangaPaths.lastIndex)
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) {
            return mangaIndex.coerceIn(0, mangaPaths.lastIndex)
        }
        val vh = rv.height.coerceAtLeast(1)
        var best = first.coerceIn(0, mangaPaths.lastIndex)
        var bestVisible = -1
        val end = if (last == RecyclerView.NO_POSITION) first else last
        for (i in first..end) {
            if (i !in mangaPaths.indices) continue
            val child = lm.findViewByPosition(i) ?: continue
            val top = child.top.coerceAtLeast(0)
            val bottom = child.bottom.coerceAtMost(vh)
            val visible = (bottom - top).coerceAtLeast(0)
            if (visible > bestVisible) {
                bestVisible = visible
                best = i
            }
        }
        return best
    }

    /** ??ä?ä¸­??é?ä?????????ç¤?? ?????é???é?é?é?????? */

    fun showMangaLocateUi() {
        if (!activity.isBindingReady()) return
        b.mangaProgress.isVisible = true
        b.mangaContinuousHost.alpha = 0f
        b.mangaImageView.alpha = 0f
    }


    fun revealMangaContent() {
        if (!activity.isBindingReady()) return
        b.mangaProgress.isVisible = false
        b.mangaContinuousHost.alpha = 1f
        b.mangaImageView.alpha = 1f
    }


    fun setupMangaHost() {
        val iv = b.mangaImageView
        iv.minZoomFactor = 0.25f
        iv.maxZoomFactor = 5f
        iv.keepRelativeZoomOnBitmapChange = true
        iv.onSideTap = { zone ->
            // ???????????????ä¸ç??é??
            if (activity.chromeVisible) {
                activity.chromeController.hideChrome()
            } else {
                when (zone) {
                    0 -> mangaGo(-1)
                    2 -> mangaGo(+1)
                }
            }
        }
        iv.onSwipePage = { forward ->
            if (activity.chromeVisible) {
                activity.chromeController.hideChrome()
            } else {
                mangaGo(if (forward) +1 else -1)
            }
        }
        iv.onCenterTap = { activity.chromeController.toggleChrome() }
        iv.onZoomChanged = {
            if (activity.chromeVisible && iv.isScaled()) activity.chromeController.hideChrome()
            scheduleSaveMangaViewState()
        }
        iv.onTransformChanged = {
            if (activity.chromeVisible && iv.isScaled()) activity.chromeController.hideChrome()
            scheduleSaveMangaViewState()
        }
    }

    /** ???????ä??ç¨??ç?­??????ç?ç¨??é?????¨?ç?????????*/

    fun isMangaContinuousLayout(): Boolean =
        mangaMode && mangaContinuousPref && mangaPaths.isNotEmpty()

    /**
     * ???ç??????[mangaImageView]????ç?­????[mangaContinuousHost]???¨?ç??????????
     * @param preservePending true ??ä¸??ç [pendingMangaTransform]?????????ä? store ????????
     */

    fun updateMangaLayoutForOrientation(preservePending: Boolean = false) {
        if (!mangaMode || !activity.isBindingReady()) return
        if (mangaPaths.isEmpty()) return
        // ????¸?????°ä¸ç?????????????¤é????ç?store ç?pending????ç¨?§ä??1x ??ç
        if (!preservePending && !suppressMangaViewSave) {
            val (keepZoom, keepPanX, keepPanY) = currentMangaTransform()
            if (isUsefulTransform(Triple(keepZoom, keepPanX, keepPanY)) ||
                pendingMangaTransform == null
            ) {
                pendingMangaTransform = Triple(keepZoom, keepPanX, keepPanY)
            }
        }
        val continuous = isMangaContinuousLayout()
        if (continuous) {
            b.mangaImageView.isVisible = false
            b.mangaImageView.setImageBitmap(null)
            b.mangaContinuousHost.isVisible = true
            ensureMangaContinuousSetup()
            mangaContinuousAdapter?.submit(mangaPaths)
            b.mangaRecycler.post {
                if (!isMangaContinuousLayout()) return@post
                restoreMangaContinuousScrollOrIndex()
                tryApplyPendingMangaTransform()
                // ????ä¸?¸§??ç­?item é????ç¨?????é?scroll + zoom
                b.mangaRecycler.post {
                    if (!isMangaContinuousLayout() || activity.isFinishing || activity.isDestroyed) return@post
                    restoreMangaContinuousScrollOrIndex()
                    tryApplyPendingMangaTransform()
                }
            }
        } else {
            if (b.mangaContinuousHost.isVisible) {
                syncMangaIndexFromContinuous()
            }
            b.mangaContinuousHost.isVisible = false
            b.mangaContinuousHost.resetZoom(notify = false)
            b.mangaImageView.isVisible = true
            showMangaIndex(mangaIndex)
            b.mangaImageView.post {
                if (mangaMode && !isMangaContinuousLayout()) {
                    tryApplyPendingMangaTransform()
                }
            }
        }
        activity.updateProgressLabel()
    }


    fun ensureMangaContinuousSetup() {
        if (mangaContinuousSetup) return
        mangaContinuousSetup = true
        val zoom = b.mangaContinuousHost
        val rv = b.mangaRecycler
        zoom.minZoom = 0.25f
        zoom.maxZoom = 3.5f
        zoom.continuousScrollWhenZoomed = true
        zoom.zoomTarget = rv
        // é???? setup ä¸?reset??enter ??ä???pending ???¤ç????

        val adapter = MangaContinuousAdapter()
        mangaContinuousAdapter = adapter
        rv.layoutManager = LinearLayoutManager(activity, RecyclerView.VERTICAL, false)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.setHasFixedSize(false)
        // ??é´é´éç?éé?¨? divider ?§????@dimen/mobi_continuous_image_gap??é????10dp???
        mangaGapDecoration?.let { rv.removeItemDecoration(it) }
        mangaGapDecoration = null
        rv.setBackgroundColor(0xFF000000.toInt())
        rv.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (!isMangaContinuousLayout()) return
                    if (activity.chromeVisible && dy != 0) activity.chromeController.hideChrome()
                    syncMangaIndexFromContinuous()
                    activity.updateProgressLabel()
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (!isMangaContinuousLayout()) return
                    // ?ç¨?????ç?ç´ä?ç??
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        syncMangaIndexFromContinuous()
                        scheduleSaveMangaViewState()
                    }
                }
            },
        )

        zoom.onSingleTap = { _, _ -> activity.chromeController.toggleChrome() }
        // ??ç?­????ä?§ç?/?°´?????????°ç¸é???????ç????ä¸é?????????
        zoom.onSideTapImmediate = { zone, _, _ ->
            // ???????????????ä¸ç??é??
            if (activity.chromeVisible) {
                activity.chromeController.hideChrome()
            } else {
                mangaGo(if (zone == 2) +1 else -1)
            }
        }
        zoom.onHorizontalSwipe = { forward ->
            if (activity.chromeVisible) {
                activity.chromeController.hideChrome()
            } else {
                mangaGo(if (forward) +1 else -1)
            }
        }
        zoom.onPanOverscroll = overscroll@{ _, overY ->
            if (!isMangaContinuousLayout()) return@overscroll
            if (activity.chromeVisible) activity.chromeController.hideChrome()
            val z = zoom.contentZoom.coerceAtLeast(0.01f)
            val scrollDy = (-overY / z).toInt()
            if (scrollDy != 0) {
                rv.scrollBy(0, scrollDy)
                syncMangaIndexFromContinuous()
                scheduleSaveMangaViewState()
            }
        }
        zoom.onFlingScroll = fling@{ _, velocityY ->
            if (!isMangaContinuousLayout()) return@fling
            val z = zoom.contentZoom.coerceAtLeast(0.01f)
            val vy = (-velocityY / z).toInt()
            if (vy != 0) rv.fling(0, vy)
        }
        zoom.onStopScroll = { rv.stopScroll() }
        zoom.onZoomChanged = {
            if (activity.chromeVisible && zoom.isScaled()) activity.chromeController.hideChrome()
            scheduleSaveMangaViewState()
        }
        zoom.onTransformChanged = {
            if (activity.chromeVisible &&
                (zoom.isScaled() || zoom.getPanX() != 0f || zoom.getPanY() != 0f)
            ) {
                activity.chromeController.hideChrome()
            }
            scheduleSaveMangaViewState()
        }
    }


    fun scrollMangaContinuousTo(index: Int, smooth: Boolean) {
        val rv = b.mangaRecycler
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val i = index.coerceIn(0, (mangaPaths.size - 1).coerceAtLeast(0))
        if (smooth) {
            rv.smoothScrollToPosition(i)
        } else {
            lm.scrollToPositionWithOffset(i, 0)
        }
        mangaIndex = i
    }

    /**
     * ??ç?­????????é????§é?? + é??é???ç§? + ç???? scrollY??
     * @return Triple(index, itemOffset, scrollY)
     */

    fun captureMangaContinuousScroll(): Triple<Int, Int, Int> {
        if (!isMangaContinuousLayout() || !activity.isBindingReady()) {
            return Triple(mangaIndex, 0, 0)
        }
        val rv = b.mangaRecycler
        val lm = rv.layoutManager as? LinearLayoutManager
            ?: return Triple(mangaIndex, 0, 0)
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) {
            return Triple(mangaIndex, 0, rv.computeVerticalScrollOffset().coerceAtLeast(0))
        }
        val child = lm.findViewByPosition(first)
        val itemOffset = child?.top ?: 0
        val scrollY = rv.computeVerticalScrollOffset().coerceAtLeast(0)
        return Triple(first, itemOffset, scrollY)
    }

    /**
     * ???¤??ç?­??ç?ç´ä?ç????ä?? index + itemOffset???
     * ç???? scrollY ä??¨????ä??????ä??????????????¸?0??
     */

    fun restoreMangaContinuousScrollOrIndex() {
        if (!isMangaContinuousLayout()) return
        val rv = b.mangaRecycler
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val idx = if (pendingMangaScrollIndex >= 0) {
            pendingMangaScrollIndex.coerceIn(0, mangaPaths.lastIndex)
        } else {
            mangaIndex.coerceIn(0, mangaPaths.lastIndex)
        }
        val itemOff = pendingMangaScrollOffset
        val targetScrollY = pendingMangaScrollY.coerceAtLeast(0)
        mangaIndex = idx
        lm.scrollToPositionWithOffset(idx, itemOff)
        ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
            "restoreScroll idx=$idx itemOff=$itemOff targetScrollY=$targetScrollY",
        )
        // ä????ç????scrollY???ä???????????????°???????target=0 ?????
        if (targetScrollY > 0) {
            rv.post {
                if (!mangaMode || !isMangaContinuousLayout() || activity.isFinishing || activity.isDestroyed) return@post
                val cur = rv.computeVerticalScrollOffset()
                val delta = targetScrollY - cur
                if (abs(delta) > 2) rv.scrollBy(0, delta)
                syncMangaIndexFromContinuous()
                activity.updateProgressLabel()
            }
        }
    }


    fun syncMangaIndexFromContinuous() {
        if (!b.mangaContinuousHost.isVisible) return
        val lm = b.mangaRecycler.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        // ä???? ?§?????¤çé??
        val firstView = lm.findViewByPosition(first)
        val second = lm.findFirstCompletelyVisibleItemPosition()
        val pick = when {
            second != RecyclerView.NO_POSITION -> second
            firstView != null && firstView.bottom < b.mangaRecycler.height / 3 ->
                (first + 1).coerceAtMost(mangaPaths.lastIndex)
            else -> first
        }
        if (pick in mangaPaths.indices && pick != mangaIndex) {
            mangaIndex = pick
            if (activity.allowProgressSave) activity.saveProgress(mangaIndex)
            preloadMangaNeighbors(mangaIndex)
        }
    }


    fun enterMangaMode(restoreIndex: Boolean) {
        if (mangaPaths.isEmpty()) {
            mangaPaths = activity.book?.imagePaths.orEmpty().filter { File(it).isFile }
        }
        if (mangaPaths.isEmpty()) {
            mangaMode = false
            Toasts.show(activity, R.string.mobi_manga_no_images)
            return
        }
        // ??°???ç?????¨???????­?
        if (activity.isTtsReady()) tts.stop()
        mangaMode = true
        // ä¸?¨?????????é?????ç?­?? / ??????ç????
        val pref = AppSettings.mobiViewMode(activity)
        mangaContinuousPref = pref == AppSettings.MobiViewMode.CONTINUOUS
        b.mangaHost.isVisible = true
        reader.visibility = View.GONE
        // ??????ä¸???ç???????ç§?????¸????ç??­?save ç?1x ??ç prefs
        suppressMangaViewSave = true
        if (restoreIndex) {
            mangaIndex = resolveMangaRestoreIndex()
            loadPendingMangaTransformFromStore()
        } else {
            mangaIndex = mangaIndex.coerceIn(0, mangaPaths.lastIndex)
            if (pendingMangaTransform == null) {
                loadPendingMangaTransformFromStore()
            }
        }
        // ?é?ä?????+ ??ç¤?? ???????ä??????é???é??é?é?é?????
        showMangaLocateUi()
        // ä?ç store ?????ç?pending????????¸??éç 1x ??ç
        updateMangaLayoutForOrientation(preservePending = true)
        activity.allowProgressSave = true
        // ????????ç´?????ä¸?ç??????suppress ä¸­??
        activity.saveProgress(mangaIndex)
        // ?¸?? + ???¨/ä????°?ç?????¤??ç¨ç????ä¸???¨
        scheduleRestoreMangaZoom(revealWhenReady = true)
        activity.updateProgressLabel()
        b.tvChapterTitle.text = ""
        activity.settingsController.updateMobiModeButtons()
        // ä????? ???é?ç???????????ç¨ mangaProgress ??ä?
        activity.loadController.hideLoadOverlay()
    }


    fun loadPendingMangaTransformFromStore() {
        if (activity.fileKey.isEmpty()) {
            ReaderLog.w(ReaderLog.Module.MANGA_ZOOM, "loadPending skip empty activity.fileKey")
            return
        }
        val state = AppSettings.loadMangaViewState(activity, activity.fileKey)
        val zoom = state.zoom.coerceIn(0.25f, 5f)
        val panX = state.panX
        val panY = state.panY
        // ??ç?­??ç?ç´ä?ç?????§ç???°ä¸????ä??zoom ä¸?1???
        pendingMangaScrollIndex = state.index
        pendingMangaScrollOffset = state.itemOffset
        pendingMangaScrollY = state.scrollY.coerceAtLeast(0)
        // ç????/???ç§???ä¸?????pending???? 1x+???ç§????
        pendingMangaTransform = Triple(zoom, panX, panY)
        ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
            "loadPending set pending=$pendingMangaTransform idx=${state.index} " +
                "itemOff=${state.itemOffset} scrollY=${state.scrollY} cont=$mangaContinuousPref",
        )
    }


    fun scheduleRestoreMangaZoom(revealWhenReady: Boolean = false) {
        val attempts = intArrayOf(0, 16, 48, 120, 280, 500, 900, 1500)
        ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
            "scheduleRestore pending=$pendingMangaTransform " +
                "scroll=($pendingMangaScrollIndex,$pendingMangaScrollOffset,$pendingMangaScrollY) " +
                "cont=${isMangaContinuousLayout()} suppress=$suppressMangaViewSave reveal=$revealWhenReady",
        )
        for (delay in attempts) {
            b.mangaHost.postDelayed({
                if (!mangaMode || activity.isFinishing || activity.isDestroyed) return@postDelayed
                // ??ç?­???????¸§é??°?????¤????+ ç????
                if (isMangaContinuousLayout()) {
                    restoreMangaContinuousScrollOrIndex()
                }
                val before = readLiveMangaTransform()
                tryApplyPendingMangaTransform()
                val after = readLiveMangaTransform()
                val zoomOk = isPendingMangaTransformAppliedOnView()
                val scrollOk = !isMangaContinuousLayout() || isMangaScrollRestoredEnough()
                val singleReady = isMangaContinuousLayout() ||
                    (b.mangaImageView.hasBitmap() && b.mangaImageView.width > 0)
                ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
                    "restore tick delay=${delay}ms zoomOk=$zoomOk scrollOk=$scrollOk " +
                        "before=$before after=$after pending=$pendingMangaTransform " +
                        "cont=${isMangaContinuousLayout()} " +
                        "ivW=${b.mangaImageView.width} hasBmp=${b.mangaImageView.hasBitmap()} " +
                        "hostW=${b.mangaContinuousHost.width} " +
                        "rvOff=${if (isMangaContinuousLayout()) b.mangaRecycler.computeVerticalScrollOffset() else -1}",
                )
                if (zoomOk && scrollOk && singleReady) {
                    suppressMangaViewSave = false
                    if (revealWhenReady) revealMangaContent()
                } else if (delay >= attempts.last()) {
                    ReaderLog.w(ReaderLog.Module.MANGA_ZOOM, "restore FAILED after all retries")
                    suppressMangaViewSave = false
                    if (revealWhenReady) revealMangaContent()
                }
            }, delay.toLong())
        }
    }

    /** ??ç?­?????¨????????????????¤ä?ç??*/

    fun isMangaScrollRestoredEnough(): Boolean {
        if (!isMangaContinuousLayout()) return true
        if (pendingMangaScrollIndex < 0 && pendingMangaScrollY <= 0) return true
        val rv = b.mangaRecycler
        val lm = rv.layoutManager as? LinearLayoutManager ?: return false
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return false
        val targetIdx = pendingMangaScrollIndex.coerceAtLeast(0)
        // é????§é??????ç?? ??????item é??????????´ offset é?ç??ç????
        if (abs(first - targetIdx) <= 1) return true
        if (pendingMangaScrollY > 0) {
            val cur = rv.computeVerticalScrollOffset()
            if (abs(cur - pendingMangaScrollY) < 80) return true
        }
        return false
    }

    /**
     * ç¨?§ä????????¤??pending ?????????ç¨??
     * ???ç?[currentMangaTransform]??ä???? pending é ??????????
     */

    fun isPendingMangaTransformAppliedOnView(): Boolean {
        val t = pendingMangaTransform ?: return true
        val live = readLiveMangaTransform() ?: return false
        val ok = abs(live.first - t.first) < 0.05f &&
            abs(live.second - t.second) < 12f &&
            abs(live.third - t.third) < 12f
        return ok
    }

    /** ä??ä¸ç¨???ç??????????é ReadingProgressStore????????§?°?????*/

    fun resolveMangaRestoreIndex(): Int {
        if (mangaPaths.isEmpty()) return 0
        val last = mangaPaths.lastIndex
        val view = AppSettings.loadMangaViewState(activity, activity.fileKey)
        if (view.index in 0..last) {
            return view.index
        }
        val saved = ReadingProgressStore.get(activity, activity.fileKey) ?: return 0
        return when {
            saved.position !in 0..last -> 0
            saved.total == mangaPaths.size -> saved.position
            saved.total > 0 && saved.total <= mangaPaths.size * 2 -> saved.position
            // ?§??°???total ??????????°??ä?ç??ä??¨??ç??´??éç¨
            saved.position <= last && saved.total == 0 -> saved.position
            else -> 0
        }
    }


    val saveMangaViewRunnable = Runnable {
        if (!activity.isFinishing && !activity.isDestroyed && mangaMode && activity.allowProgressSave) {
            saveMangaViewStateNow()
        }
    }


    fun scheduleSaveMangaViewState() {
        if (!mangaMode || !activity.allowProgressSave || activity.fileKey.isEmpty()) return
        if (suppressMangaViewSave) {
            ReaderLog.i(ReaderLog.Module.MANGA_ZOOM, "scheduleSave skipped suppress=true")
            return
        }
        b.root.removeCallbacks(saveMangaViewRunnable)
        b.root.postDelayed(saveMangaViewRunnable, 120L)
    }


    fun saveMangaViewStateNow() {
        // ???¤??ç¨ä¸­?§ä??????ä???1x??????????ä??­??é????? prefs
        if (suppressMangaViewSave) {
            ReaderLog.i(ReaderLog.Module.MANGA_ZOOM, "saveNow skipped suppress=true pending=$pendingMangaTransform")
            return
        }
        if (activity.fileKey.isEmpty() || !mangaMode || mangaPaths.isEmpty()) return
        if (!activity.isBindingReady()) return
        val best = bestMangaTransformForSave()
        ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
            "saveNow best=$best live=${readLiveMangaTransform()} pending=$pendingMangaTransform " +
                "cont=${isMangaContinuousLayout()} idx=$mangaIndex",
        )
        writeMangaViewState(best)
    }


    fun writeMangaViewState(t: Triple<Float, Float, Float>) {
        val (zoom, panX, panY) = t
        pendingMangaTransform = Triple(zoom, panX, panY)
        val (scrollIdx, itemOff, scrollY) = if (isMangaContinuousLayout()) {
            captureMangaContinuousScroll()
        } else {
            Triple(mangaIndex.coerceIn(0, mangaPaths.lastIndex), 0, 0)
        }
        // ??ç?­??ä?????¨é?é??ä¸???ç´????????ç?mangaIndex
        val idx = if (isMangaContinuousLayout()) {
            scrollIdx.coerceIn(0, mangaPaths.lastIndex)
        } else {
            mangaIndex.coerceIn(0, mangaPaths.lastIndex)
        }
        mangaIndex = idx
        // ??­? pending ???¨??é?????¤ä¸­???? 0
        pendingMangaScrollIndex = idx
        pendingMangaScrollOffset = itemOff
        pendingMangaScrollY = scrollY
        ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
            "writeState z=$zoom pan=($panX,$panY) idx=$idx itemOff=$itemOff scrollY=$scrollY " +
                "cont=${isMangaContinuousLayout()} activity.fileKey=${activity.fileKey.take(100)} " +
                "suppress=$suppressMangaViewSave",
        )
        AppSettings.saveMangaViewState(
            activity,
            activity.fileKey,
            AppSettings.MangaViewState(
                index = idx,
                zoom = zoom.coerceIn(0.25f, 5f),
                panX = panX,
                panY = panY,
                itemOffset = itemOff,
                scrollY = scrollY,
            ),
        )
    }

    /** ?§ä????????????°?ç????? null */

    fun readLiveMangaTransform(): Triple<Float, Float, Float>? {
        if (!activity.isBindingReady()) return null
        if (isMangaContinuousLayout()) {
            val z = b.mangaContinuousHost
            if (z.width <= 0) return null
            return Triple(z.contentZoom.coerceIn(0.25f, 3.5f), z.getPanX(), z.getPanY())
        }
        val iv = b.mangaImageView
        if (iv.width <= 0 || !iv.hasBitmap()) return null
        return Triple(iv.getRelativeZoom().coerceIn(0.25f, 5f), iv.getPanX(), iv.getPanY())
    }


    fun isIdentityTransform(t: Triple<Float, Float, Float>): Boolean =
        abs(t.first - 1f) < 0.02f && abs(t.second) < 0.5f && abs(t.third) < 0.5f


    fun isUsefulTransform(t: Triple<Float, Float, Float>): Boolean =
        !isIdentityTransform(t)

    /**
     * ä??­ç¨???§ä????ä???1x ??pending ?ç??????ä?? pending??é??­????¤ä¸­????????
     */

    fun bestMangaTransformForSave(): Triple<Float, Float, Float> {
        val pending = pendingMangaTransform
        val live = readLiveMangaTransform()
        if (live != null) {
            if (isIdentityTransform(live) && pending != null && isUsefulTransform(pending)) {
                return pending
            }
            return live
        }
        return pending ?: Triple(1f, 0f, 0f)
    }


    fun currentMangaTransform(): Triple<Float, Float, Float> =
        bestMangaTransformForSave()


    fun restoreMangaZoomFromStore() {
        if (!mangaMode || activity.fileKey.isEmpty()) return
        loadPendingMangaTransformFromStore()
        tryApplyPendingMangaTransform()
        scheduleRestoreMangaZoom()
    }


    fun applyMangaZoom(zoom: Float, panX: Float, panY: Float) {
        pendingMangaTransform = Triple(zoom, panX, panY)
        tryApplyPendingMangaTransform()
    }


    fun tryApplyPendingMangaTransform() {
        val t = pendingMangaTransform
        if (t == null) {
            ReaderLog.d(ReaderLog.Module.MANGA_ZOOM, "tryApply no pending")
            return
        }
        val (zoom, panX, panY) = t
        if (isMangaContinuousLayout()) {
            val host = b.mangaContinuousHost
            if (host.width <= 0 || host.height <= 0) {
                ReaderLog.d(ReaderLog.Module.MANGA_ZOOM, "tryApply cont host not laid out w=${host.width} h=${host.height}")
                return
            }
            host.setTransform(zoom.coerceIn(0.25f, 3.5f), panX, panY, notify = false)
            ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
                "tryApply cont set z=$zoom pan=($panX,$panY) â?live=${readLiveMangaTransform()}",
            )
        } else {
            val iv = b.mangaImageView
            if (iv.width <= 0 || iv.height <= 0 || !iv.hasBitmap()) {
                ReaderLog.d(ReaderLog.Module.MANGA_ZOOM,
                    "tryApply single not ready w=${iv.width} h=${iv.height} bmp=${iv.hasBitmap()}",
                )
                return
            }
            iv.setTransform(zoom.coerceIn(0.25f, 5f), panX, panY, notify = false)
            ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
                "tryApply single set z=$zoom pan=($panX,$panY) rel=${iv.getRelativeZoom()} " +
                    "live=${readLiveMangaTransform()}",
            )
        }
    }


    fun exitMangaMode() {
        // ??ç?­â?­????????é???§??????´????
        if (isMangaContinuousLayout()) {
            mangaIndex = pickMostCompleteVisibleMangaIndex()
        }
        val imgIdx = mangaIndex.coerceIn(0, (mangaPaths.size - 1).coerceAtLeast(0))
        val targetPara = paragraphIndexForMangaImage(imgIdx)
        if (activity.allowProgressSave && mangaPaths.isNotEmpty()) {
            // ???ç??§??ä????ç´?????°???­?????????°??????????
            flushMangaViewStateBeforeLeave()
            AppSettings.saveProgress(activity, activity.fileKey, targetPara)
            BookshelfStore.updateProgress(activity, activity.fileKey, targetPara)
            val totalParas = activity.book?.paragraphs?.size ?: 0
            ReadingProgressStore.saveTxt(
                activity,
                activity.fileKey,
                targetPara,
                totalParas,
                fileExt = activity.loadController.progressFileExt(),
            )
        }
        mangaMode = false
        mangaLoadJob?.cancel()
        pendingMangaTransform = null
        pendingMangaScrollIndex = -1
        pendingMangaScrollOffset = 0
        pendingMangaScrollY = 0
        revealMangaContent()
        b.mangaHost.isVisible = false
        b.mangaContinuousHost.isVisible = false
        b.mangaContinuousHost.resetZoom(notify = false)
        b.mangaImageView.isVisible = true
        b.mangaImageView.alpha = 1f
        b.mangaImageView.setImageBitmap(null)
        reader.visibility = View.VISIBLE
        // ???°??????¨?­??ä¸­ç?????ä?ç??
        if (activity.isReaderReady() && (activity.book?.paragraphs?.isNotEmpty() == true)) {
            reader.scrollToParagraph(targetPara)
        }
        activity.updateProgressLabel()
        activity.updateChapterTitleBar(targetPara)
        activity.settingsController.updateMobiModeButtons()
    }


    fun mangaGo(delta: Int) {
        if (!mangaMode || mangaPaths.isEmpty()) return
        val next = mangaIndex + delta
        if (next !in mangaPaths.indices) {
            Toasts.show(
                activity,
                if (delta > 0) R.string.mobi_manga_last else R.string.mobi_manga_first,
            )
            return
        }
        mangaIndex = next
        if (isMangaContinuousLayout()) {
            b.mangaRecycler.stopScroll()
            scrollMangaContinuousTo(next, smooth = false)
        } else {
            showMangaIndex(next)
        }
        if (activity.allowProgressSave) activity.saveProgress(next)
        activity.updateProgressLabel()
    }


    fun showMangaIndex(i: Int) {
        if (mangaPaths.isEmpty()) return
        mangaIndex = i.coerceIn(0, mangaPaths.lastIndex)
        // ?¨?????ç?­??ç????¨?´?´???ç¤?
        if (isMangaContinuousLayout()) {
            scrollMangaContinuousTo(mangaIndex, smooth = false)
            b.mangaProgress.isVisible = false
            preloadMangaNeighbors(mangaIndex)
            return
        }
        val path = mangaPaths[mangaIndex]
        val cached = mangaBitmapCache.get(path)
        if (cached != null && !cached.isRecycled) {
            b.mangaImageView.setImageBitmap(cached)
            afterMangaBitmapReady()
            b.mangaProgress.isVisible = false
            preloadMangaNeighbors(mangaIndex)
            return
        }
        b.mangaProgress.isVisible = true
        mangaLoadJob?.cancel()
        mangaLoadJob = activity.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                decodeMangaSampled(path, mangaMaxSide())
            }
            if (activity.isFinishing || activity.isDestroyed) {
                bmp?.recycle()
                return@launch
            }
            if (mangaIndex != i || isMangaContinuousLayout()) {
                if (bmp != null) mangaBitmapCache.put(path, bmp)
                return@launch
            }
            if (bmp == null) {
                b.mangaProgress.isVisible = false
                b.mangaImageView.setImageBitmap(null)
                Toasts.show(activity, R.string.image_gallery_load_fail)
                return@launch
            }
            mangaBitmapCache.put(path, bmp)
            b.mangaImageView.setImageBitmap(bmp)
            afterMangaBitmapReady()
            b.mangaProgress.isVisible = false
            preloadMangaNeighbors(mangaIndex)
        }
    }

    /**
     * ä????°?ç?????ä????ç¨ pending??????????¤?????
     * ??keepRelativeZoom ???ä?çä???ç?????????­?pending ä¸????????
     */

    fun afterMangaBitmapReady() {
        val iv = b.mangaImageView
        val t = pendingMangaTransform
        val pendingUseful = t != null && isUsefulTransform(t)
        val viewScaled = iv.isScaled() ||
            abs(iv.getRelativeZoom() - 1f) > 0.02f ||
            abs(iv.getPanX()) > 1f ||
            abs(iv.getPanY()) > 1f
        ReaderLog.i(ReaderLog.Module.MANGA_ZOOM,
            "afterBitmapReady pendingUseful=$pendingUseful viewScaled=$viewScaled " +
                "suppress=$suppressMangaViewSave rel=${iv.getRelativeZoom()} " +
                "pan=(${iv.getPanX()},${iv.getPanY()}) pending=$t",
        )
        if (pendingUseful && (!viewScaled || suppressMangaViewSave)) {
            tryApplyPendingMangaTransform()
            if (isPendingMangaTransformAppliedOnView()) {
                suppressMangaViewSave = false
                ReaderLog.i(ReaderLog.Module.MANGA_ZOOM, "afterBitmapReady applied OK suppress=false")
            }
            return
        }
        if (viewScaled && !suppressMangaViewSave) {
            pendingMangaTransform = Triple(
                iv.getRelativeZoom().coerceIn(0.25f, 5f),
                iv.getPanX(),
                iv.getPanY(),
            )
        }
    }


    fun preloadMangaNeighbors(i: Int) {
        val targets = listOf(i - 1, i + 1, i + 2, i + 3, i - 2)
            .filter { it in mangaPaths.indices }
            .distinct()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            for (ti in targets) {
                val p = mangaPaths[ti]
                if (mangaBitmapCache.get(p) != null) continue
                val bmp = decodeMangaSampled(p, mangaMaxSide()) ?: continue
                mangaBitmapCache.put(p, bmp)
            }
        }
    }

    /**
     * ??ç?­?????¨????é??ä¸??+ ??é¨?é????[R.dimen.mobi_continuous_image_gap]??é????10dp????
     */

    inner class MangaContinuousAdapter :
        RecyclerView.Adapter<MangaContinuousAdapter.VH>() {

        private var paths: List<String> = emptyList()

        fun submit(newPaths: List<String>) {
            paths = newPaths
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = paths.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val gapPx = parent.resources.getDimensionPixelSize(R.dimen.mobi_continuous_image_gap)
            val root = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setBackgroundColor(0xFF000000.toInt())
            }
            val iv = android.widget.ImageView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF000000.toInt())
            }
            val gap = View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    gapPx,
                )
                setBackgroundColor(0xFF000000.toInt())
            }
            root.addView(iv)
            root.addView(gap)
            return VH(root, iv, gap)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val path = paths.getOrNull(position) ?: return
            holder.gap.visibility =
                if (position < paths.lastIndex) View.VISIBLE else View.GONE
            holder.bind(path, position)
        }

        override fun onViewRecycled(holder: VH) {
            holder.unbind()
            super.onViewRecycled(holder)
        }

        inner class VH(
            root: View,
            val imageView: android.widget.ImageView,
            val gap: View,
        ) : RecyclerView.ViewHolder(root) {
            private var boundPath: String? = null
            private var loadToken = 0

            fun bind(path: String, position: Int) {
                boundPath = path
                val token = ++loadToken
                val cached = mangaBitmapCache.get(path)
                if (cached != null && !cached.isRecycled) {
                    applyBitmap(cached)
                    return
                }
                imageView.setImageBitmap(null)
                // ?? ä??????????¨?????é??ç????height ?¸??°?¨???
                val w = mangaListContentWidth()
                imageView.layoutParams = imageView.layoutParams.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = (w * 1.4f).toInt().coerceAtLeast(1)
                }
                activity.lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        decodeMangaSampled(path, mangaMaxSide())
                    }
                    if (token != loadToken || boundPath != path) {
                        // ???ç??ä?????ç??­?
                        if (bmp != null) mangaBitmapCache.put(path, bmp)
                        return@launch
                    }
                    if (bmp == null) {
                        imageView.setImageBitmap(null)
                        return@launch
                    }
                    mangaBitmapCache.put(path, bmp)
                    applyBitmap(bmp)
                    // é???é????
                    if (position == mangaIndex ||
                        position == mangaIndex + 1 ||
                        position == mangaIndex - 1
                    ) {
                        preloadMangaNeighbors(position)
                    }
                }
            }

            private fun applyBitmap(bmp: Bitmap) {
                // ??é??ç¨????????¨??????ç?é??????ç???ç?????ç??­ç?height ?¨?¨???ä??????
                val parentW = mangaListContentWidth()
                val h = if (bmp.width > 0 && parentW > 0) {
                    (parentW.toLong() * bmp.height / bmp.width).toInt().coerceAtLeast(1)
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
                val lp = imageView.layoutParams
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                if (lp.height != h) {
                    lp.height = h
                }
                imageView.layoutParams = lp
                // ???é???ä¸ parentW ???é???FIT_XY é??????é???FIT_CENTER ?¨é???height ä¸ç???ä¸??
                imageView.scaleType = android.widget.ImageView.ScaleType.FIT_XY
                imageView.setImageBitmap(bmp)
            }

            fun unbind() {
                loadToken++
                boundPath = null
                imageView.setImageBitmap(null)
            }
        }
    }

    /** ??ç?­?????¨???????????????????é??éç??item é???????????ä?ç???ä¸???????*/

    fun mangaListContentWidth(): Int {
        if (!activity.isBindingReady()) {
            return activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        }
        return b.mangaRecycler.width.takeIf { it > 0 }
            ?: b.mangaContinuousHost.width.takeIf { it > 0 }
            ?: b.mangaHost.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    }


    fun mangaMaxSide(): Int {
        val dm = activity.resources.displayMetrics
        return (maxOf(dm.widthPixels, dm.heightPixels) * 2).coerceAtLeast(1080)
    }


    fun decodeMangaSampled(path: String, maxSide: Int): Bitmap? {
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (longSide / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, opts)
        }.getOrNull()
    }

}
