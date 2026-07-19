package com.whj.reader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.whj.reader.MainActivity
import com.whj.reader.R

/**
 * 前台服务：仅在 TTS **正在朗读** 时运行。
 *
 * - 持有 [PowerManager.PARTIAL_WAKE_LOCK]，避免息屏后引擎被挂起
 * - 暂停 / 停止时 [TtsManager] 会 [stop] 本服务 → [onDestroy] 立刻释放锁
 * （策略对齐 MediaPlayer.setWakeMode / MediaSession：播时持锁，停/暂停释放）
 */
class TtsPlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                TtsManager.stopFromNotification()
                // stop() 会再调 stopService；此处先松锁并结束，避免短暂双持
                releaseWakeLockAndStop()
                return START_NOT_STICKY
            }
        }
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "whj.reader:tts",
        ).also {
            it.setReferenceCounted(false)
            // 上限保护；正常路径靠 pause/stop → stopService 释放
            it.acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun releaseWakeLockAndStop() {
        releaseWakeLock()
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tts_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.tts_notif_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable(),
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.tts_notif_title))
            .setContentText(getString(R.string.tts_speaking))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.tts_stop), stop)
            .build()
    }

    private fun pendingImmutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIF_ID = 7101
        const val ACTION_STOP = "com.whj.reader.tts.STOP"

        fun start(context: Context) {
            val i = Intent(context, TtsPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TtsPlaybackService::class.java))
        }
    }
}
