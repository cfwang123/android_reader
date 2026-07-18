package com.whj.reader.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.whj.reader.R
import com.whj.reader.data.TextLoader
import com.whj.reader.model.Paragraph
import java.util.Locale

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

    interface Listener {
        fun onStateChanged(snapshot: Snapshot)
        fun onParagraphHighlight(paragraphIndex: Int)
        fun onError(message: String)
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var initAttempted = false
    private var statusMessage = ""
    private var paragraphs: List<Paragraph> = emptyList()
    private var sentences: List<List<String>> = emptyList()

    private var state = State.IDLE
    private var paraIndex = 0
    private var sentIndex = 0
    private var speechRate = 1.0f
    private var pitch = 1.0f
    private var preferredVoiceName: String? = null

    /** 初始化未完成时用户点了播放，就绪后自动开始 */
    private var pendingPlay: Pair<Int, Int>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appCtx = context.applicationContext

    var listener: Listener? = null

    private fun str(resId: Int): String = appCtx.getString(resId)
    private fun str(resId: Int, vararg args: Any): String = appCtx.getString(resId, *args)

    fun init() {
        if (tts != null || initAttempted) return
        initAttempted = true
        statusMessage = str(R.string.tts_init_pending)
        // 不在此处同步 notify：Activity 可能尚未完成 UI 初始化
        try {
            // 使用默认引擎；applicationContext 避免泄漏
            tts = TextToSpeech(appCtx, this)
            // 将「初始化中」状态延后到下一消息，确保 Activity 已挂好 listener/UI
            mainHandler.post {
                if (!ready) notifyState()
            }
            // 部分机型 onInit 很慢，超时提示
            mainHandler.postDelayed({
                if (!ready) {
                    statusMessage = str(R.string.tts_still_not_ready)
                    notifyState()
                }
            }, 8000)
        } catch (e: Exception) {
            Log.e(TAG, "create TextToSpeech failed", e)
            ready = false
            statusMessage = str(R.string.tts_create_failed, e.message ?: "")
            mainHandler.post {
                listener?.onError(statusMessage)
                notifyState()
            }
        }
    }

    /** 失败后允许重新初始化 */
    fun reinit() {
        shutdownInternal(keepDocument = true)
        initAttempted = false
        init()
    }

    override fun onInit(status: Int) {
        Log.i(TAG, "onInit status=$status engines=${tts?.defaultEngine}")
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            statusMessage = str(R.string.tts_init_failed_code, status)
            listener?.onError(statusMessage)
            notifyState()
            return
        }
        val engine = tts
        if (engine == null) {
            ready = false
            statusMessage = str(R.string.tts_engine_null)
            notifyState()
            return
        }

        // 语言：中文优先，失败则默认 locale，再失败也不阻塞 ready（部分引擎 setLanguage 返回 -2 仍可说）
        val langOk = setPreferredLanguage(engine)
        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)
        preferredVoiceName?.let { applyVoiceByName(it) }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                state = State.SPEAKING
                notifyState()
                listener?.onParagraphHighlight(paraIndex)
            }

            override fun onDone(utteranceId: String?) {
                // 回调可能在子线程
                mainHandler.post {
                    if (state != State.SPEAKING) return@post
                    advanceAndSpeak()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    state = State.IDLE
                    statusMessage = str(R.string.tts_status_error)
                    notifyState()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post {
                    state = State.IDLE
                    statusMessage = str(R.string.tts_status_error_code, errorCode)
                    notifyState()
                }
            }
        })

        ready = true
        statusMessage = if (langOk) str(R.string.tts_ready) else str(R.string.tts_ready_no_zh)
        Log.i(TAG, statusMessage + " voice=${engine.voice?.name} lang=${engine.language}")
        notifyState()

        pendingPlay?.let { (p, s) ->
            pendingPlay = null
            playFrom(p, s)
        }
    }

    private fun setPreferredLanguage(engine: TextToSpeech): Boolean {
        val candidates = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.getDefault(),
            Locale.US,
        )
        for (locale in candidates) {
            val r = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            Log.i(TAG, "setLanguage $locale -> $r")
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                return locale.language.startsWith("zh") || locale.language.isEmpty()
            }
        }
        return false
    }

    fun setDocument(paragraphs: List<Paragraph>) {
        stop()
        this.paragraphs = paragraphs
        this.sentences = paragraphs.map { TextLoader.splitSentences(it.text) }
        paraIndex = 0
        sentIndex = 0
    }

    /**
     * @param restartCurrent 为 true 且正在朗读/暂停时，立即用新语速重读当前句
     */
    fun setSpeechRate(rate: Float, restartCurrent: Boolean = false) {
        speechRate = rate.coerceIn(0.5f, 2.5f)
        tts?.setSpeechRate(speechRate)
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
        if (voice != null) {
            runCatching { tts?.voice = voice }
        }
    }

    fun applyVoiceByName(name: String?): Boolean {
        preferredVoiceName = name
        if (name.isNullOrBlank()) return false
        val voice = getVoices().firstOrNull { it.name == name } ?: return false
        runCatching { tts?.voice = voice }
        return true
    }

    fun currentVoiceName(): String? = tts?.voice?.name ?: preferredVoiceName

    fun isReady(): Boolean = ready

    fun playFrom(paragraphIndex: Int, sentenceIndex: Int = 0) {
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
        tts?.stop()
        state = State.IDLE
        if (ready) statusMessage = str(R.string.tts_status_idle)
        notifyState()
    }

    fun nextSentence() {
        if (paragraphs.isEmpty()) return
        val wasPlaying = state == State.SPEAKING || state == State.PAUSED
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
            listener?.onParagraphHighlight(paraIndex)
            return
        }
        listener?.onParagraphHighlight(paraIndex)
        if (wasPlaying || state == State.IDLE) {
            state = State.SPEAKING
            speakCurrent()
        } else {
            notifyState()
        }
    }

    fun previousSentence() {
        if (paragraphs.isEmpty()) return
        val wasPlaying = state == State.SPEAKING || state == State.PAUSED
        tts?.stop()
        if (sentIndex > 0) {
            sentIndex--
        } else if (paraIndex > 0) {
            paraIndex--
            val prev = sentences.getOrNull(paraIndex).orEmpty()
            sentIndex = (prev.size - 1).coerceAtLeast(0)
        }
        listener?.onParagraphHighlight(paraIndex)
        if (wasPlaying) {
            state = State.SPEAKING
            speakCurrent()
        } else {
            notifyState()
        }
    }

    fun jumpToParagraph(index: Int, autoPlay: Boolean = true) {
        if (paragraphs.isEmpty()) return
        tts?.stop()
        paraIndex = index.coerceIn(0, paragraphs.lastIndex)
        sentIndex = 0
        listener?.onParagraphHighlight(paraIndex)
        if (autoPlay) {
            state = State.SPEAKING
            speakCurrent()
        } else {
            state = State.IDLE
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
        if (!keepDocument) {
            paragraphs = emptyList()
            sentences = emptyList()
        }
    }

    private fun advanceAndSpeak() {
        val sents = sentences.getOrNull(paraIndex).orEmpty()
        if (sentIndex < sents.lastIndex) {
            sentIndex++
            speakCurrent()
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
                state = State.IDLE
                statusMessage = str(R.string.tts_status_ended)
                notifyState()
                return
            }
            speakCurrent()
            return
        }
        state = State.IDLE
        statusMessage = str(R.string.tts_status_ended)
        notifyState()
    }

    private fun speakCurrent() {
        val engine = tts
        if (engine == null || !ready) {
            listener?.onError(str(R.string.tts_not_ready))
            return
        }
        val sents = sentences.getOrNull(paraIndex).orEmpty()
        if (sents.isEmpty()) {
            advanceAndSpeak()
            return
        }
        if (sentIndex > sents.lastIndex) sentIndex = 0
        val text = sents[sentIndex]
        if (text.isBlank()) {
            advanceAndSpeak()
            return
        }
        listener?.onParagraphHighlight(paraIndex)
        val params = Bundle()
        val id = "p${paraIndex}_s${sentIndex}_${System.nanoTime()}"
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (result == TextToSpeech.ERROR) {
            state = State.IDLE
            statusMessage = str(R.string.tts_speak_failed)
            listener?.onError(str(R.string.tts_status_fail))
            notifyState()
            return
        }
        state = State.SPEAKING
        statusMessage = str(R.string.tts_status_speaking)
        notifyState()
    }

    private fun notifyState() {
        listener?.onStateChanged(Snapshot(state, paraIndex, sentIndex, ready, statusMessage))
    }

    companion object {
        private const val TAG = "WhjTts"
    }
}
