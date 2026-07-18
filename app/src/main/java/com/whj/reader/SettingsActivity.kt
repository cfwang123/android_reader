package com.whj.reader

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.whj.reader.data.AppSettings
import com.whj.reader.data.LocaleHelper
import com.whj.reader.databinding.ActivitySettingsBinding
import com.whj.reader.model.AppLanguage
import com.whj.reader.model.EdgeSwipeAction
import com.whj.reader.util.Toasts

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    /**
     * Seek 0..24 → 分钟映射：
     * 0=不退出，1=5，2=10，3=15，4=20，5=25，6=30，… 每格 +5，最大 120 分钟(24)
     */
    private fun progressToMinutes(p: Int): Int =
        if (p <= 0) 0 else (p * 5).coerceAtMost(120)

    private fun minutesToProgress(m: Int): Int =
        when {
            m <= 0 -> 0
            else -> ((m + 4) / 5).coerceIn(1, 24)
        }

    private fun formatIdle(minutes: Int): String =
        if (minutes <= 0) getString(R.string.settings_idle_exit_never)
        else getString(R.string.settings_idle_exit_value, minutes)

    private fun edgeLabel(action: EdgeSwipeAction): String =
        when (action) {
            EdgeSwipeAction.RATE -> getString(R.string.edge_action_rate)
            EdgeSwipeAction.FONT -> getString(R.string.edge_action_font)
            EdgeSwipeAction.NONE -> getString(R.string.edge_action_none)
        }

    private fun languageLabel(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.ZH -> getString(R.string.lang_zh)
            AppLanguage.EN -> getString(R.string.lang_en)
        }

    private fun refreshEdgeLabels() {
        binding.tvLeftEdge.text = edgeLabel(AppSettings.leftEdgeAction(this))
        binding.tvRightEdge.text = edgeLabel(AppSettings.rightEdgeAction(this))
    }

    private fun refreshLanguageLabel() {
        binding.tvLanguage.text = languageLabel(AppSettings.appLanguage(this))
    }

    private fun pickEdgeAction(
        title: String,
        current: EdgeSwipeAction,
        onPicked: (EdgeSwipeAction) -> Unit,
    ) {
        val options = arrayOf(
            getString(R.string.edge_action_rate),
            getString(R.string.edge_action_font),
            getString(R.string.edge_action_none),
        )
        val values = arrayOf(
            EdgeSwipeAction.RATE,
            EdgeSwipeAction.FONT,
            EdgeSwipeAction.NONE,
        )
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                onPicked(values[which])
                refreshEdgeLabels()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickLanguage() {
        val values = arrayOf(AppLanguage.ZH, AppLanguage.EN)
        val options = arrayOf(getString(R.string.lang_zh), getString(R.string.lang_en))
        val current = AppSettings.appLanguage(this)
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val next = values[which]
                dialog.dismiss()
                if (next == current) return@setSingleChoiceItems
                AppSettings.setAppLanguage(this, next)
                // AppCompat 会按新 Locale 重建界面
                LocaleHelper.apply(next)
                Toasts.show(this, R.string.lang_switched)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        refreshLanguageLabel()
        binding.rowLanguage.setOnClickListener { pickLanguage() }

        binding.switchKeepScreenOn.isChecked = AppSettings.keepScreenOn(this)
        binding.switchAutoScroll.isChecked = AppSettings.autoScroll(this)
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            AppSettings.setKeepScreenOn(this, checked)
        }
        binding.switchAutoScroll.setOnCheckedChangeListener { _, checked ->
            AppSettings.setAutoScroll(this, checked)
        }

        val idleMin = AppSettings.idleExitMinutes(this)
        binding.seekIdleExit.progress = minutesToProgress(idleMin)
        binding.tvIdleExit.text = formatIdle(idleMin)
        binding.seekIdleExit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val m = progressToMinutes(progress)
                binding.tvIdleExit.text = formatIdle(m)
                if (fromUser) AppSettings.setIdleExitMinutes(this@SettingsActivity, m)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val rate = AppSettings.ttsRate(this)
        binding.seekDefaultRate.progress = ((rate - 0.5f) / 0.1f).toInt().coerceIn(0, 20)
        binding.tvDefaultRate.text = String.format("%.1fx", rate)
        binding.seekDefaultRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val r = 0.5f + progress * 0.1f
                binding.tvDefaultRate.text = String.format("%.1fx", r)
                if (fromUser) AppSettings.setTtsRate(this@SettingsActivity, r)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        refreshEdgeLabels()
        binding.rowLeftEdge.setOnClickListener {
            pickEdgeAction(
                getString(R.string.settings_left_edge),
                AppSettings.leftEdgeAction(this),
            ) { AppSettings.setLeftEdgeAction(this, it) }
        }
        binding.rowRightEdge.setOnClickListener {
            pickEdgeAction(
                getString(R.string.settings_right_edge),
                AppSettings.rightEdgeAction(this),
            ) { AppSettings.setRightEdgeAction(this, it) }
        }
    }
}
