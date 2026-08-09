package com.whj.reader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.whj.reader.ui.AppTheme
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.data.AppSettings
import com.whj.reader.data.BookFileType
import com.whj.reader.data.BookshelfStore
import com.whj.reader.data.PdfLinkIndex
import com.whj.reader.data.PdfOcrCacheStore
import com.whj.reader.data.PdfTextExtractor
import com.whj.reader.R
import com.whj.reader.databinding.ActivityPdfReadingBinding
import com.whj.reader.databinding.DialogPdfOcrBinding
import com.whj.reader.databinding.PanelPdfSettingsBinding
import com.whj.reader.databinding.PanelPdfTtsExportBinding
import com.whj.reader.databinding.PanelReadMenuBinding
import com.whj.reader.model.OrientationMode
import com.whj.reader.model.Paragraph
import com.whj.reader.model.PdfPageMode
import com.whj.reader.ocr.TfliteOcrEngine
import com.whj.reader.tts.Mp3Encoder
import com.whj.reader.tts.TtsExportHelper
import com.whj.reader.tts.TtsManager
import com.whj.reader.ui.PdfPageAdapter
import com.whj.reader.ui.PdfPageSurface
import com.whj.reader.ui.TextSelectionHandles
import com.whj.reader.ui.TtsExportProgressDialog
import com.whj.reader.util.ReaderLog
import com.whj.reader.util.KeepScreenController
import com.whj.reader.util.OpenFailGuide
import com.whj.reader.util.OrientationHelper
import com.whj.reader.util.StorageAccess
import com.whj.reader.util.Toasts

import com.whj.reader.pdf.mode.PdfModeController
import com.whj.reader.pdf.textload.PdfTextLoadController
import com.whj.reader.pdf.bind.PdfPageBindController
import com.whj.reader.pdf.session.PdfDocumentController
import com.whj.reader.pdf.chrome.PdfChromeController
import com.whj.reader.pdf.ocr.PdfOcrUiController
import com.whj.reader.pdf.coord.PdfCropHelper
import com.whj.reader.pdf.coord.PdfViewMapper
import com.whj.reader.pdf.layout.PdfPageHeightTable
import com.whj.reader.pdf.link.PdfLinkNavigator
import com.whj.reader.pdf.render.PdfBitmapRenderer
import com.whj.reader.pdf.render.PdfLayoutMetrics
import com.whj.reader.pdf.render.PdfRenderCache
import com.whj.reader.pdf.render.PdfRenderConfig
import com.whj.reader.pdf.render.PdfRenderPipeline
import com.whj.reader.pdf.render.PdfRenderScheduler
import com.whj.reader.pdf.render.PdfRenderTask
import com.whj.reader.pdf.render.PdfUiAttach
import com.whj.reader.pdf.render.PdfUiAttachQueue
import com.whj.reader.pdf.text.PdfTextCache
import com.whj.reader.pdf.text.PdfTextSelectionController
import com.whj.reader.pdf.text.PdfTextSelectionState
import com.whj.reader.pdf.text.PdfSelectionInteractor
import com.whj.reader.pdf.nav.PdfNavBookmarkController
import com.whj.reader.pdf.tts.PdfTtsController
import com.whj.reader.pdf.chrome.PdfStatusBarHelper
import com.whj.reader.pdf.ocr.PdfPageOcrRunner
import com.whj.reader.util.TtsVoicePicker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PDF 阅读（与 TXT 隔离：独立进度 / 上次书 / 页面模式 / 视角）。
 * - 连续滚动（默认）：RecyclerView + 页间间隔（@dimen/pdf_page_gap，默认 10dp）
 * - 单页模式：左右点按翻页
 * - 中部：与 TXT 相同 8 图标菜单
 */
class PdfReadingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
    }

    /** 单页超长图换页后竖向 pan 落点 */
    internal enum class TallPanSnap { PRESERVE, TOP, BOTTOM }

    private data class SinglePageRenderResult(
        val index: Int,
        val bitmap: Bitmap,
        val fitByWidth: Boolean,
    )

    // ─── Phase1/2 抽出的渲染 / 页高 / 管线 ─────────────────
    internal val pdfRenderCache = PdfRenderCache()
    internal val pageHeightTable = PdfPageHeightTable()
    internal val uiAttachQueue = PdfUiAttachQueue(this)
    /** 避免 scheduler ↔ pipeline 字段初始化循环 */
    private var offerRenderTaskFn: (PdfRenderTask) -> Unit = {}
    private var isPageInRenderWindowFn: (Int) -> Boolean = { true }
    internal val pdfRenderPipeline = PdfRenderPipeline(
        cache = pdfRenderCache,
        activity = this,
    )
    internal val pdfRenderScheduler = PdfRenderScheduler(this).also { sched ->
        offerRenderTaskFn = { task -> sched.offer(task) }
        isPageInRenderWindowFn = { page -> sched.isPageInRenderWindow(page) }
    }

    internal fun tileCacheKey(pageIndex: Int, tileIndex: Int, targetWidth: Int = 0): Long =
        pdfRenderCache.tileCacheKey(pageIndex, tileIndex, targetWidth)

    internal fun pinTileBitmap(bmp: Bitmap?) = pdfRenderCache.pinTileBitmap(bmp)

    internal fun unpinTileBitmap(bmp: Bitmap?) = pdfRenderCache.unpinTileBitmap(bmp)

    internal fun deliverTile(
        surface: PdfPageSurface,
        tileIndex: Int,
        bmp: Bitmap,
        bindGen: Long,
    ) = pdfRenderCache.deliverTile(surface, tileIndex, bmp, bindGen)

    internal lateinit var binding: ActivityPdfReadingBinding
    internal lateinit var readMenu: PanelReadMenuBinding
    internal lateinit var pdfSettings: PanelPdfSettingsBinding
    internal lateinit var exportPanel: PanelPdfTtsExportBinding
    internal var ttsExport: TtsExportHelper? = null
    internal var exportProgressDlg: TtsExportProgressDialog? = null

    internal var fileKey: String = ""
    internal var displayTitle: String = ""
    internal var pageCount: Int = 0
    /** 当前页（0-based）；后台渲染线程可读作锚点，故 volatile */
    @Volatile
    internal var pageIndex: Int = 0
    /** 已处理的侧边点按 DOWN 时间（Activity 层再挡一层双发） */
    internal var handledSideTapDownTime = -1L
    @Volatile internal var pageTurnBusy = false
    /** 单页位图后台渲染中（避免主线程卡顿与翻页请求积压） */
    @Volatile internal var singlePageRendering = false
    internal var singlePageRenderGen = 0L
    internal var pendingSinglePage: Pair<Int, TallPanSnap>? = null
    internal var chromeVisible = false
    /** 合成语音面板 */
    internal var exportPanelOpen = false
    /** 书内链接：page → links；后台加载 */
    /** 目录大纲（打开 PDF 后预加载到内存） */
    /** 书内链接前进/后退 */
    internal var allowProgressSave = false
    internal var immersive = false
    /** 打开菜单的时间，避免布局变化触发 onScrolled 立刻关菜单 */
    internal var chromeShownAtMs = 0L
    internal var pageMode: PdfPageMode = PdfPageMode.CONTINUOUS
    internal var night = false
    /** 四边切边比例 L,T,R,B 各 0~0.30 */
    internal var cropL = 0f
    internal var cropT = 0f
    internal var cropR = 0f
    internal var cropB = 0f

    internal var pfd: ParcelFileDescriptor? = null
    internal var renderer: PdfRenderer? = null
    internal var currentPage: PdfRenderer.Page? = null
    internal var singleBitmap: Bitmap? = null
    /** 单页超长图：分块渲染表面（与连续模式共用 tile 管线） */
    internal var singlePageSurface: PdfPageSurface? = null
    internal var singlePageUsesTiles = false

    internal var pageAdapter: PdfPageAdapter? = null
    internal lateinit var tts: TtsManager
    internal lateinit var keepScreen: KeepScreenController
    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val page = result.data?.getIntExtra(BookSearchActivity.RESULT_PAGE_INDEX, -1) ?: -1
        if (page < 0 || pageCount <= 0) return@registerForActivityResult
        hideChrome()
        val p = page.coerceIn(0, pageCount - 1)
        restorePosition(p)
        if (allowProgressSave) saveProgress(p)
        updateProgressLabel()
    }

    /** 打开失败：重新选文件 */
    internal val reselectDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        applyReselectedUri(uri)
    }

    /** 打开失败：授予全盘权限后重试 */
    internal val openFailPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val ok = StorageAccess.hasAllFilesAccess() ||
            (uriStr != null && StorageAccess.canRead(this, Uri.parse(uriStr)))
        if (ok) {
            Toasts.show(this, R.string.open_failed_permission_granted_retry)
            loadPdf()
        } else {
            showOpenFailGuide(OpenFailGuide.Reason.PERMISSION, detail = null)
        }
    }

    internal val ttsNotifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingTtsAfterNotif?.invoke()
        pendingTtsAfterNotif = null
    }
    internal var pendingTtsAfterNotif: (() -> Unit)? = null
    // textCache.paragraphs → textCache.paragraphs
    internal var ttsBarOpen = false
    internal var ttsExtracting = false
    internal var extractJob: kotlinx.coroutines.Job? = null
    internal var pendingAfterExtract: (() -> Unit)? = null
    /** PDF 页面 OCR 任务（可取消） */
    internal var ocrJob: kotlinx.coroutines.Job? = null
    internal var ocrEngine: TfliteOcrEngine? = null
    /** 长图条带 GPU det 哑火时按条回退用的 CPU 引擎 */
    internal var ocrCpuFallback: TfliteOcrEngine? = null
    internal val pdfOcrRunner = PdfPageOcrRunner(this)
    /** adb 写入 debug_pdf_ocr 后轮询触发（应用在前台时无需切后台） */
    internal val sleepTimer = com.whj.reader.tts.TtsSleepTimer(
        onTick = { left ->
            if (!isFinishing && !isDestroyed) {
                binding.tvTtsSleepCountdown.text =
                    com.whj.reader.tts.TtsSleepTimer.formatCountdown(left)
            }
        },
        onFinished = { onSleepTimerFinished() },
    )

    /** 抽字 / 段落缓存（懒加载） */
    internal val textCache = PdfTextCache()
    /** 文字选区控制器（状态 + 边缘滚选 + 选中文本） */
    internal val textSelCtrl = PdfTextSelectionController()
    private val textSel get() = textSelCtrl.state
    private var textActionMode: ActionMode? = null
    private lateinit var selectionInteractor: PdfSelectionInteractor
    internal lateinit var navBookmarkController: PdfNavBookmarkController
    private lateinit var ttsController: PdfTtsController
    private lateinit var ocrUiController: PdfOcrUiController
    private lateinit var chromeController: PdfChromeController
    internal lateinit var modeController: PdfModeController
    private lateinit var documentController: PdfDocumentController
    private lateinit var pageBindController: PdfPageBindController
    private lateinit var textLoadController: PdfTextLoadController
    /**
     * TTS 句高亮（可跨页闭区间）：
     * (hlStartPage, hlStartChar) … (hlEndPage, hlEndChar)。
     */
    internal var hlStartPage = -1
    internal var hlStartChar = -1
    internal var hlEndPage = -1
    internal var hlEndChar = -1
    /** PdfRenderer 页尺寸缓存，用于与 PDFBox 坐标对齐 */
    internal val rendererPageSize = HashMap<Int, Pair<Float, Float>>()

    /** PdfRenderer 同时只能 open 一页 */
    internal val renderLock = Any()

    @Volatile
    internal var rvScrollState: Int = RecyclerView.SCROLL_STATE_IDLE
    /** 上次刷可见区时间（仅拖动时用；fling 中主线程零渲染调度） */
    internal var lastTileRefreshMs: Long = 0L
    internal val tileRefreshMinIntervalMs = 64L
    internal var pendingContinuousTileRefresh: Runnable? = null
    internal var lastPdfZoomLogMs: Long = 0L
    internal var lastPdfOpenLogMs: Long = 0L
    /** 上次进度文字更新 */
    internal var lastProgressUiMs: Long = 0L
    internal val progressUiMinIntervalMs = 120L
    // full/tile/size pending 见 PdfRenderPipeline

    private var batteryReceiverRegistered = false
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            updateBattery(intent)
        }
    }

    internal var lastRenderW = 0
    internal var lastRenderH = 0

    /** 跳转滑条预览防抖 */
    private val jumpPreviewHandler = Handler(Looper.getMainLooper())
    private var jumpPreviewRunnable: Runnable? = null
    private val jumpPreviewDelayMs = 120L

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        cropL = data.getFloatExtra(PdfCropActivity.EXTRA_CROP_L, 0f)
        cropT = data.getFloatExtra(PdfCropActivity.EXTRA_CROP_T, 0f)
        cropR = data.getFloatExtra(PdfCropActivity.EXTRA_CROP_R, 0f)
        cropB = data.getFloatExtra(PdfCropActivity.EXTRA_CROP_B, 0f)
        if (data.hasExtra(PdfCropActivity.EXTRA_MIRROR) && fileKey.isNotEmpty()) {
            AppSettings.setPdfCropMirrorOddEven(
                this,
                fileKey,
                data.getBooleanExtra(PdfCropActivity.EXTRA_MIRROR, false),
            )
        }
        // 还原：同时关掉排版面板与底部菜单
        if (data.getBooleanExtra(PdfCropActivity.EXTRA_DISMISS_UI, false)) {
            binding.settingsPanelContainer.isVisible = false
            hideChrome()
        }
        // 已由裁剪页写入 prefs；此处同步内存并刷新
        updateCropSummary()
        clearTextSelection()
        // 切边变化后重建 TTS 文本（忽略被裁掉的字）
        applyCropToExtractedText()
        invalidatePageBitmaps()
        // 切边返回后重新绑定缩放目标，避免缩放失效
        rebindZoomTarget()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPdfReadingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 菜单：inflate 到 host（attach 后预测量，避免首次 GONE→VISIBLE 空白）
        readMenu = PanelReadMenuBinding.inflate(layoutInflater, binding.readMenuHost, true)
        exportPanel = PanelPdfTtsExportBinding.inflate(layoutInflater, binding.ttsExportHost, true)
        pdfSettings = binding.pdfSettingsPanel
        selectionInteractor = PdfSelectionInteractor(this)
        navBookmarkController = PdfNavBookmarkController(this)
        ttsController = PdfTtsController(this)
        ocrUiController = PdfOcrUiController(this)
        chromeController = PdfChromeController(this)
        modeController = PdfModeController(this)
        documentController = PdfDocumentController(this)
        pageBindController = PdfPageBindController(this)
        textLoadController = PdfTextLoadController(this)
        // TTS 必须在 setupPdfExportPanel / setupTtsBar 之前初始化（会读 currentVoiceName）
        tts = TtsManager(this)
        ttsController.bindTtsCallbacks()
        tts.setSpeechRate(AppSettings.ttsRate(this))
        tts.setPitch(AppSettings.ttsPitch(this))
        // 引擎/发音人在 TtsManager 构造与 onInit 中从 prefs 恢复
        tts.init()
        premeasureReadMenu()
        setupMenuPager()
        setupPdfExportPanel()
        setupBackPress()

        pageMode = AppSettings.pdfPageMode(this)
        night = AppSettings.pdfNight(this)
        // 切边在 loadPdf 时按 fileKey 加载（各文件独立）
        // 大屏 force 解除可能残留的竖屏 letterbox，铺满窗口
        applyOrientationMode(
            AppSettings.pdfOrientationMode(this),
            force = OrientationHelper.isLargeScreen(this),
        )
        applyNightUi()
        keepScreen = KeepScreenController(this) {
            ::tts.isInitialized && tts.currentState().state == TtsManager.State.SPEAKING
        }
        keepScreen.apply()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnHistBack.setOnClickListener { navigateHistoryBack() }
        binding.btnHistForward.setOnClickListener { navigateHistoryForward() }
        binding.btnBookmark.setOnClickListener { togglePdfBookmark() }
        binding.btnMore.setOnClickListener { v -> showPdfMoreMenu(v) }
        binding.topBar.setOnClickListener { }
        setupMenu()
        setupPdfSettings()
        setupPinchZoom()
        setupTtsBar()
        setupPageTouch()
        setupRecycler()
        setupFastScroll()
        setupBottomChromeInsets()
        hideChrome()
        updateClock()
        applyPageModeUi()
        updateHistNavButtons()
        startRenderWorker()
        binding.root.post {
            if (!isFinishing && !isDestroyed) {
                applyPortraitColumnLayout()
                applyChromeVisibility()
                if (isLandscape()) {
                    binding.pdfContainer.resetZoom(notify = true)
                    updatePdfZoomChrome()
                }
            }
        }

        loadPdf()
    }

    /** 底部菜单 / 排版面板避开系统导航条 */
    internal fun setupBottomChromeInsets() = chromeController.setupBottomChromeInsets()

    /** 打开 PDF 排版面板：抬升到最前，保证完整可见 */
    private fun openPdfSettingsPanel() {
        updateModeButtons()
        updateCropSummary()
        binding.settingsPanelContainer.bringToFront()
        binding.settingsPanelContainer.isVisible = true
        pdfSettings.root.bringToFront()
    }

    override fun onResume() {
        super.onResume()
        startClockAndBattery()
        if (::keepScreen.isInitialized) keepScreen.onResume()
        applyOrientationMode(AppSettings.pdfOrientationMode(this), allowSensor = true)
        maybeRunPdfOrientDebugFromFile()
        ocrUiController.schedulePdfOcrDebugPoll()
        ocrUiController.startWatchdog(800L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            maybeRunPdfOcrDebugFromFile()
            ocrUiController.schedulePdfOcrDebugPoll()
        }
    }

    /**
     * adb 调试竖/横切换：
     *   adb shell run-as com.whj.reader sh -c "printf land > files/debug_pdf_orient"
     *   adb shell run-as com.whj.reader sh -c "printf port > files/debug_pdf_orient"
     * 然后 HOME 再回前台；日志：adb logcat -s PdfOrient:I
     *
     * 连续模式缩放黑屏：
     *   adb logcat -s PdfZoom:I PdfZoom:W
     *
     * adb 调试 OCR（当前打开的 PDF，强制分块重识、不跳过缓存）：
     *   # 当前屏可见页（阅读中停在目标页后执行，约 1s 内自动触发）
     *   adb shell run-as com.whj.reader sh -c "echo -n current > files/debug_pdf_ocr"
     *   # 指定页（页码从 1 起）
     *   adb shell run-as com.whj.reader sh -c "echo -n page=5 > files/debug_pdf_ocr"
     *   adb logcat -s PdfOcrDbg:I PdfOcrCache:W
     *   adb shell run-as com.whj.reader cat files/pdf_ocr_debug/page_N.txt
     */
    private fun schedulePdfOcrDebugPoll() = ocrUiController.schedulePdfOcrDebugPoll()

  private fun maybeRunPdfOcrDebugFromFile() = ocrUiController.maybeRunPdfOcrDebugFromFile()

    /** @return 0-based 页码；无法解析返回 -1 */
    private fun maybeRunPdfOrientDebugFromFile() {
        val flag = java.io.File(filesDir, "debug_pdf_orient")
        if (!flag.exists()) return
        val raw = runCatching { flag.readText().trim().lowercase() }.getOrDefault("")
        runCatching { flag.delete() }
        val next = when {
            raw.startsWith("land") -> OrientationMode.LANDSCAPE
            raw.startsWith("port") -> OrientationMode.PORTRAIT
            else -> {
                ReaderLog.w(ReaderLog.Module.PDF_ORIENT, "debug file ignore raw='$raw'")
                return
            }
        }
        ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
            "debug file → $next (was ${AppSettings.pdfOrientationMode(this)})",
        )
        AppSettings.setPdfOrientationMode(this, next)
        applyOrientationMode(next, force = true)
    }

    override fun onPause() {
        super.onPause()
        if (::binding.isInitialized) {
            // ocr debug watchdog owned by ocrUiController
        }
        stopClockAndBattery()
        if (::keepScreen.isInitialized) keepScreen.onPause()
        // 后台关闭方向传感器（自动模式锁到当前方向）
        applyOrientationMode(AppSettings.pdfOrientationMode(this), allowSensor = false)
        // 锁屏/切后台不暂停 TTS，由前台服务继续播放
        if (allowProgressSave) savePdfViewAndProgress()
    }

    override fun onDestroy() {
        if (allowProgressSave) savePdfViewAndProgress()
        stopClockAndBattery()
        if (::keepScreen.isInitialized) keepScreen.onDestroy()
        cancelJumpPreview()
        extractJob?.cancel()
        extractJob = null
        ocrJob?.cancel()
        ocrJob = null
        pendingAfterExtract = null
        sleepTimer.cancel()
        ttsController.dismissExportProgressDlg()
        ttsExport?.shutdown()
        ttsExport = null
        if (::tts.isInitialized) {
            tts.onStateChanged = null
            tts.onSentenceHighlight = null
            tts.onError = null
            tts.onNeedMoreContent = null
            tts.shutdown()
        }
        runCatching { ocrEngine?.close() }
        ocrEngine = null
        runCatching { ocrCpuFallback?.close() }
        ocrCpuFallback = null
        closePdf()
        pdfRenderCache.evictAll()
        stopRenderWorker()
        super.onDestroy()
    }


    /** 子模块用：Activity 仍有效（未 finishing/destroyed） */
    internal fun isAlive(): Boolean = !isFinishing && !isDestroyed

    /** 子模块用：view binding 是否已 inflate */
    internal fun isBindingReady(): Boolean = ::binding.isInitialized

    /** 子模块用：TTS 是否已初始化 */
    internal fun isTtsReady(): Boolean = ::tts.isInitialized

    internal fun offerTask(task: PdfRenderTask) = offerRenderTask(task)

    internal fun onRenderTaskFinished(task: PdfRenderTask) {
        pdfRenderPipeline.onTaskFinished(task)
    }

    internal fun isOcrJobActive(): Boolean = ocrJob?.isActive == true

    // ─── 渲染队列（PdfRenderScheduler + 宿主执行 Full/Tile/PageSize） ─

    private fun startRenderWorker() {
        pdfRenderScheduler.start()
    }

    private fun stopRenderWorker() {
        pdfRenderScheduler.stop()
        pdfRenderPipeline.clearPending()
        uiAttachQueue.clear()
    }

    internal fun executeRenderTask(task: PdfRenderTask) {
        when (task) {
            is PdfRenderTask.Full -> pdfRenderPipeline.runFullPageTask(task)
            is PdfRenderTask.Tile -> pdfRenderPipeline.runTileTask(task)
            is PdfRenderTask.PageSize -> {
                try {
                    ensurePageSize(task.page)
                    val p = task.page
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            onPageSizeResolved(p)
                        }
                    }
                } finally {
                    pdfRenderPipeline.pendingPageSizes().remove(task.page)
                }
            }
        }
    }

    internal fun isPageInRenderWindow(page: Int): Boolean =
        pdfRenderScheduler.isPageInRenderWindow(page)

    internal fun offerRenderTask(task: PdfRenderTask) {
        pdfRenderScheduler.offer(task)
    }

    /** 仅原子更新可见窗；取消在 worker poll 时做，避免主线程每帧抢锁 */
    private fun updateVisibleRangeFromRv() {
        val lm = binding.rvPdfPages.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        pdfRenderScheduler.updateVisibleRange(first, last)?.let { pageIndex = it }
    }

    /** 滚动中（拖动或惯性）用预览分辨率，便于边滑边出图 */
    internal fun preferPreviewQuality(): Boolean =
        rvScrollState != RecyclerView.SCROLL_STATE_IDLE

    internal fun enqueueUiAttach(attach: PdfUiAttach) {
        uiAttachQueue.enqueue(attach)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev != null) {
            val a = ev.actionMasked
            if (a == android.view.MotionEvent.ACTION_DOWN ||
                a == android.view.MotionEvent.ACTION_UP
            ) {
                if (::keepScreen.isInitialized) keepScreen.onUserActivity()
            }
            // 松手/取消时强制结束边缘滚选，避免「页面自己滚停不下来」
            if (a == android.view.MotionEvent.ACTION_UP ||
                a == android.view.MotionEvent.ACTION_CANCEL
            ) {
                stopSelectionEdgeScroll("dispatch_$a")
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 停止选区边缘自动滚动 */
    internal fun stopSelectionEdgeScroll(reason: String = "") {
        if (!textSelCtrl.dragActive && !textSelCtrl.edgeScrollPosted) return
        if (reason.isNotEmpty()) {
            ReaderLog.d(ReaderLog.Module.PDF_SELECT, "stopEdgeScroll $reason")
        }
        textSelCtrl.stopEdgeScroll()
    }


    /** 前台才跑时钟与电量刷新，后台停掉以省电 */
    private fun startClockAndBattery() {
        clockHandler.removeCallbacks(clockTick)
        clockHandler.post(clockTick)
        registerBattery()
    }

    private fun stopClockAndBattery() {
        clockHandler.removeCallbacks(clockTick)
        unregisterBattery()
    }


    // ─── 加载 ─────────────────────────────────────────────

    private fun loadPdf() = documentController.loadPdf()

    private fun showOpenFailGuide(reason: OpenFailGuide.Reason, detail: String?) = documentController.showOpenFailGuide(reason, detail)

    private fun applyReselectedUri(uri: Uri) = documentController.applyReselectedUri(uri)

    /** 打开恢复位置期间隐藏页内容（loading 遮罩盖住） */
    private fun setPdfContentHidden(hidden: Boolean) = documentController.setPdfContentHidden(hidden)

    private fun closePdf() = documentController.closePdf()

    private fun closePdfLocked() = documentController.closePdfLocked()

    /** 上次按页预取的锚点，避免滚动时重复排队 */
    internal var lastTextPrefetchAnchor = -1

    /**
     * 打开后立即：PDFBox 进内存 + 提取当前页附近 1～2 页文字/区域并缓存。
     * 不挡首屏；后续翻页/TTS 再按需预取。
     */
    internal fun startNearbyTextExtraction(uri: Uri) = textLoadController.startNearbyTextExtraction(uri)

    /** [anchor] 前后各若干页，在合法页码内 */
    internal fun pagesNear(anchor: Int, before: Int = 1, after: Int = 2): List<Int> = textLoadController.pagesNear(anchor, before, after)

    /**
     * 按需预取：当前可见页附近尚未缓存的页（默认前 1 后 2）。
     * 静默后台，不弹 Toast。
     */
    internal fun prefetchNearbyText(anchor: Int = currentVisiblePage()) = textLoadController.prefetchNearbyText(anchor)

    // ─── 模式 UI ──────────────────────────────────────────

    internal fun applyPageModeUi() = modeController.applyPageModeUi()

    /** 单页模式左上角页码（在 zoomTarget 外，天然不随内容缩放） */
    internal fun updatePageBadge() = modeController.updatePageBadge()

    /**
     * 连续模式页码在 RV item 内，会随内容一起 scale；
     * 对角标施加 1/zoom，使屏幕上字号基本固定。
     */
    private fun updatePageBadgeZoomCompensation() =
            modeController.updatePageBadgeZoomCompensation()

    private fun setPageMode(mode: PdfPageMode) = modeController.setPageMode(mode)

    internal fun invalidatePageBitmaps() {
        pdfRenderCache.bitmapCache.evictAll()
        pdfRenderCache.tileCache.evictAll()
        // 切边变化后按已知页尺寸重算列表项高度
        for (i in 0 until pageHeightTable.size) {
            val sz = rendererPageSize[i] ?: continue
            recordPageItemHeight(i, sz.first, sz.second)
        }
        pageAdapter?.notifyDataSetChanged()
        if (pageMode == PdfPageMode.SINGLE && pageCount > 0) {
            showSinglePage(pageIndex)
        }
        refreshSelectionOverlay()
    }

    private fun setupPinchZoom() = modeController.setupPinchZoom()

    internal fun rebindZoomTarget() {
        val zoomLayout = binding.pdfContainer
        zoomLayout.zoomTarget = when (pageMode) {
            PdfPageMode.CONTINUOUS -> binding.rvPdfPages
            PdfPageMode.SINGLE -> {
                if (singlePageUsesTiles) {
                    ensureSinglePageSurface()
                } else {
                    binding.ivPdfPage
                }
            }
        }
        // 连续模式缩放后竖滑 = 滚列表（可到下面页）；单页模式仍用 pan
        zoomLayout.continuousScrollWhenZoomed = pageMode == PdfPageMode.CONTINUOUS
        zoomLayout.allowTallZoomTarget =
            pageMode == PdfPageMode.SINGLE && needsTallSinglePageZoomHost()
        zoomLayout.resetVisualScale()
    }

    internal fun needsTallSinglePageZoomHost(): Boolean {
        if (pageMode != PdfPageMode.SINGLE || !::binding.isInitialized) return false
        if (singlePageUsesTiles) {
            val s = singlePageSurface ?: return false
            val vh = binding.pdfContainer.height.toFloat().coerceAtLeast(1f)
            return s.logicalHeight > vh + 1f
        }
        val d = binding.ivPdfPage.drawable ?: return isLandscape()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val host = binding.pdfContainer
        val vw = host.width.toFloat().coerceAtLeast(1f)
        val vh = host.height.toFloat().coerceAtLeast(1f)
        if (!singlePageFitByWidth(dw, dh, vw, vh)) return false
        val scale = vw / dw
        return dh * scale > vh + 1f
    }

    /** 按页取裁边（奇偶对称时左右互换）；镜像开关按本书记忆 */
    internal fun cropForPage(pageIndex: Int): FloatArray =
        PdfCropHelper.cropForPage(
            base = floatArrayOf(cropL, cropT, cropR, cropB),
            pageIndex = pageIndex,
            mirrorOddEven = fileKey.isNotEmpty() &&
                AppSettings.pdfCropMirrorOddEven(this, fileKey),
        )

    /** 见 [PdfBitmapRenderer.renderPageBitmap] */
    internal fun renderPageBitmap(
        page: PdfRenderer.Page,
        targetWidth: Int,
        targetHeight: Int? = null,
        cropOverride: FloatArray? = null,
        pageIndexForMirror: Int = -1,
    ): Bitmap {
        val margins = cropOverride
            ?: if (pageIndexForMirror >= 0) cropForPage(pageIndexForMirror)
            else floatArrayOf(cropL, cropT, cropR, cropB)
        return PdfBitmapRenderer.renderPageBitmap(
            page = page,
            targetWidth = targetWidth,
            margins = margins,
            maxRenderWidth = pdfMaxRenderWidth(),
            targetHeight = targetHeight,
        )
    }

    /** 见 [PdfBitmapRenderer.renderPageStripBitmap] */
    internal fun renderPageStripBitmap(
        page: PdfRenderer.Page,
        targetWidth: Int,
        srcY0: Float,
        srcY1: Float,
        cropOverride: FloatArray? = null,
        pageIndexForMirror: Int = -1,
    ): Bitmap {
        val margins = cropOverride
            ?: if (pageIndexForMirror >= 0) cropForPage(pageIndexForMirror)
            else floatArrayOf(cropL, cropT, cropR, cropB)
        return PdfBitmapRenderer.renderPageStripBitmap(
            page = page,
            targetWidth = targetWidth,
            srcY0 = srcY0,
            srcY1 = srcY1,
            margins = margins,
            maxRenderWidth = pdfMaxRenderWidth(),
        )
    }

    /**
     * 打开页尺寸（带缓存）。**可阻塞**：仅应在 pdf-tile-render / 已持锁路径调用。
     * 主线程 bind 请用 [pageSizeForBind]。
     */
    internal fun ensurePageSize(pageIndex: Int): Pair<Float, Float> {
        rendererPageSize[pageIndex]?.let {
            recordPageItemHeight(pageIndex, it.first, it.second)
            return it
        }
        val r = renderer ?: return 1f to 1f
        if (pageIndex !in 0 until r.pageCount) return 1f to 1f
        return try {
            synchronized(renderLock) {
                currentPage?.close()
                currentPage = null
                val page = r.openPage(pageIndex)
                currentPage = page
                val sz = page.width.toFloat() to page.height.toFloat()
                page.close()
                currentPage = null
                rendererPageSize[pageIndex] = sz
                if (sz.first > 1f) {
                    pageHeightTable.estimatedPageAspect = (sz.second / sz.first).coerceIn(0.3f, 8f)
                }
                recordPageItemHeight(pageIndex, sz.first, sz.second)
                sz
            }
        } catch (_: Exception) {
            1f to 1f
        }
    }

    // ─── 稳定页高表（PdfPageHeightTable） ─────────────────

    internal fun initPageHeightTable(count: Int) {
        val gapPx = resources.getDimensionPixelSize(R.dimen.pdf_page_gap)
        pageHeightTable.init(count, gapPx)
    }

    private fun contentWidthForHeight(): Int = pdfViewportWidth()

    /** PDF 排版/渲染宽度：优先列表实测，勿 coerce 到 720（小屏会算错页高与渲染分辨率） */
    internal fun pdfViewportWidth(): Int {
        if (::binding.isInitialized) {
            binding.rvPdfPages.width.takeIf { it > 0 }?.let { return it }
            binding.pdfContainer.width.takeIf { it > 0 }?.let { return it }
        }
        return resources.displayMetrics.widthPixels.coerceAtLeast(1)
    }

    /** 单页渲染宽度上限（保持与视口一致，横屏允许更高） */
    internal fun pdfMaxRenderWidth(): Int {
        val w = pdfViewportWidth()
        val h = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        return if (w > h) w.coerceAtMost(2560) else w.coerceAtMost(1600)
    }

    /**
     * 单页是否应按宽铺满：横屏一律；竖屏仅当页比视口更「瘦长」时（超长图）。
     * 否则 fitCenter 在矮屏上两侧留白，且 maxZoom 可能补不满屏宽。
     */
    internal fun singlePageFitByWidth(dw: Float, dh: Float, vw: Float, vh: Float): Boolean {
        if (dw <= 1f || dh <= 1f || vw <= 1f || vh <= 1f) return vw > vh
        if (vw > vh) return true
        return dh / dw >= vh / vw
    }

    internal fun updateSinglePageTallHostFlag() {
        if (!::binding.isInitialized || pageMode != PdfPageMode.SINGLE) return
        val d = binding.ivPdfPage.drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val host = binding.pdfContainer
        val vw = host.width.toFloat().coerceAtLeast(1f)
        val vh = host.height.toFloat().coerceAtLeast(1f)
        val scale = if (singlePageFitByWidth(dw, dh, vw, vh)) vw / dw else min(vw / dw, vh / dh)
        val contentH = dh * scale
        binding.pdfContainer.allowTallZoomTarget =
            singlePageFitByWidth(dw, dh, vw, vh) && contentH > vh + 1f
    }

    /** 单页超长图：分块表面（懒创建，挂在 pdfContainer 内） */
    private fun ensureSinglePageSurface(): PdfPageSurface {
        singlePageSurface?.let { return it }
        val s = PdfPageSurface(this)
        s.layoutParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        s.isClickable = false
        s.isFocusable = false
        s.isVisible = false
        val container = binding.pdfContainer
        val ivIndex = container.indexOfChild(binding.ivPdfPage)
        if (ivIndex >= 0) {
            container.addView(s, ivIndex)
        } else {
            container.addView(s)
        }
        singlePageSurface = s
        return s
    }

    internal fun hideSinglePageSurface() {
        singlePageUsesTiles = false
        singlePageSurface?.let { s ->
            s.isVisible = false
            for (b in s.drainTiles()) unpinTileBitmap(b)
            s.clearContent()
        }
    }

    /** 单页模式 tile 可见带（页内坐标，与 [PdfPageSurface.ensureTilesForVisible] 一致） */
    private fun singlePageVisibleBand(): Pair<Int, Int> {
        val surface = singlePageSurface
        val host = binding.pdfContainer
        val pageH = surface?.logicalHeight?.coerceAtLeast(1)
            ?: surface?.height?.coerceAtLeast(1)
            ?: return 0 to host.height.coerceAtLeast(1)
        val vh = host.height.coerceAtLeast(1)
        val z = host.contentZoom.coerceAtLeast(0.01f)
        val ch = pageH * z
        if (ch <= vh + 0.5f) return 0 to pageH
        val visTop = (-host.getPanY() / z).coerceIn(0f, max(0f, pageH - vh / z))
        val visBottom = (visTop + vh / z).coerceIn(visTop + 1f, pageH.toFloat())
        return visTop.toInt() to visBottom.toInt()
    }

    internal fun refreshSinglePageTiles(forceRender: Boolean = true) {
        if (pageMode != PdfPageMode.SINGLE || !singlePageUsesTiles) return
        val surface = singlePageSurface ?: return
        if (surface.pageIndex < 0 || surface.tileCount <= 0) return
        val tw = surface.width.takeIf { it > 0 }
            ?: pdfViewportWidth().coerceAtMost(pdfMaxRenderWidth())
        val band = singlePageVisibleBand()
        hydrateTilesFromCache(surface, surface.pageIndex, tw)
        if (forceRender) {
            surface.ensureTilesForVisible(band.first, band.second, tw, PdfRenderConfig.TILE_PREFETCH)
        }
        if (!binding.pdfContainer.isPinching() &&
            abs(binding.pdfContainer.contentZoom - 1f) < 0.02f
        ) {
            for (b in surface.dropTilesOutside(band.first, band.second, PdfRenderConfig.TILE_PREFETCH)) {
                unpinTileBitmap(b)
            }
        }
    }

    private fun applySinglePageSurfacePanSnap(tallPanSnap: TallPanSnap) {
        val surface = singlePageSurface ?: return
        val host = binding.pdfContainer
        val vh = host.height.toFloat().coerceAtLeast(1f)
        host.allowTallZoomTarget = surface.logicalHeight > vh + 1f
        // 一次落点：放大时水平回左上/左下，避免 preserve 旧 pan 再 clamp 造成跳动
        val (minY, maxY) = host.verticalPanLimits()
        val panY = when (tallPanSnap) {
            TallPanSnap.PRESERVE -> host.getPanY()
            TallPanSnap.TOP -> maxY
            TallPanSnap.BOTTOM -> minY
        }
        val panX = when (tallPanSnap) {
            TallPanSnap.PRESERVE -> host.getPanX()
            else -> if (host.isZoomed()) 0f else host.getPanX()
        }
        host.setTransform(host.contentZoom, panX, panY, notify = false)
    }

    /**
     * 单页超长图：立即 bind + 只渲可见 tile（不阻塞主线程）。
     */
    internal fun bindSinglePageTiled(
        index: Int,
        tallPanSnap: TallPanSnap,
        tw: Int,
    ) {
        val i = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        pageIndex = i
        singlePageUsesTiles = true
        pageTurnBusy = false

        binding.ivPdfPage.isVisible = false
        binding.ivPdfPage.setImageBitmap(null)

        val surface = ensureSinglePageSurface()
        surface.isVisible = true
        surface.alpha = binding.ivPdfPage.alpha

        val (pw, ph) = pageSizeForBind(i)
        val margins = cropForPage(i)
        recordPageItemHeight(i, pw, ph)

        for (b in surface.drainTiles()) unpinTileBitmap(b)
        surface.drainFullBitmap()
        surface.bind(
            pageIndex = i,
            pageW = pw,
            pageH = ph,
            cropL = margins[0],
            cropT = margins[1],
            cropR = margins[2],
            cropB = margins[3],
            targetWidth = tw,
            tileHeightPx = tileHeightForDevice(),
            useTiles = true,
        )
        surface.setNightMode(night)
        surface.setPageBackground(if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        surface.onNeedTile = { pageIdx, surf, tileIdx, topPx, bottomPx, width, bindGen ->
            enqueueTileRender(pageIdx, surf, tileIdx, topPx, bottomPx, width, bindGen)
        }

        rebindZoomTarget()
        pdfRenderCache.bitmapCache.evictAll()
        updatePageBadge()
        if (chromeVisible) updatePdfBookmarkButton()

        ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
            "showSinglePage tiled page=$i tw=$tw tiles=${surface.tileCount} " +
                "h=${surface.logicalHeight} snap=$tallPanSnap",
        )

        val finish = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            applySinglePageSurfacePanSnap(tallPanSnap)
            refreshSinglePageTiles(forceRender = true)
            updateProgressLabel()
            if (allowProgressSave) saveProgress(pageIndex)
            drainPendingSinglePageFlip()
        }
        if (surface.height > 0) {
            surface.post(finish)
        } else {
            surface.post { surface.post(finish) }
        }
    }

    internal fun drainPendingSinglePageFlip() {
        val pending = pendingSinglePage
        pendingSinglePage = null
        if (pending != null && !isFinishing && !isDestroyed) {
            showSinglePage(pending.first, pending.second)
        }
    }

    /** 侧点翻屏前确保 pan 边界有效（仅边界塌陷时重算矩阵） */
    internal fun ensureSinglePageTallPanReady() {
        if (!needsTallSinglePageZoomHost()) return
        val host = binding.pdfContainer
        val (minY, maxY) = host.verticalPanLimits()
        if (minY < maxY - 1f) return
        if (singlePageUsesTiles) {
            applySinglePageSurfacePanSnap(TallPanSnap.PRESERVE)
        } else {
            applySinglePageImageMatrix()
        }
    }

    /**
     * 矮页 fitCenter 时，保证 maxZoom 至少能捏到满屏宽（旧机屏矮时长页需要 >3.5x）。
     */
    internal fun updatePdfZoomLimitsForSinglePage() {
        if (!::binding.isInitialized) return
        val host = binding.pdfContainer
        if (pageMode != PdfPageMode.SINGLE) {
            host.maxZoom = 3.5f
            return
        }
        val d = binding.ivPdfPage.drawable ?: return
        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val vw = host.width.toFloat().coerceAtLeast(1f)
        val vh = host.height.toFloat().coerceAtLeast(1f)
        if (singlePageFitByWidth(dw, dh, vw, vh)) {
            host.maxZoom = 3.5f
            return
        }
        val fitCenterScale = min(vw / dw, vh / dh).coerceAtLeast(0.0001f)
        val needZoom = (vw / dw) / fitCenterScale
        host.maxZoom = max(3.5f, needZoom * 1.1f).coerceAtMost(8f)
    }

    /** 根据页尺寸 + 切边写入该项像素高度（含分隔线） */
    internal fun recordPageItemHeight(pageIndex: Int, pageW: Float, pageH: Float) {
        val tw = contentWidthForHeight()
        val margins = cropForPage(pageIndex)
        val displayH = logicalDisplayHeight(pageW, pageH, margins, tw)
        val withDiv = pageHeightTable.computeHeightWithDivider(pageIndex, pageCount, displayH)
        val changed = pageHeightTable.putHeight(pageIndex, withDiv) ?: return
        val (oldH, newH) = changed
        if (oldH == newH) return
        if (oldH > 0 && pageMode == PdfPageMode.CONTINUOUS && ::binding.isInitialized) {
            val delta = newH - oldH
            if (delta != 0) {
                val rv = binding.rvPdfPages
                val lm = rv.layoutManager as? LinearLayoutManager
                val first = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                if (first != RecyclerView.NO_POSITION && pageIndex < first) {
                    rv.scrollBy(0, delta)
                    logPdfOpen(
                        "heightComp page=$pageIndex $oldH->$newH delta=$delta first=$first",
                        force = true,
                    )
                }
            }
        }
    }

    private fun averageKnownItemHeight(): Int =
        pageHeightTable.averageKnownItemHeight(contentWidthForHeight())

    internal fun itemHeightAt(index: Int): Int =
        pageHeightTable.itemHeightAt(index, contentWidthForHeight())

    private fun totalContentHeightPx(): Long =
        pageHeightTable.totalContentHeightPx(pageCount, contentWidthForHeight())

    /**
     * 视口底边在全书页高表坐标中的 Y（连续模式 = scrollY + 视口高）。
     */
    private fun visibleBottomScrollY(): Long {
        if (pageCount <= 0) return 0L
        val total = totalContentHeightPx()
        return when (pageMode) {
            PdfPageMode.CONTINUOUS -> {
                val y = heightTableScrollY().toLong()
                val extent = binding.rvPdfPages.height.toLong().coerceAtLeast(1L)
                (y + extent).coerceAtMost(total)
            }
            PdfPageMode.SINGLE -> {
                val page = pageIndex.coerceIn(0, pageCount - 1)
                var acc = 0L
                for (i in 0 until page) acc += itemHeightAt(i).toLong()
                val pageH = itemHeightAt(page).toLong()
                val divider = if (page < pageCount - 1) pageHeightTable.pageDividerPx else 0
                val contentH = (pageH - divider).coerceAtLeast(1L)
                acc + singlePageVisibleBottomInTable(contentH)
            }
        }
    }

    /** 单页模式：视口底边映射到当前页在页高表中的像素（0..contentH） */
    private fun singlePageVisibleBottomInTable(contentTableH: Long): Long {
        if (!::binding.isInitialized) return contentTableH
        val host = binding.pdfContainer
        val vh = host.height.toFloat().coerceAtLeast(1f)
        val z = host.contentZoom.coerceAtLeast(0.01f)
        if (singlePageUsesTiles) {
            val pageH = singlePageSurface?.logicalHeight?.coerceAtLeast(1) ?: return contentTableH
            val ch = pageH * z
            if (ch <= vh + 0.5f) return contentTableH
            val visBottom = (-host.getPanY() / z + vh / z).coerceIn(0f, pageH.toFloat())
            return (visBottom / pageH * contentTableH.toFloat()).toLong().coerceIn(0L, contentTableH)
        }
        val d = binding.ivPdfPage.drawable ?: return contentTableH
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        var th = dh
        if (binding.ivPdfPage.scaleType == ImageView.ScaleType.MATRIX) {
            val vals = FloatArray(9)
            binding.ivPdfPage.imageMatrix.getValues(vals)
            th = dh * abs(vals[Matrix.MSCALE_Y]).coerceAtLeast(0.001f)
        }
        val ch = th * host.contentZoom.coerceAtLeast(0.01f)
        if (ch <= vh + 0.5f) return contentTableH
        val visBottom = (-host.getPanY() + vh).coerceIn(0f, ch)
        return (visBottom / ch * contentTableH.toFloat()).toLong().coerceIn(0L, contentTableH)
    }

    /**
     * 进度 0..1 = **视口底边在全书中的纵向位置 / 内容总高度**（页高表）。
     */
    private fun progressFromHeightTable(): Float {
        if (pageCount <= 0) return 0f
        val total = totalContentHeightPx().coerceAtLeast(1L)
        val bottom = visibleBottomScrollY().coerceIn(0L, total)
        return (bottom.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /** 连续模式：按页高表累计的绝对滚动 Y（与列表项真高一致） */
    private fun heightTableScrollY(): Int {
        if (pageMode != PdfPageMode.CONTINUOUS) return 0
        val rv = binding.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return 0
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return 0
        val child = lm.findViewByPosition(first)
        return pageHeightTable.heightTableScrollY(
            firstVisible = first,
            firstChildTop = child?.top,
            contentWidthPx = contentWidthForHeight(),
        )
    }

    /** 目标页顶在全书坐标中的 scrollY */
    internal fun scrollOffsetForPageTop(page: Int): Int =
        pageHeightTable.scrollOffsetForPageTop(page, pageCount, contentWidthForHeight())

    /** scrollY 是否落在 [page] 页高范围内（用于识别旧版 RV scroll 与真页高错位） */
    private fun scrollOffsetFitsPage(page: Int, scrollY: Int): Boolean =
        pageHeightTable.scrollOffsetFitsPage(page, scrollY, pageCount, contentWidthForHeight())

    /**
     * 按页高表跳到进度 [p]（0..1）。
     * scrollToPositionWithOffset(page, -offsetInPage)：把目标内容顶对齐视口。
     */
    private fun seekByHeightTable(p: Float) {
        if (pageCount <= 0) return
        val rv = binding.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val extent = rv.height.toLong().coerceAtLeast(1L)
        val target = pageHeightTable.seekTargetByProgress(
            progress = p,
            pageCount = pageCount,
            contentWidthPx = contentWidthForHeight(),
            extent = extent,
        ) ?: return
        rv.stopScroll()
        lm.scrollToPositionWithOffset(target.page, -target.offsetInPage)
        pageIndex = target.page
        pdfRenderScheduler.visFirst = (target.page - 1).coerceAtLeast(0)
        pdfRenderScheduler.visLast =
            (target.page + 2).coerceAtMost((pageCount - 1).coerceAtLeast(0))
    }

    /**
     * 按绝对 scrollY（页高表坐标，与 [heightTableScrollY] 保存一致）定位。
     * scrollY=0 时落到 [fallbackPage] 页顶。
     */
    private fun seekByScrollOffset(scrollY: Int, fallbackPage: Int): Int {
        val rv = binding.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return fallbackPage
        val target = pageHeightTable.seekTargetByScrollY(
            scrollY = scrollY,
            pageCount = pageCount,
            contentWidthPx = contentWidthForHeight(),
            extent = rv.height.coerceAtLeast(1),
            fallbackPage = fallbackPage,
        )
        rv.stopScroll()
        lm.scrollToPositionWithOffset(target.page, -target.offsetInPage)
        pageIndex = target.page
        pdfRenderScheduler.visFirst = (target.page - 1).coerceAtLeast(0)
        pdfRenderScheduler.visLast =
            (target.page + 2).coerceAtMost((pageCount - 1).coerceAtLeast(0))
        return target.page
    }

    /**
     * 主线程 bind 用：缓存命中则真尺寸；否则立即返回估算尺寸并后台补真值。
     * **绝不在主线程抢 renderLock**，避免快速滑动时与渲染线程互锁卡顿。
     */
    internal fun pageSizeForBind(pageIndex: Int): Pair<Float, Float> {
        rendererPageSize[pageIndex]?.let {
            recordPageItemHeight(pageIndex, it.first, it.second)
            return it
        }
        schedulePageSizeFetch(pageIndex)
        // 优先用本 PDF 已解析页的真实宽高比，避免 A4 默认比导致手机截图页过矮被压扁
        val known = rendererPageSize.values.firstOrNull()
        val aspect = if (known != null && known.first > 1f) {
            (known.second / known.first).coerceIn(0.3f, 8f)
        } else {
            pageHeightTable.estimatedPageAspect
        }
        val w = 1000f
        return w to (w * aspect)
    }

    internal fun schedulePageSizeFetch(pageIndex: Int) {
        if (pageIndex !in 0 until pageCount) return
        if (rendererPageSize.containsKey(pageIndex)) return
        if (!pdfRenderPipeline.tryAddPageSize(pageIndex)) return
        offerRenderTask(PdfRenderTask.PageSize(pageIndex))
    }

    /**
     * 真页尺寸到达后：校正已 bind 的 Surface 高度，并补渲。
     * 解决「先用估算高 bind → 图到了但高度仍错 → 整页压扁」。
     */
    internal fun onPageSizeResolved(pageIndex: Int) {
        val sz = rendererPageSize[pageIndex] ?: return
        recordPageItemHeight(pageIndex, sz.first, sz.second)
        val surface = findSurfaceForPage(pageIndex) ?: return
        val tw = surface.width.takeIf { it > 0 }
            ?: contentWidthForHeight()
        val margins = cropForPage(pageIndex)
        val tall = isTallPage(sz.first, sz.second, margins, tw)
        val tileH = tileHeightForDevice()
        val geometryChanged = surface.correctDisplayGeometry(
            pageW = sz.first,
            pageH = sz.second,
            cropL = margins[0],
            cropT = margins[1],
            cropR = margins[2],
            cropB = margins[3],
            targetWidth = tw,
            tileHeightPx = tileH,
            useTiles = tall,
        )
        if (tall) {
            surface.onNeedTile = { pageIdx, surf, tileIdx, topPx, bottomPx, width, bindGen ->
                enqueueTileRender(pageIdx, surf, tileIdx, topPx, bottomPx, width, bindGen)
            }
            hydrateTilesFromCache(surface, pageIndex, tw)
            val displayH = logicalDisplayHeight(sz.first, sz.second, margins, tw)
            ensureTallPageTilesForItem(surface, displayH, tw, PdfRenderConfig.TILE_PREFETCH)
            return
        }
        // 矮页：有 cache 则按位图再校一次高并贴图；无 cache 重新入队
        val cached = pdfRenderCache.bitmapCache.get(pageIndex)
        if (cached != null && !cached.isRecycled) {
            surface.setFullBitmap(cached)
        } else if (geometryChanged || surface.needsContent()) {
            enqueueFullPageRender(pageIndex, surface, tw, surface.bindGeneration)
        }
    }

    /** 打开后 / 翻页后：后台预取附近页尺寸 */
    internal fun prefetchPageSizesAround(center: Int, radius: Int = 8) {
        if (pageCount <= 0) return
        val c = center.coerceIn(0, pageCount - 1)
        val pages = ((c - radius)..(c + radius)).filter { it in 0 until pageCount }
        for (p in pages) schedulePageSizeFetch(p)
    }

    /** 是否处于惯性滑动：此时不排队新渲染，空白即可 */
    private fun isScrollFlinging(): Boolean =
        rvScrollState == RecyclerView.SCROLL_STATE_SETTLING

    internal fun tallThresholdPx(): Int =
        PdfLayoutMetrics.tallThresholdPx(resources.displayMetrics.heightPixels)

    /** 裁切后在 targetWidth 下的逻辑显示高度 */
    internal fun logicalDisplayHeight(
        pageW: Float,
        pageH: Float,
        margins: FloatArray,
        targetWidth: Int,
    ): Int = PdfLayoutMetrics.logicalDisplayHeight(pageW, pageH, margins, targetWidth)

    internal fun isTallPage(pageW: Float, pageH: Float, margins: FloatArray, targetWidth: Int): Boolean =
        PdfLayoutMetrics.isTallPage(
            pageW, pageH, margins, targetWidth, resources.displayMetrics.heightPixels,
        )

    /** 跳到指定页；连续模式下将该页顶对齐到列表顶部 */
    internal fun restorePosition(page: Int) {
        pageIndex = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        when (pageMode) {
            PdfPageMode.SINGLE -> showSinglePage(pageIndex)
            PdfPageMode.CONTINUOUS -> {
                pageAdapter?.setPageCount(pageCount)
                val rv = binding.rvPdfPages
                val lm = rv.layoutManager as? LinearLayoutManager
                // offset=0：目标页顶贴列表顶
                if (lm != null) {
                    lm.scrollToPositionWithOffset(pageIndex, 0)
                } else {
                    rv.scrollToPosition(pageIndex)
                }
                rv.post {
                    lm?.scrollToPositionWithOffset(pageIndex, 0)
                    updateProgressLabel()
                    if (chromeVisible) updatePdfBookmarkButton()
                }
                updateProgressLabel()
            }
        }
    }

    internal val saveZoomRunnable = Runnable {
        if (!isFinishing && !isDestroyed && allowProgressSave) {
            savePdfViewAndProgress()
        }
    }

    /** 连续模式：按页高表恢复滚动（避免 scrollToPosition + scrollBy 与估算高度错位） */
    private fun restoreContinuousScroll(page: Int, scrollY: Int) {
        val p = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        pageAdapter?.setPageCount(pageCount)
        val targetY = when {
            scrollY <= 0 -> scrollOffsetForPageTop(p)
            scrollOffsetFitsPage(p, scrollY) -> scrollY
            else -> {
                logPdfOpen(
                    "restore staleScrollY scrollY=$scrollY page=$p " +
                        "range=${scrollOffsetForPageTop(p)}.." +
                        "${scrollOffsetForPageTop(p) + itemHeightAt(p)} usePageTop",
                    force = true,
                )
                scrollOffsetForPageTop(p)
            }
        }
        val targetPage = seekByScrollOffset(targetY, p)
        logPdfOpen(
            "restoreScroll scrollY=$scrollY targetY=$targetY page=$p→$targetPage " +
                "tableSum=${totalContentHeightPx()}",
            force = true,
        )
        binding.rvPdfPages.post {
            seekByScrollOffset(targetY, targetPage)
            logPdfOpenVisible("restorePost")
        }
    }

    /** 恢复页码 + 连续滚动偏移 + 缩放平移（按文件记忆 zoom） */
    internal fun restorePdfViewState(state: AppSettings.PdfViewState) {
        val page = state.page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        pageIndex = page
        // 始终按本书记录恢复缩放（含横屏）；缩小会黑边属预期
        val zoom = state.zoom.coerceIn(0.25f, 3.5f)
        val panX = state.panX
        val panY = state.panY
        when (pageMode) {
            PdfPageMode.SINGLE -> {
                showSinglePage(page)
                binding.pdfContainer.setTransform(zoom, panX, panY, notify = true)
            }
            PdfPageMode.CONTINUOUS -> {
                restoreContinuousScroll(page, state.scrollY.coerceAtLeast(0))
                binding.rvPdfPages.post {
                    binding.pdfContainer.setTransform(zoom, panX, panY, notify = true)
                    updatePdfZoomChrome()
                    updateProgressLabel()
                    logPdfOpenVisible("restoreZoom")
                }
            }
        }
        binding.root.post {
            // 再应用一次，防止 layout 前 setTransform 被冲掉
            if (abs(binding.pdfContainer.contentZoom - zoom) > 0.02f) {
                binding.pdfContainer.setTransform(zoom, panX, panY, notify = true)
            }
            updatePdfZoomChrome()
            applyChromeVisibility()
            syncPdfContentBottomInset()
        }
        updateProgressLabel()
    }

    private fun savePdfViewAndProgress() {
        if (fileKey.isEmpty() || !allowProgressSave) return
        val page = currentVisiblePage()
        val z = binding.pdfContainer
        val scrollY = if (pageMode == PdfPageMode.CONTINUOUS) {
            heightTableScrollY()
        } else {
            0
        }
        AppSettings.savePdfViewState(
            this,
            fileKey,
            AppSettings.PdfViewState(
                page = page,
                zoom = z.contentZoom,
                panX = z.getPanX(),
                panY = z.getPanY(),
                scrollY = scrollY,
            ),
        )
        BookshelfStore.updateProgress(this, fileKey, page)
        com.whj.reader.data.ReadingProgressStore.savePdf(this, fileKey, page, pageCount)
        if (displayTitle.isNotEmpty()) {
            AppSettings.setLastPdfBook(this, fileKey, displayTitle)
        }
    }

    private fun setupRecycler() {
        pageAdapter = PdfPageAdapter(0) { index, surface, width ->
            bindPageSurface(index, surface, width)
        }
        binding.rvPdfPages.layoutManager = LinearLayoutManager(this).apply {
            isItemPrefetchEnabled = true
            // 预取 2 页：滑到前已 bind/enqueue，惯性中也能陆续出图
            initialPrefetchItemCount = 2
        }
        binding.rvPdfPages.itemAnimator = null
        binding.rvPdfPages.setHasFixedSize(false)
        binding.rvPdfPages.setItemViewCacheSize(12)
        binding.rvPdfPages.recycledViewPool.setMaxRecycledViews(0, 14)
        binding.rvPdfPages.overScrollMode = View.OVER_SCROLL_NEVER
        binding.rvPdfPages.adapter = pageAdapter
        binding.rvPdfPages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (pageMode != PdfPageMode.CONTINUOUS) return@addOnLayoutChangeListener
            if (oldBottom > 0 && bottom != oldBottom) {
                scheduleContinuousTileRefresh(
                    forceRender = true,
                    afterLayout = true,
                    reason = "rvLayoutH $oldBottom->$bottom",
                )
            }
        }
        binding.rvPdfPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (pageMode != PdfPageMode.CONTINUOUS) return
                val state = recyclerView.scrollState
                updateVisibleRangeFromRv()
                // 刚打开菜单 400ms 内不因微滚关掉（点按后 RV 偶发 onScrolled）
                if (chromeVisible &&
                    state == RecyclerView.SCROLL_STATE_DRAGGING &&
                    (dx != 0 || dy != 0) &&
                    android.os.SystemClock.uptimeMillis() - chromeShownAtMs > 400L
                ) {
                    hideChrome()
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastProgressUiMs >= progressUiMinIntervalMs) {
                    lastProgressUiMs = now
                    updateProgressLabelLight()
                }
                // 右侧快速滚动手柄：滚动时淡入
                syncFastScrollThumb(show = true)
                // 拖动 + 惯性都要节流补渲/贴 cache（不能等停下）
                val interval = if (state == RecyclerView.SCROLL_STATE_SETTLING) {
                    48L
                } else {
                    tileRefreshMinIntervalMs
                }
                if (now - lastTileRefreshMs >= interval) {
                    lastTileRefreshMs = now
                    refreshVisiblePageTiles(forceRender = true)
                }
                // TTS 句高亮 / 选区：随列表滚动同步重算屏幕坐标（不能等 IDLE）
                if (hasTtsHighlight()) refreshHighlightOverlay()
                if (hasTextSelection()) refreshSelectionOverlay()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (pageMode != PdfPageMode.CONTINUOUS) return
                rvScrollState = newState
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateVisibleRangeFromRv()
                    val visible = currentVisiblePage()
                    pageIndex = visible
                    if (allowProgressSave) saveProgress(visible)
                    trimBitmapCacheAround(visible)
                    prefetchPageSizesAround(visible)
                    updateProgressLabel()
                    syncFastScrollThumb(show = true)
                    if (chromeVisible) updatePdfBookmarkButton()
                    if (hasTextSelection()) refreshSelectionOverlay()
                    if (hasTtsHighlight()) refreshHighlightOverlay()
                    // 停下：贴图 + 升清
                    refreshVisiblePageTiles(forceRender = true)
                } else if (newState == RecyclerView.SCROLL_STATE_SETTLING ||
                    newState == RecyclerView.SCROLL_STATE_DRAGGING
                ) {
                    syncFastScrollThumb(show = true)
                    // 进入滚动立刻补一轮可见区（含惯性）
                    refreshVisiblePageTiles(forceRender = true)
                    if (hasTtsHighlight()) refreshHighlightOverlay()
                    if (hasTextSelection()) refreshSelectionOverlay()
                }
            }
        })
    }

    // ─── 右侧快速滚动手柄（Office 风格） ───────────────────

    private fun setupFastScroll() {
        binding.pdfFastScroll.onSeek = { progress, ended ->
            seekPdfByFastScroll(progress, ended)
        }
        updateFastScrollEnabled()
    }

    internal fun updateFastScrollEnabled() {
        if (!::binding.isInitialized) return
        val ok = pageMode == PdfPageMode.CONTINUOUS && pageCount > 1
        binding.pdfFastScroll.seekEnabled = ok
        if (!ok) binding.pdfFastScroll.hideImmediate()
    }

    /** 同步手柄位置与长度（可视比例）；[show] 时立刻显示并重置 1s 隐藏 */
    private fun syncFastScrollThumb(show: Boolean) {
        if (!::binding.isInitialized) return
        if (pageMode != PdfPageMode.CONTINUOUS || pageCount <= 1) {
            binding.pdfFastScroll.seekEnabled = false
            return
        }
        binding.pdfFastScroll.seekEnabled = true
        // 拖动手柄时进度由触摸驱动，勿用滚动估算覆盖
        if (!binding.pdfFastScroll.isDragging) {
            val rv = binding.rvPdfPages
            val total = totalContentHeightPx().toFloat().coerceAtLeast(1f)
            val extent = rv.height.toFloat().coerceAtLeast(1f)
            val progress = progressFromHeightTable()
            // 拇指长度 ≈ 视口/总内容（长文档拇指短）
            val fraction = (extent / total).coerceIn(0.04f, 1f)
            binding.pdfFastScroll.setScrollMetrics(progress, fraction)
        }
        if (show) binding.pdfFastScroll.onScrollActivity()
    }

    /**
     * 拖动手柄跳到 0~100%。
     * 用预计算页高累计定位（主流 PDF 阅读器做法），避免变高 item 导致跳动。
     */
    private fun seekPdfByFastScroll(progress: Float, ended: Boolean) {
        if (pageCount <= 0) return
        val p = progress.coerceIn(0f, 1f)
        when (pageMode) {
            PdfPageMode.CONTINUOUS -> {
                seekByHeightTable(p)
                // 拖动中不强制 refresh 全可见区（减卡顿）；只更新页码提示
                updateProgressLabelLight()
                if (ended) {
                    updateVisibleRangeFromRv()
                    pageIndex = currentVisiblePage()
                    if (allowProgressSave) saveProgress(pageIndex)
                    refreshVisiblePageTiles(forceRender = true)
                    updateProgressLabel()
                    // 松手后用真实可见位置校准一次（高度表已尽量准确，跳动应极小）
                    syncFastScrollThumb(show = true)
                } else {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastTileRefreshMs >= 80L) {
                        lastTileRefreshMs = now
                        updateVisibleRangeFromRv()
                        refreshVisiblePageTiles(forceRender = true)
                    }
                }
            }
            PdfPageMode.SINGLE -> {
                val page = if (pageCount <= 1) {
                    0
                } else {
                    ((pageCount - 1) * p).toInt().coerceIn(0, pageCount - 1)
                }
                if (page != pageIndex) showSinglePage(page)
                if (ended && allowProgressSave) saveProgress(page)
            }
        }
    }

    /** 只保留当前页附近缓存，离开视口的页尽快回收 */
    private fun trimBitmapCacheAround(center: Int, keepRadius: Int = PdfRenderConfig.CACHE_KEEP_RADIUS) = pageBindController.trimBitmapCacheAround(center, keepRadius)

    private fun tileHeightForDevice(): Int  = pageBindController.tileHeightForDevice()

    /** 打开前同步预取 [0..upTo] 页尺寸（仅 openPage，不渲图） */
    internal fun prefetchPageSizesUpTo(upTo: Int) = pageBindController.prefetchPageSizesUpTo(upTo)

    internal fun logPdfOpen(msg: String, force: Boolean = false) = pageBindController.logPdfOpen(msg, force)

    /** 打开/恢复后记录可见页与高度表是否一致 */
    internal fun logPdfOpenVisible(tag: String) = pageBindController.logPdfOpenVisible(tag)

    private fun logPdfZoom(msg: String, force: Boolean = false) = pageBindController.logPdfZoom(msg, force)

    /** 绑定连续模式页表面：矮页整图；长页分块 + 屏外预取 */
    private fun bindPageSurface(index: Int, surface: PdfPageSurface, targetWidth: Int) = pageBindController.bindPageSurface(index, surface, targetWidth)

    /** 位图宽高比须接近目标框，否则旋转缓存串台会整页变形 */
    private fun isBitmapAspectUsable(bmp: Bitmap, expectedH: Int, targetWidth: Int): Boolean = pageBindController.isBitmapAspectUsable(bmp, expectedH, targetWidth)

    private fun wireSurfaceGeometryCallback(surface: PdfPageSurface) = pageBindController.wireSurfaceGeometryCallback(surface)

    /** 矮页整图入队（近优先、可取消）；滚动/惯性中都会渲并贴图 */
    private fun enqueueFullPageRender(
        pageIndex: Int,
        surface: PdfPageSurface,
        targetWidth: Int,
        bindGen: Long,
    ) = pageBindController.enqueueFullPageRender(pageIndex, surface, targetWidth, bindGen)

    private fun isBitmapFullQuality(bmp: Bitmap, targetWidth: Int): Boolean  = pageBindController.isBitmapFullQuality(bmp, targetWidth)

    /** 当前列表中绑定到某页的 Surface（可能为 null） */
    internal fun findSurfaceForPage(page: Int): PdfPageSurface? = pageBindController.findSurfaceForPage(page)

    /** 把 tile 缓存里属于该页、且与当前宽度匹配的块装回 Surface */
    private fun hydrateTilesFromCache(surface: PdfPageSurface, pageIndex: Int, targetWidth: Int) = pageBindController.hydrateTilesFromCache(surface, pageIndex, targetWidth)

    /** 长页 bind/尺寸校正后按 RV 可见带补 tile（layout 完成后再算） */
    private fun ensureTallPageTilesForItem(
        surface: PdfPageSurface,
        displayH: Int,
        tw: Int,
        prefetch: Int,
    ) = pageBindController.ensureTallPageTilesForItem(surface, displayH, tw, prefetch)

    /** RV 上该页 item 在页内坐标的可见竖带；不可见返回 null */
    private fun pageVisibleBandInRv(child: View, viewportH: Int, pageH: Int): Pair<Int, Int>?  = pageBindController.pageVisibleBandInRv(child, viewportH, pageH)


    /** 缩放/列表高度变化后补渲可见 tile（等 layout 完成再算可见带） */
    internal fun scheduleContinuousTileRefresh(
        forceRender: Boolean = true,
        afterLayout: Boolean = false,
        reason: String = "",
    ) = pageBindController.scheduleContinuousTileRefresh(forceRender, afterLayout, reason)

    /** 遍历可见 item：贴缓存 + 排队缺失（拖动/惯性/停下都调用） */
    internal fun refreshVisiblePageTiles(forceRender: Boolean = true) = pageBindController.refreshVisiblePageTiles(forceRender)

    private fun enqueueTileRender(
        pageIndex: Int,
        surface: PdfPageSurface,
        tileIndex: Int,
        tileTopPx: Int,
        tileBottomPx: Int,
        targetWidth: Int,
        bindGen: Long,
    ) = pageBindController.enqueueTileRender(
        pageIndex, surface, tileIndex, tileTopPx, tileBottomPx, targetWidth, bindGen,
    )

    internal fun showSinglePage(index: Int, tallPanSnap: TallPanSnap = TallPanSnap.PRESERVE) =
            modeController.showSinglePage(index, tallPanSnap)


    private fun finishSinglePageRender(completedGen: Long) =
            modeController.finishSinglePageRender(completedGen)

    /** 单页渲染中合并后续翻页请求，避免卡顿后连跳多页 */
    private fun tryCoalesceSinglePageFlip(forward: Boolean): Boolean =
            modeController.tryCoalesceSinglePageFlip(forward)

    /**
     * 音量键翻页：减=向下/下一页，加=向上/上一页（默认开启）。
     * TTS 朗读/暂停中不拦截，交给系统调音量。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ttsActive = ::tts.isInitialized &&
            tts.currentState().state != TtsManager.State.IDLE
        if (AppSettings.volumeKeyPageTurn(this) &&
            !ttsActive &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    pageTurn(forward = true, source = "volDown")
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    pageTurn(forward = false, source = "volUp")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 左点 = 向上翻，右点 = 向下翻；无动画。
     * 连续模式：页高 > 屏高则滚 80% 屏高，否则滚一页实际高度。
     * 单页模式：仍按页切换。
     *
     * @param closeMenu 为 false 时保持底部菜单（上一页/下一页按钮）
     */
    internal fun pageTurn(
            forward: Boolean,
            closeMenu: Boolean = true,
            source: String = "unknown",
        ) = modeController.pageTurn(forward, closeMenu, source)


    internal data class PageHeightEst(val height: Int, val detail: String)

    /** 当前可见页 item 高度（含分隔线）+ 调试信息 */
    internal fun estimateCurrentPageHeightDetailed(): PageHeightEst {
        val rv = binding.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager
            ?: return PageHeightEst(rv.height, "noLm rvH=${rv.height}")
        val pos = lm.findFirstVisibleItemPosition()
        if (pos >= 0) {
            val child = lm.findViewByPosition(pos)
            if (child != null && child.height > 0) {
                val surface = child.findViewById<PdfPageSurface>(R.id.ivPage)
                val tableH = itemHeightAt(pos)
                return PageHeightEst(
                    child.height,
                    "child pos=$pos h=${child.height} top=${child.top} bot=${child.bottom} " +
                        "surfH=${surface?.height} logical=${surface?.logicalHeight} " +
                        "tiles=${surface?.tileCount} mode=${surface?.debugModeLabel()} " +
                        "tableH=$tableH",
                )
            }
        }
        // 回退：按渲染比例估算
        val r = renderer ?: return PageHeightEst(rv.height, "noRenderer rvH=${rv.height}")
        if (r.pageCount <= 0) return PageHeightEst(rv.height, "emptyPdf rvH=${rv.height}")
        return try {
            synchronized(renderLock) {
                currentPage?.close()
                currentPage = null
                val page = r.openPage(pos.coerceIn(0, r.pageCount - 1))
                currentPage = page
                val w = rv.width.coerceAtLeast(1)
                val cropW = 1f - cropL - cropR
                val cropH = 1f - cropT - cropB
                val scale = w / (page.width * cropW.coerceAtLeast(0.2f))
                val h = (page.height * cropH.coerceAtLeast(0.2f) * scale).toInt() + 1
                val pw = page.width
                val ph = page.height
                page.close()
                currentPage = null
                PageHeightEst(
                    h.coerceAtLeast(1),
                    "fallback pos=$pos pdf=${pw}x$ph scale=$scale h=$h w=$w",
                )
            }
        } catch (e: Exception) {
            PageHeightEst(rv.height, "err ${e.message} rvH=${rv.height}")
        }
    }

    /** 当前可见页 item 高度（含分隔线） */
    internal fun estimateCurrentPageHeight(): Int =
        estimateCurrentPageHeightDetailed().height

    internal fun currentVisiblePage(): Int {
        if (pageMode == PdfPageMode.CONTINUOUS) {
            val lm = binding.rvPdfPages.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: pageIndex
            return first.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }
        return pageIndex
    }

    // ─── 菜单 ─────────────────────────────────────────────

    private fun setupMenu() {
        // 风格 → 排版
        (readMenu.menuStyle.getChildAt(1) as? android.widget.TextView)?.text =
            getString(R.string.menu_layout)
        updateOrientMenuIcon()
        // 上一页 / 下一页
        readMenu.btnPrevChapter.text = getString(R.string.pdf_prev_page)
        readMenu.btnNextChapter.text = getString(R.string.pdf_next_page)
        // 上/下一页：翻页后保持菜单打开
        readMenu.btnPrevChapter.setOnClickListener {
            pageTurn(false, closeMenu = false)
        }
        readMenu.btnNextChapter.setOnClickListener {
            pageTurn(true, closeMenu = false)
        }
        readMenu.menuStyle.setOnClickListener {
            hideChrome()
            openPdfSettingsPanel()
        }
        readMenu.menuPref.setOnClickListener {
            hideChrome()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        readMenu.menuJump.setOnClickListener {
            hideChrome()
            showPageJumpDialog()
        }
        readMenu.menuToc.setOnClickListener {
            hideChrome()
            showPageToc()
        }
        readMenu.menuOrient.setOnClickListener {
            // 竖屏 ↔ 横屏（已去掉自动旋转）
            val next = when (AppSettings.pdfOrientationMode(this)) {
                OrientationMode.LANDSCAPE -> OrientationMode.PORTRAIT
                else -> OrientationMode.LANDSCAPE
            }
            AppSettings.setPdfOrientationMode(this, next)
            if (chromeVisible) {
                // 旋转后保持菜单
                chromeVisible = true
            }
            applyOrientationMode(
                next,
                force = OrientationHelper.isLargeScreen(this),
            )
            updateOrientMenuIcon()
            val label = when (next) {
                OrientationMode.LANDSCAPE -> getString(R.string.orient_landscape)
                else -> getString(R.string.orient_portrait)
            }
            Toasts.show(this, getString(R.string.orient_switched, label))
        }
        readMenu.menuFullscreen.setOnClickListener {
            if (!immersive && hasDisplayCutout()) {
                Toasts.show(this, R.string.immersive_cutout_unsupported)
                return@setOnClickListener
            }
            immersive = !immersive
            applyImmersive()
            Toasts.show(
                this,
                if (immersive) R.string.immersive_on else R.string.immersive_off,
            )
        }
        readMenu.menuNight.setOnClickListener {
            night = !night
            AppSettings.setPdfNight(this, night)
            applyNightUi()
            // 刷新当前页滤镜；不关底部菜单
            if (pageMode == PdfPageMode.SINGLE) {
                applyNightFilter(binding.ivPdfPage)
                singlePageSurface?.setNightMode(night)
                singlePageSurface?.setPageBackground(
                    if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(),
                )
            } else {
                applyNightFilterToVisibleSurfaces()
            }
        }
        readMenu.menuRead.setOnClickListener {
            hideChrome()
            startPdfTts()
        }
        readMenu.menuSynth.setOnClickListener {
            openPdfExportPanel()
        }
    }

    internal fun updateOrientMenuIcon() {
        if (!::readMenu.isInitialized) return
        val mode = AppSettings.pdfOrientationMode(this)
        val iv = readMenu.menuOrient.getChildAt(0) as? android.widget.ImageView ?: return
        iv.setImageResource(OrientationHelper.menuIconRes(mode))
        val label = when (mode) {
            OrientationMode.LANDSCAPE -> getString(R.string.orient_landscape)
            else -> getString(R.string.orient_portrait)
        }
        (readMenu.menuOrient.getChildAt(1) as? android.widget.TextView)?.text = label
    }

    private fun setupMenuPager() {
        val pager = readMenu.menuPager
        pager.pageCount = 2
        pager.onPageSettled = { page -> updateMenuPageDots(page) }
        pager.setOnScrollChangeListener { _, _, _, _, _ -> updateMenuPageDots() }
    }

    private fun updateMenuPageDots(page: Int? = null) {
        if (!::readMenu.isInitialized) return
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

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val ttsActive = ::tts.isInitialized &&
                        tts.currentState().state != TtsManager.State.IDLE
                    when {
                        exportPanelOpen -> closePdfExportPanel()
                        binding.settingsPanelContainer.isVisible -> {
                            binding.settingsPanelContainer.isVisible = false
                        }
                        ttsBarOpen || ttsActive -> {
                            if (::tts.isInitialized) tts.stop()
                            ttsBarOpen = false
                            applyChromeVisibility()
                        }
                        chromeVisible -> hideChrome()
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            },
        )
    }

    private fun setupPdfExportPanel() = ttsController.setupPdfExportPanel()
    private fun openPdfExportPanel() = ttsController.openPdfExportPanel()
    private fun closePdfExportPanel() = ttsController.closePdfExportPanel()
    private fun setupPdfSettings() {
        binding.settingsScrim.setOnClickListener {
            binding.settingsPanelContainer.isVisible = false
        }
        pdfSettings.btnModeContinuous.setOnClickListener {
            setPageMode(PdfPageMode.CONTINUOUS)
        }
        pdfSettings.btnModeSingle.setOnClickListener {
            setPageMode(PdfPageMode.SINGLE)
        }
        pdfSettings.btnOpenCrop.setOnClickListener {
            openCropActivity()
        }
        updateCropSummary()
        updateModeButtons()
    }

    private fun bindCropSeek() {
        updateCropSummary()
    }

    internal fun updateCropSummary() {
        if (!::pdfSettings.isInitialized) return
        fun pct(v: Float) = (v * 100).toInt()
        pdfSettings.tvCropSummary.text = getString(
            R.string.pdf_crop_summary,
            pct(cropL), pct(cropT), pct(cropR), pct(cropB),
        )
    }

    internal fun updateModeButtons() {
        if (!::pdfSettings.isInitialized) return
        val cont = pageMode == PdfPageMode.CONTINUOUS
        val primary = AppTheme.primary(this)
        val soft = AppTheme.accentSoft(this)
        val white = 0xFFFFFFFF.toInt()
        val textPrimary = getColor(R.color.text_primary)
        // 选中：主题色实心；未选中：主题浅底 + 主题描边
        fun styleSelected(btn: com.google.android.material.button.MaterialButton, selected: Boolean) {
            val strokePx = (1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(2)
            if (selected) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
                btn.setTextColor(white)
                btn.strokeWidth = 0
                btn.strokeColor = android.content.res.ColorStateList.valueOf(primary)
                btn.alpha = 1f
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(soft)
                btn.setTextColor(textPrimary)
                btn.strokeWidth = strokePx
                btn.strokeColor = android.content.res.ColorStateList.valueOf(primary)
                btn.alpha = 1f
            }
        }
        styleSelected(pdfSettings.btnModeContinuous, cont)
        styleSelected(pdfSettings.btnModeSingle, !cont)
        pdfSettings.tvModeCurrent.setTextColor(primary)
        pdfSettings.tvModeCurrent.text = getString(
            if (cont) R.string.pdf_mode_switched_continuous else R.string.pdf_mode_switched_single,
        )
    }

    /** 打开全屏裁剪页面（红框八柄 + 奇偶对称） */
    private fun openCropActivity() {
        if (fileKey.isEmpty()) return
        cropLauncher.launch(
            Intent(this, PdfCropActivity::class.java)
                .putExtra(PdfCropActivity.EXTRA_URI, fileKey)
                .putExtra(PdfCropActivity.EXTRA_PAGE, currentVisiblePage()),
        )
    }

    // ─── TTS / 文字提取（仅启动 TTS / 选字时按页懒加载）────

    private fun hasExtractedRaw(): Boolean  = textLoadController.hasExtractedRaw()

    private fun maxCachedPage(): Int  = textLoadController.maxCachedPage()

    /**
     * 确保 [pages] 已提取；缺失页后台抽取后重建段落。
     * @param preserveTtsPosition true 时用 updateDocumentKeepPosition，不打断当前句
     * @param onReady 参数 true = 本次有新页写入缓存
     */
    internal fun ensurePagesExtracted(
        pages: Collection<Int>,
        showToast: Boolean = false,
        preserveTtsPosition: Boolean = false,
        onReady: ((added: Boolean) -> Unit)? = null,
    ) = textLoadController.ensurePagesExtracted(pages, showToast, preserveTtsPosition, onReady)

    internal fun rebuildTextFromCache(preserveTtsPosition: Boolean = false) = textLoadController.rebuildTextFromCache(preserveTtsPosition)

    /** 切边变更后仅重过滤缓存，不重新抽字 */
    private fun applyCropToExtractedText() = textLoadController.applyCropToExtractedText()

    /** 将磁盘 OCR 页合并进 textCache.rawPageCache（不覆盖已有 PDF 原生文字） */
    internal fun mergeOcrCacheFromDisk() = textLoadController.mergeOcrCacheFromDisk()

    // ─── PDF 页面 OCR（扫描版识图）────────────────────────

    private fun showPdfOcrDialog() = ocrUiController.showPdfOcrDialog()


    private fun startPdfOcrJob(fromPage0: Int, toPage0: Int, skipDone: Boolean) =
            ocrUiController.startPdfOcrJob(fromPage0, toPage0, skipDone)

    /** 渲染单页 → OCR → 持久化 + 坐标映射为 PdfChar。实现见 [PdfPageOcrRunner]。 */
    private fun ocrOnePage(pageIndex: Int, engine: TfliteOcrEngine): Boolean =
        pdfOcrRunner.ocrOnePage(pageIndex, engine)


    private fun startPdfTts() = ttsController.startPdfTts()
    internal fun currentPageHasText(): Boolean {
        val page = currentVisiblePage()
        val chars = textCache.pageChars[page] ?: return false
        return chars.any { !it.char.isWhitespace() }
    }

    internal fun applyTtsSentenceHighlight(paragraphIndex: Int, startOffset: Int, endOffset: Int) {
        if (endOffset < 0 || paragraphIndex < 0 || paragraphIndex >= textCache.paraLinks.size) {
            clearTtsHighlight()
            return
        }
        val link = textCache.paraLinks[paragraphIndex]
        val len = (link.charEnd - link.charStart).coerceAtLeast(0)
        if (len <= 0) {
            clearTtsHighlight()
            return
        }
        val a = startOffset.coerceIn(0, len - 1)
        val b = (endOffset - 1).coerceIn(a, len - 1)
        // 当前段在单页；若后续段连续同句跨页，合并相邻 textCache.paraLinks 的高亮区间
        hlStartPage = link.pageIndex
        hlStartChar = link.charStart + a
        hlEndPage = link.pageIndex
        hlEndChar = link.charStart + b
        // 同一 TTS 句若跨多段（跨页），按 offset 仍在本段内；跨页高亮由选区逻辑同款多页 rect 绘制
        refreshHighlightOverlay()
        binding.pdfContainer.post {
            if (isFinishing || isDestroyed) return@post
            scrollToCharRange(hlStartPage, hlStartChar, hlEndChar)
        }
    }

    /**
     * 底部叠层高度（状态栏 + TTS 条等），与 [pdfContainer] 坐标一致。
     * 判可见 / 跟读滚动时从可视底边扣除。
     */
    private fun pdfBottomObscuredPx(): Float {
        if (!::binding.isInitialized) return 0f
        var h = 0f
        if (binding.readStatusBar.isVisible) {
            h += binding.readStatusBar.height.coerceAtLeast(0).toFloat()
        }
        val bc = binding.bottomChrome
        if (bc.visibility == View.VISIBLE) {
            val ttsH = if (binding.ttsBar.isVisible) binding.ttsBar.height.coerceAtLeast(0) else 0
            val menuH = if (binding.readMenuHost.isVisible) {
                binding.readMenuHost.height.coerceAtLeast(0)
            } else {
                0
            }
            val expH = if (binding.ttsExportHost.isVisible) {
                binding.ttsExportHost.height.coerceAtLeast(0)
            } else {
                0
            }
            val inner = maxOf(ttsH, menuH, expH)
            if (inner > 0) {
                h += inner + bc.paddingBottom.coerceAtLeast(0)
            } else if (ttsBarOpen && !chromeVisible) {
                // 条尚未 measure 完：估一个最小高度，避免误判「已可见」
                val dens = resources.displayMetrics.density
                h += (56f * dens) + bc.paddingBottom.coerceAtLeast(0)
            }
        }
        return h
    }

    /** 容器内竖直可视区间 [top, bottom]（已扣 TTS 等遮挡） */
    private fun pdfVisibleYRange(): Pair<Float, Float> {
        val top = 0f
        val bottom = (
            binding.pdfContainer.height.toFloat() - pdfBottomObscuredPx()
            ).coerceAtLeast(48f)
        return top to bottom
    }

    internal fun scrollToCharRange(page: Int, charStart: Int, charEnd: Int) {
        if (page < 0 || pageCount <= 0) return
        val chars = textCache.pageChars[page] ?: return
        val slice = chars.filter { it.indexOnPage in charStart..charEnd }
        if (slice.isEmpty()) {
            // 至少翻到该页
            if (pageMode == PdfPageMode.SINGLE && page != pageIndex) {
                showSinglePage(page)
            } else if (pageMode == PdfPageMode.CONTINUOUS) {
                binding.rvPdfPages.scrollToPosition(page)
            }
            pageIndex = page
            updateProgressLabel()
            return
        }
        // 目标句已在「扣除 TTS 栏」的视窗内：不改竖直滚动
        if (isCharRangeFullyInViewport(page, charStart, charEnd)) {
            pageIndex = page
            updateProgressLabel()
            return
        }
        // 不完全在视窗：把句子顶部对齐到可视区最上（TTS 栏之上）
        val topY = slice.minOf { it.top }
        val pageH = slice.first().pageHeight.coerceAtLeast(1f)
        val fracTop = (topY / pageH).coerceIn(0f, 1f)
        val topPadPx = (8f * resources.displayMetrics.density).toInt().coerceAtLeast(4)
        when (pageMode) {
            PdfPageMode.SINGLE -> {
                if (page != pageIndex) showSinglePage(page)
                // 单页模式无列表滚动；缩放态下靠 pan 有限，至少保证在正确页
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = binding.rvPdfPages
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                fun alignSentenceTop(child: android.view.View) {
                    val targetTop =
                        child.top + (child.height * fracTop).toInt() - topPadPx
                    if (targetTop != 0) rv.scrollBy(0, targetTop)
                }
                val child = lm.findViewByPosition(page)
                fun refineAfterScroll() {
                    if (isFinishing || isDestroyed) return
                    if (!isCharRangeFullyInViewport(page, charStart, charEnd)) {
                        val c2 = lm.findViewByPosition(page)
                        if (c2 != null) alignSentenceTop(c2)
                    }
                    refreshHighlightOverlay()
                }
                if (child != null) {
                    alignSentenceTop(child)
                    // 二次校正：scrollBy 后若仍被 TTS 挡住再补滚
                    rv.post { refineAfterScroll() }
                } else {
                    rv.scrollToPosition(page)
                    rv.post {
                        val c = lm.findViewByPosition(page)
                        if (c != null) alignSentenceTop(c)
                        refreshHighlightOverlay()
                        rv.post { refineAfterScroll() }
                    }
                }
            }
        }
        pageIndex = page
        updateProgressLabel()
    }

    /**
     * 句子是否已完全落在**扣除 TTS/底栏**后的可视区内。
     * 已可见则 [scrollToCharRange] 不应再 scrollBy。
     */
    private fun isCharRangeFullyInViewport(page: Int, charStart: Int, charEnd: Int): Boolean {
        when (pageMode) {
            PdfPageMode.SINGLE -> {
                if (page != pageIndex) return false
                val rects = charRangeToContainerRects(page, charStart, charEnd)
                if (rects.isEmpty()) return true
                val (visTop, visBottom) = pdfVisibleYRange()
                val pad = 6f
                return rects.all { r ->
                    r.top >= visTop - pad && r.bottom <= visBottom + pad
                }
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = binding.rvPdfPages
                val lm = rv.layoutManager as? LinearLayoutManager ?: return false
                if (lm.findViewByPosition(page) == null) return false
                val rects = charRangeToContainerRects(page, charStart, charEnd)
                if (rects.isEmpty()) return false
                val (visTop, visBottom) = pdfVisibleYRange()
                val pad = 6f
                return rects.all { r ->
                    r.top >= visTop - pad && r.bottom <= visBottom + pad
                }
            }
        }
    }

    private fun withTtsNotificationPermission(then: () -> Unit) =
        ttsController.withTtsNotificationPermission(then)
    private fun startTtsFromViewport() = ttsController.startTtsFromViewport()
    internal fun mapPageCharToParaOffset(page: Int, charIndexOnPage: Int): Pair<Int, Int>? =
        textSelCtrl.mapPageCharToParaOffset(textCache.paraLinks, page, charIndexOnPage)


    internal fun startTtsFromSelection() = ttsController.startTtsFromSelection()

    // —— 控制器（PdfTtsController 等）直接访问的辅助方法（原 Host 桥接逻辑迁移） ——
    internal fun showToast(res: Int, vararg args: Any) {
        if (args.isEmpty()) Toasts.show(this, res)
        else Toasts.show(this, getString(res, *args))
    }

    internal fun showToastLong(msg: String) =
        Toasts.show(this, msg, android.widget.Toast.LENGTH_LONG)

    internal fun addWindowFlags(flags: Int) { window.addFlags(flags) }

    internal fun clearWindowFlags(flags: Int) { window.clearFlags(flags) }

    internal fun shareIntent(uri: android.net.Uri, type: String, titleRes: Int) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(titleRes)))
    }

    /** 选区触发的单页换页：snapToTop=true 落到页首，false 落到页末 */
    internal fun showSinglePageForSelection(index: Int, snapToTop: Boolean) {
        showSinglePage(index, if (snapToTop) TallPanSnap.TOP else TallPanSnap.BOTTOM)
    }
    private fun setupTtsBar() = ttsController.setupTtsBar()
    private fun updateSleepUi() = ttsController.updateSleepUi()
    private fun onSleepTimerFinished() = ttsController.onSleepTimerFinished()
    private fun updateTtsUi(snapshot: TtsManager.Snapshot) = ttsController.updateTtsUi(snapshot)
    internal fun isLandscape(): Boolean = chromeController.isLandscape()

    /** 真实窗口是否横置（状态栏/沉浸 UI 用这个，避免竖屏模式在横窗上把底栏顶到画面中间） */
    internal fun isWindowLandscape(): Boolean = chromeController.isWindowLandscape()

    /** 横竖均占满；清除历史中间竖栏 padding，保留底 inset */
    internal fun applyPortraitColumnLayout() = chromeController.applyPortraitColumnLayout()

    /**
     * 旋转后 / 模态框后收起底栏异常高度：透明 bottomChrome 被撑高时，
     * 状态栏会浮在画面中间，菜单区露出 PDF 黑底。
     */
    internal fun collapseBottomChromeLayout(hideMenuHost: Boolean) =
            chromeController.collapseBottomChromeLayout(hideMenuHost)

    internal fun sanitizeBottomChrome() = chromeController.sanitizeBottomChrome()

    /** OCR/导出进度框关闭后重算底栏（避免菜单区黑条，需再点菜单才恢复） */
    internal fun refreshBottomChromeAfterModal(reason: String) =
            chromeController.refreshBottomChromeAfterModal(reason)

    internal fun logPdfChrome(tag: String) {
        if (!::binding.isInitialized) return
        val bc = binding.bottomChrome
        ReaderLog.i(ReaderLog.Module.PDF_CHROME,
            "$tag chrome=$chromeVisible ttsOpen=$ttsBarOpen export=$exportPanelOpen " +
                "menuVis=${binding.readMenuHost.visibility} menuH=${binding.readMenuHost.height} " +
                "ttsVis=${binding.ttsBar.visibility} ttsH=${binding.ttsBar.height} " +
                "bc=${bc.width}x${bc.height} rvPadB=${binding.rvPdfPages.paddingBottom}",
        )
    }

    internal fun logPdfOcr(msg: String) {
        ReaderLog.i(ReaderLog.Module.PDF_OCR, msg)
    }

    /** 沉浸/底栏：按用户选择的横竖模式 [isLandscape] */
    internal fun applyLandscapeFullscreenUi() = chromeController.applyLandscapeFullscreenUi()

    internal fun applyChromeVisibility() = chromeController.applyChromeVisibility()

    /** 模态框（OCR 进度等）弹出前收起底栏并立刻去掉菜单区 padding，避免露出 PDF 黑底 */
    internal fun prepareBottomChromeForBlockingModal() =
            chromeController.prepareBottomChromeForBlockingModal()

    /**
     * 内容区底部 inset：TTS 条 / 状态栏 / 导航垫高，避免遮住 PDF 最后几行。
     */
    internal fun syncPdfContentBottomInset() = chromeController.syncPdfContentBottomInset()

    /** 旋转后丢弃在途渲染 / 贴图，避免旧宽结果贴到新布局 */
    internal fun cancelInFlightPdfRenders(reason: String) {
        pdfRenderScheduler.cancelAllQueued()
        pdfRenderPipeline.cancelAllPending()
        uiAttachQueue.clear()
        ReaderLog.i(ReaderLog.Module.PDF_ORIENT, "cancelInFlight reason=$reason")
    }

    /**
     * 等 PDF 容器宽高与当前 configuration 大致一致后再执行（旋转 layout 常滞后 1～几帧）。
     */
    internal fun runWhenPdfViewportSettled(
            reason: String,
            maxTries: Int = 12,
            block: () -> Unit,
        ) = chromeController.runWhenPdfViewportSettled(reason, maxTries, block)

    /** 旋转/切换视角后统一重铺；清掉错误宽度的 tile，防止长图压扁 */
    internal fun relayoutAfterOrientationChange() =
            modeController.relayoutAfterOrientationChange()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
            "onConfigurationChanged orient=${newConfig.orientation} " +
                "size=${newConfig.screenWidthDp}x${newConfig.screenHeightDp} " +
                "mode=${AppSettings.pdfOrientationMode(this)} pageMode=$pageMode",
        )
        // 只重铺，不再 setRequestedOrientation
        if (chromeVisible) {
            chromeVisible = true
        }
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            sanitizeBottomChrome()
            relayoutAfterOrientationChange()
        }
    }

    /** 预测量菜单，避免第一次点开空白 */
    private fun premeasureReadMenu() {
        val host = binding.readMenuHost
        host.visibility = View.INVISIBLE
        host.post {
            forceMenuLayout(preservePage = false)
            if (!chromeVisible) {
                host.visibility = View.GONE
            }
        }
    }

    /**
     * 底部菜单：两屏分页，第 1 屏固定 **2 行 × 4 列**。
     * 旋转后必须按新屏宽重设每页宽度，否则图标挤成一行或菜单空白。
     */
    internal fun forceMenuLayout(preservePage: Boolean = false) {
        if (!::binding.isInitialized || !::readMenu.isInitialized) return
        val host = binding.readMenuHost
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val laidOutW = binding.bottomChrome.width.takeIf { it > 0 }
            ?: binding.root.width.takeIf { it > 0 }
        val parentW = when {
            laidOutW == null -> screenW
            abs(laidOutW - screenW) > screenW * 0.15f -> screenW
            else -> laidOutW
        }
        if (parentW <= 0) return
        val prevPage = if (preservePage) {
            val pw = readMenu.menuPager.width.coerceAtLeast(1)
            ((readMenu.menuPager.scrollX + pw / 2f) / pw).toInt().coerceIn(0, 1)
        } else {
            0
        }
        // 两页各占满一屏 → 每页内 2×4 权重均分
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
        binding.bottomChrome.requestLayout()
        readMenu.menuPager.settleToPage(prevPage, smooth = false)
        updateMenuPageDots(prevPage)
    }

    private fun showPageJumpDialog() {
        if (pageCount <= 0) return
        val seek = SeekBar(this).apply {
            max = (pageCount - 1).coerceAtLeast(0)
            progress = currentVisiblePage()
        }
        val label = android.widget.TextView(this).apply {
            setPadding(48, 24, 48, 8)
            text = getString(R.string.pdf_page_of, seek.progress + 1, pageCount)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = getString(R.string.pdf_page_of, progress + 1, pageCount)
                if (!fromUser) return
                // 拖动中防抖预览目标页
                scheduleJumpPreview(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                cancelJumpPreview()
                val p = seekBar?.progress ?: return
                restorePosition(p)
                if (allowProgressSave) saveProgress(p)
            }
        })
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(label)
            addView(seek)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_jump)
            .setView(box)
            .setPositiveButton(R.string.confirm) { _, _ ->
                cancelJumpPreview()
                restorePosition(seek.progress)
                if (allowProgressSave) saveProgress(seek.progress)
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { cancelJumpPreview() }
            .show()
    }

    private fun scheduleJumpPreview(page: Int) {
        cancelJumpPreview()
        val r = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            restorePosition(page)
            // 预览不立刻写盘，松手 / 确认时再保存
        }
        jumpPreviewRunnable = r
        jumpPreviewHandler.postDelayed(r, jumpPreviewDelayMs)
    }

    private fun cancelJumpPreview() {
        jumpPreviewRunnable?.let { jumpPreviewHandler.removeCallbacks(it) }
        jumpPreviewRunnable = null
    }

    /**
     * 屏幕上「显示比例最全」的第一页（连续模式多页时取可见比最大且靠前的页；单页即当前页）。
     */
    internal fun mostVisiblePage(): Int {
        if (pageCount <= 0) return 0
        if (pageMode == PdfPageMode.SINGLE) {
            return pageIndex.coerceIn(0, pageCount - 1)
        }
        val rv = binding.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager
            ?: return pageIndex.coerceIn(0, pageCount - 1)
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) {
            return pageIndex.coerceIn(0, pageCount - 1)
        }
        val vh = rv.height.coerceAtLeast(1)
        var bestPage = first.coerceIn(0, pageCount - 1)
        var bestRatio = -1f
        for (pos in first..last.coerceAtLeast(first)) {
            if (pos !in 0 until pageCount) continue
            val child = lm.findViewByPosition(pos) ?: continue
            val top = child.top.coerceAtLeast(0)
            val bottom = child.bottom.coerceAtMost(vh)
            val visible = (bottom - top).toFloat().coerceAtLeast(0f)
            val h = child.height.coerceAtLeast(1).toFloat()
            val ratio = (visible / h).coerceIn(0f, 1f)
            // 更全则更新；同样全时保留更靠前的页（第一页）
            if (ratio > bestRatio + 0.001f) {
                bestRatio = ratio
                bestPage = pos
            }
        }
        return bestPage
    }

    private fun pdfBookmarkProgress(page: Int): Float {
        if (pageCount <= 1) return if (page > 0) 100f else 0f
        return ((page.toFloat() / (pageCount - 1).toFloat()) * 100f).coerceIn(0f, 100f)
    }

    internal fun updatePdfBookmarkButton() {
        if (!::binding.isInitialized || fileKey.isBlank() || pageCount <= 0) {
            if (::binding.isInitialized) {
                binding.btnBookmark.setImageResource(R.drawable.ic_bookmark_border)
            }
            return
        }
        val page = mostVisiblePage()
        val on = com.whj.reader.data.BookmarkStore.has(this, fileKey, page)
        binding.btnBookmark.setImageResource(
            if (on) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
        )
    }

    private fun togglePdfBookmark() {
        if (fileKey.isBlank() || pageCount <= 0) return
        val page = mostVisiblePage()
        if (com.whj.reader.data.BookmarkStore.has(this, fileKey, page)) {
            com.whj.reader.data.BookmarkStore.remove(this, fileKey, page)
            Toasts.show(this, R.string.bookmark_off)
            updatePdfBookmarkButton()
            return
        }
        // 先立刻写入书签并刷新图标（预览用缓存文字；无缓存先占位再后台补全）
        val cachedPreview = previewFromCache(page)
        val pct = pdfBookmarkProgress(page)
        com.whj.reader.data.BookmarkStore.add(
            this,
            com.whj.reader.model.Bookmark(
                fileKey = fileKey,
                paragraphIndex = page, // PDF：存页码（0-based）
                preview = cachedPreview ?: getString(R.string.pdf_bookmark_no_text),
                progressPercent = pct,
            ),
        )
        Toasts.show(this, R.string.bookmark_on)
        updatePdfBookmarkButton()
        // 缓存没有文字时再后台抽字，写回预览（不挡 UI）
        if (cachedPreview.isNullOrBlank()) {
            lifecycleScope.launch {
                val preview = withContext(Dispatchers.IO) {
                    extractPagePreview(page)
                }
                if (isFinishing || isDestroyed) return@launch
                if (!com.whj.reader.data.BookmarkStore.has(this@PdfReadingActivity, fileKey, page)) {
                    return@launch
                }
                com.whj.reader.data.BookmarkStore.add(
                    this@PdfReadingActivity,
                    com.whj.reader.model.Bookmark(
                        fileKey = fileKey,
                        paragraphIndex = page,
                        preview = preview,
                        progressPercent = pct,
                    ),
                )
            }
        }
    }

    private fun previewFromCache(page: Int): String? = navBookmarkController.previewFromCache(page)

    private fun extractPagePreview(page: Int): String = navBookmarkController.extractPagePreview(page)

    internal fun preloadOutlineAsync(uri: Uri) = navBookmarkController.preloadOutlineAsync(uri)

    private fun showPageToc() = navBookmarkController.showPageToc()

    private fun showPdfTocAndBookmarkSheet(roots: List<com.whj.reader.data.PdfOutlineLoader.Node>) =
        navBookmarkController.showPdfTocAndBookmarkSheet(roots)

    private fun setupPageTouch() = navBookmarkController.setupPageTouch()

    internal fun handleTap(x: Float, width: Float) = navBookmarkController.handleTap(x, width)

    internal fun loadPdfLinksAsync(uri: Uri) = navBookmarkController.loadPdfLinksAsync(uri)

    internal fun tryHandlePdfLinkTap(containerX: Float, containerY: Float): Boolean =
        navBookmarkController.tryHandlePdfLinkTap(containerX, containerY)

    private fun hitTestLink(containerX: Float, containerY: Float): PdfLinkIndex.Link? =
        navBookmarkController.hitTestLink(containerX, containerY)

    internal fun navigateToPageWithHistory(targetPage: Int) =
        navBookmarkController.navigateToPageWithHistory(targetPage)

    private fun navigateHistoryBack() = navBookmarkController.navigateHistoryBack()

    private fun navigateHistoryForward() = navBookmarkController.navigateHistoryForward()

    internal fun updateHistNavButtons() = navBookmarkController.updateHistNavButtons()

    private fun confirmOpenExternalUri(uriStr: String) = navBookmarkController.confirmOpenExternalUri(uriStr)

    // ─── 长按选字 / 坐标映射（支持跨页） ──────────────────

    private fun compareDocPos(pageA: Int, charA: Int, pageB: Int, charB: Int): Int =
        selectionInteractor.compareDocPos(pageA, charA, pageB, charB)

    internal fun hasTextSelection(): Boolean = selectionInteractor.hasTextSelection()

    internal fun hasTtsHighlight(): Boolean = selectionInteractor.hasTtsHighlight()

    internal fun clearTtsHighlight() = selectionInteractor.clearTtsHighlight()

    internal fun clearTextSelection(fromActionModeDestroy: Boolean = false) =
        selectionInteractor.clearTextSelection(fromActionModeDestroy)

    private fun setSelectionFromAnchorAndHit(hitPage: Int, hitChar: Int) =
        selectionInteractor.setSelectionFromAnchorAndHit(hitPage, hitChar)

    private fun normalizeSelectionOrder() = selectionInteractor.normalizeSelectionOrder()

    /** 选区覆盖的每一页上的字符闭区间 -> 容器矩形（跨页拼接） */
    private fun multiPageCharRangeToContainerRects(
        startPage: Int,
        startChar: Int,
        endPage: Int,
        endChar: Int,
    ): List<RectF> = selectionInteractor.multiPageCharRangeToContainerRects(startPage, startChar, endPage, endChar)

    internal fun beginTextSelection(containerX: Float, containerY: Float) =
        selectionInteractor.beginTextSelection(containerX, containerY)

    /** 滑动改 pan / 抬手未落字：作废进行中的长按选字 */
    internal fun cancelPendingTextSelectionGesture() =
        selectionInteractor.cancelPendingTextSelectionGesture()

    /** 文字已就绪（或确认无字）后进入选区，禁止再触发提取递归 */
    private fun beginTextSelectionAfterReady(containerX: Float, containerY: Float) =
        selectionInteractor.beginTextSelectionAfterReady(containerX, containerY)

    internal fun extendTextSelection(containerX: Float, containerY: Float) =
        selectionInteractor.extendTextSelection(containerX, containerY)

    /** 选区跨越的页若尚未抽字，后台补齐并回夹字符下标 */
    private fun prefetchTextForSelectionRange() = selectionInteractor.prefetchTextForSelectionRange()

    /** 抽字完成后把选区下标夹到真实字符范围 */
    internal fun clampSelectionToLoadedChars() = selectionInteractor.clampSelectionToLoadedChars()

    internal fun adjustPdfSelectionHandle(which: TextSelectionHandles.Which, x: Float, y: Float) =
        selectionInteractor.adjustPdfSelectionHandle(which, x, y)

    internal fun autoScrollPdfWhileSelecting(containerY: Float) =
        selectionInteractor.autoScrollPdfWhileSelecting(containerY)

    /**
     * 连续模式：用页高表估算手指处文档页，强制推进选区终点（解决「只能选到有 child 的页」）。
     */
    private fun extendSelectionByDocumentY(containerY: Float, forward: Boolean) =
        selectionInteractor.extendSelectionByDocumentY(containerY, forward)

    /** 容器 Y -> 页高表估算的 0-based 页码 */
    private fun pageIndexAtContainerY(containerY: Float): Int? =
        selectionInteractor.pageIndexAtContainerY(containerY)

    /** 手指在 [page] 页内的纵向比例 0..1（估） */
    private fun pageLocalYFraction(containerY: Float, page: Int): Float =
        selectionInteractor.pageLocalYFraction(containerY, page)

    /** 单页模式拖选到边缘时翻页，并把焦点落到新页首/末字 */
    private fun trySelectPageTurnWhileSelecting(forward: Boolean) =
        selectionInteractor.trySelectPageTurnWhileSelecting(forward)

    internal fun ensurePdfSelectionEdgeScrollLoop() =
        selectionInteractor.ensurePdfSelectionEdgeScrollLoop()

    private fun runPdfSelectionEdgeScrollLoop() =
        selectionInteractor.runPdfSelectionEdgeScrollLoop()

    /**
     * 命中：pageIndex + charIndexOnPage。
     * [forSelection]=true 时：无字页也返回临时下标（并触发抽字），距离阈值放宽，保证可跨页拖选。
     */
    private fun hitTestChar(
        containerX: Float,
        containerY: Float,
        forSelection: Boolean = false,
    ): Pair<Int, Int>? = selectionInteractor.hitTestChar(containerX, containerY, forSelection)

    /**
     * 取页在 PDFBox 与 PdfRenderer 下的尺寸，字符坐标按 PDFBox 尺寸；
     * 映射到图时用「归一化 0~1」再乘到裁剪后的位图区域，避免两边尺寸不一致。
     */
    private fun pageLogicalSize(pageIndex: Int): Pair<Float, Float> =
        selectionInteractor.pageLogicalSize(pageIndex)

    /**
     * 单页 ImageView 矩阵：横屏按宽铺满（顶对齐；内容加高后由 ZoomableFrameLayout pan 看全页），
     * 竖屏 fitCenter。
     */
    private fun applySinglePageImageMatrix() =
            modeController.applySinglePageImageMatrix()

    /**
     * ImageView 本地坐标 -> PDF 页坐标（左上原点、Y 向下，与 [PdfTextExtractor.PdfChar] 一致）。
     */
    internal fun viewToPageCoords(
        iv: ImageView,
        localX: Float,
        localY: Float,
        pageIndex: Int,
    ): FloatArray? = selectionInteractor.viewToPageCoords(iv, localX, localY, pageIndex)

    private fun viewToPageCoordsOnSurface(
        surface: PdfPageSurface,
        localX: Float,
        localY: Float,
        pageIndex: Int,
    ): FloatArray? = selectionInteractor.viewToPageCoordsOnSurface(surface, localX, localY, pageIndex)

    /**
     * @param always true=拖选模式：总是返回最近字符（不因距离阈值失败，否则跨页拖到页边会 miss）
     */
    private fun nearestCharIndex(
        chars: List<PdfTextExtractor.PdfChar>,
        pageX: Float,
        pageY: Float,
        always: Boolean = false,
    ): Int? = selectionInteractor.nearestCharIndex(chars, pageX, pageY, always)

    private fun selectedText(): String = selectionInteractor.selectedText()

    internal fun refreshSelectionOverlay() = selectionInteractor.refreshSelectionOverlay()

    private fun fillTextSelectionContentRect(out: Rect): Boolean =
        selectionInteractor.fillTextSelectionContentRect(out)

    internal fun invalidateTextSelectionActionMode() =
        selectionInteractor.invalidateTextSelectionActionMode()

    private fun selectionHandlePoints(rects: List<RectF>): Pair<PointF, PointF>? =
        selectionInteractor.selectionHandlePoints(rects)

    internal fun refreshHighlightOverlay() = selectionInteractor.refreshHighlightOverlay()

    /** 将页内字符区间映射为容器坐标系矩形列表（合并同行） */
    private fun charRangeToContainerRects(
        page: Int,
        startIdx: Int,
        endIdx: Int,
    ): List<RectF> = selectionInteractor.charRangeToContainerRects(page, startIdx, endIdx)

    /**
     * PDF 页坐标矩形 -> [PdfPageSurface] 本地坐标。
     * 页宽高优先用字符自带的 PDFBox 尺寸（与抽字一致）。
     */
    private fun mapPdfCharRectToSurfaceView(
        surface: PdfPageSurface,
        pageIndex: Int,
        pageRect: RectF,
        sampleChars: List<PdfTextExtractor.PdfChar>,
    ): RectF = selectionInteractor.mapPdfCharRectToSurfaceView(surface, pageIndex, pageRect, sampleChars)

    private fun mergeLineRects(chars: List<PdfTextExtractor.PdfChar>): List<RectF> =
        selectionInteractor.mergeLineRects(chars)

    /**
     * zoomTarget 内容坐标 -> [pdfContainer] 子视图坐标（与选区/高亮 overlay 一致）。
     * 须计入 target 的 layout 位置（padding）与 scale/translation。
     */
    private fun contentToContainer(x: Float, y: Float): FloatArray =
        selectionInteractor.contentToContainer(x, y)

    /**
     * 页坐标矩形 -> 单页 ImageView 本地坐标。
     * **必须与 [applySinglePageImageMatrix] 一致**：横屏 fit-width 顶对齐，竖屏 fitCenter。
     */
    private fun pageRectToContent(
        iv: ImageView,
        pageIndex: Int,
        pageRect: RectF,
        contentOffsetX: Float,
        contentOffsetY: Float,
    ): RectF? = selectionInteractor.pageRectToContent(iv, pageIndex, pageRect, contentOffsetX, contentOffsetY)

    internal fun showTextActionMode() = selectionInteractor.showTextActionMode()

    internal fun toggleChrome() = chromeController.toggleChrome()

    /** 标题栏 ⋮：搜索、识别扫描版 PDF 文字 */
    private fun showPdfMoreMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.search)
        popup.menu.add(0, 2, 1, R.string.menu_pdf_ocr)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    if (fileKey.isNotBlank()) {
                        searchLauncher.launch(
                            BookSearchActivity.intentPdf(this, fileKey, displayTitle),
                        )
                    }
                    true
                }
                2 -> {
                    showPdfOcrDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    internal fun showChrome() = chromeController.showChrome()

    internal fun hideChrome() = chromeController.hideChrome()

    // ─── 外观 ─────────────────────────────────────────────

    internal fun applyNightUi() = chromeController.applyNightUi()

    /**
     * 缩放外观：缩小后页面两侧/外侧用纯黑；正常/放大时恢复日夜内容底色。
     */
    internal fun updatePdfZoomChrome() = chromeController.updatePdfZoomChrome()

    internal fun applyNightFilter(iv: ImageView) = chromeController.applyNightFilter(iv)

    internal fun applyNightFilterToVisibleSurfaces() {
        val rv = binding.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        for (pos in first..last) {
            val child = lm.findViewByPosition(pos) ?: continue
            val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: continue
            surface.setNightMode(night)
            surface.setPageBackground(if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    /** 是否打孔/刘海等挖孔屏 */
    internal fun hasDisplayCutout(): Boolean = chromeController.hasDisplayCutout()

    /**
     * 全屏开关；横屏始终全屏（见 [applyLandscapeFullscreenUi]）。
     */
    internal fun applyImmersive() = chromeController.applyImmersive()

    internal fun applyOrientationMode(
            mode: OrientationMode,
            allowSensor: Boolean = true,
            force: Boolean = false,
        ) = chromeController.applyOrientationMode(mode, allowSensor, force)

    // ─── 进度 / 状态栏 ────────────────────────────────────

    internal fun saveProgress(page: Int) {
        if (fileKey.isEmpty() || !allowProgressSave) return
        // 与视图状态一并写入（含缩放平移）
        val z = binding.pdfContainer
        val scrollY = if (pageMode == PdfPageMode.CONTINUOUS) {
            heightTableScrollY()
        } else {
            0
        }
        AppSettings.savePdfViewState(
            this,
            fileKey,
            AppSettings.PdfViewState(
                page = page,
                zoom = z.contentZoom,
                panX = z.getPanX(),
                panY = z.getPanY(),
                scrollY = scrollY,
            ),
        )
        BookshelfStore.updateProgress(this, fileKey, page)
        com.whj.reader.data.ReadingProgressStore.savePdf(this, fileKey, page, pageCount)
        if (displayTitle.isNotEmpty()) {
            AppSettings.setLastPdfBook(this, fileKey, displayTitle)
        }
    }

    /** 查询并持久化 PDF 文件大小，供书架列表直接读取 */
    internal fun cachePdfFileSize(uriStr: String) {
        if (uriStr.isBlank()) return
        if (com.whj.reader.data.ShelfFileMetaStore.getSizeBytes(this, uriStr) >= 0L) return
        val bytes = runCatching {
            val uri = Uri.parse(uriStr)
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else -1L
            } ?: contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
        if (bytes >= 0L) {
            com.whj.reader.data.ShelfFileMetaStore.setSizeBytes(this, uriStr, bytes)
        }
    }

    /**
     * 滚动中轻量进度：连续模式同样用**页高表**（滚动位置/总高度），
     * 避免长页内滚动时 % 卡住不动。
     */
    internal fun updateProgressLabelLight() {
        if (pageCount <= 0) {
            binding.tvProgress.text = "—"
            return
        }
        val visible = currentVisiblePage().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val p = visible + 1
        val pct = computeScrollProgressPercent()
        binding.tvProgress.text = getString(R.string.pdf_page_of_progress, p, pageCount, pct)
        if (pageMode == PdfPageMode.CONTINUOUS &&
            ::binding.isInitialized &&
            !binding.pdfFastScroll.isDragging
        ) {
            binding.pdfFastScroll.progress = pct / 100f
        }
    }

    internal fun updateProgressLabel() {
        if (pageCount <= 0) {
            binding.tvProgress.text = "—"
            return
        }
        val visible = currentVisiblePage()
        val p = visible + 1
        val pct = computeScrollProgressPercent()
        binding.tvProgress.text = getString(R.string.pdf_page_of_progress, p, pageCount, pct)
        if (pageMode == PdfPageMode.SINGLE) {
            pageIndex = visible
            updatePageBadge()
        }
        if (pageMode == PdfPageMode.CONTINUOUS &&
            ::binding.isInitialized &&
            !binding.pdfFastScroll.isDragging
        ) {
            binding.pdfFastScroll.progress = pct / 100f
        }
        if (allowProgressSave && !isScrollFlinging()) {
            prefetchNearbyText(visible)
        }
    }

    /**
     * 进度% = 视口底边在全书中的纵向位置 / 内容总高度（页高表）。
     */
    private fun computeScrollProgressPercent(): Float {
        if (pageCount <= 0) return 0f
        return (progressFromHeightTable() * 100f).coerceIn(0f, 100f)
    }

    private fun updateClock() {
        if (!::binding.isInitialized) return
        binding.tvClock.text = PdfStatusBarHelper.formatClock()
    }


    private fun updateBattery(intent: Intent) {
        if (!::binding.isInitialized) return
        PdfStatusBarHelper.formatBattery(intent)?.let { binding.tvBattery.text = it }
    }

    private fun registerBattery() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = registerReceiver(batteryReceiver, filter)
        batteryReceiverRegistered = true
        if (sticky != null) updateBattery(sticky)
    }

    private fun unregisterBattery() {
        if (batteryReceiverRegistered) {
            runCatching { unregisterReceiver(batteryReceiver) }
            batteryReceiverRegistered = false
        }
    }
}
