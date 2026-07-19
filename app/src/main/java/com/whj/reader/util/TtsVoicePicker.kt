package com.whj.reader.util

import android.app.Activity
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.whj.reader.R
import com.whj.reader.tts.TtsManager

/**
 * 朗读设置：引擎 / 语言 / 发音人 三级下拉。
 */
object TtsVoicePicker {

    fun show(
        activity: Activity,
        tts: TtsManager,
        onApplied: (() -> Unit)? = null,
    ) {
        if (activity.isFinishing) return
        if (!tts.isReady()) {
            tts.reinit()
            Toasts.show(activity, R.string.tts_not_ready)
            return
        }

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_tts_voice_picker, null)
        val spEngine = view.findViewById<Spinner>(R.id.spTtsEngine)
        val spLanguage = view.findViewById<Spinner>(R.id.spTtsLanguage)
        val spVoice = view.findViewById<Spinner>(R.id.spTtsVoice)
        val tvHint = view.findViewById<TextView>(R.id.tvTtsPickerHint)

        val engines = tts.listEngines()
        val engineLabels = engines.map { it.label }
        spEngine.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            engineLabels,
        )

        // 当前引擎选中：有用户偏好则匹配偏好，否则「自动」
        val preferredPkg = tts.preferredEnginePackage()
        val engineIndex = when {
            preferredPkg.isNullOrBlank() -> 0
            else -> engines.indexOfFirst { !it.isAuto && it.packageName == preferredPkg }
                .takeIf { it >= 0 } ?: 0
        }
        spEngine.setSelection(engineIndex, false)

        var langKeys: List<String> = emptyList()
        var voicesForLang: List<Voice> = emptyList()
        var suppressEngineCallback = false
        var suppressLangCallback = false

        fun setHint(text: String?, visible: Boolean = !text.isNullOrBlank()) {
            if (text.isNullOrBlank()) {
                tvHint.visibility = View.GONE
                tvHint.text = ""
            } else {
                tvHint.visibility = if (visible) View.VISIBLE else View.GONE
                tvHint.text = text
            }
        }

        fun voiceLabel(v: Voice): String {
            val quality = when {
                v.quality >= 400 -> activity.getString(R.string.tts_quality_high)
                v.quality >= 300 -> activity.getString(R.string.tts_quality_mid)
                else -> activity.getString(R.string.tts_quality_low)
            }
            val net = if (v.isNetworkConnectionRequired) {
                " · ${activity.getString(R.string.tts_voice_network)}"
            } else {
                ""
            }
            return "${v.name}（$quality$net）"
        }

        fun fillVoices(langKey: String?) {
            val byLang = tts.voicesByLanguage()
            val list = if (langKey.isNullOrBlank()) {
                emptyList()
            } else {
                byLang[langKey].orEmpty().sortedBy { it.name }
            }
            voicesForLang = list
            if (list.isEmpty()) {
                spVoice.adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(activity.getString(R.string.tts_voice_none)),
                )
                spVoice.isEnabled = false
            } else {
                spVoice.adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    list.map { voiceLabel(it) },
                )
                spVoice.isEnabled = true
                val current = tts.currentVoiceName()
                val idx = list.indexOfFirst { it.name == current }.coerceAtLeast(0)
                spVoice.setSelection(idx, false)
            }
        }

        fun fillLanguages(preferLangKey: String? = tts.preferredLanguageKey()) {
            val byLang = tts.voicesByLanguage()
            langKeys = VoiceLocaleOrder.sortLangKeys(byLang.keys)
            suppressLangCallback = true
            if (langKeys.isEmpty()) {
                spLanguage.adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(activity.getString(R.string.tts_voice_none)),
                )
                spLanguage.isEnabled = false
                fillVoices(null)
                setHint(activity.getString(R.string.tts_no_voices))
            } else {
                val labels = langKeys.map { key ->
                    val sample = byLang[key]!!.first().locale
                    val display = tts.localeDisplayName(sample)
                    "$display · ${activity.getString(R.string.tts_voice_count, byLang[key]!!.size)}"
                }
                spLanguage.adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    labels,
                )
                spLanguage.isEnabled = true
                val currentVoice = tts.currentVoiceName()
                val idxByVoice = langKeys.indexOfFirst { key ->
                    byLang[key]!!.any { it.name == currentVoice }
                }
                val idxByPref = preferLangKey?.let { langKeys.indexOf(it) } ?: -1
                val idx = when {
                    idxByVoice >= 0 -> idxByVoice
                    idxByPref >= 0 -> idxByPref
                    else -> 0
                }
                spLanguage.setSelection(idx, false)
                fillVoices(langKeys.getOrNull(idx))
                setHint(null)
            }
            suppressLangCallback = false
        }

        fillLanguages()

        spLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (suppressLangCallback || langKeys.isEmpty()) return
                val key = langKeys.getOrNull(position) ?: return
                tts.setPreferredLanguageKey(key)
                fillVoices(key)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (suppressEngineCallback) return
                val info = engines.getOrNull(position) ?: return
                val target = if (info.isAuto) null else info.packageName
                val currentPref = tts.preferredEnginePackage()
                // 未变化则不切换
                if (target == currentPref || (target.isNullOrBlank() && currentPref.isNullOrBlank())) {
                    return
                }
                setHint(activity.getString(R.string.tts_engine_switching))
                spLanguage.isEnabled = false
                spVoice.isEnabled = false
                spEngine.isEnabled = false
                tts.switchEngine(target) { ok ->
                    if (activity.isFinishing) return@switchEngine
                    spEngine.isEnabled = true
                    if (ok) {
                        Toasts.show(activity, R.string.tts_engine_switch_ok)
                        fillLanguages()
                    } else {
                        setHint(activity.getString(R.string.tts_engine_switch_fail))
                        Toasts.show(activity, R.string.tts_engine_switch_fail)
                        // 回退选中到实际偏好
                        suppressEngineCallback = true
                        val pref = tts.preferredEnginePackage()
                        val back = when {
                            pref.isNullOrBlank() -> 0
                            else -> engines.indexOfFirst { !it.isAuto && it.packageName == pref }
                                .takeIf { it >= 0 } ?: 0
                        }
                        spEngine.setSelection(back, false)
                        suppressEngineCallback = false
                        if (tts.isReady()) fillLanguages()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.tts_voice_picker_title)
            .setView(view)
            .setPositiveButton(R.string.confirm) { d, _ ->
                if (tts.isReady() && voicesForLang.isNotEmpty() && spVoice.isEnabled) {
                    val idx = spVoice.selectedItemPosition
                    val voice = voicesForLang.getOrNull(idx)
                    if (voice != null) {
                        tts.setVoice(voice)
                        Toasts.show(activity, voice.name)
                        onApplied?.invoke()
                    }
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.tts_open_settings) { _, _ ->
                runCatching { activity.startActivity(tts.openTtsSettingsIntent()) }
            }
            .create()
        dialog.show()
    }
}
