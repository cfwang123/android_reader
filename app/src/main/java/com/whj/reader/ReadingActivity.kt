package com.whj.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.whj.reader.data.AppSettings
import com.whj.reader.data.BookmarkStore
import com.whj.reader.data.BookshelfStore
import com.whj.reader.data.LoadedBook
import com.whj.reader.data.TextLoader
import com.whj.reader.databinding.ActivityReadingBinding
import com.whj.reader.databinding.PanelReadMenuBinding
import com.whj.reader.databinding.PanelReadSettingsBinding
import com.whj.reader.databinding.SheetTocBinding
import com.whj.reader.model.EdgeSwipeAction
import com.whj.reader.model.OrientationMode
import com.whj.reader.model.ReadStyle
import com.whj.reader.model.ReadTheme
import com.whj.reader.tts.TtsManager
import com.whj.reader.ui.ParagraphAdapter
import com.whj.reader.ui.TocAdapter
import com.whj.reader.ui.TocItem
import com.whj.reader.ui.VirtualReaderView
import com.whj.reader.util.Toasts
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
    }

    private lateinit var binding: ActivityReadingBinding
    private lateinit var settingsPanel: PanelReadSettingsBinding
    private lateinit var readMenu: PanelReadMenuBinding
    private lateinit var reader: VirtualReaderView
    private lateinit var tts: TtsManager

    private var book: LoadedBook? = null
    private var style: ReadStyle = ReadStyle()
    private var chromeVisible = false
    /** 用户通过「朗读」打开过 TTS 条；停止后关闭 */
    private var ttsBarOpen = false
    private var immersive = false
    private var fileKey: String = ""
    private var displayTitle: String = ""
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
    private val idleExitRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        Toasts.show(this, R.string.idle_exit_toast)
        if (::tts.isInitialized) {
            tts.stop()
        }
        finish()
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
        readMenu = binding.readMenu
        reader = binding.readerView

        style = AppSettings.loadStyle(this)

        reader.onZoneTap = { zone ->
            bumpIdleTimer()
            if (!binding.settingsPanelContainer.isVisible) {
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
        // 进度保存已在 View 内节流；这里只写入，并刷新底部进度
        reader.onScrollChangedListener = { first ->
            bumpIdleTimer()
            saveProgress(first)
            updateProgressLabel()
        }
        reader.onReadFromParagraph = { paraIndex ->
            bumpIdleTimer()
            // 关闭菜单，打开 TTS 条并从该段朗读
            chromeVisible = false
            ttsBarOpen = true
            applyChromeVisibility()
            if (!tts.isReady()) {
                tts.reinit()
            }
            tts.playFrom(paraIndex, 0)
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
        AppSettings.voiceName(this)?.let { tts.applyVoiceByName(it) }
        tts.init()

        setupTopBar()
        setupReadMenu()
        setupTtsBar()
        setupSettingsPanel()
        applyStyleToUi()
        hideChrome()
        updateClock()
        updateProgressLabel()
        registerBattery()

        if (AppSettings.keepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        loadContent()
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        updateProgressLabel()
        // 偏好页可能改过边缘手势，回来时刷新
        applyEdgeSwipeFlags()
        clockHandler.removeCallbacks(clockTick)
        clockHandler.post(clockTick)
        bumpIdleTimer()
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTick)
        idleHandler.removeCallbacks(idleExitRunnable)
        if (::tts.isInitialized && tts.currentState().state == TtsManager.State.SPEAKING) {
            tts.pause()
        }
        if (::reader.isInitialized) {
            saveProgress(reader.firstVisibleParagraph())
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev != null &&
            (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                ev.actionMasked == android.view.MotionEvent.ACTION_UP)
        ) {
            bumpIdleTimer()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 重置空闲退出计时（默认 30 分钟无操作则退出阅读） */
    private fun bumpIdleTimer() {
        idleHandler.removeCallbacks(idleExitRunnable)
        val mins = AppSettings.idleExitMinutes(this)
        if (mins > 0) {
            idleHandler.postDelayed(idleExitRunnable, mins * 60_000L)
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
            }
        }

        override fun onParagraphHighlight(paragraphIndex: Int) {
            runOnUiThread {
                if (!::reader.isInitialized || !::tts.isInitialized || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                val st = tts.currentState().state
                if (st == TtsManager.State.SPEAKING || st == TtsManager.State.PAUSED) {
                    reader.setHighlightParagraph(paragraphIndex)
                } else {
                    reader.clearHighlight()
                }
                if (AppSettings.autoScroll(this@ReadingActivity) &&
                    st != TtsManager.State.IDLE
                ) {
                    // 目标句已在屏内则不竖直跳动
                    reader.scrollToParagraphIfNeeded(paragraphIndex)
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
        clockHandler.removeCallbacks(clockTick)
        idleHandler.removeCallbacks(idleExitRunnable)
        unregisterBattery()
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
    }

    /** 瞬时翻页（无动画），保留约 1 行；快翻时进度由 View 节流回调更新 */
    private fun pageTurn(forward: Boolean) {
        if (!reader.canPage(forward)) {
            Toasts.show(this, if (forward) R.string.page_bottom else R.string.page_top)
            return
        }
        reader.pageTurn(forward = forward, overlapLines = 1)
        // 进度条节流，避免连点拖慢主线程
        if (reader.shouldUpdateProgressUi()) {
            updateProgressLabel()
        }
    }

    private fun setupReadMenu() {
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
        }
        // 全屏显示（沉浸）
        readMenu.menuFullscreen.setOnClickListener {
            immersive = !immersive
            applyImmersive()
            Toasts.show(
                this,
                if (immersive) R.string.immersive_on else R.string.immersive_off,
            )
        }
        readMenu.menuNight.setOnClickListener {
            val next = if (style.theme == ReadTheme.NIGHT) ReadTheme.DEFAULT else ReadTheme.NIGHT
            setTheme(next)
        }
        readMenu.menuRead.setOnClickListener {
            // 关闭 8 图标菜单，打开 TTS 条并朗读
            chromeVisible = false
            ttsBarOpen = true
            applyChromeVisibility()
            if (!tts.isReady()) {
                tts.reinit()
                Toasts.show(this, R.string.tts_not_ready)
            }
            val snap = tts.currentState()
            if (snap.state == TtsManager.State.IDLE) {
                val start = reader.currentHighlight().takeIf { it >= 0 }
                    ?: reader.firstVisibleParagraph()
                tts.playFrom(start, 0)
            } else {
                tts.playPauseToggle()
            }
        }
    }

    private fun setupTtsBar() {
        binding.btnTtsPlayPause.setOnClickListener {
            if (!tts.isReady()) {
                tts.reinit()
                Toasts.show(this, R.string.tts_reinit)
            }
            val snap = tts.currentState()
            if (snap.state == TtsManager.State.IDLE) {
                val start = reader.currentHighlight().takeIf { it >= 0 }
                    ?: reader.firstVisibleParagraph()
                tts.playFrom(start, 0)
            } else {
                tts.playPauseToggle()
            }
        }
        binding.btnTtsPrev.setOnClickListener { tts.previousSentence() }
        binding.btnTtsNext.setOnClickListener { tts.nextSentence() }
        binding.btnTtsStop.setOnClickListener {
            tts.stop()
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
        binding.btnTtsRateDown.setOnClickListener { adjustTtsRate(-0.1f) }
        binding.btnTtsRateUp.setOnClickListener { adjustTtsRate(0.1f) }
        updateTtsRateLabel(AppSettings.ttsRate(this))
    }

    private fun adjustTtsRate(delta: Float) {
        val next = (AppSettings.ttsRate(this) + delta).coerceIn(0.5f, 2.5f)
        // 对齐到 0.1
        val rounded = (kotlin.math.round(next * 10f) / 10f).coerceIn(0.5f, 2.5f)
        AppSettings.setTtsRate(this, rounded)
        // 立即用新语速重读当前句
        tts.setSpeechRate(rounded, restartCurrent = true)
        updateTtsRateLabel(rounded)
        // 同步设置面板上的 seek（若已打开）
        settingsPanel.seekTtsRate.progress = ((rounded - 0.5f) / 0.1f).toInt().coerceIn(0, 20)
        settingsPanel.tvTtsRate.text = String.format("%.1fx", rounded)
    }

    private fun updateTtsRateLabel(rate: Float) {
        binding.tvTtsRate.text = String.format("%.1f×", rate)
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
        settingsPanel.chipThemeGreen.setOnClickListener { setTheme(ReadTheme.GREEN) }
        settingsPanel.chipThemeBlue.setOnClickListener { setTheme(ReadTheme.BLUE) }
        settingsPanel.chipThemePurple.setOnClickListener { setTheme(ReadTheme.PURPLE) }
        settingsPanel.chipThemeSepia.setOnClickListener { setTheme(ReadTheme.SEPIA) }
        settingsPanel.chipThemeNight.setOnClickListener { setTheme(ReadTheme.NIGHT) }

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
    }

    private fun persistAndApplyStyle(keepAnchor: Boolean = true) {
        AppSettings.saveStyle(this, style)
        applyStyleToUi(keepAnchor = keepAnchor)
    }

    private fun applyStyleToUi(keepAnchor: Boolean = true) {
        val bg = ParagraphAdapter.backgroundColor(style.theme)
        val (textColor, hl) = ParagraphAdapter.themeColors(style.theme)
        binding.rootReading.setBackgroundColor(bg)
        reader.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val night = style.theme == ReadTheme.NIGHT
        val metaColor = if (night) 0xFF9A9A9A.toInt() else 0xFF888888.toInt()
        binding.tvReadTitle.setTextColor(metaColor)
        binding.tvBattery.setTextColor(metaColor)
        binding.tvClock.setTextColor(metaColor)
        binding.tvProgress.setTextColor(metaColor)
        binding.readStatusBar.setBackgroundColor(bg)
        binding.tvReadTitle.setBackgroundColor(bg)
        @Suppress("DEPRECATION")
        if (night || immersive) {
            window.decorView.systemUiVisibility = 0
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        reader.applyStyle(style, textColor, hl, keepAnchor = keepAnchor)
        updateProgressLabel()
    }

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        if (immersive) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        } else {
            applyStyleToUi()
        }
    }

    private fun applyOrientationMode(mode: OrientationMode, toast: Boolean) {
        requestedOrientation = when (mode) {
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
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

    /** 边缘滑动：+1 上滑增大，-1 下滑减小 */
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
                val next = (style.fontSizeSp + direction).coerceIn(12f, 36f)
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

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        asset != null -> TextLoader.loadFromAssets(
                            this@ReadingActivity,
                            asset,
                            titleExtra ?: getString(R.string.sample_book),
                        )
                        uriStr != null -> TextLoader.loadFromUri(
                            this@ReadingActivity,
                            Uri.parse(uriStr),
                            titleExtra,
                        )
                        else -> error("未指定文件")
                    }
                }
            }
            result.onSuccess { loaded ->
                book = loaded
                fileKey = loaded.uri
                displayTitle = loaded.title
                binding.tvBookTitle.text = loaded.title
                binding.tvReadTitle.text = loaded.title
                reader.setContent(loaded.paragraphs)
                applyStyleToUi()
                tts.setDocument(loaded.paragraphs)
                applyChromeVisibility()

                BookshelfStore.addOrUpdateBook(
                    this@ReadingActivity,
                    uri = loaded.uri,
                    displayName = loaded.title,
                    pathHint = if (asset != null) getString(R.string.builtin_sample) else uriStr.orEmpty(),
                )
                AppSettings.setLastBook(this@ReadingActivity, loaded.uri, loaded.title)

                val progress = AppSettings.progressFor(this@ReadingActivity, fileKey)
                if (progress in loaded.paragraphs.indices) {
                    reader.post {
                        reader.scrollToParagraph(progress)
                        updateProgressLabel()
                    }
                } else {
                    updateProgressLabel()
                }
            }.onFailure { e ->
                Toasts.show(
                    this@ReadingActivity,
                    getString(R.string.load_failed, e.message ?: ""),
                    android.widget.Toast.LENGTH_LONG,
                )
                finish()
            }
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
        if (fileKey.isEmpty()) return
        AppSettings.saveProgress(this, fileKey, paragraphIndex)
        BookshelfStore.updateProgress(this, fileKey, paragraphIndex)
        if (displayTitle.isNotEmpty()) {
            AppSettings.setLastBook(this, fileKey, displayTitle)
        }
    }

    private fun toggleChrome() {
        if (chromeVisible) hideChrome() else showChrome()
    }

    private fun showChrome() {
        chromeVisible = true
        applyChromeVisibility()
    }

    private fun hideChrome() {
        chromeVisible = false
        applyChromeVisibility()
    }

    /**
     * 菜单（顶栏+8 图标）与 TTS 条互斥：
     * - 有菜单 → 隐藏 TTS 条
     * - 无菜单且已打开朗读 → 显示 TTS 条
     * - 停止朗读后 ttsBarOpen=false → 隐藏 TTS 条
     */
    private fun applyChromeVisibility() {
        binding.topBar.isVisible = chromeVisible
        readMenu.root.isVisible = chromeVisible
        binding.ttsBar.isVisible = !chromeVisible && ttsBarOpen
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
        if (!tts.isReady()) {
            tts.reinit()
            Toasts.show(this, R.string.tts_not_ready)
            return
        }
        val voices = tts.getVoices()
        if (voices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.tts_no_engine)
                .setMessage(R.string.tts_no_voices)
                .setPositiveButton(R.string.tts_open_settings) { _, _ ->
                    runCatching { startActivity(tts.openTtsSettingsIntent()) }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val byLang = linkedMapOf<String, MutableList<Voice>>()
        voices.forEach { v ->
            val key = localeKey(v.locale)
            byLang.getOrPut(key) { mutableListOf() }.add(v)
        }
        val langKeys = byLang.keys.toList()
        val langLabels = langKeys.map { key ->
            val sample = byLang[key]!!.first().locale
            val display = localeDisplayName(sample)
            "$display · ${getString(R.string.tts_voice_count, byLang[key]!!.size)}"
        }.toTypedArray()

        val currentVoice = tts.currentVoiceName()
        val currentLangIndex = langKeys.indexOfFirst { key ->
            byLang[key]!!.any { it.name == currentVoice }
        }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.tts_lang_pick)
            .setSingleChoiceItems(langLabels, currentLangIndex) { dialog, which ->
                dialog.dismiss()
                showVoiceListForLanguage(byLang[langKeys[which]].orEmpty())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showVoiceListForLanguage(voices: List<Voice>) {
        if (voices.isEmpty()) return
        val sorted = voices.sortedBy { it.name }
        val labels = sorted.map { v ->
            val quality = when {
                v.quality >= 400 -> getString(R.string.tts_quality_high)
                v.quality >= 300 -> getString(R.string.tts_quality_mid)
                else -> getString(R.string.tts_quality_low)
            }
            val net = if (v.isNetworkConnectionRequired) " · 需网络" else ""
            "${v.name}（$quality$net）"
        }.toTypedArray()
        val current = tts.currentVoiceName()
        val checked = sorted.indexOfFirst { it.name == current }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.tts_voice_pick)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val voice = sorted[which]
                tts.setVoice(voice)
                AppSettings.setVoiceName(this, voice.name)
                Toasts.show(this, voice.name)
                if (tts.currentState().state == TtsManager.State.SPEAKING) {
                    val snap = tts.currentState()
                    tts.playFrom(snap.paragraphIndex, snap.sentenceIndex)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun localeKey(locale: Locale): String {
        val lang = locale.language.ifBlank { "und" }
        val country = locale.country
        return if (country.isNullOrBlank()) lang else "${lang}_$country"
    }

    private fun localeDisplayName(locale: Locale): String {
        val name = runCatching {
            locale.getDisplayName(Locale.SIMPLIFIED_CHINESE)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (!name.isNullOrBlank()) return name
        return locale.toString().ifBlank { getString(R.string.tts_unknown_lang) }
    }

    private fun showTocSheet() {
        val b = book ?: return
        val dialog = BottomSheetDialog(this)
        val sheet = SheetTocBinding.inflate(LayoutInflater.from(this))
        dialog.setContentView(sheet.root)

        val tocAdapter = TocAdapter { item ->
            val index = when (item) {
                is TocItem.ChapterItem -> item.chapter.paragraphIndex
                is TocItem.BookmarkItem -> item.bookmark.paragraphIndex
            }
            dialog.dismiss()
            reader.scrollToParagraph(index)
            saveProgress(index)
            if (tts.currentState().state != TtsManager.State.IDLE) {
                tts.jumpToParagraph(index, autoPlay = true)
            } else {
                reader.clearHighlight()
            }
        }
        sheet.rvToc.layoutManager = LinearLayoutManager(this)
        sheet.rvToc.adapter = tocAdapter

        fun showChapters() {
            val items = b.chapters.map { TocItem.ChapterItem(it) }
            tocAdapter.submit(items, reader.firstVisibleParagraph())
            sheet.tvTocEmpty.isVisible = items.isEmpty()
            sheet.rvToc.isVisible = items.isNotEmpty()
            if (items.isEmpty()) sheet.tvTocEmpty.setText(R.string.toc_empty)
        }

        fun showBookmarks() {
            val items = BookmarkStore.list(this, fileKey).map { TocItem.BookmarkItem(it) }
            tocAdapter.submit(items, reader.firstVisibleParagraph())
            sheet.tvTocEmpty.isVisible = items.isEmpty()
            sheet.rvToc.isVisible = items.isNotEmpty()
            if (items.isEmpty()) sheet.tvTocEmpty.setText(R.string.bookmark_empty)
        }

        sheet.tabLayout.addTab(sheet.tabLayout.newTab().setText(R.string.toc))
        sheet.tabLayout.addTab(sheet.tabLayout.newTab().setText(R.string.bookmark))
        sheet.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showChapters()
                    1 -> showBookmarks()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        showChapters()
        dialog.show()
    }
}
