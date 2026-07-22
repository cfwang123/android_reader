package com.whj.reader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.widget.LinearLayout
import android.widget.SeekBar
import com.google.android.material.button.MaterialButton
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayoutMediator
import com.whj.reader.R
import com.whj.reader.data.AppSettings
import com.whj.reader.data.BookChineseModeStore
import com.whj.reader.data.BookEncodingStore
import com.whj.reader.data.BookFileType
import com.whj.reader.data.BookmarkStore
import com.whj.reader.data.BookshelfStore
import com.whj.reader.data.ChineseConvert
import com.whj.reader.data.CustomFontStore
import com.whj.reader.data.ReadingProgressStore
import com.whj.reader.data.BookLoader
import com.whj.reader.data.LoadedBook
import com.whj.reader.data.TextLoader
import com.whj.reader.databinding.ActivityReadingBinding
import com.whj.reader.databinding.PanelReadMenuBinding
import com.whj.reader.databinding.PanelReadSettingsBinding
import com.whj.reader.databinding.PanelTtsExportBinding
import com.whj.reader.databinding.SheetTocBinding
import com.whj.reader.model.EdgeSwipeAction
import com.whj.reader.model.OrientationMode
import com.whj.reader.model.ReadStyle
import com.whj.reader.model.ReadTheme
import com.whj.reader.tts.Mp3Encoder
import com.whj.reader.tts.TtsExportHelper
import com.whj.reader.tts.TtsManager
import com.whj.reader.ui.ParagraphAdapter
import com.whj.reader.ui.TocAdapter
import com.whj.reader.ui.TocItem
import com.whj.reader.ui.HsvColorPickerDialog
import com.whj.reader.ui.TtsExportProgressDialog
import com.whj.reader.ui.VirtualReaderView
import com.whj.reader.util.BgTextures
import com.whj.reader.util.KeepScreenController
import com.whj.reader.util.OpenFailGuide
import com.whj.reader.util.OrientationHelper
import com.whj.reader.util.ReaderFonts
import com.whj.reader.util.StorageAccess
import com.whj.reader.util.Toasts
import com.whj.reader.util.TtsVoicePicker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import kotlin.math.abs

class ReadingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_ASSET = "asset"
        const val EXTRA_TITLE = "title"
        /** 指定文本编码；空/不传 = 自动判断 */
        const val EXTRA_ENCODING = "encoding"
        /** adb logcat -s MangaZoom */
        private const val TAG_MANGA_ZOOM = "MangaZoom"
        /** 连续图图间间隔（px） */
        private const val MANGA_PAGE_GAP_PX = 10
    }

    private lateinit var binding: ActivityReadingBinding
    private lateinit var settingsPanel: PanelReadSettingsBinding
    private lateinit var readMenu: PanelReadMenuBinding
    private lateinit var exportPanel: PanelTtsExportBinding
    private lateinit var reader: VirtualReaderView
    private lateinit var tts: TtsManager
    private var ttsExport: TtsExportHelper? = null
    private var exportProgressDlg: TtsExportProgressDialog? = null

    private var book: LoadedBook? = null
    private var bookStreamer: com.whj.reader.data.BookStreamer? = null
    /** 按需续载任务（不一次扫完全书） */
    private var streamerJob: Job? = null
    @Volatile
    private var streamerLoading = false
    /** 流式加载时待恢复的段落（内容够长后再滚） */
    private var pendingRestorePara: Int = -1
    private var style: ReadStyle = ReadStyle()
    /** 设置面板上动态注入的自定义字体 chip（tag = font id） */
    private val customFontChips = mutableListOf<MaterialButton>()
    /** 背景纹理 chip（tag = texture id） */
    private val textureChips = mutableListOf<MaterialButton>()
    private val textColorSwatches = mutableListOf<View>()
    private val bgColorSwatches = mutableListOf<View>()
    private var chromeVisible = false
    /** 用户通过「朗读」打开过 TTS 条；停止后关闭 */
    private var ttsBarOpen = false
    /** 合成语音面板打开 */
    private var exportPanelOpen = false
    private enum class ExportPickMode { NONE, START, END }
    private var exportPickMode = ExportPickMode.NONE
    private var exportStartPara = -1
    private var exportEndPara = -1
    private var immersive = false
    private var chromeShownAtMs = 0L
    /** 主题/排版重布局触发的滚动回调期间，勿收起底部菜单（如点「夜间」） */
    private var ignoreScrollChromeHideUntilMs = 0L
    private var fileKey: String = ""
    private var displayTitle: String = ""
    /** 加载并恢复进度完成前，禁止把「第 0 段」写回进度（否则会冲掉上次位置） */
    private var allowProgressSave = false
    private var batteryReceiverRegistered = false
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000L)
        }
    }
    private lateinit var keepScreen: KeepScreenController

    /** MOBI 漫画模式：忽略正文，一次一张图（竖屏）；横屏为连续图流 */
    private var mangaMode = false
    private var mangaPaths: List<String> = emptyList()
    private var mangaIndex = 0
    private var mangaLoadJob: Job? = null
    private var mangaContinuousSetup = false
    private var mangaContinuousAdapter: MangaContinuousAdapter? = null
    /**
     * 待恢复的缩放/平移（相对 fit）。
     * 单图位图异步解码完成后再应用；关闭时会写入 [AppSettings.MangaViewState]。
     */
    private var pendingMangaTransform: Triple<Float, Float, Float>? = null
    /** 进入漫画恢复期间禁止把 1x 写回 prefs，避免冲掉上次缩放 */
    private var suppressMangaViewSave = false
    private var mangaGapDecoration: RecyclerView.ItemDecoration? = null
    /** 当前是否连续图布局（与 [AppSettings.mobiViewMode] 同步） */
    private var mangaContinuousPref = false
    /**
     * 连续图待恢复滚动：首可见项索引 + 项顶相对 RV 顶偏移 + 绝对 scrollY。
     * index&lt;0 表示无待恢复滚动。
     */
    private var pendingMangaScrollIndex: Int = -1
    private var pendingMangaScrollOffset: Int = 0
    private var pendingMangaScrollY: Int = 0
    private val mangaBitmapCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(8 * 1024 * 1024, 48 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val para = result.data?.getIntExtra(BookSearchActivity.RESULT_PARA_INDEX, -1) ?: -1
        if (para < 0 || !::reader.isInitialized) return@registerForActivityResult
        hideChrome()
        reader.scrollToParagraph(para)
        if (allowProgressSave) saveProgress(para)
        updateProgressLabel()
    }

    /** 全屏看图退出后滚到当前图对应段落 */
    private val imageGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val para = result.data?.getIntExtra(ImageGalleryActivity.RESULT_PARA_INDEX, -1) ?: -1
        if (para < 0 || !::reader.isInitialized) return@registerForActivityResult
        hideChrome()
        reader.scrollToParagraph(para)
        if (allowProgressSave) saveProgress(para)
        updateProgressLabel()
    }

    /** 安装自定义字体（TTF/OTF） */
    private val installFontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        installCustomFont(uri)
    }

    /** 导入阅读背景图 */
    private val importBgImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        importBackgroundImage(uri)
    }

    /** 打开失败：重新选文件 */
    private val reselectDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        applyReselectedUri(uri)
    }

    /** 打开失败：授予全盘权限后重试 */
    /** 朗读前申请通知权限（Android 13+ 前台服务通知，锁屏续播依赖） */
    private val ttsNotifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // 无论是否授权都继续；无权限时系统仍可能限制 FGS 通知
        pendingTtsAfterNotif?.invoke()
        pendingTtsAfterNotif = null
    }
    private var pendingTtsAfterNotif: (() -> Unit)? = null

    private val openFailPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (StorageAccess.hasAllFilesAccess() ||
            (intent.getStringExtra(EXTRA_URI)?.let { StorageAccess.canRead(this, Uri.parse(it)) } == true)
        ) {
            Toasts.show(this, R.string.open_failed_permission_granted_retry)
            loadContent()
        } else {
            showOpenFailGuide(
                OpenFailGuide.Reason.PERMISSION,
                detail = null,
            )
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            updateBattery(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityReadingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsPanel = binding.settingsPanel
        // 菜单 inflate 到 host，并预测量避免首次空白
        readMenu = PanelReadMenuBinding.inflate(layoutInflater, binding.readMenuHost, true)
        exportPanel = PanelTtsExportBinding.inflate(layoutInflater, binding.ttsExportHost, true)
        reader = binding.readerView
        premeasureReadMenu()
        setupMangaHost()

        style = AppSettings.loadStyle(this)

        reader.onParagraphPicked = { para ->
            onExportParagraphPicked(para)
        }
        reader.onImageLongPress = { paraIndex ->
            openImageGallery(paraIndex)
        }
        reader.onZoneTap = { zone ->
            when {
                exportPanelOpen -> {
                    // 合成面板打开时：左右仍可翻页，中心不关面板
                    when (zone) {
                        0 -> pageTurn(forward = false)
                        2 -> pageTurn(forward = true)
                    }
                }
                !binding.settingsPanelContainer.isVisible -> {
                    when (zone) {
                        0 -> {
                            // 菜单打开时只关菜单，不翻页
                            if (chromeVisible) hideChrome()
                            else pageTurn(forward = false)
                        }
                        2 -> {
                            if (chromeVisible) hideChrome()
                            else pageTurn(forward = true)
                        }
                        else -> toggleChrome()
                    }
                }
            }
        }
        // 左右滑翻页：左滑下一页，右滑上一页
        reader.onHorizontalSwipe = { forward ->
            if (binding.settingsPanelContainer.isVisible) {
                binding.settingsPanelContainer.isVisible = false
            } else if (chromeVisible) {
                // 菜单打开时只关菜单，不翻页
                hideChrome()
            } else {
                pageTurn(forward = forward)
            }
        }
        // 进度保存已在 View 内节流；这里只写入，并刷新底部进度
        reader.onScrollChangedListener = { first ->
            // 滚动时先刷新书签态，再考虑收起菜单（避免图标停在旧状态）
            if (chromeVisible) {
                updateBookmarkButton()
            }
            // 用户滚动时收菜单；主题切换等程序重布局触发的滚动不收
            val now = android.os.SystemClock.uptimeMillis()
            if (chromeVisible &&
                now > ignoreScrollChromeHideUntilMs &&
                now - chromeShownAtMs > 200L
            ) {
                hideChrome()
            }
            if (allowProgressSave) {
                saveProgress(first)
            }
            updateProgressLabel()
            updateChapterTitleBar(first)
            // 滑近已加载末尾时再续解析下一批
            maybeRequestMoreContent(first)
        }
        reader.onLinkClick = { href ->
            handleLinkClick(href)
        }
        reader.onReadFromParagraph = { paraIndex, charOffset ->
            // 关闭菜单，打开 TTS 条并从选区起点读到文末
            chromeVisible = false
            ttsBarOpen = true
            applyChromeVisibility()
            if (!tts.isReady()) {
                tts.reinit()
            }
            tts.playFromParagraphOffset(paraIndex, charOffset)
        }
        reader.onEdgeAdjust = { isLeft, direction ->
            handleEdgeAdjust(isLeft, direction)
        }
        applyEdgeSwipeFlags()
        // onCreate 显式允许传感器，避免 AUTO 被误锁竖屏 → 横放 letterbox
        // force：大屏若上次锁成竖屏 letterbox（半宽），进入时解除并铺满
        applyOrientationMode(
            AppSettings.orientationMode(this),
            toast = false,
            force = OrientationHelper.isLargeScreen(this),
        )

        tts = TtsManager(this)
        tts.listener = ttsListener
        tts.setSpeechRate(AppSettings.ttsRate(this))
        tts.setPitch(AppSettings.ttsPitch(this))
        // TXT/MOBI/EPUB：不同段落（回车）间隔 0.3 秒
        tts.setParagraphGapMs(300L)
        // 引擎/发音人在 TtsManager 构造与 onInit 中从 prefs 恢复，勿在 init 前 apply
        tts.init()

        setupTopBar()
        setupReadMenu()
        setupTtsBar()
        setupExportPanel()
        setupSettingsPanel()
        setupBottomChromeInsets()
        setupBackPress()
        applyStyleToUi()
        hideChrome()
        applyLandscapeFullscreenUi()
        updateClock()
        updateProgressLabel()

        keepScreen = KeepScreenController(this) {
            ::tts.isInitialized && tts.currentState().state == TtsManager.State.SPEAKING
        }
        keepScreen.apply()

        loadContent()
    }

    /**
     * 返回键优先级：
     * 合成面板 → 风格面板 → TTS 朗读/TTS 条 → 底部菜单 → 退出阅读
     */
    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val ttsActive = ::tts.isInitialized &&
                        tts.currentState().state != TtsManager.State.IDLE
                    when {
                        exportPanelOpen -> {
                            closeExportPanel()
                        }
                        binding.settingsPanelContainer.isVisible -> {
                            binding.settingsPanelContainer.isVisible = false
                        }
                        // 朗读中或 TTS 条打开：只停播并关条，不退出阅读
                        ttsBarOpen || ttsActive -> {
                            if (::tts.isInitialized) tts.stop()
                            if (::reader.isInitialized) reader.clearHighlight()
                            ttsBarOpen = false
                            applyChromeVisibility()
                        }
                        chromeVisible -> {
                            hideChrome()
                        }
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
        updateClock()
        updateProgressLabel()
        // 偏好页可能改过边缘手势，回来时刷新
        applyEdgeSwipeFlags()
        startClockAndBattery()
        if (::keepScreen.isInitialized) keepScreen.onResume()
        // 仅在方向偏好与当前不一致时纠正（同方向不重设，避免闪）
        applyOrientationMode(AppSettings.orientationMode(this), toast = false, force = false)
    }

    override fun onPause() {
        super.onPause()
        stopClockAndBattery()
        if (::keepScreen.isInitialized) keepScreen.onPause()
        // 锁屏/切后台不暂停 TTS，由前台服务继续播放
        if (mangaMode) {
            flushMangaViewStateBeforeLeave()
            if (allowProgressSave) saveProgress(mangaIndex)
        } else if (::reader.isInitialized) {
            saveProgress(reader.firstVisibleParagraph())
        }
    }

    override fun onStop() {
        // 再保险：离开前台时同步写入缩放/平移
        if (mangaMode) flushMangaViewStateBeforeLeave()
        super.onStop()
    }

    /** 取消节流回调并立刻写入漫画索引+缩放+平移 */
    private fun flushMangaViewStateBeforeLeave() {
        if (!::binding.isInitialized) return
        binding.root.removeCallbacks(saveMangaViewRunnable)
        if (mangaMode && mangaPaths.isNotEmpty() && fileKey.isNotEmpty()) {
            val best = bestMangaTransformForSave()
            Log.i(
                TAG_MANGA_ZOOM,
                "flushLeave best=$best live=${readLiveMangaTransform()} " +
                    "pending=$pendingMangaTransform suppress=$suppressMangaViewSave " +
                    "cont=${isMangaContinuousLayout()} idx=$mangaIndex",
            )
            // 离开时始终写入「最佳」状态（不因 suppress 跳过）
            writeMangaViewState(best)
        }
    }

    private fun startClockAndBattery() {
        clockHandler.removeCallbacks(clockTick)
        clockHandler.post(clockTick)
        registerBattery()
    }

    private fun stopClockAndBattery() {
        clockHandler.removeCallbacks(clockTick)
        unregisterBattery()
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

    private val ttsListener = object : TtsManager.Listener {
        override fun onStateChanged(snapshot: TtsManager.Snapshot) {
            runOnUiThread {
                if (!::reader.isInitialized || isFinishing || isDestroyed) return@runOnUiThread
                if (snapshot.state == TtsManager.State.IDLE) {
                    reader.clearHighlight()
                }
                updateTtsUi(snapshot)
                if (::keepScreen.isInitialized) keepScreen.onTtsStateChanged()
            }
        }

        override fun onSentenceHighlight(
            paragraphIndex: Int,
            startOffset: Int,
            endOffset: Int,
        ) {
            runOnUiThread {
                if (!::reader.isInitialized || !::tts.isInitialized || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                val st = tts.currentState().state
                if (paragraphIndex < 0 || endOffset < 0 ||
                    st == TtsManager.State.IDLE
                ) {
                    reader.clearHighlight()
                    return@runOnUiThread
                }
                if (st == TtsManager.State.SPEAKING || st == TtsManager.State.PAUSED) {
                    reader.setHighlightRange(paragraphIndex, startOffset, endOffset)
                } else {
                    reader.clearHighlight()
                }
                if (AppSettings.autoScroll(this@ReadingActivity) &&
                    st != TtsManager.State.IDLE
                ) {
                    // 下一句未完全在屏内 → 翻到句首正好贴正文区顶；全在屏内不动
                    // TTS 条高度计入不可见区
                    reader.post {
                        if (!::reader.isInitialized || isFinishing || isDestroyed) return@post
                        syncReaderBottomObscured()
                        reader.scrollToHighlightIfNeeded(
                            paragraphIndex,
                            startOffset,
                            endOffset,
                        )
                    }
                }
                saveProgress(paragraphIndex)
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 初始化失败/进行中：仅 UI 状态，不 Toast
                if (isTtsInitNoise(message)) return@runOnUiThread
                Toasts.show(this@ReadingActivity, message)
            }
        }
    }

    override fun onDestroy() {
        if (mangaMode) flushMangaViewStateBeforeLeave()
        streamerJob?.cancel()
        mangaLoadJob?.cancel()
        bookStreamer?.cancel()
        bookStreamer = null
        stopClockAndBattery()
        if (::keepScreen.isInitialized) keepScreen.onDestroy()
        sleepTimer.cancel()
        dismissExportProgressDlg()
        ttsExport?.shutdown()
        ttsExport = null
        if (::tts.isInitialized) {
            tts.listener = null
            tts.shutdown()
        }
        if (::binding.isInitialized) {
            binding.root.removeCallbacks(saveMangaViewRunnable)
            binding.mangaImageView.setImageBitmap(null)
            binding.mangaRecycler.adapter = null
        }
        mangaContinuousAdapter = null
        mangaBitmapCache.evictAll()
        super.onDestroy()
    }

    private fun registerBattery() {
        if (batteryReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = registerReceiver(batteryReceiver, filter)
        batteryReceiverRegistered = true
        sticky?.let { updateBattery(it) }
    }

    private fun unregisterBattery() {
        if (!batteryReceiverRegistered) return
        runCatching { unregisterReceiver(batteryReceiver) }
        batteryReceiverRegistered = false
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) (level * 100 / scale) else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        binding.tvBattery.text = when {
            pct < 0 -> "--%"
            charging -> "⚡$pct%"
            else -> "$pct%"
        }
    }

    private fun updateClock() {
        binding.tvClock.text = clockFmt.format(Date())
    }

    private fun updateProgressLabel() {
        if (mangaMode && mangaPaths.isNotEmpty()) {
            binding.tvProgress.text = getString(
                R.string.mobi_manga_progress,
                mangaIndex + 1,
                mangaPaths.size,
            )
            return
        }
        if (!::reader.isInitialized) {
            binding.tvProgress.text = "0%"
            return
        }
        val b = book
        // TXT 始终全文百分比；仅 EPUB/MOBI 用「第 n/m 章」
        if (b != null && isChapterProgressBook(b)) {
            val chapters = b.chapters
            if (chapters.isNotEmpty()) {
                val para = reader.firstVisibleParagraph()
                    .coerceIn(0, b.paragraphs.lastIndex.coerceAtLeast(0))
                val (n, m, pct) = chapterProgressOf(para, chapters, b.paragraphs.size)
                binding.tvProgress.text = getString(R.string.chapter_progress, n, m, pct)
                return
            }
        }
        binding.tvProgress.text = String.format(Locale.US, "%.0f%%", reader.progressPercent())
    }

    /** EPUB/MOBI：进度用「第 n/m 章 xx%」；TXT 明确排除，始终全文 % */
    private fun isChapterProgressBook(b: LoadedBook): Boolean {
        if (BookFileType.isTxt(b.uri) || BookFileType.isTxt(displayTitle) ||
            BookFileType.isTxt(fileKey)
        ) {
            return false
        }
        return BookFileType.isEpub(b.uri) || BookFileType.isMobi(b.uri) ||
            BookFileType.isEpub(displayTitle) || BookFileType.isMobi(displayTitle) ||
            BookFileType.isEpub(fileKey) || BookFileType.isMobi(fileKey)
    }

    /**
     * @return (章序号 1-based, 总章数, 章内 0–100%)
     */
    private fun chapterProgressOf(
        para: Int,
        chapters: List<com.whj.reader.model.Chapter>,
        totalParas: Int,
    ): Triple<Int, Int, Int> {
        if (chapters.isEmpty()) {
            return Triple(1, 1, 0)
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
        val within = ((para - start).toFloat() / span * 100f).toInt().coerceIn(0, 100)
        return Triple(idx + 1, chapters.size, within)
    }

    /** 滑近已加载内容末尾，或恢复/跳转目标尚未载入时，再解析下一批 */
    private fun maybeRequestMoreContent(firstVisiblePara: Int = -1) {
        if (bookStreamer == null) return
        val b = book ?: return
        if (b.isComplete) return
        val last = b.paragraphs.lastIndex.coerceAtLeast(0)
        val needForRestore = pendingRestorePara > last
        val nearEnd = if (firstVisiblePara >= 0) {
            firstVisiblePara >= (last - 30).coerceAtLeast(0)
        } else {
            false
        }
        if (!needForRestore && !nearEnd) return
        requestStreamBatch()
    }

    private fun requestStreamBatch() {
        val streamer = bookStreamer ?: return
        if (streamerLoading) return
        streamerLoading = true
        streamerJob?.cancel()
        streamerJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val target = pendingRestorePara
                if (target > streamLastIdx) {
                    // 恢复/跳转：一口气加载到目标段（中间少刷 UI；EPUB 优先读 spine 磁盘缓存）
                    var guard = 0
                    while (isActive && bookStreamer != null && !streamComplete && guard < 8) {
                        guard++
                        val hasMore = streamer.loadUntilParagraphBlocking(target)
                        if (!hasMore || streamLastIdx >= target || streamComplete) break
                    }
                } else {
                    // 普通触底：只多载一批
                    streamer.loadNextBatchBlocking()
                }
            } finally {
                streamerLoading = false
            }
        }
    }

    private fun attachBookStreamer(streamer: com.whj.reader.data.BookStreamer) {
        bookStreamer = streamer
        streamLastIdx = book?.paragraphs?.lastIndex ?: -1
        streamComplete = false
        streamer.start(
            onUpdate = { loaded ->
                // 与 loadNextBatchBlocking 同线程，供续载循环判断
                streamLastIdx = loaded.paragraphs.lastIndex
                streamComplete = loaded.isComplete
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    applyLoadedBook(loaded, isInitial = false)
                    maybeRevealReaderAfterRestore()
                }
            },
            onComplete = { loaded ->
                streamLastIdx = loaded.paragraphs.lastIndex
                streamComplete = true
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    bookStreamer = null
                    streamerLoading = false
                    applyLoadedBook(loaded, isInitial = false)
                    updateStreamTitle(loaded)
                    // 全书结束仍未到目标则落在末尾
                    if (pendingRestorePara > 0) {
                        val last = loaded.paragraphs.lastIndex
                        if (last >= 0) {
                            reader.scrollToParagraph(pendingRestorePara.coerceAtMost(last))
                        }
                        pendingRestorePara = -1
                    }
                    maybeRevealReaderAfterRestore()
                }
            },
            onProgress = { msg, cur, tot ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    updateStreamTitle(
                        book?.copy(
                            streamCurrent = cur,
                            streamTotal = tot.coerceAtLeast(1),
                            isComplete = false,
                        ) ?: return@runOnUiThread,
                        msg,
                    )
                }
            },
        )
        // 首屏后再预取一批；若有恢复进度则连续多批
        requestStreamBatch()
    }

    /** 与 streamer 批处理同线程更新的段落末索引 / 完成标记 */
    @Volatile
    private var streamLastIdx: Int = -1
    @Volatile
    private var streamComplete: Boolean = false

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener {
            // 返回前立刻落盘漫画缩放/平移（勿等 onPause 里可能被冲掉）
            flushMangaViewStateBeforeLeave()
            finish()
        }
        binding.btnSearch.setOnClickListener {
            if (fileKey.isBlank()) return@setOnClickListener
            searchLauncher.launch(
                BookSearchActivity.intentTxt(this, fileKey, displayTitle),
            )
        }
        binding.btnBookmark.setOnClickListener { toggleBookmarkAtCurrent() }
        binding.btnEncoding.setOnClickListener { showEncodingPicker() }
    }

    /**
     * 书签锚点：用户看到的「屏幕最上方第一段」。
     * 菜单打开时顶栏盖住 Reader 顶部，需扣除顶栏高度。
     */
    private fun bookmarkAnchorParagraph(): Int {
        if (book == null || !::reader.isInitialized) return 0
        val density = resources.displayMetrics.density
        val inset = if (chromeVisible) {
            // 顶栏可能尚未 measure，用实测高度或 52dp 兜底
            val h = binding.topBar.height
            if (h > 0) h.toFloat() else 52f * density
        } else {
            0f
        }
        return reader.topScreenParagraph(inset)
            .coerceIn(0, book!!.paragraphs.lastIndex.coerceAtLeast(0))
    }

    /** 当前阅读位置添加/取消书签（屏幕最上方第一段） */
    private fun toggleBookmarkAtCurrent() {
        if (fileKey.isBlank() || book == null) return
        // 布局后再取锚点，避免顶栏 height=0
        binding.topBar.post {
            val para = bookmarkAnchorParagraph()
            if (BookmarkStore.has(this, fileKey, para)) {
                BookmarkStore.remove(this, fileKey, para)
                Toasts.show(this, R.string.bookmark_off)
            } else {
                val preview = book!!.paragraphs.getOrNull(para)?.text
                    ?.take(80)
                    ?.replace('\n', ' ')
                    .orEmpty()
                val pct = reader.progressPercentForParagraph(para)
                BookmarkStore.add(
                    this,
                    com.whj.reader.model.Bookmark(
                        fileKey = fileKey,
                        paragraphIndex = para,
                        preview = preview,
                        progressPercent = pct,
                    ),
                )
                Toasts.show(this, R.string.bookmark_on)
            }
            updateBookmarkButton()
        }
    }

    private fun updateBookmarkButton() {
        if (!::binding.isInitialized || !::reader.isInitialized) return
        if (fileKey.isBlank() || book == null) {
            binding.btnBookmark.setImageResource(R.drawable.ic_bookmark_border)
            return
        }
        val para = bookmarkAnchorParagraph()
        val on = BookmarkStore.has(this, fileKey, para)
        binding.btnBookmark.setImageResource(
            if (on) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
        )
        binding.btnBookmark.contentDescription = if (on) {
            getString(R.string.bookmark_on)
        } else {
            getString(R.string.add_bookmark)
        }
    }

    /** 编码（RadioGroup）+ 简繁转换 */
    private fun showEncodingPicker() {
        val key = fileKey.ifBlank {
            intent.getStringExtra(EXTRA_URI)
                ?: intent.getStringExtra(EXTRA_ASSET)?.let { "asset://$it" }
                ?: return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_text_options, null)
        val rgEnc = view.findViewById<android.widget.RadioGroup>(R.id.rgEncoding)
        val rgZh = view.findViewById<android.widget.RadioGroup>(R.id.rgChinese)
        val rbOff = view.findViewById<android.widget.RadioButton>(R.id.rbZhOff)
        val rbSimple = view.findViewById<android.widget.RadioButton>(R.id.rbZhToSimple)
        val rbTrad = view.findViewById<android.widget.RadioButton>(R.id.rbZhToTrad)

        val ids = BookEncodingStore.OPTION_IDS
        val currentEnc = BookEncodingStore.get(this, key) ?: BookEncodingStore.ENCODING_AUTO
        val encRadioIds = IntArray(ids.size)
        for ((i, code) in ids.withIndex()) {
            val rb = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = if (code == BookEncodingStore.ENCODING_AUTO) {
                    getString(R.string.encoding_auto)
                } else {
                    code
                }
                minHeight = (40 * resources.displayMetrics.density).toInt()
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

        when (BookChineseModeStore.get(this, key)) {
            ChineseConvert.Mode.TO_SIMPLE -> rbSimple.isChecked = true
            ChineseConvert.Mode.TO_TRADITIONAL -> rbTrad.isChecked = true
            else -> rbOff.isChecked = true
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.text_options_title)
            .setView(view)
            .setPositiveButton(R.string.apply) { dialog, _ ->
                val checkedEncId = rgEnc.checkedRadioButtonId
                val encIdx = encRadioIds.indexOf(checkedEncId).coerceAtLeast(0)
                val code = ids.getOrElse(encIdx) { BookEncodingStore.ENCODING_AUTO }
                val enc = if (code == BookEncodingStore.ENCODING_AUTO) null else code
                BookEncodingStore.set(this, key, enc)
                if (enc != null) intent.putExtra(EXTRA_ENCODING, enc)
                else intent.removeExtra(EXTRA_ENCODING)

                val zhMode = when (rgZh.checkedRadioButtonId) {
                    R.id.rbZhToSimple -> ChineseConvert.Mode.TO_SIMPLE
                    R.id.rbZhToTrad -> ChineseConvert.Mode.TO_TRADITIONAL
                    else -> ChineseConvert.Mode.OFF
                }
                BookChineseModeStore.set(this, key, zhMode)

                val encLabel = enc ?: getString(R.string.encoding_auto)
                val zhLabel = when (zhMode) {
                    ChineseConvert.Mode.TO_SIMPLE -> getString(R.string.chinese_convert_to_simple)
                    ChineseConvert.Mode.TO_TRADITIONAL -> getString(R.string.chinese_convert_to_trad)
                    ChineseConvert.Mode.OFF -> getString(R.string.chinese_convert_off)
                }
                Toasts.show(
                    this,
                    getString(R.string.encoding_set, encLabel) + " · " + zhLabel,
                )
                dialog.dismiss()
                reloadTextOptions(enc, zhMode)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun reloadTextOptions(
        preferredEncoding: String?,
        chineseMode: ChineseConvert.Mode,
    ) {
        val asset = intent.getStringExtra(EXTRA_ASSET)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val titleExtra = intent.getStringExtra(EXTRA_TITLE)
        val keepPara = if (::reader.isInitialized) {
            reader.firstVisibleParagraph()
        } else {
            0
        }
        if (::tts.isInitialized) {
            tts.stop()
        }
        streamerJob?.cancel()
        bookStreamer?.cancel()
        bookStreamer = null
        streamerLoading = false
        showLoadOverlay(getString(R.string.loading_book))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        asset != null -> com.whj.reader.data.BookOpenResult(
                            book = TextLoader.loadFromAssets(
                                this@ReadingActivity,
                                asset,
                                titleExtra ?: getString(R.string.unnamed),
                                preferredEncoding = preferredEncoding,
                                chineseMode = chineseMode,
                            ),
                        )
                        uriStr != null -> BookLoader.openFromUri(
                            this@ReadingActivity,
                            Uri.parse(uriStr),
                            titleExtra,
                            preferredEncoding = preferredEncoding,
                            chineseMode = chineseMode,
                            onProgress = { msg, cur, tot ->
                                runOnUiThread { updateLoadOverlay(msg, cur, tot) }
                            },
                        )
                        else -> error("未指定文件")
                    }
                }
            }
            result.onSuccess { open ->
                pendingRestorePara = keepPara
                applyLoadedBook(open.book, isInitial = true)
                // 重载时优先回到刚才的位置
                if (keepPara > 0) pendingRestorePara = maxOf(pendingRestorePara, keepPara)
                val streamer = open.streamer
                if (streamer != null) {
                    attachBookStreamer(streamer)
                } else {
                    updateBookmarkButton()
                    // 无续载：若无需等待目标段，收尾显示
                    maybeRevealReaderAfterRestore()
                }
            }.onFailure { e ->
                hideLoadOverlay()
                // 重载失败：引导权限/重选；已有正文时不因关闭对话框而退出
                showOpenFailGuide(
                    reason = OpenFailGuide.reasonFrom(e),
                    detail = e.message,
                    exitOnClose = book == null,
                )
            }
        }
    }

    private fun showLoadOverlay(message: String) {
        if (!::binding.isInitialized) return
        binding.loadOverlay.isVisible = true
        binding.tvLoadMessage.text = message
        binding.tvLoadDetail.text = ""
        binding.progressLoad.isIndeterminate = true
    }

    private fun updateLoadOverlay(message: String, current: Int, total: Int) {
        if (!::binding.isInitialized) return
        binding.loadOverlay.isVisible = true
        binding.tvLoadMessage.text = message.ifBlank { getString(R.string.loading_book) }
        if (total > 0) {
            binding.progressLoad.isIndeterminate = false
            binding.progressLoad.max = total
            binding.progressLoad.progress = current.coerceIn(0, total)
            binding.tvLoadDetail.text = getString(R.string.load_progress_detail, current, total)
        } else {
            binding.progressLoad.isIndeterminate = true
            binding.tvLoadDetail.text = ""
        }
    }

    private fun hideLoadOverlay() {
        if (!::binding.isInitialized) return
        binding.loadOverlay.isVisible = false
    }

    /** 长按图片 → 全屏看图（书内全部图片可滑动切换） */
    private fun openImageGallery(paraIndex: Int) {
        val paras = book?.paragraphs.orEmpty()
        if (paras.isEmpty()) return
        val paths = ArrayList<String>()
        val indices = ArrayList<Int>()
        for (p in paras) {
            // 看图模式只收集整行图（行内小图仍在正文内显示）
            val path = p.imagePath?.takeIf { it.isNotBlank() } ?: continue
            if (!java.io.File(path).isFile) continue
            paths.add(path)
            indices.add(p.index)
        }
        if (paths.isEmpty()) {
            Toasts.show(this, R.string.image_gallery_empty)
            return
        }
        var start = indices.indexOf(paraIndex)
        if (start < 0) {
            // 容错：按段落序号找最近的图片
            start = indices.indexOfFirst { it >= paraIndex }.takeIf { it >= 0 }
                ?: indices.indexOfLast { it <= paraIndex }.coerceAtLeast(0)
        }
        imageGalleryLauncher.launch(
            ImageGalleryActivity.intent(
                this,
                paths = paths,
                paraIndices = indices.toIntArray(),
                startIndex = start,
            ),
        )
    }

    /** 音量键翻页：减=下一页，加=上一页（默认开启） */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (AppSettings.volumeKeyPageTurn(this) &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    pageTurn(forward = true)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    pageTurn(forward = false)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 瞬时翻页：下翻末行顶置，上翻首行底置；第 1 行在标题栏下完整显示 */
    private fun pageTurn(forward: Boolean) {
        if (chromeVisible) hideChrome()
        if (binding.settingsPanelContainer.isVisible) {
            binding.settingsPanelContainer.isVisible = false
        }
        if (mangaMode) {
            mangaGo(if (forward) +1 else -1)
            return
        }
        if (!reader.canPage(forward)) {
            Toasts.show(this, if (forward) R.string.page_bottom else R.string.page_top)
            return
        }
        if (!reader.pageTurn(forward = forward)) {
            Toasts.show(this, if (forward) R.string.page_bottom else R.string.page_top)
            return
        }
        if (reader.shouldUpdateProgressUi()) {
            updateProgressLabel()
        }
    }

    private fun updateOrientMenuIcon() {
        if (!::readMenu.isInitialized) return
        val mode = AppSettings.orientationMode(this)
        val iv = readMenu.menuOrient.getChildAt(0) as? android.widget.ImageView ?: return
        iv.setImageResource(OrientationHelper.menuIconRes(mode))
        val label = when (mode) {
            OrientationMode.LANDSCAPE -> getString(R.string.orient_landscape)
            else -> getString(R.string.orient_portrait)
        }
        (readMenu.menuOrient.getChildAt(1) as? android.widget.TextView)?.text = label
    }

    private fun setupReadMenu() {
        setupMenuPagerSnap()
        updateOrientMenuIcon()
        readMenu.btnPrevChapter.setOnClickListener { jumpChapter(-1) }
        readMenu.btnNextChapter.setOnClickListener { jumpChapter(1) }
        readMenu.menuStyle.setOnClickListener {
            hideChrome()
            rebuildCustomFontChips()
            updateMobiModeButtons()
            openStyleSettingsPanel()
        }
        readMenu.menuPref.setOnClickListener {
            hideChrome()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        readMenu.menuJump.setOnClickListener {
            hideChrome()
            showProgressJumpSheet()
        }
        readMenu.menuToc.setOnClickListener {
            hideChrome()
            showTocSheet()
        }
        // 视角：竖屏 ↔ 横屏（已去掉自动旋转）
        readMenu.menuOrient.setOnClickListener {
            val next = when (AppSettings.orientationMode(this)) {
                OrientationMode.LANDSCAPE -> OrientationMode.PORTRAIT
                else -> OrientationMode.LANDSCAPE
            }
            AppSettings.setOrientationMode(this, next)
            // 大屏 force 解除 letterbox；手机 force=false 仅在方向真变时改
            // 从菜单切换视角时保持底栏菜单打开
            applyOrientationMode(
                next,
                toast = true,
                force = OrientationHelper.isLargeScreen(this),
                keepMenu = true,
            )
            updateOrientMenuIcon()
        }
        // 全屏显示（沉浸）
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
            // 不关菜单；抑制 applyStyle 重布局触发的滚动收栏
            ignoreScrollChromeHideUntilMs =
                android.os.SystemClock.uptimeMillis() + 600L
            toggleNightStyle()
        }
        readMenu.menuRead.setOnClickListener {
            // 关闭菜单，打开 TTS 条并朗读
            chromeVisible = false
            ttsBarOpen = true
            applyChromeVisibility()
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
        readMenu.menuSynth.setOnClickListener {
            openExportPanel()
        }
    }

    private fun setupExportPanel() {
        exportPanel.btnExportClose.setOnClickListener { closeExportPanel() }
        exportPanel.btnExportVoice.setOnClickListener {
            TtsVoicePicker.show(this, tts) {
                refreshExportVoiceLabel()
            }
        }
        exportPanel.btnPickStart.setOnClickListener {
            exportPickMode = ExportPickMode.START
            reader.paragraphPickEnabled = true
            exportPanel.tvExportHint.text = getString(R.string.tts_export_pick_start_hint)
            Toasts.show(this, R.string.tts_export_pick_start_hint)
        }
        exportPanel.btnPickEnd.setOnClickListener {
            exportPickMode = ExportPickMode.END
            reader.paragraphPickEnabled = true
            exportPanel.tvExportHint.text = getString(R.string.tts_export_pick_end_hint)
            Toasts.show(this, R.string.tts_export_pick_end_hint)
        }
        exportPanel.btnPickAll.setOnClickListener {
            selectExportAll()
            Toasts.show(this, R.string.tts_export_all_set)
        }
        exportPanel.btnStartExport.setOnClickListener { startRangeExport() }
        exportPanel.btnCancelExport.setOnClickListener {
            ttsExport?.cancel()
        }
        setupExportBitrateSpinner()
        setupExportFormatOptions()
        exportPanel.rgExportFormat.setOnCheckedChangeListener { _, _ ->
            refreshExportBitrateEnabled()
        }
        refreshExportVoiceLabel()
        updateExportRangeUi()
        refreshExportBitrateEnabled()
    }

    private fun setupExportFormatOptions() {
        val mp3Ok = Mp3Encoder.isAvailable()
        exportPanel.rbFormatMp3.isEnabled = mp3Ok
        if (mp3Ok) {
            exportPanel.rbFormatMp3.isChecked = true
        } else {
            exportPanel.rbFormatMp3.alpha = 0.45f
            exportPanel.rbFormatM4a.isChecked = true
        }
    }

    private val exportBitrateOptions = intArrayOf(32, 48, 64, 96, 128, 160, 192)

    private fun setupExportBitrateSpinner() {
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
        exportPanel.spExportBitrate.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val kbps = exportBitrateOptions.getOrNull(position) ?: 64
                    AppSettings.setTtsExportBitrateKbps(this@ReadingActivity, kbps)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun refreshExportBitrateEnabled() {
        if (!::exportPanel.isInitialized) return
        // 码率对 MP3 / M4A 有效，WAV 忽略
        val needBitrate = exportPanel.rbFormatMp3.isChecked || exportPanel.rbFormatM4a.isChecked
        exportPanel.spExportBitrate.isEnabled = needBitrate
        exportPanel.tvBitrateLabel.alpha = if (needBitrate) 1f else 0.4f
        exportPanel.spExportBitrate.alpha = if (needBitrate) 1f else 0.4f
    }

    private fun selectedExportBitrateKbps(): Int {
        val pos = exportPanel.spExportBitrate.selectedItemPosition
        return exportBitrateOptions.getOrNull(pos)
            ?: AppSettings.ttsExportBitrateKbps(this)
    }

    private fun openExportPanel() {
        // 释放朗读占用的 TTS 引擎，避免与合成实例抢引擎导致卡死
        if (::tts.isInitialized) {
            tts.stop()
        }
        chromeVisible = false
        ttsBarOpen = false
        exportPanelOpen = true
        exportPickMode = ExportPickMode.NONE
        reader.paragraphPickEnabled = false
        // 默认全文
        selectExportAll()
        refreshExportVoiceLabel()
        setExportProgressUi(active = false)
        applyChromeVisibility()
        exportPanel.tvExportHint.text = getString(R.string.tts_export_hint)
    }

    /** 选择全书（默认） */
    private fun selectExportAll() {
        exportPickMode = ExportPickMode.NONE
        reader.paragraphPickEnabled = false
        val last = book?.paragraphs?.lastIndex ?: -1
        if (last < 0) {
            exportStartPara = -1
            exportEndPara = -1
        } else {
            exportStartPara = 0
            exportEndPara = last
        }
        exportPanel.tvExportHint.text = getString(R.string.tts_export_hint)
        updateExportRangeUi()
    }

    private fun closeExportPanel() {
        if (ttsExport?.isWorking() == true) {
            ttsExport?.cancel()
        }
        exportPanelOpen = false
        exportPickMode = ExportPickMode.NONE
        reader.paragraphPickEnabled = false
        // 关闭时去掉正文范围高亮与选区状态
        exportStartPara = -1
        exportEndPara = -1
        if (::reader.isInitialized) {
            reader.clearExportRangeHighlight()
        }
        if (::exportPanel.isInitialized) {
            exportPanel.tvExportRange.text = getString(R.string.tts_export_range_none)
            exportPanel.tvExportHint.text = getString(R.string.tts_export_hint)
            setExportProgressUi(active = false)
        }
        applyChromeVisibility()
    }

    private fun onExportParagraphPicked(para: Int) {
        val last = book?.paragraphs?.lastIndex ?: return
        val p = para.coerceIn(0, last)
        when (exportPickMode) {
            ExportPickMode.START -> {
                exportStartPara = p
                if (exportEndPara < 0) exportEndPara = p
                Toasts.show(this, getString(R.string.tts_export_start_set, p + 1))
            }
            ExportPickMode.END -> {
                exportEndPara = p
                if (exportStartPara < 0) exportStartPara = p
                Toasts.show(this, getString(R.string.tts_export_end_set, p + 1))
            }
            ExportPickMode.NONE -> return
        }
        exportPickMode = ExportPickMode.NONE
        reader.paragraphPickEnabled = false
        exportPanel.tvExportHint.text = getString(R.string.tts_export_hint)
        normalizeExportRange()
        updateExportRangeUi()
    }

    private fun normalizeExportRange() {
        if (exportStartPara >= 0 && exportEndPara >= 0 && exportStartPara > exportEndPara) {
            val t = exportStartPara
            exportStartPara = exportEndPara
            exportEndPara = t
        }
    }

    /**
     * 合成用文本：范围内每段（一行）单独处理；
     * 段末若无句读停顿标点，自动补「。」。
     */
    private fun buildExportSpeechText(
        book: LoadedBook,
        start: Int,
        end: Int,
    ): String {
        val sb = StringBuilder()
        for (i in start..end) {
            val raw = book.paragraphs.getOrNull(i)?.text.orEmpty()
            // 段内若仍含换行，按行再拆
            for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val piece = ensureSentenceTerminator(line)
                if (piece.isEmpty()) continue
                sb.append(piece)
            }
        }
        return sb.toString()
    }

    /** 行尾无。！？等则补中文句号 */
    private fun ensureSentenceTerminator(line: String): String {
        val t = line.trim()
        if (t.isEmpty()) return ""
        var i = t.lastIndex
        // 跳过尾部引号/括号
        while (i >= 0 && t[i] in "\"'”’」』》〉）)]｝}") i--
        if (i < 0) return "$t。"
        val c = t[i]
        if (c in "。！？.!?;；…‥~～") return t
        return "$t。"
    }

    private fun updateExportRangeUi() {
        if (!::exportPanel.isInitialized || !::reader.isInitialized) return
        if (exportStartPara < 0 || exportEndPara < 0) {
            exportPanel.tvExportRange.text = getString(R.string.tts_export_range_none)
            reader.clearExportRangeHighlight()
            return
        }
        normalizeExportRange()
        val b = book
        val chars = if (b != null) {
            (exportStartPara..exportEndPara).sumOf { i ->
                b.paragraphs.getOrNull(i)?.text?.length ?: 0
            }
        } else {
            0
        }
        exportPanel.tvExportRange.text = getString(
            R.string.tts_export_range,
            exportStartPara + 1,
            exportEndPara + 1,
            chars,
        )
        reader.setExportRangeHighlight(exportStartPara, exportEndPara)
    }

    private fun refreshExportVoiceLabel() {
        if (!::exportPanel.isInitialized || !::tts.isInitialized) return
        val name = tts.currentVoiceName()
            ?: AppSettings.voiceName(this)
            ?: getString(R.string.tts_voice)
        exportPanel.tvExportVoice.text = name
    }

    private fun startRangeExport() {
        val b = book
        if (b == null || exportStartPara < 0 || exportEndPara < 0) {
            Toasts.show(this, R.string.tts_export_need_range)
            return
        }
        if (ttsExport?.isWorking() == true) return
        normalizeExportRange()
        // 按段拼接；段末无句号等则补「。」，避免换行处连读不清
        val text = buildExportSpeechText(b, exportStartPara, exportEndPara)
        if (text.isBlank()) {
            Toasts.show(this, R.string.tts_export_need_range)
            return
        }
        var format = when {
            exportPanel.rbFormatWav.isChecked -> TtsExportHelper.Format.WAV
            exportPanel.rbFormatMp3.isChecked -> TtsExportHelper.Format.MP3
            else -> TtsExportHelper.Format.M4A
        }
        // 选了 MP3 但本机无 LAME（非 arm64 等）→ 自动改用 M4A
        if (format == TtsExportHelper.Format.MP3 && !Mp3Encoder.isAvailable()) {
            format = TtsExportHelper.Format.M4A
            exportPanel.rbFormatM4a.isChecked = true
            Toasts.show(this, R.string.tts_export_mp3_unsupported)
        }
        val bitRateKbps = selectedExportBitrateKbps()
        AppSettings.setTtsExportBitrateKbps(this, bitRateKbps)
        val helper = TtsExportHelper(this).also { ttsExport = it }
        setExportProgressUi(active = true, done = 0, total = 1)
        val dlg = TtsExportProgressDialog(this) {
            helper.cancel()
        }.also { exportProgressDlg = it }
        dlg.show()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        helper.export(
            text = text,
            format = format,
            filePrefix = "book",
            bitRateKbps = bitRateKbps,
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
                    // 面板进度条：按字数 0–100，否则按段+段内
                    val pct = progressPercent(done, t, phase, doneChars, totalChars, partFraction)
                    setExportProgressUi(
                        active = true,
                        done = pct,
                        total = 100,
                        label = label,
                    )
                    exportProgressDlg?.update(
                        done, total, phase, doneChars, totalChars, partFraction,
                    )
                }

                override fun onSuccess(file: File) {
                    if (isFinishing || isDestroyed) return
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    dismissExportProgressDlg()
                    setExportProgressUi(active = false)
                    Toasts.show(
                        this@ReadingActivity,
                        getString(R.string.tts_export_ok, file.name),
                        android.widget.Toast.LENGTH_LONG,
                    )
                    shareExportedAudio(file)
                }

                override fun onError(message: String) {
                    if (isFinishing || isDestroyed) return
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    dismissExportProgressDlg()
                    setExportProgressUi(active = false)
                    Toasts.show(
                        this@ReadingActivity,
                        getString(R.string.tts_export_fail, message),
                        android.widget.Toast.LENGTH_LONG,
                    )
                }

                override fun onCancelled() {
                    if (isFinishing || isDestroyed) return
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    dismissExportProgressDlg()
                    setExportProgressUi(active = false)
                    Toasts.show(this@ReadingActivity, R.string.tts_export_cancelled)
                }
            },
        )
    }

    private fun dismissExportProgressDlg() {
        exportProgressDlg?.dismiss()
        exportProgressDlg = null
    }

    /** 导出进度 0–100（与进度窗算法一致） */
    private fun progressPercent(
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

    private fun setExportProgressUi(
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
        exportPanel.btnPickStart.isEnabled = !active
        exportPanel.btnPickEnd.isEnabled = !active
        exportPanel.btnPickAll.isEnabled = !active
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

    private fun shareExportedAudio(file: File) {
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

    /** 从可视区第一个完整显示的字开始读到文末 */
    private fun startTtsFromViewport() {
        val (para, off) = reader.firstFullyVisibleCharPosition()
        tts.playFromParagraphOffset(para, off)
    }

    /**
     * 朗读前尽量拿到通知权限：Android 13+ 无通知时前台服务易被系统在锁屏后杀掉。
     */
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

    private val sleepTimer = com.whj.reader.tts.TtsSleepTimer(
        onTick = { left ->
            if (!isFinishing && !isDestroyed) {
                binding.tvTtsSleepCountdown.text =
                    com.whj.reader.tts.TtsSleepTimer.formatCountdown(left)
            }
        },
        onFinished = { onSleepTimerFinished() },
    )

    private val ttsRateOptions = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)

    private fun setupTtsBar() {
        binding.btnTtsPlayPause.setOnClickListener {
            withTtsNotificationPermission {
                if (!tts.isReady()) {
                    tts.reinit()
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
        binding.btnTtsPrev.setOnClickListener { tts.previousSentence() }
        binding.btnTtsNext.setOnClickListener { tts.nextSentence() }
        binding.btnTtsStop.setOnClickListener {
            tts.stop()
            sleepTimer.cancel()
            updateSleepUi()
            reader.clearHighlight()
            ttsBarOpen = false
            updateTtsUi(tts.currentState())
            applyChromeVisibility()
        }
        binding.btnVoice.setOnClickListener { showVoicePicker() }
        binding.btnTtsRetry.setOnClickListener {
            tts.reinit()
            try {
                startActivity(tts.openTtsSettingsIntent())
            } catch (_: Exception) {
                Toasts.show(this, R.string.tts_check_system, android.widget.Toast.LENGTH_LONG)
            }
        }
        binding.btnTtsRate.setOnClickListener { v -> showTtsRateMenu(v) }
        binding.btnTtsSleep.setOnClickListener { v -> showTtsSleepMenu(v) }
        binding.tvTtsSleepCountdown.setOnClickListener { confirmCancelSleepTimer() }
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
        if (::reader.isInitialized) reader.clearHighlight()
        updateSleepUi()
        if (::tts.isInitialized) updateTtsUi(tts.currentState())
        Toasts.show(this, R.string.tts_sleep_finished)
    }

    private fun adjustTtsRate(delta: Float) {
        val next = (AppSettings.ttsRate(this) + delta).coerceIn(0.5f, 2.5f)
        val rounded = (kotlin.math.round(next * 10f) / 10f).coerceIn(0.5f, 2.5f)
        AppSettings.setTtsRate(this, rounded)
        tts.setSpeechRate(rounded, restartCurrent = true)
        updateTtsRateLabel(rounded)
    }

    /** 样式面板：可滚动，高度不超过屏高约 78%，避免底部被裁切 */
    private fun openStyleSettingsPanel() {
        val panel = binding.settingsPanel.root
        val maxH = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        val lp = panel.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.height = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        lp.gravity = android.view.Gravity.BOTTOM
        panel.layoutParams = lp
        binding.settingsPanelContainer.isVisible = true
        panel.post {
            if (!binding.settingsPanelContainer.isVisible) return@post
            val natural = panel.height
            if (natural > maxH) {
                val lp2 = panel.layoutParams as android.widget.FrameLayout.LayoutParams
                lp2.height = maxH
                lp2.gravity = android.view.Gravity.BOTTOM
                panel.layoutParams = lp2
            }
        }
    }

    private fun setupSettingsPanel() {
        binding.settingsScrim.setOnClickListener {
            binding.settingsPanelContainer.isVisible = false
        }

        fun bindSeekers() {
            settingsPanel.seekFontSize.progress = (style.fontSizeSp - 12f).toInt().coerceIn(0, 24)
            settingsPanel.tvFontSize.text = style.fontSizeSp.toInt().toString()
            settingsPanel.seekLineSpacing.progress =
                ((style.lineSpacingMult - 1.0f) * 10).toInt().coerceIn(0, 20)
            settingsPanel.tvLineSpacing.text = String.format("%.1f", style.lineSpacingMult)
            settingsPanel.seekParaSpacing.progress = style.paraSpacingDp.coerceIn(0, 32)
            settingsPanel.tvParaSpacing.text = style.paraSpacingDp.toString()
        }
        bindSeekers()

        settingsPanel.seekFontSize.setOnSeekBarChangeListener(simpleSeek { p ->
            style = style.copy(fontSizeSp = 12f + p)
            settingsPanel.tvFontSize.text = style.fontSizeSp.toInt().toString()
            // 拖动时即时预览并锚定当前位置
            persistAndApplyStyle(keepAnchor = true)
        })
        settingsPanel.seekLineSpacing.setOnSeekBarChangeListener(simpleSeek { p ->
            style = style.copy(lineSpacingMult = 1.0f + p / 10f)
            settingsPanel.tvLineSpacing.text = String.format("%.1f", style.lineSpacingMult)
            persistAndApplyStyle(keepAnchor = true)
        })
        settingsPanel.seekParaSpacing.setOnSeekBarChangeListener(simpleSeek { p ->
            style = style.copy(paraSpacingDp = p)
            settingsPanel.tvParaSpacing.text = p.toString()
            persistAndApplyStyle(keepAnchor = true)
        })

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
            style = style.copy(fontSizeSp = 16f, lineSpacingMult = 1.2f, paraSpacingDp = 4)
            bindSeekers()
            persistAndApplyStyle(keepAnchor = true)
        }
        settingsPanel.btnLayoutDefault.setOnClickListener {
            style = style.copy(fontSizeSp = 18f, lineSpacingMult = 1.4f, paraSpacingDp = 8)
            bindSeekers()
            persistAndApplyStyle(keepAnchor = true)
        }
        settingsPanel.btnLayoutLoose.setOnClickListener {
            style = style.copy(fontSizeSp = 20f, lineSpacingMult = 1.7f, paraSpacingDp = 16)
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

    private fun isMobiBook(): Boolean {
        val b = book
        return BookFileType.isMobi(fileKey) ||
            BookFileType.isMobi(displayTitle) ||
            (b != null && BookFileType.isMobi(b.uri)) ||
            BookFileType.isMobi(intent.getStringExtra(EXTRA_TITLE)) ||
            BookFileType.isMobi(intent.getStringExtra(EXTRA_URI))
    }

    /**
     * 无有效正文的 MOBI（仅空段/块图/占位）：有图时自动漫画模式。
     */
    private fun isImageOnlyMobi(loaded: LoadedBook): Boolean {
        if (loaded.imagePaths.isEmpty()) return false
        if (!BookFileType.isMobi(loaded.uri) && !isMobiBook()) return false
        return loaded.paragraphs.none { p ->
            !p.isBlockImage && p.text.any { !it.isWhitespace() }
        }
    }

    private fun progressFileExt(): String {
        val fromUri = BookFileType.extensionOf(fileKey)
        if (fromUri != null) return fromUri
        val title = displayTitle
        val fromTitle = BookFileType.extensionOf(title)
        if (fromTitle != null) return fromTitle
        return when {
            isMobiBook() -> ".mobi"
            BookFileType.isEpub(fileKey) || BookFileType.isEpub(title) -> ".epub"
            BookFileType.isPdf(fileKey) || BookFileType.isPdf(title) -> ".pdf"
            else -> ".txt"
        }
    }

    private fun currentMobiViewMode(): AppSettings.MobiViewMode {
        return when {
            !mangaMode -> AppSettings.MobiViewMode.TEXT
            mangaContinuousPref -> AppSettings.MobiViewMode.CONTINUOUS
            else -> AppSettings.MobiViewMode.MANGA
        }
    }

    private fun updateMobiModeButtons() {
        if (!::settingsPanel.isInitialized) return
        val show = isMobiBook()
        settingsPanel.rowMobiViewMode.isVisible = show
        if (!show) return
        val mode = currentMobiViewMode()
        val primary = AppTheme.primary(this)
        val density = resources.displayMetrics.density
        val stroke = (1.5f * density).toInt().coerceAtLeast(2)
        fun styleToggle(btn: MaterialButton, selected: Boolean, labelRes: Int) {
            btn.alpha = 1f
            val label = getString(labelRes)
            btn.text = if (selected) "✓ $label" else label
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
        val modeLabel = getString(
            when (mode) {
                AppSettings.MobiViewMode.TEXT -> R.string.mobi_mode_text
                AppSettings.MobiViewMode.MANGA -> R.string.mobi_mode_manga
                AppSettings.MobiViewMode.CONTINUOUS -> R.string.mobi_mode_continuous
            },
        )
        settingsPanel.tvMobiModeCurrent.text = getString(R.string.mobi_mode_current, modeLabel)
    }

    private fun setMobiViewMode(mode: AppSettings.MobiViewMode) {
        if (!isMobiBook()) return
        if (mode == currentMobiViewMode()) {
            updateMobiModeButtons()
            return
        }
        when (mode) {
            AppSettings.MobiViewMode.TEXT -> {
                AppSettings.setMobiViewMode(this, mode)
                if (mangaMode) exitMangaMode()
                mangaContinuousPref = false
            }
            AppSettings.MobiViewMode.MANGA,
            AppSettings.MobiViewMode.CONTINUOUS,
            -> {
                val paths = mangaPaths.ifEmpty { book?.imagePaths.orEmpty() }
                    .filter { File(it).isFile }
                if (paths.isEmpty()) {
                    Toasts.show(this, R.string.mobi_manga_no_images)
                    updateMobiModeButtons()
                    return
                }
                mangaPaths = paths
                val wantContinuous = mode == AppSettings.MobiViewMode.CONTINUOUS
                AppSettings.setMobiViewMode(this, mode)
                if (mangaMode) {
                    // 图模式内切换：智能对齐进度
                    switchMangaImageLayout(wantContinuous)
                } else {
                    mangaContinuousPref = wantContinuous
                    enterMangaMode(restoreIndex = true)
                }
            }
        }
        updateMobiModeButtons()
    }

    /**
     * 单图 ↔ 连续图：
     * - 单图→连续：当前图滚到顶部
     * - 连续→单图：取视口内可见面积最大的一张（缓存命中则无黑屏）
     */
    private fun switchMangaImageLayout(wantContinuous: Boolean) {
        if (wantContinuous == mangaContinuousPref) return
        // 先根据当前布局同步索引
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
        Log.i(TAG_MANGA_ZOOM, "switchLayout cont=$wantContinuous idx=$mangaIndex")
        allowProgressSave = true
        if (wantContinuous) {
            // 单图→连续：遮罩定位，避免闪到列表头
            suppressMangaViewSave = true
            showMangaLocateUi()
            updateMangaLayoutForOrientation(preservePending = true)
            scheduleRestoreMangaZoom(revealWhenReady = true)
        } else {
            // 连续→单图：缓存命中则直接切换，无黑屏
            switchContinuousToSingleSeamless(mangaIndex)
        }
        updateProgressLabel()
    }

    /** 连续→单图：优先缓存直出；否则在连续流上转圈，解码完再切 */
    private fun switchContinuousToSingleSeamless(index: Int) {
        val i = index.coerceIn(0, mangaPaths.lastIndex)
        mangaIndex = i
        mangaContinuousPref = false
        val path = mangaPaths[i]
        val cached = mangaBitmapCache.get(path)
        if (cached != null && !cached.isRecycled) {
            applySingleMangaLayoutWithBitmap(cached)
            suppressMangaViewSave = false
            if (allowProgressSave) saveProgress(i)
            return
        }
        // 解码期间：保持连续流可见，仅显示进度圈
        binding.mangaProgress.isVisible = true
        mangaLoadJob?.cancel()
        mangaLoadJob = lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                decodeMangaSampled(path, mangaMaxSide())
            }
            if (isFinishing || isDestroyed) {
                bmp?.recycle()
                return@launch
            }
            if (mangaIndex != i || isMangaContinuousLayout()) {
                // 用户又切走了
                if (bmp != null) mangaBitmapCache.put(path, bmp)
                binding.mangaProgress.isVisible = false
                return@launch
            }
            if (bmp == null) {
                binding.mangaProgress.isVisible = false
                // 仍切到单图空态
                applySingleMangaLayoutWithBitmap(null)
                Toasts.show(this@ReadingActivity, R.string.image_gallery_load_fail)
                return@launch
            }
            mangaBitmapCache.put(path, bmp)
            applySingleMangaLayoutWithBitmap(bmp)
            suppressMangaViewSave = false
            if (allowProgressSave) saveProgress(i)
        }
    }

    private fun applySingleMangaLayoutWithBitmap(bmp: android.graphics.Bitmap?) {
        binding.mangaContinuousHost.isVisible = false
        binding.mangaContinuousHost.resetZoom(notify = false)
        binding.mangaContinuousHost.alpha = 1f
        binding.mangaImageView.isVisible = true
        binding.mangaImageView.alpha = 1f
        binding.mangaImageView.setImageBitmap(bmp)
        if (bmp != null) {
            afterMangaBitmapReady()
        }
        binding.mangaProgress.isVisible = false
        updateProgressLabel()
    }

    /** 漫画图索引 → 正文段落（块图路径匹配 / 第 N 张块图 / 比例回退） */
    private fun paragraphIndexForMangaImage(imageIndex: Int): Int {
        val paras = book?.paragraphs.orEmpty()
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
        // 按块图序号对齐
        var n = 0
        for (i in paras.indices) {
            if (paras[i].isBlockImage) {
                if (n == idx) return i
                n++
            }
        }
        // 比例回退
        if (mangaPaths.isEmpty()) return 0
        return ((idx.toFloat() / mangaPaths.size.coerceAtLeast(1)) * paras.lastIndex)
            .toInt()
            .coerceIn(0, paras.lastIndex)
    }

    /**
     * 连续列表视口内「露得最多」的一张（高度可见像素最大）。
     */
    private fun pickMostCompleteVisibleMangaIndex(): Int {
        if (!isMangaContinuousLayout() || mangaPaths.isEmpty()) {
            return mangaIndex.coerceIn(0, (mangaPaths.size - 1).coerceAtLeast(0))
        }
        val rv = binding.mangaRecycler
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

    /** 定位中：遮住内容，显示加载，避免先闪首页再跳 */
    private fun showMangaLocateUi() {
        if (!::binding.isInitialized) return
        binding.mangaProgress.isVisible = true
        binding.mangaContinuousHost.alpha = 0f
        binding.mangaImageView.alpha = 0f
    }

    private fun revealMangaContent() {
        if (!::binding.isInitialized) return
        binding.mangaProgress.isVisible = false
        binding.mangaContinuousHost.alpha = 1f
        binding.mangaImageView.alpha = 1f
    }

    private fun setupMangaHost() {
        val iv = binding.mangaImageView
        iv.minZoomFactor = 0.25f
        iv.maxZoomFactor = 5f
        iv.keepRelativeZoomOnBitmapChange = true
        iv.onSideTap = { zone ->
            // 菜单打开时只关菜单，不翻页
            if (chromeVisible) {
                hideChrome()
            } else {
                when (zone) {
                    0 -> mangaGo(-1)
                    2 -> mangaGo(+1)
                }
            }
        }
        iv.onSwipePage = { forward ->
            if (chromeVisible) {
                hideChrome()
            } else {
                mangaGo(if (forward) +1 else -1)
            }
        }
        iv.onCenterTap = { toggleChrome() }
        iv.onZoomChanged = {
            if (chromeVisible && iv.isScaled()) hideChrome()
            scheduleSaveMangaViewState()
        }
        iv.onTransformChanged = {
            if (chromeVisible && iv.isScaled()) hideChrome()
            scheduleSaveMangaViewState()
        }
    }

    /** 当前是否使用连续图流（由用户选择，横竖屏均可） */
    private fun isMangaContinuousLayout(): Boolean =
        mangaMode && mangaContinuousPref && mangaPaths.isNotEmpty()

    /**
     * 漫画：单图 [mangaImageView]；连续图：[mangaContinuousHost]（横竖屏均可）。
     * @param preservePending true 时不覆盖 [pendingMangaTransform]（进入时刚从 store 读出）
     */
    private fun updateMangaLayoutForOrientation(preservePending: Boolean = false) {
        if (!mangaMode || !::binding.isInitialized) return
        if (mangaPaths.isEmpty()) return
        // 切换布局前记下缩放；进入恢复阶段用 store 的 pending，勿用控件 1x 覆盖
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
            binding.mangaImageView.isVisible = false
            binding.mangaImageView.setImageBitmap(null)
            binding.mangaContinuousHost.isVisible = true
            ensureMangaContinuousSetup()
            mangaContinuousAdapter?.submit(mangaPaths)
            binding.mangaRecycler.post {
                if (!isMangaContinuousLayout()) return@post
                restoreMangaContinuousScrollOrIndex()
                tryApplyPendingMangaTransform()
                // 再延一帧：等 item 高度稳定后再钉 scroll + zoom
                binding.mangaRecycler.post {
                    if (!isMangaContinuousLayout() || isFinishing || isDestroyed) return@post
                    restoreMangaContinuousScrollOrIndex()
                    tryApplyPendingMangaTransform()
                }
            }
        } else {
            if (binding.mangaContinuousHost.isVisible) {
                syncMangaIndexFromContinuous()
            }
            binding.mangaContinuousHost.isVisible = false
            binding.mangaContinuousHost.resetZoom(notify = false)
            binding.mangaImageView.isVisible = true
            showMangaIndex(mangaIndex)
            binding.mangaImageView.post {
                if (mangaMode && !isMangaContinuousLayout()) {
                    tryApplyPendingMangaTransform()
                }
            }
        }
        updateProgressLabel()
    }

    private fun ensureMangaContinuousSetup() {
        if (mangaContinuousSetup) return
        mangaContinuousSetup = true
        val zoom = binding.mangaContinuousHost
        val rv = binding.mangaRecycler
        zoom.minZoom = 0.25f
        zoom.maxZoom = 3.5f
        zoom.continuousScrollWhenZoomed = true
        zoom.zoomTarget = rv
        // 首次 setup 不 reset：enter 时会按 pending 恢复缩放

        val adapter = MangaContinuousAdapter()
        mangaContinuousAdapter = adapter
        rv.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.setHasFixedSize(false)
        // 图间 5px 由适配器内 divider 控制（黑条可见）
        mangaGapDecoration?.let { rv.removeItemDecoration(it) }
        mangaGapDecoration = null
        rv.setBackgroundColor(0xFF000000.toInt())
        rv.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (!isMangaContinuousLayout()) return
                    if (chromeVisible && dy != 0) hideChrome()
                    syncMangaIndexFromContinuous()
                    updateProgressLabel()
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (!isMangaContinuousLayout()) return
                    // 停稳后写入竖直位置
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        syncMangaIndexFromContinuous()
                        scheduleSaveMangaViewState()
                    }
                }
            },
        )

        zoom.onSingleTap = { _, _ -> toggleChrome() }
        // 连续流：侧点/水平滑跳到相邻图（未缩放且非双指手势时）
        zoom.onSideTapImmediate = { zone, _, _ ->
            // 菜单打开时只关菜单，不翻页
            if (chromeVisible) {
                hideChrome()
            } else {
                mangaGo(if (zone == 2) +1 else -1)
            }
        }
        zoom.onHorizontalSwipe = { forward ->
            if (chromeVisible) {
                hideChrome()
            } else {
                mangaGo(if (forward) +1 else -1)
            }
        }
        zoom.onPanOverscroll = overscroll@{ _, overY ->
            if (!isMangaContinuousLayout()) return@overscroll
            if (chromeVisible) hideChrome()
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
            if (chromeVisible && zoom.isScaled()) hideChrome()
            scheduleSaveMangaViewState()
        }
        zoom.onTransformChanged = {
            if (chromeVisible &&
                (zoom.isScaled() || zoom.getPanX() != 0f || zoom.getPanY() != 0f)
            ) {
                hideChrome()
            }
            scheduleSaveMangaViewState()
        }
    }

    private fun scrollMangaContinuousTo(index: Int, smooth: Boolean) {
        val rv = binding.mangaRecycler
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
     * 连续图：读取首可见项 + 项顶偏移 + 绝对 scrollY。
     * @return Triple(index, itemOffset, scrollY)
     */
    private fun captureMangaContinuousScroll(): Triple<Int, Int, Int> {
        if (!isMangaContinuousLayout() || !::binding.isInitialized) {
            return Triple(mangaIndex, 0, 0)
        }
        val rv = binding.mangaRecycler
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
     * 恢复连续图竖直位置：优先 index + itemOffset；
     * 绝对 scrollY 仅在同方向会话内可信，换向后应已清 0。
     */
    private fun restoreMangaContinuousScrollOrIndex() {
        if (!isMangaContinuousLayout()) return
        val rv = binding.mangaRecycler
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
        Log.i(
            TAG_MANGA_ZOOM,
            "restoreScroll idx=$idx itemOff=$itemOff targetScrollY=$targetScrollY",
        )
        // 仅当明确有 scrollY（同会话未换向）才微调；换向后 target=0 跳过
        if (targetScrollY > 0) {
            rv.post {
                if (!mangaMode || !isMangaContinuousLayout() || isFinishing || isDestroyed) return@post
                val cur = rv.computeVerticalScrollOffset()
                val delta = targetScrollY - cur
                if (abs(delta) > 2) rv.scrollBy(0, delta)
                syncMangaIndexFromContinuous()
                updateProgressLabel()
            }
        }
    }

    private fun syncMangaIndexFromContinuous() {
        if (!binding.mangaContinuousHost.isVisible) return
        val lm = binding.mangaRecycler.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        // 优先取占视口较多的项
        val firstView = lm.findViewByPosition(first)
        val second = lm.findFirstCompletelyVisibleItemPosition()
        val pick = when {
            second != RecyclerView.NO_POSITION -> second
            firstView != null && firstView.bottom < binding.mangaRecycler.height / 3 ->
                (first + 1).coerceAtMost(mangaPaths.lastIndex)
            else -> first
        }
        if (pick in mangaPaths.indices && pick != mangaIndex) {
            mangaIndex = pick
            if (allowProgressSave) saveProgress(mangaIndex)
            preloadMangaNeighbors(mangaIndex)
        }
    }

    private fun enterMangaMode(restoreIndex: Boolean) {
        if (mangaPaths.isEmpty()) {
            mangaPaths = book?.imagePaths.orEmpty().filter { File(it).isFile }
        }
        if (mangaPaths.isEmpty()) {
            mangaMode = false
            Toasts.show(this, R.string.mobi_manga_no_images)
            return
        }
        // 切到漫画前若在朗读则停止
        if (::tts.isInitialized) tts.stop()
        mangaMode = true
        // 与全局偏好对齐（连续图 / 单图漫画）
        val pref = AppSettings.mobiViewMode(this)
        mangaContinuousPref = pref == AppSettings.MobiViewMode.CONTINUOUS
        binding.mangaHost.isVisible = true
        reader.visibility = View.GONE
        // 先读出上次缩放/平移，再布局；禁止 save 用 1x 覆盖 prefs
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
        // 先遮住内容 + 显示加载，定位完成后再露（避免闪首页）
        showMangaLocateUi()
        // 保留 store 读出的 pending，勿被布局里的 1x 覆盖
        updateMangaLayoutForOrientation(preservePending = true)
        allowProgressSave = true
        // 只写进度索引，不写缩放（suppress 中）
        saveProgress(mangaIndex)
        // 布局 + 列表/位图就绪后反复应用缩放与滚动
        scheduleRestoreMangaZoom(revealWhenReady = true)
        updateProgressLabel()
        binding.tvChapterTitle.text = ""
        updateMobiModeButtons()
        // 书本加载遮罩可关；图内用 mangaProgress 定位
        hideLoadOverlay()
    }

    private fun loadPendingMangaTransformFromStore() {
        if (fileKey.isEmpty()) {
            Log.w(TAG_MANGA_ZOOM, "loadPending skip empty fileKey")
            return
        }
        val state = AppSettings.loadMangaViewState(this, fileKey)
        val zoom = state.zoom.coerceIn(0.25f, 5f)
        val panX = state.panX
        val panY = state.panY
        // 连续图竖直位置：始终记下（即使 zoom 为 1）
        pendingMangaScrollIndex = state.index
        pendingMangaScrollOffset = state.itemOffset
        pendingMangaScrollY = state.scrollY.coerceAtLeast(0)
        // 缩放/平移：一律写入 pending（含 1x+平移）
        pendingMangaTransform = Triple(zoom, panX, panY)
        Log.i(
            TAG_MANGA_ZOOM,
            "loadPending set pending=$pendingMangaTransform idx=${state.index} " +
                "itemOff=${state.itemOffset} scrollY=${state.scrollY} cont=$mangaContinuousPref",
        )
    }

    private fun scheduleRestoreMangaZoom(revealWhenReady: Boolean = false) {
        val attempts = intArrayOf(0, 16, 48, 120, 280, 500, 900, 1500)
        Log.i(
            TAG_MANGA_ZOOM,
            "scheduleRestore pending=$pendingMangaTransform " +
                "scroll=($pendingMangaScrollIndex,$pendingMangaScrollOffset,$pendingMangaScrollY) " +
                "cont=${isMangaContinuousLayout()} suppress=$suppressMangaViewSave reveal=$revealWhenReady",
        )
        for (delay in attempts) {
            binding.mangaHost.postDelayed({
                if (!mangaMode || isFinishing || isDestroyed) return@postDelayed
                // 连续图：每帧都尝试恢复滚动 + 缩放
                if (isMangaContinuousLayout()) {
                    restoreMangaContinuousScrollOrIndex()
                }
                val before = readLiveMangaTransform()
                tryApplyPendingMangaTransform()
                val after = readLiveMangaTransform()
                val zoomOk = isPendingMangaTransformAppliedOnView()
                val scrollOk = !isMangaContinuousLayout() || isMangaScrollRestoredEnough()
                val singleReady = isMangaContinuousLayout() ||
                    (binding.mangaImageView.hasBitmap() && binding.mangaImageView.width > 0)
                Log.i(
                    TAG_MANGA_ZOOM,
                    "restore tick delay=${delay}ms zoomOk=$zoomOk scrollOk=$scrollOk " +
                        "before=$before after=$after pending=$pendingMangaTransform " +
                        "cont=${isMangaContinuousLayout()} " +
                        "ivW=${binding.mangaImageView.width} hasBmp=${binding.mangaImageView.hasBitmap()} " +
                        "hostW=${binding.mangaContinuousHost.width} " +
                        "rvOff=${if (isMangaContinuousLayout()) binding.mangaRecycler.computeVerticalScrollOffset() else -1}",
                )
                if (zoomOk && scrollOk && singleReady) {
                    suppressMangaViewSave = false
                    if (revealWhenReady) revealMangaContent()
                } else if (delay >= attempts.last()) {
                    Log.w(TAG_MANGA_ZOOM, "restore FAILED after all retries")
                    suppressMangaViewSave = false
                    if (revealWhenReady) revealMangaContent()
                }
            }, delay.toLong())
        }
    }

    /** 连续图滚动是否已接近待恢复位置 */
    private fun isMangaScrollRestoredEnough(): Boolean {
        if (!isMangaContinuousLayout()) return true
        if (pendingMangaScrollIndex < 0 && pendingMangaScrollY <= 0) return true
        val rv = binding.mangaRecycler
        val lm = rv.layoutManager as? LinearLayoutManager ?: return false
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return false
        val targetIdx = pendingMangaScrollIndex.coerceAtLeast(0)
        // 首可见页接近目标即可（item 高度变化导致 offset 难精确）
        if (abs(first - targetIdx) <= 1) return true
        if (pendingMangaScrollY > 0) {
            val cur = rv.computeVerticalScrollOffset()
            if (abs(cur - pendingMangaScrollY) < 80) return true
        }
        return false
    }

    /**
     * 用控件实读判断 pending 是否已应用。
     * 切勿用 [currentMangaTransform]（会回落 pending 造成「假成功」）。
     */
    private fun isPendingMangaTransformAppliedOnView(): Boolean {
        val t = pendingMangaTransform ?: return true
        val live = readLiveMangaTransform() ?: return false
        val ok = abs(live.first - t.first) < 0.05f &&
            abs(live.second - t.second) < 12f &&
            abs(live.third - t.third) < 12f
        return ok
    }

    /** 优先专用漫画进度，再回退 ReadingProgressStore（兼容旧数据） */
    private fun resolveMangaRestoreIndex(): Int {
        if (mangaPaths.isEmpty()) return 0
        val last = mangaPaths.lastIndex
        val view = AppSettings.loadMangaViewState(this, fileKey)
        if (view.index in 0..last) {
            return view.index
        }
        val saved = ReadingProgressStore.get(this, fileKey) ?: return 0
        return when {
            saved.position !in 0..last -> 0
            saved.total == mangaPaths.size -> saved.position
            saved.total > 0 && saved.total <= mangaPaths.size * 2 -> saved.position
            // 旧记录 total 可能是段数；位置仍在图片范围内则采用
            saved.position <= last && saved.total == 0 -> saved.position
            else -> 0
        }
    }

    private val saveMangaViewRunnable = Runnable {
        if (!isFinishing && !isDestroyed && mangaMode && allowProgressSave) {
            saveMangaViewStateNow()
        }
    }

    private fun scheduleSaveMangaViewState() {
        if (!mangaMode || !allowProgressSave || fileKey.isEmpty()) return
        if (suppressMangaViewSave) {
            Log.i(TAG_MANGA_ZOOM, "scheduleSave skipped suppress=true")
            return
        }
        binding.root.removeCallbacks(saveMangaViewRunnable)
        binding.root.postDelayed(saveMangaViewRunnable, 120L)
    }

    private fun saveMangaViewStateNow() {
        // 恢复过程中控件可能仍是 1x：跳过节流保存，避免冲掉 prefs
        if (suppressMangaViewSave) {
            Log.i(TAG_MANGA_ZOOM, "saveNow skipped suppress=true pending=$pendingMangaTransform")
            return
        }
        if (fileKey.isEmpty() || !mangaMode || mangaPaths.isEmpty()) return
        if (!::binding.isInitialized) return
        val best = bestMangaTransformForSave()
        Log.i(
            TAG_MANGA_ZOOM,
            "saveNow best=$best live=${readLiveMangaTransform()} pending=$pendingMangaTransform " +
                "cont=${isMangaContinuousLayout()} idx=$mangaIndex",
        )
        writeMangaViewState(best)
    }

    private fun writeMangaViewState(t: Triple<Float, Float, Float>) {
        val (zoom, panX, panY) = t
        pendingMangaTransform = Triple(zoom, panX, panY)
        val (scrollIdx, itemOff, scrollY) = if (isMangaContinuousLayout()) {
            captureMangaContinuousScroll()
        } else {
            Triple(mangaIndex.coerceIn(0, mangaPaths.lastIndex), 0, 0)
        }
        // 连续图以滚动首项为准写索引；单图用 mangaIndex
        val idx = if (isMangaContinuousLayout()) {
            scrollIdx.coerceIn(0, mangaPaths.lastIndex)
        } else {
            mangaIndex.coerceIn(0, mangaPaths.lastIndex)
        }
        mangaIndex = idx
        // 同步 pending 滚动，避免恢复中误写 0
        pendingMangaScrollIndex = idx
        pendingMangaScrollOffset = itemOff
        pendingMangaScrollY = scrollY
        Log.i(
            TAG_MANGA_ZOOM,
            "writeState z=$zoom pan=($panX,$panY) idx=$idx itemOff=$itemOff scrollY=$scrollY " +
                "cont=${isMangaContinuousLayout()} fileKey=${fileKey.take(100)} " +
                "suppress=$suppressMangaViewSave",
        )
        AppSettings.saveMangaViewState(
            this,
            fileKey,
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

    /** 控件实读；未就绪返回 null */
    private fun readLiveMangaTransform(): Triple<Float, Float, Float>? {
        if (!::binding.isInitialized) return null
        if (isMangaContinuousLayout()) {
            val z = binding.mangaContinuousHost
            if (z.width <= 0) return null
            return Triple(z.contentZoom.coerceIn(0.25f, 3.5f), z.getPanX(), z.getPanY())
        }
        val iv = binding.mangaImageView
        if (iv.width <= 0 || !iv.hasBitmap()) return null
        return Triple(iv.getRelativeZoom().coerceIn(0.25f, 5f), iv.getPanX(), iv.getPanY())
    }

    private fun isIdentityTransform(t: Triple<Float, Float, Float>): Boolean =
        abs(t.first - 1f) < 0.02f && abs(t.second) < 0.5f && abs(t.third) < 0.5f

    private fun isUsefulTransform(t: Triple<Float, Float, Float>): Boolean =
        !isIdentityTransform(t)

    /**
     * 保存用：控件若仍是 1x 而 pending 有缩放，优先 pending（防止恢复中误写）。
     */
    private fun bestMangaTransformForSave(): Triple<Float, Float, Float> {
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

    private fun currentMangaTransform(): Triple<Float, Float, Float> =
        bestMangaTransformForSave()

    private fun restoreMangaZoomFromStore() {
        if (!mangaMode || fileKey.isEmpty()) return
        loadPendingMangaTransformFromStore()
        tryApplyPendingMangaTransform()
        scheduleRestoreMangaZoom()
    }

    private fun applyMangaZoom(zoom: Float, panX: Float, panY: Float) {
        pendingMangaTransform = Triple(zoom, panX, panY)
        tryApplyPendingMangaTransform()
    }

    private fun tryApplyPendingMangaTransform() {
        val t = pendingMangaTransform
        if (t == null) {
            Log.d(TAG_MANGA_ZOOM, "tryApply no pending")
            return
        }
        val (zoom, panX, panY) = t
        if (isMangaContinuousLayout()) {
            val host = binding.mangaContinuousHost
            if (host.width <= 0 || host.height <= 0) {
                Log.d(TAG_MANGA_ZOOM, "tryApply cont host not laid out w=${host.width} h=${host.height}")
                return
            }
            host.setTransform(zoom.coerceIn(0.25f, 3.5f), panX, panY, notify = false)
            Log.i(
                TAG_MANGA_ZOOM,
                "tryApply cont set z=$zoom pan=($panX,$panY) → live=${readLiveMangaTransform()}",
            )
        } else {
            val iv = binding.mangaImageView
            if (iv.width <= 0 || iv.height <= 0 || !iv.hasBitmap()) {
                Log.d(
                    TAG_MANGA_ZOOM,
                    "tryApply single not ready w=${iv.width} h=${iv.height} bmp=${iv.hasBitmap()}",
                )
                return
            }
            iv.setTransform(zoom.coerceIn(0.25f, 5f), panX, panY, notify = false)
            Log.i(
                TAG_MANGA_ZOOM,
                "tryApply single set z=$zoom pan=($panX,$panY) rel=${iv.getRelativeZoom()} " +
                    "live=${readLiveMangaTransform()}",
            )
        }
    }

    private fun exitMangaMode() {
        // 连续→正文：先对齐「视口最完整图」
        if (isMangaContinuousLayout()) {
            mangaIndex = pickMostCompleteVisibleMangaIndex()
        }
        val imgIdx = mangaIndex.coerceIn(0, (mangaPaths.size - 1).coerceAtLeast(0))
        val targetPara = paragraphIndexForMangaImage(imgIdx)
        if (allowProgressSave && mangaPaths.isNotEmpty()) {
            // 漫画视图仍按图索引记；正文进度记对应段落
            flushMangaViewStateBeforeLeave()
            AppSettings.saveProgress(this, fileKey, targetPara)
            BookshelfStore.updateProgress(this, fileKey, targetPara)
            val totalParas = book?.paragraphs?.size ?: 0
            ReadingProgressStore.saveTxt(
                this,
                fileKey,
                targetPara,
                totalParas,
                fileExt = progressFileExt(),
            )
        }
        mangaMode = false
        mangaLoadJob?.cancel()
        pendingMangaTransform = null
        pendingMangaScrollIndex = -1
        pendingMangaScrollOffset = 0
        pendingMangaScrollY = 0
        revealMangaContent()
        binding.mangaHost.isVisible = false
        binding.mangaContinuousHost.isVisible = false
        binding.mangaContinuousHost.resetZoom(notify = false)
        binding.mangaImageView.isVisible = true
        binding.mangaImageView.alpha = 1f
        binding.mangaImageView.setImageBitmap(null)
        reader.visibility = View.VISIBLE
        // 滚到该图在正文中的段落位置
        if (::reader.isInitialized && (book?.paragraphs?.isNotEmpty() == true)) {
            reader.scrollToParagraph(targetPara)
        }
        updateProgressLabel()
        updateChapterTitleBar(targetPara)
        updateMobiModeButtons()
    }

    private fun mangaGo(delta: Int) {
        if (!mangaMode || mangaPaths.isEmpty()) return
        val next = mangaIndex + delta
        if (next !in mangaPaths.indices) {
            Toasts.show(
                this,
                if (delta > 0) R.string.mobi_manga_last else R.string.mobi_manga_first,
            )
            return
        }
        mangaIndex = next
        if (isMangaContinuousLayout()) {
            scrollMangaContinuousTo(next, smooth = true)
        } else {
            showMangaIndex(next)
        }
        if (allowProgressSave) saveProgress(next)
        updateProgressLabel()
    }

    private fun showMangaIndex(i: Int) {
        if (mangaPaths.isEmpty()) return
        mangaIndex = i.coerceIn(0, mangaPaths.lastIndex)
        // 横屏连续流由列表负责显示
        if (isMangaContinuousLayout()) {
            scrollMangaContinuousTo(mangaIndex, smooth = false)
            binding.mangaProgress.isVisible = false
            preloadMangaNeighbors(mangaIndex)
            return
        }
        val path = mangaPaths[mangaIndex]
        val cached = mangaBitmapCache.get(path)
        if (cached != null && !cached.isRecycled) {
            binding.mangaImageView.setImageBitmap(cached)
            afterMangaBitmapReady()
            binding.mangaProgress.isVisible = false
            preloadMangaNeighbors(mangaIndex)
            return
        }
        binding.mangaProgress.isVisible = true
        mangaLoadJob?.cancel()
        mangaLoadJob = lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                decodeMangaSampled(path, mangaMaxSide())
            }
            if (isFinishing || isDestroyed) {
                bmp?.recycle()
                return@launch
            }
            if (mangaIndex != i || isMangaContinuousLayout()) {
                if (bmp != null) mangaBitmapCache.put(path, bmp)
                return@launch
            }
            if (bmp == null) {
                binding.mangaProgress.isVisible = false
                binding.mangaImageView.setImageBitmap(null)
                Toasts.show(this@ReadingActivity, R.string.image_gallery_load_fail)
                return@launch
            }
            mangaBitmapCache.put(path, bmp)
            binding.mangaImageView.setImageBitmap(bmp)
            afterMangaBitmapReady()
            binding.mangaProgress.isVisible = false
            preloadMangaNeighbors(mangaIndex)
        }
    }

    /**
     * 位图就绪后：优先应用 pending（打开时恢复）；
     * 若 keepRelativeZoom 已保留会话缩放，则同步 pending 为当前值。
     */
    private fun afterMangaBitmapReady() {
        val iv = binding.mangaImageView
        val t = pendingMangaTransform
        val pendingUseful = t != null && isUsefulTransform(t)
        val viewScaled = iv.isScaled() ||
            abs(iv.getRelativeZoom() - 1f) > 0.02f ||
            abs(iv.getPanX()) > 1f ||
            abs(iv.getPanY()) > 1f
        Log.i(
            TAG_MANGA_ZOOM,
            "afterBitmapReady pendingUseful=$pendingUseful viewScaled=$viewScaled " +
                "suppress=$suppressMangaViewSave rel=${iv.getRelativeZoom()} " +
                "pan=(${iv.getPanX()},${iv.getPanY()}) pending=$t",
        )
        if (pendingUseful && (!viewScaled || suppressMangaViewSave)) {
            tryApplyPendingMangaTransform()
            if (isPendingMangaTransformAppliedOnView()) {
                suppressMangaViewSave = false
                Log.i(TAG_MANGA_ZOOM, "afterBitmapReady applied OK suppress=false")
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

    private fun preloadMangaNeighbors(i: Int) {
        val targets = listOf(i - 1, i + 1, i + 2, i + 3, i - 2)
            .filter { it in mangaPaths.indices }
            .distinct()
        lifecycleScope.launch(Dispatchers.IO) {
            for (ti in targets) {
                val p = mangaPaths[ti]
                if (mangaBitmapCache.get(p) != null) continue
                val bmp = decodeMangaSampled(p, mangaMaxSide()) ?: continue
                mangaBitmapCache.put(p, bmp)
            }
        }
    }

    /**
     * 连续图列表：每项一图 + 底部分隔条 [MANGA_PAGE_GAP_PX]。
     */
    private inner class MangaContinuousAdapter :
        RecyclerView.Adapter<MangaContinuousAdapter.VH>() {

        private var paths: List<String> = emptyList()

        fun submit(newPaths: List<String>) {
            paths = newPaths
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = paths.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
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
                    MANGA_PAGE_GAP_PX,
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
                // 先占位：按当前列表宽，避免竖屏 height 带到横屏
                val w = mangaListContentWidth()
                imageView.layoutParams = imageView.layoutParams.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = (w * 1.4f).toInt().coerceAtLeast(1)
                }
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        decodeMangaSampled(path, mangaMaxSide())
                    }
                    if (token != loadToken || boundPath != path) {
                        // 过期结果仍可入缓存
                        if (bmp != null) mangaBitmapCache.put(path, bmp)
                        return@launch
                    }
                    if (bmp == null) {
                        imageView.setImageBitmap(null)
                        return@launch
                    }
                    mangaBitmapCache.put(path, bmp)
                    applyBitmap(bmp)
                    // 邻近预载
                    if (position == mangaIndex ||
                        position == mangaIndex + 1 ||
                        position == mangaIndex - 1
                    ) {
                        preloadMangaNeighbors(position)
                    }
                }
            }

            private fun applyBitmap(bmp: Bitmap) {
                // 必须用「当前」列表宽度算高度；竖屏绑定时缓存的 height 在横屏会变半宽
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
                // 宽高比与 parentW 对齐后 FIT_XY 铺满，避免 FIT_CENTER 在错误 height 下缩成一半
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

    /** 连续图列表内容宽度（旋转后必须重算 item 高度，否则图会缩成一半宽） */
    private fun mangaListContentWidth(): Int {
        if (!::binding.isInitialized) {
            return resources.displayMetrics.widthPixels.coerceAtLeast(1)
        }
        return binding.mangaRecycler.width.takeIf { it > 0 }
            ?: binding.mangaContinuousHost.width.takeIf { it > 0 }
            ?: binding.mangaHost.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.coerceAtLeast(1)
    }

    private fun mangaMaxSide(): Int {
        val dm = resources.displayMetrics
        return (maxOf(dm.widthPixels, dm.heightPixels) * 2).coerceAtLeast(1080)
    }

    private fun decodeMangaSampled(path: String, maxSide: Int): Bitmap? {
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

    private fun simpleSeek(onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    /** 菜单夜间快捷：亮色 / 夜色颗粒 切换 */
    private fun toggleNightStyle() {
        val isNight = style.bgTextureId == BgTextures.NIGHT_GRAIN ||
            style.theme == ReadTheme.NIGHT ||
            !ParagraphAdapter.isLightColor(style.customBgColor)
        if (isNight) {
            style = style.copy(
                theme = ReadTheme.DEFAULT,
                bgTextureId = "",
                customBgColor = 0xFFF7F4ED.toInt(),
                textColor = 0xFF2C2C2C.toInt(),
            )
        } else {
            style = style.copy(
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

    private fun setBgTexture(id: String) {
        when (id) {
            BgTextures.NONE -> {
                // 纯色：清除纹理，用当前背景色
                style = style.copy(
                    bgTextureId = "",
                    customBgImageFile = "",
                    theme = ReadTheme.CUSTOM,
                )
                persistAndApplyStyle(keepAnchor = true)
                refreshTextureChips()
                refreshBgColorSwatches()
            }
            BgTextures.IMPORT -> {
                importBgImageLauncher.launch(arrayOf("image/*"))
            }
            else -> {
                val base = BgTextures.baseColor(id) ?: style.customBgColor
                val autoText = ParagraphAdapter.textColorForBackground(base)
                style = style.copy(
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

    private fun rebuildTextureChips() {
        if (!::settingsPanel.isInitialized) return
        val row = settingsPanel.textureRow
        row.removeAllViews()
        textureChips.clear()
        val density = resources.displayMetrics.density
        val marginEnd = (8 * density).toInt()
        fun addChip(id: String, label: String, tint: Int?) {
            val btn = MaterialButton(
                this,
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
            addChip(spec.id, getString(spec.labelRes), spec.baseColor)
        }
        // 导入图片
        addChip(BgTextures.IMPORT, getString(R.string.bg_texture_import), null)
        refreshTextureChips()
    }

    private fun refreshTextureChips() {
        if (!::settingsPanel.isInitialized) return
        val cur = when {
            style.bgTextureId == BgTextures.IMPORT -> BgTextures.IMPORT
            style.bgTextureId.isBlank() -> BgTextures.NONE
            else -> style.bgTextureId
        }
        fun mark(btn: MaterialButton, selected: Boolean) {
            btn.alpha = if (selected) 1f else 0.55f
            btn.strokeWidth = if (selected) (2 * resources.displayMetrics.density).toInt() else 1
        }
        textureChips.forEach { btn ->
            mark(btn, (btn.tag as? String) == cur)
        }
    }

    /** 常用字体色（圆形色点） */
    private val textColorPresets = intArrayOf(
        0xFF2C2C2C.toInt(),
        0xFF1A1A1A.toInt(),
        0xFF3E3224.toInt(),
        0xFF1E3A24.toInt(),
        0xFF1A3344.toInt(),
        0xFF4A148C.toInt(),
        0xFFB71C1C.toInt(),
        0xFF666666.toInt(),
        0xFFC8C8C8.toInt(),
        0xFFFFFFFF.toInt(),
    )

    /** 常用背景纯色 */
    private val bgColorPresets = intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFFF7F4ED.toInt(),
        0xFFFFF8E7.toInt(),
        0xFFF4ECD8.toInt(),
        0xFFC7EDCC.toInt(),
        0xFFDCEEF8.toInt(),
        0xFFF0E8F5.toInt(),
        0xFFECEFF1.toInt(),
        0xFF1A1A1A.toInt(),
        0xFF263238.toInt(),
    )

    private fun rebuildTextColorSwatches() {
        if (!::settingsPanel.isInitialized) return
        val row = settingsPanel.textColorRow
        row.removeAllViews()
        textColorSwatches.clear()
        val density = resources.displayMetrics.density
        // 圆形色点约原 36dp 的 2/3
        val size = (24 * density).toInt()
        val gap = (8 * density).toInt()
        for (c in textColorPresets) {
            val v = makeColorSwatchView(size, gap, c) {
                applyTextColor(c)
            }
            row.addView(v)
            textColorSwatches.add(v)
        }
        // 尾部：自定义 → HSV
        val custom = makeCustomColorChip(size, gap) {
            HsvColorPickerDialog.show(
                this,
                getString(R.string.color_picker_text_title),
                style.textColor,
            ) { c -> applyTextColor(c) }
        }
        row.addView(custom)
        textColorSwatches.add(custom)
        refreshTextColorSwatches()
    }

    private fun rebuildBgColorSwatches() {
        if (!::settingsPanel.isInitialized) return
        val row = settingsPanel.bgColorRow
        row.removeAllViews()
        bgColorSwatches.clear()
        val density = resources.displayMetrics.density
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
                this,
                getString(R.string.color_picker_bg_title),
                style.customBgColor,
            ) { c -> applyBgColor(c) }
        }
        row.addView(custom)
        bgColorSwatches.add(custom)
        refreshBgColorSwatches()
    }

    private fun makeColorSwatchView(
        size: Int,
        marginEnd: Int,
        color: Int,
        onClick: () -> Unit,
    ): View {
        return View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).also {
                it.marginEnd = marginEnd
            }
            background = androidx.core.content.ContextCompat.getDrawable(
                this@ReadingActivity,
                R.drawable.bg_color_swatch,
            )?.mutate()
            backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            tag = color
            setOnClickListener { onClick() }
            contentDescription = String.format("#%06X", color and 0xFFFFFF)
        }
    }

    private fun makeCustomColorChip(size: Int, marginEnd: Int, onClick: () -> Unit): View {
        return MaterialButton(
            this,
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
            text = getString(R.string.color_custom)
            tag = "custom"
            setOnClickListener { onClick() }
        }
    }

    private fun applyTextColor(c: Int) {
        style = style.copy(textColor = c or 0xFF000000.toInt())
        persistAndApplyStyle(keepAnchor = true)
        refreshTextColorSwatches()
    }

    private fun applyBgColor(c: Int) {
        val color = c or 0xFF000000.toInt()
        style = style.copy(
            theme = ReadTheme.CUSTOM,
            customBgColor = color,
            bgTextureId = "",
            customBgImageFile = "",
            // 字色若对比差会不好读：不强制改用户字色
        )
        persistAndApplyStyle(keepAnchor = true)
        refreshTextureChips()
        refreshBgColorSwatches()
    }

    private fun refreshTextColorSwatches() {
        if (!::settingsPanel.isInitialized) return
        val cur = style.textColor or 0xFF000000.toInt()
        textColorSwatches.forEach { v ->
            val selected = when (val t = v.tag) {
                is Int -> (t or 0xFF000000.toInt()) == cur
                else -> {
                    // 自定义：当前色不在预设中
                    textColorPresets.none { (it or 0xFF000000.toInt()) == cur }
                }
            }
            markSwatchSelected(v, selected)
        }
    }

    private fun refreshBgColorSwatches() {
        if (!::settingsPanel.isInitialized) return
        val solidMode = style.bgTextureId.isBlank() || style.bgTextureId == BgTextures.NONE
        val cur = style.customBgColor or 0xFF000000.toInt()
        bgColorSwatches.forEach { v ->
            val selected = if (!solidMode) {
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

    private fun markSwatchSelected(v: View, selected: Boolean) {
        if (v is MaterialButton) {
            v.alpha = if (selected) 1f else 0.55f
            v.strokeWidth = if (selected) (2 * resources.displayMetrics.density).toInt() else 1
            return
        }
        val dens = resources.displayMetrics.density
        v.scaleX = if (selected) 1.12f else 1f
        v.scaleY = if (selected) 1.12f else 1f
        v.foreground = if (selected) {
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_color_swatch_ring)
        } else {
            null
        }
        // 轻微外扩选中感
        v.elevation = if (selected) 3f * dens else 0f
    }

    private fun importBackgroundImage(uri: Uri) {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                BgTextures.importFromUri(this@ReadingActivity, uri)
            }
            if (isFinishing || isDestroyed) return@launch
            if (name.isNullOrBlank()) {
                Toasts.show(this@ReadingActivity, R.string.bg_texture_import_fail)
                return@launch
            }
            style = style.copy(
                bgTextureId = BgTextures.IMPORT,
                customBgImageFile = name,
                theme = ReadTheme.CUSTOM,
            )
            persistAndApplyStyle(keepAnchor = true)
            refreshTextureChips()
            refreshBgColorSwatches()
            Toasts.show(this@ReadingActivity, R.string.bg_texture_import_ok)
        }
    }

    private fun setFont(id: String) {
        style = style.copy(fontFamily = id)
        persistAndApplyStyle(keepAnchor = true)
        refreshFontChips()
    }

    private fun launchInstallFont() {
        // */*：部分 OEM 不暴露 font/* MIME
        installFontLauncher.launch(arrayOf("*/*"))
    }

    private fun installCustomFont(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                CustomFontStore.installFromUri(this@ReadingActivity, uri)
            }
            if (isFinishing || isDestroyed) return@launch
            when (result) {
                is CustomFontStore.InstallResult.Ok -> {
                    Toasts.show(
                        this@ReadingActivity,
                        getString(R.string.font_install_ok, result.entry.name),
                    )
                    rebuildCustomFontChips()
                    setFont(result.entry.id)
                }
                is CustomFontStore.InstallResult.Fail -> {
                    val msg = when (result.reason) {
                        CustomFontStore.FailReason.BAD_FORMAT ->
                            getString(R.string.font_install_bad_format)
                        CustomFontStore.FailReason.TOO_LARGE ->
                            getString(R.string.font_install_too_large)
                        CustomFontStore.FailReason.LIMIT ->
                            getString(R.string.font_install_limit, CustomFontStore.MAX_COUNT)
                        CustomFontStore.FailReason.INVALID_FONT,
                        CustomFontStore.FailReason.IO,
                        -> getString(R.string.font_install_fail)
                    }
                    Toasts.show(this@ReadingActivity, msg)
                }
            }
        }
    }

    private fun confirmDeleteCustomFont(entry: CustomFontStore.Entry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.font_delete_title)
            .setMessage(getString(R.string.font_delete_msg, entry.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                CustomFontStore.delete(this, entry.id)
                ReaderFonts.invalidate(entry.id)
                if (style.fontFamily == entry.id) {
                    style = style.copy(fontFamily = ReaderFonts.ID_DEFAULT)
                    persistAndApplyStyle(keepAnchor = true)
                }
                rebuildCustomFontChips()
                Toasts.show(this, getString(R.string.font_deleted, entry.name))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 在 mono 与「安装」之间注入已装字体 chip */
    private fun rebuildCustomFontChips() {
        if (!::settingsPanel.isInitialized) return
        val row = settingsPanel.fontRow
        customFontChips.forEach { row.removeView(it) }
        customFontChips.clear()

        val installChip = settingsPanel.chipFontInstall
        val insertAt = row.indexOfChild(installChip).coerceAtLeast(0)
        val density = resources.displayMetrics.density
        val marginEnd = (8 * density).toInt()
        val entries = CustomFontStore.list(this)
        entries.forEachIndexed { i, entry ->
            val btn = MaterialButton(
                this,
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
        // 若当前字体记录已不存在，回退默认
        if (ReaderFonts.isCustom(style.fontFamily) &&
            entries.none { it.id == style.fontFamily }
        ) {
            style = style.copy(fontFamily = ReaderFonts.ID_DEFAULT)
            persistAndApplyStyle(keepAnchor = true)
        }
        refreshFontChips()
    }

    private fun refreshFontChips() {
        if (!::settingsPanel.isInitialized) return
        val id = style.fontFamily
        fun mark(btn: MaterialButton, selected: Boolean) {
            btn.alpha = if (selected) 1f else 0.55f
            btn.strokeWidth = if (selected) (2 * resources.displayMetrics.density).toInt() else 1
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
        // 安装钮不高亮
        settingsPanel.chipFontInstall.alpha = 0.85f
    }

    private fun persistAndApplyStyle(keepAnchor: Boolean = true) {
        AppSettings.saveStyle(this, style)
        applyStyleToUi(keepAnchor = keepAnchor)
    }

    private fun applyStyleToUi(keepAnchor: Boolean = true) {
        val textureId = style.bgTextureId
        val textureBase = BgTextures.baseColor(textureId)
        val solidBg = style.customBgColor or 0xFF000000.toInt()
        val bgForChrome = when {
            textureId == BgTextures.IMPORT -> solidBg
            textureBase != null -> textureBase
            else -> solidBg
        }
        val textColor = style.textColor
        val hl = if (ParagraphAdapter.isLightColor(textColor)) {
            // 浅色字 → 深底，高亮偏蓝
            0x884A90C0.toInt()
        } else {
            0x66FFE082.toInt()
        }

        val bgDrawable: android.graphics.drawable.Drawable? = when {
            textureId == BgTextures.IMPORT && style.customBgImageFile.isNotBlank() ->
                BgTextures.importedDrawable(this, style.customBgImageFile)
            textureId.isNotBlank() && textureId != BgTextures.NONE ->
                BgTextures.tiledDrawable(this, textureId)
            else -> null
        }

        if (bgDrawable != null) {
            fun copyBg(): android.graphics.drawable.Drawable? =
                when {
                    textureId == BgTextures.IMPORT ->
                        BgTextures.importedDrawable(this, style.customBgImageFile)
                    else -> BgTextures.tiledDrawable(this, textureId)
                }
            binding.rootReading.background = bgDrawable
            reader.background = copyBg()
            binding.readStatusBar.background = copyBg()
            binding.readTitleBar.background = copyBg()
            binding.tvReadTitle.background = null
            binding.tvReadTitle.setBackgroundColor(0x00000000)
        } else {
            binding.rootReading.setBackgroundColor(bgForChrome)
            reader.setBackgroundColor(bgForChrome)
            binding.readStatusBar.setBackgroundColor(bgForChrome)
            binding.readTitleBar.setBackgroundColor(bgForChrome)
            binding.tvReadTitle.setBackgroundColor(bgForChrome)
        }
        window.statusBarColor = bgForChrome
        window.navigationBarColor = bgForChrome
        val darkChrome = !ParagraphAdapter.isLightColor(bgForChrome) ||
            textureId == BgTextures.NIGHT_GRAIN ||
            style.theme == ReadTheme.NIGHT
        val metaColor = if (darkChrome) 0xFF9A9A9A.toInt() else 0xFF888888.toInt()
        binding.tvBookName.setTextColor(metaColor)
        binding.tvChapterTitle.setTextColor(metaColor)
        binding.tvReadTitle.setTextColor(metaColor)
        binding.tvBattery.setTextColor(metaColor)
        binding.tvClock.setTextColor(metaColor)
        binding.tvProgress.setTextColor(metaColor)
        @Suppress("DEPRECATION")
        if (darkChrome || immersive) {
            window.decorView.systemUiVisibility = 0
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        reader.applyStyle(style, textColor, hl, keepAnchor = keepAnchor)
        updateProgressLabel()
        if (::settingsPanel.isInitialized) {
            refreshTextureChips()
            refreshTextColorSwatches()
            refreshBgColorSwatches()
        }
    }

    /**
     * 全屏开关：竖屏隐藏导航栏、保留系统状态栏；横屏始终全屏（见 [applyLandscapeFullscreenUi]）。
     */
    private fun applyImmersive() {
        applyLandscapeFullscreenUi()
        applyStyleToUi()
    }

    /** 合并同帧内多次方向重铺，避免闪烁 */
    private var pendingOrientRelayout: Runnable? = null
    private var orientRelayoutKeepMenu = false

    private fun applyOrientationMode(
        mode: OrientationMode,
        toast: Boolean,
        allowSensor: Boolean = true,
        force: Boolean = false,
        keepMenu: Boolean = false,
    ) {
        val fixed = if (mode == OrientationMode.AUTO) OrientationMode.PORTRAIT else mode
        val changed = OrientationHelper.apply(
            this,
            fixed,
            allowSensor = false,
            force = force,
        )
        // 方向未变时也重铺（菜单/漫画等），有变化时稍延迟等窗口转完
        if (keepMenu) orientRelayoutKeepMenu = true
        scheduleOrientRelayout(debounceMs = if (changed) 48L else 0L)
        if (toast) {
            val label = when (fixed) {
                OrientationMode.LANDSCAPE -> getString(R.string.orient_landscape)
                else -> getString(R.string.orient_portrait)
            }
            Toasts.show(this, getString(R.string.orient_switched, label))
        }
    }

    /** 单次合并重铺（方向/菜单/漫画布局），去掉多次 post 连环闪 */
    private fun scheduleOrientRelayout(debounceMs: Long = 0L) {
        if (!::binding.isInitialized) return
        pendingOrientRelayout?.let { binding.root.removeCallbacks(it) }
        val r = Runnable {
            pendingOrientRelayout = null
            if (isFinishing || isDestroyed) return@Runnable
            val keepMenu = orientRelayoutKeepMenu
            orientRelayoutKeepMenu = false
            exportPanelOpen = false
            if (keepMenu) {
                // 先清残留高度，再保持菜单可见并按新宽度重铺
                collapseBottomChromeHard()
                chromeVisible = true
            } else {
                chromeVisible = false
                collapseBottomChromeHard()
            }
            applyPortraitColumnLayout()
            applyChromeVisibility()
            if (keepMenu && chromeVisible) {
                forceMenuLayout(preservePage = true)
            }
            // 换向后：旧 pan/zoom/绝对 scrollY 全部作废；连续图必须按新宽度重绑 item
            if (mangaMode) {
                pendingMangaTransform = Triple(1f, 0f, 0f)
                pendingMangaScrollOffset = 0
                pendingMangaScrollY = 0
                pendingMangaScrollIndex = mangaIndex
                if (isMangaContinuousLayout()) {
                    binding.mangaContinuousHost.resetZoom(notify = false)
                }
                updateMangaLayoutForOrientation()
                binding.mangaHost.post {
                    if (!mangaMode || isFinishing || isDestroyed) return@post
                    if (isMangaContinuousLayout()) {
                        binding.mangaContinuousHost.resetZoom(notify = false)
                        // 强制按新列表宽重算高度（修竖→横半宽图）
                        mangaContinuousAdapter?.notifyDataSetChanged()
                        binding.mangaRecycler.post {
                            if (!mangaMode || !isMangaContinuousLayout()) return@post
                            scrollMangaContinuousTo(mangaIndex, smooth = false)
                            // 再刷一次：确保 layout 后 width 正确
                            mangaContinuousAdapter?.notifyDataSetChanged()
                            binding.mangaRecycler.post {
                                if (mangaMode && isMangaContinuousLayout()) {
                                    scrollMangaContinuousTo(mangaIndex, smooth = false)
                                }
                            }
                        }
                    } else {
                        showMangaIndex(mangaIndex)
                    }
                }
            }
            if (::reader.isInitialized) {
                reader.requestLayout()
                syncReaderBottomObscured()
            }
            binding.root.requestLayout()
            // 等新尺寸落稳再收一次底栏高度残留；keepMenu 时重新展开菜单
            binding.root.post {
                if (isFinishing || isDestroyed) return@post
                if (keepMenu) {
                    collapseBottomChromeHard()
                    chromeVisible = true
                    applyChromeVisibility()
                    forceMenuLayout(preservePage = true)
                } else {
                    collapseBottomChromeHard()
                    applyChromeVisibility()
                }
                applyPortraitColumnLayout()
                binding.root.requestLayout()
            }
        }
        pendingOrientRelayout = r
        if (debounceMs <= 0L) {
            binding.root.post(r)
        } else {
            binding.root.postDelayed(r, debounceMs)
        }
    }

    /**
     * 强制收起 bottomChrome 内所有子面板，高度归 WRAP，避免旋转后
     * 状态栏被顶到屏幕中间。
     */
    private fun collapseBottomChromeHard() {
        if (!::binding.isInitialized) return
        chromeVisible = false
        binding.readMenuHost.visibility = View.GONE
        binding.ttsExportHost.visibility = View.GONE
        if (!ttsBarOpen) {
            binding.ttsBar.visibility = View.GONE
        }
        binding.readMenuHost.layoutParams = binding.readMenuHost.layoutParams.apply {
            width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.bottomChrome.translationY = 0f
        binding.readStatusBar.translationY = 0f
        binding.bottomChrome.minimumHeight = 0
        val lp = binding.bottomChrome.layoutParams
        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
            lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        }
        binding.bottomChrome.layoutParams = lp
        // 状态栏钉在底栏上方、父布局底部链路
        val slp = binding.readStatusBar.layoutParams
        if (slp is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
            slp.bottomToTop = binding.bottomChrome.id
            slp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            binding.readStatusBar.layoutParams = slp
        }
        binding.bottomChrome.requestLayout()
        binding.readStatusBar.requestLayout()
    }

    private fun applyEdgeSwipeFlags() {
        if (!::reader.isInitialized) return
        reader.leftEdgeEnabled = AppSettings.leftEdgeAction(this) != EdgeSwipeAction.NONE
        reader.rightEdgeEnabled = AppSettings.rightEdgeAction(this) != EdgeSwipeAction.NONE
    }

    /**
     * 边缘滑动。
     * [direction]：+1 上滑，-1 下滑（来自 VirtualReaderView）。
     * 字号：下滑加大、上滑减小；语速仍为上滑加快、下滑减慢。
     */
    private fun handleEdgeAdjust(isLeft: Boolean, direction: Int) {
        val action = if (isLeft) {
            AppSettings.leftEdgeAction(this)
        } else {
            AppSettings.rightEdgeAction(this)
        }
        when (action) {
            EdgeSwipeAction.RATE -> {
                val next = (AppSettings.ttsRate(this) + direction * 0.1f)
                    .let { (kotlin.math.round(it * 10f) / 10f) }
                    .coerceIn(0.5f, 2.5f)
                if (next == AppSettings.ttsRate(this)) return
                AppSettings.setTtsRate(this, next)
                tts.setSpeechRate(next, restartCurrent = true)
                updateTtsRateLabel(next)
                Toasts.show(this, getString(R.string.edge_toast_rate, next))
            }
            EdgeSwipeAction.FONT -> {
                // 下滑(direction=-1)加大字号，上滑(+1)减小；步进 0.5sp，支持小数，不弹 Toast
                val next = (style.fontSizeSp - direction * 0.5f)
                    .let { (kotlin.math.round(it * 2f) / 2f) }
                    .coerceIn(12f, 36f)
                if (next == style.fontSizeSp) return
                style = style.copy(fontSizeSp = next)
                persistAndApplyStyle(keepAnchor = true)
                if (::settingsPanel.isInitialized) {
                    settingsPanel.seekFontSize.progress =
                        (style.fontSizeSp - 12f).toInt().coerceIn(0, 24)
                    settingsPanel.tvFontSize.text = formatFontSizeLabel(style.fontSizeSp)
                }
            }
            EdgeSwipeAction.NONE -> Unit
        }
    }

    /** 字号标签：整数不带小数点，半号显示一位小数 */
    private fun formatFontSizeLabel(sp: Float): String {
        val rounded = kotlin.math.round(sp * 2f) / 2f
        return if (abs(rounded - rounded.toInt()) < 0.01f) {
            rounded.toInt().toString()
        } else {
            String.format("%.1f", rounded)
        }
    }

    private fun loadContent() {
        val asset = intent.getStringExtra(EXTRA_ASSET)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val titleExtra = intent.getStringExtra(EXTRA_TITLE)
        val encodingExtra = intent.getStringExtra(EXTRA_ENCODING)
        val preferredEncoding = encodingExtra?.takeIf { it.isNotBlank() }
            ?: uriStr?.let { BookEncodingStore.get(this, it) }
            ?: asset?.let { BookEncodingStore.get(this, "asset://$it") }
        val bookKey = uriStr ?: asset?.let { "asset://$it" }.orEmpty()
        val chineseMode = if (bookKey.isNotBlank()) {
            BookChineseModeStore.get(this, bookKey)
        } else {
            ChineseConvert.Mode.OFF
        }

        streamerJob?.cancel()
        bookStreamer?.cancel()
        bookStreamer = null
        streamerLoading = false
        showLoadOverlay(getString(R.string.loading_book))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        asset != null -> com.whj.reader.data.BookOpenResult(
                            book = TextLoader.loadFromAssets(
                                this@ReadingActivity,
                                asset,
                                titleExtra ?: getString(R.string.unnamed),
                                preferredEncoding = preferredEncoding,
                                chineseMode = chineseMode,
                            ),
                            streamer = null,
                        )
                        uriStr != null -> BookLoader.openFromUri(
                            this@ReadingActivity,
                            Uri.parse(uriStr),
                            titleExtra,
                            preferredEncoding = preferredEncoding,
                            chineseMode = chineseMode,
                            onProgress = { msg, cur, tot ->
                                runOnUiThread { updateLoadOverlay(msg, cur, tot) }
                            },
                        )
                        else -> error("未指定文件")
                    }
                }
            }
            result.onSuccess { open ->
                // 遮罩保持到定位完成（见 maybeRevealReaderAfterRestore），避免闪首页
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

    private fun applyLoadedBook(loaded: LoadedBook, isInitial: Boolean) {
        book = loaded
        fileKey = loaded.uri
        displayTitle = loaded.title
        updateStreamTitle(loaded)
        if (loaded.imagePaths.isNotEmpty()) {
            mangaPaths = loaded.imagePaths.filter { File(it).isFile }
        }
        updateMobiModeButtons()

        if (isInitial) {
            val usedEnc = loaded.encoding
            if (!usedEnc.equals("UTF-8", ignoreCase = true)) {
                if (BookEncodingStore.get(this, loaded.uri) == null) {
                    BookEncodingStore.set(this, loaded.uri, usedEnc)
                }
            }
            allowProgressSave = false
            // 用户偏好漫画，或无文字纯图 MOBI → 自动漫画模式
            val imageOnly = isImageOnlyMobi(loaded)
            val viewMode = AppSettings.mobiViewMode(this)
            val wantManga = isMobiBook() &&
                mangaPaths.isNotEmpty() &&
                (viewMode != AppSettings.MobiViewMode.TEXT || imageOnly)
            if (wantManga) {
                // 纯图 MOBI 无偏好时默认单图漫画
                mangaContinuousPref = when {
                    viewMode == AppSettings.MobiViewMode.CONTINUOUS -> true
                    viewMode == AppSettings.MobiViewMode.MANGA -> false
                    imageOnly -> false
                    else -> false
                }
                if (imageOnly && viewMode == AppSettings.MobiViewMode.TEXT) {
                    AppSettings.setMobiViewMode(this, AppSettings.MobiViewMode.MANGA)
                }
            }
            val saved = AppSettings.progressFor(this, loaded.uri)
            val shelfPara = BookshelfStore.findBookByUri(this, loaded.uri)?.lastParagraph ?: 0
            val mangaView = if (wantManga) {
                AppSettings.loadMangaViewState(this, loaded.uri)
            } else {
                null
            }
            pendingRestorePara = if (wantManga) {
                // 漫画进度在 enterMangaMode 里按图片索引恢复
                -1
            } else {
                maxOf(saved, shelfPara)
            }
            // 书架进度：漫画用图片索引，避免打开时把进度冲成 0
            val shelfProgressHint = if (wantManga) {
                val rp = ReadingProgressStore.get(this, loaded.uri)
                when {
                    mangaView != null && mangaView.index >= 0 -> mangaView.index
                    rp != null && rp.position >= 0 -> rp.position
                    else -> shelfPara
                }
            } else {
                pendingRestorePara.coerceAtLeast(0)
            }

            // 恢复完成前隐藏正文 + 保持加载遮罩，避免闪首页 1 秒
            reader.visibility = android.view.View.INVISIBLE
            if (pendingRestorePara > 0) {
                updateLoadOverlay(
                    getString(R.string.loading_locate_progress),
                    0,
                    0,
                )
            }
            reader.setContent(loaded.paragraphs)
            applyStyleToUi(keepAnchor = false)
            tts.setDocument(
                loaded.paragraphs,
                TextLoader.SentenceLineBreakMode.NEWLINE,
            )
            tts.setSessionTitle(displayTitle.ifBlank { loaded.title })
            applyChromeVisibility()

            BookshelfStore.updateIfExists(
                this,
                uri = loaded.uri,
                displayName = loaded.title,
                lastParagraph = shelfProgressHint,
            )
            if (!wantManga) {
                ReadingProgressStore.saveTxt(
                    this,
                    loaded.uri,
                    pendingRestorePara.coerceIn(0, loaded.paragraphs.lastIndex.coerceAtLeast(0)),
                    loaded.paragraphs.size,
                    fileExt = progressFileExt(),
                )
            }
            AppSettings.setLastBook(this, loaded.uri, loaded.title)

            // 布局后再尝试定位；未到目标前不 reveal
            reader.post {
                if (isFinishing || isDestroyed) return@post
                if (wantManga) {
                    enterMangaMode(restoreIndex = true)
                } else {
                    tryRestoreProgress(loaded)
                    maybeRevealReaderAfterRestore()
                }
            }
        } else {
            if (mangaMode) {
                // 漫画模式不依赖流式正文，仅刷新进度文案
                updateProgressLabel()
                return
            }
            reader.updateContent(loaded.paragraphs, keepScroll = true)
            if (::tts.isInitialized) {
                tts.updateDocumentKeepPosition(
                    loaded.paragraphs,
                    TextLoader.SentenceLineBreakMode.NEWLINE,
                )
            }
            tryRestoreProgress(loaded)
            maybeRevealReaderAfterRestore()
            updateProgressLabel()
            // 总段数变化时刷新进度存储的 total
            ReadingProgressStore.saveTxt(
                this,
                loaded.uri,
                reader.firstVisibleParagraph(),
                loaded.paragraphs.size,
                fileExt = progressFileExt(),
            )
        }
    }

    private fun tryRestoreProgress(loaded: LoadedBook) {
        val target = pendingRestorePara
        if (target <= 0) {
            updateProgressLabel()
            return
        }
        if (target in loaded.paragraphs.indices) {
            reader.scrollToParagraph(target)
            // 目标已在当前已载入正文内 → 清除待恢复
            if (loaded.isComplete || target <= loaded.paragraphs.lastIndex) {
                pendingRestorePara = -1
            }
        } else {
            // 仍在加载：更新遮罩进度
            val tot = loaded.streamTotal.coerceAtLeast(1)
            val cur = loaded.streamCurrent.coerceIn(0, tot)
            updateLoadOverlay(
                getString(R.string.loading_locate_progress),
                cur,
                tot,
            )
        }
        updateProgressLabel()
    }

    /**
     * 打开书时：目标段已就绪（或无需恢复）才显示正文并关掉遮罩。
     * 避免先闪首页再跳转。
     */
    private fun maybeRevealReaderAfterRestore() {
        if (isFinishing || isDestroyed) return
        if (!::reader.isInitialized) return
        if (mangaMode) {
            hideLoadOverlay()
            allowProgressSave = true
            updateProgressLabel()
            return
        }
        // 仍在等目标段
        if (pendingRestorePara > 0) {
            val last = book?.paragraphs?.lastIndex ?: -1
            if (pendingRestorePara > last) {
                // 继续加载
                if (bookStreamer != null) {
                    requestStreamBatch()
                } else {
                    // 无 streamer 却仍超范围：落到末尾并显示
                    pendingRestorePara = -1
                }
                return
            }
            // 已在范围内但 flag 未清
            book?.let { tryRestoreProgress(it) }
            if (pendingRestorePara > 0) return
        }
        if (reader.visibility != android.view.View.VISIBLE) {
            reader.visibility = android.view.View.VISIBLE
        }
        hideLoadOverlay()
        allowProgressSave = true
        saveProgress(reader.firstVisibleParagraph())
        updateProgressLabel()
        updateChapterTitleBar(reader.firstVisibleParagraph())
        updateBookmarkButton()
    }

    private fun updateStreamTitle(loaded: LoadedBook, progressMsg: String? = null) {
        if (!::binding.isInitialized) return
        displayTitle = loaded.title
        val base = loaded.title
        // 加载中时左侧文件名后附加百分比
        val left = when {
            loaded.isComplete -> base
            progressMsg != null -> "$base · $progressMsg"
            else -> {
                val tot = loaded.streamTotal.coerceAtLeast(1)
                val cur = loaded.streamCurrent.coerceIn(0, tot)
                val pct = (cur * 100 / tot).coerceIn(0, 99)
                getString(R.string.load_stream_title, base, pct)
            }
        }
        binding.tvBookName.text = left
        binding.tvReadTitle.text = left // 兼容
        if (::reader.isInitialized) {
            updateChapterTitleBar(reader.firstVisibleParagraph())
        } else {
            binding.tvChapterTitle.text = ""
        }
    }

    /** 顶部右侧：当前章节标题（根据可见段向前找最近章节） */
    private fun updateChapterTitleBar(firstVisiblePara: Int) {
        if (!::binding.isInitialized) return
        val chapters = book?.chapters.orEmpty()
        if (chapters.isEmpty()) {
            binding.tvChapterTitle.text = ""
            return
        }
        val p = firstVisiblePara.coerceAtLeast(0)
        val ch = chapters.lastOrNull { it.paragraphIndex <= p }
            ?: chapters.firstOrNull()
        binding.tvChapterTitle.text = ch?.title.orEmpty()
    }

    /** 解析 EPUB/MOBI 站内链接并跳转；外链尝试系统浏览器 */
    private fun handleLinkClick(href: String) {
        val raw = href.trim()
        if (raw.isEmpty()) return
        val lower = raw.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("mailto:")
        ) {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(raw)).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                    ),
                )
            }.onFailure {
                Toasts.show(this, getString(R.string.link_open_fail))
            }
            return
        }
        val target = resolveInternalLink(raw)
        if (target < 0) {
            Toasts.show(this, getString(R.string.link_not_found))
            return
        }
        // 按需续载未到目标时：记下待恢复位置并继续解析
        val maxIdx = book?.paragraphs?.lastIndex ?: -1
        if (target > maxIdx) {
            pendingRestorePara = target
            requestStreamBatch()
            Toasts.show(this, getString(R.string.link_loading_target))
            return
        }
        hideChrome()
        reader.scrollToParagraph(target)
        if (allowProgressSave) saveProgress(target)
        updateProgressLabel()
        updateChapterTitleBar(target)
    }

    private fun resolveInternalLink(href: String): Int {
        val map = book?.linkTargets.orEmpty()
        if (map.isEmpty()) return -1
        var h = href.trim()
        h = runCatching {
            java.net.URLDecoder.decode(h, Charsets.UTF_8.name())
        }.getOrDefault(h)
        h = h.replace('\\', '/').trim()
        // 去掉开头 ./
        while (h.startsWith("./")) h = h.removePrefix("./")
        val hash = h.substringAfter('#', missingDelimiterValue = "").trim()
        var path = h.substringBefore('#', missingDelimiterValue = h).trim()
        // 纯锚点 #id
        if (path.isEmpty() && hash.isNotEmpty()) {
            map[hash]?.let { return it }
            map[hash.lowercase(Locale.ROOT)]?.let { return it }
            return -1
        }
        // 规范化 ../ 与 .
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
        // 优先精确：path#id / file#id / id（与 EpubLoader putLinkTarget 键一致）
        if (hash.isNotEmpty()) {
            if (path.isNotEmpty()) {
                candidates += "$path#$hash"
                candidates += "$fileName#$hash"
                // OEBPS/text/foo.xhtml#id 等
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
        // 末兜底：仅文件名（忽略目录）对所有 key 后缀匹配 path#hash
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

    private fun showOpenFailGuide(
        reason: OpenFailGuide.Reason,
        detail: String?,
        exitOnClose: Boolean = true,
    ) {
        val title = intent.getStringExtra(EXTRA_TITLE)
        val isAsset = !intent.getStringExtra(EXTRA_ASSET).isNullOrBlank()
        OpenFailGuide.show(
            activity = this,
            reason = reason,
            detail = detail,
            bookTitle = title,
            onGrantPermission = if (isAsset) {
                null
            } else {
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        openFailPermissionLauncher.launch(
                            StorageAccess.manageAllFilesIntent(this),
                        )
                    } else {
                        // 旧版：引导后直接重试
                        loadContent()
                    }
                }
            },
            onReselect = if (isAsset) {
                null
            } else {
                {
                    reselectDocLauncher.launch(
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
                if (exitOnClose) finish()
            },
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
            // 若用户重选了 PDF，跳转 PDF 阅读
            if (BookFileType.isPdfUri(this@ReadingActivity, uri, name) ||
                BookFileType.isPdf(name)
            ) {
                val stable = withContext(Dispatchers.IO) {
                    OpenFailGuide.bindReselectedFile(
                        this@ReadingActivity,
                        oldUri = oldUri,
                        newUri = uri,
                        displayName = name,
                    )
                }
                Toasts.show(this@ReadingActivity, R.string.open_failed_reselect_done)
                startActivity(
                    Intent(this@ReadingActivity, PdfReadingActivity::class.java)
                        .putExtra(PdfReadingActivity.EXTRA_URI, stable)
                        .putExtra(PdfReadingActivity.EXTRA_TITLE, BookFileType.stripBookExt(name)),
                )
                finish()
                return@launch
            }
            val stable = withContext(Dispatchers.IO) {
                OpenFailGuide.bindReselectedFile(
                    this@ReadingActivity,
                    oldUri = oldUri,
                    newUri = uri,
                    displayName = name,
                )
            }
            intent.putExtra(EXTRA_URI, stable)
            intent.putExtra(EXTRA_TITLE, BookFileType.stripBookExt(name))
            intent.removeExtra(EXTRA_ASSET)
            Toasts.show(this@ReadingActivity, R.string.open_failed_reselect_done)
            loadContent()
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
                binding.btnTtsPlayPause.contentDescription = getString(R.string.tts_pause)
                // 不显示段数/句数，只提示状态
                binding.tvTtsStatus.text = getString(R.string.tts_speaking)
            }
            TtsManager.State.PAUSED -> {
                binding.btnTtsPlayPause.setImageResource(R.drawable.ic_play)
                binding.btnTtsPlayPause.contentDescription = getString(R.string.tts_resume)
                binding.tvTtsStatus.text = getString(R.string.tts_paused)
            }
            TtsManager.State.IDLE -> {
                binding.btnTtsPlayPause.setImageResource(R.drawable.ic_play)
                binding.btnTtsPlayPause.contentDescription = getString(R.string.tts_play)
                binding.tvTtsStatus.text = when {
                    !snapshot.ready -> snapshot.statusMessage.ifBlank { getString(R.string.tts_not_ready) }
                    else -> getString(R.string.tts_idle)
                }
            }
        }
        binding.btnTtsRetry.isVisible = !snapshot.ready
    }

    private fun saveProgress(paragraphIndex: Int) {
        if (fileKey.isEmpty() || !allowProgressSave) return
        val pos: Int
        val total: Int
        if (mangaMode && mangaPaths.isNotEmpty()) {
            pos = mangaIndex.coerceIn(0, mangaPaths.lastIndex)
            total = mangaPaths.size
            // 专用漫画索引 + 缩放（与正文段落进度隔离）
            saveMangaViewStateNow()
        } else {
            pos = paragraphIndex
            total = book?.paragraphs?.size ?: 0
        }
        AppSettings.saveProgress(this, fileKey, pos)
        BookshelfStore.updateProgress(this, fileKey, pos)
        ReadingProgressStore.saveTxt(
            this,
            fileKey,
            pos,
            total,
            fileExt = progressFileExt(),
        )
        if (displayTitle.isNotEmpty()) {
            AppSettings.setLastBook(this, fileKey, displayTitle)
        }
    }

    private fun toggleChrome() {
        if (chromeVisible) hideChrome() else showChrome()
    }

    private fun showChrome() {
        chromeVisible = true
        chromeShownAtMs = android.os.SystemClock.uptimeMillis()
        updateOrientMenuIcon()
        applyChromeVisibility()
        // 等顶栏布局后再算书签锚点（避免 height=0 / 旧图标状态）
        binding.topBar.post { updateBookmarkButton() }
    }

    private fun hideChrome() {
        chromeVisible = false
        applyChromeVisibility()
    }

    private fun hasDisplayCutout(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 28) return false
        val cutout = window.decorView.rootWindowInsets?.displayCutout
            ?: return false
        return cutout.boundingRects.isNotEmpty()
    }

    /** 内容排版是否横屏（跟视角模式） */
    private fun isLandscape(): Boolean {
        val mode = AppSettings.orientationMode(this)
        val root = if (::binding.isInitialized) binding.root else null
        return OrientationHelper.isEffectiveLandscape(this, mode, root)
    }

    private fun isWindowLandscape(): Boolean {
        val root = if (::binding.isInitialized) binding.root else null
        return OrientationHelper.isWindowLandscape(this, root)
    }

    /** 大屏竖屏：正文/漫画收成居中竖栏 */
    /** 横竖模式均占满窗口，清除历史上的「中间竖栏」左右 padding */
    private fun applyPortraitColumnLayout() {
        if (!::binding.isInitialized) return
        if (binding.readerView.paddingLeft != 0 || binding.readerView.paddingRight != 0) {
            binding.readerView.setPadding(0, 0, 0, 0)
        }
        if (binding.mangaHost.paddingLeft != 0 || binding.mangaHost.paddingRight != 0) {
            binding.mangaHost.setPadding(0, 0, 0, 0)
        }
    }

    private fun sanitizeBottomChrome() {
        if (!::binding.isInitialized) return
        if (!chromeVisible) {
            collapseBottomChromeHard()
            return
        }
        if (!exportPanelOpen) {
            binding.ttsExportHost.visibility = View.GONE
        }
        if (!ttsBarOpen) {
            binding.ttsBar.visibility = View.GONE
        }
        binding.bottomChrome.translationY = 0f
        binding.readStatusBar.translationY = 0f
        binding.bottomChrome.minimumHeight = 0
        val lp = binding.bottomChrome.layoutParams
        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        binding.bottomChrome.layoutParams = lp
        binding.bottomChrome.requestLayout()
    }

    /**
     * 横屏模式：全屏沉浸 + 藏标题/底状态栏；竖屏模式：恢复。
     * 大屏以用户选择的模式为准（[isLandscape]），不跟系统 letterbox 窗口误判。
     */
    private fun applyLandscapeFullscreenUi() {
        if (!::binding.isInitialized) return
        val landUi = isLandscape()
        binding.readTitleBar.isVisible = !landUi
        binding.readStatusBar.isVisible = !landUi
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (landUi) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else if (immersive) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * 菜单（顶栏+图标）/ 合成面板 / TTS 条互斥：
     * - 有菜单 → 隐藏 TTS 与合成面板
     * - 合成面板 → 隐藏菜单与 TTS
     * - 无菜单且已打开朗读 → 显示 TTS 条
     */
    private fun applyChromeVisibility() {
        applyLandscapeFullscreenUi()
        binding.topBar.isVisible = chromeVisible && !exportPanelOpen
        binding.ttsBar.isVisible = !chromeVisible && !exportPanelOpen && ttsBarOpen
        val menuHost = binding.readMenuHost
        val exportHost = binding.ttsExportHost
        if (exportPanelOpen) {
            menuHost.visibility = View.GONE
            exportHost.visibility = View.VISIBLE
            exportPanel.root.visibility = View.VISIBLE
            exportHost.bringToFront()
            if (binding.readStatusBar.isVisible) binding.readStatusBar.bringToFront()
            binding.bottomChrome.bringToFront()
        } else if (chromeVisible) {
            exportHost.visibility = View.GONE
            menuHost.visibility = View.VISIBLE
            readMenu.root.visibility = View.VISIBLE
            val lp = menuHost.layoutParams
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            menuHost.layoutParams = lp
            menuHost.bringToFront()
            if (binding.readStatusBar.isVisible) binding.readStatusBar.bringToFront()
            binding.bottomChrome.bringToFront()
            binding.topBar.bringToFront()
            menuHost.post { if (chromeVisible && !exportPanelOpen) forceMenuLayout() }
        } else {
            menuHost.visibility = View.GONE
            exportHost.visibility = View.GONE
        }
        // TTS/底栏高度变化后同步给阅读区（跟读可见判定）
        binding.bottomChrome.post { syncReaderBottomObscured() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 只重铺一次，禁止再次 setRequestedOrientation；不保留菜单
        orientRelayoutKeepMenu = false
        scheduleOrientRelayout(debounceMs = 0L)
    }

    /** TTS 条 + 底栏可见高度 → VirtualReaderView.bottomObscuredPx */
    private fun syncReaderBottomObscured() {
        if (!::reader.isInitialized || !::binding.isInitialized) return
        var h = 0
        if (binding.readStatusBar.isVisible) {
            h += binding.readStatusBar.height.coerceAtLeast(0)
        }
        if (binding.bottomChrome.isVisible) {
            // ttsBar / menu 在 bottomChrome 内
            if (binding.ttsBar.isVisible) {
                h += binding.ttsBar.height.coerceAtLeast(0)
            }
            if (binding.readMenuHost.isVisible && chromeVisible) {
                h += binding.readMenuHost.height.coerceAtLeast(0)
            }
            if (binding.ttsExportHost.isVisible && exportPanelOpen) {
                h += binding.ttsExportHost.height.coerceAtLeast(0)
            }
        }
        reader.bottomObscuredPx = h.toFloat()
    }

    private fun premeasureReadMenu() {
        val host = binding.readMenuHost
        host.visibility = View.INVISIBLE
        host.post {
            forceMenuLayout()
            if (!chromeVisible) host.visibility = View.GONE
        }
    }

    /** 两屏分页：惯性 fling 落到目标屏，慢滑吸附最近屏 */
    private fun setupMenuPagerSnap() {
        val pager = readMenu.menuPager
        pager.pageCount = 2
        pager.onPageSettled = { page -> updateMenuPageDots(page) }
        pager.setOnScrollChangeListener { _, _, _, _, _ ->
            updateMenuPageDots()
        }
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

    /**
     * 底部菜单：两屏分页，第 1 屏固定 **2 行 × 4 列**。
     * 旋转后按新屏宽重设每页宽度，保持 2×4，不关菜单时可 [preservePage]。
     */
    private fun forceMenuLayout(preservePage: Boolean = false) {
        if (!::binding.isInitialized || !::readMenu.isInitialized) return
        val host = binding.readMenuHost
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val laidOutW = binding.bottomChrome.width.takeIf { it > 0 }
            ?: binding.root.width.takeIf { it > 0 }
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
        binding.bottomChrome.requestLayout()
        readMenu.menuPager.settleToPage(prevPage, smooth = false)
        updateMenuPageDots(prevPage)
    }

    private fun jumpChapter(delta: Int) {
        if (mangaMode) {
            // 漫画：上下章=翻图，保持菜单不关
            mangaGo(delta)
            return
        }
        val b = book ?: return
        val chapters = b.chapters
        if (chapters.isEmpty()) {
            Toasts.show(this, R.string.toc_empty)
            return
        }
        val cur = reader.firstVisibleParagraph()
        val idx = chapters.indexOfLast { it.paragraphIndex <= cur }.coerceAtLeast(0)
        val target = idx + delta
        when {
            target < 0 -> Toasts.show(this, R.string.no_prev_chapter)
            target > chapters.lastIndex -> {
                // 章节表尚未完全解析时，继续加载再试
                if (bookStreamer != null && !b.isComplete) {
                    pendingRestorePara = (b.paragraphs.size + 50).coerceAtLeast(pendingRestorePara)
                    requestStreamBatch()
                    Toasts.show(this, R.string.link_loading_target)
                } else {
                    Toasts.show(this, R.string.no_next_chapter)
                }
            }
            else -> {
                val ch = chapters[target]
                val p = ch.paragraphIndex
                if (p < 0 || p > b.paragraphs.lastIndex) {
                    // 目录有缓存段位但正文未载入：按需续载
                    if (p >= 0) pendingRestorePara = p
                    else if (ch.spineIndex >= 0) {
                        // 尚无段索引：按 spine 预估目标，多载几批
                        pendingRestorePara =
                            (b.paragraphs.size + (ch.spineIndex + 1) * 80).coerceAtLeast(0)
                    } else {
                        pendingRestorePara = b.paragraphs.size + 50
                    }
                    requestStreamBatch()
                    Toasts.show(this, R.string.link_loading_target)
                    return
                }
                // 上/下一章滚动时不关底部菜单（与 PDF 上一页/下一页一致）
                ignoreScrollChromeHideUntilMs =
                    android.os.SystemClock.uptimeMillis() + 1_200L
                reader.scrollToParagraph(p)
                saveProgress(p)
                updateProgressLabel()
                updateChapterTitleBar(p)
                if (tts.currentState().state != TtsManager.State.IDLE) {
                    tts.jumpToParagraph(p, autoPlay = true)
                } else {
                    reader.clearHighlight()
                }
            }
        }
    }

    /**
     * 进度跳转：0~100% 滚动条，拖动时实时跳转位置。
     * 漫画模式按图片序号跳转。
     */
    private fun showProgressJumpSheet() {
        if (book == null) return
        if (mangaMode && mangaPaths.isNotEmpty()) {
            showMangaProgressJumpSheet()
            return
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }
        val tvPercent = android.widget.TextView(this).apply {
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, pad / 2)
        }
        // 0.01% 精度：0..10000
        val seek = android.widget.SeekBar(this).apply {
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
                updateProgressLabel()
                saveProgress(reader.firstVisibleParagraph())
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
                saveProgress(reader.firstVisibleParagraph())
                updateProgressLabel()
            }
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_jump)
            .setView(container)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showMangaProgressJumpSheet() {
        if (mangaPaths.isEmpty()) return
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }
        val tvLabel = android.widget.TextView(this).apply {
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, pad / 2)
        }
        val max = (mangaPaths.size - 1).coerceAtLeast(0)
        val seek = android.widget.SeekBar(this).apply {
            this.max = max
            progress = mangaIndex.coerceIn(0, max)
        }
        fun refreshLabel(idx: Int) {
            tvLabel.text = getString(R.string.mobi_manga_progress, idx + 1, mangaPaths.size)
        }
        refreshLabel(mangaIndex)
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
                    showMangaIndex(progress)
                    updateProgressLabel()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (allowProgressSave) saveProgress(mangaIndex)
                updateProgressLabel()
            }
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_jump)
            .setView(container)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showVoicePicker() {
        TtsVoicePicker.show(this, tts) {
            if (tts.currentState().state == TtsManager.State.SPEAKING) {
                val snap = tts.currentState()
                tts.playFrom(snap.paragraphIndex, snap.sentenceIndex)
            }
        }
    }

    private fun showTocSheet() {
        val b = book ?: return
        val dialog = BottomSheetDialog(this)
        val sheet = SheetTocBinding.inflate(LayoutInflater.from(this))
        dialog.setContentView(sheet.root)

        val curPara = bookmarkAnchorParagraph()
        val totalParas = b.paragraphs.size

        fun jumpTo(index: Int, spineIndex: Int = -1) {
            dialog.dismiss()
            val last = book?.paragraphs?.lastIndex ?: -1
            if (index < 0 || index > last) {
                // 大 EPUB：目录段位来自缓存，正文按需续载后再跳
                pendingRestorePara = when {
                    index >= 0 -> index
                    spineIndex >= 0 ->
                        (last + 1 + (spineIndex + 1) * 80).coerceAtLeast(0)
                    else -> last + 50
                }
                requestStreamBatch()
                Toasts.show(this, R.string.link_loading_target)
                return
            }
            reader.scrollToParagraph(index)
            saveProgress(index)
            updateProgressLabel()
            updateChapterTitleBar(index)
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
                BookmarkStore.remove(this, bm.fileKey, bm.paragraphIndex)
                val items = BookmarkStore.list(this, fileKey).map { TocItem.BookmarkItem(it) }
                bookmarkAdapter.submit(items, curPara, totalParas)
                updateBookmarkButton()
                Toasts.show(this, R.string.bookmark_removed)
            },
        )
        chapterAdapter.submit(
            b.chapters.map { TocItem.ChapterItem(it) },
            curPara,
            totalParas,
        )
        bookmarkAdapter.submit(
            BookmarkStore.list(this, fileKey).map { TocItem.BookmarkItem(it) },
            curPara,
            totalParas,
        )

        val titles = listOf(getString(R.string.toc), getString(R.string.bookmark))
        val adapters = listOf(chapterAdapter, bookmarkAdapter)
        val emptyMsgs = listOf(R.string.toc_empty, R.string.bookmark_empty)

        sheet.vpToc.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = 2

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
                    rv.layoutManager = LinearLayoutManager(this@ReadingActivity)
                }
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
                // 目录页：滚到当前章节
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
                // 每个 page 只挂一次 observer，避免重复注册
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

        // 打开即为最大高度，不要先半屏再上拉两段
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet,
            ) ?: return@setOnShowListener
            val maxH = (resources.displayMetrics.heightPixels * 0.92f).toInt()
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
            behavior.expandedOffset = (resources.displayMetrics.heightPixels - maxH)
                .coerceAtLeast(0)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }
}
