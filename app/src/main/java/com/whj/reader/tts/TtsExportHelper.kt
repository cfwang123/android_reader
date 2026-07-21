package com.whj.reader.tts

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.whj.reader.data.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 长文 TTS 分段 synthesizeToFile → 合并 WAV → 可选编码 M4A。
 *
 * 部分 OEM 引擎不回调 onDone，因此用「文件大小稳定」轮询 + 超时兜底。
 */
class TtsExportHelper(private val context: Context) {

    enum class Format { MP3, M4A, WAV }

    interface Listener {
        /**
         * @param done 已完成段数（synth 时为已完成段；当前段合成中仍为已完成数）
         * @param total 总段数
         * @param phase prepare / synth / merge / encode
         * @param doneChars 已合成字数（含当前段估算）
         * @param totalChars 全文总字数
         * @param partFraction 当前段内进度 0..1（仅 synth 有效）
         */
        fun onProgress(
            done: Int,
            total: Int,
            phase: String,
            doneChars: Int = 0,
            totalChars: Int = 0,
            partFraction: Float = 0f,
        )
        fun onSuccess(file: File)
        fun onError(message: String)
        fun onCancelled()
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private val cancelled = AtomicBoolean(false)
    private var working = false
    private var partWatchRunnable: Runnable? = null
    private var initTimeoutRunnable: Runnable? = null

    fun isWorking(): Boolean = working

    fun cancel() {
        if (!working && !cancelled.get()) return
        cancelled.set(true)
        clearPartWatch()
        main.removeCallbacksAndMessages(null)
        runCatching { tts?.stop() }
        // 确保 UI 收到取消（合成卡在等待时也要退出）
        main.post {
            if (working) finishCancelled()
        }
    }

    fun shutdown() {
        cancel()
        releaseEngine()
        working = false
    }

    /**
     * @param bitRateKbps AAC 码率（仅 M4A 有效），如 32/64/96/128
     */
    fun export(
        text: String,
        format: Format,
        filePrefix: String = "tts",
        bitRateKbps: Int = 64,
        listener: Listener,
    ) {
        if (working) {
            listener.onError("busy")
            return
        }
        val body = text.trim()
        if (body.isEmpty()) {
            listener.onError("empty")
            return
        }
        working = true
        cancelled.set(false)
        listenerRef = listener
        outFormat = format
        prefix = filePrefix
        aacBitRate = bitRateKbps.coerceIn(16, 320) * 1000

        // 必须在主线程创建/回调 TTS
        main.post {
            if (cancelled.get()) {
                finishCancelled()
                return@post
            }
            bindEngine(body)
        }
    }

    private var chunks: List<String> = emptyList()
    private var partFiles: MutableList<File> = mutableListOf()
    private var workDir: File? = null
    private var chunkIndex = 0
    private var outFormat: Format = Format.M4A
    private var prefix: String = "tts"
    private var aacBitRate: Int = 64_000
    private var listenerRef: Listener? = null
    private var waitingUtterance: String? = null
    private var waitingPart: File? = null
    private var advancing = false
    private var totalChars: Int = 0
    private var doneChars: Int = 0

    private fun reportProgress(
        done: Int,
        total: Int,
        phase: String,
        partFraction: Float = 0f,
        softDoneChars: Int = -1,
    ) {
        val chars = if (softDoneChars >= 0) softDoneChars else doneChars
        // 始终主线程回调，避免 UI 不刷新
        main.post {
            if (!working && phase != "encode" && phase != "merge") return@post
            listenerRef?.onProgress(
                done,
                total,
                phase,
                chars.coerceIn(0, totalChars.coerceAtLeast(chars)),
                totalChars,
                partFraction.coerceIn(0f, 1f),
            )
        }
    }

    /** 当前段合成中的软进度（字数 = 已完成 + 当前段×fraction） */
    private fun reportSynthSoft(partFraction: Float) {
        val total = chunks.size.coerceAtLeast(1)
        val i = chunkIndex.coerceIn(0, total)
        val curLen = chunks.getOrNull(i)?.length ?: 0
        val soft = (doneChars + (curLen * partFraction.coerceIn(0f, 0.99f))).toInt()
            .coerceAtMost(totalChars.coerceAtLeast(1))
        reportProgress(i, total, "synth", partFraction, soft)
    }

    private fun bindEngine(body: String) {
        totalChars = body.length
        doneChars = 0
        reportProgress(0, 1, "prepare")
        val enginePkg = AppSettings.ttsEnginePackage(context)?.takeIf { it.isNotBlank() }
        val app = context.applicationContext
        val initListener = TextToSpeech.OnInitListener { status ->
            main.post {
                if (cancelled.get()) {
                    finishCancelled()
                    return@post
                }
                main.removeCallbacks(initTimeoutRunnable ?: Runnable {})
                if (status != TextToSpeech.SUCCESS) {
                    finishError("tts init failed ($status)")
                    return@post
                }
                val engine = tts
                if (engine == null) {
                    finishError("tts null")
                    return@post
                }
                applyVoiceSettings(engine)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.i(TAG, "onStart id=$utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.i(TAG, "onDone id=$utteranceId wait=$waitingUtterance")
                        main.post { tryAdvanceFromCallback(utteranceId) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { failCurrent("utterance error id=$utteranceId") }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        main.post { failCurrent("error=$errorCode id=$utteranceId") }
                    }
                })
                // 部分引擎 onInit 后立刻 synthesize 会挂起，稍延迟
                main.postDelayed({
                    if (cancelled.get()) {
                        finishCancelled()
                        return@postDelayed
                    }
                    startChunks(body)
                }, 350L)
            }
        }

        // 绑定超时
        initTimeoutRunnable = Runnable {
            if (working && chunks.isEmpty() && partFiles.isEmpty()) {
                Log.e(TAG, "init timeout")
                finishError("tts init timeout")
            }
        }
        main.postDelayed(initTimeoutRunnable!!, 12_000L)

        runCatching {
            tts = if (enginePkg != null) {
                TextToSpeech(app, initListener, enginePkg)
            } else {
                TextToSpeech(app, initListener)
            }
        }.onFailure { t ->
            Log.e(TAG, "create tts", t)
            finishError(t.message ?: "create tts failed")
        }
    }

    private fun startChunks(body: String) {
        chunks = chunkText(body, MAX_CHUNK)
        totalChars = body.length
        doneChars = 0
        Log.i(TAG, "chunks=${chunks.size} totalChars=${body.length}")
        if (chunks.isEmpty()) {
            finishError("empty chunks")
            return
        }
        workDir = File(context.cacheDir, "tts_export_${System.currentTimeMillis()}").also {
            it.mkdirs()
        }
        partFiles = mutableListOf()
        chunkIndex = 0
        reportProgress(0, chunks.size, "synth")
        synthesizeNext()
    }

    private fun synthesizeNext() {
        if (cancelled.get()) {
            finishCancelled()
            return
        }
        val engine = tts
        val dir = workDir
        val listener = listenerRef
        if (engine == null || dir == null || listener == null) {
            finishError("state lost")
            return
        }
        if (chunkIndex >= chunks.size) {
            mergeAndFinish()
            return
        }
        val total = chunks.size
        val i = chunkIndex
        val text = chunks[i]
        reportProgress(i, total, "synth")
        val part = File(dir, String.format(Locale.US, "part_%03d.wav", i))
        if (part.exists()) part.delete()
        val id = "exp_${i}_${System.nanoTime()}"
        waitingUtterance = id
        waitingPart = part
        advancing = false
        Log.i(TAG, "synthesize i=$i/${total - 1} len=${text.length} id=$id")

        val params = Bundle()
        // 部分引擎读 Bundle 里的 utterance id
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        val code = try {
            engine.synthesizeToFile(text, params, part, id)
        } catch (t: Throwable) {
            Log.e(TAG, "synthesizeToFile throw", t)
            failCurrent(t.message ?: "synthesize throw")
            return
        }
        if (code != TextToSpeech.SUCCESS) {
            failCurrent("synthesizeToFile code=$code")
            return
        }
        // onDone 不可靠：轮询文件写完
        startPartWatch(part, id, text.length)
    }

    private fun startPartWatch(part: File, id: String, charCount: Int) {
        clearPartWatch()
        val startAt = SystemClock.uptimeMillis()
        // 按时长粗估：~4 字/秒 + 余量，最少 20s，最多 180s
        val timeoutMs = ((charCount / 3.5f) * 1000f + 15_000f).toLong().coerceIn(20_000L, 180_000L)
        // 段内进度时间尺度：约 4 字/秒，上限 timeout 的 85%
        val expectMs = ((charCount / 4f) * 1000f).toLong().coerceIn(2_000L, (timeoutMs * 0.85f).toLong())
        var lastSize = -1L
        var stableTicks = 0
        var peakSize = 0L
        val watch = object : Runnable {
            override fun run() {
                if (waitingUtterance != id || cancelled.get()) return
                val elapsed = SystemClock.uptimeMillis() - startAt
                // 段内软进度：时间推进 + 文件增大
                var frac = (elapsed.toFloat() / expectMs).coerceIn(0f, 0.92f)
                if (part.exists()) {
                    val sz = part.length()
                    if (sz > peakSize) peakSize = sz
                    // 文件在写：按体积粗估（WAV 头后每秒约 32k～176k 字节，用相对增长）
                    if (peakSize > 44L) {
                        val bySize = ((peakSize - 44L).toFloat() / (charCount * 200f + 1f))
                            .coerceIn(0f, 0.95f)
                        frac = maxOf(frac, bySize)
                    }
                    if (sz >= 44L) {
                        if (sz == lastSize && sz > 44L) {
                            stableTicks++
                            if (stableTicks >= 2) {
                                Log.i(TAG, "part ready by poll size=$sz id=$id")
                                tryAdvanceFromFile(id)
                                return
                            }
                        } else {
                            stableTicks = 0
                            lastSize = sz
                        }
                    }
                }
                reportSynthSoft(frac)
                if (elapsed > timeoutMs) {
                    Log.e(TAG, "part timeout id=$id exists=${part.exists()} size=${part.length()}")
                    failCurrent("timeout part $chunkIndex")
                    return
                }
                main.postDelayed(this, 300L)
            }
        }
        partWatchRunnable = watch
        // 立刻刷一次进度，避免首段长时间 0%
        reportSynthSoft(0.02f)
        main.postDelayed(watch, 300L)
    }

    private fun clearPartWatch() {
        partWatchRunnable?.let { main.removeCallbacks(it) }
        partWatchRunnable = null
    }

    private fun tryAdvanceFromCallback(utteranceId: String?) {
        if (advancing) return
        val wait = waitingUtterance
        val part = waitingPart
        if (wait == null || part == null) return
        // id 不一致时，若文件已就绪仍推进（OEM 回调 id 异常）
        if (utteranceId != null && utteranceId != wait) {
            if (!(part.exists() && part.length() >= 44L)) {
                Log.w(TAG, "ignore done id=$utteranceId wait=$wait")
                return
            }
        }
        tryAdvanceFromFile(wait)
    }

    private fun tryAdvanceFromFile(expectedId: String) {
        if (advancing) return
        if (waitingUtterance != expectedId) return
        val part = waitingPart ?: return
        if (!part.exists() || part.length() < 44L) {
            // 再等一会儿，由 poll 处理
            return
        }
        advancing = true
        clearPartWatch()
        waitingUtterance = null
        waitingPart = null
        if (cancelled.get()) {
            finishCancelled()
            return
        }
        // 本段算完成
        doneChars = (doneChars + (chunks.getOrNull(chunkIndex)?.length ?: 0)).coerceAtMost(totalChars)
        partFiles.add(part)
        chunkIndex++
        reportProgress(chunkIndex, chunks.size, "synth")
        // 下一段略延迟，避免引擎队列忙
        main.postDelayed({
            advancing = false
            if (cancelled.get()) {
                finishCancelled()
            } else {
                synthesizeNext()
            }
        }, 120L)
    }

    private fun mergeAndFinish() {
        val listener = listenerRef ?: return
        val dir = workDir ?: return
        clearPartWatch()
        try {
            if (cancelled.get()) {
                finishCancelled()
                return
            }
            if (partFiles.isEmpty()) {
                finishError("no audio parts")
                return
            }
            doneChars = totalChars
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val outDir = File(context.getExternalFilesDir(null), "tts_export").also { it.mkdirs() }
            val merged = File(dir, "merged.wav")
            reportProgress(chunks.size, chunks.size, "merge")
            WavMerger.merge(partFiles, merged)
            val kbps = (aacBitRate / 1000).coerceIn(16, 320)
            val finalFile = when (outFormat) {
                Format.WAV -> {
                    val dest = File(outDir, "${prefix}_$stamp.wav")
                    merged.copyTo(dest, overwrite = true)
                    dest
                }
                Format.MP3 -> {
                    reportProgress(chunks.size, chunks.size, "encode")
                    encodePreferMp3(merged, outDir, prefix, stamp, kbps)
                }
                Format.M4A -> {
                    reportProgress(chunks.size, chunks.size, "encode")
                    encodePreferM4a(merged, outDir, prefix, stamp, aacBitRate)
                }
            }
            cleanupTemp()
            working = false
            val okListener = listener
            releaseEngine()
            okListener.onSuccess(finalFile)
        } catch (t: Throwable) {
            Log.e(TAG, "merge", t)
            finishError(t.message ?: "merge failed")
        }
    }

    /**
     * 优先 MP3（arm64 LAME）；不可用或失败则 M4A，再失败则 WAV。
     */
    private fun encodePreferMp3(
        merged: File,
        outDir: File,
        prefix: String,
        stamp: String,
        kbps: Int,
    ): File {
        if (Mp3Encoder.isAvailable()) {
            try {
                val dest = File(outDir, "${prefix}_$stamp.mp3")
                Mp3Encoder.wavToMp3(merged, dest, bitRateKbps = kbps)
                if (dest.exists() && dest.length() > 0) {
                    Log.i(TAG, "encoded mp3 ${dest.length()} bytes")
                    return dest
                }
            } catch (t: Throwable) {
                Log.e(TAG, "mp3 encode failed, fallback m4a", t)
            }
        } else {
            Log.i(TAG, "mp3 not available on this device, fallback m4a")
        }
        return encodePreferM4a(merged, outDir, prefix, stamp, kbps * 1000)
    }

    private fun encodePreferM4a(
        merged: File,
        outDir: File,
        prefix: String,
        stamp: String,
        bitRate: Int,
    ): File {
        try {
            val dest = File(outDir, "${prefix}_$stamp.m4a")
            AacEncoder.wavToM4a(merged, dest, bitRate = bitRate)
            if (dest.exists() && dest.length() > 0) return dest
        } catch (t: Throwable) {
            Log.e(TAG, "aac encode", t)
        }
        val wavDest = File(outDir, "${prefix}_$stamp.wav")
        merged.copyTo(wavDest, overwrite = true)
        return wavDest
    }

    private fun failCurrent(msg: String) {
        Log.e(TAG, "fail: $msg")
        clearPartWatch()
        val listener = listenerRef
        cleanupTemp()
        working = false
        releaseEngine()
        listener?.onError(msg)
    }

    private fun finishError(msg: String) {
        clearPartWatch()
        val listener = listenerRef
        cleanupTemp()
        working = false
        releaseEngine()
        listener?.onError(msg)
    }

    private fun finishCancelled() {
        clearPartWatch()
        val listener = listenerRef
        cleanupTemp()
        working = false
        releaseEngine()
        listener?.onCancelled()
    }

    private fun cleanupTemp() {
        workDir?.deleteRecursively()
        workDir = null
        partFiles.clear()
    }

    private fun releaseEngine() {
        runCatching {
            tts?.setOnUtteranceProgressListener(null)
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        waitingUtterance = null
        waitingPart = null
        listenerRef = null
        initTimeoutRunnable = null
    }

    private fun applyVoiceSettings(engine: TextToSpeech) {
        val rate = AppSettings.ttsRate(context).coerceIn(0.5f, 2.5f)
        val pitch = AppSettings.ttsPitch(context).coerceIn(0.5f, 2.0f)
        runCatching { engine.setSpeechRate(rate) }
        runCatching { engine.setPitch(pitch) }
        val voiceName = AppSettings.voiceName(context)
        if (!voiceName.isNullOrBlank()) {
            val v = runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == voiceName }
            if (v != null) {
                runCatching {
                    engine.language = v.locale
                    engine.voice = v
                }
            }
        } else {
            val langKey = AppSettings.ttsLanguageKey(context)
            if (!langKey.isNullOrBlank()) {
                val parts = langKey.split('_', '-')
                val loc = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
                runCatching { engine.language = loc }
            } else {
                runCatching { engine.language = Locale.CHINA }
            }
        }
    }

    companion object {
        private const val TAG = "TtsExport"
        /** 单段不宜过长：部分引擎长文 synthesizeToFile 会挂起 */
        private const val MAX_CHUNK = 900

        fun chunkText(text: String, maxLen: Int): List<String> {
            if (text.length <= maxLen) return listOf(text)
            val out = ArrayList<String>()
            var i = 0
            while (i < text.length) {
                var end = (i + maxLen).coerceAtMost(text.length)
                if (end < text.length) {
                    val slice = text.substring(i, end)
                    val br = maxOf(
                        slice.lastIndexOf('。'),
                        slice.lastIndexOf('！'),
                        slice.lastIndexOf('？'),
                        slice.lastIndexOf('\n'),
                        slice.lastIndexOf('.'),
                        slice.lastIndexOf('；'),
                        slice.lastIndexOf('，'),
                        slice.lastIndexOf(' '),
                    )
                    if (br > maxLen / 5) end = i + br + 1
                }
                val piece = text.substring(i, end).trim()
                if (piece.isNotEmpty()) out.add(piece)
                i = end.coerceAtLeast(i + 1)
            }
            return out
        }
    }
}
