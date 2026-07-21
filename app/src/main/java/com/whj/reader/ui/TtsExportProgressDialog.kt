package com.whj.reader.ui

import android.app.Activity
import android.os.SystemClock
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.whj.reader.R
import java.util.Locale

/**
 * 合成语音进度窗口：阶段、段数/字数、百分比、取消。
 * 进度优先按字数，段内也会随 partFraction 推进。
 */
class TtsExportProgressDialog(
    private val activity: Activity,
    private val onCancel: () -> Unit,
) {
    private var dialog: AlertDialog? = null
    private var tvPhase: TextView? = null
    private var tvDetail: TextView? = null
    private var tvPercent: TextView? = null
    private var progressBar: ProgressBar? = null
    private var startElapsed = 0L
    /** 进度条只增不减，避免抖动回退 */
    private var lastPercent = 0

    fun show() {
        if (activity.isFinishing) return
        dismiss()
        startElapsed = SystemClock.elapsedRealtime()
        lastPercent = 0
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_tts_export_progress, null)
        tvPhase = view.findViewById(R.id.tvExportDlgPhase)
        tvDetail = view.findViewById(R.id.tvExportDlgDetail)
        tvPercent = view.findViewById(R.id.tvExportDlgPercent)
        progressBar = view.findViewById(R.id.progressExportDlg)
        progressBar?.apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        tvPhase?.text = activity.getString(R.string.tts_export_phase_prepare)
        tvDetail?.text = ""
        tvPercent?.text = "0%"
        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .create()
        dialog?.show()
    }

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun dismiss() {
        runCatching { dialog?.dismiss() }
        dialog = null
        tvPhase = null
        tvDetail = null
        tvPercent = null
        progressBar = null
    }

    /**
     * @param done 已完成段数
     * @param total 总段数
     * @param phase prepare / synth / merge / encode
     * @param doneChars 已合成字数（可含当前段估算）
     * @param totalChars 总字数
     * @param partFraction 当前段内 0..1
     */
    fun update(
        done: Int,
        total: Int,
        phase: String,
        doneChars: Int = 0,
        totalChars: Int = 0,
        partFraction: Float = 0f,
    ) {
        val run = Runnable {
            if (activity.isFinishing || dialog?.isShowing != true) return@Runnable
            applyUpdate(done, total, phase, doneChars, totalChars, partFraction)
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            run.run()
        } else {
            activity.runOnUiThread(run)
        }
    }

    private fun applyUpdate(
        done: Int,
        total: Int,
        phase: String,
        doneChars: Int,
        totalChars: Int,
        partFraction: Float,
    ) {
        val t = total.coerceAtLeast(1)
        val d = done.coerceIn(0, t)
        val frac = partFraction.coerceIn(0f, 1f)

        // 总进度 0–100：字数优先；合成 0–92，合并 93–96，编码 97–99
        val rawPct = when (phase) {
            "prepare", "init" -> 1
            "merge" -> 94
            "encode" -> 98
            else -> {
                val byChars = if (totalChars > 0) {
                    ((doneChars.toFloat() / totalChars) * 92f)
                } else {
                    val base = (d.toFloat() / t) * 92f
                    val within = if (d < t) frac * (92f / t) else 0f
                    base + within
                }
                byChars.coerceIn(0f, 92f)
            }
        }
        val pct = maxOf(lastPercent, rawPct.toInt().coerceIn(0, 99))
        lastPercent = pct

        val bar = progressBar
        if (bar != null) {
            // 避免 indeterminate 粘住导致 progress 不刷新
            bar.isIndeterminate = false
            bar.max = 100
            bar.progress = pct
        }
        tvPercent?.text = "$pct%"

        // 显示「当前段」：合成中第 (done+1) 段；已全部完成则 total/total
        val currentPart = when {
            phase != "synth" -> t
            d >= t -> t
            else -> (d + 1).coerceAtMost(t)
        }
        tvPhase?.text = when (phase) {
            "prepare", "init" -> activity.getString(R.string.tts_export_phase_prepare)
            "merge" -> activity.getString(R.string.tts_export_phase_merge)
            "encode" -> activity.getString(R.string.tts_export_phase_encode)
            else -> activity.getString(R.string.tts_export_phase_synth, currentPart, t)
        }

        val elapsedSec = ((SystemClock.elapsedRealtime() - startElapsed) / 1000L).coerceAtLeast(0L)
        val timeStr = formatElapsed(elapsedSec)
        val charsShow = doneChars.coerceIn(0, totalChars.coerceAtLeast(doneChars))
        val detail = buildString {
            if (totalChars > 0) {
                append(
                    activity.getString(
                        R.string.tts_export_chars_progress,
                        charsShow,
                        totalChars,
                    ),
                )
            }
            if (phase == "synth" && t >= 1) {
                if (isNotEmpty()) append('\n')
                append(
                    activity.getString(
                        R.string.tts_export_parts_progress,
                        currentPart,
                        t,
                    ),
                )
                if (frac > 0.01f && d < t) {
                    append(" · ")
                    append("${(frac * 100).toInt().coerceIn(0, 99)}%")
                }
            }
            if (isNotEmpty()) append('\n')
            append(activity.getString(R.string.tts_export_elapsed, timeStr))
            if (phase == "synth" && charsShow > 0 && totalChars > charsShow && elapsedSec >= 2) {
                val remain = estimateRemainByChars(charsShow, totalChars, elapsedSec)
                if (remain > 0) {
                    append(" · ")
                    append(activity.getString(R.string.tts_export_eta, formatElapsed(remain)))
                }
            }
        }
        tvDetail?.text = detail
    }

    private fun estimateRemainByChars(done: Int, total: Int, elapsedSec: Long): Long {
        if (done <= 0 || elapsedSec <= 0 || total <= done) return 0
        val per = elapsedSec.toDouble() / done
        return ((total - done) * per).toLong().coerceAtLeast(0)
    }

    private fun formatElapsed(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) {
            String.format(Locale.getDefault(), "%d:%02d", m, s)
        } else {
            activity.getString(R.string.tts_export_seconds, s.toInt())
        }
    }
}
