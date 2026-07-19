package com.whj.reader.tts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.whj.reader.R
import com.whj.reader.data.AppSettings
import com.whj.reader.data.TextLoader
import com.whj.reader.model.Paragraph
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 系统 TTS 封装：按句朗读，支持暂停/继续、上一句/下一句、跳段、发音人选择。
 *
 * 注意：Android TTS 无真正 pause，暂停用 stop + 记住位置，继续时从当前句重读。
 * Android 11+ 需在 Manifest 声明 queries TTS_SERVICE，否则会找不到引擎。
 */
class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    enum class State { IDLE, SPEAKING, PAUSED }

    data class Snapshot(
        val state: State,
        val paragraphIndex: Int,
        val sentenceIndex: Int,
        val ready: Boolean,
        val statusMessage: String = "",
    )

    /** 已安装 TTS 引擎；[packageName] 为 null 表示「自动」选择 */
    data class EngineInfo(
        val packageName: String?,
        val label: String,
        val isAuto: Boolean = false,
    )

    interface Listener {
        fun onStateChanged(snapshot: Snapshot)
        /**
         * 高亮当前朗读句（句号分段）。
         * @param startOffset 段内起始（含）
         * @param endOffset 段内结束（不含）；-1 表示清高亮
         */
        fun onSentenceHighlight(paragraphIndex: Int, startOffset: Int, endOffset: Int)
        fun onError(message: String)
        /**
         * 文档已读到末尾，但调用方可能还有未提取页。
         * 返回 true 表示正在补充内容，TTS 进入等待；补充后请调用 [continueAfterMoreContent]。
         */
        fun onNeedMoreContent(lastParagraphIndex: Int): Boolean = false
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var initAttempted = false
    private var statusMessage = ""
    private var paragraphs: List<Paragraph> = emptyList()
    private var sentenceSpans: List<List<TextLoader.SentenceSpan>> = emptyList()
    private var sentences: List<List<String>> = emptyList()

    private var state = State.IDLE
    private var paraIndex = 0
    private var sentIndex = 0
    private var speechRate = 1.0f
    private var pitch = 1.0f
    private var preferredVoiceName: String? = null
    /** 用户指定引擎包名；null = 自动（上次成功 / OEM 优先） */
    private var preferredEnginePackage: String? = null
    /** 上次成功绑定的引擎（启动时优先尝试） */
    private var lastEnginePackage: String? = null
    /** 用户指定语言 key，如 zh_CN */
    private var preferredLanguageKey: String? = null
    private var sentenceLineBreakMode: TextLoader.SentenceLineBreakMode =
        TextLoader.SentenceLineBreakMode.NEWLINE

    /** 初始化未完成时用户点了播放，就绪后自动开始 (para, sentence) */
    private var pendingPlay: Pair<Int, Int>? = null
    /** 首句从段内某偏移起读（读到该句末后清空，继续后续句） */
    private var firstUtteranceFromOffset: Int = -1
    /** 等待调用方补充更多文档内容 */
    private var waitingForMoreContent = false
    /**
     * 句间额外 delay（ms）。默认 0。
     * 连贯性靠：当前句播放时 [fillPipeline] 用 QUEUE_ADD 预排下一句。
     */
    private var sentenceGapMs: Long = 0L
    /**
     * 已提交给引擎、尚未 onDone 的 utterance（队首=当前/即将播）。
     * 深度 2：当前句 + 下一句，避免 onDone 后再 speak 的引擎空档。
     */
    private val pipeline = ArrayDeque<PendingUtt>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appCtx = context.applicationContext

    /** 一次 speak 的一句 */
    private data class PendingUtt(
        val id: String,
        val para: Int,
        val sent: Int,
        val text: String,
    )

    /** 待尝试的引擎包名队列；空串表示系统默认 */
    private val engineQueue: ArrayDeque<String> = ArrayDeque()
    private var currentEnginePackage: String? = null
    private var initGeneration = 0
    /** 单引擎绑定超时：过长会导致「点朗读要等很久」；本机 Google 默认常卡住 */
    private val initTimeoutMs = 2_500L
    /** 切换引擎完成后回调（主线程） */
    private var switchResultCallback: ((Boolean) -> Unit)? = null
    private var forceSingleEngine = false

    var listener: Listener? = null

    init {
        // 供通知栏「停止」回调
        activeRef = java.lang.ref.WeakReference(this)
        preferredEnginePackage = AppSettings.ttsEnginePackage(appCtx)
        lastEnginePackage = AppSettings.ttsLastEnginePackage(appCtx)
        preferredLanguageKey = AppSettings.ttsLanguageKey(appCtx)
        preferredVoiceName = AppSettings.voiceName(appCtx)
        Log.i(
            TAG,
            "prefs engine preferred=$preferredEnginePackage last=$lastEnginePackage " +
                "lang=$preferredLanguageKey voice=$preferredVoiceName",
        )
    }

    private fun str(resId: Int): String = appCtx.getString(resId)
    private fun str(resId: Int, vararg args: Any): String = appCtx.getString(resId, *args)

    fun init() {
        if (tts != null || initAttempted) return
        initAttempted = true
        statusMessage = str(R.string.tts_init_pending)
        forceSingleEngine = false
        buildEngineQueue()
        tryBindNextEngine()
        mainHandler.post {
            if (!ready) notifyState()
        }
    }

    /**
     * 列出已安装引擎（首项为「自动」）。
     */
    fun listEngines(): List<EngineInfo> {
        val result = ArrayList<EngineInfo>()
        result.add(
            EngineInfo(
                packageName = null,
                label = str(R.string.tts_engine_auto),
                isAuto = true,
            ),
        )
        val pm = appCtx.packageManager
        val services = queryInstalledTtsServices()
        val seen = LinkedHashSet<String>()
        for (ri in services) {
            val pkg = ri.serviceInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: pkg
            result.add(EngineInfo(packageName = pkg, label = label))
        }
        return result
    }

    /** 当前绑定的引擎包名（自动选择时可能为实际生效的包） */
    fun boundEnginePackage(): String? = currentEnginePackage ?: tts?.defaultEngine

    /** 用户偏好的引擎包名；null 表示自动 */
    fun preferredEnginePackage(): String? = preferredEnginePackage

    /**
     * 切换 TTS 引擎（保留文档与进度）。[packageName] null = 自动。
     * 回调在主线程，success=true 表示新引擎就绪。
     */
    fun switchEngine(packageName: String?, onResult: ((Boolean) -> Unit)? = null) {
        val target = packageName?.takeIf { it.isNotBlank() }
        preferredEnginePackage = target
        AppSettings.setTtsEnginePackage(appCtx, target)
        switchResultCallback = onResult
        forceSingleEngine = target != null

        // 保留文档/语速/音高/发音人偏好；停播并解绑
        pendingPlay = null
        firstUtteranceFromOffset = -1
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
        initAttempted = true
        state = State.IDLE
        statusMessage = str(R.string.tts_engine_switching)
        notifyState()

        engineQueue.clear()
        if (target != null) {
            // 用户明确指定：只绑该引擎（失败再给默认一次兜底）
            engineQueue.addLast(target)
            engineQueue.addLast("")
        } else {
            forceSingleEngine = false
            buildEngineQueue()
        }
        Log.i(TAG, "switchEngine target=${target ?: "auto"} queue=$engineQueue")
        tryBindNextEngine()
    }

    /**
     * 引擎顺序：用户偏好 → 上次成功 → 本机/OEM 优先 → 默认 → Google 最后。
     * 真机上默认常指向 Google，绑定慢甚至超时，先绑小米等可秒就绪。
     */
    private fun buildEngineQueue() {
        engineQueue.clear()
        val installed = queryInstalledTtsPackages()
        val preferred = preferredEnginePackage
        val last = lastEnginePackage
        // 1) 用户明确选择的引擎（即使 query 未列出也要先试，部分 OEM 列表不全）
        if (!preferred.isNullOrBlank()) {
            engineQueue.addLast(preferred)
        }
        // 2) 上次成功绑定（启动恢复）
        if (!last.isNullOrBlank()) {
            engineQueue.addLast(last)
        }
        val oem = installed.filter { pkg ->
            pkg.isNotBlank() &&
                !pkg.contains("google", ignoreCase = true)
        }
        val google = installed.filter { pkg ->
            pkg.contains("google", ignoreCase = true)
        }
        // 3) OEM（如 com.xiaomi.mibrain.speech）
        oem.forEach { engineQueue.addLast(it) }
        // 4) 系统默认（可能仍是 Google，超时会快速跳过）
        engineQueue.addLast("")
        // 5) Google 等
        google.forEach { pkg ->
            if (!engineQueue.contains(pkg)) engineQueue.addLast(pkg)
        }
        // 去重保序
        val seen = LinkedHashSet<String>()
        val unique = ArrayDeque<String>()
        while (engineQueue.isNotEmpty()) {
            val p = engineQueue.removeFirst()
            if (seen.add(p)) unique.addLast(p)
        }
        engineQueue.clear()
        engineQueue.addAll(unique)
        Log.i(TAG, "TTS engine queue=$engineQueue preferred=$preferred last=$last")
    }

    private fun queryInstalledTtsPackages(): List<String> =
        queryInstalledTtsServices().mapNotNull { it.serviceInfo?.packageName }.distinct()

    private fun queryInstalledTtsServices(): List<android.content.pm.ResolveInfo> {
        return runCatching {
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val pm = appCtx.packageManager
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentServices(
                    intent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
        }.getOrDefault(emptyList())
    }

    private fun tryBindNextEngine() {
        if (ready) return
        if (engineQueue.isEmpty()) {
            ready = false
            statusMessage = str(R.string.tts_init_failed)
            Log.e(TAG, "all TTS engines failed")
            listener?.onError(statusMessage)
            finishSwitchCallback(false)
            notifyState()
            return
        }
        val pkg = engineQueue.removeFirst()
        currentEnginePackage = pkg.ifBlank { null }
        // 清掉上一个未就绪实例
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        val gen = ++initGeneration
        try {
            tts = if (pkg.isBlank()) {
                Log.i(TAG, "binding default TTS engine")
                TextToSpeech(appCtx, this)
            } else {
                Log.i(TAG, "binding TTS engine package=$pkg")
                TextToSpeech(appCtx, this, pkg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "create TextToSpeech($pkg) failed", e)
            tryBindNextEngine()
            return
        }
        // 绑定超时：Google 等引擎可能 onInit 很久或永不回调
        mainHandler.postDelayed({
            if (gen != initGeneration || ready) return@postDelayed
            Log.w(TAG, "TTS init timeout engine=${currentEnginePackage ?: "default"}, try next")
            statusMessage = str(R.string.tts_still_not_ready)
            notifyState()
            tryBindNextEngine()
        }, initTimeoutMs)
    }

    private fun finishSwitchCallback(success: Boolean) {
        val cb = switchResultCallback ?: return
        switchResultCallback = null
        forceSingleEngine = false
        mainHandler.post { cb(success) }
    }

    /** 失败后允许重新初始化 */
    fun reinit() {
        mainHandler.removeCallbacksAndMessages(null)
        switchResultCallback = null
        forceSingleEngine = false
        shutdownInternal(keepDocument = true)
        initAttempted = false
        ready = false
        init()
    }

    /**
     * 注意：系统常在 binder 线程回调 onInit，必须切主线程再动 UI / speak。
     */
    override fun onInit(status: Int) {
        Log.i(
            TAG,
            "onInit status=$status engine=${currentEnginePackage ?: "default"} " +
                "defaultEngine=${tts?.defaultEngine}",
        )
        mainHandler.post { handleOnInit(status) }
    }

    private fun handleOnInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            Log.e(TAG, "onInit failed status=$status engine=$currentEnginePackage")
            // 换下一个引擎
            tryBindNextEngine()
            return
        }
        val engine = tts
        if (engine == null) {
            ready = false
            statusMessage = str(R.string.tts_engine_null)
            tryBindNextEngine()
            return
        }

        // 成功：取消后续引擎尝试
        initGeneration++ // 使超时回调失效
        engineQueue.clear()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
        }
        // 先恢复发音人；成功则不必再 setLanguage（避免冲掉已选 voice）
        var voiceOk = false
        if (!preferredVoiceName.isNullOrBlank()) {
            voiceOk = applyVoiceByName(preferredVoiceName, persist = false)
        }
        val langOk = if (voiceOk) {
            true
        } else {
            setPreferredLanguage(engine).also {
                // 语言设完后再试一次发音人（部分引擎 onInit 时 voices 尚未就绪）
                if (!preferredVoiceName.isNullOrBlank()) {
                    voiceOk = applyVoiceByName(preferredVoiceName, persist = false)
                }
            }
        }
        applySpeechRateToEngine(engine)
        engine.setPitch(pitch)
        // 延迟再应用发音人：Google/部分 OEM 在 onInit 后 voices 才就绪或会短暂被默认 voice 覆盖
        if (!preferredVoiceName.isNullOrBlank()) {
            val name = preferredVoiceName
            fun retryVoice(tag: String) {
                if (!ready || preferredVoiceName != name) return
                val ok = applyVoiceByName(name, persist = false)
                Log.i(TAG, "$tag applyVoice name=$name ok=$ok current=${tts?.voice?.name}")
                if (ok) notifyState()
            }
            if (!voiceOk) {
                mainHandler.postDelayed({ retryVoice("delayed") }, 400)
                mainHandler.postDelayed({ retryVoice("delayed2") }, 1200)
                mainHandler.postDelayed({ retryVoice("delayed3") }, 3000)
            } else {
                // 已成功仍再确认一次，防止引擎异步改回默认发音人
                mainHandler.postDelayed({ retryVoice("confirm") }, 800)
            }
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    if (state == State.IDLE || state == State.PAUSED) return@post
                    onUtteranceStart(utteranceId)
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    if (state != State.SPEAKING) return@post
                    onUtteranceDone(utteranceId)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    pipeline.clear()
                    state = State.IDLE
                    statusMessage = str(R.string.tts_status_error)
                    notifyState()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post {
                    pipeline.clear()
                    state = State.IDLE
                    statusMessage = str(R.string.tts_status_error_code, errorCode)
                    Log.e(TAG, "utterance error code=$errorCode id=$utteranceId")
                    notifyState()
                }
            }
        })

        // 绑定成功后记录实际引擎包名；不要用「回退引擎」覆盖用户偏好（否则重启会丢选择）
        if (currentEnginePackage.isNullOrBlank()) {
            currentEnginePackage = runCatching { engine.defaultEngine }.getOrNull()
        }
        val bound = currentEnginePackage?.takeIf { it.isNotBlank() }
            ?: runCatching { engine.defaultEngine }.getOrNull()?.takeIf { it.isNotBlank() }
        if (!bound.isNullOrBlank()) {
            currentEnginePackage = bound
            lastEnginePackage = bound
            AppSettings.setTtsLastEnginePackage(appCtx, bound)
            // 仅当用户偏好就是该包（或用户选了自动）时写回偏好
            if (preferredEnginePackage.isNullOrBlank() || preferredEnginePackage == bound) {
                // 自动模式：只记 last，不写 preferred
            } else if (preferredEnginePackage == bound) {
                AppSettings.setTtsEnginePackage(appCtx, bound)
            }
            // preferred != bound 说明当前是回退引擎，保留 preferred 以便下次仍优先试用户选择
        }

        ready = true
        forceSingleEngine = false
        statusMessage = if (langOk || voiceOk) str(R.string.tts_ready) else str(R.string.tts_ready_no_zh)
        Log.i(
            TAG,
            "$statusMessage voice=${engine.voice?.name} prefVoice=$preferredVoiceName " +
                "lang=${engine.voice?.locale} engine=${engine.defaultEngine} " +
                "bound=$currentEnginePackage preferred=$preferredEnginePackage voiceOk=$voiceOk",
        )
        notifyState()
        finishSwitchCallback(true)

        pendingPlay?.let { (p, s) ->
            pendingPlay = null
            playFrom(p, s)
        }
    }

    private fun setPreferredLanguage(engine: TextToSpeech): Boolean {
        val preferred = preferredLanguageKey?.let { localeFromKey(it) }
        val candidates = buildList {
            if (preferred != null) add(preferred)
            add(Locale.SIMPLIFIED_CHINESE)
            add(Locale.CHINESE)
            add(Locale.TRADITIONAL_CHINESE)
            add(Locale.getDefault())
            add(Locale.US)
        }
        for (locale in candidates) {
            val r = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            Log.i(TAG, "setLanguage $locale -> $r")
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                return true
            }
        }
        return false
    }

    fun preferredLanguageKey(): String? = preferredLanguageKey

    fun setPreferredLanguageKey(key: String?) {
        preferredLanguageKey = key?.takeIf { it.isNotBlank() }
        AppSettings.setTtsLanguageKey(appCtx, preferredLanguageKey)
        val locale = preferredLanguageKey?.let { localeFromKey(it) } ?: return
        val engine = tts ?: return
        if (!ready) return
        runCatching { engine.setLanguage(locale) }
    }

    fun localeKey(locale: Locale): String {
        val lang = locale.language.ifBlank { "und" }
        val country = locale.country
        return if (country.isNullOrBlank()) lang else "${lang}_$country"
    }

    fun localeFromKey(key: String): Locale {
        val parts = key.split('_', '-', limit = 3)
        return when (parts.size) {
            1 -> Locale(parts[0])
            2 -> Locale(parts[0], parts[1])
            else -> Locale(parts[0], parts[1], parts[2])
        }
    }

    fun localeDisplayName(locale: Locale): String {
        val name = runCatching {
            locale.getDisplayName(Locale.SIMPLIFIED_CHINESE)
        }.getOrNull().orEmpty()
        return name.ifBlank {
            locale.toString().ifBlank { str(R.string.tts_unknown_lang) }
        }
    }

    fun setDocument(
        paragraphs: List<Paragraph>,
        lineBreakMode: TextLoader.SentenceLineBreakMode = TextLoader.SentenceLineBreakMode.NEWLINE,
    ) {
        stop()
        waitingForMoreContent = false
        sentenceLineBreakMode = lineBreakMode
        this.paragraphs = paragraphs
        this.sentenceSpans = paragraphs.map {
            TextLoader.splitSentenceSpans(it.text, lineBreakMode)
        }
        this.sentences = sentenceSpans.map { spans -> spans.map { it.text } }
        paraIndex = 0
        sentIndex = 0
    }

    /**
     * 更新文档（例如 PDF 懒加载新页）且尽量保持当前段/句位置，不打断正在播的句子。
     * 新段落应以前缀兼容方式追加，使旧索引仍有效。
     */
    fun updateDocumentKeepPosition(
        paragraphs: List<Paragraph>,
        lineBreakMode: TextLoader.SentenceLineBreakMode = sentenceLineBreakMode,
    ) {
        sentenceLineBreakMode = lineBreakMode
        val oldP = paraIndex
        val oldS = sentIndex
        this.paragraphs = paragraphs
        this.sentenceSpans = paragraphs.map {
            TextLoader.splitSentenceSpans(it.text, lineBreakMode)
        }
        this.sentences = sentenceSpans.map { spans -> spans.map { it.text } }
        if (paragraphs.isEmpty()) {
            paraIndex = 0
            sentIndex = 0
            return
        }
        paraIndex = oldP.coerceIn(0, paragraphs.lastIndex)
        val sents = sentences.getOrNull(paraIndex).orEmpty()
        sentIndex = if (sents.isEmpty()) 0 else oldS.coerceIn(0, sents.lastIndex)
    }

    /**
     * 补充内容后继续朗读：若在等待更多内容，则从下一段/下一句开始；
     * 若当前段后还有句则继续当前逻辑。
     */
    fun continueAfterMoreContent() {
        if (!waitingForMoreContent) return
        waitingForMoreContent = false
        if (paragraphs.isEmpty() || !ready) {
            state = State.IDLE
            statusMessage = str(R.string.tts_status_ended)
            notifyState()
            return
        }
        // 从「上次末尾」之后继续：若当前段还有下一句则下一句，否则下一段
        val sents = sentences.getOrNull(paraIndex).orEmpty()
        if (sentIndex < sents.lastIndex) {
            sentIndex++
            state = State.SPEAKING
            speakCurrent()
            return
        }
        if (paraIndex < paragraphs.lastIndex) {
            paraIndex++
            sentIndex = 0
            state = State.SPEAKING
            speakCurrent()
            return
        }
        // 仍无更多
        state = State.IDLE
        statusMessage = str(R.string.tts_status_ended)
        notifyState()
    }

    /**
     * @param restartCurrent 为 true 且正在朗读/暂停时，立即用新语速重读当前句
     */
    fun setSpeechRate(rate: Float, restartCurrent: Boolean = false) {
        speechRate = rate.coerceIn(0.5f, 2.5f)
        val r = applySpeechRateToEngine(tts)
        Log.i(TAG, "setSpeechRate=$speechRate engineResult=$r eng=${boundEnginePackage()}")
        if (restartCurrent && ready && paragraphs.isNotEmpty() &&
            (state == State.SPEAKING || state == State.PAUSED)
        ) {
            playFrom(paraIndex, sentIndex)
        }
    }

    fun setPitch(value: Float) {
        pitch = value.coerceIn(0.5f, 2.0f)
        tts?.setPitch(pitch)
    }

    /**
     * 客户端 setSpeechRate：倍率 1.0=正常。
     * 小米 libcntts 仅接受 speed∈(0.5, 2.0)，超出会忽略并回落默认。
     */
    private fun applySpeechRateToEngine(engine: TextToSpeech?): Int {
        if (engine == null) return TextToSpeech.ERROR
        val rateForEngine = effectiveSpeechRateForEngine()
        return runCatching { engine.setSpeechRate(rateForEngine) }
            .getOrDefault(TextToSpeech.ERROR)
    }

    private fun isXiaomiEngine(): Boolean {
        val pkg = (currentEnginePackage ?: tts?.defaultEngine).orEmpty()
        return pkg.contains("xiaomi", ignoreCase = true) ||
            pkg.contains("mibrain", ignoreCase = true) ||
            pkg.contains("miui", ignoreCase = true)
    }

    /**
     * 交给引擎的有效语速倍率。
     * 小米 mibrain/libcntts：`synthesis speed out of range: (0.5, 2.0)`，
     * 必须落在该开区间内（用 0.55～1.95 避免边界被拒）。
     */
    private fun effectiveSpeechRateForEngine(): Float {
        return if (isXiaomiEngine()) {
            speechRate.coerceIn(XIAOMI_SPEED_MIN, XIAOMI_SPEED_MAX)
        } else {
            speechRate.coerceIn(0.5f, 2.5f)
        }
    }

    private fun effectivePitchForEngine(): Float {
        // 小米音高仍走框架 100=正常；倍率限制在合理范围
        return pitch.coerceIn(0.5f, 2.0f)
    }

    /** 系统设置默认语速（本机曾见 600；AOSP 正常为 100） */
    private fun systemTtsDefaultRate(): Int =
        runCatching {
            Settings.Secure.getInt(
                appCtx.contentResolver,
                Settings.Secure.TTS_DEFAULT_RATE,
                100,
            )
        }.getOrDefault(100)

    /**
     * 应用倍率 → Bundle 整数 rate。
     *
     * 框架与 [TextToSpeech.setSpeechRate] 约定：`rateInt = (int)(speed * 100)`，100=1.0x。
     * 小米底层 libcntts 使用 float speed∈(0.5,2.0)，故 rateInt 对应约 55～195。
     * 切勿用 0～100/50=正常 的讯飞刻度，也勿把 2.5x 写成 250（speed=2.5 超范围被丢弃）。
     */
    private fun speechRateToEngineParam(mult: Float): Int {
        val speed = if (isXiaomiEngine()) {
            mult.coerceIn(XIAOMI_SPEED_MIN, XIAOMI_SPEED_MAX)
        } else {
            mult.coerceIn(0.5f, 2.5f)
        }
        // 与 AOSP TextToSpeech.setSpeechRate 内部一致
        return (speed * 100f).roundToInt().coerceAtLeast(1)
    }

    private fun pitchToEngineParam(mult: Float): Int {
        val p = mult.coerceIn(0.5f, 2.0f)
        return (p * 100f).roundToInt().coerceAtLeast(1)
    }

    /**
     * speak 参数。rate/pitch 必须是 **Int**（TextToSpeechService.getIntParam）。
     * 会与 setSpeechRate 写入的 mParams 合并；这里显式写入，避免系统默认 600 等异常值盖过。
     */
    private fun buildSpeakParams(): Bundle {
        val params = Bundle()
        val speed = effectiveSpeechRateForEngine()
        val rateParam = speechRateToEngineParam(speechRate)
        val pitchParam = pitchToEngineParam(pitch)
        params.putInt(KEY_PARAM_RATE, rateParam)
        params.putInt(KEY_PARAM_PITCH, pitchParam)
        Log.i(
            TAG,
            "speak params rateInt=$rateParam (speed=$speed) pitchInt=$pitchParam " +
                "uiRate=$speechRate uiPitch=$pitch xiaomi=${isXiaomiEngine()} " +
                "sysRate=${systemTtsDefaultRate()} eng=${boundEnginePackage()}",
        )
        return params
    }

    fun getVoices(): List<Voice> {
        val engine = tts ?: return emptyList()
        if (!ready) return emptyList()
        return engine.voices
            ?.toList()
            ?.sortedWith(
                compareByDescending<Voice> { it.locale.language.startsWith("zh") }
                    .thenBy { it.locale.toString() }
                    .thenBy { it.name },
            )
            ?: emptyList()
    }

    fun setVoice(voice: Voice?) {
        preferredVoiceName = voice?.name
        AppSettings.setVoiceName(appCtx, preferredVoiceName)
        if (voice != null) {
            preferredLanguageKey = localeKey(voice.locale)
            AppSettings.setTtsLanguageKey(appCtx, preferredLanguageKey)
            // 选定发音人时锁定当前引擎，保证重启后仍能找到同名 voice
            val bound = boundEnginePackage()?.takeIf { it.isNotBlank() }
            if (!bound.isNullOrBlank()) {
                preferredEnginePackage = bound
                lastEnginePackage = bound
                AppSettings.setTtsEnginePackage(appCtx, bound)
                AppSettings.setTtsLastEnginePackage(appCtx, bound)
            }
            runCatching {
                tts?.language = voice.locale
                tts?.voice = voice
            }
            Log.i(
                TAG,
                "setVoice saved name=${voice.name} locale=${voice.locale} engine=$bound",
            )
        } else {
            AppSettings.setVoiceName(appCtx, null)
        }
    }

    /**
     * @param persist 是否写回 prefs；启动恢复时用 false，避免无意义反复 commit
     */
    fun applyVoiceByName(name: String?, persist: Boolean = true): Boolean {
        if (name.isNullOrBlank()) return false
        preferredVoiceName = name
        if (persist) {
            AppSettings.setVoiceName(appCtx, name)
        }
        val voices = getVoices()
        if (voices.isEmpty()) {
            Log.w(TAG, "applyVoiceByName: voices empty, keep name=$name for retry")
            return false
        }
        val voice = voices.firstOrNull { it.name == name }
            ?: voices.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return false.also {
                Log.w(TAG, "applyVoiceByName: name=$name not in ${voices.size} voices")
            }
        return runCatching {
            tts?.language = voice.locale
            tts?.voice = voice
            preferredLanguageKey = localeKey(voice.locale)
            if (persist) {
                AppSettings.setTtsLanguageKey(appCtx, preferredLanguageKey)
            }
            Log.i(TAG, "applyVoiceByName ok name=${voice.name}")
            true
        }.getOrDefault(false)
    }

    fun currentVoiceName(): String? = tts?.voice?.name ?: preferredVoiceName

    /** 当前引擎下按语言分组的发音人（语言 key → 列表） */
    fun voicesByLanguage(): LinkedHashMap<String, MutableList<Voice>> {
        val byLang = linkedMapOf<String, MutableList<Voice>>()
        getVoices().forEach { v ->
            val key = localeKey(v.locale)
            byLang.getOrPut(key) { mutableListOf() }.add(v)
        }
        return byLang
    }

    fun isReady(): Boolean = ready

    fun playFrom(paragraphIndex: Int, sentenceIndex: Int = 0) {
        firstUtteranceFromOffset = -1
        playFromInternal(paragraphIndex, sentenceIndex)
    }

    /**
     * 从指定段内字符偏移读到全书末尾。
     * 首句从 [charOffset] 起（可句中），之后按句继续。
     */
    fun playFromParagraphOffset(paragraphIndex: Int, charOffset: Int) {
        if (paragraphs.isEmpty()) {
            listener?.onError(str(R.string.tts_no_content))
            return
        }
        val p = paragraphIndex.coerceIn(0, paragraphs.lastIndex)
        val text = paragraphs[p].text
        val off = charOffset.coerceIn(0, text.length)
        val spans = sentenceSpans.getOrNull(p).orEmpty()
        var si = spans.indexOfFirst { off < it.end }
        if (si < 0) si = 0
        firstUtteranceFromOffset = if (
            spans.isNotEmpty() &&
            off > (spans.getOrNull(si)?.start ?: 0) &&
            off < (spans.getOrNull(si)?.end ?: 0)
        ) {
            off
        } else {
            -1
        }
        playFromInternal(p, si)
    }

    private fun playFromInternal(paragraphIndex: Int, sentenceIndex: Int) {
        if (paragraphs.isEmpty()) {
            listener?.onError(str(R.string.tts_no_content))
            return
        }
        if (!ready) {
            pendingPlay = paragraphIndex to sentenceIndex
            if (tts == null) {
                reinit()
            }
            statusMessage = str(R.string.tts_waiting)
            listener?.onError(str(R.string.tts_initializing))
            notifyState()
            return
        }
        paraIndex = paragraphIndex.coerceIn(0, paragraphs.lastIndex)
        sentIndex = sentenceIndex.coerceAtLeast(0)
        val sents = sentences.getOrNull(paraIndex).orEmpty()
        if (sents.isEmpty()) {
            firstUtteranceFromOffset = -1
            state = State.SPEAKING
            advanceAndSpeak()
            return
        }
        if (sentIndex > sents.lastIndex) sentIndex = sents.lastIndex
        state = State.SPEAKING
        speakCurrent()
    }

    fun playPauseToggle() {
        when (state) {
            State.SPEAKING -> pause()
            State.PAUSED -> resume()
            State.IDLE -> playFrom(paraIndex, sentIndex)
        }
    }

    fun pause() {
        if (state != State.SPEAKING) return
        clearQueueState()
        tts?.stop()
        state = State.PAUSED
        statusMessage = str(R.string.tts_status_paused)
        notifyState()
    }

    fun resume() {
        if (state != State.PAUSED && state != State.IDLE) return
        playFrom(paraIndex, sentIndex)
    }

    fun stop() {
        pendingPlay = null
        waitingForMoreContent = false
        clearQueueState()
        tts?.stop()
        state = State.IDLE
        if (ready) statusMessage = str(R.string.tts_status_idle)
        notifyState()
    }

    private fun clearQueueState() {
        pipeline.clear()
        mainHandler.removeCallbacksAndMessages(null)
    }

    /** 等待补充内容失败/无更多页时结束 */
    fun finishWaitingNoMore() {
        if (!waitingForMoreContent) return
        waitingForMoreContent = false
        state = State.IDLE
        statusMessage = str(R.string.tts_status_ended)
        notifyState()
        notifySentenceHighlight(clear = true)
    }

    fun nextSentence() {
        if (paragraphs.isEmpty()) return
        val wasPlaying = state == State.SPEAKING || state == State.PAUSED
        clearQueueState()
        tts?.stop()
        val sents = sentences.getOrNull(paraIndex).orEmpty()
        if (sentIndex < sents.lastIndex) {
            sentIndex++
        } else if (paraIndex < paragraphs.lastIndex) {
            paraIndex++
            sentIndex = 0
        } else {
            state = State.IDLE
            notifyState()
            notifySentenceHighlight(clear = true)
            return
        }
        if (wasPlaying || state == State.IDLE) {
            state = State.SPEAKING
            speakCurrent()
        } else {
            notifySentenceHighlight()
            notifyState()
        }
    }

    fun previousSentence() {
        if (paragraphs.isEmpty()) return
        val wasPlaying = state == State.SPEAKING || state == State.PAUSED
        clearQueueState()
        tts?.stop()
        if (sentIndex > 0) {
            sentIndex--
        } else if (paraIndex > 0) {
            paraIndex--
            val prev = sentences.getOrNull(paraIndex).orEmpty()
            sentIndex = (prev.size - 1).coerceAtLeast(0)
        }
        if (wasPlaying) {
            state = State.SPEAKING
            speakCurrent()
        } else {
            notifySentenceHighlight()
            notifyState()
        }
    }

    fun jumpToParagraph(index: Int, autoPlay: Boolean = true) {
        if (paragraphs.isEmpty()) return
        clearQueueState()
        tts?.stop()
        paraIndex = index.coerceIn(0, paragraphs.lastIndex)
        sentIndex = 0
        if (autoPlay) {
            state = State.SPEAKING
            speakCurrent()
        } else {
            state = State.IDLE
            notifySentenceHighlight(clear = true)
            notifyState()
        }
    }

    fun currentParagraphIndex(): Int = paraIndex

    fun currentState(): Snapshot = Snapshot(state, paraIndex, sentIndex, ready, statusMessage)

    fun openTtsSettingsIntent(): Intent =
        Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openInstallDataIntent(): Intent =
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)

    fun shutdown() {
        shutdownInternal(keepDocument = false)
    }

    private fun shutdownInternal(keepDocument: Boolean) {
        mainHandler.removeCallbacksAndMessages(null)
        pendingPlay = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
        initAttempted = false
        state = State.IDLE
        statusMessage = "TTS 已关闭"
        TtsPlaybackService.stop(appCtx)
        if (!keepDocument) {
            paragraphs = emptyList()
            sentences = emptyList()
            sentenceSpans = emptyList()
            paraIndex = 0
            sentIndex = 0
        }
    }

    /** 句间间隔毫秒；默认 0。 */
    fun setSentenceGapMs(ms: Long) {
        sentenceGapMs = ms.coerceAtLeast(0L)
    }

    private fun onUtteranceStart(utteranceId: String?) {
        if (utteranceId.isNullOrEmpty()) return
        val utt = pipeline.firstOrNull { it.id == utteranceId } ?: return
        paraIndex = utt.para
        sentIndex = utt.sent
        state = State.SPEAKING
        notifySentenceHighlight()
        notifyState()
        // 开播时补排下一句
        fillPipeline()
    }

    private fun onUtteranceDone(utteranceId: String?) {
        if (utteranceId.isNullOrEmpty()) {
            scheduleAdvanceFromEndOfPipeline()
            return
        }
        // 丢弃已完成项
        if (pipeline.isNotEmpty() && pipeline.first().id == utteranceId) {
            val done = pipeline.removeFirst()
            paraIndex = done.para
            sentIndex = done.sent
        } else {
            val it = pipeline.iterator()
            var removed: PendingUtt? = null
            while (it.hasNext()) {
                val u = it.next()
                if (u.id == utteranceId) {
                    it.remove()
                    removed = u
                    break
                }
            }
            if (removed == null) {
                // 过期 id（stop/跳转后），忽略
                return
            }
            paraIndex = removed.para
            sentIndex = removed.sent
        }

        // 下一句已在引擎队列：只对齐高亮并补排，禁止再 speak
        if (pipeline.isNotEmpty()) {
            val next = pipeline.first()
            val alreadyOnNext = paraIndex == next.para && sentIndex == next.sent
            if (!alreadyOnNext) {
                // 部分引擎对 QUEUE_ADD 不调 onStart，在此兜底高亮
                paraIndex = next.para
                sentIndex = next.sent
                notifySentenceHighlight()
            }
            notifyState()
            fillPipeline()
            return
        }

        // 管道空了：按 gap 推进并 speak 下一句
        scheduleAdvanceFromEndOfPipeline()
    }

    private fun scheduleAdvanceFromEndOfPipeline() {
        val go = {
            if (state == State.SPEAKING) advanceAndSpeak()
        }
        if (sentenceGapMs <= 0L) go()
        else mainHandler.postDelayed(go, sentenceGapMs)
    }

    /** 当前句已结束且无预排，推进到下一句并 speak */
    private fun advanceAndSpeak() {
        val sents = sentences.getOrNull(paraIndex).orEmpty()
        if (sentIndex < sents.lastIndex) {
            sentIndex++
            speakCurrent(flush = false)
            return
        }
        if (paraIndex < paragraphs.lastIndex) {
            paraIndex++
            sentIndex = 0
            while (paraIndex <= paragraphs.lastIndex &&
                sentences.getOrNull(paraIndex).orEmpty().isEmpty()
            ) {
                paraIndex++
            }
            if (paraIndex > paragraphs.lastIndex) {
                if (requestMoreContentOrEnd()) return
                finishPlayback()
                return
            }
            speakCurrent(flush = false)
            return
        }
        if (requestMoreContentOrEnd()) return
        finishPlayback()
    }

    private fun finishPlayback() {
        pipeline.clear()
        state = State.IDLE
        statusMessage = str(R.string.tts_status_ended)
        notifyState()
        notifySentenceHighlight(clear = true)
    }

    /** @return true 表示正在等待补充内容 */
    private fun requestMoreContentOrEnd(): Boolean {
        val need = runCatching {
            listener?.onNeedMoreContent(paraIndex) == true
        }.getOrDefault(false)
        if (need) {
            waitingForMoreContent = true
            statusMessage = str(R.string.tts_waiting)
            // 保持 SPEAKING 语义上的「未结束」，但暂不 speak
            notifyState()
            return true
        }
        waitingForMoreContent = false
        return false
    }

    /**
     * @param flush true 清空队列（起播/跳转）；false 用 QUEUE_ADD 紧接上一句
     */
    private fun speakCurrent(flush: Boolean = true) {
        val engine = tts
        if (engine == null || !ready) {
            listener?.onError(str(R.string.tts_not_ready))
            return
        }
        if (flush) {
            pipeline.clear()
        }
        // 管道里已有预排时，只补满，禁止重复 speak
        if (!flush && pipeline.isNotEmpty()) {
            fillPipeline()
            state = State.SPEAKING
            statusMessage = str(R.string.tts_status_speaking)
            notifyState()
            return
        }
        val unit = buildSentenceUnit(paraIndex, sentIndex, applyOffset = true) ?: run {
            firstUtteranceFromOffset = -1
            advanceAndSpeak()
            return
        }
        if (unit.text.isBlank()) {
            firstUtteranceFromOffset = -1
            paraIndex = unit.para
            sentIndex = unit.sent
            advanceAndSpeak()
            return
        }
        paraIndex = unit.para
        sentIndex = unit.sent
        notifySentenceHighlight()
        // 小米等引擎：每次 speak 前重设；rate 必须在引擎允许范围内
        applySpeechRateToEngine(engine)
        engine.setPitch(effectivePitchForEngine())
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = engine.speak(unit.text, mode, buildSpeakParams(), unit.id)
        if (result == TextToSpeech.ERROR) {
            pipeline.clear()
            state = State.IDLE
            statusMessage = str(R.string.tts_speak_failed)
            listener?.onError(str(R.string.tts_status_fail))
            notifyState()
            return
        }
        pipeline.addLast(unit)
        Log.i(TAG, "speak p=${unit.para} s=${unit.sent} len=${unit.text.length} mode=$mode")
        fillPipeline()
        state = State.SPEAKING
        statusMessage = str(R.string.tts_status_speaking)
        notifyState()
    }

    /** 用 QUEUE_ADD 预排下一句，保持管道深度为 2 */
    private fun fillPipeline() {
        if (sentenceGapMs > 0L) return
        val engine = tts ?: return
        if (!ready) return
        while (pipeline.size < PIPELINE_DEPTH) {
            val last = pipeline.lastOrNull() ?: break
            val nextPos = nextSentencePos(last.para, last.sent) ?: break
            val unit = buildSentenceUnit(nextPos.first, nextPos.second, applyOffset = false) ?: break
            if (unit.text.isBlank()) {
                val skip = nextSentencePos(unit.para, unit.sent) ?: break
                val retry = buildSentenceUnit(skip.first, skip.second, applyOffset = false) ?: break
                if (retry.text.isBlank()) break
                val r = engine.speak(retry.text, TextToSpeech.QUEUE_ADD, buildSpeakParams(), retry.id)
                if (r == TextToSpeech.ERROR) break
                pipeline.addLast(retry)
                Log.i(TAG, "prequeue p=${retry.para} s=${retry.sent}")
                continue
            }
            val r = engine.speak(unit.text, TextToSpeech.QUEUE_ADD, buildSpeakParams(), unit.id)
            if (r == TextToSpeech.ERROR) break
            pipeline.addLast(unit)
            Log.i(TAG, "prequeue p=${unit.para} s=${unit.sent}")
        }
    }

    /** (para, sent) 的下一句位置 */
    private fun nextSentencePos(para: Int, sent: Int): Pair<Int, Int>? {
        val cur = sentences.getOrNull(para).orEmpty()
        if (sent < cur.lastIndex) return para to (sent + 1)
        var p = para + 1
        while (p <= paragraphs.lastIndex) {
            val list = sentences.getOrNull(p).orEmpty()
            if (list.isNotEmpty()) return p to 0
            p++
        }
        return null
    }

    /** 构建单句 utterance；空段则跳到下一有效句 */
    private fun buildSentenceUnit(para: Int, sent: Int, applyOffset: Boolean): PendingUtt? {
        if (para !in paragraphs.indices) return null
        val sents = sentences.getOrNull(para).orEmpty()
        if (sents.isEmpty() || sent !in sents.indices) {
            val next = nextSentencePos(para, sent.coerceAtLeast(0)) ?: return null
            return buildSentenceUnit(next.first, next.second, applyOffset)
        }
        var text = sents[sent].trim()
        if (applyOffset && firstUtteranceFromOffset >= 0) {
            val span = sentenceSpans.getOrNull(para)?.getOrNull(sent)
            val body = paragraphs.getOrNull(para)?.text.orEmpty()
            val from = firstUtteranceFromOffset
            firstUtteranceFromOffset = -1
            if (span != null && from in span.start until span.end) {
                text = body.substring(from, span.end).trim()
            }
        }
        if (text.isEmpty()) {
            val next = nextSentencePos(para, sent) ?: return null
            return buildSentenceUnit(next.first, next.second, applyOffset = false)
        }
        val id = "p${para}_s${sent}_${System.nanoTime()}"
        return PendingUtt(id, para, sent, text)
    }

    private fun notifySentenceHighlight(clear: Boolean = false) {
        if (clear || paragraphs.isEmpty()) {
            listener?.onSentenceHighlight(-1, 0, -1)
            return
        }
        val span = sentenceSpans.getOrNull(paraIndex)?.getOrNull(sentIndex)
        if (span == null) {
            // 无句信息时退回整段
            val len = paragraphs.getOrNull(paraIndex)?.text?.length ?: 0
            listener?.onSentenceHighlight(paraIndex, 0, len)
        } else {
            listener?.onSentenceHighlight(paraIndex, span.start, span.end)
        }
    }

    private fun notifyState() {
        val snap = Snapshot(state, paraIndex, sentIndex, ready, statusMessage)
        listener?.onStateChanged(snap)
        // 仅 SPEAKING 时持有前台服务 + PARTIAL_WAKE_LOCK；
        // 暂停 / 停止立刻停服务并释放锁（对齐 MediaSession：播时持锁、停/暂停释放）
        mainHandler.post {
            when (state) {
                State.SPEAKING -> TtsPlaybackService.start(appCtx)
                State.PAUSED, State.IDLE -> TtsPlaybackService.stop(appCtx)
            }
        }
    }

    /** 当前句在段内的 [start, end) */
    fun currentSentenceRange(): IntRange? {
        val span = sentenceSpans.getOrNull(paraIndex)?.getOrNull(sentIndex) ?: return null
        return span.start until span.end
    }

    companion object {
        private const val TAG = "WhjTts"
        /**
         * 与 TextToSpeechService 一致：整数 100 = 正常（= float 1.0）。
         * 勿写 String，否则 getIntParam 失败会回退 Secure.tts_default_rate（本机曾为 600）。
         */
        private const val KEY_PARAM_RATE = "rate"
        private const val KEY_PARAM_PITCH = "pitch"
        /**
         * 小米 libcntts.so：`synthesis speed out of range: (0.5, 2.0)`。
         * 取开区间内侧，避免边界被拒后回落 1.0。
         */
        private const val XIAOMI_SPEED_MIN = 0.55f
        private const val XIAOMI_SPEED_MAX = 1.95f
        /** 引擎中同时保持的 utterance 数（当前句 + 下一句） */
        private const val PIPELINE_DEPTH = 2
        private var activeRef: java.lang.ref.WeakReference<TtsManager>? = null

        /** 通知栏停止：结束当前朗读 */
        fun stopFromNotification() {
            activeRef?.get()?.stop()
        }
    }
}
