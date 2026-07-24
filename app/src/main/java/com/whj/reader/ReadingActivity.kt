package com.whj.reader

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
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
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
import com.whj.reader.data.BookChapterPatternStore
import com.whj.reader.data.BookChineseModeStore
import com.whj.reader.data.BookEncodingStore
import com.whj.reader.data.BookFileType
import com.whj.reader.data.BookmarkStore
import com.whj.reader.data.BookNotesFileStore
import com.whj.reader.data.BookshelfStore
import com.whj.reader.data.ChineseConvert
import com.whj.reader.data.CustomChapterScanner
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
import com.whj.reader.ui.ParagraphAdapter
import com.whj.reader.ui.TocAdapter
import com.whj.reader.ui.TocVpScrollHelper
import com.whj.reader.ui.TocItem
import com.whj.reader.ui.HighlightNotePopup
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.whj.reader.txt.highlight.TextHighlightController
import com.whj.reader.txt.chrome.TextChromeController
import com.whj.reader.txt.chrome.TextStatusBarHelper
import com.whj.reader.txt.nav.TextNavBookmarkController
import com.whj.reader.txt.tts.TextTtsController
import com.whj.reader.txt.settings.TextSettingsController
import com.whj.reader.txt.load.TextLoadController
import com.whj.reader.txt.manga.TextMangaController
import com.whj.reader.util.ReaderLog
import kotlin.math.abs

class ReadingActivity : AppCompatActivity() {
    internal lateinit var highlightController: TextHighlightController
    internal lateinit var chromeController: TextChromeController
    internal lateinit var navController: TextNavBookmarkController
    internal lateinit var ttsController: TextTtsController
    internal lateinit var settingsController: TextSettingsController
    internal lateinit var loadController: TextLoadController
    internal lateinit var mangaController: TextMangaController

    internal var mangaMode: Boolean
        get() = mangaController.mangaMode
        set(v) { mangaController.mangaMode = v }
    internal var mangaPaths: List<String>
        get() = mangaController.mangaPaths
        set(v) { mangaController.mangaPaths = v }
    internal var mangaIndex: Int
        get() = mangaController.mangaIndex
        set(v) { mangaController.mangaIndex = v }
    internal var mangaContinuousPref: Boolean
        get() = mangaController.mangaContinuousPref
        set(v) { mangaController.mangaContinuousPref = v }

    internal fun isBindingReady(): Boolean = ::binding.isInitialized
    internal fun isReaderReady(): Boolean = ::reader.isInitialized
    internal fun isTtsReady(): Boolean = ::tts.isInitialized
    internal fun isReadMenuReady(): Boolean = ::readMenu.isInitialized
    internal fun isSettingsPanelReady(): Boolean = ::settingsPanel.isInitialized
    internal fun isExportPanelReady(): Boolean = ::exportPanel.isInitialized
    internal fun isKeepScreenReady(): Boolean = ::keepScreen.isInitialized

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_ASSET = "asset"
        const val EXTRA_TITLE = "title"
        /** 指定文本编码；空/不传 = 自动判断 */
        const val EXTRA_ENCODING = "encoding"
        /** adb logcat -s MangaZoom（开关见 ReaderLog.ENABLED_MODULES） */
        /** adb: am broadcast -a com.whj.reader.DEBUG_MANGA_PINCH -p com.whj.reader */
        const val ACTION_DEBUG_MANGA_PINCH = "com.whj.reader.DEBUG_MANGA_PINCH"
    }

    internal lateinit var binding: ActivityReadingBinding
    internal lateinit var settingsPanel: PanelReadSettingsBinding
    internal lateinit var readMenu: PanelReadMenuBinding
    internal lateinit var exportPanel: PanelTtsExportBinding
    internal lateinit var reader: VirtualReaderView
    internal lateinit var tts: TtsManager
    internal var book: LoadedBook? = null
    internal var bookStreamer: com.whj.reader.data.BookStreamer? = null
    /** 按需续载任务（不一次扫完全书） */
    internal var streamerJob: Job? = null
    @Volatile
    internal var streamerLoading = false
    /** 流式加载时待恢复的段落（内容够长后再滚） */
    internal var pendingRestorePara: Int = -1
    internal var style: ReadStyle = ReadStyle()
    internal var chromeVisible = false
    internal var ttsBarOpen = false
    internal var exportPanelOpen = false
    internal var immersive = false
    internal var chromeShownAtMs = 0L
    /** 主题/排版重布局触发的滚动回调期间，勿收起底部菜单（如点「夜间」） */
    internal var ignoreScrollChromeHideUntilMs = 0L
    internal var fileKey: String = ""
    internal var displayTitle: String = ""
    /** 加载并恢复进度完成前，禁止把「第 0 段」写回进度（否则会冲掉上次位置） */
    internal var allowProgressSave = false
    internal var searchHighlightActive = false
    internal var pendingOrientRelayout: Runnable? = null
    internal var orientRelayoutKeepMenu = false
    private var batteryReceiverRegistered = false
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000L)
        }
    }
    internal lateinit var keepScreen: KeepScreenController

    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val para = result.data?.getIntExtra(BookSearchActivity.RESULT_PARA_INDEX, -1) ?: -1
        if (para < 0 || !::reader.isInitialized) return@registerForActivityResult
        val offset = result.data?.getIntExtra(BookSearchActivity.RESULT_CHAR_OFFSET, 0) ?: 0
        val matchLen = result.data?.getIntExtra(BookSearchActivity.RESULT_MATCH_LENGTH, 1) ?: 1
        chromeController.hideChrome()
        val end = (offset + matchLen).coerceAtLeast(offset + 1)
        reader.scrollToHighlightIfNeeded(para, offset, end)
        searchHighlightActive = true
        reader.setHighlightRange(para, offset, end)
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
        chromeController.hideChrome()
        reader.scrollToParagraph(para)
        if (allowProgressSave) saveProgress(para)
        updateProgressLabel()
    }

    /** 安装自定义字体（TTF/OTF） */
    internal val installFontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        installCustomFont(uri)
    }

    /** 导入阅读背景图 */
    internal val importBgImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        importBackgroundImage(uri)
    }

    /** 打开失败：重新选文件 */
    internal val reselectDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        applyReselectedUri(uri)
    }

    /** 打开失败：授予全盘权限后重试 */
    /** 朗读前申请通知权限（Android 13+ 前台服务通知，锁屏续播依赖） */
    internal val ttsNotifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // 无论是否授权都继续；无权限时系统仍可能限制 FGS 通知
        pendingTtsAfterNotif?.invoke()
        pendingTtsAfterNotif = null
    }
    internal var pendingTtsAfterNotif: (() -> Unit)? = null

    internal val openFailPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (StorageAccess.hasAllFilesAccess() ||
            (intent.getStringExtra(EXTRA_URI)?.let { StorageAccess.canRead(this, Uri.parse(it)) } == true)
        ) {
            Toasts.show(this, R.string.open_failed_permission_granted_retry)
            loadController.loadContent()
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

        highlightController = TextHighlightController(this)
        chromeController = TextChromeController(this)
        navController = TextNavBookmarkController(this)
        ttsController = TextTtsController(this)
        settingsController = TextSettingsController(this)
        loadController = TextLoadController(this)
        mangaController = TextMangaController(this)

        binding = ActivityReadingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsPanel = binding.settingsPanel
        // 菜单 inflate 到 host，并预测量避免首次空白
        readMenu = PanelReadMenuBinding.inflate(layoutInflater, binding.readMenuHost, true)
        exportPanel = PanelTtsExportBinding.inflate(layoutInflater, binding.ttsExportHost, true)
        reader = binding.readerView
        chromeController.premeasureReadMenu()
        mangaController.setupMangaHost()

        style = AppSettings.loadStyle(this)

        reader.onParagraphPicked = { para ->
            onExportParagraphPicked(para)
        }
        reader.onImageLongPress = { paraIndex ->
            openImageGallery(paraIndex)
        }
        reader.onZoneTap = { zone ->
            clearSearchHighlight()
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
            clearSearchHighlight()
            if (binding.settingsPanelContainer.isVisible) {
                binding.settingsPanelContainer.isVisible = false
            } else if (chromeVisible) {
                // 菜单打开时只关菜单，不翻页
                chromeController.hideChrome()
            } else {
                pageTurn(forward = forward)
            }
        }
        reader.onUserInteract = { clearSearchHighlight() }
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
                chromeController.hideChrome()
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
            clearSearchHighlight()
            // 关闭菜单，打开 TTS 条并从选区起点读到文末
            chromeVisible = false
            ttsBarOpen = true
            applyChromeVisibility()
            if (!tts.isReady()) {
                tts.reinit()
            }
            tts.playFromParagraphOffset(paraIndex, charOffset)
        }
        reader.onHighlightMenuClick = { addHighlightFromSelection() }
        reader.onNoteBubbleClick = { showHighlightView(it) }
        reader.onEdgeAdjust = { isLeft, direction ->
            handleEdgeAdjust(isLeft, direction)
        }
        applyEdgeSwipeFlags()
        // onCreate 显式允许传感器，避免 AUTO 被误锁竖屏 → 横放 letterbox
        // force：大屏若上次锁成竖屏 letterbox（半宽），进入时解除并铺满
        applyOrientationMode(
            AppSettings.orientationMode(this),
            force = OrientationHelper.isLargeScreen(this),
        )

        tts = TtsManager(this)
        ttsController.bindTtsCallbacks()
        tts.setSpeechRate(AppSettings.ttsRate(this))
        tts.setPitch(AppSettings.ttsPitch(this))
        // TXT/MOBI/EPUB：不同段落（回车）间隔 0.3 秒
        tts.setParagraphGapMs(300L)
        // 引擎/发音人在 TtsManager 构造与 onInit 中从 prefs 恢复，勿在 init 前 apply
        tts.init()

        setupTopBar()
        setupReadMenu()
        ttsController.setupTtsBar()
        ttsController.setupExportPanel()
        settingsController.setupSettingsPanel()
        chromeController.setupBottomChromeInsets()
        setupBackPress()
        settingsController.applyStyleToUi()
        chromeController.hideChrome()
        chromeController.applyLandscapeFullscreenUi()
        updateClock()
        updateProgressLabel()

        keepScreen = KeepScreenController(this) {
            ::tts.isInitialized && tts.currentState().state == TtsManager.State.SPEAKING
        }
        keepScreen.apply()

        loadController.loadContent()
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
                            chromeController.hideChrome()
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

    override fun onResume() {
        super.onResume()
        updateClock()
        updateProgressLabel()
        // 偏好页可能改过边缘手势，回来时刷新
        applyEdgeSwipeFlags()
        startClockAndBattery()
        registerDebugMangaPinch()
        maybeRunMangaPinchDebugFromFile()
        if (::keepScreen.isInitialized) keepScreen.onResume()
        // 仅在方向偏好与当前不一致时纠正（同方向不重设，避免闪）
        applyOrientationMode(AppSettings.orientationMode(this), force = false)
    }

    override fun onPause() {
        super.onPause()
        unregisterDebugMangaPinch()
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

    override fun onDestroy() {
        if (mangaMode) flushMangaViewStateBeforeLeave()
        streamerJob?.cancel()
        mangaController.mangaLoadJob?.cancel()
        bookStreamer?.cancel()
        bookStreamer = null
        stopClockAndBattery()
        if (::keepScreen.isInitialized) keepScreen.onDestroy()
        ttsController.sleepTimer.cancel()
        ttsController.dismissExportProgressDlg()
        ttsController.ttsExport?.shutdown()
        ttsController.ttsExport = null
        if (::tts.isInitialized) {
            tts.onStateChanged = null
            tts.onSentenceHighlight = null
            tts.onError = null
            tts.onNeedMoreContent = null
            tts.shutdown()
        }
        if (::binding.isInitialized) {
            binding.root.removeCallbacks(mangaController.saveMangaViewRunnable)
            binding.mangaImageView.setImageBitmap(null)
            binding.mangaRecycler.adapter = null
        }
        mangaController.mangaContinuousAdapter = null
        mangaController.mangaBitmapCache.evictAll()
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
        binding.tvBattery.text = TextStatusBarHelper.formatBattery(intent) ?: "--%"
    }

    private fun updateClock() {
        binding.tvClock.text = TextStatusBarHelper.formatClock()
    }

    internal fun updateProgressLabel() {
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
        // TXT 全文 %、EPUB/MOBI 章节进度：按屏幕底部 y 计算
        if (b != null && isChapterProgressBook(b)) {
            val chapters = b.chapters
            if (chapters.isNotEmpty()) {
                val para = reader.bottomScreenParagraph()
                    .coerceIn(0, b.paragraphs.lastIndex.coerceAtLeast(0))
                val (n, m, pct) = chapterProgressOf(para, chapters, b.paragraphs.size)
                binding.tvProgress.text = getString(R.string.chapter_progress, n, m, pct)
                return
            }
        }
        binding.tvProgress.text = String.format(
            Locale.US,
            "%.2f%%",
            reader.progressPercentAtBottom(),
        )
    }

    /** EPUB/MOBI：进度用「第 n/m 章 xx%」；TXT 明确排除，始终全文 % */
    private fun setupTopBar() {
        binding.btnBack.setOnClickListener {
            // 返回前立刻落盘漫画缩放/平移（勿等 onPause 里可能被冲掉）
            flushMangaViewStateBeforeLeave()
            finish()
        }
        binding.btnSearch.setOnClickListener {
            if (fileKey.isBlank()) return@setOnClickListener
            val para = if (::reader.isInitialized) {
                reader.topScreenParagraph(
                    if (chromeVisible) binding.topBar.height.toFloat().coerceAtLeast(0f) else 0f,
                )
            } else {
                0
            }
            val starts = book?.chapters?.map { it.paragraphIndex }?.toIntArray() ?: intArrayOf()
            searchLauncher.launch(
                BookSearchActivity.intentTxt(
                    this,
                    fileKey,
                    displayTitle,
                    currentParagraph = para,
                    chapterStarts = starts,
                ),
            )
        }
        binding.btnBookmark.setOnClickListener { toggleBookmarkAtCurrent() }
        binding.btnEncoding.setOnClickListener { showEncodingPicker() }
    }

    /**
     * 书签锚点：用户看到的「屏幕最上方第一段」。
     * 菜单打开时顶栏盖住 Reader 顶部，需扣除顶栏高度。
     */
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
        clearSearchHighlight()
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

    private fun setupReadMenu() {
        chromeController.setupMenuPagerSnap()
        updateOrientMenuIcon()
        readMenu.btnPrevChapter.setOnClickListener { jumpChapter(-1) }
        readMenu.btnNextChapter.setOnClickListener { jumpChapter(1) }
        readMenu.menuStyle.setOnClickListener {
            chromeController.hideChrome()
            rebuildCustomFontChips()
            updateMobiModeButtons()
            openStyleSettingsPanel()
        }
        readMenu.menuPref.setOnClickListener {
            chromeController.hideChrome()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        readMenu.menuJump.setOnClickListener {
            chromeController.hideChrome()
            showProgressJumpSheet()
        }
        readMenu.menuToc.setOnClickListener {
            chromeController.hideChrome()
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

    private fun clearSearchHighlight() {
        if (!searchHighlightActive) return
        searchHighlightActive = false
        if (!::reader.isInitialized) return
        val ttsOn = ::tts.isInitialized &&
            tts.currentState().state != TtsManager.State.IDLE
        if (!ttsOn) reader.clearHighlight()
    }

    private fun applyOrientationMode(
        mode: OrientationMode,
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
            // 换向后：连续图保持 zoom/pan 比例；单图重置变换
            if (mangaMode) {
                val contSnap = if (isMangaContinuousLayout()) {
                    binding.mangaContinuousHost.snapshotContinuousTransform()
                } else {
                    null
                }
                binding.mangaContinuousHost.scheduleContinuousTransformRestore(contSnap)
                if (!isMangaContinuousLayout()) {
                    mangaController.pendingMangaTransform = Triple(1f, 0f, 0f)
                    mangaController.pendingMangaScrollOffset = 0
                    mangaController.pendingMangaScrollY = 0
                }
                mangaController.pendingMangaScrollIndex = mangaIndex
                updateMangaLayoutForOrientation()
                binding.mangaHost.post {
                    if (!mangaMode || isFinishing || isDestroyed) return@post
                    if (isMangaContinuousLayout()) {
                        mangaController.mangaContinuousAdapter?.notifyDataSetChanged()
                        binding.mangaRecycler.post {
                            if (!mangaMode || !isMangaContinuousLayout()) return@post
                            scrollMangaContinuousTo(mangaIndex, smooth = false)
                            contSnap?.let { binding.mangaContinuousHost.restoreContinuousTransform(it) }
                            mangaController.mangaContinuousAdapter?.notifyDataSetChanged()
                            binding.mangaRecycler.post {
                                if (mangaMode && isMangaContinuousLayout()) {
                                    scrollMangaContinuousTo(mangaIndex, smooth = false)
                                    contSnap?.let {
                                        binding.mangaContinuousHost.restoreContinuousTransform(it)
                                    }
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
    internal fun updateChapterTitleBar(firstVisiblePara: Int) {
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
    internal fun saveProgress(paragraphIndex: Int) {
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 只重铺一次，禁止再次 setRequestedOrientation；不保留菜单
        orientRelayoutKeepMenu = false
        scheduleOrientRelayout(debounceMs = 0L)
    }

    /** TTS 条 + 底栏可见高度 → VirtualReaderView.bottomObscuredPx */

    private fun hasDisplayCutout() = chromeController.hasDisplayCutout()
    private fun startTtsFromViewport() = ttsController.startTtsFromViewport()

    // --- Controller delegations ---
    private fun reloadBookHighlights() = highlightController.reloadBookHighlights()
    private fun addHighlightFromSelection() = highlightController.addHighlightFromSelection()
    private fun showHighlightView(highlightId: String) = highlightController.showHighlightView(highlightId)
    private fun showHighlightEdit(highlightId: String) = highlightController.showHighlightEdit(highlightId)
    private fun applyHighlightList(highlights: List<Highlight>) = highlightController.applyHighlightList(highlights)
    private fun saveBookHighlights() = highlightController.saveBookHighlights()
    private fun highlightTocItems(totalParagraphs: Int) = highlightController.highlightTocItems(totalParagraphs)

    private fun toggleChrome() = chromeController.toggleChrome()
    private fun showChrome() = chromeController.showChrome()
    private fun hideChrome() = chromeController.hideChrome()
    private fun applyChromeVisibility() = chromeController.applyChromeVisibility()
    private fun syncReaderBottomObscured() = chromeController.syncReaderBottomObscured()
    private fun collapseBottomChromeHard() = chromeController.collapseBottomChromeHard()
    private fun applyPortraitColumnLayout() = chromeController.applyPortraitColumnLayout()
    private fun applyLandscapeFullscreenUi() = chromeController.applyLandscapeFullscreenUi()
    private fun forceMenuLayout(preservePage: Boolean = false) = chromeController.forceMenuLayout(preservePage)
    private fun applyImmersive() = chromeController.applyImmersive()
    private fun isLandscape() = chromeController.isLandscape()
    private fun isMangaContinuousLayout() = mangaController.isMangaContinuousLayout()
    private fun updateOrientMenuIcon() = chromeController.updateOrientMenuIcon()

    private fun toggleBookmarkAtCurrent() = navController.toggleBookmarkAtCurrent()
    private fun updateBookmarkButton() = navController.updateBookmarkButton()
    private fun bookmarkAnchorParagraph() = navController.bookmarkAnchorParagraph()
    private fun showEncodingPicker() = navController.showEncodingPicker()
    private fun showTocSheet() = navController.showTocSheet()
    private fun jumpChapter(delta: Int) = navController.jumpChapter(delta)
    private fun showProgressJumpSheet() = navController.showProgressJumpSheet()
    private fun handleLinkClick(href: String) = navController.handleLinkClick(href)
    private fun showCustomChapterPatternDialog(onApplied: (LoadedBook) -> Unit = {}) =
        navController.showCustomChapterPatternDialog(onApplied)
    private fun reloadTextOptions(preferredEncoding: String?, chineseMode: ChineseConvert.Mode) =
        navController.reloadTextOptions(preferredEncoding, chineseMode)

    private fun openExportPanel() = ttsController.openExportPanel()
    private fun closeExportPanel() = ttsController.closeExportPanel()
    private fun onExportParagraphPicked(para: Int) = ttsController.onExportParagraphPicked(para)
    private fun updateTtsUi(snapshot: TtsManager.Snapshot) = ttsController.updateTtsUi(snapshot)
    private fun withTtsNotificationPermission(then: () -> Unit) = ttsController.withTtsNotificationPermission(then)
    private fun dismissExportProgressDlg() = ttsController.dismissExportProgressDlg()
    private fun showVoicePicker() = ttsController.showVoicePicker()
    private fun updateTtsRateLabel(rate: Float) = ttsController.updateTtsRateLabel(rate)

    private fun persistAndApplyStyle(keepAnchor: Boolean = true) = settingsController.persistAndApplyStyle(keepAnchor)
    private fun applyStyleToUi(keepAnchor: Boolean = true) = settingsController.applyStyleToUi(keepAnchor)
    private fun updateMobiModeButtons() = settingsController.updateMobiModeButtons()
    private fun rebuildCustomFontChips() = settingsController.rebuildCustomFontChips()
    private fun toggleNightStyle() = settingsController.toggleNightStyle()
    private fun openStyleSettingsPanel() = settingsController.openStyleSettingsPanel()
    private fun installCustomFont(uri: Uri) = settingsController.installCustomFont(uri)
    private fun importBackgroundImage(uri: Uri) = settingsController.importBackgroundImage(uri)

    private fun loadContent() = loadController.loadContent()
    private fun attachBookStreamer(streamer: com.whj.reader.data.BookStreamer) = loadController.attachBookStreamer(streamer)
    private fun requestStreamBatch() = loadController.requestStreamBatch()
    private fun maybeRequestMoreContent(firstVisiblePara: Int = -1) = loadController.maybeRequestMoreContent(firstVisiblePara)
    private fun showLoadOverlay(message: String) = loadController.showLoadOverlay(message)
    private fun updateLoadOverlay(message: String, current: Int, total: Int) = loadController.updateLoadOverlay(message, current, total)
    private fun hideLoadOverlay() = loadController.hideLoadOverlay()
    private fun applyLoadedBook(loaded: LoadedBook, isInitial: Boolean) = loadController.applyLoadedBook(loaded, isInitial)
    private fun maybeRevealReaderAfterRestore() = loadController.maybeRevealReaderAfterRestore()
    private fun updateStreamTitle(loaded: LoadedBook, progressMsg: String? = null) = loadController.updateStreamTitle(loaded, progressMsg)
    private fun showOpenFailGuide(reason: OpenFailGuide.Reason, detail: String?, exitOnClose: Boolean = true) =
        loadController.showOpenFailGuide(reason, detail, exitOnClose)
    private fun applyReselectedUri(uri: Uri) = loadController.applyReselectedUri(uri)
    private fun progressFileExt() = loadController.progressFileExt()
    private fun isChapterProgressBook(b: LoadedBook) = loadController.isChapterProgressBook(b)
    private fun chapterProgressOf(para: Int, chapters: List<com.whj.reader.model.Chapter>, totalParas: Int) =
        loadController.chapterProgressOf(para, chapters, totalParas)

    private fun flushMangaViewStateBeforeLeave() = mangaController.flushMangaViewStateBeforeLeave()
    private fun setupMangaHost() = mangaController.setupMangaHost()
    private fun enterMangaMode(restoreIndex: Boolean) = mangaController.enterMangaMode(restoreIndex)
    private fun mangaGo(delta: Int) = mangaController.mangaGo(delta)
    private fun showMangaIndex(i: Int) = mangaController.showMangaIndex(i)
    private fun updateMangaLayoutForOrientation(preservePending: Boolean = false) =
        mangaController.updateMangaLayoutForOrientation(preservePending)
    private fun scrollMangaContinuousTo(index: Int, smooth: Boolean) =
        mangaController.scrollMangaContinuousTo(index, smooth)
    private fun registerDebugMangaPinch() = mangaController.registerDebugMangaPinch()
    private fun unregisterDebugMangaPinch() = mangaController.unregisterDebugMangaPinch()
    private fun maybeRunMangaPinchDebugFromFile() = mangaController.maybeRunMangaPinchDebugFromFile()
    private fun saveMangaViewStateNow() = mangaController.saveMangaViewStateNow()
    private fun formatFontSizeLabel(sp: Float) = settingsController.formatFontSizeLabel(sp)

}
