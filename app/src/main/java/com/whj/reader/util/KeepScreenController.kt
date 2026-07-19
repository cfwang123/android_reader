package com.whj.reader.util

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.whj.reader.data.AppSettings
import com.whj.reader.model.KeepScreenMode

/**
 * 阅读页屏幕常亮策略：
 * - [KeepScreenMode.OFF]：不常亮
 * - [KeepScreenMode.ALWAYS]：阅读时常亮；可选「空闲熄屏」后放开系统锁屏
 * - [KeepScreenMode.TTS_ONLY]：仅 TTS 朗读中常亮
 */
class KeepScreenController(
    private val activity: Activity,
    private val isTtsSpeaking: () -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    /** ALWAYS 模式下因空闲已取消常亮 */
    private var idleReleased = false
    private val idleReleaseRunnable = Runnable {
        idleReleased = true
        apply()
    }

    fun onResume() {
        onUserActivity()
    }

    fun onPause() {
        handler.removeCallbacks(idleReleaseRunnable)
        // 离开前台时不强制清 flag，系统会处理；保持语义干净
        clearFlag()
    }

    fun onDestroy() {
        handler.removeCallbacks(idleReleaseRunnable)
        clearFlag()
    }

    /** 用户触摸/操作：重置空闲熄屏计时并恢复 ALWAYS 常亮 */
    fun onUserActivity() {
        idleReleased = false
        handler.removeCallbacks(idleReleaseRunnable)
        val mode = AppSettings.keepScreenMode(activity)
        val mins = AppSettings.idleScreenOffMinutes(activity)
        if (mode == KeepScreenMode.ALWAYS && mins > 0) {
            handler.postDelayed(idleReleaseRunnable, mins * 60_000L)
        }
        apply()
    }

    /** TTS 状态变化时调用 */
    fun onTtsStateChanged() {
        apply()
    }

    fun apply() {
        if (activity.isFinishing) return
        val mode = AppSettings.keepScreenMode(activity)
        val speaking = runCatching { isTtsSpeaking() }.getOrDefault(false)
        val keep = when (mode) {
            KeepScreenMode.OFF -> false
            KeepScreenMode.TTS_ONLY -> speaking
            // 朗读中始终常亮；否则在空闲熄屏触发前常亮
            KeepScreenMode.ALWAYS -> speaking || !idleReleased
        }
        if (keep) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            clearFlag()
        }
    }

    private fun clearFlag() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
