package com.whj.reader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
import com.whj.reader.ui.VirtualReaderView
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_ASSET = "asset"
        const val EXTRA_TITLE = "title"
        /** 指定文本编码；空/不传 = 自动判断 */
        const val EXTRA_ENCODING = "encoding"
    }

    private lateinit var binding: ActivityReadingBinding
    private lateinit var settingsPanel: PanelReadSettingsBinding
    private lateinit var readMenu: PanelReadMenuBinding
    private lateinit var exportPanel: PanelTtsExportBinding
    private lateinit var reader: VirtualReaderView
    private lateinit var tts: TtsManager
    private var ttsExport: TtsExportHelper? = null

    private var book: LoadedBook? = null
    private var bookStreamer: com.whj.reader.data.BookStreamer? = null
    /** 流式加载时待恢复的段落（内容够长后再滚） */
    private var pendingRestorePara: Int = -1
    private var style: ReadStyle = ReadStyle()
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
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleExitRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            // TTS 定时优先：定时未结束前不因空闲退出
            if (sleepTimer.isActive()) {
                val wait = (sleepTimer.remainingMs() + 2_000L).coerceAtLeast(1_000L)
                idleHandler.postDelayed(this, wait)
                return
            }
            Toasts.show(this@ReadingActivity, R.string.idle_exit_toast)
            if (::tts.isInitialized) {
                tts.stop()
            }
            finish()
        }
    }
    private lateinit var keepScreen: KeepScreenController

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
        super.onCreate(savedInstanceState)
        binding = ActivityReadingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsPanel = binding.settingsPanel
        // 菜单 inflate 到 host，并预测量避免首次空白
        readMenu = PanelReadMenuBinding.inflate(layoutInflater, binding.readMenuHost, true)
        exportPanel = PanelTtsExportBinding.inflate(layoutInflater, binding.ttsExportHost, true)
        reader = binding.readerView
        premeasureReadMenu()

        style = AppSettings.loadStyle(this)

        reader.onParagraphPicked = { para ->
            bumpIdleTimer()
            onExportParagraphPicked(para)
        }
        reader.onImageLongPress = { paraIndex ->
            bumpIdleTimer()
            openImageGallery(paraIndex)
        }
        reader.onZoneTap = { zone ->
            bumpIdleTimer()
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
                            if (chromeVisible) hideChrome()
                            pageTurn(forward = false)
                        }
                        2 -> {
                            if (chromeVisible) hideChrome()
                            pageTurn(forward = true)
                        }
                        else -> toggleChrome()
                    }
                }
            }
        }
        // 左右滑翻页：左滑下一页，右滑上一页
        reader.onHorizontalSwipe = { forward ->
            bumpIdleTimer()
            if (binding.settingsPanelContainer.isVisible) {
                binding.settingsPanelContainer.isVisible = false
            } else {
                if (chromeVisible) hideChrome()
                pageTurn(forward = forward)
            }
        }
        // 进度保存已在 View 内节流；这里只写入，并刷新底部进度
        reader.onScrollChangedListener = { first ->
            bumpIdleTimer()
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
        }
        reader.onLinkClick = { href ->
            bumpIdleTimer()
            handleLinkClick(href)
        }
        reader.onReadFromParagraph = { paraIndex, charOffset ->
            bumpIdleTimer()
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
            bumpIdleTimer()
            handleEdgeAdjust(isLeft, direction)
        }
        applyEdgeSwipeFlags()
        applyOrientationMode(AppSettings.orientationMode(this), toast = false)

        tts = TtsManager(this)
        tts.listener = ttsListener
        tts.setSpeechRate(AppSettings.ttsRate(this))
        tts.setPitch(AppSettings.ttsPitch(this))
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
        bumpIdleTimer()
        if (::keepScreen.isInitialized) keepScreen.onResume()
        // 前台才允许「自动」使用方向传感器
        applyOrientationMode(AppSettings.orientationMode(this), toast = false, allowSensor = true)
    }

    override fun onPause() {
        super.onPause()
        stopClockAndBattery()
        idleHandler.removeCallbacks(idleExitRunnable)
        if (::keepScreen.isInitialized) keepScreen.onPause()
        // 后台关闭方向传感器（自动模式锁到当前方向）
        applyOrientationMode(AppSettings.orientationMode(this), toast = false, allowSensor = false)
        // 锁屏/切后台不暂停 TTS，由前台服务继续播放
        if (::reader.isInitialized) {
            saveProgress(reader.firstVisibleParagraph())
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
            bumpIdleTimer()
            if (::keepScreen.isInitialized) keepScreen.onUserActivity()
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 重置空闲退出计时（默认 30 分钟无操作则退出阅读）。
     * 若 TTS 定时关闭仍在计时，则空闲退出不得早于定时结束（定时优先）。
     */
    private fun bumpIdleTimer() {
        idleHandler.removeCallbacks(idleExitRunnable)
        val mins = AppSettings.idleExitMinutes(this)
        if (mins <= 0 && !sleepTimer.isActive()) return
        val idleMs = if (mins > 0) mins * 60_000L else 0L
        val delay = if (sleepTimer.isActive()) {
            val sleepMs = sleepTimer.remainingMs() + 2_000L
            if (idleMs > 0L) maxOf(idleMs, sleepMs) else sleepMs
        } else {
            idleMs
        }
        if (delay > 0L) {
            idleHandler.postDelayed(idleExitRunnable, delay)
        }
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
                Toasts.show(this@ReadingActivity, message)
            }
        }
    }

    override fun onDestroy() {
        bookStreamer?.cancel()
        bookStreamer = null
        stopClockAndBattery()
        idleHandler.removeCallbacks(idleExitRunnable)
        if (::keepScreen.isInitialized) keepScreen.onDestroy()
        sleepTimer.cancel()
        ttsExport?.shutdown()
        ttsExport = null
        if (::tts.isInitialized) {
            tts.listener = null
            tts.shutdown()
        }
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
        if (!::reader.isInitialized) {
            binding.tvProgress.text = "0.00%"
            return
        }
        binding.tvProgress.text = String.format(Locale.US, "%.2f%%", reader.progressPercent())
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener { finish() }
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
        bookStreamer?.cancel()
        bookStreamer = null
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
            hideLoadOverlay()
            result.onSuccess { open ->
                pendingRestorePara = keepPara
                applyLoadedBook(open.book, isInitial = true)
                val streamer = open.streamer
                if (streamer != null) {
                    bookStreamer = streamer
                    streamer.start(
                        onUpdate = { b ->
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                applyLoadedBook(b, isInitial = false)
                            }
                        },
                        onComplete = { b ->
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                bookStreamer = null
                                applyLoadedBook(b, isInitial = false)
                                updateStreamTitle(b)
                                updateBookmarkButton()
                            }
                        },
                    )
                } else {
                    updateBookmarkButton()
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

    /** 瞬时翻页：下翻末行顶置，上翻首行底置；第 1 行在标题栏下完整显示 */
    private fun pageTurn(forward: Boolean) {
        if (chromeVisible) hideChrome()
        if (binding.settingsPanelContainer.isVisible) {
            binding.settingsPanelContainer.isVisible = false
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
            OrientationMode.PORTRAIT -> getString(R.string.orient_portrait)
            OrientationMode.LANDSCAPE -> getString(R.string.orient_landscape)
            OrientationMode.AUTO -> getString(R.string.orient_auto)
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
            binding.settingsPanelContainer.isVisible = true
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
        // 视角：竖屏 → 横屏 → 自动旋转
        readMenu.menuOrient.setOnClickListener {
            val next = when (AppSettings.orientationMode(this)) {
                OrientationMode.PORTRAIT -> OrientationMode.LANDSCAPE
                OrientationMode.LANDSCAPE -> OrientationMode.AUTO
                OrientationMode.AUTO -> OrientationMode.PORTRAIT
            }
            AppSettings.setOrientationMode(this, next)
            applyOrientationMode(next, toast = true)
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
            val next = if (style.theme == ReadTheme.NIGHT) ReadTheme.DEFAULT else ReadTheme.NIGHT
            setTheme(next)
        }
        readMenu.menuRead.setOnClickListener {
            // 关闭菜单，打开 TTS 条并朗读
            chromeVisible = false
            ttsBarOpen = true
            applyChromeVisibility()
            withTtsNotificationPermission {
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        helper.export(
            text = text,
            format = format,
            filePrefix = "book",
            bitRateKbps = bitRateKbps,
            listener = object : TtsExportHelper.Listener {
                override fun onProgress(done: Int, total: Int, phase: String) {
                    if (isFinishing || isDestroyed) return
                    val label = when (phase) {
                        "encode" -> getString(R.string.tts_export_encoding)
                        "merge" -> getString(R.string.tts_export_progress, total, total)
                        else -> getString(R.string.tts_export_progress, done.coerceAtMost(total), total)
                    }
                    setExportProgressUi(active = true, done = done, total = total.coerceAtLeast(1), label = label)
                }

                override fun onSuccess(file: File) {
                    if (isFinishing || isDestroyed) return
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setExportProgressUi(active = false)
                    // 若请求 MP3 却落到 m4a/wav，文件名可看出；成功 toast 显示实际文件名
                    Toasts.show(this@ReadingActivity, getString(R.string.tts_export_ok, file.name), android.widget.Toast.LENGTH_LONG)
                    shareExportedAudio(file)
                }

                override fun onError(message: String) {
                    if (isFinishing || isDestroyed) return
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setExportProgressUi(active = false)
                    Toasts.show(this@ReadingActivity, getString(R.string.tts_export_fail, message), android.widget.Toast.LENGTH_LONG)
                }

                override fun onCancelled() {
                    if (isFinishing || isDestroyed) return
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setExportProgressUi(active = false)
                    Toasts.show(this@ReadingActivity, R.string.tts_export_cancelled)
                }
            },
        )
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
                    Toasts.show(this, R.string.tts_reinit)
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
            settingsPanel.seekTtsRate.progress =
                ((rate - 0.5f) / 0.1f).toInt().coerceIn(0, 20)
            settingsPanel.tvTtsRate.text = String.format("%.1fx", rate)
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
                bumpIdleTimer()
                Toasts.show(this, R.string.tts_sleep_cancelled)
            } else {
                sleepTimer.start(mins * 60_000L)
                updateSleepUi()
                // 定时优先于空闲退出：重算空闲计时
                bumpIdleTimer()
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
        // 定时结束后按正常空闲规则重新计时
        bumpIdleTimer()
        Toasts.show(this, R.string.tts_sleep_finished)
    }

    private fun adjustTtsRate(delta: Float) {
        val next = (AppSettings.ttsRate(this) + delta).coerceIn(0.5f, 2.5f)
        val rounded = (kotlin.math.round(next * 10f) / 10f).coerceIn(0.5f, 2.5f)
        AppSettings.setTtsRate(this, rounded)
        tts.setSpeechRate(rounded, restartCurrent = true)
        updateTtsRateLabel(rounded)
        settingsPanel.seekTtsRate.progress = ((rounded - 0.5f) / 0.1f).toInt().coerceIn(0, 20)
        settingsPanel.tvTtsRate.text = String.format("%.1fx", rounded)
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
            val rate = AppSettings.ttsRate(this)
            settingsPanel.seekTtsRate.progress = ((rate - 0.5f) / 0.1f).toInt().coerceIn(0, 20)
            settingsPanel.tvTtsRate.text = String.format("%.1fx", rate)
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
        settingsPanel.seekTtsRate.setOnSeekBarChangeListener(simpleSeek { p ->
            val rate = 0.5f + p * 0.1f
            settingsPanel.tvTtsRate.text = String.format("%.1fx", rate)
            AppSettings.setTtsRate(this, rate)
            tts.setSpeechRate(rate, restartCurrent = true)
            updateTtsRateLabel(rate)
        })

        settingsPanel.chipThemeDefault.setOnClickListener { setTheme(ReadTheme.DEFAULT) }
        settingsPanel.chipThemeWhite.setOnClickListener { setTheme(ReadTheme.WHITE) }
        settingsPanel.chipThemeCustom.setOnClickListener { showCustomBgPicker() }
        settingsPanel.chipThemeGreen.setOnClickListener { setTheme(ReadTheme.GREEN) }
        settingsPanel.chipThemeBlue.setOnClickListener { setTheme(ReadTheme.BLUE) }
        settingsPanel.chipThemePurple.setOnClickListener { setTheme(ReadTheme.PURPLE) }
        settingsPanel.chipThemeSepia.setOnClickListener { setTheme(ReadTheme.SEPIA) }
        settingsPanel.chipThemeNight.setOnClickListener { setTheme(ReadTheme.NIGHT) }
        refreshThemeChips()

        fun setFont(id: String) {
            style = style.copy(fontFamily = id)
            persistAndApplyStyle(keepAnchor = true)
            refreshFontChips()
        }
        settingsPanel.chipFontDefault.setOnClickListener { setFont(ReaderFonts.ID_DEFAULT) }
        settingsPanel.chipFontSans.setOnClickListener { setFont(ReaderFonts.ID_SANS) }
        settingsPanel.chipFontSerif.setOnClickListener { setFont(ReaderFonts.ID_SERIF) }
        settingsPanel.chipFontMono.setOnClickListener { setFont(ReaderFonts.ID_MONO) }
        refreshFontChips()

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
    }

    private fun simpleSeek(onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    private fun setTheme(theme: ReadTheme) {
        style = style.copy(theme = theme)
        persistAndApplyStyle(keepAnchor = true)
        refreshThemeChips()
    }

    /** 自定义背景色：预设色板 */
    private fun showCustomBgPicker() {
        val colors = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFFFAFAFA.toInt(),
            0xFFF5F5F5.toInt(),
            0xFFF7F4ED.toInt(),
            0xFFFFF8E7.toInt(),
            0xFFF4ECD8.toInt(),
            0xFFE8F5E9.toInt(),
            0xFFE3F2FD.toInt(),
            0xFFF3E5F5.toInt(),
            0xFFFFEBEE.toInt(),
            0xFFECEFF1.toInt(),
            0xFF212121.toInt(),
            0xFF1A1A1A.toInt(),
            0xFF263238.toInt(),
            0xFF1B2A1B.toInt(),
            0xFF1A237E.toInt(),
        )
        val labels = arrayOf(
            "纯白", "浅灰1", "浅灰2", "米黄", "象牙", "羊皮纸",
            "淡绿", "淡蓝", "淡紫", "淡粉", "蓝灰",
            "深灰", "近黑", "蓝黑", "墨绿", "深蓝",
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.theme_pick_bg)
            .setItems(labels) { _, which ->
                val c = colors[which]
                style = style.copy(theme = ReadTheme.CUSTOM, customBgColor = c)
                settingsPanel.chipThemeCustom.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(c)
                persistAndApplyStyle(keepAnchor = true)
                refreshThemeChips()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshThemeChips() {
        if (!::settingsPanel.isInitialized) return
        fun mark(btn: com.google.android.material.button.MaterialButton, selected: Boolean) {
            btn.alpha = if (selected) 1f else 0.55f
            btn.strokeWidth = if (selected) (2 * resources.displayMetrics.density).toInt() else 1
        }
        val t = style.theme
        mark(settingsPanel.chipThemeDefault, t == ReadTheme.DEFAULT)
        mark(settingsPanel.chipThemeWhite, t == ReadTheme.WHITE)
        mark(settingsPanel.chipThemeCustom, t == ReadTheme.CUSTOM)
        mark(settingsPanel.chipThemeGreen, t == ReadTheme.GREEN)
        mark(settingsPanel.chipThemeBlue, t == ReadTheme.BLUE)
        mark(settingsPanel.chipThemePurple, t == ReadTheme.PURPLE)
        mark(settingsPanel.chipThemeSepia, t == ReadTheme.SEPIA)
        mark(settingsPanel.chipThemeNight, t == ReadTheme.NIGHT)
        // 自定义钮显示当前色
        settingsPanel.chipThemeCustom.backgroundTintList =
            android.content.res.ColorStateList.valueOf(style.customBgColor)
        val customText = ParagraphAdapter.textColorForBackground(style.customBgColor)
        settingsPanel.chipThemeCustom.setTextColor(customText)
    }

    private fun refreshFontChips() {
        if (!::settingsPanel.isInitialized) return
        val id = style.fontFamily
        // 用描边按钮的 alpha 简单标出当前项
        fun mark(btn: com.google.android.material.button.MaterialButton, selected: Boolean) {
            btn.alpha = if (selected) 1f else 0.55f
            btn.strokeWidth = if (selected) (2 * resources.displayMetrics.density).toInt() else 1
        }
        mark(settingsPanel.chipFontDefault, id == ReaderFonts.ID_DEFAULT)
        mark(settingsPanel.chipFontSans, id == ReaderFonts.ID_SANS)
        mark(settingsPanel.chipFontSerif, id == ReaderFonts.ID_SERIF)
        mark(settingsPanel.chipFontMono, id == ReaderFonts.ID_MONO)
    }

    private fun persistAndApplyStyle(keepAnchor: Boolean = true) {
        AppSettings.saveStyle(this, style)
        applyStyleToUi(keepAnchor = keepAnchor)
    }

    private fun applyStyleToUi(keepAnchor: Boolean = true) {
        val bg = ParagraphAdapter.backgroundColor(style.theme, style.customBgColor)
        val (textColor, hl) = ParagraphAdapter.themeColors(style.theme, style.customBgColor)
        binding.rootReading.setBackgroundColor(bg)
        reader.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val darkChrome = style.theme == ReadTheme.NIGHT ||
            (style.theme == ReadTheme.CUSTOM && !ParagraphAdapter.isLightColor(bg))
        val metaColor = if (darkChrome) 0xFF9A9A9A.toInt() else 0xFF888888.toInt()
        binding.tvBookName.setTextColor(metaColor)
        binding.tvChapterTitle.setTextColor(metaColor)
        binding.tvReadTitle.setTextColor(metaColor)
        binding.tvBattery.setTextColor(metaColor)
        binding.tvClock.setTextColor(metaColor)
        binding.tvProgress.setTextColor(metaColor)
        binding.readStatusBar.setBackgroundColor(bg)
        binding.readTitleBar.setBackgroundColor(bg)
        binding.tvReadTitle.setBackgroundColor(bg)
        @Suppress("DEPRECATION")
        if (darkChrome || immersive) {
            window.decorView.systemUiVisibility = 0
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        reader.applyStyle(style, textColor, hl, keepAnchor = keepAnchor)
        updateProgressLabel()
        if (::settingsPanel.isInitialized) refreshThemeChips()
    }

    /**
     * 全屏：隐藏导航栏，**保留系统状态栏**（打孔屏仍显示时间/信号/电量）。
     */
    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        if (immersive) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            applyStyleToUi()
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            applyStyleToUi()
        }
    }

    private fun applyOrientationMode(
        mode: OrientationMode,
        toast: Boolean,
        allowSensor: Boolean = !isFinishing && hasWindowFocus(),
    ) {
        // 仅「自动」且前台时监听传感器；竖/横屏锁定不启用传感器
        OrientationHelper.apply(this, mode, allowSensor = allowSensor && mode == OrientationMode.AUTO)
        if (toast) {
            val label = when (mode) {
                OrientationMode.PORTRAIT -> getString(R.string.orient_portrait)
                OrientationMode.LANDSCAPE -> getString(R.string.orient_landscape)
                OrientationMode.AUTO -> getString(R.string.orient_auto)
            }
            Toasts.show(this, getString(R.string.orient_switched, label))
        }
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
                if (::settingsPanel.isInitialized) {
                    settingsPanel.seekTtsRate.progress =
                        ((next - 0.5f) / 0.1f).toInt().coerceIn(0, 20)
                    settingsPanel.tvTtsRate.text = String.format("%.1fx", next)
                }
                Toasts.show(this, getString(R.string.edge_toast_rate, next))
            }
            EdgeSwipeAction.FONT -> {
                // 下滑(direction=-1)加大字号，上滑(+1)减小
                val next = (style.fontSizeSp - direction).coerceIn(12f, 36f)
                if (next == style.fontSizeSp) return
                style = style.copy(fontSizeSp = next)
                persistAndApplyStyle(keepAnchor = true)
                if (::settingsPanel.isInitialized) {
                    settingsPanel.seekFontSize.progress =
                        (style.fontSizeSp - 12f).toInt().coerceIn(0, 24)
                    settingsPanel.tvFontSize.text = style.fontSizeSp.toInt().toString()
                }
                Toasts.show(this, getString(R.string.edge_toast_font, style.fontSizeSp.toInt()))
            }
            EdgeSwipeAction.NONE -> Unit
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

        bookStreamer?.cancel()
        bookStreamer = null
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
                // 先铺内容并恢复进度，再关遮罩，避免闪第一页/封面
                applyLoadedBook(open.book, isInitial = true)
                val streamer = open.streamer
                if (streamer != null) {
                    bookStreamer = streamer
                    streamer.start(
                        onUpdate = { b ->
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                applyLoadedBook(b, isInitial = false)
                            }
                        },
                        onComplete = { b ->
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                bookStreamer = null
                                applyLoadedBook(b, isInitial = false)
                                updateStreamTitle(b)
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

        if (isInitial) {
            val usedEnc = loaded.encoding
            if (!usedEnc.equals("UTF-8", ignoreCase = true)) {
                if (BookEncodingStore.get(this, loaded.uri) == null) {
                    BookEncodingStore.set(this, loaded.uri, usedEnc)
                }
            }
            allowProgressSave = false
            val saved = AppSettings.progressFor(this, loaded.uri)
            val shelfPara = BookshelfStore.findBookByUri(this, loaded.uri)?.lastParagraph ?: 0
            pendingRestorePara = maxOf(saved, shelfPara)

            // 恢复完成前隐藏正文，避免闪封面/第 1 页
            reader.visibility = android.view.View.INVISIBLE
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
                lastParagraph = pendingRestorePara.coerceAtLeast(0),
            )
            ReadingProgressStore.saveTxt(
                this,
                loaded.uri,
                pendingRestorePara.coerceIn(0, loaded.paragraphs.lastIndex.coerceAtLeast(0)),
                loaded.paragraphs.size,
            )
            AppSettings.setLastBook(this, loaded.uri, loaded.title)

            // 布局完成后再测高并滚到进度，然后显示
            reader.post(object : Runnable {
                override fun run() {
                    if (isFinishing || isDestroyed) return
                    tryRestoreProgress(loaded)
                    reader.visibility = android.view.View.VISIBLE
                    hideLoadOverlay()
                    allowProgressSave = true
                    saveProgress(reader.firstVisibleParagraph())
                    // 二次校正：首帧 contentWidth 偶发不准
                    reader.post {
                        if (isFinishing || isDestroyed) return@post
                        if (pendingRestorePara > 0) tryRestoreProgress(loaded)
                    }
                }
            })
        } else {
            reader.updateContent(loaded.paragraphs, keepScroll = true)
            if (::tts.isInitialized) {
                tts.updateDocumentKeepPosition(
                    loaded.paragraphs,
                    TextLoader.SentenceLineBreakMode.NEWLINE,
                )
            }
            tryRestoreProgress(loaded)
            updateProgressLabel()
            // 总段数变化时刷新进度存储的 total
            ReadingProgressStore.saveTxt(
                this,
                loaded.uri,
                reader.firstVisibleParagraph(),
                loaded.paragraphs.size,
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
            if (loaded.isComplete || target < loaded.paragraphs.size - 5) {
                pendingRestorePara = -1
            }
        }
        updateProgressLabel()
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
        // 流式加载未到目标时：记下待恢复位置并尽量滚
        val maxIdx = book?.paragraphs?.lastIndex ?: -1
        if (target > maxIdx) {
            pendingRestorePara = target
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
        AppSettings.saveProgress(this, fileKey, paragraphIndex)
        BookshelfStore.updateProgress(this, fileKey, paragraphIndex)
        val total = book?.paragraphs?.size ?: 0
        ReadingProgressStore.saveTxt(this, fileKey, paragraphIndex, total)
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

    /**
     * 菜单（顶栏+图标）/ 合成面板 / TTS 条互斥：
     * - 有菜单 → 隐藏 TTS 与合成面板
     * - 合成面板 → 隐藏菜单与 TTS
     * - 无菜单且已打开朗读 → 显示 TTS 条
     */
    private fun applyChromeVisibility() {
        binding.topBar.isVisible = chromeVisible && !exportPanelOpen
        binding.ttsBar.isVisible = !chromeVisible && !exportPanelOpen && ttsBarOpen
        val menuHost = binding.readMenuHost
        val exportHost = binding.ttsExportHost
        if (exportPanelOpen) {
            menuHost.visibility = View.GONE
            exportHost.visibility = View.VISIBLE
            exportPanel.root.visibility = View.VISIBLE
            exportHost.bringToFront()
            binding.readStatusBar.bringToFront()
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
            binding.readStatusBar.bringToFront()
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

    private fun forceMenuLayout() {
        val host = binding.readMenuHost
        val parentW = binding.bottomChrome.width.takeIf { it > 0 }
            ?: binding.root.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        if (parentW <= 0) return
        // 两页各占满一屏宽度
        val pageW = parentW
        for (page in listOf(readMenu.menuPage0, readMenu.menuPage1)) {
            val lp = page.layoutParams
            lp.width = pageW
            page.layoutParams = lp
        }
        val wSpec = View.MeasureSpec.makeMeasureSpec(parentW, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        readMenu.root.measure(wSpec, hSpec)
        host.measure(wSpec, hSpec)
        host.requestLayout()
        binding.bottomChrome.requestLayout()
        // 打开菜单时回到第 1 屏
        readMenu.menuPager.settleToPage(0, smooth = false)
        updateMenuPageDots(0)
    }

    private fun jumpChapter(delta: Int) {
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
            target > chapters.lastIndex ->
                Toasts.show(this, R.string.no_next_chapter)
            else -> {
                val p = chapters[target].paragraphIndex
                reader.scrollToParagraph(p)
                saveProgress(p)
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
     */
    private fun showProgressJumpSheet() {
        if (book == null) return
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

        fun jumpTo(index: Int) {
            dialog.dismiss()
            reader.scrollToParagraph(index)
            saveProgress(index)
            if (tts.currentState().state != TtsManager.State.IDLE) {
                tts.jumpToParagraph(index, autoPlay = true)
            } else {
                reader.clearHighlight()
            }
        }

        val chapterAdapter = TocAdapter(
            onClick = { item ->
                val index = (item as? TocItem.ChapterItem)?.chapter?.paragraphIndex ?: return@TocAdapter
                jumpTo(index)
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
