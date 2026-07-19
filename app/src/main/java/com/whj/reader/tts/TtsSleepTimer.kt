package com.whj.reader.tts

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.min

/**
 * TTS 定时关闭：到点回调 [onFinished]（由 Activity 停止朗读）。
 * 运行中每秒 [onTick] 刷新倒计时 UI。
 */
class TtsSleepTimer(
    private val onTick: (remainingMs: Long) -> Unit,
    private val onFinished: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var endElapsed = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            val left = endElapsed - SystemClock.elapsedRealtime()
            if (left <= 0L) {
                clearInternal()
                onFinished()
            } else {
                onTick(left)
                handler.postDelayed(this, min(1000L, left))
            }
        }
    }

    fun isActive(): Boolean = endElapsed > SystemClock.elapsedRealtime()

    fun remainingMs(): Long =
        (endElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    /** @param durationMs 0 或负数表示关闭定时 */
    fun start(durationMs: Long) {
        cancel()
        if (durationMs <= 0L) return
        endElapsed = SystemClock.elapsedRealtime() + durationMs
        onTick(durationMs)
        handler.postDelayed(tickRunnable, 1000L)
    }

    fun cancel() {
        clearInternal()
    }

    private fun clearInternal() {
        handler.removeCallbacks(tickRunnable)
        endElapsed = 0L
    }

    companion object {
        /** 0 = 关闭定时；其余为分钟数 */
        val OPTION_MINUTES: IntArray = intArrayOf(0, 5, 15, 30, 45, 60, 90)

        fun formatCountdown(ms: Long): String {
            val totalSec = ((ms + 999L) / 1000L).toInt().coerceAtLeast(0)
            val m = totalSec / 60
            val s = totalSec % 60
            return "%d:%02d".format(m, s)
        }
    }
}
