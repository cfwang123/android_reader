package com.whj.reader

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.data.AppSettings
import com.whj.reader.data.BookFileType
import com.whj.reader.data.BookshelfStore
import com.whj.reader.data.PdfOcrCacheStore
import com.whj.reader.data.PdfOcrConverter
import com.whj.reader.data.PdfTextExtractor
import com.whj.reader.R
import com.whj.reader.databinding.ActivityPdfReadingBinding
import com.whj.reader.databinding.DialogPdfOcrBinding
import com.whj.reader.databinding.PanelPdfSettingsBinding
import com.whj.reader.databinding.PanelReadMenuBinding
import com.whj.reader.model.OrientationMode
import com.whj.reader.model.Paragraph
import com.whj.reader.model.PdfPageMode
import com.whj.reader.ocr.TfliteOcrEngine
import com.whj.reader.tts.TtsManager
import com.whj.reader.ui.PdfPageAdapter
import com.whj.reader.util.KeepScreenController
import com.whj.reader.util.OpenFailGuide
import com.whj.reader.util.OrientationHelper
import com.whj.reader.util.StorageAccess
import com.whj.reader.util.Toasts
import com.whj.reader.util.TtsVoicePicker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
        /** 连续模式 bitmap 页数上限 */
        private const val BITMAP_CACHE_PAGES = 5
        /** 当前页前后各保留几页 */
        private const val CACHE_KEEP_RADIUS = 2
        /** 渲染相对源页最大放大倍数（原 4，降以省内存） */
        private const val RENDER_MAX_SCALE = 2.2f
    }

    private lateinit var binding: ActivityPdfReadingBinding
    private lateinit var readMenu: PanelReadMenuBinding
    private lateinit var pdfSettings: PanelPdfSettingsBinding

    private var fileKey: String = ""
    private var displayTitle: String = ""
    private var pageCount: Int = 0
    private var pageIndex: Int = 0
    private var chromeVisible = false
    private var allowProgressSave = false
    private var immersive = false
    /** 打开菜单的时间，避免布局变化触发 onScrolled 立刻关菜单 */
    private var chromeShownAtMs = 0L
    private var pageMode: PdfPageMode = PdfPageMode.CONTINUOUS
    private var night = false
    /** 四边切边比例 L,T,R,B 各 0~0.30 */
    private var cropL = 0f
    private var cropT = 0f
    private var cropR = 0f
    private var cropB = 0f

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var singleBitmap: Bitmap? = null

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
    private var ttsParagraphs: List<Paragraph> = emptyList()
    private var ttsBarOpen = false
    private var ttsExtracting = false
    private var extractJob: kotlinx.coroutines.Job? = null
    private var pendingAfterExtract: (() -> Unit)? = null
    /** PDF 页面 OCR 任务（可取消） */
    private var ocrJob: kotlinx.coroutines.Job? = null
    private var ocrEngine: TfliteOcrEngine? = null
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
    /** TTS 当前句高亮：页内字符下标闭区间 */
    private var hlPage = -1
    private var hlStart = -1
    private var hlEnd = -1
    /** PdfRenderer 页尺寸缓存，用于与 PDFBox 坐标对齐 */
    private val rendererPageSize = HashMap<Int, Pair<Float, Float>>()

    /** PdfRenderer 同时只能 open 一页 */
    private val renderLock = Any()
    /**
     * 连续模式仅保留附近几页。
     * **注意**：淘汰时不要 [Bitmap.recycle]——View 可能仍在显示该图，立刻 recycle 会导致白页。
     * 依赖 ImageView 解绑 + GC 回收即可。
     */
    private val bitmapCache = object : LruCache<Int, Bitmap>(BITMAP_CACHE_PAGES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
    }


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
        if (data.hasExtra(PdfCropActivity.EXTRA_MIRROR)) {
            AppSettings.setPdfCropMirrorOddEven(
                this,
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
        super.onCreate(savedInstanceState)
        binding = ActivityPdfReadingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 菜单：inflate 到 host（attach 后预测量，避免首次 GONE→VISIBLE 空白）
        readMenu = PanelReadMenuBinding.inflate(layoutInflater, binding.readMenuHost, true)
        pdfSettings = binding.pdfSettingsPanel
        premeasureReadMenu()

        pageMode = AppSettings.pdfPageMode(this)
        night = AppSettings.pdfNight(this)
        val m = AppSettings.pdfCropMargins(this)
        cropL = m[0]; cropT = m[1]; cropR = m[2]; cropB = m[3]
        applyOrientationMode(AppSettings.pdfOrientationMode(this))
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
        binding.btnBookmark.setOnClickListener { togglePdfBookmark() }
        binding.btnMore.setOnClickListener { v -> showPdfMoreMenu(v) }
        binding.topBar.setOnClickListener { }
        setupMenu()
        setupPdfSettings()
        setupPinchZoom()
        setupTtsBar()
        setupPageTouch()
        setupRecycler()
        setupBottomChromeInsets()
        hideChrome()
        updateClock()
        applyPageModeUi()

        loadPdf()
    }

    /** 底部菜单避开系统导航条 */
    private fun setupBottomChromeInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomChrome) { v, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav.bottom)
            insets
        }
        binding.bottomChrome.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        startClockAndBattery()
        if (::keepScreen.isInitialized) keepScreen.onResume()
        // 前台才允许「自动」使用方向传感器
        applyOrientationMode(AppSettings.pdfOrientationMode(this), allowSensor = true)
    }

    override fun onPause() {
        super.onPause()
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
        if (::tts.isInitialized) {
            tts.listener = null
            tts.shutdown()
        }
        runCatching { ocrEngine?.close() }
        ocrEngine = null
        closePdf()
        bitmapCache.evictAll()
        super.onDestroy()
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
                if (!isFinishing && !isDestroyed) Toasts.show(this@PdfReadingActivity, message)
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

                    allowProgressSave = false
                    // 恢复页码 / 缩放 / 平移 / 滚动（切边为全局设置，onCreate 已加载）
                    val viewState = AppSettings.loadPdfViewState(this@PdfReadingActivity, fileKey)
                    val shelf = BookshelfStore.findBookByUri(this@PdfReadingActivity, fileKey)
                        ?.lastParagraph ?: 0
                    val progressPage = com.whj.reader.data.ReadingProgressStore
                        .get(this@PdfReadingActivity, fileKey)
                        ?.takeIf { it.kind == com.whj.reader.data.ReadingProgressStore.Kind.PDF }
                        ?.position ?: 0
                    pageIndex = maxOf(viewState.page, shelf, progressPage)
                        .coerceIn(0, (pageCount - 1).coerceAtLeast(0))

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
                        }
                    }
                    // 打开后立即后台：PDFBox 进内存 + 当前页附近文字缓存，之后按需预取
                    startNearbyTextExtraction(uri)
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
        singleBitmap?.recycle()
        singleBitmap = null
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
                    android.util.Log.w("PdfReading", "nearby extract: openSession failed")
                    return@launch
                }
                val extracted = withContext(Dispatchers.IO) {
                    runCatching {
                        PdfTextExtractor.extractPagesRaw(
                            this@PdfReadingActivity,
                            uri,
                            nearby,
                        )
                    }.getOrElse {
                        android.util.Log.e("PdfReading", "nearby extract failed", it)
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
                android.util.Log.i(
                    "PdfReading",
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
                // 隐藏的 RV 勿再参与触摸；单页图仅展示，触摸由 ZoomableFrameLayout 处理
                binding.rvPdfPages.isEnabled = false
                binding.ivPdfPage.isVisible = true
                binding.ivPdfPage.isClickable = false
                binding.ivPdfPage.isFocusable = false
                binding.tvPageBadge.isVisible = true
                updatePageBadge()
            }
        }
        rebindZoomTarget()
        updateModeButtons()
        refreshSelectionOverlay()
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
        pageAdapter?.notifyDataSetChanged()
        if (pageMode == PdfPageMode.SINGLE && pageCount > 0) {
            showSinglePage(pageIndex)
        }
        refreshSelectionOverlay()
    }

    private fun setupPinchZoom() {
        val zoomLayout = binding.pdfContainer
        zoomLayout.minZoom = 1f
        zoomLayout.maxZoom = 3.5f
        rebindZoomTarget()
        // 缩放保留在 transform 上，支持平移；不重绘 bitmap
        zoomLayout.onZoomChanged = {
            clearTextSelection()
            // TTS 高亮随缩放更新屏幕位置
            if (hlPage >= 0) refreshHighlightOverlay()
            refreshSelectionOverlay()
            // 页码角标反缩放，视觉大小不随 zoom 变
            updatePageBadgeZoomCompensation()
        }
        // 平移/缩放时：关菜单 + 刷新高亮位置
        zoomLayout.onTransformChanged = {
            if (chromeVisible && (zoomLayout.isZoomed() || zoomLayout.getPanX() != 0f || zoomLayout.getPanY() != 0f)) {
                hideChrome()
            }
            if (hlPage >= 0) refreshHighlightOverlay()
            if (hasTextSelection()) refreshSelectionOverlay()
            updatePageBadgeZoomCompensation()
        }
        // 侧边立即翻页（无双击等待）
        zoomLayout.onSideTapImmediate = side@{ zone, _, _ ->
            if (binding.settingsPanelContainer.isVisible) {
                binding.settingsPanelContainer.isVisible = false
                return@side
            }
            if (hasTextSelection()) {
                clearTextSelection()
                return@side
            }
            if (chromeVisible) hideChrome()
            pageTurn(forward = zone == 2)
        }
        // 左右滑翻页：左滑下一页，右滑上一页（单页 / 连续均可用）
        zoomLayout.onHorizontalSwipe = swipe@{ forward ->
            if (binding.settingsPanelContainer.isVisible) {
                binding.settingsPanelContainer.isVisible = false
                return@swipe
            }
            if (hasTextSelection()) {
                clearTextSelection()
                return@swipe
            }
            if (chromeVisible) hideChrome()
            pageTurn(forward = forward)
        }
        // 中部轻点：菜单 / 关面板（可有双击延迟）
        zoomLayout.onSingleTap = { x, _ ->
            if (binding.settingsPanelContainer.isVisible) {
                binding.settingsPanelContainer.isVisible = false
            } else {
                handleTap(x, zoomLayout.width.toFloat().coerceAtLeast(1f))
            }
        }
        zoomLayout.onLongPress = { x, y -> beginTextSelection(x, y) }
        zoomLayout.onSelectionDrag = { x, y, ended ->
            extendTextSelection(x, y)
            if (ended) showTextActionMode()
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
            PdfPageMode.SINGLE -> binding.ivPdfPage
        }
        // 连续模式缩放后竖滑 = 滚列表（可到下面页）；单页模式仍用 pan
        zoomLayout.continuousScrollWhenZoomed = pageMode == PdfPageMode.CONTINUOUS
        zoomLayout.resetVisualScale()
    }

    /** 按页取裁边（奇偶对称时左右互换） */
    private fun cropForPage(pageIndex: Int): FloatArray {
        val base = floatArrayOf(cropL, cropT, cropR, cropB)
        if (!AppSettings.pdfCropMirrorOddEven(this) || pageIndex % 2 == 0) return base
        return floatArrayOf(cropR, cropT, cropL, cropB)
    }

    /** 渲染时应用四边切边（视觉缩放由 ZoomableFrameLayout 负责，此处不再乘 zoom） */
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
        // 上限控制分辨率：够屏显即可，避免 4x 大图耗内存/电
        val maxEdge = resources.displayMetrics.widthPixels.coerceIn(720, 1600)
        val cappedTw = tw.coerceAtMost(maxEdge)
        val scale = if (targetHeight != null) {
            minOf(cappedTw / srcW, targetHeight / srcH, RENDER_MAX_SCALE)
        } else {
            (cappedTw / srcW).coerceIn(0.3f, RENDER_MAX_SCALE)
        }
        val bw = (srcW * scale).toInt().coerceIn(1, maxEdge)
        val bh = (srcH * scale).toInt().coerceIn(1, maxEdge * 3)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        matrix.postTranslate(-page.width * cl, -page.height * ct)
        matrix.postScale(bw / srcW, bh / srcH)
        page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
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

    /** 恢复页码 + 连续滚动偏移 + 缩放平移（调用时内容应仍隐藏，避免先闪第 1 页） */
    private fun restorePdfViewState(state: AppSettings.PdfViewState) {
        val page = state.page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        pageIndex = page
        when (pageMode) {
            PdfPageMode.SINGLE -> {
                showSinglePage(page)
                binding.pdfContainer.setTransform(state.zoom, state.panX, state.panY)
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = binding.rvPdfPages
                val lm = rv.layoutManager as? LinearLayoutManager
                // 先滚到目标页再通知数据量变化的顺序不可靠；set 后立刻 scroll，且内容仍 alpha=0
                pageAdapter?.setPageCount(pageCount)
                if (lm != null) {
                    lm.scrollToPositionWithOffset(page, 0)
                } else {
                    rv.scrollToPosition(page)
                }
                rv.post {
                    // 尽量还原精确竖直滚动
                    val target = state.scrollY.coerceAtLeast(0)
                    if (target > 0) {
                        val cur = rv.computeVerticalScrollOffset()
                        if (target != cur) {
                            rv.scrollBy(0, target - cur)
                        }
                    } else if (page > 0) {
                        // 无 scrollY 时再确保在目标页（防止首次布局回弹到 0）
                        val first = lm?.findFirstVisibleItemPosition() ?: -1
                        if (first != page && first >= 0) {
                            lm?.scrollToPositionWithOffset(page, 0)
                        }
                    }
                    binding.pdfContainer.setTransform(state.zoom, state.panX, state.panY)
                    updateProgressLabel()
                }
            }
        }
        updateProgressLabel()
    }

    private fun savePdfViewAndProgress() {
        if (fileKey.isEmpty() || !allowProgressSave) return
        val page = currentVisiblePage()
        val z = binding.pdfContainer
        val scrollY = if (pageMode == PdfPageMode.CONTINUOUS) {
            binding.rvPdfPages.computeVerticalScrollOffset()
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
        pageAdapter = PdfPageAdapter(0) { index, imageView, width ->
            renderPageToView(index, imageView, width)
        }
        binding.rvPdfPages.layoutManager = LinearLayoutManager(this)
        binding.rvPdfPages.adapter = pageAdapter
        binding.rvPdfPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (pageMode != PdfPageMode.CONTINUOUS) return
                // 仅用户手指拖动时收菜单（布局/程序滚动不关，避免首次打开被立刻关掉）
                if (chromeVisible &&
                    recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING &&
                    (dx != 0 || dy != 0)
                ) {
                    hideChrome()
                }
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val first = lm.findFirstVisibleItemPosition()
                if (first >= 0 && first != pageIndex) {
                    pageIndex = first
                    if (allowProgressSave) saveProgress(pageIndex)
                    trimBitmapCacheAround(pageIndex)
                }
                // 页码 + 按滚动位置算的进度%（每帧更新）
                updateProgressLabel()
                if (chromeVisible) updatePdfBookmarkButton()
                if (hasTextSelection()) refreshSelectionOverlay()
                if (hlPage >= 0) refreshHighlightOverlay()
                updatePageBadgeZoomCompensation()
            }
        })
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

    private fun renderPageToView(index: Int, imageView: ImageView, targetWidth: Int) {
        val r = renderer ?: return
        if (index !in 0 until r.pageCount) return
        val cacheKey = index
        val cached = bitmapCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            applyNightFilter(imageView)
            return
        }
        val tw = targetWidth.coerceAtLeast(1)
            .coerceAtMost(resources.displayMetrics.widthPixels.coerceAtLeast(720))
        synchronized(renderLock) {
            try {
                currentPage?.close()
                currentPage = null
                val page = r.openPage(index)
                currentPage = page
                val bmp = renderPageBitmap(
                    page,
                    tw,
                    pageIndexForMirror = index,
                )
                page.close()
                currentPage = null
                bitmapCache.put(cacheKey, bmp)
                trimBitmapCacheAround(currentVisiblePage())
                imageView.setImageBitmap(bmp)
                applyNightFilter(imageView)
            } catch (_: Exception) {
                imageView.setImageBitmap(null)
            }
        }
    }

    private fun showSinglePage(index: Int) {
        val r = renderer ?: return
        if (r.pageCount <= 0) return
        val i = index.coerceIn(0, r.pageCount - 1)
        pageIndex = i
        synchronized(renderLock) {
            try {
                currentPage?.close()
                currentPage = null
                val page = r.openPage(i)
                currentPage = page
                val container = binding.pdfContainer
                val maxW = (container.width.takeIf { it > 0 }
                    ?: resources.displayMetrics.widthPixels).coerceAtLeast(1)
                val maxH = (container.height.takeIf { it > 0 }
                    ?: (resources.displayMetrics.heightPixels * 0.8f).toInt()).coerceAtLeast(1)
                lastRenderW = maxW
                lastRenderH = maxH
                val old = singleBitmap
                val bmp = renderPageBitmap(page, maxW, maxH, pageIndexForMirror = i)
                singleBitmap = bmp
                page.close()
                currentPage = null
                binding.ivPdfPage.setImageBitmap(bmp)
                applyNightFilter(binding.ivPdfPage)
                // 勿立刻 recycle：ImageView 可能仍短暂持有 old
                if (old != null && old !== bmp) {
                    // 交给 GC；或下一帧再尝试
                    binding.ivPdfPage.post {
                        if (old !== singleBitmap && !old.isRecycled) {
                            runCatching { old.recycle() }
                        }
                    }
                }
            } catch (e: Exception) {
                Toasts.show(this, getString(R.string.load_failed, e.message ?: ""))
            }
        }
        // 单页模式不需要多页缓存
        bitmapCache.evictAll()
        updatePageBadge()
        updateProgressLabel()
        if (chromeVisible) updatePdfBookmarkButton()
        if (allowProgressSave) saveProgress(pageIndex)
    }

    /**
     * 左点 = 向上翻，右点 = 向下翻；无动画。
     * 连续模式：页高 > 屏高则滚 80% 屏高，否则滚一页实际高度。
     * 单页模式：仍按页切换。
     */
    private fun pageTurn(forward: Boolean) {
        if (chromeVisible) hideChrome()
        if (binding.settingsPanelContainer.isVisible) {
            binding.settingsPanelContainer.isVisible = false
        }
        if (pageMode == PdfPageMode.CONTINUOUS) {
            val rv = binding.rvPdfPages
            val viewportH = rv.height
            if (viewportH <= 0 || pageCount <= 0) return
            val pageH = estimateCurrentPageHeight().coerceAtLeast(1)
            val step = if (pageH > viewportH) {
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
            if (after == before) {
                Toasts.show(this, if (forward) R.string.page_bottom else R.string.page_top)
                return
            }
            val lm = rv.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: pageIndex
            if (first >= 0) pageIndex = first
            updateProgressLabel()
            if (allowProgressSave) saveProgress(pageIndex)
            return
        }
        val next = if (forward) pageIndex + 1 else pageIndex - 1
        if (next !in 0 until pageCount) {
            Toasts.show(this, if (forward) R.string.page_bottom else R.string.page_top)
            return
        }
        showSinglePage(next)
    }

    /** 当前可见页 item 高度（含分隔线） */
    private fun estimateCurrentPageHeight(): Int {
        val rv = binding.rvPdfPages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return rv.height
        val pos = lm.findFirstVisibleItemPosition()
        if (pos >= 0) {
            val child = lm.findViewByPosition(pos)
            if (child != null && child.height > 0) return child.height
        }
        // 回退：按渲染比例估算
        val r = renderer ?: return rv.height
        if (r.pageCount <= 0) return rv.height
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
                page.close()
                currentPage = null
                h.coerceAtLeast(1)
            }
        } catch (_: Exception) {
            rv.height
        }
    }

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
        // 上一页 / 下一页
        readMenu.btnPrevChapter.text = getString(R.string.pdf_prev_page)
        readMenu.btnNextChapter.text = getString(R.string.pdf_next_page)
        // 上/下一页：翻页后保持菜单打开
        readMenu.btnPrevChapter.setOnClickListener {
            pageTurn(false)
        }
        readMenu.btnNextChapter.setOnClickListener {
            pageTurn(true)
        }
        readMenu.menuStyle.setOnClickListener {
            hideChrome()
            binding.settingsPanelContainer.isVisible = true
            updateModeButtons()
            bindCropSeek()
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
            val next = when (AppSettings.pdfOrientationMode(this)) {
                OrientationMode.PORTRAIT -> OrientationMode.LANDSCAPE
                OrientationMode.LANDSCAPE -> OrientationMode.AUTO
                OrientationMode.AUTO -> OrientationMode.PORTRAIT
            }
            AppSettings.setPdfOrientationMode(this, next)
            applyOrientationMode(next)
            val label = when (next) {
                OrientationMode.PORTRAIT -> getString(R.string.orient_portrait)
                OrientationMode.LANDSCAPE -> getString(R.string.orient_landscape)
                OrientationMode.AUTO -> getString(R.string.orient_auto)
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
            // 刷新当前页滤镜
            if (pageMode == PdfPageMode.SINGLE) {
                applyNightFilter(binding.ivPdfPage)
            } else {
                pageAdapter?.notifyDataSetChanged()
            }
        }
        readMenu.menuRead.setOnClickListener {
            hideChrome()
            startPdfTts()
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
                        android.util.Log.e("PdfReading", "extractPagesRaw failed", t)
                        emptyMap()
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("PdfReading", "extract job failed", t)
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
                android.util.Log.e("PdfReading", "after extract failed", t)
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

        val done = PdfOcrCacheStore.listRecognized(this, fileKey).sorted()
        binding.tvOcrRecognized.text = if (done.isEmpty()) {
            getString(R.string.pdf_ocr_recognized_none)
        } else {
            val preview = formatPageList(done.map { it + 1 })
            getString(R.string.pdf_ocr_recognized_list, preview)
        }

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
        val already = PdfOcrCacheStore.listRecognized(this, fileKey)
        val queue = if (skipDone) pages.filter { it !in already } else pages
        if (queue.isEmpty()) {
            Toasts.show(this, getString(R.string.pdf_ocr_done, 0, pages.size, 0))
            mergeOcrCacheFromDisk()
            rebuildTextFromCache(preserveTtsPosition = true)
            return
        }

        ocrJob?.cancel()
        val progressView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val progressTv = progressView.findViewById<android.widget.TextView>(android.R.id.text1)
        progressTv.setPadding(48, 36, 48, 24)
        progressTv.text = getString(R.string.pdf_ocr_progress, 0, queue.size, queue.first() + 1)
        val progressDlg = AlertDialog.Builder(this)
            .setTitle(R.string.pdf_ocr_title)
            .setView(progressView)
            .setCancelable(false)
            .setNegativeButton(R.string.pdf_ocr_cancel) { _, _ ->
                ocrJob?.cancel()
            }
            .create()
        progressDlg.show()

        ocrJob = lifecycleScope.launch {
            var ok = 0
            var fail = 0
            var skipped = pages.size - queue.size
            try {
                val eng = withContext(Dispatchers.Default) {
                    ocrEngine ?: TfliteOcrEngine(
                        this@PdfReadingActivity,
                        TfliteOcrEngine.Backend.AUTO,
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
                            android.util.Log.e("PdfReading", "ocr page $page", it)
                        }.isSuccess
                    }
                    if (success) ok++ else fail++
                }
                if (isFinishing || isDestroyed) return@launch
                mergeOcrCacheFromDisk()
                // 把本次识别页写进内存缓存
                for (p in queue) {
                    val chars = PdfOcrCacheStore.loadPage(this@PdfReadingActivity, fileKey, p)
                    if (chars != null && chars.isNotEmpty()) {
                        val existing = rawPageCache[p]
                        if (existing.isNullOrEmpty()) rawPageCache[p] = chars
                    }
                }
                rebuildTextFromCache(preserveTtsPosition = true)
                val msg = if (!isActive) {
                    getString(R.string.pdf_ocr_cancelled, ok)
                } else {
                    getString(R.string.pdf_ocr_done, ok, skipped, fail)
                }
                Toasts.show(this@PdfReadingActivity, msg)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    Toasts.show(this@PdfReadingActivity, getString(R.string.pdf_ocr_cancelled, ok))
                } else {
                    android.util.Log.e("PdfReading", "ocr job", t)
                    Toasts.show(
                        this@PdfReadingActivity,
                        t.message ?: getString(R.string.pdf_ocr_engine_fail),
                    )
                }
            } finally {
                if (progressDlg.isShowing) progressDlg.dismiss()
            }
        }
    }

    /**
     * 渲染单页 → OCR → 持久化 + 坐标映射为 PdfChar。
     * 须在持有 [renderLock] 的 IO 线程外调用 render（内部会锁）。
     */
    private fun ocrOnePage(pageIndex: Int, engine: TfliteOcrEngine): Boolean {
        val r = renderer ?: return false
        if (pageIndex !in 0 until r.pageCount) return false
        val maxEdge = 1600
        val (bmp, pageW, pageH) = synchronized(renderLock) {
            currentPage?.close()
            currentPage = null
            val page = r.openPage(pageIndex)
            currentPage = page
            try {
                val pw = page.width.toFloat()
                val ph = page.height.toFloat()
                rendererPageSize[pageIndex] = pw to ph
                val bmp = renderPageBitmap(
                    page,
                    targetWidth = maxEdge,
                    targetHeight = null,
                    pageIndexForMirror = pageIndex,
                )
                page.close()
                currentPage = null
                Triple(bmp, pw, ph)
            } catch (t: Throwable) {
                runCatching { page.close() }
                currentPage = null
                throw t
            }
        }
        return try {
            val result = engine.recognize(bmp)
            val margins = cropForPage(pageIndex)
            val chars = PdfOcrConverter.linesToPdfChars(
                pageIndex = pageIndex,
                lines = result.lines,
                bmpW = bmp.width,
                bmpH = bmp.height,
                pageW = pageW,
                pageH = pageH,
                cropL = margins[0],
                cropT = margins[1],
                cropR = margins[2],
                cropB = margins[3],
            )
            PdfOcrCacheStore.savePage(this, fileKey, pageIndex, chars)
            true
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
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
        Toasts.show(this, msgRes)
    }

    /** TTS 句高亮 + 滚动到可见 */
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
        scrollToCharRange(hlPage, hlStart, hlEnd)
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
        // 目标句已在视窗内：不改竖直滚动
        if (isCharRangeFullyInViewport(page, charStart, charEnd)) {
            pageIndex = page
            updateProgressLabel()
            return
        }
        // 不完全在视窗：把句子顶部对齐到视窗最上
        val topY = slice.minOf { it.top }
        val pageH = slice.first().pageHeight.coerceAtLeast(1f)
        val fracTop = (topY / pageH).coerceIn(0f, 1f)
        val topPadPx = (8f * resources.displayMetrics.density).toInt().coerceAtLeast(4)
        when (pageMode) {
            PdfPageMode.SINGLE -> {
                if (page != pageIndex) showSinglePage(page)
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
                if (child != null) {
                    alignSentenceTop(child)
                } else {
                    rv.scrollToPosition(page)
                    rv.post {
                        val c = lm.findViewByPosition(page) ?: return@post
                        alignSentenceTop(c)
                        refreshHighlightOverlay()
                    }
                }
            }
        }
        pageIndex = page
        updateProgressLabel()
    }

    /**
     * 句子（字符区间）是否已完全落在当前视窗内（只关心竖直方向）。
     * 已可见则 [scrollToCharRange] 不应再 scrollBy。
     */
    private fun isCharRangeFullyInViewport(page: Int, charStart: Int, charEnd: Int): Boolean {
        when (pageMode) {
            PdfPageMode.SINGLE -> {
                // 单页模式整页进视窗；仅当已是当前页且无竖直平移需求
                if (page != pageIndex) return false
                // 有缩放平移时用容器坐标判断
                val rects = charRangeToContainerRects(page, charStart, charEnd)
                if (rects.isEmpty()) return true // 整页模式且无字框：视为可见
                val vh = binding.pdfContainer.height.toFloat().coerceAtLeast(1f)
                val pad = 6f
                return rects.all { r -> r.top >= -pad && r.bottom <= vh + pad }
            }
            PdfPageMode.CONTINUOUS -> {
                val rv = binding.rvPdfPages
                val lm = rv.layoutManager as? LinearLayoutManager ?: return false
                // 页未 bind 到屏幕 → 不可见
                if (lm.findViewByPosition(page) == null) return false
                val rects = charRangeToContainerRects(page, charStart, charEnd)
                if (rects.isEmpty()) return false
                // 与高亮层同一套容器坐标，兼顾缩放/平移
                val vh = binding.pdfContainer.height.toFloat().coerceAtLeast(1f)
                val pad = 6f
                return rects.all { r -> r.top >= -pad && r.bottom <= vh + pad }
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
        if (!tts.isReady()) {
            tts.reinit()
            Toasts.show(this, R.string.tts_not_ready)
        }
        val snap = tts.currentState()
        if (snap.state == TtsManager.State.IDLE) {
            startTtsFromViewport()
        } else {
            tts.playPauseToggle()
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
                    val iv = child.findViewById<ImageView>(R.id.ivPage) ?: continue
                    val chars = pageChars[pos] ?: continue
                    // 页在 content（RV）坐标中
                    val itemTop = child.top.toFloat()
                    val itemBottom = child.bottom.toFloat()
                    // 完整可见：item 顶不低于视口顶（允许少量像素误差）
                    // 优先找页内第一个完全落在视口内的字
                    val fullyInView = itemTop >= viewportTop - 2f && itemBottom <= viewportBottom + 2f
                    val sorted = chars.filter { !it.char.isWhitespace() }
                        .sortedWith(compareBy({ it.top }, { it.left }))
                    for (c in sorted) {
                        val rect = pageRectToContent(
                            iv,
                            pos,
                            RectF(c.left, c.top, c.right, c.bottom),
                            child.left + iv.left.toFloat(),
                            child.top + iv.top.toFloat(),
                        ) ?: continue
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
        }
        val mapped = mapPageCharToParaOffset(page, charIdx)
        chromeVisible = false
        ttsBarOpen = true
        applyChromeVisibility()
        if (!tts.isReady()) {
            tts.reinit()
            Toasts.show(this, R.string.tts_not_ready)
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
            val snap = tts.currentState()
            if (snap.state == TtsManager.State.IDLE) {
                startTtsFromViewport()
            } else {
                tts.playPauseToggle()
            }
        }
        binding.btnTtsNext.setOnClickListener { tts.nextSentence() }
        binding.btnTtsStop.setOnClickListener {
            tts.stop()
            sleepTimer.cancel()
            updateSleepUi()
            ttsBarOpen = false
            applyChromeVisibility()
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

    private fun applyChromeVisibility() {
        binding.topBar.isVisible = chromeVisible
        binding.ttsBar.isVisible = !chromeVisible && ttsBarOpen
        val host = binding.readMenuHost
        if (chromeVisible) {
            host.visibility = View.VISIBLE
            readMenu.root.visibility = View.VISIBLE
            val lp = host.layoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            host.layoutParams = lp
            host.bringToFront()
            binding.readStatusBar.bringToFront()
            binding.bottomChrome.bringToFront()
            binding.topBar.bringToFront()
            // 二次测量：父曾 GONE 时首次 VISIBLE 可能高度正确但子树未参与绘制
            host.post { if (chromeVisible) forceMenuLayout() }
        } else {
            host.visibility = View.GONE
        }
    }

    /** 预测量菜单，避免第一次点开空白 */
    private fun premeasureReadMenu() {
        val host = binding.readMenuHost
        host.visibility = View.INVISIBLE
        host.post {
            forceMenuLayout()
            if (!chromeVisible) {
                host.visibility = View.GONE
            }
        }
    }

    private fun forceMenuLayout() {
        val host = binding.readMenuHost
        val parentW = binding.bottomChrome.width.takeIf { it > 0 }
            ?: binding.root.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        if (parentW <= 0) return
        val wSpec = View.MeasureSpec.makeMeasureSpec(parentW, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        readMenu.root.measure(wSpec, hSpec)
        host.measure(wSpec, hSpec)
        host.requestLayout()
        binding.bottomChrome.requestLayout()
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

    /** 目录（树）+ 书签，可滑动切换 */
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
        val cached = com.whj.reader.data.PdfOutlineCache.get(this, uri)
        if (cached == null) {
            Toasts.show(this, R.string.pdf_toc_loading)
        }
        lifecycleScope.launch {
            val roots = withContext(Dispatchers.IO) {
                try {
                    com.whj.reader.data.PdfOutlineCache.loadOrParse(
                        this@PdfReadingActivity,
                        uri,
                    )
                } catch (t: Throwable) {
                    android.util.Log.e("PdfReading", "outline load", t)
                    emptyList()
                }
            }
            if (isFinishing || isDestroyed) return@launch
            try {
                showPdfTocAndBookmarkSheet(roots)
            } catch (t: Throwable) {
                android.util.Log.e("PdfReading", "show toc UI failed", t)
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
            restorePosition(page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
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
                if (selPage >= 0) refreshSelectionOverlay()
            }
        })

        binding.pdfContainer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            val h = v.height
            if (renderer != null && pageCount > 0 && w > 0 && h > 0 &&
                pageMode == PdfPageMode.SINGLE &&
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
        // 侧边理论上不会走到这里；兜底仍支持翻页
        when {
            x < width / 3f -> {
                if (chromeVisible) hideChrome()
                pageTurn(false)
            }
            x > width * 2f / 3f -> {
                if (chromeVisible) hideChrome()
                pageTurn(true)
            }
            else -> toggleChrome()
        }
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
        if (pageChars[page].isNullOrEmpty()) {
            Toasts.show(this, R.string.pdf_no_text_here)
            return
        }
        val hit = runCatching { hitTestChar(containerX, containerY) }.getOrNull()
        if (hit == null) {
            Toasts.show(this, R.string.pdf_no_text_here)
            return
        }
        selPage = hit.first
        selAnchor = hit.second
        selStart = hit.second
        selEnd = hit.second
        runCatching {
            refreshSelectionOverlay()
            showTextActionMode()
        }.onFailure {
            android.util.Log.e("PdfReading", "begin selection UI failed", it)
            clearTextSelection()
            Toasts.show(this, R.string.pdf_no_text_here)
        }
    }

    private fun extendTextSelection(containerX: Float, containerY: Float) {
        if (selPage < 0 || selAnchor < 0) return
        val hit = hitTestChar(containerX, containerY) ?: return
        if (hit.first != selPage) return
        selStart = min(selAnchor, hit.second)
        selEnd = max(selAnchor, hit.second)
        refreshSelectionOverlay()
        textActionMode?.invalidate()
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
                val iv = child.findViewById<ImageView>(R.id.ivPage) ?: return null
                val localX = content.x - child.left - iv.left
                val localY = content.y - child.top - iv.top
                val pageXY = viewToPageCoords(iv, localX, localY, pos) ?: return null
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
     * ImageView 本地坐标 → PDF 页坐标（左上原点、Y 向下，与 [PdfTextExtractor.PdfChar] 一致）。
     */
    private fun viewToPageCoords(
        iv: ImageView,
        localX: Float,
        localY: Float,
        pageIndex: Int,
    ): FloatArray? {
        val d = iv.drawable ?: return null
        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val vw = iv.width.toFloat().coerceAtLeast(1f)
        val vh = iv.height.toFloat().coerceAtLeast(1f)
        // fitCenter / adjustViewBounds
        val scale = min(vw / dw, vh / dh)
        val ox = (vw - dw * scale) / 2f
        val oy = (vh - dh * scale) / 2f
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
        val rects = charRangeToContainerRects(selPage, min(selStart, selEnd), max(selStart, selEnd))
        binding.pdfSelectionOverlay.setSelectionRects(rects)
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
                val iv = child.findViewById<ImageView>(R.id.ivPage) ?: return emptyList()
                val ox = child.left + iv.left.toFloat()
                val oy = child.top + iv.top.toFloat()
                for (line in mergeLineRects(selected)) {
                    pageRectToContent(iv, page, line, ox, oy)?.let { contentRects.add(it) }
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

    private fun contentToContainer(x: Float, y: Float): FloatArray {
        val z = binding.pdfContainer
        val origin = z.mapToContent(0f, 0f)
        val zoom = z.contentZoom.coerceAtLeast(0.01f)
        val panX = -origin.x * zoom
        val panY = -origin.y * zoom
        return floatArrayOf(x * zoom + panX, y * zoom + panY)
    }

    /**
     * 页坐标矩形（左上原点）→ zoomTarget 内容坐标系中的矩形。
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
        val scale = min(vw / dw, vh / dh)
        val ox = (vw - dw * scale) / 2f
        val oy = (vh - dh * scale) / 2f
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
            textActionMode?.invalidate()
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
                outRect.set(
                    (view.width / 2 - 60).coerceAtLeast(0),
                    (view.height / 3).coerceAtLeast(0),
                    (view.width / 2 + 60).coerceAtMost(view.width.coerceAtLeast(1)),
                    (view.height / 3 + 40).coerceAtMost(view.height.coerceAtLeast(1)),
                )
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
        binding.pdfContainer.setBackgroundColor(contentBg)
        binding.readStatusBar.setBackgroundColor(bar)
        binding.tvReadTitle.setBackgroundColor(bar)
        binding.tvReadTitle.setTextColor(meta)
        binding.tvBattery.setTextColor(meta)
        binding.tvClock.setTextColor(meta)
        binding.tvProgress.setTextColor(meta)
        binding.tvLoading.setTextColor(if (night) 0xFFCCCCCC.toInt() else 0xFF666666.toInt())
        binding.tvLoading.setBackgroundColor(contentBg)
        window.statusBarColor = bar
        window.navigationBarColor = bar
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

    /** 是否打孔/刘海等挖孔屏 */
    private fun hasDisplayCutout(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 28) return false
        val cutout = window.decorView.rootWindowInsets?.displayCutout
            ?: return false
        return cutout.boundingRects.isNotEmpty()
    }

    /**
     * 全屏：隐藏导航栏与书名栏。打孔屏不支持（进入前已拦截提示）。
     */
    private fun applyImmersive() {
        val decor = window.decorView
        @Suppress("DEPRECATION")
        if (immersive) {
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            binding.tvReadTitle.isVisible = false
            binding.readStatusBar.isVisible = true
            applyNightUi()
        } else {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            binding.tvReadTitle.isVisible = true
            binding.readStatusBar.isVisible = true
            applyNightUi()
        }
    }

    private fun applyOrientationMode(
        mode: OrientationMode,
        allowSensor: Boolean = !isFinishing && hasWindowFocus(),
    ) {
        // 仅「自动」且前台时监听传感器；竖/横屏锁定不启用传感器
        OrientationHelper.apply(this, mode, allowSensor = allowSensor && mode == OrientationMode.AUTO)
    }

    // ─── 进度 / 状态栏 ────────────────────────────────────

    private fun saveProgress(page: Int) {
        if (fileKey.isEmpty() || !allowProgressSave) return
        // 与视图状态一并写入（含缩放平移）
        val z = binding.pdfContainer
        val scrollY = if (pageMode == PdfPageMode.CONTINUOUS) {
            binding.rvPdfPages.computeVerticalScrollOffset()
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
        // 翻页/滚动时按需预取附近文字
        if (allowProgressSave) {
            prefetchNearbyText(visible)
        }
    }

    /**
     * 进度%：连续模式 = 竖直滚动偏移 / 可滚动总高度；
     * 单页模式 = 当前页索引 / (总页-1)。
     */
    private fun computeScrollProgressPercent(): Int {
        if (pageCount <= 0) return 0
        if (pageMode == PdfPageMode.CONTINUOUS) {
            val rv = binding.rvPdfPages
            val offset = rv.computeVerticalScrollOffset().toFloat().coerceAtLeast(0f)
            val range = rv.computeVerticalScrollRange().toFloat().coerceAtLeast(1f)
            val extent = rv.computeVerticalScrollExtent().toFloat().coerceAtLeast(0f)
            val scrollable = (range - extent).coerceAtLeast(1f)
            // 滚动位置占可滚高度的比例
            return ((offset / scrollable) * 100f).toInt().coerceIn(0, 100)
        }
        if (pageCount <= 1) return 100
        return ((pageIndex.toFloat() / (pageCount - 1).toFloat()) * 100f)
            .toInt()
            .coerceIn(0, 100)
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
