package com.whj.reader.util

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import com.whj.reader.data.AppSettings
import com.whj.reader.tts.TtsManager
import com.whj.reader.tts.TtsPlaybackService
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 应用级「自动关闭」：
 * - 无操作超过 [AppSettings.autoCloseMinutes] 后：停止 TTS、关闭全部界面
 * - 触摸/按键、通知栏暂停继续、耳机线控暂停继续等会 [onUserActivity] 重新计时
 * - 0 分钟 = 禁用
 */
object AutoCloseController {

    private const val TAG = "AutoClose"

    private var app: Application? = null
    private val handler = Handler(Looper.getMainLooper())
    private val activities = CopyOnWriteArrayList<WeakReference<Activity>>()
    private var lastActivityElapsed = 0L
    private var closing = false

    private val closeRunnable = Runnable { performClose() }

    fun init(application: Application) {
        if (app != null) return
        app = application
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        onUserActivity("init")
    }

    /** 任意用户操作：重置倒计时 */
    fun onUserActivity(reason: String = "user") {
        val ctx = app ?: return
        if (closing) return
        lastActivityElapsed = SystemClock.elapsedRealtime()
        handler.removeCallbacks(closeRunnable)
        val mins = AppSettings.autoCloseMinutes(ctx)
        if (mins <= 0) {
            ReaderLog.d(ReaderLog.Module.TTS, "$TAG skip (disabled) reason=$reason")
            return
        }
        val delay = mins * 60_000L
        handler.postDelayed(closeRunnable, delay)
        ReaderLog.d(
            ReaderLog.Module.TTS,
            "$TAG reschedule ${mins}min reason=$reason",
        )
    }

    /** 设置页改动后立刻按新值重排 */
    fun reloadFromSettings() {
        onUserActivity("settings")
    }

    private fun performClose() {
        val ctx = app ?: return
        if (closing) return
        val mins = AppSettings.autoCloseMinutes(ctx)
        if (mins <= 0) return
        val idle = SystemClock.elapsedRealtime() - lastActivityElapsed
        if (idle < mins * 60_000L - 500L) {
            // 期间又有操作但回调未清干净时，重新排程
            onUserActivity("recheck")
            return
        }
        closing = true
        ReaderLog.i(ReaderLog.Module.TTS, "$TAG timeout after ${mins}min → stop TTS & exit")

        // 先停朗读（通知服务会随之 teardown）
        runCatching { TtsManager.stopFromNotification() }

        val list = activities.mapNotNull { it.get() }.filter { !it.isFinishing && !it.isDestroyed }
        val top = list.lastOrNull()
        if (top != null) {
            runCatching {
                android.widget.Toast.makeText(
                    top,
                    top.getString(com.whj.reader.R.string.auto_close_triggered),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }

        // 结束全部 Activity（onPause 会落盘进度）
        for (act in list.asReversed()) {
            runCatching {
                act.runOnUiThread {
                    if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                    if (android.os.Build.VERSION.SDK_INT >= 21) {
                        act.finishAndRemoveTask()
                    } else {
                        act.finish()
                    }
                }
            }
        }

        // 确保前台服务退出
        runCatching {
            ctx.stopService(Intent(ctx, TtsPlaybackService::class.java))
        }

        // 短暂延迟后复位 flag，便于下次冷启动
        handler.postDelayed({ closing = false }, 2_000L)
    }

    private fun track(activity: Activity) {
        activities.removeAll { it.get() == null || it.get() === activity }
        activities.add(WeakReference(activity))
    }

    private fun untrack(activity: Activity) {
        activities.removeAll { it.get() == null || it.get() === activity }
    }

    private fun wrapWindowCallback(activity: Activity) {
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current is TrackingWindowCallback) return
        window.callback = TrackingWindowCallback(current) {
            onUserActivity("touch_or_key")
        }
    }

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            track(activity)
            // AppCompat 会在 setContentView 后替换 callback，延后包一层
            activity.window?.decorView?.post { wrapWindowCallback(activity) }
        }

        override fun onActivityStarted(activity: Activity) {
            track(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            track(activity)
            wrapWindowCallback(activity)
            onUserActivity("resume")
        }

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {}

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
            untrack(activity)
        }
    }

    /**
     * 转发原 Window.Callback，在触摸按下 / 按键按下时记一次用户操作。
     */
    private class TrackingWindowCallback(
        private val base: Window.Callback,
        private val onInteract: () -> Unit,
    ) : Window.Callback by base {

        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event != null &&
                (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_UP)
            ) {
                onInteract()
            }
            return base.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                onInteract()
            }
            return base.dispatchKeyEvent(event)
        }
    }
}
