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
import android.util.LruCache
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
import com.whj.reader.data.PdfOcrConverter
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
import com.whj.reader.ocr.OcrTileHelper
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
import com.whj.reader.util.TtsVoicePicker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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
 * - 连续滚动（默认）：RecyclerView + 页间细黑线
 * - 单页模式：左右点按翻页
 * - 中部：与 TXT 相同 8 图标菜单
 */
class PdfReadingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        /** 连续模式 bitmap 页数上限（仅矮页整图） */
        private const val BITMAP_CACHE_PAGES = 5
        /** 当前页前后各保留几页 */
        private const val CACHE_KEEP_RADIUS = 2
        /** 渲染相对源页最大放大倍数 */
        private const val RENDER_MAX_SCALE = 2.2f
        /**
         * 矮页整图像素上限（ARGB≈4B/px → 约 24MB）。
         */
        private const val RENDER_MAX_PIXELS = 6_000_000
        /** 单边最大像素 */
        private const val RENDER_MAX_DIM = 8192
        /** 单页超长图整页渲染像素上限（按宽铺满时允许更高，避免宽度被压到屏宽以下） */
        private const val SINGLE_TALL_MAX_PIXELS = 20_000_000L
        private const val SINGLE_TALL_MAX_HEIGHT = 16_384
        /**
         * 连续模式逻辑显示高度超过此值 → 长图分块渲染。
         * max(2.2×屏高, 4000px)
         */
        private const val TALL_PAGE_MIN_FACTOR = 2.2f
        private const val TALL_PAGE_MIN_PX = 4000
        /** 长图单块高度 ≈ 屏高比例 */
        private const val TILE_HEIGHT_FACTOR = 0.85f
        /** 可见块上下各预渲染几块 */
        private const val TILE_PREFETCH = 3
        /**
         * tile 缓存总字节上限（按 bitmap.byteCount 计）。
         * 原先按「条数=24」且每块可 ~15MB → 易到 300MB+；现按 64MB 硬顶。
         */
        private const val TILE_CACHE_MAX_BYTES = 64 * 1024 * 1024
        /** 单块最大像素（宽×高），约 2.5MP → ARGB≈10MB / RGB_565≈5MB */
        private const val TILE_MAX_PIXELS = 2_500_000
    }

    /** 单页超长图换页后竖向 pan 落点 */
    private enum class TallPanSnap { PRESERVE, TOP, BOTTOM }

    private data class SinglePageRenderResult(
        val index: Int,
        val bitmap: Bitmap,
        val fitByWidth: Boolean,
    )

    /**
     * PDF 渲染调度：单工作线程 + 可取消优先队列。
     * - 滑动中也渲染可见页
     * - 离开可见邻域的任务在开工前丢弃
     * - 始终先做离当前页最近的任务（避免 FIFO 堆积导致卡顿）
     */
    private sealed class PdfRenderTask {
        abstract val page: Int
        /** full=0 优先于 tile=1（同距离时） */
        abstract val kind: Int
        @Volatile var cancelled: Boolean = false

        class Full(
            override val page: Int,
            val surface: PdfPageSurface,
            val targetWidth: Int,
            val bindGen: Long,
        ) : PdfRenderTask() {
            override val kind: Int = 0
        }

        class Tile(
            override val page: Int,
            val surface: PdfPageSurface,
            val tileIndex: Int,
            val tileTopPx: Int,
            val tileBottomPx: Int,
            val targetWidth: Int,
            val bindGen: Long,
        ) : PdfRenderTask() {
            override val kind: Int = 1
        }

        class PageSize(override val page: Int) : PdfRenderTask() {
            override val kind: Int = 2
        }
    }

    private val renderQueueLock = Object()
    private val renderQueue = ArrayList<PdfRenderTask>(48)
    @Volatile private var renderWorkerStop = false
    /** 当前可见页闭区间（含），供后台取消判定 */
    @Volatile private var visFirst = 0
    @Volatile private var visLast = 0
    private val tileExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pdf-tile-render").apply { isDaemon = true }
    }
    /**
     * 全局 tile 缓存：key = pageIndex<<16|tileIndex，size = byteCount。
     * 淘汰/替换时 recycle，避免 native 堆堆积到数百 MB。
     */
    /**
     * 仍被某个 PdfPageSurface 握着的 tile，淘汰时禁止 recycle（否则白屏）。
     */
    private val tilePinned = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Bitmap, Boolean>(),
    )

    private val tileCache = object : LruCache<Long, Bitmap>(TILE_CACHE_MAX_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int =
            if (value.isRecycled) 1 else value.byteCount.coerceAtLeast(1)

        override fun entryRemoved(
            evicted: Boolean,
            key: Long,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            if (oldValue === newValue || oldValue.isRecycled) return
            // 仍在屏幕上绘制 → 只摘 cache，不 recycle
            if (tilePinned.contains(oldValue)) return
            runCatching { oldValue.recycle() }
        }
    }

    /**
     * tile 缓存键：页 + 块 + 目标宽档。
     * 必须带宽度，否则横屏渲染的块在竖屏复用会被横向压扁。
     */
    private fun tileCacheKey(pageIndex: Int, tileIndex: Int, targetWidth: Int = 0): Long {
        val twBucket = (targetWidth.coerceAtLeast(0) / 16).coerceIn(0, 0x3FF)
        return (pageIndex.toLong() shl 26) or
            ((tileIndex.toLong() and 0x3FF) shl 16) or
            twBucket.toLong()
    }

    private fun pinTileBitmap(bmp: Bitmap?) {
        if (bmp != null && !bmp.isRecycled) tilePinned.add(bmp)
    }

    private fun unpinTileBitmap(bmp: Bitmap?) {
        if (bmp == null) return
        tilePinned.remove(bmp)
    }

    /** 把 tile 交给 Surface，并维护 pin，避免 cache 淘汰时 recycle 正在显示的图 */
    private fun deliverTile(
        surface: PdfPageSurface,
        tileIndex: Int,
        bmp: Bitmap,
        bindGen: Long,
    ) {
        if (bmp.isRecycled) return
        val old = surface.setTile(tileIndex, bmp, bindGen, owned = false)
        pinTileBitmap(bmp)
        if (old != null) unpinTileBitmap(old)
    }

    private lateinit var binding: ActivityPdfReadingBinding
    private lateinit var readMenu: PanelReadMenuBinding
    private lateinit var pdfSettings: PanelPdfSettingsBinding
    private lateinit var exportPanel: PanelPdfTtsExportBinding
    private var ttsExport: TtsExportHelper? = null
    private var exportProgressDlg: TtsExportProgressDialog? = null

    private var fileKey: String = ""
    private var displayTitle: String = ""
    private var pageCount: Int = 0
    /** 当前页（0-based）；后台渲染线程可读作锚点，故 volatile */
    @Volatile
    private var pageIndex: Int = 0
    /** 已处理的侧边点按 DOWN 时间（Activity 层再挡一层双发） */
    private var handledSideTapDownTime = -1L
    @Volatile private var pageTurnBusy = false
    /** 单页位图后台渲染中（避免主线程卡顿与翻页请求积压） */
    @Volatile private var singlePageRendering = false
    private var singlePageRenderGen = 0L
    private var pendingSinglePage: Pair<Int, TallPanSnap>? = null
    private var chromeVisible = false
    /** 合成语音面板 */
    private var exportPanelOpen = false
    /** 书内链接：page → links；后台加载 */
    private var pageLinks: Map<Int, List<PdfLinkIndex.Link>> = emptyMap()
    /** 目录大纲（打开 PDF 后预加载到内存） */
    private var outlineRoots: List<com.whj.reader.data.PdfOutlineLoader.Node>? = null
    private var outlineLoading = false
    /** 链接跳转历史（存离开前页码） */
    private val navBackStack = ArrayDeque<Int>()
    private val navForwardStack = ArrayDeque<Int>()
    private var allowProgressSave = false
    private var immersive = false
    /** 打开菜单的时间，避免布局变化触发 onScrolled 立刻关菜单 */
    private var chromeShownAtMs = 0L
    private var pageMode: PdfPageMode = PdfPageMode.CONTINUOUS
    private var night = false
    private val exportBitrateOptions = intArrayOf(32, 48, 64, 96, 128, 160, 192)
    /** 四边切边比例 L,T,R,B 各 0~0.30 */
    private var cropL = 0f
    private var cropT = 0f
    private var cropR = 0f
    private var cropB = 0f

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var singleBitmap: Bitmap? = null
    /** 单页超长图：分块渲染表面（与连续模式共用 tile 管线） */
    private var singlePageSurface: PdfPageSurface? = null
    private var singlePageUsesTiles = false

    private var pageAdapter: PdfPageAdapter? = null
    private lateinit var tts: TtsManager
    private lateinit var keepScreen: KeepScreenController
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
    private val reselectDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        applyReselectedUri(uri)
    }

    /** 打开失败：授予全盘权限后重试 */
    private val openFailPermissionLauncher = registerForActivityResult(
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

    private val ttsNotifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingTtsAfterNotif?.invoke()
        pendingTtsAfterNotif = null
    }
    private var pendingTtsAfterNotif: (() -> Unit)? = null
    private var ttsParagraphs: List<Paragraph> = emptyList()
    private var ttsBarOpen = false
    private var ttsExtracting = false
    private var extractJob: kotlinx.coroutines.Job? = null
    private var pendingAfterExtract: (() -> Unit)? = null
    /** PDF 页面 OCR 任务（可取消） */
    private var ocrJob: kotlinx.coroutines.Job? = null
    private var ocrEngine: TfliteOcrEngine? = null
    /** 长图条带 GPU det 哑火时按条回退用的 CPU 引擎 */
    private var ocrCpuFallback: TfliteOcrEngine? = null
    /** adb 写入 debug_pdf_ocr 后轮询触发（应用在前台时无需切后台） */
    private var ocrDebugPoll: Runnable? = null
    private val ocrDebugWatchdog = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || !::binding.isInitialized) return
            if (java.io.File(filesDir, "debug_pdf_ocr").exists()) {
                schedulePdfOcrDebugPoll()
            }
            binding.root.postDelayed(this, 1200L)
        }
    }
    private val sleepTimer = com.whj.reader.tts.TtsSleepTimer(
        onTick = { left ->
            if (!isFinishing && !isDestroyed) {
                binding.tvTtsSleepCountdown.text =
                    com.whj.reader.tts.TtsSleepTimer.formatCountdown(left)
            }
        },
        onFinished = { onSleepTimerFinished() },
    )

    /**
     * 按页缓存的原始字符（懒加载：仅 TTS/选字需要时提取当前页与邻页）。
     * key = 0-based pageIndex
     */
    private val rawPageCache = LinkedHashMap<Int, List<PdfTextExtractor.PdfChar>>()
    /** 0-based page → 带坐标字符（已按切边过滤） */
    private var pageChars: Map<Int, List<PdfTextExtractor.PdfChar>> = emptyMap()
    private var paraLinks: List<PdfTextExtractor.ParaLink> = emptyList()
    private var selPage = -1
    private var selStart = -1
    private var selEnd = -1
    /** 长按起点，拖动时与当前位置组成区间 */
    private var selAnchor = -1
    private var textActionMode: ActionMode? = null
    private var pdfDraggingHandle: TextSelectionHandles.Which? = null
    private var pdfSelDragX = 0f
    private var pdfSelDragY = 0f
    private var pdfSelectionDragActive = false
    private var pdfEdgeScrollPosted = false
    private val pdfEdgeScrollState = TextSelectionHandles.EdgeScrollState()
    /** TTS 当前句高亮：页内字符下标闭区间 */
    private var hlPage = -1
    private var hlStart = -1
    private var hlEnd = -1
    /** PdfRenderer 页尺寸缓存，用于与 PDFBox 坐标对齐 */
    private val rendererPageSize = HashMap<Int, Pair<Float, Float>>()

    /** PdfRenderer 同时只能 open 一页 */
    private val renderLock = Any()
    /**
     * 连续模式仅保留附近几页矮页整图。
     *
     * **禁止在 entryRemoved 里 recycle**：Surface 仍可能握着同一张 Bitmap 在画。
     * 一旦 recycle，onDraw 发现 isRecycled 直接 return → 中间页只剩白底+页码（见空白页 bug）。
     * 从 cache 摘掉后靠 Surface 解绑 + GC 回收即可。
     */
    private val bitmapCache = object : LruCache<Int, Bitmap>(BITMAP_CACHE_PAGES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
        // 故意不 recycle oldValue
    }

    @Volatile
    private var rvScrollState: Int = RecyclerView.SCROLL_STATE_IDLE
    /** 上次刷可见区时间（仅拖动时用；fling 中主线程零渲染调度） */
    private var lastTileRefreshMs: Long = 0L
    private val tileRefreshMinIntervalMs = 64L
    private var pendingContinuousTileRefresh: Runnable? = null
    private var lastPdfZoomLogMs: Long = 0L
    private var lastPdfOpenLogMs: Long = 0L
    /** 上次进度文字更新 */
    private var lastProgressUiMs: Long = 0L
    private val progressUiMinIntervalMs = 120L
    /** 未知页尺寸时的估算（优先用已见页的平均宽高比） */
    @Volatile private var estimatedPageAspect: Float = 1.414f // A4 竖向 H/W
    /**
     * 每页列表项高度（含页间分隔线），0=未知用估算。
     * 主流阅读器做法：用已知高度累计定位，避免 RV 变高估算导致拖动手柄跳动。
     */
    private var pageItemHeights: IntArray = IntArray(0)
    /** 连续模式页间间隔（px），与 item_pdf_page 的 pageDivider 一致 */
    private var pageDividerPx: Int = 5
    /** 已入队的 full / tile / size，用于去重与取消 */
    private val pendingFullPages = java.util.concurrent.ConcurrentHashMap<Int, PdfRenderTask.Full>()
    private val pendingTiles = java.util.concurrent.ConcurrentHashMap<Long, PdfRenderTask.Tile>()
    private val pendingPageSizes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>(),
    )
    /**
     * 滚动中用较低分辨率预渲（后台快、可贴图），停下再升清。
     * 贴图经 Choreographer 每帧限流，避免 onBind/回帖 同一帧塞多张大图卡 300ms。
     */
    private val PREVIEW_WIDTH_FACTOR = 0.5f
    private val MAX_BITMAP_ATTACH_PER_FRAME = 2
    private val pendingUiAttaches = java.util.ArrayDeque<UiAttach>()
    private var uiAttachFrameScheduled = false

    private data class UiAttach(
        val surface: PdfPageSurface,
        val page: Int,
        val bindGen: Long,
        val bmp: Bitmap,
        val isTile: Boolean,
        val tileIndex: Int = 0,
    )


    private var batteryReceiverRegistered = false
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
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

    private var lastRenderW = 0
    private var lastRenderH = 0

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

        tts = TtsManager(this)
        tts.listener = ttsListener
        tts.setSpeechRate(AppSettings.ttsRate(this))
        tts.setPitch(AppSettings.ttsPitch(this))
        // 引擎/发音人在 TtsManager 构造与 onInit 中从 prefs 恢复
        tts.init()

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
    private fun setupBottomChromeInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomChrome) { v, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav.bottom)
            insets
        }
        binding.bottomChrome.requestApplyInsets()
        // 排版面板贴底时补导航条高度，避免「切边」等末项被裁切
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            pdfSettings.root,
        ) { v, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav.bottom)
            insets
        }
        pdfSettings.root.requestApplyInsets()
    }

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
        schedulePdfOcrDebugPoll()
        binding.root.removeCallbacks(ocrDebugWatchdog)
        binding.root.postDelayed(ocrDebugWatchdog, 800L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            maybeRunPdfOcrDebugFromFile()
            schedulePdfOcrDebugPoll()
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
    private fun schedulePdfOcrDebugPoll() {
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        val flag = java.io.File(filesDir, "debug_pdf_ocr")
        if (!flag.exists()) {
            ocrDebugPoll?.let { binding.root.removeCallbacks(it) }
            ocrDebugPoll = null
            return
        }
        if (ocrDebugPoll != null) return
        val r = Runnable {
            ocrDebugPoll = null
            if (isFinishing || isDestroyed) return@Runnable
            maybeRunPdfOcrDebugFromFile()
            if (java.io.File(filesDir, "debug_pdf_ocr").exists()) {
                schedulePdfOcrDebugPoll()
            }
        }
        ocrDebugPoll = r
        binding.root.postDelayed(r, 400L)
    }

  private fun maybeRunPdfOcrDebugFromFile() {
        val flag = java.io.File(filesDir, "debug_pdf_ocr")
        if (!flag.exists()) return
        val raw = runCatching { flag.readText().trim() }.getOrDefault("")
        ocrDebugPoll?.let { binding.root.removeCallbacks(it) }
        ocrDebugPoll = null
        if (fileKey.isEmpty() || pageCount <= 0 || renderer == null) {
            runCatching { flag.delete() }
            logPdfOcr("debug skip: pdf not open raw='$raw'")
            Toasts.show(this, "adb OCR: 请先打开 PDF")
            return
        }
        if (ocrJob?.isActive == true) {
            logPdfOcr("debug skip: ocr busy raw='$raw'")
            Toasts.show(this, R.string.pdf_ocr_busy)
            schedulePdfOcrDebugPoll()
            return
        }
        val targetPage = parsePdfOcrDebugPage(raw)
        if (targetPage < 0) {
            runCatching { flag.delete() }
            logPdfOcr("debug ignore raw='$raw' (use: current | page=N | N)")
            return
        }
        runCatching { flag.delete() }
        val p = targetPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        logPdfOcr(
            "debug trigger page=${p + 1}/$pageCount visible=${currentVisiblePage() + 1} " +
                "raw='$raw' title=${displayTitle.take(40)}",
        )
        Toasts.show(this, getString(R.string.pdf_ocr_debug_start, p + 1))
        startPdfOcrJob(fromPage0 = p, toPage0 = p, skipDone = false)
    }

    /** @return 0-based 页码；无法解析返回 -1 */
    private fun parsePdfOcrDebugPage(raw: String): Int {
        val s = raw.trim()
        if (s.isEmpty() || s.equals("current", ignoreCase = true)) {
            return currentVisiblePage()
        }
        if (s.startsWith("page=", ignoreCase = true)) {
            val n = s.substringAfter('=').trim().toIntOrNull() ?: return -1
            return if (n >= 1) n - 1 else n
        }
        if (s.all { it.isDigit() }) {
            val n = s.toIntOrNull() ?: return -1
            return if (n >= 1) n - 1 else n
        }
        return -1
    }
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
            binding.root.removeCallbacks(ocrDebugWatchdog)
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
        ocrDebugPoll?.let {
            if (::binding.isInitialized) binding.root.removeCallbacks(it)
        }
        ocrDebugPoll = null
        pendingAfterExtract = null
        sleepTimer.cancel()
        dismissExportProgressDlg()
        ttsExport?.shutdown()
        ttsExport = null
        if (::tts.isInitialized) {
            tts.listener = null
            tts.shutdown()
        }
        runCatching { ocrEngine?.close() }
        ocrEngine = null
        runCatching { ocrCpuFallback?.close() }
        ocrCpuFallback = null
        closePdf()
        bitmapCache.evictAll()
        stopRenderWorker()
        tileExecutor.shutdownNow()
        tileCache.evictAll()
        super.onDestroy()
    }

    // ─── 渲染队列（可取消 + 近优先） ─────────────────────────

    private fun startRenderWorker() {
        renderWorkerStop = false
        tileExecutor.execute {
            while (!renderWorkerStop && !Thread.currentThread().isInterrupted) {
                val task = pollBestRenderTask() ?: continue
                if (task.cancelled || !isPageInRenderWindow(task.page)) {
                    onRenderTaskFinished(task)
                    continue
                }
                when (task) {
                    is PdfRenderTask.Full -> runFullPageTask(task)
                    is PdfRenderTask.Tile -> runTileTask(task)
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
                            pendingPageSizes.remove(task.page)
                        }
                    }
                }
                onRenderTaskFinished(task)
            }
        }
    }

    private fun stopRenderWorker() {
        renderWorkerStop = true
        synchronized(renderQueueLock) {
            for (t in renderQueue) t.cancelled = true
            renderQueue.clear()
            renderQueueLock.notifyAll()
        }
        pendingFullPages.clear()
        pendingTiles.clear()
        pendingPageSizes.clear()
    }

    /** 可见邻域：前后各多预渲 1～2 页，滑到时已有图；更远则取消 */
    private fun isPageInRenderWindow(page: Int): Boolean {
        val f = visFirst
        val l = visLast
        if (l < f) return kotlin.math.abs(page - pageIndex) <= CACHE_KEEP_RADIUS
        // 向前多 2、向后多 1：快速向下滑时先渲下面页
        return page in (f - 1)..(l + 2)
    }

    private fun offerRenderTask(task: PdfRenderTask) {
        if (renderWorkerStop) return
        synchronized(renderQueueLock) {
            renderQueue.add(task)
            renderQueueLock.notify()
        }
    }

    /** 取离当前页最近的未取消任务；无任务时 wait */
    private fun pollBestRenderTask(): PdfRenderTask? {
        synchronized(renderQueueLock) {
            while (!renderWorkerStop) {
                // 丢掉已取消 / 离屏，并清 pending
                val it = renderQueue.iterator()
                while (it.hasNext()) {
                    val t = it.next()
                    if (t.cancelled || !isPageInRenderWindow(t.page)) {
                        t.cancelled = true
                        it.remove()
                        onRenderTaskFinished(t)
                    }
                }
                if (renderQueue.isEmpty()) {
                    try {
                        renderQueueLock.wait(500)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                    continue
                }
                val anchor = pageIndex
                var bestIdx = 0
                var bestScore = Int.MAX_VALUE
                for (i in renderQueue.indices) {
                    val t = renderQueue[i]
                    val score = kotlin.math.abs(t.page - anchor) * 10 + t.kind
                    if (score < bestScore) {
                        bestScore = score
                        bestIdx = i
                    }
                }
                return renderQueue.removeAt(bestIdx)
            }
        }
        return null
    }

    private fun onRenderTaskFinished(task: PdfRenderTask) {
        when (task) {
            is PdfRenderTask.Full -> pendingFullPages.remove(task.page, task)
            is PdfRenderTask.Tile -> {
                val key = tileCacheKey(task.page, task.tileIndex, task.targetWidth)
                pendingTiles.remove(key, task)
            }
            is PdfRenderTask.PageSize -> pendingPageSizes.remove(task.page)
        }
    }

    /** 仅原子更新可见窗；取消在 worker poll 时做，避免主线程每帧抢锁 */
    private fun updateVisibleRangeFromRv() {
        val lm = binding.rvPdfPages.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        visFirst = first
        visLast = last.coerceAtLeast(first)
        if (first >= 0) pageIndex = first
    }

    /** 滚动中（拖动或惯性）用预览分辨率，便于边滑边出图 */
    private fun preferPreviewQuality(): Boolean =
        rvScrollState != RecyclerView.SCROLL_STATE_IDLE

    /**
     * 主线程贴图限流：每帧最多 [MAX_BITMAP_ATTACH_PER_FRAME] 张。
     * 解决快速滑时 onBind + 多任务同时 setFullBitmap 导致 UI 卡死约 0.3s。
     */
    private fun enqueueUiAttach(attach: UiAttach) {
        synchronized(pendingUiAttaches) {
            // 同 surface 只保留最新
            pendingUiAttaches.removeAll {
                it.surface === attach.surface && it.isTile == attach.isTile &&
                    (!it.isTile || it.tileIndex == attach.tileIndex)
            }
            pendingUiAttaches.addLast(attach)
        }
        scheduleUiAttachFlush()
    }

    private fun scheduleUiAttachFlush() {
        if (uiAttachFrameScheduled) return
        uiAttachFrameScheduled = true
        val choreographer = android.view.Choreographer.getInstance()
        choreographer.postFrameCallback {
            uiAttachFrameScheduled = false
            if (isFinishing || isDestroyed) {
                synchronized(pendingUiAttaches) { pendingUiAttaches.clear() }
                return@postFrameCallback
            }
            var n = 0
            while (n < MAX_BITMAP_ATTACH_PER_FRAME) {
                val a = synchronized(pendingUiAttaches) {
                    if (pendingUiAttaches.isEmpty()) null else pendingUiAttaches.removeFirst()
                } ?: break
                if (a.bmp.isRecycled) continue
                if (a.surface.pageIndex != a.page || a.surface.bindGeneration != a.bindGen) {
                    if (a.isTile) unpinTileBitmap(a.bmp)
                    continue
                }
                // surface 仍绑该页就贴；勿因可见窗短暂收窄丢弃（会导致第 N 页白屏）
                if (a.isTile) {
                    deliverTile(a.surface, a.tileIndex, a.bmp, a.bindGen)
                    a.surface.setNightMode(night)
                } else {
                    a.surface.setFullBitmap(a.bmp)
                }
                n++
            }
            val more = synchronized(pendingUiAttaches) { pendingUiAttaches.isNotEmpty() }
            if (more) scheduleUiAttachFlush()
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev != null &&
            (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                ev.actionMasked == android.view.MotionEvent.ACTION_UP)
        ) {
            if (::keepScreen.isInitialized) keepScreen.onUserActivity()
        }
        return super.dispatchTouchEvent(ev)
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

    private val ttsListener = object : TtsManager.Listener {
        override fun onStateChanged(snapshot: TtsManager.Snapshot) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateTtsUi(snapshot)
                if (::keepScreen.isInitialized) keepScreen.onTtsStateChanged()
                if (snapshot.state == TtsManager.State.IDLE) {
                    hlPage = -1
                    hlStart = -1
                    hlEnd = -1
                    binding.pdfSelectionOverlay.clearHighlight()
                }
            }
        }

        override fun onSentenceHighlight(paragraphIndex: Int, startOffset: Int, endOffset: Int) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyTtsSentenceHighlight(paragraphIndex, startOffset, endOffset)
                prefetchNextPdfPagesForTts(paragraphIndex)
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 初始化失败/进行中：仅 UI 状态，不 Toast
                if (isTtsInitNoise(message)) return@runOnUiThread
                Toasts.show(this@PdfReadingActivity, message)
            }
        }

        override fun onNeedMoreContent(lastParagraphIndex: Int): Boolean {
            val maxCached = rawPageCache.keys.maxOrNull() ?: return false
            val next = maxCached + 1
            if (next >= pageCount) return false
            // 异步提取下一页（及再下一页），完成后继续朗读
            ensurePagesExtracted(
                pages = listOf(next, next + 1),
                showToast = false,
                preserveTtsPosition = true,
            ) { added ->
                if (isFinishing || isDestroyed) return@ensurePagesExtracted
                if (added) {
                    tts.continueAfterMoreContent()
                } else {
                    tts.finishWaitingNoMore()
                }
            }
            return true
        }
    }

    // ─── 加载 ─────────────────────────────────────────────

    private fun loadPdf() {
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val titleExtra = intent.getStringExtra(EXTRA_TITLE)
        if (uriStr.isNullOrBlank()) {
            showOpenFailGuide(
                OpenFailGuide.Reason.UNAVAILABLE,
                detail = "no uri",
            )
            return
        }
        val uri = Uri.parse(uriStr)
        displayTitle = titleExtra?.ifBlank { null }
            ?: uri.lastPathSegment
            ?: getString(R.string.unnamed)
        fileKey = uriStr
        binding.tvReadTitle.text = displayTitle
        // 按本书加载切边（各 PDF 独立，不共通）
        val cropM = AppSettings.pdfCropMargins(this, fileKey)
        cropL = cropM[0]; cropT = cropM[1]; cropR = cropM[2]; cropB = cropM[3]
        updateCropSummary()
        // 遮罩 + 隐藏内容，防止恢复位置前先画出第 1 页
        setPdfContentHidden(true)
        binding.tvLoading.isVisible = true

        lifecycleScope.launch {
            val fdResult = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openFileDescriptor(uri, "r")
                        ?: error("无法打开 PDF")
                }
            }
            fdResult.onFailure { e ->
                binding.tvLoading.isVisible = false
                setPdfContentHidden(false)
                showOpenFailGuide(
                    reason = OpenFailGuide.reasonFrom(e),
                    detail = e.message,
                )
            }
            fdResult.onSuccess { fd ->
                try {
                    closePdfLocked()
                    pfd = fd
                    val r = PdfRenderer(fd)
                    renderer = r
                    pageCount = r.pageCount
                    if (pageCount <= 0) error("PDF 无页面")
                    initPageHeightTable(pageCount)

                    allowProgressSave = false
                    // 恢复页码 / 缩放 / 平移 / 滚动（切边已按 fileKey 加载）
                    val viewState = AppSettings.loadPdfViewState(this@PdfReadingActivity, fileKey)
                    val shelf = BookshelfStore.findBookByUri(this@PdfReadingActivity, fileKey)
                        ?.lastParagraph ?: 0
                    val progressPage = com.whj.reader.data.ReadingProgressStore
                        .get(this@PdfReadingActivity, fileKey)
                        ?.takeIf { it.kind == com.whj.reader.data.ReadingProgressStore.Kind.PDF }
                        ?.position ?: 0
                    pageIndex = maxOf(viewState.page, shelf, progressPage)
                        .coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    // 打开前预取 0..目标页 真尺寸，避免长图 PDF 用 A4 估算高度 → 滚动错位、上一页尾部空白
                    withContext(Dispatchers.IO) {
                        prefetchPageSizesUpTo(pageIndex)
                    }
                    logPdfOpen(
                        "prefetch done targetPage=$pageIndex scrollY=${viewState.scrollY} " +
                            "heights0..3=${pageItemHeights.take(4).joinToString()}",
                        force = true,
                    )
                    navBackStack.clear()
                    navForwardStack.clear()
                    pageLinks = emptyMap()
                    outlineRoots = null
                    outlineLoading = false
                    updateHistNavButtons()

                    // 仅更新已在书架上的书，不自动新增（绑定文件夹打开不进主书架）
                    BookshelfStore.updateIfExists(
                        this@PdfReadingActivity,
                        uri = fileKey,
                        displayName = BookFileType.stripBookExt(displayTitle),
                        lastParagraph = pageIndex,
                    )
                    com.whj.reader.data.ReadingProgressStore.savePdf(
                        this@PdfReadingActivity,
                        fileKey,
                        pageIndex,
                        pageCount,
                    )
                    // 缓存文件大小（书架列表用，避免反复 query）
                    cachePdfFileSize(fileKey)
                    // 不写 TXT 的 lastBook，只写 PDF 上次书
                    AppSettings.setLastPdfBook(this@PdfReadingActivity, fileKey, displayTitle)

                    // 勿在 post 前 setPageCount：会先绑定第 0 页造成闪一下
                    binding.pdfContainer.post {
                        applyPageModeUi()
                        restorePdfViewState(viewState.copy(page = pageIndex))
                        // 再等一帧：连续模式 scrollToPosition 需布局完成后才稳定
                        binding.pdfContainer.post {
                            if (isFinishing || isDestroyed) return@post
                            setPdfContentHidden(false)
                            binding.tvLoading.isVisible = false
                            allowProgressSave = true
                            updateProgressLabel()
                            updateFastScrollEnabled()
                            // 布局/滚动稳定后再刷一次长图条带，避免首帧空白
                            refreshVisiblePageTiles(forceRender = true)
                            binding.rvPdfPages.post {
                                refreshVisiblePageTiles(forceRender = true)
                                logPdfOpenVisible("afterOpenRefresh")
                            }
                        }
                    }
                    // 后台预取当前附近页尺寸，避免 onBind 主线程抢 renderLock
                    prefetchPageSizesAround(pageIndex)
                    // 打开后立即后台：PDFBox 进内存 + 当前页附近文字缓存，之后按需预取
                    startNearbyTextExtraction(uri)
                    // 后台加载书内链接
                    loadPdfLinksAsync(uri)
                } catch (e: Exception) {
                    binding.tvLoading.isVisible = false
                    setPdfContentHidden(false)
                    showOpenFailGuide(
                        reason = OpenFailGuide.reasonFrom(e),
                        detail = e.message,
                    )
                }
            }
        }
    }

    private fun showOpenFailGuide(reason: OpenFailGuide.Reason, detail: String?) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: displayTitle
        OpenFailGuide.show(
            activity = this,
            reason = reason,
            detail = detail,
            bookTitle = title,
            onGrantPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    openFailPermissionLauncher.launch(
                        StorageAccess.manageAllFilesIntent(this),
                    )
                } else {
                    loadPdf()
                }
            },
            onReselect = {
                reselectDocLauncher.launch(
                    arrayOf(
                        "application/pdf",
                        "application/octet-stream",
                        "text/plain",
                        "text/*",
                    ),
                )
            },
            onClose = { finish() },
        )
    }

    private fun applyReselectedUri(uri: Uri) {
        val oldUri = intent.getStringExtra(EXTRA_URI)
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    }
                }.getOrNull()
                    ?: uri.lastPathSegment
                    ?: intent.getStringExtra(EXTRA_TITLE)
                    ?: getString(R.string.unnamed)
            }
            // 重选为 TXT 时切到文本阅读
            val isPdf = BookFileType.isPdfUri(this@PdfReadingActivity, uri, name) ||
                BookFileType.isPdf(name)
            val stable = withContext(Dispatchers.IO) {
                OpenFailGuide.bindReselectedFile(
                    this@PdfReadingActivity,
                    oldUri = oldUri,
                    newUri = uri,
                    displayName = name,
                )
            }
            Toasts.show(this@PdfReadingActivity, R.string.open_failed_reselect_done)
            if (!isPdf) {
                startActivity(
                    Intent(this@PdfReadingActivity, ReadingActivity::class.java)
                        .putExtra(ReadingActivity.EXTRA_URI, stable)
                        .putExtra(ReadingActivity.EXTRA_TITLE, BookFileType.stripBookExt(name)),
                )
                finish()
                return@launch
            }
            intent.putExtra(EXTRA_URI, stable)
            intent.putExtra(EXTRA_TITLE, BookFileType.stripBookExt(name))
            displayTitle = BookFileType.stripBookExt(name)
            loadPdf()
        }
    }

    /** 打开恢复位置期间隐藏页内容（loading 遮罩盖住） */
    private fun setPdfContentHidden(hidden: Boolean) {
        if (!::binding.isInitialized) return
        val a = if (hidden) 0f else 1f
        binding.rvPdfPages.alpha = a
        binding.ivPdfPage.alpha = a
        singlePageSurface?.alpha = a
        binding.tvPageBadge.alpha = a
    }

    private fun closePdf() {
        try {
            closePdfLocked()
        } catch (_: Exception) {
        }
    }

    private fun closePdfLocked() {
        extractJob?.cancel()
        extractJob = null
        ttsExtracting = false
        pendingAfterExtract = null
        currentPage?.close()
        currentPage = null
        renderer?.close()
        renderer = null
        pfd?.close()
        pfd = null
        // 释放内存中的 PDFBox 文档与文字缓存
        PdfTextExtractor.closeSession()
        rawPageCache.clear()
        pageChars = emptyMap()
        paraLinks = emptyList()
        ttsParagraphs = emptyList()
        pageLinks = emptyMap()
        outlineRoots = null
        outlineLoading = false
        singleBitmap?.recycle()
        singleBitmap = null
        singlePageSurface?.let { s ->
            for (b in s.drainTiles()) unpinTileBitmap(b)
            s.clearContent()
            s.isVisible = false
        }
        singlePageUsesTiles = false
        tileCache.evictAll()
        tilePinned.clear()
    }

    /** 上次按页预取的锚点，避免滚动时重复排队 */
    private var lastTextPrefetchAnchor = -1

    /**
     * 打开后立即：PDFBox 进内存 + 提取当前页附近 1～2 页文字/区域并缓存。
     * 不挡首屏；后续翻页/TTS 再按需预取。
     */
    private fun startNearbyTextExtraction(uri: Uri) {
        extractJob?.cancel()
        pendingAfterExtract = null
        lastTextPrefetchAnchor = -1
        ttsExtracting = true
        val anchor = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val nearby = pagesNear(anchor, before = 1, after = 2)
        extractJob = lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val opened = withContext(Dispatchers.IO) {
                    runCatching {
                        PdfTextExtractor.openSession(this@PdfReadingActivity, uri)
                    }.getOrDefault(false)
                }
                if (!opened) {
                    ReaderLog.w(ReaderLog.Module.PDF, "nearby extract: openSession failed")
                    return@launch
                }
                // 会话就绪后立刻预加载目录到内存（不挡首屏）
                preloadOutlineAsync(uri)
                val extracted = withContext(Dispatchers.IO) {
                    runCatching {
                        PdfTextExtractor.extractPagesRaw(
                            this@PdfReadingActivity,
                            uri,
                            nearby,
                        )
                    }.getOrElse {
                        ReaderLog.e(ReaderLog.Module.PDF, "nearby extract failed", it)
                        emptyMap()
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                // 磁盘 OCR 缓存优先填入空页，再合并 PDF 原生文字
                mergeOcrCacheFromDisk()
                for (p in nearby) {
                    val pdfChars = extracted[p] ?: emptyList()
                    val existing = rawPageCache[p]
                    rawPageCache[p] = when {
                        pdfChars.isNotEmpty() -> pdfChars
                        !existing.isNullOrEmpty() -> existing
                        else -> emptyList()
                    }
                }
                lastTextPrefetchAnchor = anchor
                rebuildTextFromCache(preserveTtsPosition = false)
                val ms = System.currentTimeMillis() - t0
                ReaderLog.i(ReaderLog.Module.PDF,
                    "nearby text extract done pages=$nearby ${ms}ms",
                )
            } finally {
                ttsExtracting = false
                val queued = pendingAfterExtract
                pendingAfterExtract = null
                if (queued != null && !isFinishing && !isDestroyed) {
                    binding.pdfContainer.post {
                        if (!isFinishing && !isDestroyed) queued.invoke()
                    }
                }
            }
        }
    }

    /** [anchor] 前后各若干页，在合法页码内 */
    private fun pagesNear(anchor: Int, before: Int = 1, after: Int = 2): List<Int> {
        if (pageCount <= 0) return emptyList()
        val a = anchor.coerceIn(0, pageCount - 1)
        return ((a - before)..(a + after)).filter { it in 0 until pageCount }
    }

    /**
     * 按需预取：当前可见页附近尚未缓存的页（默认前 1 后 2）。
     * 静默后台，不弹 Toast。
     */
    private fun prefetchNearbyText(anchor: Int = currentVisiblePage()) {
        if (pageCount <= 0 || fileKey.isEmpty()) return
        val a = anchor.coerceIn(0, pageCount - 1)
        if (a == lastTextPrefetchAnchor) {
            // 同页也检查是否仍有空洞
            val holes = pagesNear(a, 1, 2).any { it !in rawPageCache }
            if (!holes) return
        } else {
            lastTextPrefetchAnchor = a
        }
        val need = pagesNear(a, before = 1, after = 2).filter { it !in rawPageCache }
        if (need.isEmpty()) return
        ensurePagesExtracted(
            pages = need,
            showToast = false,
            preserveTtsPosition = true,
            onReady = null,
        )
    }

    // ─── 模式 UI ──────────────────────────────────────────

    private fun applyPageModeUi() {
        when (pageMode) {
            PdfPageMode.CONTINUOUS -> {
                binding.rvPdfPages.isVisible = true
                binding.rvPdfPages.isEnabled = true
                binding.ivPdfPage.isVisible = false
                binding.tvPageBadge.isVisible = false
            }
            PdfPageMode.SINGLE -> {
                binding.rvPdfPages.isVisible = false
                binding.rvPdfPages.isEnabled = false
                binding.ivPdfPage.isVisible = !singlePageUsesTiles
                binding.ivPdfPage.isClickable = false
                binding.ivPdfPage.isFocusable = false
                singlePageSurface?.isVisible = singlePageUsesTiles
                binding.tvPageBadge.isVisible = true
                updatePageBadge()
            }
        }
        rebindZoomTarget()
        updateModeButtons()
        refreshSelectionOverlay()
        updateFastScrollEnabled()
    }

    /** 单页模式左上角页码（在 zoomTarget 外，天然不随内容缩放） */
    private fun updatePageBadge() {
        if (!::binding.isInitialized) return
        if (pageMode != PdfPageMode.SINGLE || pageCount <= 0) {
            binding.tvPageBadge.isVisible = false
            return
        }
        binding.tvPageBadge.isVisible = true
        binding.tvPageBadge.text = "${pageIndex + 1}"
    }

    /**
     * 连续模式页码在 RV item 内，会随内容一起 scale；
     * 对角标施加 1/zoom，使屏幕上字号基本固定。
     */
    private fun updatePageBadgeZoomCompensation() {
        if (!::binding.isInitialized) return
        if (pageMode != PdfPageMode.CONTINUOUS) return
        val z = binding.pdfContainer.contentZoom.coerceAtLeast(0.01f)
        val inv = 1f / z
        val rv = binding.rvPdfPages
        for (i in 0 until rv.childCount) {
            val badge = rv.getChildAt(i).findViewById<android.widget.TextView>(R.id.tvPageBadge)
                ?: continue
            badge.pivotX = 0f
            badge.pivotY = 0f
            badge.scaleX = inv
            badge.scaleY = inv
        }
    }

    private fun setPageMode(mode: PdfPageMode) {
        if (pageMode == mode) return
        val keep = currentVisiblePage()
        pageMode = mode
        AppSettings.setPdfPageMode(this, mode)
        clearTextSelection()
        invalidatePageBitmaps()
        applyPageModeUi()
        restorePosition(keep)
    }

    private fun invalidatePageBitmaps() {
        bitmapCache.evictAll()
        tileCache.evictAll()
        // 切边变化后按已知页尺寸重算列表项高度
        for (i in pageItemHeights.indices) {
            val sz = rendererPageSize[i] ?: continue
            recordPageItemHeight(i, sz.first, sz.second)
        }
        pageAdapter?.notifyDataSetChanged()
        if (pageMode == PdfPageMode.SINGLE && pageCount > 0) {
            showSinglePage(pageIndex)
        }
        refreshSelectionOverlay()
    }

    private fun setupPinchZoom() {
        val zoomLayout = binding.pdfContainer
        // 支持缩小到 25%（与 MOBI 连续图一致）
        zoomLayout.minZoom = 0.25f
        zoomLayout.maxZoom = 3.5f
        rebindZoomTarget()
        // 缩放保留在 transform 上，支持平移；不重绘 bitmap
        zoomLayout.onZoomChanged = {
            updatePdfZoomChrome()
            clearTextSelection()
            // TTS 高亮随缩放更新屏幕位置
            if (hlPage >= 0) refreshHighlightOverlay()
            refreshSelectionOverlay()
            // 页码角标反缩放，视觉大小不随 zoom 变
            updatePageBadgeZoomCompensation()
            // 缩小后列表视口变高，补拉可见/预取 tile
            if (pageMode == PdfPageMode.CONTINUOUS) {
                scheduleContinuousTileRefresh(
                    forceRender = true,
                    afterLayout = true,
                    reason = "onZoomChanged",
                )
            }
            // 缩放到文件记录（debounce 用 post，避免捏合过程狂写）
            if (allowProgressSave && fileKey.isNotEmpty()) {
                binding.pdfContainer.removeCallbacks(saveZoomRunnable)
                binding.pdfContainer.postDelayed(saveZoomRunnable, 280L)
            }
        }
        // 平移/缩放时：关菜单 + 刷新高亮位置；捏合过程中也要即时切换黑底
        zoomLayout.onTransformChanged = {
            updatePdfZoomChrome()
            if (chromeVisible &&
                (zoomLayout.isScaled() || zoomLayout.getPanX() != 0f || zoomLayout.getPanY() != 0f)
            ) {
                hideChrome()
            }
            if (hlPage >= 0) refreshHighlightOverlay()
            if (hasTextSelection()) refreshSelectionOverlay()
            updatePageBadgeZoomCompensation()
            if (pageMode == PdfPageMode.CONTINUOUS) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTileRefreshMs >= tileRefreshMinIntervalMs) {
                    lastTileRefreshMs = now
                    scheduleContinuousTileRefresh(
                        forceRender = true,
                        afterLayout = zoomLayout.isPinching(),
                        reason = "onTransformChanged",
                    )
                }
            } else if (pageMode == PdfPageMode.SINGLE) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastProgressUiMs >= progressUiMinIntervalMs) {
                    lastProgressUiMs = now
                    updateProgressLabelLight()
                }
                if (singlePageUsesTiles) {
                    if (now - lastTileRefreshMs >= tileRefreshMinIntervalMs) {
                        lastTileRefreshMs = now
                        refreshSinglePageTiles(forceRender = true)
                    }
                }
            }
        }
        // 侧边立即翻页（无双击等待）
        zoomLayout.onSideTapImmediate = side@{ zone, x, y ->
            val gestureDown = zoomLayout.sideTapGestureDownTime
            if (gestureDown == handledSideTapDownTime) {
                ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN,
                    "sideTap dup downTime=$gestureDown zone=$zone page=$pageIndex",
                )
                return@side
            }
            handledSideTapDownTime = gestureDown
            ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                "sideTap zone=$zone x=$x y=$y downTime=$gestureDown mode=$pageMode " +
                    "chrome=$chromeVisible sel=${hasTextSelection()} " +
                    "panel=${binding.settingsPanelContainer.isVisible} " +
                    "page=$pageIndex/${pageCount.coerceAtLeast(1)}",
            )
            if (binding.settingsPanelContainer.isVisible) {
                binding.settingsPanelContainer.isVisible = false
                return@side
            }
            if (hasTextSelection()) {
                clearTextSelection()
                return@side
            }
            // 菜单打开时只关菜单，不翻页
            if (chromeVisible) {
                hideChrome()
                return@side
            }
            pageTurn(forward = zone == 2, source = "sideTap")
        }
        // 左右滑翻页：左滑下一页，右滑上一页（单页 / 连续均可用）
        zoomLayout.onHorizontalSwipe = swipe@{ forward ->
            ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                "hSwipe forward=$forward mode=$pageMode chrome=$chromeVisible",
            )
            if (binding.settingsPanelContainer.isVisible) {
                binding.settingsPanelContainer.isVisible = false
                return@swipe
            }
            if (hasTextSelection()) {
                clearTextSelection()
                return@swipe
            }
            if (chromeVisible) {
                hideChrome()
                return@swipe
            }
            pageTurn(forward = forward, source = "hSwipe")
        }
        // 中部轻点：优先书内链接 → 菜单 / 关面板
        zoomLayout.onSingleTap = { x, y ->
            if (binding.settingsPanelContainer.isVisible) {
                binding.settingsPanelContainer.isVisible = false
            } else if (!tryHandlePdfLinkTap(x, y)) {
                handleTap(x, zoomLayout.width.toFloat().coerceAtLeast(1f))
            }
        }
        zoomLayout.onLongPress = { x, y -> beginTextSelection(x, y) }
        zoomLayout.onSelectionDrag = { x, y, ended ->
            pdfSelDragX = x
            pdfSelDragY = y
            extendTextSelection(x, y)
            if (!ended) {
                pdfSelectionDragActive = true
                autoScrollPdfWhileSelecting(y)
                ensurePdfSelectionEdgeScrollLoop()
            } else {
                pdfSelectionDragActive = false
                pdfEdgeScrollPosted = false
                pdfEdgeScrollState.reset()
                showTextActionMode()
            }
        }
        binding.pdfSelectionOverlay.onHandleDrag = { which, x, y, ended ->
            pdfDraggingHandle = if (ended) null else which
            pdfSelDragX = x
            pdfSelDragY = y
            adjustPdfSelectionHandle(which, x, y)
            if (!ended) {
                pdfSelectionDragActive = true
                autoScrollPdfWhileSelecting(y)
                ensurePdfSelectionEdgeScrollLoop()
            } else {
                pdfSelectionDragActive = false
                pdfEdgeScrollPosted = false
                pdfEdgeScrollState.reset()
                invalidateTextSelectionActionMode()
            }
        }
        // 连续模式缩放后竖滑 → 滚列表，从而可滑到下面页
        zoomLayout.onPanOverscroll = overscroll@{ _, overY ->
            if (pageMode != PdfPageMode.CONTINUOUS) return@overscroll
            if (chromeVisible) hideChrome()
            val z = zoomLayout.contentZoom.coerceAtLeast(0.01f)
            // 屏幕位移 overY；RV 被 scale 后 scrollBy(s) 视觉位移约 s*z
            // 手指上滑 overY<0 → 看下方内容 → scroll 正方向
            val dy = (-overY / z).toInt()
            if (dy != 0) {
                binding.rvPdfPages.scrollBy(0, dy)
                updateProgressLabel()
                if (hlPage >= 0) refreshHighlightOverlay()
                if (selPage >= 0) refreshSelectionOverlay()
            }
        }
        // 缩放后松手：列表 fling 惯性（与未缩放时一致）
        zoomLayout.onFlingScroll = fling@{ _, velocityY ->
            if (pageMode != PdfPageMode.CONTINUOUS) return@fling
            if (!zoomLayout.isZoomed()) return@fling
            val z = zoomLayout.contentZoom.coerceAtLeast(0.01f)
            // 屏幕速度 → 内容速度；手指上滑 vy<0 → fling 向下（正）
            val vy = (-velocityY / z).toInt()
            if (vy != 0) {
                binding.rvPdfPages.fling(0, vy)
            }
        }
        zoomLayout.onStopScroll = {
            binding.rvPdfPages.stopScroll()
        }
    }

    private fun rebindZoomTarget() {
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

    private fun needsTallSinglePageZoomHost(): Boolean {
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
    private fun cropForPage(pageIndex: Int): FloatArray {
        val base = floatArrayOf(cropL, cropT, cropR, cropB)
        if (fileKey.isEmpty() ||
            !AppSettings.pdfCropMirrorOddEven(this, fileKey) ||
            pageIndex % 2 == 0
        ) {
            return base
        }
        return floatArrayOf(cropR, cropT, cropL, cropB)
    }

    /**
     * 渲染时应用四边切边（视觉缩放由 ZoomableFrameLayout 负责，此处不再乘 zoom）。
     *
     * **必须等比缩放**：不可对宽/高分别 coerceIn，否则长图 PDF（每页高度上万 pt）
     * 会被纵向压扁。超限时统一降低 scale。
     */
    private fun renderPageBitmap(
        page: PdfRenderer.Page,
        targetWidth: Int,
        targetHeight: Int? = null,
        cropOverride: FloatArray? = null,
        pageIndexForMirror: Int = -1,
    ): Bitmap {
        val margins = cropOverride
            ?: if (pageIndexForMirror >= 0) cropForPage(pageIndexForMirror)
            else floatArrayOf(cropL, cropT, cropR, cropB)
        val cl = margins[0].coerceIn(0f, 0.30f)
        val ct = margins[1].coerceIn(0f, 0.30f)
        val cr = margins[2].coerceIn(0f, 0.30f)
        val cb = margins[3].coerceIn(0f, 0.30f)
        val srcW = page.width * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = page.height * (1f - ct - cb).coerceAtLeast(0.2f)
        val tw = targetWidth.coerceAtLeast(1)
        val maxEdge = pdfMaxRenderWidth()
        val cappedTw = tw.coerceAtMost(maxEdge).toFloat()
        val area = srcW * srcH
        // 先按适配目标算 scale，再按像素/边长预算统一下调（保持宽高比）
        var scale = if (targetHeight != null) {
            minOf(cappedTw / srcW, targetHeight / srcH, RENDER_MAX_SCALE)
        } else {
            // 按宽铺满（超长单页）：锁定屏宽，高度可很长
            minOf(cappedTw / srcW, RENDER_MAX_SCALE)
        }
        if (scale <= 0f || scale.isNaN() || scale.isInfinite()) scale = 0.05f
        if (targetHeight == null) {
            // 宽优先：勿用 RENDER_MAX_DIM 压高度把宽度连带缩小（长图翻页 pan 会错位）
            val minScale = cappedTw / srcW
            if (scale < minScale) scale = minScale
            var bhEst = srcH * scale
            if (bhEst > SINGLE_TALL_MAX_HEIGHT) {
                scale = SINGLE_TALL_MAX_HEIGHT / srcH
                bhEst = SINGLE_TALL_MAX_HEIGHT.toFloat()
            }
            if (area > 0f && area * scale * scale > SINGLE_TALL_MAX_PIXELS) {
                scale = sqrt(SINGLE_TALL_MAX_PIXELS.toFloat() / area)
            }
        } else {
            if (area > 0f && area * scale * scale > RENDER_MAX_PIXELS) {
                scale = sqrt(RENDER_MAX_PIXELS / area)
            }
            if (srcW * scale > RENDER_MAX_DIM) scale = RENDER_MAX_DIM / srcW
            if (srcH * scale > RENDER_MAX_DIM) scale = RENDER_MAX_DIM / srcH
        }
        scale = scale.coerceAtLeast(0.02f)

        val bw = (srcW * scale).toInt().coerceAtLeast(1)
        val bh = (srcH * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        // 页坐标 → 位图：先 scale 再 translate（与条带渲染一致）
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(-page.width * cl * scale, -page.height * ct * scale)
        page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }

    /**
     * 渲染裁切后内容的纵向条带 [srcY0, srcY1)（page points，已扣 crop 顶）。
     * 宽按 [targetWidth] 满分辨率；条带高度有限，无需整页像素预算。
     */
    private fun renderPageStripBitmap(
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
        val cl = margins[0].coerceIn(0f, 0.30f)
        val ct = margins[1].coerceIn(0f, 0.30f)
        val cr = margins[2].coerceIn(0f, 0.30f)
        val cb = margins[3].coerceIn(0f, 0.30f)
        val srcW = page.width * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = page.height * (1f - ct - cb).coerceAtLeast(0.2f)
        val y0 = srcY0.coerceIn(0f, srcH)
        val y1 = srcY1.coerceIn(y0 + 0.5f, srcH)
        val stripH = (y1 - y0).coerceAtLeast(0.5f)
        val maxTw = pdfMaxRenderWidth()
        val tw = targetWidth.coerceAtLeast(1).coerceAtMost(maxTw)
        var scale = (tw / srcW).coerceAtLeast(0.05f)
        if (srcW * stripH * scale * scale > TILE_MAX_PIXELS) {
            scale = sqrt(TILE_MAX_PIXELS / (srcW * stripH))
        }
        if (srcW * scale > RENDER_MAX_DIM) scale = RENDER_MAX_DIM / srcW
        if (stripH * scale > RENDER_MAX_DIM) scale = RENDER_MAX_DIM / stripH
        scale = scale.coerceAtLeast(0.05f)

        val bw = (srcW * scale).toInt().coerceAtLeast(1)
        val bh = (stripH * scale).toInt().coerceAtLeast(1)
        // PdfRenderer 要求 ARGB_8888；RGB_565 在多数机型上会渲成空白
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        // 页坐标 → 位图：先 scale 再 translate。
        // 旧写法 postTranslate 再 postScale 在 scale≠1 时把条带顶错位，
        // 越往下偏移越大，后半页 det 只看到空白 → 只识别出上部。
        val pageLeft = page.width * cl
        val pageTop = page.height * ct + y0
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(-pageLeft * scale, -pageTop * scale)
        page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }

    /**
     * 打开页尺寸（带缓存）。**可阻塞**：仅应在 pdf-tile-render / 已持锁路径调用。
     * 主线程 bind 请用 [pageSizeForBind]。
     */
    private fun ensurePageSize(pageIndex: Int): Pair<Float, Float> {
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
                    estimatedPageAspect = (sz.second / sz.first).coerceIn(0.3f, 8f)
                }
                recordPageItemHeight(pageIndex, sz.first, sz.second)
                sz
            }
        } catch (_: Exception) {
            1f to 1f
        }
    }

    // ─── 稳定页高表（消除变高 item 拖动手柄跳动） ───────────

    private fun initPageHeightTable(count: Int) {
        // 与 layout 中 pageDivider height=5px 一致
        pageDividerPx = 5
        pageItemHeights = IntArray(count.coerceAtLeast(0))
    }

    private fun contentWidthForHeight(): Int = pdfViewportWidth()

    /** PDF 排版/渲染宽度：优先列表实测，勿 coerce 到 720（小屏会算错页高与渲染分辨率） */
    private fun pdfViewportWidth(): Int {
        if (::binding.isInitialized) {
            binding.rvPdfPages.width.takeIf { it > 0 }?.let { return it }
            binding.pdfContainer.width.takeIf { it > 0 }?.let { return it }
        }
        return resources.displayMetrics.widthPixels.coerceAtLeast(1)
    }

    /** 单页渲染宽度上限（保持与视口一致，横屏允许更高） */
    private fun pdfMaxRenderWidth(): Int {
        val w = pdfViewportWidth()
        val h = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        return if (w > h) w.coerceAtMost(2560) else w.coerceAtMost(1600)
    }

    /**
     * 单页是否应按宽铺满：横屏一律；竖屏仅当页比视口更「瘦长」时（超长图）。
     * 否则 fitCenter 在矮屏上两侧留白，且 maxZoom 可能补不满屏宽。
     */
    private fun singlePageFitByWidth(dw: Float, dh: Float, vw: Float, vh: Float): Boolean {
        if (dw <= 1f || dh <= 1f || vw <= 1f || vh <= 1f) return vw > vh
        if (vw > vh) return true
        return dh / dw >= vh / vw
    }

    private fun updateSinglePageTallHostFlag() {
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

    private fun hideSinglePageSurface() {
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

    private fun refreshSinglePageTiles(forceRender: Boolean = true) {
        if (pageMode != PdfPageMode.SINGLE || !singlePageUsesTiles) return
        val surface = singlePageSurface ?: return
        if (surface.pageIndex < 0 || surface.tileCount <= 0) return
        val tw = surface.width.takeIf { it > 0 }
            ?: pdfViewportWidth().coerceAtMost(pdfMaxRenderWidth())
        val band = singlePageVisibleBand()
        hydrateTilesFromCache(surface, surface.pageIndex, tw)
        if (forceRender) {
            surface.ensureTilesForVisible(band.first, band.second, tw, TILE_PREFETCH)
        }
        if (!binding.pdfContainer.isPinching() &&
            abs(binding.pdfContainer.contentZoom - 1f) < 0.02f
        ) {
            for (b in surface.dropTilesOutside(band.first, band.second, TILE_PREFETCH)) {
                unpinTileBitmap(b)
            }
        }
    }

    private fun applySinglePageSurfacePanSnap(tallPanSnap: TallPanSnap) {
        val surface = singlePageSurface ?: return
        val host = binding.pdfContainer
        val vh = host.height.toFloat().coerceAtLeast(1f)
        host.allowTallZoomTarget = surface.logicalHeight > vh + 1f
        host.setTransform(host.contentZoom, host.getPanX(), host.getPanY(), notify = false)
        val (minY, maxY) = host.verticalPanLimits()
        val panY = when (tallPanSnap) {
            TallPanSnap.PRESERVE -> host.getPanY()
            TallPanSnap.TOP -> maxY
            TallPanSnap.BOTTOM -> minY
        }
        host.setTransform(host.contentZoom, host.getPanX(), panY, notify = false)
    }

    /**
     * 单页超长图：立即 bind + 只渲可见 tile（不阻塞主线程）。
     */
    private fun bindSinglePageTiled(
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
        bitmapCache.evictAll()
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

    private fun drainPendingSinglePageFlip() {
        val pending = pendingSinglePage
        pendingSinglePage = null
        if (pending != null && !isFinishing && !isDestroyed) {
            showSinglePage(pending.first, pending.second)
        }
    }

    /** 侧点翻屏前确保 pan 边界有效（仅边界塌陷时重算矩阵） */
    private fun ensureSinglePageTallPanReady() {
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
    private fun updatePdfZoomLimitsForSinglePage() {
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
    private fun recordPageItemHeight(pageIndex: Int, pageW: Float, pageH: Float) {
        if (pageIndex !in pageItemHeights.indices) return
        val tw = contentWidthForHeight()
        val margins = cropForPage(pageIndex)
        val displayH = logicalDisplayHeight(pageW, pageH, margins, tw)
        val withDiv = displayH + if (pageIndex < pageCount - 1) pageDividerPx else 0
        if (withDiv <= 0) return
        val oldH = pageItemHeights[pageIndex]
        if (oldH == withDiv) return
        pageItemHeights[pageIndex] = withDiv
        // 上方页变高/变矮后补偿滚动，避免视口落在上一页空白尾部
        if (oldH > 0 && pageMode == PdfPageMode.CONTINUOUS && ::binding.isInitialized) {
            val delta = withDiv - oldH
            if (delta != 0) {
                val rv = binding.rvPdfPages
                val lm = rv.layoutManager as? LinearLayoutManager
                val first = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                if (first != RecyclerView.NO_POSITION && pageIndex < first) {
                    rv.scrollBy(0, delta)
                    logPdfOpen(
                        "heightComp page=$pageIndex $oldH->$withDiv delta=$delta first=$first",
                        force = true,
                    )
                }
            }
        }
    }

    private fun averageKnownItemHeight(): Int {
        var sum = 0
        var n = 0
        for (h in pageItemHeights) {
            if (h > 0) {
                sum += h
                n++
            }
        }
        if (n > 0) return (sum / n).coerceAtLeast(1)
        val tw = contentWidthForHeight()
        return (tw * estimatedPageAspect).toInt().coerceAtLeast(200) + pageDividerPx
    }

    private fun itemHeightAt(index: Int): Int {
        if (index !in pageItemHeights.indices) return averageKnownItemHeight()
        val h = pageItemHeights[index]
        return if (h > 0) h else averageKnownItemHeight()
    }

    private fun totalContentHeightPx(): Long {
        if (pageCount <= 0) return 0L
        var sum = 0L
        for (i in 0 until pageCount) sum += itemHeightAt(i)
        return sum
    }

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
                val divider = if (page < pageCount - 1) pageDividerPx else 0
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
        var y = 0L
        for (i in 0 until first) y += itemHeightAt(i)
        if (child != null) {
            y += (-child.top).coerceAtLeast(0).toLong()
        }
        return y.toInt().coerceAtLeast(0)
    }

    /** 目标页顶在全书坐标中的 scrollY */
    private fun scrollOffsetForPageTop(page: Int): Int {
        var acc = 0
        val p = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        for (i in 0 until p) acc += itemHeightAt(i)
        return acc
    }

    /** scrollY 是否落在 [page] 页高范围内（用于识别旧版 RV scroll 与真页高错位） */
    private fun scrollOffsetFitsPage(page: Int, scrollY: Int): Boolean {
        if (scrollY <= 0) return true
        val p = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val top = scrollOffsetForPageTop(p)
        val bottom = top + itemHeightAt(p)
        return scrollY in top..bottom
    }

    /**
     * 按页高表跳到进度 [p]（0..1）。
     * scrollToPositionWithOffset(page, -offsetInPage)：把目标内容顶对齐视口。
     */
    private fun seekByHeightTable(p: Float) {
        if (pageCount <= 0) return
        val rv = binding.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val total = totalContentHeightPx()
        val extent = rv.height.toLong().coerceAtLeast(1L)
        val scrollable = (total - extent).coerceAtLeast(0L)
        val targetY = if (scrollable <= 0L) {
            0L
        } else {
            (scrollable.toDouble() * p.coerceIn(0f, 1f).toDouble()).toLong()
                .coerceIn(0L, scrollable)
        }
        var acc = 0L
        var page = 0
        while (page < pageCount - 1) {
            val h = itemHeightAt(page).toLong()
            if (acc + h > targetY) break
            acc += h
            page++
        }
        val offsetInPage = (targetY - acc).toInt().coerceAtLeast(0)
        rv.stopScroll()
        // offset 为 item 顶相对 RV 顶的位置；负值 = 页内下滚
        lm.scrollToPositionWithOffset(page, -offsetInPage)
        pageIndex = page
        // 保持较宽可见窗，避免 seek 时把邻页渲染任务误取消导致白页
        visFirst = (page - 1).coerceAtLeast(0)
        visLast = (page + 2).coerceAtMost((pageCount - 1).coerceAtLeast(0))
    }

    /**
     * 按绝对 scrollY（页高表坐标，与 [heightTableScrollY] 保存一致）定位。
     * scrollY=0 时落到 [fallbackPage] 页顶。
     */
    private fun seekByScrollOffset(scrollY: Int, fallbackPage: Int): Int {
        val rv = binding.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return fallbackPage
        val p = fallbackPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (scrollY <= 0) {
            lm.scrollToPositionWithOffset(p, 0)
            pageIndex = p
            return p
        }
        val total = totalContentHeightPx()
        val extent = rv.height.coerceAtLeast(1)
        val scrollable = (total - extent).coerceAtLeast(1)
        val targetY = scrollY.coerceIn(0, scrollable.toInt())
        var acc = 0L
        var targetPage = 0
        while (targetPage < pageCount - 1) {
            val h = itemHeightAt(targetPage).toLong()
            if (acc + h > targetY) break
            acc += h
            targetPage++
        }
        val offsetInPage = (targetY - acc).toInt().coerceAtLeast(0)
        rv.stopScroll()
        lm.scrollToPositionWithOffset(targetPage, -offsetInPage)
        pageIndex = targetPage
        visFirst = (targetPage - 1).coerceAtLeast(0)
        visLast = (targetPage + 2).coerceAtMost((pageCount - 1).coerceAtLeast(0))
        return targetPage
    }

    /**
     * 主线程 bind 用：缓存命中则真尺寸；否则立即返回估算尺寸并后台补真值。
     * **绝不在主线程抢 renderLock**，避免快速滑动时与渲染线程互锁卡顿。
     */
    private fun pageSizeForBind(pageIndex: Int): Pair<Float, Float> {
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
            estimatedPageAspect
        }
        val w = 1000f
        return w to (w * aspect)
    }

    private fun schedulePageSizeFetch(pageIndex: Int) {
        if (pageIndex !in 0 until pageCount) return
        if (rendererPageSize.containsKey(pageIndex)) return
        if (!pendingPageSizes.add(pageIndex)) return
        offerRenderTask(PdfRenderTask.PageSize(pageIndex))
    }

    /**
     * 真页尺寸到达后：校正已 bind 的 Surface 高度，并补渲。
     * 解决「先用估算高 bind → 图到了但高度仍错 → 整页压扁」。
     */
    private fun onPageSizeResolved(pageIndex: Int) {
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
            ensureTallPageTilesForItem(surface, displayH, tw, TILE_PREFETCH)
            return
        }
        // 矮页：有 cache 则按位图再校一次高并贴图；无 cache 重新入队
        val cached = bitmapCache.get(pageIndex)
        if (cached != null && !cached.isRecycled) {
            surface.setFullBitmap(cached)
        } else if (geometryChanged || surface.needsContent()) {
            enqueueFullPageRender(pageIndex, surface, tw, surface.bindGeneration)
        }
    }

    /** 打开后 / 翻页后：后台预取附近页尺寸 */
    private fun prefetchPageSizesAround(center: Int, radius: Int = 8) {
        if (pageCount <= 0) return
        val c = center.coerceIn(0, pageCount - 1)
        val pages = ((c - radius)..(c + radius)).filter { it in 0 until pageCount }
        for (p in pages) schedulePageSizeFetch(p)
    }

    /** 是否处于惯性滑动：此时不排队新渲染，空白即可 */
    private fun isScrollFlinging(): Boolean =
        rvScrollState == RecyclerView.SCROLL_STATE_SETTLING

    private fun tallThresholdPx(): Int {
        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(800)
        return max((sh * TALL_PAGE_MIN_FACTOR).toInt(), TALL_PAGE_MIN_PX)
    }

    /** 裁切后在 targetWidth 下的逻辑显示高度 */
    private fun logicalDisplayHeight(
        pageW: Float,
        pageH: Float,
        margins: FloatArray,
        targetWidth: Int,
    ): Int {
        val cl = margins[0].coerceIn(0f, 0.30f)
        val ct = margins[1].coerceIn(0f, 0.30f)
        val cr = margins[2].coerceIn(0f, 0.30f)
        val cb = margins[3].coerceIn(0f, 0.30f)
        val srcW = pageW * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = pageH * (1f - ct - cb).coerceAtLeast(0.2f)
        val tw = targetWidth.coerceAtLeast(1).toFloat()
        return max(1, (tw * srcH / srcW).toInt())
    }

    private fun isTallPage(pageW: Float, pageH: Float, margins: FloatArray, targetWidth: Int): Boolean {
        return logicalDisplayHeight(pageW, pageH, margins, targetWidth) > tallThresholdPx()
    }

    /** 跳到指定页；连续模式下将该页顶对齐到列表顶部 */
    private fun restorePosition(page: Int) {
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

    private val saveZoomRunnable = Runnable {
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
    private fun restorePdfViewState(state: AppSettings.PdfViewState) {
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
                if (hlPage >= 0) refreshHighlightOverlay()
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
                    if (hlPage >= 0) refreshHighlightOverlay()
                    // 停下：贴图 + 升清
                    refreshVisiblePageTiles(forceRender = true)
                } else if (newState == RecyclerView.SCROLL_STATE_SETTLING ||
                    newState == RecyclerView.SCROLL_STATE_DRAGGING
                ) {
                    syncFastScrollThumb(show = true)
                    // 进入滚动立刻补一轮可见区（含惯性）
                    refreshVisiblePageTiles(forceRender = true)
                    if (hlPage >= 0) refreshHighlightOverlay()
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

    private fun updateFastScrollEnabled() {
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
    private fun trimBitmapCacheAround(center: Int, keepRadius: Int = CACHE_KEEP_RADIUS) {
        val keys = bitmapCache.snapshot().keys.toList()
        for (k in keys) {
            if (kotlin.math.abs(k - center) > keepRadius) {
                bitmapCache.remove(k)
            }
        }
    }

    private fun tileHeightForDevice(): Int {
        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(800)
        return (sh * TILE_HEIGHT_FACTOR).toInt().coerceIn(800, 3200)
    }

    /** 打开前同步预取 [0..upTo] 页尺寸（仅 openPage，不渲图） */
    private fun prefetchPageSizesUpTo(upTo: Int) {
        if (pageCount <= 0) return
        val end = upTo.coerceIn(0, pageCount - 1)
        for (i in 0..end) {
            ensurePageSize(i)
        }
        prefetchPageSizesAround(end.coerceAtMost(pageCount - 1), radius = 2)
    }

    private fun logPdfOpen(msg: String, force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastPdfOpenLogMs < 100L) return
        lastPdfOpenLogMs = now
        ReaderLog.i(ReaderLog.Module.PDF_OPEN, msg)
    }

    /** 打开/恢复后记录可见页与高度表是否一致 */
    private fun logPdfOpenVisible(tag: String) {
        if (pageMode != PdfPageMode.CONTINUOUS || !::binding.isInitialized) return
        val rv = binding.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        val scrollY = rv.computeVerticalScrollOffset()
        val sb = StringBuilder("$tag scrollY=$scrollY first=$first last=$last rvH=${rv.height}")
        if (first != RecyclerView.NO_POSITION) {
            for (pos in first..last.coerceAtLeast(first)) {
                val child = lm.findViewByPosition(pos) ?: continue
                val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: continue
                val tableH = itemHeightAt(pos)
                sb.append(
                    " | p$pos top=${child.top} bot=${child.bottom} " +
                        "surfH=${surface.height} tableH=$tableH " +
                        "tiles=${surface.installedTileCount()}/${surface.tileCount} " +
                        "need=${surface.needsContent()}",
                )
            }
        }
        logPdfOpen(sb.toString(), force = true)
    }

    private fun logPdfZoom(msg: String, force: Boolean = false) {
        val zl = binding.pdfContainer
        if (!::binding.isInitialized) return
        val scaled = zl.isScaled() || zl.isPinching()
        if (!force && !scaled) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastPdfZoomLogMs < 80L) return
        lastPdfZoomLogMs = now
        ReaderLog.i(ReaderLog.Module.PDF_ZOOM, msg)
    }

    /** 绑定连续模式页表面：矮页整图；长页分块 + 屏外预取 */
    private fun bindPageSurface(index: Int, surface: PdfPageSurface, targetWidth: Int) {
        val r = renderer ?: return
        if (index !in 0 until r.pageCount) return
        val tw = targetWidth.coerceAtLeast(1)
            .coerceAtMost(pdfMaxRenderWidth())
        val curW = surface.width.takeIf { it > 0 }
        if (surface.pageIndex == index && curW == tw && !surface.needsContent()) {
            logPdfZoom(
                "bind skip page=$index mode=${surface.debugModeLabel()} " +
                    "tiles=${surface.installedTileCount()}/${surface.tileCount} " +
                    "h=${surface.height}",
                force = true,
            )
            surface.setNightMode(night)
            surface.setPageBackground(if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            return
        }
        logPdfZoom(
            "bind clear page=$index was=${surface.pageIndex} mode=${surface.debugModeLabel()} " +
                "tiles=${surface.installedTileCount()} tw=$tw curW=$curW",
            force = true,
        )
        val (pw, ph) = pageSizeForBind(index)
        val margins = cropForPage(index)
        val tall = isTallPage(pw, ph, margins, tw)
        val tileH = tileHeightForDevice()
        // 固定列表项高度表，供手柄定位
        recordPageItemHeight(index, pw, ph)
        for (b in surface.drainTiles()) unpinTileBitmap(b)
        surface.drainFullBitmap()
        surface.bind(
            pageIndex = index,
            pageW = pw,
            pageH = ph,
            cropL = margins[0],
            cropT = margins[1],
            cropR = margins[2],
            cropB = margins[3],
            targetWidth = tw,
            tileHeightPx = tileH,
            useTiles = tall,
        )
        surface.setNightMode(night)
        surface.setPageBackground(if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        surface.onNeedTile = { pageIdx, surf, tileIdx, topPx, bottomPx, width, bindGen ->
            enqueueTileRender(pageIdx, surf, tileIdx, topPx, bottomPx, width, bindGen)
        }

        if (tall) {
            hydrateTilesFromCache(surface, index, tw)
            val displayH = logicalDisplayHeight(pw, ph, margins, tw)
            val pref = if (preferPreviewQuality()) 1 else TILE_PREFETCH
            ensureTallPageTilesForItem(surface, displayH, tw, pref)
            return
        }

        val cached = bitmapCache.get(index)
        val gen = surface.bindGeneration
        if (cached != null && !cached.isRecycled) {
            // 绝不在 onBind 同步 setFullBitmap（会卡 RV 布局 ~300ms）→ 帧回调贴
            enqueueUiAttach(
                UiAttach(surface, index, gen, cached, isTile = false),
            )
            if (preferPreviewQuality() || isBitmapFullQuality(cached, tw)) {
                return
            }
            // 已是预览：继续排队升清
        }
        enqueueFullPageRender(index, surface, tw, gen)
    }

    /** 矮页整图入队（近优先、可取消）；滚动/惯性中都会渲并贴图 */
    private fun enqueueFullPageRender(
        pageIndex: Int,
        surface: PdfPageSurface,
        targetWidth: Int,
        bindGen: Long,
    ) {
        if (pageIndex !in 0 until pageCount) return
        val cached = bitmapCache.get(pageIndex)
        if (cached != null && !cached.isRecycled) {
            val needUpgrade = !preferPreviewQuality() && !isBitmapFullQuality(cached, targetWidth)
            if (!needUpgrade) {
                enqueueUiAttach(
                    UiAttach(surface, pageIndex, bindGen, cached, isTile = false),
                )
                return
            }
        }
        pendingFullPages[pageIndex]?.let { old ->
            if (!old.cancelled) old.cancelled = true
        }
        val task = PdfRenderTask.Full(pageIndex, surface, targetWidth, bindGen)
        pendingFullPages[pageIndex] = task
        offerRenderTask(task)
    }

    private fun runFullPageTask(task: PdfRenderTask.Full) {
        if (task.cancelled) return
        val pageIndex = task.page
        // 开工前已离窗则跳过；但一旦开渲，结果必进 cache，避免「渲完丢弃 → 白页」
        if (!isPageInRenderWindow(pageIndex) && !task.cancelled) {
            // 仍允许近邻页（宽窗）渲染；严格窗外才跳过
            if (kotlin.math.abs(pageIndex - this.pageIndex) > CACHE_KEEP_RADIUS + 2) return
        }
        val wantFull = !preferPreviewQuality()
        val hit = bitmapCache.get(pageIndex)
        if (hit != null && !hit.isRecycled) {
            if (wantFull && !isBitmapFullQuality(hit, task.targetWidth)) {
                // 缓存是预览，继续渲高清
            } else {
                postFullBitmap(task, hit)
                return
            }
        }
        if (task.cancelled) return
        val r = renderer ?: return
        if (pageIndex !in 0 until r.pageCount) return
        // 滚动中低分辨率：边滑边出图；停下用全宽
        val renderW = if (wantFull) {
            task.targetWidth
        } else {
            (task.targetWidth * PREVIEW_WIDTH_FACTOR).toInt().coerceIn(320, task.targetWidth)
        }
        val bmp = try {
            synchronized(renderLock) {
                if (task.cancelled) return
                currentPage?.close()
                currentPage = null
                val page = r.openPage(pageIndex)
                currentPage = page
                try {
                    renderPageBitmap(page, renderW, pageIndexForMirror = pageIndex)
                } finally {
                    page.close()
                    currentPage = null
                }
            }
        } catch (t: Throwable) {
            ReaderLog.e(ReaderLog.Module.PDF, "full page render p=$pageIndex", t)
            return
        }
        if (bmp.isRecycled) return
        // 无论是否仍可见，先入 cache，滑回来可立刻贴
        val old = bitmapCache.get(pageIndex)
        if (old == null || old.isRecycled || bmp.width >= (old.width * 0.9f)) {
            bitmapCache.put(pageIndex, bmp)
        }
        if (task.cancelled) return
        postFullBitmap(task, bmp)
    }

    private fun isBitmapFullQuality(bmp: Bitmap, targetWidth: Int): Boolean =
        bmp.width >= targetWidth * 0.82f

    private fun postFullBitmap(task: PdfRenderTask.Full, bmp: Bitmap) {
        if (bmp.isRecycled) return
        // 回主线程后走帧限流队列；bindGen 过期时由 flush 丢弃，cache 仍保留
        runOnUiThread {
            if (isFinishing || isDestroyed || bmp.isRecycled) return@runOnUiThread
            // surface 已换绑：仍尝试按 page 找当前 holder 贴图
            val surf = if (task.surface.pageIndex == task.page &&
                task.surface.bindGeneration == task.bindGen
            ) {
                task.surface
            } else {
                findSurfaceForPage(task.page)
            } ?: return@runOnUiThread
            enqueueUiAttach(
                UiAttach(
                    surface = surf,
                    page = task.page,
                    bindGen = surf.bindGeneration,
                    bmp = bmp,
                    isTile = false,
                ),
            )
        }
    }

    /** 当前列表中绑定到某页的 Surface（可能为 null） */
    private fun findSurfaceForPage(page: Int): PdfPageSurface? {
        if (pageMode == PdfPageMode.SINGLE) {
            val s = singlePageSurface
            return if (singlePageUsesTiles && s != null && s.pageIndex == page) s else null
        }
        if (pageMode != PdfPageMode.CONTINUOUS) return null
        val lm = binding.rvPdfPages.layoutManager as? LinearLayoutManager ?: return null
        val child = lm.findViewByPosition(page) ?: return null
        val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: return null
        return if (surface.pageIndex == page) surface else null
    }

    /** 把 tileCache 里属于该页、且与当前宽度匹配的块装回 Surface */
    private fun hydrateTilesFromCache(surface: PdfPageSurface, pageIndex: Int, targetWidth: Int) {
        val n = surface.tileCount
        if (n <= 0) return
        val gen = surface.bindGeneration
        for (i in 0 until n) {
            val bmp = tileCache.get(tileCacheKey(pageIndex, i, targetWidth)) ?: continue
            if (bmp.isRecycled) continue
            deliverTile(surface, i, bmp, gen)
        }
    }

    /** 长页 bind/尺寸校正后按 RV 可见带补 tile（layout 完成后再算） */
    private fun ensureTallPageTilesForItem(
        surface: PdfPageSurface,
        displayH: Int,
        tw: Int,
        prefetch: Int,
    ) {
        val apply = {
            val item = (surface.parent as? View) ?: surface
            val vh = binding.rvPdfPages.height.takeIf { it > 0 }
                ?: (resources.displayMetrics.heightPixels * 0.85f).toInt()
            val band = pageVisibleBandInRv(item, vh, displayH)
                ?: (0 to vh.coerceAtMost(displayH))
            surface.ensureTilesForVisible(band.first, band.second, tw, prefetch)
        }
        if (binding.rvPdfPages.height > 0 && surface.height > 0) {
            apply()
        } else {
            binding.rvPdfPages.post { apply() }
        }
    }

    /** RV 上该页 item 在页内坐标的可见竖带；不可见返回 null */
    private fun pageVisibleBandInRv(child: View, viewportH: Int, pageH: Int): Pair<Int, Int>? {
        val visibleH = (
            child.bottom.coerceAtMost(viewportH) - child.top.coerceAtLeast(0)
            ).coerceAtLeast(0)
        if (visibleH <= 0) return null
        val visTop = if (child.top < 0) (-child.top).coerceIn(0, pageH) else 0
        val visBottom = (visTop + visibleH).coerceIn(visTop + 1, pageH)
        return visTop to visBottom
    }

    /** 缩放/列表高度变化后补渲可见 tile（等 layout 完成再算可见带） */
    private fun scheduleContinuousTileRefresh(
        forceRender: Boolean = true,
        afterLayout: Boolean = false,
        reason: String = "",
    ) {
        if (pageMode != PdfPageMode.CONTINUOUS) return
        logPdfZoom(
            "scheduleRefresh reason=$reason force=$forceRender afterLayout=$afterLayout " +
                "z=${binding.pdfContainer.contentZoom} pinching=${binding.pdfContainer.isPinching()}",
            force = reason.isNotEmpty(),
        )
        pendingContinuousTileRefresh?.let { binding.rvPdfPages.removeCallbacks(it) }
        val r = Runnable {
            pendingContinuousTileRefresh = null
            if (isFinishing || isDestroyed) return@Runnable
            val run = Runnable { refreshVisiblePageTiles(forceRender = forceRender) }
            if (afterLayout) {
                binding.rvPdfPages.post { binding.rvPdfPages.post(run) }
            } else {
                binding.rvPdfPages.post(run)
            }
        }
        pendingContinuousTileRefresh = r
        binding.rvPdfPages.post(r)
    }

    /** 遍历可见 item：贴缓存 + 排队缺失（拖动/惯性/停下都调用） */
    private fun refreshVisiblePageTiles(forceRender: Boolean = true) {
        if (pageMode != PdfPageMode.CONTINUOUS) return
        val rv = binding.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        visFirst = first
        visLast = last.coerceAtLeast(first)
        val viewportH = rv.height.coerceAtLeast(1)
        val scrolling = preferPreviewQuality()
        val prefetch = if (scrolling) 1 else TILE_PREFETCH
        // 缩放态不摘 tile，避免捏合/松手布局突变时误删可见块
        val allowDropTiles = !binding.pdfContainer.isPinching() &&
            abs(binding.pdfContainer.contentZoom - 1f) < 0.02f
        val z = binding.pdfContainer.contentZoom
        val rvLpH = rv.layoutParams?.height ?: -1
        // 可见 + 下方 1 页（惯性下滑时提前渲）
        val end = (last + if (scrolling) 1 else 0).coerceAtMost((pageCount - 1).coerceAtLeast(0))
        logPdfZoom(
            "refresh first=$first last=$last end=$end viewportH=$viewportH rvLpH=$rvLpH " +
                "z=$z pinching=${binding.pdfContainer.isPinching()} drop=$allowDropTiles scroll=$scrolling",
        )
        for (pos in first..end) {
            val child = lm.findViewByPosition(pos)
            val surface = if (child != null) {
                child.findViewById<PdfPageSurface>(R.id.ivPage)
            } else {
                null
            }
            // 已 bind 的可见页
            if (surface != null && surface.pageIndex == pos) {
                val tw = surface.width.takeIf { it > 0 }
                    ?: rv.width.takeIf { it > 0 }
                    ?: resources.displayMetrics.widthPixels
                // 白页恢复：有 cache 立刻贴；无 cache 强制再入队（防渲染被取消后卡住）
                if (surface.needsContent()) {
                    logPdfZoom(
                        "page=$pos NEED childTop=${child?.top} childBot=${child?.bottom} " +
                            "mode=${surface.debugModeLabel()} tiles=${surface.installedTileCount()}/" +
                            "${surface.tileCount}",
                    )
                    val cached = bitmapCache.get(pos)
                    if (cached != null && !cached.isRecycled &&
                        !surface.isTileMode && surface.tileCount <= 0
                    ) {
                        enqueueUiAttach(
                            UiAttach(surface, pos, surface.bindGeneration, cached, false),
                        )
                        if (forceRender && !scrolling && !isBitmapFullQuality(cached, tw)) {
                            enqueueFullPageRender(pos, surface, tw, surface.bindGeneration)
                        }
                    } else if (surface.isTileMode || surface.tileCount > 0) {
                        hydrateTilesFromCache(surface, pos, tw)
                        val pageH = surface.height.coerceAtLeast(surface.logicalHeight).coerceAtLeast(1)
                        val band = pageVisibleBandInRv(child!!, viewportH, pageH) ?: continue
                        if (forceRender) {
                            surface.ensureTilesForVisible(band.first, band.second, tw, prefetch)
                        }
                    } else if (forceRender) {
                        enqueueFullPageRender(pos, surface, tw, surface.bindGeneration)
                    }
                    continue
                }
                if (surface.isFullMode) {
                    val c = child!!
                    logPdfZoom(
                        "page=$pos FULL childTop=${c.top} childBot=${c.bottom} " +
                            "h=${surface.height} need=${surface.needsContent()}",
                    )
                    if (forceRender && !scrolling) {
                        val cached = bitmapCache.get(pos)
                        if (cached != null && !cached.isRecycled &&
                            !isBitmapFullQuality(cached, tw)
                        ) {
                            enqueueFullPageRender(pos, surface, tw, surface.bindGeneration)
                        }
                    }
                    continue
                }
                if (!surface.isTileMode && surface.tileCount <= 0) continue
                val pageH = surface.height.coerceAtLeast(surface.logicalHeight).coerceAtLeast(1)
                val band = pageVisibleBandInRv(child!!, viewportH, pageH) ?: continue
                hydrateTilesFromCache(surface, pos, tw)
                if (forceRender) {
                    surface.ensureTilesForVisible(band.first, band.second, tw, prefetch)
                }
                var dropped = 0
                if (allowDropTiles) {
                    for (b in surface.dropTilesOutside(band.first, band.second, prefetch)) {
                        unpinTileBitmap(b)
                        dropped++
                    }
                }
                logPdfZoom(
                    "page=$pos childTop=${child.top} childBot=${child.bottom} " +
                        "band=${band.first}..${band.second} pageH=$pageH " +
                        "mode=${surface.debugModeLabel()} tiles=${surface.installedTileCount()}/" +
                        "${surface.tileCount} need=${surface.needsContent()} dropped=$dropped",
                )
            } else if (forceRender && pos > last) {
                // 下方预取页尚未 bind：只确保尺寸入队，等 bind 再渲
                schedulePageSizeFetch(pos)
            }
        }
    }

    private fun enqueueTileRender(
        pageIndex: Int,
        surface: PdfPageSurface,
        tileIndex: Int,
        tileTopPx: Int,
        tileBottomPx: Int,
        targetWidth: Int,
        bindGen: Long,
    ) {
        val cacheKey = tileCacheKey(pageIndex, tileIndex, targetWidth)
        val cached = tileCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            if (surface.pageIndex == pageIndex && surface.bindGeneration == bindGen) {
                deliverTile(surface, tileIndex, cached, bindGen)
            }
            return
        }
        if (!isPageInRenderWindow(pageIndex)) return
        pendingTiles[cacheKey]?.let { it.cancelled = true }
        val task = PdfRenderTask.Tile(
            page = pageIndex,
            surface = surface,
            tileIndex = tileIndex,
            tileTopPx = tileTopPx,
            tileBottomPx = tileBottomPx,
            targetWidth = targetWidth,
            bindGen = bindGen,
        )
        pendingTiles[cacheKey] = task
        offerRenderTask(task)
    }

    private fun runTileTask(task: PdfRenderTask.Tile) {
        if (task.cancelled) return
        val cacheKey = tileCacheKey(task.page, task.tileIndex, task.targetWidth)
        val hit = tileCache.get(cacheKey)
        if (hit != null && !hit.isRecycled) {
            postTile(task, hit)
            return
        }
        if (task.cancelled) return
        if (!isPageInRenderWindow(task.page) &&
            kotlin.math.abs(task.page - pageIndex) > CACHE_KEEP_RADIUS + 2
        ) {
            return
        }
        val r = renderer ?: return
        if (task.page !in 0 until r.pageCount) return
        val (pw, ph) = try {
            ensurePageSize(task.page)
        } catch (t: Throwable) {
            ReaderLog.e(ReaderLog.Module.PDF, "tile ensurePageSize p=${task.page}", t)
            return
        }
        if (task.cancelled) return
        val margins = cropForPage(task.page)
        val displayH = logicalDisplayHeight(pw, ph, margins, task.targetWidth).toFloat().coerceAtLeast(1f)
        val srcH = ph * (1f - margins[1] - margins[3]).coerceAtLeast(0.2f)
        val srcY0 = (task.tileTopPx / displayH) * srcH
        val srcY1 = (task.tileBottomPx / displayH) * srcH
        val bmp = try {
            synchronized(renderLock) {
                if (task.cancelled) return
                currentPage?.close()
                currentPage = null
                val page = r.openPage(task.page)
                currentPage = page
                try {
                    renderPageStripBitmap(
                        page,
                        task.targetWidth,
                        srcY0,
                        srcY1,
                        pageIndexForMirror = task.page,
                    )
                } finally {
                    page.close()
                    currentPage = null
                }
            }
        } catch (t: Throwable) {
            ReaderLog.e(ReaderLog.Module.PDF,
                "tile render p=${task.page} t=${task.tileIndex}",
                t,
            )
            return
        }
        if (bmp.isRecycled) return
        // 先入 cache，再决定是否贴到当前 surface
        tileCache.put(cacheKey, bmp)
        if (task.cancelled) return
        pinTileBitmap(bmp)
        postTile(task, bmp)
    }

    private fun postTile(task: PdfRenderTask.Tile, bmp: Bitmap) {
        if (bmp.isRecycled) {
            unpinTileBitmap(bmp)
            return
        }
        runOnUiThread {
            if (isFinishing || isDestroyed || bmp.isRecycled) {
                unpinTileBitmap(bmp)
                return@runOnUiThread
            }
            val surf = if (task.surface.pageIndex == task.page &&
                task.surface.bindGeneration == task.bindGen
            ) {
                task.surface
            } else {
                findSurfaceForPage(task.page)
            }
            if (surf == null) {
                // 页不在屏上：cache 已有，unpin 显示引用（cache 仍持有）
                unpinTileBitmap(bmp)
                return@runOnUiThread
            }
            enqueueUiAttach(
                UiAttach(
                    surface = surf,
                    page = task.page,
                    bindGen = surf.bindGeneration,
                    bmp = bmp,
                    isTile = true,
                    tileIndex = task.tileIndex,
                ),
            )
        }
    }

    private fun showSinglePage(index: Int, tallPanSnap: TallPanSnap = TallPanSnap.PRESERVE) {
        val r = renderer ?: return
        if (r.pageCount <= 0) return
        val i = index.coerceIn(0, r.pageCount - 1)

        if (singlePageRendering) {
            pendingSinglePage = i to tallPanSnap
            ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
                "showSinglePage coalesce page=$i snap=$tallPanSnap",
            )
            return
        }

        val container = binding.pdfContainer
        if (container.width <= 0 || container.height <= 0) {
            ReaderLog.w(ReaderLog.Module.PDF_ORIENT,
                "showSinglePage defer page=$i container=${container.width}x${container.height}",
            )
            container.post { if (!isFinishing && !isDestroyed) showSinglePage(i, tallPanSnap) }
            return
        }

        val maxW = container.width.coerceAtLeast(1)
        val maxH = container.height.coerceAtLeast(1)
        lastRenderW = maxW
        lastRenderH = maxH
        val tw = maxW.coerceAtMost(pdfMaxRenderWidth())
        schedulePageSizeFetch(i)
        val (pw, ph) = pageSizeForBind(i)
        val margins = cropForPage(i)
        if (isTallPage(pw, ph, margins, tw)) {
            bindSinglePageTiled(i, tallPanSnap, tw)
            prefetchPageSizesAround(i, radius = 3)
            return
        }

        hideSinglePageSurface()
        rebindZoomTarget()
        singlePageRendering = true
        val gen = ++singlePageRenderGen

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    synchronized(renderLock) {
                        currentPage?.close()
                        currentPage = null
                        val page = r.openPage(i)
                        val crop = cropForPage(i)
                        val cl = crop[0].coerceIn(0f, 0.30f)
                        val ct = crop[1].coerceIn(0f, 0.30f)
                        val cr = crop[2].coerceIn(0f, 0.30f)
                        val cb = crop[3].coerceIn(0f, 0.30f)
                        val dw = page.width * (1f - cl - cr).coerceAtLeast(0.2f)
                        val dh = page.height * (1f - ct - cb).coerceAtLeast(0.2f)
                        val fitByWidth = singlePageFitByWidth(dw, dh, maxW.toFloat(), maxH.toFloat())
                        val bmp = renderPageBitmap(
                            page,
                            maxW,
                            if (fitByWidth) null else maxH,
                            pageIndexForMirror = i,
                        )
                        page.close()
                        currentPage = null
                        SinglePageRenderResult(i, bmp, fitByWidth)
                    }
                }
            }
            if (isFinishing || isDestroyed || gen != singlePageRenderGen) {
                result.getOrNull()?.bitmap?.let { bmp ->
                    if (!bmp.isRecycled) runCatching { bmp.recycle() }
                }
                finishSinglePageRender(gen)
                return@launch
            }
            result.onSuccess { applySinglePageBitmap(it, tallPanSnap, gen) }
                .onFailure { e ->
                    Toasts.show(
                        this@PdfReadingActivity,
                        getString(R.string.load_failed, e.message ?: ""),
                    )
                    finishSinglePageRender(gen)
                }
        }

        bitmapCache.evictAll()
        if (chromeVisible) updatePdfBookmarkButton()
    }

    private fun applySinglePageBitmap(
        result: SinglePageRenderResult,
        tallPanSnap: TallPanSnap,
        gen: Long,
    ) {
        if (gen != singlePageRenderGen || isFinishing || isDestroyed) {
            if (!result.bitmap.isRecycled) runCatching { result.bitmap.recycle() }
            finishSinglePageRender(gen)
            return
        }
        val i = result.index
        pageIndex = i
        hideSinglePageSurface()
        binding.ivPdfPage.isVisible = true
        rebindZoomTarget()
        val old = singleBitmap
        singleBitmap = result.bitmap
        binding.ivPdfPage.layoutParams?.let { lp ->
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.ivPdfPage.layoutParams = lp
        }
        binding.ivPdfPage.setImageBitmap(result.bitmap)
        applyNightFilter(binding.ivPdfPage)
        val container = binding.pdfContainer
        ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
            "showSinglePage page=$i container=${lastRenderW}x${lastRenderH} " +
                "fitByWidth=${result.fitByWidth} bmp=${result.bitmap.width}x${result.bitmap.height} " +
                "zoom=${container.contentZoom} pan=(${container.getPanX()},${container.getPanY()})",
        )
        binding.ivPdfPage.post {
            if (gen != singlePageRenderGen || isFinishing || isDestroyed) return@post
            applySinglePageImageMatrix()
            binding.pdfContainer.post {
                if (gen != singlePageRenderGen || isFinishing || isDestroyed) return@post
                val host = binding.pdfContainer
                val (minY, maxY) = host.verticalPanLimits()
                val panY = when (tallPanSnap) {
                    TallPanSnap.PRESERVE -> host.getPanY()
                    TallPanSnap.TOP -> maxY
                    TallPanSnap.BOTTOM -> minY
                }
                host.setTransform(host.contentZoom, host.getPanX(), panY, notify = false)
                updatePageBadge()
                updateProgressLabel()
                if (allowProgressSave) saveProgress(pageIndex)
                finishSinglePageRender(gen)
            }
        }
        if (old != null && old !== result.bitmap) {
            binding.ivPdfPage.post {
                if (old !== singleBitmap && !old.isRecycled) {
                    runCatching { old.recycle() }
                }
            }
        }
    }

    private fun finishSinglePageRender(completedGen: Long) {
        if (completedGen != singlePageRenderGen) return
        singlePageRendering = false
        pageTurnBusy = false
        drainPendingSinglePageFlip()
    }

    /** 单页渲染中合并后续翻页请求，避免卡顿后连跳多页 */
    private fun tryCoalesceSinglePageFlip(forward: Boolean): Boolean {
        if (pageMode != PdfPageMode.SINGLE || pageCount <= 0) return false
        val base = pendingSinglePage?.first ?: pageIndex
        val next = if (forward) base + 1 else base - 1
        if (next !in 0 until pageCount) return false
        val snap = if (binding.pdfContainer.allowTallZoomTarget) {
            if (forward) TallPanSnap.TOP else TallPanSnap.BOTTOM
        } else {
            TallPanSnap.TOP
        }
        pendingSinglePage = next to snap
        return true
    }

    /**
     * 音量键翻页：减=向下/下一页，加=向上/上一页（默认开启）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (AppSettings.volumeKeyPageTurn(this) &&
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
    private fun pageTurn(
        forward: Boolean,
        closeMenu: Boolean = true,
        source: String = "unknown",
    ) {
        if (pageTurnBusy || singlePageRendering) {
            if (tryCoalesceSinglePageFlip(forward)) {
                ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                    "pageTurn coalesce fwd=$forward page=$pageIndex src=$source",
                )
            } else {
                ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN, "pageTurn busy skip src=$source")
            }
            return
        }
        pageTurnBusy = true
        try {
            pageTurnInner(forward, closeMenu, source)
        } finally {
            if (!singlePageRendering) pageTurnBusy = false
        }
    }

    private fun pageTurnInner(
        forward: Boolean,
        closeMenu: Boolean = true,
        source: String = "unknown",
    ) {
        if (closeMenu && chromeVisible) hideChrome()
        if (closeMenu && binding.settingsPanelContainer.isVisible) {
            binding.settingsPanelContainer.isVisible = false
        }
        val dm = resources.displayMetrics
        if (pageMode == PdfPageMode.CONTINUOUS) {
            val rv = binding.rvPdfPages
            val viewportH = rv.height
            if (viewportH <= 0 || pageCount <= 0) {
                ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN,
                    "cont abort src=$source viewportH=$viewportH pageCount=$pageCount",
                )
                return
            }
            val est = estimateCurrentPageHeightDetailed()
            val pageH = est.height.coerceAtLeast(1)
            val stepByScreen = pageH > viewportH
            val step = if (stepByScreen) {
                (viewportH * 0.8f).toInt().coerceAtLeast(1)
            } else {
                pageH
            }
            // forward=true 右边 → 向下；false 左边 → 向上
            val dy = if (forward) step else -step
            val before = rv.computeVerticalScrollOffset()
            // 无动画
            rv.stopScroll()
            rv.scrollBy(0, dy)
            val after = rv.computeVerticalScrollOffset()
            val lm = rv.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: pageIndex
            val last = lm?.findLastVisibleItemPosition() ?: pageIndex
            ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                "cont src=$source fwd=$forward mode=$pageMode " +
                    "screen=${dm.widthPixels}x${dm.heightPixels} dens=${dm.densityDpi} " +
                    "rv=${rv.width}x$viewportH pageH=$pageH step=$step byScreen=$stepByScreen " +
                    "dy=$dy scroll $before->$after delta=${after - before} " +
                    "pageIdx=$pageIndex first=$first last=$last " +
                    "est=${est.detail}",
            )
            if (after == before) {
                Toasts.show(this, if (forward) R.string.page_bottom else R.string.page_top)
                return
            }
            if (first >= 0) pageIndex = first
            updateProgressLabel()
            if (allowProgressSave) saveProgress(pageIndex)
            rv.post { refreshVisiblePageTiles(forceRender = true) }
            return
        }
        if (needsTallSinglePageZoomHost()) {
            ensureSinglePageTallPanReady()
            val host = binding.pdfContainer
            val viewportH = host.height.coerceAtLeast(1)
            val step = viewportH * 0.8f
            val dy = if (forward) -step else step
            val panYBefore = host.getPanY()
            val (_, movedY) = host.panContentBy(0f, dy)
            val panY = host.getPanY()
            val (minY, maxY) = host.verticalPanLimits()
            val canPanVert = minY < maxY - 1f
            val scrollRange = (maxY - minY).coerceAtLeast(1f)
            val viewFrac = kotlin.math.abs(movedY) / viewportH
            val contentFrac = kotlin.math.abs(movedY) / scrollRange
            val atBottom = canPanVert && panY <= minY + 2f
            val atTop = canPanVert && panY >= maxY - 2f
            ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                "singleTall src=$source fwd=$forward page=$pageIndex " +
                    "screen=${dm.widthPixels}x${dm.heightPixels} host=${host.width}x$viewportH " +
                    "step=$step dy=$dy movedY=$movedY pan $panYBefore->$panY " +
                    "bounds=$minY..$maxY canPan=$canPanVert atTop=$atTop atBottom=$atBottom " +
                    "viewFrac=${"%.2f".format(viewFrac)} contentFrac=${"%.3f".format(contentFrac)} " +
                    "zoom=${host.contentZoom} iv=${binding.ivPdfPage.width}x${binding.ivPdfPage.height}",
            )
            if (kotlin.math.abs(movedY) > 0.5f) {
                updateProgressLabel()
                refreshSinglePageTiles(forceRender = true)
                return
            }
            if (!canPanVert) {
                ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN,
                    "singleTall blocked: vertical pan range collapsed, skip flip",
                )
                return
            }
            if (forward) {
                if (!atBottom) {
                    ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN,
                        "singleTall blocked: not at bottom (panY=$panY minY=$minY)",
                    )
                    return
                }
            } else if (!atTop) {
                ReaderLog.w(ReaderLog.Module.PDF_PAGE_TURN,
                    "singleTall blocked: not at top (panY=$panY maxY=$maxY)",
                )
                return
            }
            val next = if (forward) pageIndex + 1 else pageIndex - 1
            if (next !in 0 until pageCount) {
                Toasts.show(this, if (forward) R.string.page_bottom else R.string.page_top)
                return
            }
            ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
                "singleTall flip page=$pageIndex -> $next src=$source",
            )
            showSinglePage(
                next,
                if (forward) TallPanSnap.TOP else TallPanSnap.BOTTOM,
            )
            return
        }
        val next = if (forward) pageIndex + 1 else pageIndex - 1
        ReaderLog.i(ReaderLog.Module.PDF_PAGE_TURN,
            "single src=$source fwd=$forward page=$pageIndex -> $next " +
                "screen=${dm.widthPixels}x${dm.heightPixels} dens=${dm.densityDpi}",
        )
        if (next !in 0 until pageCount) {
            Toasts.show(this, if (forward) R.string.page_bottom else R.string.page_top)
            return
        }
        showSinglePage(next, TallPanSnap.TOP)
    }

    private data class PageHeightEst(val height: Int, val detail: String)

    /** 当前可见页 item 高度（含分隔线）+ 调试信息 */
    private fun estimateCurrentPageHeightDetailed(): PageHeightEst {
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
    private fun estimateCurrentPageHeight(): Int =
        estimateCurrentPageHeightDetailed().height

    private fun currentVisiblePage(): Int {
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

    private fun updateOrientMenuIcon() {
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

    private fun setupPdfExportPanel() {
        exportPanel.btnExportClose.setOnClickListener { closePdfExportPanel() }
        exportPanel.btnExportVoice.setOnClickListener {
            TtsVoicePicker.show(this, tts) { refreshPdfExportVoiceLabel() }
        }
        exportPanel.btnPageAll.setOnClickListener { setPdfExportAllPages() }
        exportPanel.btnStartExport.setOnClickListener { startPdfPageExport() }
        exportPanel.btnCancelExport.setOnClickListener { ttsExport?.cancel() }
        val labels = exportBitrateOptions.map {
            getString(R.string.tts_export_bitrate_kbps, it)
        }
        exportPanel.spExportBitrate.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        val saved = AppSettings.ttsExportBitrateKbps(this)
        val idx = exportBitrateOptions.indexOf(saved).takeIf { it >= 0 }
            ?: exportBitrateOptions.indexOf(64).coerceAtLeast(0)
        exportPanel.spExportBitrate.setSelection(idx)
        val mp3Ok = Mp3Encoder.isAvailable()
        exportPanel.rbFormatMp3.isEnabled = mp3Ok
        if (mp3Ok) {
            exportPanel.rbFormatMp3.isChecked = true
        } else {
            exportPanel.rbFormatMp3.alpha = 0.45f
            exportPanel.rbFormatM4a.isChecked = true
        }
        exportPanel.rgExportFormat.setOnCheckedChangeListener { _, _ ->
            refreshPdfExportBitrateEnabled()
        }
        fun onPageEdit() = updatePdfExportRangeLabel()
        exportPanel.etPageFrom.setOnFocusChangeListener { _, has -> if (!has) onPageEdit() }
        exportPanel.etPageTo.setOnFocusChangeListener { _, has -> if (!has) onPageEdit() }
        refreshPdfExportVoiceLabel()
        refreshPdfExportBitrateEnabled()
    }

    private fun openPdfExportPanel() {
        if (::tts.isInitialized) tts.stop()
        // 先关底部菜单/TTS 条，再打开合成面板（避免菜单与面板叠在一起）
        chromeVisible = false
        ttsBarOpen = false
        exportPanelOpen = true
        binding.readMenuHost.visibility = View.GONE
        binding.ttsBar.isVisible = false
        binding.topBar.isVisible = false
        // 默认：当前页 ~ 全书末
        val cur = (currentVisiblePage() + 1).coerceAtLeast(1)
        val max = pageCount.coerceAtLeast(1)
        exportPanel.etPageFrom.setText(cur.toString())
        exportPanel.etPageTo.setText(max.toString())
        updatePdfExportRangeLabel()
        refreshPdfExportVoiceLabel()
        setPdfExportProgressUi(active = false)
        applyChromeVisibility()
    }

    private fun closePdfExportPanel() {
        if (ttsExport?.isWorking() == true) ttsExport?.cancel()
        exportPanelOpen = false
        setPdfExportProgressUi(active = false)
        applyChromeVisibility()
    }

    private fun setPdfExportAllPages() {
        val max = pageCount.coerceAtLeast(1)
        exportPanel.etPageFrom.setText("1")
        exportPanel.etPageTo.setText(max.toString())
        updatePdfExportRangeLabel()
    }

    private fun parsePdfExportRange(): Pair<Int, Int>? {
        if (pageCount <= 0) return null
        val from1 = exportPanel.etPageFrom.text?.toString()?.toIntOrNull() ?: return null
        val to1 = exportPanel.etPageTo.text?.toString()?.toIntOrNull() ?: return null
        var a = from1.coerceIn(1, pageCount)
        var b = to1.coerceIn(1, pageCount)
        if (a > b) {
            val t = a; a = b; b = t
        }
        return (a - 1) to (b - 1)
    }

    private fun updatePdfExportRangeLabel() {
        val range = parsePdfExportRange()
        if (range == null) {
            exportPanel.tvExportRange.text = getString(R.string.pdf_tts_export_invalid_pages, pageCount.coerceAtLeast(1))
            return
        }
        val (from0, to0) = range
        val n = to0 - from0 + 1
        var chars = 0
        for (p in from0..to0) {
            chars += pageChars[p]?.count { !it.char.isWhitespace() }
                ?: rawPageCache[p]?.count { !it.char.isWhitespace() }
                ?: 0
        }
        exportPanel.tvExportRange.text = getString(
            R.string.pdf_tts_export_range,
            from0 + 1,
            to0 + 1,
            n,
            chars,
        )
    }

    private fun refreshPdfExportVoiceLabel() {
        if (!::exportPanel.isInitialized || !::tts.isInitialized) return
        exportPanel.tvExportVoice.text = tts.currentVoiceName()
            ?: AppSettings.voiceName(this)
            ?: getString(R.string.tts_voice)
    }

    private fun refreshPdfExportBitrateEnabled() {
        if (!::exportPanel.isInitialized) return
        val need = exportPanel.rbFormatMp3.isChecked || exportPanel.rbFormatM4a.isChecked
        exportPanel.spExportBitrate.isEnabled = need
        exportPanel.tvBitrateLabel.alpha = if (need) 1f else 0.4f
        exportPanel.spExportBitrate.alpha = if (need) 1f else 0.4f
    }

    private fun selectedPdfExportBitrateKbps(): Int {
        val pos = exportPanel.spExportBitrate.selectedItemPosition
        return exportBitrateOptions.getOrNull(pos)
            ?: AppSettings.ttsExportBitrateKbps(this)
    }

    private fun startPdfPageExport() {
        val range = parsePdfExportRange()
        if (range == null) {
            Toasts.show(this, getString(R.string.pdf_tts_export_invalid_pages, pageCount.coerceAtLeast(1)))
            return
        }
        if (ttsExport?.isWorking() == true) return
        val (from0, to0) = range
        val pages = (from0..to0).toList()
        exportPanel.tvExportProgress.isVisible = true
        exportPanel.tvExportProgress.text = getString(R.string.pdf_tts_export_extracting)
        ensurePagesExtracted(
            pages = pages,
            showToast = false,
            preserveTtsPosition = true,
        ) { _ ->
            if (isFinishing || isDestroyed) return@ensurePagesExtracted
            val text = buildExportTextForPages(from0, to0)
            if (text.isBlank()) {
                setPdfExportProgressUi(active = false)
                Toasts.show(this, R.string.pdf_tts_export_no_text)
                return@ensurePagesExtracted
            }
            var format = when {
                exportPanel.rbFormatWav.isChecked -> TtsExportHelper.Format.WAV
                exportPanel.rbFormatMp3.isChecked -> TtsExportHelper.Format.MP3
                else -> TtsExportHelper.Format.M4A
            }
            if (format == TtsExportHelper.Format.MP3 && !Mp3Encoder.isAvailable()) {
                format = TtsExportHelper.Format.M4A
                exportPanel.rbFormatM4a.isChecked = true
                Toasts.show(this, R.string.tts_export_mp3_unsupported)
            }
            val kbps = selectedPdfExportBitrateKbps()
            AppSettings.setTtsExportBitrateKbps(this, kbps)
            val helper = TtsExportHelper(this).also { ttsExport = it }
            setPdfExportProgressUi(active = true, done = 0, total = 1)
            val dlg = TtsExportProgressDialog(this) {
                helper.cancel()
            }.also { exportProgressDlg = it }
            dlg.show()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            helper.export(
                text = text,
                format = format,
                filePrefix = "pdf",
                bitRateKbps = kbps,
                listener = object : TtsExportHelper.Listener {
                    override fun onProgress(
                        done: Int,
                        total: Int,
                        phase: String,
                        doneChars: Int,
                        totalChars: Int,
                        partFraction: Float,
                    ) {
                        if (isFinishing || isDestroyed) return
                        val t = total.coerceAtLeast(1)
                        val cur = if (phase == "synth" && done < t) done + 1 else done.coerceAtMost(t)
                        val label = when (phase) {
                            "prepare", "init" -> getString(R.string.tts_export_phase_prepare)
                            "encode" -> getString(R.string.tts_export_encoding)
                            "merge" -> getString(R.string.tts_export_phase_merge)
                            else -> getString(R.string.tts_export_progress, cur, t)
                        }
                        val pct = pdfExportProgressPercent(
                            done, t, phase, doneChars, totalChars, partFraction,
                        )
                        setPdfExportProgressUi(true, pct, 100, label)
                        exportProgressDlg?.update(
                            done, total, phase, doneChars, totalChars, partFraction,
                        )
                    }

                    override fun onSuccess(file: File) {
                        if (isFinishing || isDestroyed) return
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        dismissExportProgressDlg()
                        setPdfExportProgressUi(false)
                        Toasts.show(
                            this@PdfReadingActivity,
                            getString(R.string.tts_export_ok, file.name),
                            android.widget.Toast.LENGTH_LONG,
                        )
                        sharePdfExportAudio(file)
                    }

                    override fun onError(message: String) {
                        if (isFinishing || isDestroyed) return
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        dismissExportProgressDlg()
                        setPdfExportProgressUi(false)
                        Toasts.show(
                            this@PdfReadingActivity,
                            getString(R.string.tts_export_fail, message),
                            android.widget.Toast.LENGTH_LONG,
                        )
                    }

                    override fun onCancelled() {
                        if (isFinishing || isDestroyed) return
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        dismissExportProgressDlg()
                        setPdfExportProgressUi(false)
                        Toasts.show(this@PdfReadingActivity, R.string.tts_export_cancelled)
                    }
                },
            )
        }
    }

    private fun dismissExportProgressDlg() {
        exportProgressDlg?.dismiss()
        exportProgressDlg = null
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
                    val within = if (done < t) partFraction.coerceIn(0f, 1f) * (92f / t) else 0f
                    (base + within).toInt().coerceIn(0, 92)
                }
            }
        }
    }

    /** 从已提取缓存拼页范围文本；段末无句读标点则补「。」 */
    private fun buildExportTextForPages(from0: Int, to0: Int): String {
        val sb = StringBuilder()
        // 优先用分段段落（阅读 TTS 同源）
        if (paraLinks.isNotEmpty() && ttsParagraphs.isNotEmpty()) {
            for (i in paraLinks.indices) {
                val link = paraLinks[i]
                if (link.pageIndex !in from0..to0) continue
                val t = ttsParagraphs.getOrNull(i)?.text?.trim().orEmpty()
                if (t.isEmpty()) continue
                sb.append(ensurePdfExportSentenceEnd(t))
            }
        }
        if (sb.isNotEmpty()) return sb.toString()
        // 回退：按页字符流
        for (p in from0..to0) {
            val chars = pageChars[p] ?: rawPageCache[p] ?: continue
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
        if (!::exportPanel.isInitialized) return
        exportPanel.progressExport.isVisible = active
        exportPanel.tvExportProgress.isVisible = active
        exportPanel.btnCancelExport.isVisible = active
        exportPanel.btnStartExport.isEnabled = !active
        exportPanel.etPageFrom.isEnabled = !active
        exportPanel.etPageTo.isEnabled = !active
        exportPanel.btnPageAll.isEnabled = !active
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
                ?: getString(R.string.tts_export_progress, done, t)
        }
    }

    private fun sharePdfExportAudio(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.tts_export_share)))
        }
    }

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

    private fun updateCropSummary() {
        if (!::pdfSettings.isInitialized) return
        fun pct(v: Float) = (v * 100).toInt()
        pdfSettings.tvCropSummary.text = getString(
            R.string.pdf_crop_summary,
            pct(cropL), pct(cropT), pct(cropR), pct(cropB),
        )
    }

    private fun updateModeButtons() {
        if (!::pdfSettings.isInitialized) return
        val cont = pageMode == PdfPageMode.CONTINUOUS
        pdfSettings.btnModeContinuous.alpha = if (cont) 1f else 0.55f
        pdfSettings.btnModeSingle.alpha = if (cont) 0.55f else 1f
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

    private fun hasExtractedRaw(): Boolean = rawPageCache.isNotEmpty()

    private fun maxCachedPage(): Int = rawPageCache.keys.maxOrNull() ?: -1

    /**
     * 确保 [pages] 已提取；缺失页后台抽取后重建段落。
     * @param preserveTtsPosition true 时用 updateDocumentKeepPosition，不打断当前句
     * @param onReady 参数 true = 本次有新页写入缓存
     */
    private fun ensurePagesExtracted(
        pages: Collection<Int>,
        showToast: Boolean = false,
        preserveTtsPosition: Boolean = false,
        onReady: ((added: Boolean) -> Unit)? = null,
    ) {
        val wanted = pages.filter { it in 0 until pageCount }.distinct().sorted()
        if (wanted.isEmpty()) {
            onReady?.invoke(false)
            return
        }
        val missing = wanted.filter { it !in rawPageCache }
        if (missing.isEmpty()) {
            if (ttsParagraphs.isEmpty() || pageChars.isEmpty()) {
                rebuildTextFromCache(preserveTtsPosition = preserveTtsPosition)
            }
            onReady?.invoke(false)
            return
        }
        // 合并并发：提取结束后再补缺
        if (ttsExtracting) {
            if (onReady != null) {
                val prev = pendingAfterExtract
                pendingAfterExtract = {
                    prev?.invoke()
                    ensurePagesExtracted(wanted, showToast = false, preserveTtsPosition, onReady)
                }
            }
            if (showToast) Toasts.show(this, R.string.pdf_tts_extracting)
            return
        }
        val uriStr = intent.getStringExtra(EXTRA_URI) ?: run {
            onReady?.invoke(false)
            return
        }
        ttsExtracting = true
        if (showToast) Toasts.show(this, R.string.pdf_tts_extracting)
        val uri = Uri.parse(uriStr)
        val missingSnap = missing.toList()
        extractJob = lifecycleScope.launch {
            val extracted = try {
                withContext(Dispatchers.IO) {
                    try {
                        PdfTextExtractor.extractPagesRaw(this@PdfReadingActivity, uri, missingSnap)
                    } catch (t: Throwable) {
                        ReaderLog.e(ReaderLog.Module.PDF, "extractPagesRaw failed", t)
                        emptyMap()
                    }
                }
            } catch (t: Throwable) {
                ReaderLog.e(ReaderLog.Module.PDF, "extract job failed", t)
                emptyMap()
            }
            ttsExtracting = false
            if (isFinishing || isDestroyed) return@launch
            try {
                var added = false
                for ((p, chars) in extracted) {
                    val old = rawPageCache[p]
                    when {
                        chars.isNotEmpty() -> {
                            rawPageCache[p] = chars
                            added = true
                        }
                        old == null -> {
                            // PDF 无字：尝试 OCR 缓存
                            val ocr = PdfOcrCacheStore.loadPage(this@PdfReadingActivity, fileKey, p)
                            rawPageCache[p] = ocr ?: emptyList()
                            added = true
                        }
                    }
                }
                // 空页也标记已尝试，避免反复抽 / 无限回调
                for (p in missingSnap) {
                    if (p !in rawPageCache) {
                        rawPageCache[p] =
                            PdfOcrCacheStore.loadPage(this@PdfReadingActivity, fileKey, p)
                                ?: emptyList()
                        added = true
                    }
                }
                rebuildTextFromCache(preserveTtsPosition = preserveTtsPosition)
                val queued = pendingAfterExtract
                pendingAfterExtract = null
                onReady?.invoke(added)
                // 排队任务延后一帧，避免深层同步回调栈溢出
                if (queued != null) {
                    binding.pdfContainer.post {
                        if (!isFinishing && !isDestroyed) queued.invoke()
                    }
                }
            } catch (t: Throwable) {
                ReaderLog.e(ReaderLog.Module.PDF, "after extract failed", t)
                pendingAfterExtract = null
                onReady?.invoke(false)
            }
        }
    }

    /** 启动 TTS：当前页附近（前 1 后 2） */
    private fun pagesForTtsStart(anchorPage: Int = currentVisiblePage()): List<Int> =
        pagesNear(anchorPage, before = 1, after = 2)

    /** 朗读过程中预取当前页之后的页 */
    private fun prefetchNextPdfPagesForTts(paragraphIndex: Int) {
        val link = paraLinks.getOrNull(paragraphIndex) ?: return
        prefetchNearbyText(link.pageIndex)
    }

    /**
     * 按当前切边从 [rawPageCache] 重建段落与选字索引。
     */
    private fun rebuildTextFromCache(preserveTtsPosition: Boolean = false) {
        if (rawPageCache.isEmpty()) {
            ttsParagraphs = emptyList()
            pageChars = emptyMap()
            paraLinks = emptyList()
            return
        }
        val built = runCatching {
            PdfTextExtractor.buildFromCachedPages(rawPageCache) { page -> cropForPage(page) }
        }.getOrElse {
            PdfTextExtractor.Extracted(emptyList(), emptyMap(), emptyList(), rawPageCache.toMap())
        }
        ttsParagraphs = built.paragraphs
        pageChars = built.pageChars
        paraLinks = built.paraLinks
        if (::tts.isInitialized) {
            if (preserveTtsPosition && ttsParagraphs.isNotEmpty()) {
                tts.updateDocumentKeepPosition(
                    ttsParagraphs,
                    com.whj.reader.data.TextLoader.SentenceLineBreakMode.NONE,
                )
            } else {
                tts.setDocument(
                    ttsParagraphs,
                    com.whj.reader.data.TextLoader.SentenceLineBreakMode.NONE,
                )
            }
            tts.setSessionTitle(displayTitle)
        }
        if (!preserveTtsPosition) {
            hlPage = -1
            hlStart = -1
            hlEnd = -1
            binding.pdfSelectionOverlay.clearHighlight()
        }
    }

    /** 切边变更后仅重过滤缓存，不重新抽字 */
    private fun applyCropToExtractedText() {
        rebuildTextFromCache(preserveTtsPosition = false)
    }

    /** 将磁盘 OCR 页合并进 rawPageCache（不覆盖已有 PDF 原生文字） */
    private fun mergeOcrCacheFromDisk() {
        if (fileKey.isEmpty()) return
        val all = runCatching {
            PdfOcrCacheStore.loadAllPages(this, fileKey)
        }.getOrDefault(emptyMap())
        for ((p, chars) in all) {
            val old = rawPageCache[p]
            if (old.isNullOrEmpty() && chars.isNotEmpty()) {
                rawPageCache[p] = chars
            }
        }
    }

    // ─── PDF 页面 OCR（扫描版识图）────────────────────────

    private fun showPdfOcrDialog() {
        if (pageCount <= 0 || fileKey.isEmpty()) return
        if (ocrJob?.isActive == true) {
            Toasts.show(this, R.string.pdf_ocr_busy)
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_pdf_ocr, null)
        val binding = DialogPdfOcrBinding.bind(view)
        val cur = currentVisiblePage() + 1
        binding.tvOcrHint.text = getString(R.string.pdf_ocr_hint)
        binding.etFrom.setText(cur.toString())
        binding.etTo.setText(pageCount.toString())

        val withText = PdfOcrCacheStore.listRecognizedWithText(this, fileKey).sorted()
        val partial = (0 until pageCount).filter {
            PdfOcrCacheStore.ocrQuality(this, fileKey, it) == PdfOcrCacheStore.OcrQuality.PARTIAL
        }.sorted()
        val emptyOnly = PdfOcrCacheStore.listRecognized(this, fileKey)
            .filter { it !in withText.toSet() && it !in partial.toSet() }
            .sorted()
        binding.tvOcrRecognized.text = when {
            withText.isEmpty() && emptyOnly.isEmpty() && partial.isEmpty() ->
                getString(R.string.pdf_ocr_recognized_none)
            emptyOnly.isEmpty() && partial.isEmpty() ->
                getString(
                    R.string.pdf_ocr_recognized_list,
                    formatPageList(withText.map { it + 1 }),
                )
            withText.isEmpty() && partial.isEmpty() ->
                getString(
                    R.string.pdf_ocr_empty_result_list,
                    formatPageList(emptyOnly.map { it + 1 }),
                )
            else -> buildString {
                if (withText.isNotEmpty()) {
                    append(
                        getString(
                            R.string.pdf_ocr_recognized_list,
                            formatPageList(withText.map { it + 1 }),
                        ),
                    )
                }
                if (partial.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(
                        getString(
                            R.string.pdf_ocr_partial_result_list,
                            formatPageList(partial.map { it + 1 }),
                        ),
                    )
                }
                if (emptyOnly.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(
                        getString(
                            R.string.pdf_ocr_empty_result_list,
                            formatPageList(emptyOnly.map { it + 1 }),
                        ),
                    )
                }
            }
        }
        // 仅有空结果时默认不勾选「跳过」；长图局部结果（partial）也不默认跳过
        binding.cbSkipDone.isChecked = withText.isNotEmpty() && partial.isEmpty() &&
            (emptyOnly.isEmpty() || withText.isNotEmpty())

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pdf_ocr_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pdf_ocr_start, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = binding.etFrom.text.toString().toIntOrNull()
                val to = binding.etTo.text.toString().toIntOrNull()
                if (from == null || to == null || from < 1 || to < from || to > pageCount) {
                    Toasts.show(this, R.string.pdf_ocr_invalid_range)
                    return@setOnClickListener
                }
                val skipDone = binding.cbSkipDone.isChecked
                dialog.dismiss()
                startPdfOcrJob(
                    fromPage0 = from - 1,
                    toPage0 = to - 1,
                    skipDone = skipDone,
                )
            }
        }
        dialog.show()
    }

    /** 连续页合并为区间，如 `1~100, 151~299` */
    private fun formatPageList(pages1Based: List<Int>): String {
        if (pages1Based.isEmpty()) return ""
        val sorted = pages1Based.distinct().sorted()
        val ranges = ArrayList<String>()
        var start = sorted[0]
        var prev = sorted[0]
        for (i in 1 until sorted.size) {
            val p = sorted[i]
            if (p == prev + 1) {
                prev = p
            } else {
                ranges.add(if (start == prev) "$start" else "$start~$prev")
                start = p
                prev = p
            }
        }
        ranges.add(if (start == prev) "$start" else "$start~$prev")
        // 区间过多时截断，避免对话框被撑爆
        if (ranges.size <= 24) return ranges.joinToString(", ")
        return ranges.take(20).joinToString(", ") +
            getString(R.string.pdf_ocr_and_more, sorted.size)
    }

    private fun startPdfOcrJob(fromPage0: Int, toPage0: Int, skipDone: Boolean) {
        val pages = (fromPage0..toPage0).toList()
        if (pages.isEmpty()) return

        hideChrome()
        prepareBottomChromeForBlockingModal()

        val progressView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val progressTv = progressView.findViewById<android.widget.TextView>(android.R.id.text1)
        progressTv.setPadding(48, 36, 48, 24)
        progressTv.text = getString(R.string.pdf_ocr_preparing)
        val progressDlg = AlertDialog.Builder(this)
            .setTitle(R.string.pdf_ocr_title)
            .setView(progressView)
            .setCancelable(false)
            .setNegativeButton(R.string.pdf_ocr_cancel) { _, _ ->
                ocrJob?.cancel()
            }
            .create()
        progressDlg.setOnShowListener {
            prepareBottomChromeForBlockingModal()
        }
        progressDlg.show()

        ocrJob?.cancel()
        ocrJob = lifecycleScope.launch {
            val pageSizes = withContext(Dispatchers.IO) {
                pages.associateWith { p ->
                    rendererPageSize[p] ?: ensurePageSize(p)
                }
            }
            val skippedPages = ArrayList<Int>()
            val partialPages = ArrayList<Int>()
            val queue = if (skipDone) {
                pages.filter { p ->
                    val (pw, ph) = pageSizes[p] ?: (1f to 1f)
                    val q = PdfOcrCacheStore.ocrQuality(this@PdfReadingActivity, fileKey, p, pw, ph)
                    when (q) {
                        PdfOcrCacheStore.OcrQuality.COMPLETE -> {
                            skippedPages += p
                            false
                        }
                        PdfOcrCacheStore.OcrQuality.PARTIAL -> {
                            partialPages += p
                            true
                        }
                        else -> true
                    }
                }
            } else {
                pages
            }
            val skippedPre = skippedPages.size
            logPdfOcr(
                "start range=${fromPage0 + 1}..${toPage0 + 1} skipDone=$skipDone " +
                    "pages=${pages.size} queue=${queue.size} skipped=$skippedPre " +
                    "partial=${partialPages.map { it + 1 }} " +
                    "skippedPages=${skippedPages.map { it + 1 }}",
            )
            if (queue.isEmpty()) {
                Toasts.show(
                    this@PdfReadingActivity,
                    getString(R.string.pdf_ocr_done, 0, pages.size, 0),
                )
                mergeOcrCacheFromDisk()
                rebuildTextFromCache(preserveTtsPosition = true)
                if (progressDlg.isShowing) progressDlg.dismiss()
                refreshBottomChromeAfterModal("ocrEmpty")
                return@launch
            }

            progressTv.text = getString(
                R.string.pdf_ocr_progress,
                0,
                queue.size,
                queue.first() + 1,
            )

            var ok = 0
            var fail = 0
            try {
                // 主路径 GPU（AUTO：GPU→强制 GPU→CPU）；条带哑火时再按条 CPU 回退
                val eng = withContext(Dispatchers.Default) {
                    runCatching { ocrEngine?.close() }
                    ocrEngine = null
                    runCatching { ocrCpuFallback?.close() }
                    ocrCpuFallback = null
                    TfliteOcrEngine(
                        this@PdfReadingActivity,
                        TfliteOcrEngine.Backend.GPU,
                    ).also { ocrEngine = it }
                }
                for ((i, page) in queue.withIndex()) {
                    if (!isActive || isFinishing || isDestroyed) break
                    progressTv.text = getString(
                        R.string.pdf_ocr_progress,
                        i + 1,
                        queue.size,
                        page + 1,
                    )
                    val success = withContext(Dispatchers.IO) {
                        runCatching {
                            ocrOnePage(page, eng)
                        }.onFailure {
                            ReaderLog.e(ReaderLog.Module.PDF, "ocr page $page", it)
                        }.isSuccess
                    }
                    if (success) ok++ else fail++
                }
                if (isFinishing || isDestroyed) return@launch
                mergeOcrCacheFromDisk()
                // 本次 OCR 页强制刷新内存（可覆盖旧的部分识别结果）
                for (p in queue) {
                    val chars = PdfOcrCacheStore.loadPage(this@PdfReadingActivity, fileKey, p)
                    if (chars != null) {
                        rawPageCache[p] = chars
                    }
                }
                rebuildTextFromCache(preserveTtsPosition = true)
                val msg = if (!isActive) {
                    getString(R.string.pdf_ocr_cancelled, ok)
                } else {
                    getString(R.string.pdf_ocr_done, ok, skippedPre, fail)
                }
                logPdfOcr("done ok=$ok skipped=$skippedPre fail=$fail queue=${queue.size}")
                Toasts.show(this@PdfReadingActivity, msg)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    Toasts.show(this@PdfReadingActivity, getString(R.string.pdf_ocr_cancelled, ok))
                } else {
                    ReaderLog.e(ReaderLog.Module.PDF, "ocr job", t)
                    Toasts.show(
                        this@PdfReadingActivity,
                        t.message ?: getString(R.string.pdf_ocr_engine_fail),
                    )
                }
            } finally {
                if (progressDlg.isShowing) progressDlg.dismiss()
                refreshBottomChromeAfterModal("ocrDone")
            }
        }
    }

    /**
     * 渲染单页 → OCR → 持久化 + 坐标映射为 PdfChar。
     *
     * **超长页**：按近似屏高纵向分块（块间交叠），分块识别后智能合并。
     * 调试日志：tag=`PdfOcrDbg`，并写入 `files/pdf_ocr_debug/page_N.txt`。
     */
    private fun ocrOnePage(pageIndex: Int, engine: TfliteOcrEngine): Boolean {
        val r = renderer ?: return false
        if (pageIndex !in 0 until r.pageCount) return false
        val dbg = StringBuilder()
        fun log(msg: String) {
            dbg.append(msg).append('\n')
            ReaderLog.i(ReaderLog.Module.PDF_OCR, msg)
        }

        val margins = cropForPage(pageIndex)
        val cl = margins[0]
        val ct = margins[1]
        val cr = margins[2]
        val cb = margins[3]

        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(800)
        val ocrTargetW = pdfMaxRenderWidth()
        // 条带不宜太高：det 输入约 0.3× 缩放时字过小；控制在 ~900px 提高检出率
        val tileHPx = (screenH * 0.55f).toInt().coerceIn(520, 960)
        val overlapPx = (tileHPx * 0.22f).toInt().coerceIn(80, 220)

        val (pageW, pageH) = synchronized(renderLock) {
            currentPage?.close()
            currentPage = null
            val page = r.openPage(pageIndex)
            currentPage = page
            try {
                val pw = page.width.toFloat()
                val ph = page.height.toFloat()
                rendererPageSize[pageIndex] = pw to ph
                page.close()
                currentPage = null
                pw to ph
            } catch (t: Throwable) {
                runCatching { page.close() }
                currentPage = null
                throw t
            }
        }

        val contentW = pageW * (1f - cl - cr).coerceAtLeast(0.2f)
        val contentH = pageH * (1f - ct - cb).coerceAtLeast(0.2f)
        log(
            "=== ocr page=$pageIndex === screen=${pdfViewportWidth()}x${screenH} " +
                "pagePt=${pageW}x${pageH} crop=[$cl,$ct,$cr,$cb] " +
                "contentPt=${contentW}x${contentH} aspect=${contentH / contentW.coerceAtLeast(1f)}",
        )

        // 先估 scale（与 strip 渲染一致），判断是否分块
        val displayH = logicalDisplayHeight(pageW, pageH, margins, ocrTargetW)
        var probeScale = (ocrTargetW / contentW).coerceIn(0.05f, 1.25f)
        val probeStripH = min(contentH, tileHPx / probeScale)
        val areaBefore = contentW * probeStripH * probeScale * probeScale
        if (areaBefore > TILE_MAX_PIXELS) {
            probeScale = sqrt(TILE_MAX_PIXELS / (contentW * probeStripH.coerceAtLeast(1f)))
        }
        probeScale = probeScale.coerceAtLeast(0.05f)
        val estFullW = max(1, (contentW * probeScale).toInt())
        val estFullH = max(1, (contentH * probeScale).toInt())
        val useTilesByHelper = OcrTileHelper.shouldTile(displayH, tileHPx, ocrTargetW)
        // PDF 竖长页强制分块（旧逻辑用 estFullH 可能因 scale 过小漏判）
        val useTilesByAspect = PdfOcrCacheStore.isTallPage(pageW, pageH) && displayH > 500
        val useTiles = useTilesByHelper || useTilesByAspect
        log(
            "probeScale=$probeScale tileHPx=$tileHPx ovPx=$overlapPx " +
                "displayH=$displayH estPx=${estFullW}x${estFullH} areaStrip=$areaBefore " +
                "useTiles=$useTiles (helper=$useTilesByHelper aspect=$useTilesByAspect)",
        )

        val lines: List<TfliteOcrEngine.LineResult>
        val mapBmpW: Int
        val mapBmpH: Int

        if (!useTiles) {
            // 矮页：整页一次，用实际位图尺寸映射
            val bmp = renderOcrPageBitmap(r, pageIndex, ocrTargetW)
            try {
                log(
                    "single-shot bmp=${bmp.width}x${bmp.height} bytes=${bmp.byteCount} " +
                        "backend=${engine.backendName}",
                )
                val safe = ensureSoftwareArgb(bmp)
                val result = engine.recognize(safe, autoInvert = true)
                if (safe !== bmp && !safe.isRecycled) safe.recycle()
                lines = result.lines
                mapBmpW = bmp.width
                mapBmpH = bmp.height
                log(
                    "single-shot done lines=${lines.size} detMs=${result.detMs} " +
                        "recMs=${result.recMs} backend=${result.backend}",
                )
                log(lineYSpanText("single", lines))
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        } else {
            // 长页/竖长截图：按内容高度切条带，块高对应 tileHPx 像素
            val stripContentH = (tileHPx / probeScale).coerceIn(8f, contentH)
            val overlapContent = (overlapPx / probeScale).coerceIn(0f, stripContentH * 0.45f)
            val ranges = verticalContentRanges(contentH, stripContentH, overlapContent)
            log(
                "tile mode strips=${ranges.size} stripHPt=$stripContentH ovPt=$overlapContent " +
                    "cover=${ranges.firstOrNull()?.first}->${ranges.lastOrNull()?.second} / $contentH",
            )
            ranges.forEachIndexed { i, (a, b) ->
                log("  range[$i]=$a..$b h=${b - a}")
            }

            val parts = ArrayList<List<TfliteOcrEngine.LineResult>>(ranges.size)
            var unifiedW = 0
            var unifiedScale = probeScale
            var totalLocalLines = 0
            var gpuEmptyStreak = 0
            log("tile engine backend=${engine.backendName}")

            for ((ti, range) in ranges.withIndex()) {
                if (Thread.interrupted() || ocrJob?.isActive == false) {
                    throw kotlinx.coroutines.CancellationException("ocr cancelled")
                }
                val (srcY0, srcY1) = range
                val strip = renderOcrStripBitmap(r, pageIndex, ocrTargetW, srcY0, srcY1)
                try {
                    if (unifiedW <= 0) {
                        unifiedW = strip.width.coerceAtLeast(1)
                        unifiedScale = unifiedW / contentW
                        log("unifiedW=$unifiedW unifiedScale=$unifiedScale")
                    }
                    val ink = sampleInkRatio(strip)
                    val inkF = ink.toFloatOrNull() ?: 0f
                    // GPU 输入用独立软件位图，避免连续条带复用/硬件缓冲导致 det 哑火
                    val safeBmp = ensureSoftwareArgb(strip)
                    var result = engine.recognize(safeBmp, autoInvert = true)
                    var usedBackend = result.backend
                    // 主路径含 GPU 时：有“文字感”墨量却 0 行 → 本条纯 CPU 回退
                    val mainIsGpu = engine.backendName.contains("GPU", ignoreCase = true)
                    if (result.lines.isEmpty() && inkLooksLikeText(inkF) && mainIsGpu) {
                        gpuEmptyStreak++
                        log(
                            "  strip$ti GPU empty streak=$gpuEmptyStreak ink=$ink " +
                                "backend=${engine.backendName} → try CPU",
                        )
                        val cpu = ensureOcrCpuFallback()
                        val r2 = cpu.recognize(safeBmp, autoInvert = true)
                        if (r2.lines.isNotEmpty()) {
                            result = r2
                            usedBackend = "CPU-fallback"
                            log("  strip$ti CPU-fallback ok lines=${r2.lines.size}")
                            gpuEmptyStreak = 0
                        } else {
                            log("  strip$ti CPU-fallback still empty")
                        }
                    } else if (result.lines.isNotEmpty()) {
                        gpuEmptyStreak = 0
                    } else {
                        // 高 ink 多半是插图区，或不含 GPU 无需回退
                        log("  strip$ti empty skip-fallback ink=$ink backend=${engine.backendName}")
                    }
                    if (safeBmp !== strip && !safeBmp.isRecycled) safeBmp.recycle()

                    val local = result.lines
                    totalLocalLines += local.size
                    // 条带像素 → 整页内容像素：x 按宽比，y = 顶偏移 + 局部 y 按高比
                    val xScale = if (strip.width > 0) unifiedW / strip.width.toFloat() else 1f
                    val stripContentSpan = (srcY1 - srcY0).coerceAtLeast(0.5f)
                    val topPx = srcY0 * unifiedScale
                    val spanPx = stripContentSpan * unifiedScale
                    val yScale = if (strip.height > 0) spanPx / strip.height else 1f
                    val mapH = max(1, (contentH * unifiedScale).toInt()).toFloat()
                    val mapped = local.map { line ->
                        val box = line.box
                        if (box == null || box.size < 8) {
                            line
                        } else {
                            val nb = FloatArray(8) { i ->
                                if (i % 2 == 0) {
                                    (box[i] * xScale).coerceIn(0f, unifiedW.toFloat())
                                } else {
                                    (box[i] * yScale + topPx).coerceIn(0f, mapH)
                                }
                            }
                            line.copy(box = nb)
                        }
                    }
                    parts += mapped
                    val sample = local.take(2).joinToString(" | ") { it.text.take(24) }
                    if (result.log.contains("invert") || result.log.contains("lowThr")) {
                        log("  engine: ${result.log.trim().replace("\n", " | ")}")
                    }
                    log(
                        "strip $ti/${ranges.size} yPt=$srcY0..$srcY1 " +
                            "bmp=${strip.width}x${strip.height} ink=$ink " +
                            "topPx=$topPx yScale=$yScale lines=${local.size} " +
                            "detMs=${result.detMs} recMs=${result.recMs} " +
                            "via=$usedBackend sample=[$sample]",
                    )
                    log(lineYSpanText("strip$ti-local", local))
                    log(lineYSpanText("strip$ti-mapped", mapped))
                } finally {
                    if (!strip.isRecycled) strip.recycle()
                }
            }

            val beforeMerge = parts.sumOf { it.size }
            lines = OcrTileHelper.mergeLines(parts)
            mapBmpW = unifiedW.coerceAtLeast(1)
            mapBmpH = max(1, (contentH * unifiedScale).toInt())
            log(
                "merge: parts=${parts.size} linesBefore=$beforeMerge " +
                    "localSum=$totalLocalLines after=${lines.size} " +
                    "mapBmp=${mapBmpW}x${mapBmpH}",
            )
            log(lineYSpanText("merged", lines))
        }

        val chars = PdfOcrConverter.linesToPdfChars(
            pageIndex = pageIndex,
            lines = lines,
            bmpW = mapBmpW,
            bmpH = mapBmpH,
            pageW = pageW,
            pageH = pageH,
            cropL = cl,
            cropT = ct,
            cropR = cr,
            cropB = cb,
        )
        // 字符在页坐标中的纵向覆盖比例（用于判断是否只识别了上部）
        if (chars.isNotEmpty()) {
            val yMin = chars.minOf { it.top }
            val yMax = chars.maxOf { it.bottom }
            val cover = (yMax - yMin) / pageH.coerceAtLeast(1f)
            log(
                "chars=${chars.size} pageY=$yMin..$yMax " +
                    "coverFrac=$cover map=${mapBmpW}x${mapBmpH} page=${pageW}x${pageH}",
            )
        } else {
            log("chars=0 (empty OCR)")
        }
        writeOcrDebugFile(pageIndex, dbg.toString())
        PdfOcrCacheStore.savePage(
            this,
            fileKey,
            pageIndex,
            chars,
            pageWidth = pageW,
            pageHeight = pageH,
        )
        return true
    }

    private fun lineYSpanText(tag: String, lines: List<TfliteOcrEngine.LineResult>): String {
        if (lines.isEmpty()) return "  $tag: empty"
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        for (line in lines) {
            val box = line.box ?: continue
            if (box.size < 8) continue
            val ys = floatArrayOf(box[1], box[3], box[5], box[7])
            minY = min(minY, ys.min())
            maxY = max(maxY, ys.max())
        }
        return if (minY == Float.MAX_VALUE) {
            "  $tag: ${lines.size} lines (no boxes)"
        } else {
            "  $tag: ${lines.size} lines y=$minY..$maxY " +
                "first='${lines.first().text.take(20)}' last='${lines.last().text.take(20)}'"
        }
    }

    /** 白底正文常见 ink 区间；过高多为插图/大色块，不必 CPU 回退 */
    private fun inkLooksLikeText(ink: Float): Boolean = ink in 0.04f..0.62f

    private fun ensureSoftwareArgb(src: android.graphics.Bitmap): android.graphics.Bitmap {
        if (src.config == android.graphics.Bitmap.Config.ARGB_8888 && !src.isMutable) {
            // 仍 copy 一份，切断与 PdfRenderer 缓冲/上一帧 GPU 输入的关联
            return src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: src
        }
        return src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: src
    }

    private fun ensureOcrCpuFallback(): TfliteOcrEngine {
        ocrCpuFallback?.let { return it }
        return TfliteOcrEngine(this, TfliteOcrEngine.Backend.CPU).also {
            ocrCpuFallback = it
            ReaderLog.i(ReaderLog.Module.PDF_OCR, "cpu fallback engine opened backend=${it.backendName}")
        }
    }

    /** 抽样非白像素比例，用于判断条带渲染是否空白 */
    private fun sampleInkRatio(bmp: android.graphics.Bitmap): String {
        if (bmp.isRecycled || bmp.width < 2 || bmp.height < 2) return "n/a"
        val w = bmp.width
        val h = bmp.height
        val stepX = max(1, w / 40)
        val stepY = max(1, h / 40)
        var dark = 0
        var total = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 相对白底有墨
                if (r < 245 || g < 245 || b < 245) dark++
                total++
                x += stepX
            }
            y += stepY
        }
        val ratio = if (total > 0) dark.toFloat() / total else 0f
        return String.format(java.util.Locale.US, "%.3f", ratio)
    }

    private fun writeOcrDebugFile(pageIndex: Int, text: String) {
        runCatching {
            val dir = java.io.File(filesDir, "pdf_ocr_debug").also { it.mkdirs() }
            java.io.File(dir, "page_$pageIndex.txt").writeText(text, Charsets.UTF_8)
            // 整文件覆盖会话汇总，避免无限增长
            java.io.File(dir, "last_session.txt").writeText(
                "----- page $pageIndex ${System.currentTimeMillis()} -----\n$text",
                Charsets.UTF_8,
            )
        }.onFailure {
            ReaderLog.w(ReaderLog.Module.PDF_OCR, "write debug fail", it)
        }
    }

    /** 内容坐标系下的纵向条带 [y0, y1)（已扣 crop 的内容高） */
    private fun verticalContentRanges(
        contentH: Float,
        stripH: Float,
        overlap: Float,
    ): List<Pair<Float, Float>> {
        val h = contentH.coerceAtLeast(1f)
        val th = stripH.coerceIn(1f, h)
        val ov = overlap.coerceIn(0f, th * 0.45f)
        // 与 OcrTileHelper.shouldTile 一致：略超块高也分块
        if (h <= th * 1.05f) return listOf(0f to h)
        val step = (th - ov).coerceAtLeast(1f)
        val out = ArrayList<Pair<Float, Float>>()
        var top = 0f
        while (true) {
            val bottom = min(h, top + th)
            out += top to bottom
            if (bottom >= h - 0.01f) break
            val next = top + step
            if (next + th >= h) {
                // 末块贴底，高度不超过 th（不把末段拉成超高条）
                val lastTop = max(0f, h - th)
                if (lastTop > top + ov * 0.5f) {
                    out += lastTop to h
                } else {
                    out[out.lastIndex] = top to h
                }
                break
            }
            top = next
        }
        return out
    }

    private fun renderOcrPageBitmap(
        r: PdfRenderer,
        pageIndex: Int,
        targetWidth: Int,
    ): Bitmap = synchronized(renderLock) {
        currentPage?.close()
        currentPage = null
        val page = r.openPage(pageIndex)
        currentPage = page
        try {
            val b = renderPageBitmap(
                page,
                targetWidth = targetWidth,
                targetHeight = null,
                pageIndexForMirror = pageIndex,
            )
            page.close()
            currentPage = null
            b
        } catch (t: Throwable) {
            runCatching { page.close() }
            currentPage = null
            throw t
        }
    }

    private fun renderOcrStripBitmap(
        r: PdfRenderer,
        pageIndex: Int,
        targetWidth: Int,
        srcY0: Float,
        srcY1: Float,
    ): Bitmap = synchronized(renderLock) {
        currentPage?.close()
        currentPage = null
        val page = r.openPage(pageIndex)
        currentPage = page
        try {
            val b = renderPageStripBitmap(
                page = page,
                targetWidth = targetWidth,
                srcY0 = srcY0,
                srcY1 = srcY1,
                pageIndexForMirror = pageIndex,
            )
            page.close()
            currentPage = null
            b
        } catch (t: Throwable) {
            runCatching { page.close() }
            currentPage = null
            throw t
        }
    }

    private fun startPdfTts() {
        val pages = pagesForTtsStart()
        ensurePagesExtracted(
            pages = pages,
            showToast = true,
            preserveTtsPosition = false,
        ) { _ ->
            if (ttsParagraphs.isEmpty()) {
                exitTtsWithMessage(R.string.pdf_tts_unavailable)
            } else {
                Toasts.show(
                    this@PdfReadingActivity,
                    getString(R.string.pdf_tts_ready, ttsParagraphs.size),
                )
                openTtsAndPlay()
            }
        }
    }

    private fun currentPageHasText(): Boolean {
        val page = currentVisiblePage()
        val chars = pageChars[page] ?: return false
        return chars.any { !it.char.isWhitespace() }
    }

    private fun exitTtsWithMessage(msgRes: Int) {
        if (::tts.isInitialized) tts.stop()
        sleepTimer.cancel()
        updateSleepUi()
        ttsBarOpen = false
        chromeVisible = false
        applyChromeVisibility()
        syncPdfContentBottomInset()
        Toasts.show(this, msgRes)
    }

    /** TTS 句高亮 + 滚动到可见（避开 TTS 控制栏遮挡） */
    private fun applyTtsSentenceHighlight(paragraphIndex: Int, startOffset: Int, endOffset: Int) {
        if (endOffset < 0 || paragraphIndex < 0 || paragraphIndex >= paraLinks.size) {
            hlPage = -1
            hlStart = -1
            hlEnd = -1
            binding.pdfSelectionOverlay.clearHighlight()
            return
        }
        val link = paraLinks[paragraphIndex]
        val len = (link.charEnd - link.charStart).coerceAtLeast(0)
        if (len <= 0) {
            binding.pdfSelectionOverlay.clearHighlight()
            return
        }
        val a = startOffset.coerceIn(0, len - 1)
        val b = (endOffset - 1).coerceIn(a, len - 1)
        hlPage = link.pageIndex
        hlStart = link.charStart + a
        hlEnd = link.charStart + b
        refreshHighlightOverlay()
        // 等 TTS 条 layout 后再判可见/翻屏，避免 height=0 漏滚
        binding.pdfContainer.post {
            if (isFinishing || isDestroyed) return@post
            scrollToCharRange(hlPage, hlStart, hlEnd)
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

    private fun scrollToCharRange(page: Int, charStart: Int, charEnd: Int) {
        if (page < 0 || pageCount <= 0) return
        val chars = pageChars[page] ?: return
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

    private fun openTtsAndPlay() {
        // 当前页无字：提示并退出 TTS
        if (!currentPageHasText()) {
            exitTtsWithMessage(R.string.pdf_tts_page_no_text)
            return
        }
        chromeVisible = false
        ttsBarOpen = true
        applyChromeVisibility()
        // 打开 TTS 后补底 inset，尾页可滚到控制条上方
        binding.ttsBar.post { syncPdfContentBottomInset() }
        withTtsNotificationPermission {
            if (!tts.isReady()) {
                tts.reinit()
                // 状态仅显示在 TTS 面板，不 Toast
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

    private fun withTtsNotificationPermission(then: () -> Unit) {
        if (TtsManager.hasNotificationPermission(this)) {
            then()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            pendingTtsAfterNotif = then
            ttsNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            then()
        }
    }

    /** 从当前屏第一个完整可见字开始读到文末 */
    private fun startTtsFromViewport() {
        if (ttsParagraphs.isEmpty() || paraLinks.isEmpty()) {
            exitTtsWithMessage(R.string.pdf_tts_unavailable)
            return
        }
        if (!currentPageHasText()) {
            exitTtsWithMessage(R.string.pdf_tts_page_no_text)
            return
        }
        val pos = findFirstFullyVisiblePdfChar()
        if (pos == null) {
            val page = currentVisiblePage()
            val para = paraLinks.indexOfFirst { it.pageIndex == page }
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

    /**
     * @return (段落索引, 段内字符偏移)
     */
    private fun findFirstFullyVisiblePdfChar(): Pair<Int, Int>? {
        if (pageChars.isEmpty() || paraLinks.isEmpty()) return null
        when (pageMode) {
            PdfPageMode.SINGLE -> {
                val page = pageIndex
                val chars = pageChars[page] ?: return null
                // 单页：从上到下第一个非空白字
                val first = chars.firstOrNull { !it.char.isWhitespace() } ?: return null
                return mapPageCharToParaOffset(page, first.indexOnPage)
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = binding.rvPdfPages
                val lm = rv.layoutManager as? LinearLayoutManager ?: return null
                val firstPos = lm.findFirstVisibleItemPosition()
                if (firstPos == RecyclerView.NO_POSITION) return null
                val lastPos = lm.findLastVisibleItemPosition().coerceAtLeast(firstPos)
                val viewportTop = 0f
                val viewportBottom = rv.height.toFloat()
                for (pos in firstPos..lastPos) {
                    val child = lm.findViewByPosition(pos) ?: continue
                    val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: continue
                    val chars = pageChars[pos] ?: continue
                    // 页在 content（RV）坐标中
                    val itemTop = child.top.toFloat()
                    val itemBottom = child.bottom.toFloat()
                    // 完整可见：item 顶不低于视口顶（允许少量像素误差）
                    // 优先找页内第一个完全落在视口内的字
                    @Suppress("UNUSED_VARIABLE")
                    val fullyInView = itemTop >= viewportTop - 2f && itemBottom <= viewportBottom + 2f
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
                        // 字符矩形完全在 RV 视口内
                        if (rect.top >= viewportTop - 1f && rect.bottom <= viewportBottom + 1f) {
                            return mapPageCharToParaOffset(pos, c.indexOnPage)
                        }
                    }
                    // 本页没有完全在视口内的字：若页顶被裁切，继续下一页；
                    // 若页顶完整进入视口，取页内第一个字
                    if (itemTop >= viewportTop - 2f && sorted.isNotEmpty()) {
                        return mapPageCharToParaOffset(pos, sorted.first().indexOnPage)
                    }
                    if (fullyInView && sorted.isNotEmpty()) {
                        return mapPageCharToParaOffset(pos, sorted.first().indexOnPage)
                    }
                }
                return null
            }
        }
    }

    private fun mapPageCharToParaOffset(page: Int, charIndexOnPage: Int): Pair<Int, Int>? {
        for ((i, link) in paraLinks.withIndex()) {
            if (link.pageIndex != page) continue
            if (charIndexOnPage in link.charStart until link.charEnd) {
                return i to (charIndexOnPage - link.charStart)
            }
        }
        // 页内任意段：取该页第一段
        val i = paraLinks.indexOfFirst { it.pageIndex == page }
        return if (i >= 0) i to 0 else null
    }

    /** 从选区起点读到全书末尾（保持完整文档，不替换为选区片段） */
    private fun startTtsFromSelection() {
        if (!hasTextSelection() || ttsParagraphs.isEmpty()) return
        val page = selPage
        val charIdx = min(selStart, selEnd)
        // 确保文档是完整提取结果
        if (::tts.isInitialized) {
            tts.setDocument(ttsParagraphs)
            tts.setSessionTitle(displayTitle)
        }
        val mapped = mapPageCharToParaOffset(page, charIdx)
        chromeVisible = false
        ttsBarOpen = true
        applyChromeVisibility()
        if (!tts.isReady()) {
            tts.reinit()
            updateTtsUi(tts.currentState())
        }
        if (mapped != null) {
            tts.playFromParagraphOffset(mapped.first, mapped.second)
        } else {
            tts.playFrom(0, 0)
        }
        clearTextSelection()
    }

    private fun setupTtsBar() {
        binding.btnTtsPrev.setOnClickListener { tts.previousSentence() }
        binding.btnTtsPlayPause.setOnClickListener {
            withTtsNotificationPermission {
                val snap = tts.currentState()
                if (snap.state == TtsManager.State.IDLE) {
                    startTtsFromViewport()
                } else {
                    tts.playPauseToggle()
                }
            }
        }
        binding.btnTtsNext.setOnClickListener { tts.nextSentence() }
        binding.btnTtsStop.setOnClickListener {
            tts.stop()
            sleepTimer.cancel()
            updateSleepUi()
            ttsBarOpen = false
            applyChromeVisibility()
            syncPdfContentBottomInset()
        }
        binding.btnTtsRate.setOnClickListener { v -> showTtsRateMenu(v) }
        binding.btnTtsSleep.setOnClickListener { v -> showTtsSleepMenu(v) }
        binding.tvTtsSleepCountdown.setOnClickListener { confirmCancelSleepTimer() }
        binding.btnVoice.setOnClickListener { showVoicePicker() }
        updateTtsRateLabel(AppSettings.ttsRate(this))
        updateSleepUi()
    }

    private fun confirmCancelSleepTimer() {
        if (!sleepTimer.isActive()) {
            showTtsSleepMenu(binding.btnTtsSleep)
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.tts_sleep_cancel_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                sleepTimer.cancel()
                updateSleepUi()
                Toasts.show(this, R.string.tts_sleep_cancelled)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val ttsRateOptions = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)

    private fun showTtsRateMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        ttsRateOptions.forEachIndexed { i, rate ->
            popup.menu.add(0, i, i, formatRateLabel(rate))
        }
        popup.setOnMenuItemClickListener { item ->
            val rate = ttsRateOptions.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            AppSettings.setTtsRate(this, rate)
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
        return body + "×"
    }

    private fun updateTtsRateLabel(rate: Float) {
        binding.btnTtsRate.text = formatRateLabel(rate)
    }

    private fun showTtsSleepMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        com.whj.reader.tts.TtsSleepTimer.OPTION_MINUTES.forEachIndexed { i, mins ->
            val title = if (mins == 0) {
                getString(R.string.tts_sleep_off)
            } else {
                getString(R.string.tts_sleep_minutes, mins)
            }
            popup.menu.add(0, i, i, title)
        }
        popup.setOnMenuItemClickListener { item ->
            val mins = com.whj.reader.tts.TtsSleepTimer.OPTION_MINUTES
                .getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            if (mins == 0) {
                sleepTimer.cancel()
                updateSleepUi()
                Toasts.show(this, R.string.tts_sleep_cancelled)
            } else {
                sleepTimer.start(mins * 60_000L)
                updateSleepUi()
                Toasts.show(this, getString(R.string.tts_sleep_set, mins))
            }
            true
        }
        popup.show()
    }

    private fun updateSleepUi() {
        val active = sleepTimer.isActive()
        binding.btnTtsSleep.isVisible = !active
        binding.tvTtsSleepCountdown.isVisible = active
        if (active) {
            binding.tvTtsSleepCountdown.text =
                com.whj.reader.tts.TtsSleepTimer.formatCountdown(sleepTimer.remainingMs())
        }
    }

    private fun onSleepTimerFinished() {
        if (isFinishing || isDestroyed) return
        if (::tts.isInitialized) tts.stop()
        updateSleepUi()
        if (::tts.isInitialized) updateTtsUi(tts.currentState())
        Toasts.show(this, R.string.tts_sleep_finished)
    }

    /** 引擎 / 语言 / 发音人 三级下拉 */
    private fun showVoicePicker() {
        TtsVoicePicker.show(this, tts) {
            if (tts.currentState().state == TtsManager.State.SPEAKING) {
                val snap = tts.currentState()
                tts.playFrom(snap.paragraphIndex, snap.sentenceIndex)
            }
        }
    }

    /** TTS 初始化/未就绪：不弹 Toast（状态文案已在 TTS 面板显示） */
    private fun isTtsInitNoise(message: String): Boolean {
        if (message.isBlank()) return false
        return message == getString(R.string.tts_init_failed) ||
            message == getString(R.string.tts_initializing) ||
            message == getString(R.string.tts_init_pending) ||
            message == getString(R.string.tts_still_not_ready) ||
            message == getString(R.string.tts_not_ready) ||
            message == getString(R.string.tts_reinit) ||
            message.startsWith(getString(R.string.tts_init_failed))
    }

    private fun updateTtsUi(snapshot: TtsManager.Snapshot) {
        applyChromeVisibility()
        when (snapshot.state) {
            TtsManager.State.SPEAKING -> {
                binding.btnTtsPlayPause.setImageResource(R.drawable.ic_pause)
                binding.tvTtsStatus.text = getString(R.string.tts_speaking)
            }
            TtsManager.State.PAUSED -> {
                binding.btnTtsPlayPause.setImageResource(R.drawable.ic_play)
                binding.tvTtsStatus.text = getString(R.string.tts_paused)
            }
            TtsManager.State.IDLE -> {
                binding.btnTtsPlayPause.setImageResource(R.drawable.ic_play)
                binding.tvTtsStatus.text = when {
                    !snapshot.ready -> snapshot.statusMessage.ifBlank {
                        getString(R.string.tts_not_ready)
                    }
                    else -> getString(R.string.tts_idle)
                }
            }
        }
    }

    /**
     * 内容排版是否按横屏（跟视角模式；大屏竖屏=竖栏+竖屏 fit）。
     */
    private fun isLandscape(): Boolean {
        val mode = AppSettings.pdfOrientationMode(this)
        val root = if (::binding.isInitialized) binding.root else null
        return OrientationHelper.isEffectiveLandscape(this, mode, root)
    }

    /** 真实窗口是否横置（状态栏/沉浸 UI 用这个，避免竖屏模式在横窗上把底栏顶到画面中间） */
    private fun isWindowLandscape(): Boolean {
        val root = if (::binding.isInitialized) binding.root else null
        return OrientationHelper.isWindowLandscape(this, root)
    }

    /** 横竖均占满；清除历史中间竖栏 padding，保留底 inset */
    private fun applyPortraitColumnLayout() {
        if (!::binding.isInitialized) return
        val bottom = binding.pdfContainer.paddingBottom
        if (binding.pdfContainer.paddingLeft != 0 || binding.pdfContainer.paddingRight != 0) {
            binding.pdfContainer.setPadding(0, 0, 0, bottom)
        }
        updatePdfZoomChrome()
        applyNightUi()
        when (pageMode) {
            PdfPageMode.SINGLE -> if (pageCount > 0) {
                if (singlePageUsesTiles) {
                    binding.pdfContainer.post { refreshSinglePageTiles(forceRender = true) }
                } else {
                    binding.ivPdfPage.post { applySinglePageImageMatrix() }
                }
            }
            PdfPageMode.CONTINUOUS -> {
                binding.rvPdfPages.requestLayout()
                binding.rvPdfPages.post { refreshVisiblePageTiles(forceRender = true) }
            }
        }
    }

    /**
     * 旋转后 / 模态框后收起底栏异常高度：透明 bottomChrome 被撑高时，
     * 状态栏会浮在画面中间，菜单区露出 PDF 黑底。
     */
    private fun collapseBottomChromeLayout(hideMenuHost: Boolean) {
        if (!::binding.isInitialized) return
        if (hideMenuHost) {
            binding.readMenuHost.visibility = View.GONE
            binding.readMenuHost.layoutParams = binding.readMenuHost.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        if (!exportPanelOpen) {
            binding.ttsExportHost.visibility = View.GONE
            if (::exportPanel.isInitialized) {
                exportPanel.root.visibility = View.GONE
            }
        }
        if (!ttsBarOpen) {
            binding.ttsBar.visibility = View.GONE
        }
        binding.bottomChrome.translationY = 0f
        binding.readStatusBar.translationY = 0f
        binding.bottomChrome.minimumHeight = 0
        val lp = binding.bottomChrome.layoutParams
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
            lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        }
        binding.bottomChrome.layoutParams = lp
        val slp = binding.readStatusBar.layoutParams
        if (slp is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
            slp.bottomToTop = binding.bottomChrome.id
            slp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            binding.readStatusBar.layoutParams = slp
        }
        binding.bottomChrome.requestLayout()
        binding.readStatusBar.requestLayout()
    }

    private fun sanitizeBottomChrome() {
        collapseBottomChromeLayout(hideMenuHost = !chromeVisible)
    }

    /** OCR/导出进度框关闭后重算底栏（避免菜单区黑条，需再点菜单才恢复） */
    private fun refreshBottomChromeAfterModal(reason: String) {
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        binding.bottomChrome.post {
            if (isFinishing || isDestroyed) return@post
            binding.bottomChrome.requestApplyInsets()
            collapseBottomChromeLayout(hideMenuHost = !chromeVisible)
            applyChromeVisibility()
            logPdfChrome(reason)
        }
    }

    private fun logPdfChrome(tag: String) {
        if (!::binding.isInitialized) return
        val bc = binding.bottomChrome
        ReaderLog.i(ReaderLog.Module.PDF_CHROME,
            "$tag chrome=$chromeVisible ttsOpen=$ttsBarOpen export=$exportPanelOpen " +
                "menuVis=${binding.readMenuHost.visibility} menuH=${binding.readMenuHost.height} " +
                "ttsVis=${binding.ttsBar.visibility} ttsH=${binding.ttsBar.height} " +
                "bc=${bc.width}x${bc.height} rvPadB=${binding.rvPdfPages.paddingBottom}",
        )
    }

    private fun logPdfOcr(msg: String) {
        ReaderLog.i(ReaderLog.Module.PDF_OCR, msg)
    }

    /** 沉浸/底栏：按用户选择的横竖模式 [isLandscape] */
    private fun applyLandscapeFullscreenUi() {
        if (!::binding.isInitialized) return
        val landUi = isLandscape()
        binding.readStatusBar.isVisible = !landUi
        if (landUi) {
            binding.tvReadTitle.isVisible = false
        } else if (!immersive) {
            binding.tvReadTitle.isVisible = true
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (landUi) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            updatePdfZoomChrome()
        } else if (immersive) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            binding.tvReadTitle.isVisible = false
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
            binding.tvReadTitle.isVisible = true
        }
    }

    private fun applyChromeVisibility() {
        applyLandscapeFullscreenUi()
        binding.topBar.isVisible = chromeVisible && !exportPanelOpen
        binding.ttsBar.isVisible = !chromeVisible && !exportPanelOpen && ttsBarOpen
        val menuHost = binding.readMenuHost
        val exportHost = binding.ttsExportHost
        if (exportPanelOpen) {
            menuHost.visibility = View.GONE
            readMenu.root.visibility = View.GONE
            exportHost.visibility = View.VISIBLE
            exportPanel.root.visibility = View.VISIBLE
            exportHost.bringToFront()
            if (binding.readStatusBar.isVisible) binding.readStatusBar.bringToFront()
            binding.bottomChrome.bringToFront()
        } else if (chromeVisible) {
            exportHost.visibility = View.GONE
            exportPanel.root.visibility = View.GONE
            menuHost.visibility = View.VISIBLE
            readMenu.root.visibility = View.VISIBLE
            val lp = menuHost.layoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            menuHost.layoutParams = lp
            menuHost.bringToFront()
            if (binding.readStatusBar.isVisible) binding.readStatusBar.bringToFront()
            binding.bottomChrome.bringToFront()
            binding.topBar.bringToFront()
            menuHost.post { if (chromeVisible && !exportPanelOpen) forceMenuLayout() }
        } else {
            menuHost.visibility = View.GONE
            exportHost.visibility = View.GONE
            if (::exportPanel.isInitialized) {
                exportPanel.root.visibility = View.GONE
            }
            collapseBottomChromeLayout(hideMenuHost = true)
        }
        // TTS/菜单叠在内容上：列表底部加 padding，尾页最底可滚到控制条之上
        syncPdfContentBottomInset()
        binding.bottomChrome.post { syncPdfContentBottomInset() }
    }

    /** 模态框（OCR 进度等）弹出前收起底栏并立刻去掉菜单区 padding，避免露出 PDF 黑底 */
    private fun prepareBottomChromeForBlockingModal() {
        if (!::binding.isInitialized) return
        collapseBottomChromeLayout(hideMenuHost = true)
        syncPdfContentBottomInset()
        binding.bottomChrome.post {
            if (isFinishing || isDestroyed) return@post
            collapseBottomChromeLayout(hideMenuHost = true)
            syncPdfContentBottomInset()
            logPdfChrome("modalPrepare")
        }
    }

    /**
     * 内容区底部 inset：TTS 条 / 状态栏 / 导航垫高，避免遮住 PDF 最后几行。
     */
    private fun syncPdfContentBottomInset() {
        if (!::binding.isInitialized) return
        var pad = 0
        if (binding.readStatusBar.isVisible) {
            pad += binding.readStatusBar.height.coerceAtLeast(0)
        }
        val bc = binding.bottomChrome
        if (bc.visibility == View.VISIBLE) {
            val ttsH = if (binding.ttsBar.isVisible) binding.ttsBar.height.coerceAtLeast(0) else 0
            val menuH = if (binding.readMenuHost.isVisible && chromeVisible) {
                binding.readMenuHost.height.coerceAtLeast(0)
            } else {
                0
            }
            val expH = if (binding.ttsExportHost.isVisible && exportPanelOpen) {
                binding.ttsExportHost.height.coerceAtLeast(0)
            } else {
                0
            }
            val inner = maxOf(ttsH, menuH, expH)
            if (inner > 0) {
                pad += inner + bc.paddingBottom.coerceAtLeast(0)
            } else if (ttsBarOpen && !chromeVisible) {
                pad += (56f * resources.displayMetrics.density).toInt() +
                    bc.paddingBottom.coerceAtLeast(0)
            }
        }
        val rv = binding.rvPdfPages
        if (rv.paddingBottom != pad) {
            rv.setPadding(rv.paddingLeft, rv.paddingTop, rv.paddingRight, pad)
            rv.clipToPadding = false
        }
        // 单页模式：内容在 ZoomableFrameLayout 内，底 inset 用 pdfContainer padding 底
        val sideL = binding.pdfContainer.paddingLeft
        val sideR = binding.pdfContainer.paddingRight
        if (pageMode == PdfPageMode.SINGLE) {
            if (binding.pdfContainer.paddingBottom != pad) {
                binding.pdfContainer.setPadding(sideL, 0, sideR, pad)
            }
        } else if (binding.pdfContainer.paddingBottom != 0) {
            binding.pdfContainer.setPadding(sideL, 0, sideR, 0)
        }
    }

    /** 旋转/切换视角后统一重铺；清掉错误宽度的 tile，防止长图压扁 */
    private fun relayoutAfterOrientationChange() {
        if (!::binding.isInitialized) return
        val keepMenu = chromeVisible
        val continuousSnap = if (pageMode == PdfPageMode.CONTINUOUS) {
            binding.pdfContainer.snapshotContinuousTransform()
        } else {
            null
        }
        binding.pdfContainer.scheduleContinuousTransformRestore(continuousSnap)
        val cfg = resources.configuration
        ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
            "relayout start mode=${AppSettings.pdfOrientationMode(this)} " +
                "pageMode=$pageMode page=$pageIndex " +
                "cfg=${cfg.screenWidthDp}x${cfg.screenHeightDp} " +
                "root=${binding.root.width}x${binding.root.height} " +
                "container=${binding.pdfContainer.width}x${binding.pdfContainer.height} " +
                "contSnap=$continuousSnap " +
                "isLand=${isLandscape()} winLand=${isWindowLandscape()}",
        )
        sanitizeBottomChrome()
        // 先清左右 padding，再按模式重算竖栏（手机通常为 0）
        binding.pdfContainer.setPadding(0, 0, 0, 0)
        binding.pdfContainer.allowTallZoomTarget = needsTallSinglePageZoomHost()
        applyPortraitColumnLayout()
        if (keepMenu) chromeVisible = true
        applyChromeVisibility()
        if (chromeVisible) forceMenuLayout(preservePage = true)
        // 横竖屏切换：废弃旧宽度的 tile / 整页缓存，避免压扁
        tileCache.evictAll()
        bitmapCache.evictAll()
        updatePdfZoomChrome()
        // 重算全部页高表（宽度变了）
        for (i in 0 until pageCount) {
            rendererPageSize[i]?.let { recordPageItemHeight(i, it.first, it.second) }
        }
        when (pageMode) {
            PdfPageMode.SINGLE -> {
                // 单页：旋转后重渲 + 重置到 1x 顶对齐，避免竖屏 pan/zoom 带到横屏裁切
                // （横屏 fit-width 长页靠 pan 看全页，旧 pan 会导致「显示不全」）
                binding.pdfContainer.resetZoom(notify = false)
                if (pageCount > 0) {
                    binding.pdfContainer.post {
                        if (isFinishing || isDestroyed) return@post
                        showSinglePage(pageIndex)
                        binding.ivPdfPage.post {
                            applySinglePageImageMatrix()
                            // 等加高 layout 完成后再 reset，否则 pan 边界仍按旧高度
                            binding.ivPdfPage.post {
                                binding.pdfContainer.resetZoom(notify = true)
                                ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
                                    "relayout single done " +
                                        "container=${binding.pdfContainer.width}x${binding.pdfContainer.height} " +
                                        "iv=${binding.ivPdfPage.width}x${binding.ivPdfPage.height} " +
                                        "lpH=${binding.ivPdfPage.layoutParams.height} " +
                                        "zoom=${binding.pdfContainer.contentZoom} " +
                                        "pan=(${binding.pdfContainer.getPanX()},${binding.pdfContainer.getPanY()}) " +
                                        "canPan=${binding.pdfContainer.canPanContent()}",
                                )
                            }
                        }
                    }
                }
            }
            PdfPageMode.CONTINUOUS -> {
                // 连续：保持 zoom 与水平 pan 比例；竖向滚动位置由页高表保留
                binding.rvPdfPages.adapter?.notifyDataSetChanged()
                binding.rvPdfPages.post {
                    if (isFinishing || isDestroyed) return@post
                    continuousSnap?.let { binding.pdfContainer.restoreContinuousTransform(it) }
                    refreshVisiblePageTiles(forceRender = true)
                    updatePdfZoomChrome()
                    syncPdfContentBottomInset()
                    ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
                        "relayout continuous done " +
                            "container=${binding.pdfContainer.width}x${binding.pdfContainer.height} " +
                            "rv=${binding.rvPdfPages.width}x${binding.rvPdfPages.height} " +
                            "zoom=${binding.pdfContainer.contentZoom} " +
                            "pan=(${binding.pdfContainer.getPanX()},${binding.pdfContainer.getPanY()})",
                    )
                }
            }
        }
        binding.root.requestLayout()
        binding.bottomChrome.post {
            if (isFinishing || isDestroyed) return@post
            sanitizeBottomChrome()
            if (keepMenu) {
                chromeVisible = true
                applyChromeVisibility()
                forceMenuLayout(preservePage = true)
            }
            syncPdfContentBottomInset()
            binding.pdfContainer.requestLayout()
            // 旋转后重算高亮，避免横屏坐标错位
            if (hlPage >= 0) refreshHighlightOverlay()
            if (hasTextSelection()) refreshSelectionOverlay()
            ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
                "relayout postChrome " +
                    "container=${binding.pdfContainer.width}x${binding.pdfContainer.height} " +
                    "iv=${binding.ivPdfPage.width}x${binding.ivPdfPage.height}",
            )
        }
    }

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
        pendingPdfOrientRelayout?.let { binding.root.removeCallbacks(it) }
        val r = Runnable {
            pendingPdfOrientRelayout = null
            if (isFinishing || isDestroyed) return@Runnable
            sanitizeBottomChrome()
            relayoutAfterOrientationChange()
        }
        pendingPdfOrientRelayout = r
        binding.root.post(r)
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
    private fun forceMenuLayout(preservePage: Boolean = false) {
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
    private fun mostVisiblePage(): Int {
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

    private fun updatePdfBookmarkButton() {
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

    private fun previewFromCache(page: Int): String? {
        fun fromChars(chars: List<PdfTextExtractor.PdfChar>?): String? {
            if (chars.isNullOrEmpty()) return null
            val s = buildString {
                for (c in chars) {
                    if (c.char == '\n' || c.char == '\r') append(' ') else append(c.char)
                    if (length >= 160) break
                }
            }.replace(Regex("\\s+"), " ").trim()
            return s.take(120).ifBlank { null }
        }
        return fromChars(pageChars[page]) ?: fromChars(rawPageCache[page])
    }

    /** 本页文字预览（约 120 字）；可能触发 PDFBox 抽字，勿在主线程调用 */
    private fun extractPagePreview(page: Int): String {
        previewFromCache(page)?.let { return it }
        val uriStr = intent.getStringExtra(EXTRA_URI) ?: return getString(R.string.pdf_bookmark_no_text)
        val extracted = runCatching {
            PdfTextExtractor.extractPagesRaw(this, Uri.parse(uriStr), listOf(page))[page]
        }.getOrNull()
        fun fromChars(chars: List<PdfTextExtractor.PdfChar>?): String? {
            if (chars.isNullOrEmpty()) return null
            val s = buildString {
                for (c in chars) {
                    if (c.char == '\n' || c.char == '\r') append(' ') else append(c.char)
                    if (length >= 160) break
                }
            }.replace(Regex("\\s+"), " ").trim()
            return s.take(120).ifBlank { null }
        }
        return fromChars(extracted) ?: getString(R.string.pdf_bookmark_no_text)
    }

    /**
     * 打开 PDF 后预加载目录到 [outlineRoots]（磁盘缓存优先，否则从会话 PDFBox 解析）。
     */
    private fun preloadOutlineAsync(uri: Uri) {
        if (outlineLoading) return
        // 已有内存结果
        outlineRoots?.let { return }
        // 先试磁盘/全局内存缓存（快）
        val hit = com.whj.reader.data.PdfOutlineCache.get(this, uri)
        if (hit != null) {
            outlineRoots = hit
            ReaderLog.i(ReaderLog.Module.PDF, "outline memory from cache nodes=${hit.size}")
            return
        }
        outlineLoading = true
        lifecycleScope.launch {
            val roots = withContext(Dispatchers.IO) {
                try {
                    // 会话内解析，避免再整本 load
                    val fromSession = PdfTextExtractor.withSessionDocument { doc ->
                        com.whj.reader.data.PdfOutlineLoader.loadFromDocument(doc)
                    }
                    val list = fromSession
                        ?: com.whj.reader.data.PdfOutlineCache.loadOrParse(
                            this@PdfReadingActivity,
                            uri,
                        )
                    com.whj.reader.data.PdfOutlineCache.put(this@PdfReadingActivity, uri, list)
                    list
                } catch (t: Throwable) {
                    ReaderLog.e(ReaderLog.Module.PDF, "preload outline", t)
                    emptyList()
                }
            }
            outlineLoading = false
            if (isFinishing || isDestroyed) return@launch
            outlineRoots = roots
            ReaderLog.i(ReaderLog.Module.PDF, "outline preloaded nodes=${roots.size}")
        }
    }

    /** 目录（树）+ 书签，可滑动切换；优先用打开时已缓存的大纲 */
    private fun showPageToc() {
        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr.isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pdf_toc_title)
                .setMessage(R.string.pdf_toc_empty)
                .setPositiveButton(R.string.confirm, null)
                .show()
            return
        }
        val uri = Uri.parse(uriStr)
        // 已在内存：立刻展示
        outlineRoots?.let { roots ->
            try {
                showPdfTocAndBookmarkSheet(roots)
            } catch (t: Throwable) {
                ReaderLog.e(ReaderLog.Module.PDF, "show toc UI failed", t)
                AlertDialog.Builder(this)
                    .setTitle(R.string.pdf_toc_title)
                    .setMessage(R.string.pdf_toc_empty)
                    .setPositiveButton(R.string.confirm, null)
                    .show()
            }
            return
        }
        // 尚未预加载完：提示并等待
        if (outlineLoading) {
            Toasts.show(this, R.string.pdf_toc_loading)
        } else {
            // 异常路径：补一次预加载
            preloadOutlineAsync(uri)
            Toasts.show(this, R.string.pdf_toc_loading)
        }
        lifecycleScope.launch {
            // 等预加载结束（最多约数秒）
            var wait = 0
            while (outlineRoots == null && wait < 80) {
                kotlinx.coroutines.delay(50)
                wait++
            }
            val roots = outlineRoots
                ?: withContext(Dispatchers.IO) {
                    runCatching {
                        com.whj.reader.data.PdfOutlineCache.loadOrParse(
                            this@PdfReadingActivity,
                            uri,
                        )
                    }.getOrDefault(emptyList())
                }.also { outlineRoots = it }
            if (isFinishing || isDestroyed) return@launch
            try {
                showPdfTocAndBookmarkSheet(roots)
            } catch (t: Throwable) {
                ReaderLog.e(ReaderLog.Module.PDF, "show toc UI failed", t)
                AlertDialog.Builder(this@PdfReadingActivity)
                    .setTitle(R.string.pdf_toc_title)
                    .setMessage(R.string.pdf_toc_empty)
                    .setPositiveButton(R.string.confirm, null)
                    .show()
            }
        }
    }

    private fun showPdfTocAndBookmarkSheet(roots: List<com.whj.reader.data.PdfOutlineLoader.Node>) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheet = com.whj.reader.databinding.SheetTocBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        val cur = mostVisiblePage()
        fun jumpPage(page: Int) {
            dialog.dismiss()
            // 目录/书签跳转也记入历史，便于顶栏后退
            navigateToPageWithHistory(page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
        }

        // 页 0：目录树
        val outlineAdapter = if (roots.isNotEmpty()) {
            com.whj.reader.ui.PdfTocAdapter(
                roots = roots,
                expanded = com.whj.reader.data.PdfOutlineLoader.defaultExpanded(roots, cur),
                currentPage = cur,
                onOpenPage = { page -> jumpPage(page) },
            )
        } else {
            null
        }

        // 页 1：书签
        lateinit var bookmarkAdapter: com.whj.reader.ui.TocAdapter
        bookmarkAdapter = com.whj.reader.ui.TocAdapter(
            onClick = { item ->
                val page = (item as? com.whj.reader.ui.TocItem.BookmarkItem)
                    ?.bookmark?.paragraphIndex ?: return@TocAdapter
                jumpPage(page)
            },
            onDeleteBookmark = { bm ->
                com.whj.reader.data.BookmarkStore.remove(this, bm.fileKey, bm.paragraphIndex)
                val items = com.whj.reader.data.BookmarkStore.list(this, fileKey)
                    .map { com.whj.reader.ui.TocItem.BookmarkItem(it) }
                bookmarkAdapter.submit(items, cur, pageCount)
                updatePdfBookmarkButton()
                Toasts.show(this, R.string.bookmark_removed)
            },
            totalParagraphs = pageCount,
            bookmarkAsPage = true,
        )
        bookmarkAdapter.submit(
            com.whj.reader.data.BookmarkStore.list(this, fileKey)
                .map { com.whj.reader.ui.TocItem.BookmarkItem(it) },
            cur,
            pageCount,
        )

        val titles = listOf(getString(R.string.toc), getString(R.string.bookmark))
        sheet.vpToc.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = 2

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val page = layoutInflater.inflate(R.layout.page_toc_list, parent, false)
                return object : RecyclerView.ViewHolder(page) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val page = holder.itemView
                val rv = page.findViewById<RecyclerView>(R.id.rvList)
                val empty = page.findViewById<android.widget.TextView>(R.id.tvEmpty)
                if (rv.layoutManager == null) {
                    rv.layoutManager = LinearLayoutManager(this@PdfReadingActivity)
                }
                com.whj.reader.ui.TocVpScrollHelper.attachVerticalList(rv, sheet.vpToc)
                if (position == 0) {
                    if (outlineAdapter != null) {
                        rv.adapter = outlineAdapter
                        empty.isVisible = false
                        rv.isVisible = true
                    } else {
                        rv.adapter = null
                        empty.isVisible = true
                        rv.isVisible = false
                        empty.setText(R.string.pdf_toc_empty)
                    }
                } else {
                    rv.adapter = bookmarkAdapter
                    fun sync() {
                        val n = bookmarkAdapter.itemCount
                        empty.isVisible = n == 0
                        rv.isVisible = n > 0
                        empty.setText(R.string.bookmark_empty)
                    }
                    sync()
                    if (page.getTag(R.id.rvList) !== bookmarkAdapter) {
                        page.setTag(R.id.rvList, bookmarkAdapter)
                        bookmarkAdapter.registerAdapterDataObserver(
                            object : RecyclerView.AdapterDataObserver() {
                                override fun onChanged() = sync()
                            },
                        )
                    }
                }
            }
        }
        com.google.android.material.tabs.TabLayoutMediator(sheet.tabLayout, sheet.vpToc) { tab, pos ->
            tab.text = titles[pos]
        }.attach()

        dialog.setOnShowListener {
            runCatching {
                val bottomSheet = dialog.findViewById<View>(
                    com.google.android.material.R.id.design_bottom_sheet,
                ) ?: return@setOnShowListener
                val maxH = (resources.displayMetrics.heightPixels * 0.92f).toInt()
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply { height = maxH }
                bottomSheet.requestLayout()
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior
                    .from(bottomSheet)
                behavior.skipCollapsed = true
                behavior.isFitToContents = false
                behavior.expandedOffset =
                    (resources.displayMetrics.heightPixels - maxH).coerceAtLeast(0)
                behavior.state =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    // ─── 触摸 ─────────────────────────────────────────────

    private fun setupPageTouch() {
        // 单击 / 长按 / 缩放后平移 由 ZoomableFrameLayout 统一处理
        // 未缩放时 RV 仍自行滚动；单页模式无滚动手势
        binding.rvPdfPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // 与 setupContinuousList 的滚动回调互补：确保选区/TTS 高亮跟随
                if (selPage >= 0) refreshSelectionOverlay()
                if (hlPage >= 0) refreshHighlightOverlay()
            }
        })

        binding.pdfContainer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            val h = v.height
            if (renderer != null && pageCount > 0 && w > 0 && h > 0 &&
                pageMode == PdfPageMode.SINGLE &&
                !singlePageRendering &&
                (w != lastRenderW || h != lastRenderH)
            ) {
                lastRenderW = w
                lastRenderH = h
                showSinglePage(pageIndex)
            }
            refreshSelectionOverlay()
        }
    }

    /** 中部轻点：开关菜单（侧边翻页已由 onSideTapImmediate 处理） */
    private fun handleTap(x: Float, width: Float) {
        if (hasTextSelection()) {
            clearTextSelection()
            return
        }
        if (binding.settingsPanelContainer.isVisible) {
            binding.settingsPanelContainer.isVisible = false
            return
        }
        // 侧边理论上不会走到这里；菜单打开时只关菜单
        when {
            x < width / 3f -> {
                if (chromeVisible) {
                    hideChrome()
                    return
                }
                pageTurn(false)
            }
            x > width * 2f / 3f -> {
                if (chromeVisible) {
                    hideChrome()
                    return
                }
                pageTurn(true)
            }
            else -> toggleChrome()
        }
    }

    // ─── 书内链接 ─────────────────────────────────────────

    private fun loadPdfLinksAsync(uri: Uri) {
        lifecycleScope.launch {
            val links = withContext(Dispatchers.IO) {
                if (!PdfTextExtractor.hasSession(uri)) {
                    PdfTextExtractor.openSession(this@PdfReadingActivity, uri)
                }
                PdfTextExtractor.extractLinksFromSession()
            }
            if (isFinishing || isDestroyed) return@launch
            pageLinks = links
            ReaderLog.i(ReaderLog.Module.PDF,
                "links ready pages=${links.size} total=${links.values.sumOf { it.size }}",
            )
        }
    }

    /**
     * 点击是否命中书内/外部链接。
     * @return true 已处理（不再开关菜单）
     */
    private fun tryHandlePdfLinkTap(containerX: Float, containerY: Float): Boolean {
        if (pageLinks.isEmpty()) return false
        if (hasTextSelection()) return false
        val hit = hitTestLink(containerX, containerY) ?: return false
        when {
            hit.targetPage != null -> {
                val target = hit.targetPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                if (target == currentVisiblePage()) return true
                navigateToPageWithHistory(target)
                Toasts.show(this, getString(R.string.pdf_link_jumped, target + 1))
                return true
            }
            !hit.uri.isNullOrBlank() -> {
                confirmOpenExternalUri(hit.uri)
                return true
            }
        }
        return false
    }

    private fun hitTestLink(containerX: Float, containerY: Float): PdfLinkIndex.Link? {
        val content = binding.pdfContainer.mapToContent(containerX, containerY)
        return when (pageMode) {
            PdfPageMode.SINGLE -> {
                val page = pageIndex
                val links = pageLinks[page] ?: return null
                val pageXY = viewToPageCoords(binding.ivPdfPage, content.x, content.y, page)
                    ?: return null
                links.firstOrNull { it.contains(pageXY[0], pageXY[1]) }
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = binding.rvPdfPages
                val child = rv.findChildViewUnder(content.x, content.y) ?: return null
                val pos = rv.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) return null
                val links = pageLinks[pos] ?: return null
                val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: return null
                val localX = content.x - child.left - surface.left
                val localY = content.y - child.top - surface.top
                val pageXY = surface.viewToPage(localX, localY) ?: return null
                links.firstOrNull { it.contains(pageXY[0], pageXY[1]) }
            }
        }
    }

    private fun navigateToPageWithHistory(targetPage: Int) {
        val from = currentVisiblePage()
        if (from == targetPage) return
        navBackStack.addLast(from)
        navForwardStack.clear()
        if (chromeVisible) hideChrome()
        restorePosition(targetPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
        updateHistNavButtons()
        updateProgressLabel()
        updatePdfBookmarkButton()
    }

    private fun navigateHistoryBack() {
        if (navBackStack.isEmpty()) return
        val cur = currentVisiblePage()
        val target = navBackStack.removeLast()
        navForwardStack.addLast(cur)
        restorePosition(target.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
        updateHistNavButtons()
        updateProgressLabel()
        updatePdfBookmarkButton()
    }

    private fun navigateHistoryForward() {
        if (navForwardStack.isEmpty()) return
        val cur = currentVisiblePage()
        val target = navForwardStack.removeLast()
        navBackStack.addLast(cur)
        restorePosition(target.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
        updateHistNavButtons()
        updateProgressLabel()
        updatePdfBookmarkButton()
    }

    private fun updateHistNavButtons() {
        if (!::binding.isInitialized) return
        val canBack = navBackStack.isNotEmpty()
        val canFwd = navForwardStack.isNotEmpty()
        binding.btnHistBack.isEnabled = canBack
        binding.btnHistBack.alpha = if (canBack) 1f else 0.35f
        binding.btnHistForward.isEnabled = canFwd
        binding.btnHistForward.alpha = if (canFwd) 1f else 0.35f
    }

    private fun confirmOpenExternalUri(uriStr: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pdf_link_external)
            .setMessage(uriStr)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                        ),
                    )
                }.onFailure {
                    Toasts.show(this, it.message ?: "error")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ─── 长按选字 / 坐标映射 ──────────────────────────────

    private fun hasTextSelection(): Boolean =
        selPage >= 0 && selStart >= 0 && selEnd >= 0 && selStart <= selEnd

    private fun clearTextSelection() {
        textActionMode?.finish()
        textActionMode = null
        selPage = -1
        selStart = -1
        selEnd = -1
        selAnchor = -1
        pdfDraggingHandle = null
        pdfSelectionDragActive = false
        pdfEdgeScrollPosted = false
        pdfEdgeScrollState.reset()
        binding.pdfSelectionOverlay.clearSelection()
    }

    private fun beginTextSelection(containerX: Float, containerY: Float) {
        val page = currentVisiblePage()
        val need = pagesForTtsStart(page)
        // 仅缺缓存时再提取；已提取但无字不要递归（否则 StackOverflow 闪退）
        val uncached = need.filter { it !in rawPageCache }
        if (uncached.isNotEmpty()) {
            ensurePagesExtracted(
                pages = need,
                showToast = true,
                preserveTtsPosition = false,
            ) {
                if (isFinishing || isDestroyed) return@ensurePagesExtracted
                beginTextSelectionAfterReady(containerX, containerY)
            }
            return
        }
        // 缓存有页但 pageChars 空：可能切边后未重建
        if (pageChars[page].isNullOrEmpty() && rawPageCache.isNotEmpty()) {
            runCatching { rebuildTextFromCache(preserveTtsPosition = false) }
        }
        beginTextSelectionAfterReady(containerX, containerY)
    }

    /** 文字已就绪（或确认无字）后进入选区，禁止再触发提取递归 */
    private fun beginTextSelectionAfterReady(containerX: Float, containerY: Float) {
        val page = currentVisiblePage()
        // 无字 / 未命中：静默返回，不 Toast（避免长按打扰）
        if (pageChars[page].isNullOrEmpty()) return
        val hit = runCatching { hitTestChar(containerX, containerY) }.getOrNull() ?: return
        selPage = hit.first
        selAnchor = hit.second
        selStart = hit.second
        selEnd = hit.second
        runCatching {
            refreshSelectionOverlay()
            showTextActionMode()
        }.onFailure {
            ReaderLog.e(ReaderLog.Module.PDF, "begin selection UI failed", it)
            clearTextSelection()
        }
    }

    private fun extendTextSelection(containerX: Float, containerY: Float) {
        if (selPage < 0 || selAnchor < 0) return
        val hit = hitTestChar(containerX, containerY) ?: return
        if (hit.first != selPage) return
        selStart = min(selAnchor, hit.second)
        selEnd = max(selAnchor, hit.second)
        refreshSelectionOverlay()
    }

    private fun adjustPdfSelectionHandle(which: TextSelectionHandles.Which, x: Float, y: Float) {
        if (selPage < 0) return
        val hit = hitTestChar(x, y) ?: return
        if (hit.first != selPage) return
        when (which) {
            TextSelectionHandles.Which.START -> selStart = hit.second
            TextSelectionHandles.Which.END -> selEnd = hit.second
        }
        if (selStart > selEnd) {
            val t = selStart
            selStart = selEnd
            selEnd = t
        }
        refreshSelectionOverlay()
    }

    private fun autoScrollPdfWhileSelecting(containerY: Float) {
        val container = binding.pdfContainer
        val h = container.height.toFloat()
        if (h <= 1f) return
        val unit = 24f * resources.displayMetrics.density
        val step = TextSelectionHandles.edgeScrollStep(
            containerY,
            h,
            unit,
            resources.displayMetrics.density,
            pdfEdgeScrollState,
        )
        if (step == 0f) return
        when (pageMode) {
            PdfPageMode.CONTINUOUS -> {
                binding.rvPdfPages.scrollBy(0, step.toInt().coerceIn(-60, 60))
                updateProgressLabel()
            }
            PdfPageMode.SINGLE -> {
                if (container.canPanContent()) {
                    container.setTransform(
                        container.contentZoom,
                        container.getPanX(),
                        container.getPanY() + step,
                        false,
                    )
                }
            }
        }
        if (hlPage >= 0) refreshHighlightOverlay()
        refreshSelectionOverlay()
    }

    private fun ensurePdfSelectionEdgeScrollLoop() {
        if (pdfEdgeScrollPosted) return
        pdfEdgeScrollPosted = true
        binding.pdfContainer.postOnAnimation { runPdfSelectionEdgeScrollLoop() }
    }

    private fun runPdfSelectionEdgeScrollLoop() {
        if (!pdfSelectionDragActive || isFinishing || isDestroyed) {
            pdfEdgeScrollPosted = false
            return
        }
        autoScrollPdfWhileSelecting(pdfSelDragY)
        val handle = pdfDraggingHandle
        if (handle != null) {
            adjustPdfSelectionHandle(handle, pdfSelDragX, pdfSelDragY)
        } else {
            extendTextSelection(pdfSelDragX, pdfSelDragY)
        }
        binding.pdfContainer.postOnAnimation { runPdfSelectionEdgeScrollLoop() }
    }

    /** 命中：pageIndex + charIndexOnPage */
    private fun hitTestChar(containerX: Float, containerY: Float): Pair<Int, Int>? {
        val content = binding.pdfContainer.mapToContent(containerX, containerY)
        return when (pageMode) {
            PdfPageMode.SINGLE -> {
                val page = pageIndex
                val chars = pageChars[page] ?: return null
                val pageXY = viewToPageCoords(binding.ivPdfPage, content.x, content.y, page)
                    ?: return null
                nearestCharIndex(chars, pageXY[0], pageXY[1])?.let { page to it }
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = binding.rvPdfPages
                val child = rv.findChildViewUnder(content.x, content.y) ?: return null
                val pos = rv.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) return null
                val chars = pageChars[pos] ?: return null
                val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: return null
                val localX = content.x - child.left - surface.left
                val localY = content.y - child.top - surface.top
                val pageXY = surface.viewToPage(localX, localY) ?: return null
                nearestCharIndex(chars, pageXY[0], pageXY[1])?.let { pos to it }
            }
        }
    }

    /**
     * 取页在 PDFBox 与 PdfRenderer 下的尺寸，字符坐标按 PDFBox 尺寸；
     * 映射到图时用「归一化 0~1」再乘到裁剪后的位图区域，避免两边尺寸不一致。
     */
    private fun pageLogicalSize(pageIndex: Int): Pair<Float, Float> {
        val sample = pageChars[pageIndex]?.firstOrNull()
        if (sample != null) {
            return sample.pageWidth to sample.pageHeight
        }
        rendererPageSize[pageIndex]?.let { return it }
        val r = renderer ?: return 1f to 1f
        return try {
            synchronized(renderLock) {
                currentPage?.close()
                currentPage = null
                val page = r.openPage(pageIndex.coerceIn(0, r.pageCount - 1))
                currentPage = page
                val sz = page.width.toFloat() to page.height.toFloat()
                page.close()
                currentPage = null
                rendererPageSize[pageIndex] = sz
                sz
            }
        } catch (_: Exception) {
            1f to 1f
        }
    }

    /**
     * 单页 ImageView 矩阵：横屏按宽铺满（顶对齐；内容加高后由 ZoomableFrameLayout pan 看全页），
     * 竖屏 fitCenter。
     */
    private fun applySinglePageImageMatrix() {
        val iv = binding.ivPdfPage
        val host = binding.pdfContainer
        val d = iv.drawable ?: return
        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        // 用容器尺寸：ImageView 可能刚被加高，vw/vh 要以宿主视口为准
        val vw = host.width.toFloat().coerceAtLeast(1f)
        val vh = host.height.toFloat().coerceAtLeast(1f)
        if (vw <= 1f || vh <= 1f) {
            ReaderLog.w(ReaderLog.Module.PDF_ORIENT, "applyMatrix skip host=${vw}x$vh")
            return
        }
        val landscape = vw > vh
        val fitByWidth = singlePageFitByWidth(dw, dh, vw, vh)
        val scale = if (fitByWidth) vw / dw else min(vw / dw, vh / dh)
        val contentH = dh * scale
        val needTall = fitByWidth && contentH > vh + 1f
        // 超长页：layout 高度与 matrix 只应用一次缩放（layoutH = dh×scale，matrix = scale）
        val matrixScale = scale
        val layoutH = if (needTall) {
            (dh * matrixScale).toInt().coerceAtLeast(1)
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        val lp = iv.layoutParams
        val wantW = ViewGroup.LayoutParams.MATCH_PARENT
        if (lp != null && (lp.height != layoutH || lp.width != wantW)) {
            lp.width = wantW
            lp.height = layoutH
            iv.layoutParams = lp
            ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
                "ivLayout tall=$needTall height=$layoutH bmp=${dw.toInt()}x${dh.toInt()} " +
                    "matrixScale=$matrixScale host=${vw.toInt()}x${vh.toInt()}",
            )
        }
        val m = Matrix()
        m.setScale(matrixScale, matrixScale)
        val contentWVis = dw * matrixScale
        val contentHVis = dh * matrixScale
        // 加高后 iv 高度=contentH，矩阵从 (0,0) 铺满内容即可；未加高则居中/顶对齐
        val dx = if (fitByWidth) 0f else (vw - contentWVis) / 2f
        val dy = when {
            needTall -> 0f
            fitByWidth && contentHVis > vh -> 0f
            else -> (vh - contentHVis) / 2f
        }
        m.postTranslate(dx, dy)
        iv.scaleType = ImageView.ScaleType.MATRIX
        iv.imageMatrix = m
        ReaderLog.i(ReaderLog.Module.PDF_ORIENT,
            "applyMatrix land=$landscape matrixScale=$matrixScale " +
                "content=${contentWVis.toInt()}x${contentHVis.toInt()} " +
                "host=${vw.toInt()}x${vh.toInt()} " +
                "iv=${iv.width}x${iv.height} lpH=$layoutH dx=$dx dy=$dy " +
                "canPan=${host.canPanContent()} zoom=${host.contentZoom} " +
                "pan=(${host.getPanX()},${host.getPanY()}) " +
                "bounds=${host.verticalPanLimits()}",
        )
        updateSinglePageTallHostFlag()
        updatePdfZoomLimitsForSinglePage()
        // 边界变更后重夹 pan，避免旧 panY 落在错误区间
        host.setTransform(host.contentZoom, host.getPanX(), host.getPanY(), notify = false)
    }

    /**
     * ImageView 本地坐标 → PDF 页坐标（左上原点、Y 向下，与 [PdfTextExtractor.PdfChar] 一致）。
     */
    private fun viewToPageCoords(
        iv: ImageView,
        localX: Float,
        localY: Float,
        pageIndex: Int,
    ): FloatArray? {
        if (singlePageUsesTiles && iv === binding.ivPdfPage) {
            val surface = singlePageSurface ?: return null
            return viewToPageCoordsOnSurface(surface, localX, localY, pageIndex)
        }
        val d = iv.drawable ?: return null
        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val vw = iv.width.toFloat().coerceAtLeast(1f)
        val vh = iv.height.toFloat().coerceAtLeast(1f)
        val fitByWidth = singlePageFitByWidth(dw, dh, vw, vh)
        val scale = if (fitByWidth) vw / dw else min(vw / dw, vh / dh)
        val ox = if (fitByWidth) 0f else (vw - dw * scale) / 2f
        val oy = when {
            fitByWidth && dh * scale > vh -> 0f
            else -> (vh - dh * scale) / 2f
        }
        val bx = (localX - ox) / scale
        val by = (localY - oy) / scale
        if (bx < -4f || by < -4f || bx > dw + 4f || by > dh + 4f) return null

        val (pageW, pageH) = pageLogicalSize(pageIndex)
        val margins = cropForPage(pageIndex)
        val cl = margins[0]
        val ct = margins[1]
        val cr = margins[2]
        val cb = margins[3]
        val srcW = pageW * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = pageH * (1f - ct - cb).coerceAtLeast(0.2f)
        // 位图 = 裁剪后内容；归一化到裁剪区再回到全页
        val pageX = pageW * cl + (bx / dw) * srcW
        val pageY = pageH * ct + (by / dh) * srcH
        return floatArrayOf(pageX, pageY)
    }

    private fun viewToPageCoordsOnSurface(
        surface: PdfPageSurface,
        localX: Float,
        localY: Float,
        pageIndex: Int,
    ): FloatArray? {
        val vw = surface.width.toFloat().coerceAtLeast(1f)
        val displayH = surface.logicalHeight.coerceAtLeast(1).toFloat()
        val (pageW, pageH) = pageLogicalSize(pageIndex)
        val margins = cropForPage(pageIndex)
        val cl = margins[0]
        val ct = margins[1]
        val cr = margins[2]
        val cb = margins[3]
        val srcW = pageW * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = pageH * (1f - ct - cb).coerceAtLeast(0.2f)
        if (localX < -4f || localY < -4f || localX > vw + 4f || localY > displayH + 4f) return null
        val pageX = pageW * cl + (localX / vw) * srcW
        val pageY = pageH * ct + (localY / displayH) * srcH
        return floatArrayOf(pageX, pageY)
    }

    private fun nearestCharIndex(
        chars: List<PdfTextExtractor.PdfChar>,
        pageX: Float,
        pageY: Float,
    ): Int? {
        if (chars.isEmpty()) return null
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (c in chars) {
            if (c.char.isWhitespace()) continue
            if (c.contains(pageX, pageY, pad = 8f)) {
                return c.indexOnPage
            }
            val dx = c.midX - pageX
            val dy = c.midY - pageY
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = c.indexOnPage
            }
        }
        // 自适应：按页宽 8% 作命中半径
        val pageW = chars.first().pageWidth.coerceAtLeast(1f)
        val thr = (pageW * 0.08f).coerceIn(24f, 80f)
        return if (best >= 0 && bestDist < thr * thr) best else null
    }

    private fun selectedText(): String {
        if (!hasTextSelection()) return ""
        val chars = pageChars[selPage] ?: return ""
        val a = min(selStart, selEnd)
        val b = max(selStart, selEnd)
        val sb = StringBuilder()
        for (c in chars) {
            if (c.indexOnPage in a..b) sb.append(c.char)
        }
        return sb.toString()
    }

    private fun refreshSelectionOverlay() {
        if (!hasTextSelection()) {
            binding.pdfSelectionOverlay.clearSelection()
            return
        }
        val a = min(selStart, selEnd)
        val b = max(selStart, selEnd)
        val rects = charRangeToContainerRects(selPage, a, b)
        val handles = selectionHandlePoints(rects)
        binding.pdfSelectionOverlay.setSelectionRects(
            rects,
            handles?.first,
            handles?.second,
        )
        invalidateTextSelectionActionMode()
    }

    private fun fillTextSelectionContentRect(out: Rect): Boolean {
        if (!hasTextSelection()) return false
        val rects = charRangeToContainerRects(
            selPage,
            min(selStart, selEnd),
            max(selStart, selEnd),
        )
        if (rects.isEmpty()) return false
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = Float.MIN_VALUE
        var b = Float.MIN_VALUE
        for (rf in rects) {
            l = min(l, rf.left)
            t = min(t, rf.top)
            r = max(r, rf.right)
            b = max(b, rf.bottom)
        }
        out.set(
            l.toInt().coerceAtLeast(0),
            t.toInt().coerceAtLeast(0),
            r.toInt().coerceAtLeast(l.toInt() + 1),
            b.toInt().coerceAtLeast(t.toInt() + 1),
        )
        return true
    }

    private fun invalidateTextSelectionActionMode() {
        if (textActionMode == null || !hasTextSelection()) return
        textActionMode?.invalidateContentRect()
    }

    private fun selectionHandlePoints(rects: List<RectF>): Pair<PointF, PointF>? {
        if (rects.isEmpty()) return null
        val first = rects.first()
        val last = rects.last()
        return PointF(first.left, first.bottom) to PointF(last.right, last.bottom)
    }

    private fun refreshHighlightOverlay() {
        if (hlPage < 0 || hlStart < 0 || hlEnd < hlStart) {
            binding.pdfSelectionOverlay.clearHighlight()
            return
        }
        val rects = charRangeToContainerRects(hlPage, hlStart, hlEnd)
        binding.pdfSelectionOverlay.setHighlightRects(rects)
    }

    /** 将页内字符区间映射为容器坐标系矩形列表（合并同行） */
    private fun charRangeToContainerRects(
        page: Int,
        startIdx: Int,
        endIdx: Int,
    ): List<RectF> {
        val chars = pageChars[page] ?: return emptyList()
        val selected = chars.filter { it.indexOnPage in startIdx..endIdx && !it.char.isWhitespace() }
        if (selected.isEmpty()) return emptyList()
        val contentRects = ArrayList<RectF>()
        when (pageMode) {
            PdfPageMode.SINGLE -> {
                val iv = binding.ivPdfPage
                for (line in mergeLineRects(selected)) {
                    pageRectToContent(iv, page, line, 0f, 0f)?.let { contentRects.add(it) }
                }
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = binding.rvPdfPages
                val lm = rv.layoutManager as? LinearLayoutManager ?: return emptyList()
                val child = lm.findViewByPosition(page) ?: return emptyList()
                val surface = child.findViewById<PdfPageSurface>(R.id.ivPage) ?: return emptyList()
                // 页 item 在 RV 内容坐标系中的偏移（RV 为 zoomTarget）
                val ox = child.left + surface.left.toFloat()
                val oy = child.top + surface.top.toFloat()
                for (line in mergeLineRects(selected)) {
                    val local = surface.pageRectToView(line)
                    contentRects.add(
                        RectF(
                            ox + local.left,
                            oy + local.top,
                            ox + local.right,
                            oy + local.bottom,
                        ),
                    )
                }
            }
        }
        return contentRects.map { r ->
            val p0 = contentToContainer(r.left, r.top)
            val p1 = contentToContainer(r.right, r.bottom)
            RectF(min(p0[0], p1[0]), min(p0[1], p1[1]), max(p0[0], p1[0]), max(p0[1], p1[1]))
        }
    }

    /** 同行字符合并为一条矩形，减少碎块 */
    private fun mergeLineRects(chars: List<PdfTextExtractor.PdfChar>): List<RectF> {
        if (chars.isEmpty()) return emptyList()
        val sorted = chars.sortedWith(compareBy({ it.top }, { it.left }))
        val avgH = sorted.map { (it.bottom - it.top).coerceAtLeast(1f) }.average().toFloat()
        val lineTol = avgH * 0.55f
        val lines = ArrayList<RectF>()
        var cur = RectF(sorted[0].left, sorted[0].top, sorted[0].right, sorted[0].bottom)
        var curMidY = sorted[0].midY
        for (i in 1 until sorted.size) {
            val c = sorted[i]
            if (abs(c.midY - curMidY) <= lineTol) {
                cur.left = min(cur.left, c.left)
                cur.right = max(cur.right, c.right)
                cur.top = min(cur.top, c.top)
                cur.bottom = max(cur.bottom, c.bottom)
                curMidY = (cur.top + cur.bottom) / 2f
            } else {
                lines.add(RectF(cur))
                cur.set(c.left, c.top, c.right, c.bottom)
                curMidY = c.midY
            }
        }
        lines.add(cur)
        return lines
    }

    /**
     * zoomTarget 内容坐标 → [pdfContainer] 子视图坐标（与选区/高亮 overlay 一致）。
     * 须计入 target 的 layout 位置（padding）与 scale/translation。
     */
    private fun contentToContainer(x: Float, y: Float): FloatArray {
        val container = binding.pdfContainer
        val target = container.zoomTarget
        val zoom = container.contentZoom.coerceAtLeast(0.01f)
        val panX = container.getPanX()
        val panY = container.getPanY()
        // pivot 在 (0,0)：屏幕 = target.layout + content * zoom + pan
        val tl = target?.left?.toFloat() ?: container.paddingLeft.toFloat()
        val tt = target?.top?.toFloat() ?: container.paddingTop.toFloat()
        return floatArrayOf(tl + x * zoom + panX, tt + y * zoom + panY)
    }

    /**
     * 页坐标矩形 → 单页 ImageView 本地坐标。
     * **必须与 [applySinglePageImageMatrix] 一致**：横屏 fit-width 顶对齐，竖屏 fitCenter。
     */
    private fun pageRectToContent(
        iv: ImageView,
        pageIndex: Int,
        pageRect: RectF,
        contentOffsetX: Float,
        contentOffsetY: Float,
    ): RectF? {
        val d = iv.drawable ?: return null
        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val vw = iv.width.toFloat().coerceAtLeast(1f)
        val vh = iv.height.toFloat().coerceAtLeast(1f)
        val landscape = vw > vh
        val scale = if (landscape) vw / dw else min(vw / dw, vh / dh)
        val ox = (vw - dw * scale) / 2f
        val oy = if (landscape && dh * scale > vh) 0f else (vh - dh * scale) / 2f
        val (pageW, pageH) = pageLogicalSize(pageIndex)
        val margins = cropForPage(pageIndex)
        val cl = margins[0]
        val ct = margins[1]
        val cr = margins[2]
        val cb = margins[3]
        val srcW = pageW * (1f - cl - cr).coerceAtLeast(0.2f)
        val srcH = pageH * (1f - ct - cb).coerceAtLeast(0.2f)
        val cropLeft = pageW * cl
        val cropTop = pageH * ct
        fun px(x: Float) = contentOffsetX + ox + ((x - cropLeft) / srcW) * dw * scale
        fun py(y: Float) = contentOffsetY + oy + ((y - cropTop) / srcH) * dh * scale
        return RectF(px(pageRect.left), py(pageRect.top), px(pageRect.right), py(pageRect.bottom))
    }

    private fun showTextActionMode() {
        if (!hasTextSelection()) return
        if (textActionMode != null) {
            invalidateTextSelectionActionMode()
            return
        }
        val callback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, R.string.pdf_select_copy)
                menu.add(0, 2, 1, R.string.pdf_select_read)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    1 -> {
                        val text = selectedText()
                        if (text.isNotEmpty()) {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("pdf", text))
                            Toasts.show(this@PdfReadingActivity, R.string.pdf_text_copied)
                        }
                        mode.finish()
                        clearTextSelection()
                        return true
                    }
                    2 -> {
                        mode.finish()
                        startTtsFromSelection()
                        return true
                    }
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                textActionMode = null
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (!fillTextSelectionContentRect(outRect)) {
                    outRect.set(
                        (view.width / 2 - 60).coerceAtLeast(0),
                        (view.height / 3).coerceAtLeast(0),
                        (view.width / 2 + 60).coerceAtMost(view.width.coerceAtLeast(1)),
                        (view.height / 3 + 40).coerceAtMost(view.height.coerceAtLeast(1)),
                    )
                }
            }
        }
        // 部分机型 FLOATING 异常；失败则回退普通 ActionMode
        textActionMode = runCatching {
            binding.pdfContainer.startActionMode(callback, ActionMode.TYPE_FLOATING)
        }.getOrNull() ?: runCatching {
            binding.pdfContainer.startActionMode(callback)
        }.getOrNull()
    }

    private fun toggleChrome() {
        if (chromeVisible) hideChrome() else showChrome()
    }

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

    private fun showChrome() {
        chromeVisible = true
        chromeShownAtMs = android.os.SystemClock.uptimeMillis()
        updateOrientMenuIcon()
        // 打开 8 图标菜单时收起 TTS 条（与 TXT 一致）
        applyChromeVisibility()
        binding.topBar.post { updatePdfBookmarkButton() }
    }

    private fun hideChrome() {
        if (!chromeVisible && !binding.readMenuHost.isVisible && !binding.topBar.isVisible) return
        chromeVisible = false
        applyChromeVisibility()
    }

    // ─── 外观 ─────────────────────────────────────────────

    private fun applyNightUi() {
        // 日间：标题栏 / 内容区 / 底栏白底；夜间保持深色。页间分隔线仍为黑色（item 布局）
        val bg = if (night) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        val bar = if (night) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
        val meta = if (night) 0xFF888888.toInt() else 0xFF666666.toInt()
        val contentBg = if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        binding.rootPdf.setBackgroundColor(bg)
        binding.readStatusBar.setBackgroundColor(bar)
        binding.bottomChrome.setBackgroundColor(bar)
        binding.tvReadTitle.setBackgroundColor(bar)
        binding.tvReadTitle.setTextColor(meta)
        binding.tvBattery.setTextColor(meta)
        binding.tvClock.setTextColor(meta)
        binding.tvProgress.setTextColor(meta)
        binding.tvLoading.setTextColor(if (night) 0xFFCCCCCC.toInt() else 0xFF666666.toInt())
        binding.tvLoading.setBackgroundColor(contentBg)
        window.statusBarColor = bar
        window.navigationBarColor = bar
        if (::binding.isInitialized) {
            binding.pdfFastScroll.setNight(night)
        }
        updatePdfZoomChrome()
    }

    /**
     * 缩放外观：缩小后页面两侧/外侧用纯黑；正常/放大时恢复日夜内容底色。
     */
    private fun updatePdfZoomChrome() {
        if (!::binding.isInitialized) return
        val z = binding.pdfContainer.contentZoom
        val darkExterior = z < 0.99f || night
        val contentBg = when {
            z < 0.99f -> 0xFF000000.toInt()
            night -> 0xFF000000.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        binding.pdfContainer.setBackgroundColor(contentBg)
        // 列表/页表面板在缩小时也用黑底，避免 item 白边露出
        if (pageMode == PdfPageMode.CONTINUOUS) {
            binding.rvPdfPages.setBackgroundColor(
                if (z < 0.99f) 0xFF000000.toInt() else contentBg,
            )
        }
        // 缩小露黑边 / 夜间：滚动手柄提亮，否则在黑底上看不见
        binding.pdfFastScroll.setOnDarkExterior(darkExterior)
    }

    private fun applyNightFilter(iv: ImageView) {
        if (night) {
            // 轻微反色/压暗，便于夜间看白底 PDF
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

    private fun applyNightFilterToVisibleSurfaces() {
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
    private fun hasDisplayCutout(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 28) return false
        val cutout = window.decorView.rootWindowInsets?.displayCutout
            ?: return false
        return cutout.boundingRects.isNotEmpty()
    }

    /**
     * 全屏开关；横屏始终全屏（见 [applyLandscapeFullscreenUi]）。
     */
    private fun applyImmersive() {
        applyLandscapeFullscreenUi()
        applyNightUi()
    }

    private var pendingPdfOrientRelayout: Runnable? = null

    private fun applyOrientationMode(
        mode: OrientationMode,
        allowSensor: Boolean = true,
        force: Boolean = false,
    ) {
        val fixed = if (mode == OrientationMode.AUTO) OrientationMode.PORTRAIT else mode
        val changed = OrientationHelper.apply(
            this,
            fixed,
            allowSensor = false,
            force = force,
        )
        // 单次合并重铺；去掉 force 二次 setOrientation 与 80ms 连闪
        if (!::binding.isInitialized) return
        pendingPdfOrientRelayout?.let { binding.root.removeCallbacks(it) }
        val r = Runnable {
            pendingPdfOrientRelayout = null
            if (isFinishing || isDestroyed) return@Runnable
            sanitizeBottomChrome()
            relayoutAfterOrientationChange()
        }
        pendingPdfOrientRelayout = r
        binding.root.postDelayed(r, if (changed) 16L else 0L)
    }

    // ─── 进度 / 状态栏 ────────────────────────────────────

    private fun saveProgress(page: Int) {
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
    private fun cachePdfFileSize(uriStr: String) {
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
    private fun updateProgressLabelLight() {
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

    private fun updateProgressLabel() {
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
    private fun computeScrollProgressPercent(): Int {
        if (pageCount <= 0) return 0
        return (progressFromHeightTable() * 100f).toInt().coerceIn(0, 100)
    }

    private fun updateClock() {
        binding.tvClock.text = clockFmt.format(Date())
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        binding.tvBattery.text = when {
            pct < 0 -> "--%"
            charging -> "⚡$pct%"
            else -> "$pct%"
        }
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
