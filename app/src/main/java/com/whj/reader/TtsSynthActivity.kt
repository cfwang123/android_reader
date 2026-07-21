package com.whj.reader

import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.whj.reader.ui.AppTheme
import androidx.core.content.FileProvider
import com.whj.reader.data.AppSettings
import com.whj.reader.databinding.ActivityTtsSynthBinding
import com.whj.reader.util.Toasts
import com.whj.reader.util.VoiceLocaleOrder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文本转语音：输入文本 → 选引擎/发音人 → 播放或导出音频文件。
 */
class TtsSynthActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityTtsSynthBinding
    private var tts: TextToSpeech? = null
    private var ready = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var initGen = 0

    private data class EngineItem(val packageName: String?, val label: String)

    private var engines: List<EngineItem> = emptyList()
    private var langKeys: List<String> = emptyList()
    private var voicesForLang: List<Voice> = emptyList()
    private var suppressEngine = false
    private var suppressLang = false

    private var speechRate = 1.0f
    private var pitch = 1.0f
    private var preferredEngine: String? = null
    private var preferredVoice: String? = null
    private var preferredLang: String? = null

    private var exportCallback: ((Boolean, String?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityTtsSynthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferredEngine = AppSettings.ttsEnginePackage(this)
        preferredVoice = AppSettings.voiceName(this)
        preferredLang = AppSettings.ttsLanguageKey(this)
        speechRate = AppSettings.ttsRate(this).coerceIn(0.5f, 2.5f)
        pitch = AppSettings.ttsPitch(this).coerceIn(0.5f, 2.0f)

        binding.btnBack.setOnClickListener { finish() }
        setupSeekBars()
        setupSpinners()
        setupInputScroll()
        binding.btnPlay.setOnClickListener { play() }
        binding.btnStop.setOnClickListener { stopPlayback() }
        binding.btnExport.setOnClickListener { exportFile() }

        setStatus(getString(R.string.tts_init_pending))
        bindEngine(preferredEngine)
    }

    /** 输入框固定高度内竖直滚动 + 滚动条；避免被外层 ScrollView 抢手势 */
    private fun setupInputScroll() {
        val et = binding.etSynthText
        et.isVerticalScrollBarEnabled = true
        et.setOnTouchListener { v, event ->
            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
    }

    override fun onDestroy() {
        exportCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        super.onDestroy()
    }

    private fun setupSeekBars() {
        // 0.5~2.5 → progress 0~200, center 1.0 = 50
        fun rateToProgress(r: Float) = ((r - 0.5f) / 2.0f * 200f).toInt().coerceIn(0, 200)
        fun progressToRate(p: Int) = 0.5f + p / 200f * 2.0f
        fun pitchToProgress(p: Float) = ((p - 0.5f) / 1.5f * 200f).toInt().coerceIn(0, 200)
        fun progressToPitch(p: Int) = 0.5f + p / 200f * 1.5f

        binding.seekRate.progress = rateToProgress(speechRate)
        binding.seekPitch.progress = pitchToProgress(pitch)
        updateRatePitchLabels()

        binding.seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                speechRate = progressToRate(progress)
                updateRatePitchLabels()
                if (ready) runCatching { tts?.setSpeechRate(speechRate) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                AppSettings.setTtsRate(this@TtsSynthActivity, speechRate)
            }
        })
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                pitch = progressToPitch(progress)
                updateRatePitchLabels()
                if (ready) runCatching { tts?.setPitch(pitch) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                AppSettings.setTtsPitch(this@TtsSynthActivity, pitch)
            }
        })
    }

    private fun updateRatePitchLabels() {
        binding.tvRateLabel.text = getString(R.string.tts_synth_rate_fmt, speechRate)
        binding.tvPitchLabel.text = getString(R.string.tts_synth_pitch_fmt, pitch)
    }

    private fun setupSpinners() {
        engines = listEngines()
        binding.spEngine.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            engines.map { it.label },
        )
        val engIdx = when {
            preferredEngine.isNullOrBlank() -> 0
            else -> engines.indexOfFirst { it.packageName == preferredEngine }
                .takeIf { it >= 0 } ?: 0
        }
        suppressEngine = true
        binding.spEngine.setSelection(engIdx, false)
        suppressEngine = false

        binding.spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressEngine) return
                val item = engines.getOrNull(position) ?: return
                val target = item.packageName
                if (target == preferredEngine ||
                    (target.isNullOrBlank() && preferredEngine.isNullOrBlank())
                ) {
                    return
                }
                preferredEngine = target
                AppSettings.setTtsEnginePackage(this@TtsSynthActivity, target)
                setStatus(getString(R.string.tts_engine_switching))
                bindEngine(target)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.spLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressLang || langKeys.isEmpty()) return
                val key = langKeys.getOrNull(position) ?: return
                preferredLang = key
                AppSettings.setTtsLanguageKey(this@TtsSynthActivity, key)
                fillVoices(key)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun listEngines(): List<EngineItem> {
        val result = ArrayList<EngineItem>()
        result.add(EngineItem(null, getString(R.string.tts_engine_auto)))
        val pm = packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val services = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentServices(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
        }.getOrDefault(emptyList())
        val seen = LinkedHashSet<String>()
        for (ri in services) {
            val pkg = ri.serviceInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: pkg
            result.add(EngineItem(pkg, label))
        }
        return result
    }

    private fun bindEngine(packageName: String?) {
        ready = false
        initGen++
        val gen = initGen
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        val pkg = packageName?.takeIf { it.isNotBlank() }
            ?: AppSettings.ttsLastEnginePackage(this)
        tts = try {
            if (pkg.isNullOrBlank()) {
                TextToSpeech(this, this)
            } else {
                TextToSpeech(this, this, pkg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "create TTS failed", e)
            setStatus(getString(R.string.tts_init_failed))
            return
        }
        mainHandler.postDelayed({
            if (gen != initGen || ready) return@postDelayed
            // 超时：若指定包失败，试默认
            if (!pkg.isNullOrBlank()) {
                Log.w(TAG, "init timeout, try default")
                bindEngine(null)
            } else {
                setStatus(getString(R.string.tts_init_failed))
            }
        }, 4000)
    }

    override fun onInit(status: Int) {
        mainHandler.post {
            if (status != TextToSpeech.SUCCESS) {
                ready = false
                setStatus(getString(R.string.tts_init_failed))
                return@post
            }
            val engine = tts ?: return@post
            initGen++ // cancel timeout
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
            engine.setSpeechRate(speechRate)
            engine.setPitch(pitch)
            runCatching {
                engine.defaultEngine?.takeIf { it.isNotBlank() }?.let {
                    AppSettings.setTtsLastEnginePackage(this, it)
                }
            }
            applyPreferredVoiceOrLang(engine)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainHandler.post {
                        if (utteranceId?.startsWith("export_") == true) {
                            setStatus(getString(R.string.tts_synth_exporting))
                        } else {
                            setStatus(getString(R.string.tts_synth_playing))
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        if (utteranceId?.startsWith("export_") == true) {
                            val cb = exportCallback
                            exportCallback = null
                            cb?.invoke(true, null)
                        } else {
                            setStatus(getString(R.string.tts_synth_ready))
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        if (utteranceId?.startsWith("export_") == true) {
                            val cb = exportCallback
                            exportCallback = null
                            cb?.invoke(false, "error")
                        } else {
                            setStatus(getString(R.string.tts_status_error))
                        }
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    mainHandler.post {
                        if (utteranceId?.startsWith("export_") == true) {
                            val cb = exportCallback
                            exportCallback = null
                            cb?.invoke(false, "code=$errorCode")
                        } else {
                            setStatus(getString(R.string.tts_status_error_code, errorCode))
                        }
                    }
                }
            })
            ready = true
            fillLanguages()
            setStatus(getString(R.string.tts_synth_ready))
        }
    }

    private fun applyPreferredVoiceOrLang(engine: TextToSpeech) {
        val voices = engine.voices?.toList().orEmpty()
        if (!preferredVoice.isNullOrBlank()) {
            val v = voices.firstOrNull { it.name == preferredVoice }
                ?: voices.firstOrNull { it.name.equals(preferredVoice, ignoreCase = true) }
            if (v != null) {
                runCatching {
                    engine.language = v.locale
                    engine.voice = v
                }
                preferredLang = localeKey(v.locale)
                return
            }
        }
        val locale = preferredLang?.let { localeFromKey(it) } ?: Locale.SIMPLIFIED_CHINESE
        runCatching { engine.setLanguage(locale) }
    }

    private fun fillLanguages() {
        val engine = tts ?: return
        val byLang = linkedMapOf<String, MutableList<Voice>>()
        engine.voices?.forEach { v ->
            val key = localeKey(v.locale)
            byLang.getOrPut(key) { mutableListOf() }.add(v)
        }
        langKeys = VoiceLocaleOrder.sortLangKeys(byLang.keys)
        suppressLang = true
        if (langKeys.isEmpty()) {
            binding.spLanguage.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.tts_voice_none)),
            )
            binding.spLanguage.isEnabled = false
            fillVoices(null)
        } else {
            val labels = langKeys.map { key ->
                val sample = byLang[key]!!.first().locale
                val display = sample.getDisplayName(Locale.SIMPLIFIED_CHINESE).ifBlank { key }
                "$display · ${getString(R.string.tts_voice_count, byLang[key]!!.size)}"
            }
            binding.spLanguage.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                labels,
            )
            binding.spLanguage.isEnabled = true
            val curVoice = tts?.voice?.name ?: preferredVoice
            val idxByVoice = langKeys.indexOfFirst { key ->
                byLang[key]!!.any { it.name == curVoice }
            }
            val idxByPref = preferredLang?.let { langKeys.indexOf(it) } ?: -1
            val idx = when {
                idxByVoice >= 0 -> idxByVoice
                idxByPref >= 0 -> idxByPref
                else -> 0
            }
            binding.spLanguage.setSelection(idx, false)
            fillVoices(langKeys.getOrNull(idx))
        }
        suppressLang = false
    }

    private fun fillVoices(langKey: String?) {
        val engine = tts
        val all = engine?.voices?.toList().orEmpty()
        val list = if (langKey.isNullOrBlank()) {
            emptyList()
        } else {
            all.filter { localeKey(it.locale) == langKey }.sortedBy { it.name }
        }
        voicesForLang = list
        if (list.isEmpty()) {
            binding.spVoice.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.tts_voice_none)),
            )
            binding.spVoice.isEnabled = false
            return
        }
        val labels = list.map { v ->
            val q = when {
                v.quality >= 400 -> getString(R.string.tts_quality_high)
                v.quality >= 300 -> getString(R.string.tts_quality_mid)
                else -> getString(R.string.tts_quality_low)
            }
            val net = if (v.isNetworkConnectionRequired) {
                " · ${getString(R.string.tts_voice_network)}"
            } else {
                ""
            }
            "${v.name}（$q$net）"
        }
        binding.spVoice.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        binding.spVoice.isEnabled = true
        val cur = engine?.voice?.name ?: preferredVoice
        val idx = list.indexOfFirst { it.name == cur }.coerceAtLeast(0)
        binding.spVoice.setSelection(idx, false)
        binding.spVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val v = voicesForLang.getOrNull(position) ?: return
                preferredVoice = v.name
                preferredLang = localeKey(v.locale)
                AppSettings.setVoiceName(this@TtsSynthActivity, v.name)
                AppSettings.setTtsLanguageKey(this@TtsSynthActivity, preferredLang)
                runCatching {
                    tts?.language = v.locale
                    tts?.voice = v
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        // apply current selection
        list.getOrNull(idx)?.let { v ->
            runCatching {
                engine?.language = v.locale
                engine?.voice = v
            }
        }
    }

    private fun currentText(): String =
        binding.etSynthText.text?.toString()?.trim().orEmpty()

    private fun play() {
        val text = currentText()
        if (text.isEmpty()) {
            Toasts.show(this, R.string.tts_synth_empty)
            return
        }
        if (!ready || tts == null) {
            Toasts.show(this, R.string.tts_synth_not_ready)
            return
        }
        // 应用当前 spinner 发音人
        voicesForLang.getOrNull(binding.spVoice.selectedItemPosition)?.let { v ->
            runCatching {
                tts?.language = v.locale
                tts?.voice = v
            }
        }
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(pitch)
        val id = "play_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        // 长文分段播放（QUEUE_ADD）
        val chunks = chunkText(text, 3500)
        tts?.speak(chunks.first(), TextToSpeech.QUEUE_FLUSH, params, id)
        for (i in 1 until chunks.size) {
            val cid = "${id}_$i"
            val p = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, cid)
            }
            tts?.speak(chunks[i], TextToSpeech.QUEUE_ADD, p, cid)
        }
        setStatus(getString(R.string.tts_synth_playing))
    }

    private fun stopPlayback() {
        runCatching { tts?.stop() }
        exportCallback = null
        setStatus(if (ready) getString(R.string.tts_synth_ready) else getString(R.string.tts_init_pending))
    }

    private fun exportFile() {
        val text = currentText()
        if (text.isEmpty()) {
            Toasts.show(this, R.string.tts_synth_empty)
            return
        }
        if (!ready || tts == null) {
            Toasts.show(this, R.string.tts_synth_not_ready)
            return
        }
        voicesForLang.getOrNull(binding.spVoice.selectedItemPosition)?.let { v ->
            runCatching {
                tts?.language = v.locale
                tts?.voice = v
            }
        }
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(pitch)

        val dir = File(getExternalFilesDir(null), "tts_export").also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val out = File(dir, "tts_$stamp.wav")
        val id = "export_${System.currentTimeMillis()}"
        setStatus(getString(R.string.tts_synth_exporting))
        exportCallback = { ok, err ->
            if (ok && out.exists() && out.length() > 0) {
                Toasts.show(
                    this,
                    getString(R.string.tts_synth_export_ok, out.name),
                    android.widget.Toast.LENGTH_LONG,
                )
                setStatus(getString(R.string.tts_synth_export_ok, out.absolutePath))
                shareAudio(out)
            } else {
                Toasts.show(
                    this,
                    getString(R.string.tts_synth_export_fail, err ?: "unknown"),
                    android.widget.Toast.LENGTH_LONG,
                )
                setStatus(getString(R.string.tts_synth_export_fail, err ?: "unknown"))
            }
        }
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        // 导出时合并为一段（过长引擎可能截断）
        val body = if (text.length > 3500) text.take(3500) else text
        val code = tts?.synthesizeToFile(body, params, out, id)
            ?: TextToSpeech.ERROR
        if (code != TextToSpeech.SUCCESS) {
            val cb = exportCallback
            exportCallback = null
            cb?.invoke(false, "code=$code")
        }
    }

    private fun shareAudio(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.tts_synth_share)))
        }
    }

    private fun chunkText(text: String, maxLen: Int): List<String> {
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
                    slice.lastIndexOf(' '),
                )
                if (br > maxLen / 3) end = i + br + 1
            }
            out.add(text.substring(i, end).trim())
            i = end
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun setStatus(msg: String) {
        binding.tvStatus.text = msg
    }

    private fun localeKey(locale: Locale): String {
        val lang = locale.language.ifBlank { "und" }
        val country = locale.country
        return if (country.isNullOrBlank()) lang else "${lang}_$country"
    }

    private fun localeFromKey(key: String): Locale {
        val parts = key.split('_', '-', limit = 3)
        return when (parts.size) {
            1 -> Locale(parts[0])
            2 -> Locale(parts[0], parts[1])
            else -> Locale(parts[0], parts[1], parts[2])
        }
    }

    companion object {
        private const val TAG = "TtsSynth"
    }
}
