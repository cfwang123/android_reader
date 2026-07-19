package com.whj.reader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.whj.reader.MainActivity
import com.whj.reader.R
import java.lang.ref.WeakReference

/**
 * TTS 前台服务：通知栏 + 锁屏媒体控制器（对齐 music-player）。
 *
 * 支持：暂停 / 继续、上一句、下一句、停止；耳机/蓝牙媒体键。
 * [SPEAKING] 与 [PAUSED] 时保持运行；[IDLE]/停止时退出。
 */
class TtsPlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var isForeground = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 周期性刷新 MediaSession + 持锁，降低 OEM 判定「假播放」/冻进程 */
    private val heartbeat = object : Runnable {
        override fun run() {
            val info = TtsManager.sessionInfo()
            if (!info.active) {
                teardownAndStop()
                return
            }
            // 朗读中始终持 PARTIAL_WAKE_LOCK（含灭屏）
            if (info.playing) {
                acquireWakeLock()
                // 灭屏时也刷成 PLAYING，防止系统把会话打成 paused
                updateSessionState(playing = true, active = true)
            } else {
                releaseWakeLock()
            }
            refreshChrome()
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 部分 OEM 锁屏会误发 LOSS；灭屏时忽略，亮屏才真正让出
                if (isScreenInteractive()) {
                    Log.i(TAG, "audio focus LOSS (screen on) → pause TTS")
                    TtsManager.pauseFromExternal()
                    refreshChrome()
                } else {
                    Log.w(TAG, "ignore AUDIOFOCUS_LOSS while screen off (OEM quirk)")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isScreenInteractive()) {
                    Log.i(TAG, "audio focus LOSS_TRANSIENT → pause TTS")
                    TtsManager.pauseFromExternal()
                    refreshChrome()
                } else {
                    Log.w(TAG, "ignore AUDIOFOCUS_LOSS_TRANSIENT while screen off")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 朗读不 duck、不停播
                Log.i(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ignored")
            }
            AudioManager.AUDIOFOCUS_GAIN -> Log.i(TAG, "audio focus gain")
        }
    }

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= 20) {
            pm.isInteractive
        } else {
            @Suppress("DEPRECATION")
            pm.isScreenOn
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        ensureChannel()
        initMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 耳机线控 / 蓝牙媒体键
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (intent?.action) {
            ACTION_STOP -> {
                TtsManager.stopFromNotification()
                teardownAndStop()
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE -> {
                TtsManager.playPauseFromExternal()
                enterForegroundIfNeeded()
                refreshChrome()
                return START_STICKY
            }
            ACTION_PAUSE -> {
                TtsManager.pauseFromExternal()
                enterForegroundIfNeeded()
                refreshChrome()
                return START_STICKY
            }
            ACTION_PLAY -> {
                TtsManager.resumeFromExternal()
                enterForegroundIfNeeded()
                refreshChrome()
                return START_STICKY
            }
            ACTION_PREV -> {
                TtsManager.previousSentenceFromExternal()
                enterForegroundIfNeeded()
                refreshChrome()
                return START_STICKY
            }
            ACTION_NEXT -> {
                TtsManager.nextSentenceFromExternal()
                enterForegroundIfNeeded()
                refreshChrome()
                return START_STICKY
            }
            ACTION_REFRESH -> {
                val info = TtsManager.sessionInfo()
                if (!info.active) {
                    teardownAndStop()
                    return START_NOT_STICKY
                }
                enterForegroundIfNeeded()
                refreshChrome()
                return START_STICKY
            }
        }

        // 默认：起播 / 保活
        val info = TtsManager.sessionInfo()
        if (!info.active && intent?.action == null) {
            // 可能尚未写入 SPEAKING，仍进前台；下一帧 refresh 会纠正
        }
        if (info.playing || intent?.action == null || intent.action == ACTION_REFRESH) {
            requestAudioFocus()
        }
        enterForegroundIfNeeded()
        refreshChrome()
        mainHandler.removeCallbacks(heartbeat)
        mainHandler.postDelayed(heartbeat, HEARTBEAT_MS)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 滑掉任务卡片：朗读中仍保持（与 music-player 一致）
        val info = TtsManager.sessionInfo()
        if (!info.active) {
            teardownAndStop()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (instanceRef?.get() === this) instanceRef = null
        mainHandler.removeCallbacks(heartbeat)
        releaseWakeLock()
        abandonAudioFocus()
        releaseMediaSession()
        isForeground = false
        super.onDestroy()
    }

    private fun enterForegroundIfNeeded() {
        ensureChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        val playing = TtsManager.sessionInfo().playing
        if (playing) {
            requestAudioFocus()
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    private fun teardownAndStop() {
        mainHandler.removeCallbacks(heartbeat)
        releaseWakeLock()
        abandonAudioFocus()
        mediaSession?.isActive = false
        updateSessionState(playing = false, active = false)
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        isForeground = false
        stopSelf()
    }

    private fun initMediaSession() {
        if (mediaSession != null) return
        val session = MediaSessionCompat(this, "whj.reader.tts").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        TtsManager.resumeFromExternal()
                        refreshChrome()
                    }

                    override fun onPause() {
                        // 部分机型锁屏会向 MediaSession 发 pause，勿误停朗读
                        if (!isScreenInteractive()) {
                            Log.w(TAG, "ignore MediaSession onPause while screen off")
                            // 立刻把状态推回 Playing，避免系统媒体面板显示已暂停
                            updateSessionState(playing = true, active = true)
                            return
                        }
                        TtsManager.pauseFromExternal()
                        refreshChrome()
                    }

                    override fun onSkipToNext() {
                        TtsManager.nextSentenceFromExternal()
                        refreshChrome()
                    }

                    override fun onSkipToPrevious() {
                        TtsManager.previousSentenceFromExternal()
                        refreshChrome()
                    }

                    override fun onStop() {
                        TtsManager.stopFromNotification()
                        teardownAndStop()
                    }
                },
                mainHandler,
            )
            isActive = true
        }
        // 媒体按钮 PendingIntent → MediaButtonReceiver → 本服务
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(
            this,
            MediaButtonReceiver::class.java,
        )
        val pi = PendingIntent.getBroadcast(
            this,
            0,
            mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable(),
        )
        session.setMediaButtonReceiver(pi)
        mediaSession = session
    }

    /** 刷新锁屏会话 + 通知内容 */
    fun refreshChrome() {
        val info = TtsManager.sessionInfo()
        if (!info.active) {
            // 已停止：收服务
            if (isForeground) teardownAndStop()
            return
        }
        updateSessionMetadata(info)
        updateSessionState(playing = info.playing, active = true)
        if (!isForeground) {
            enterForegroundIfNeeded()
            return
        }
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun updateSessionMetadata(info: TtsManager.SessionInfo) {
        val session = mediaSession ?: return
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, info.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, info.subtitle)
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ALBUM,
                    getString(R.string.tts_notif_channel),
                )
                // 未知时长；部分锁屏 UI 需要非 0 才稳定显示控件
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                .build(),
        )
    }

    private fun updateSessionState(playing: Boolean, active: Boolean) {
        val session = mediaSession ?: return
        if (!active) {
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY)
                    .setState(
                        PlaybackStateCompat.STATE_STOPPED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        0f,
                        SystemClock.elapsedRealtime(),
                    )
                    .build(),
            )
            session.isActive = false
            return
        }
        val actions = (
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
        val state = if (playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1.0f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        session.isActive = true
    }

    private fun releaseMediaSession() {
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val am = audioManager ?: return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
                .setAcceptsDelayedFocusGain(true)
                // 被其它短暂音频打扰时也不自动 pause（我们自己处理 LOSS）
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "requestAudioFocus result=$result granted=$hasAudioFocus")
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(audioFocusListener) }
        }
        hasAudioFocus = false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "whj.reader:tts",
        ).also {
            it.setReferenceCounted(false)
            it.acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
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
                setSound(null, null)
            },
        )
    }

    private fun buildNotification(): Notification {
        val info = TtsManager.sessionInfo()
        val playing = info.playing
        val title = info.title.ifBlank { getString(R.string.tts_notif_title) }
        val text = info.subtitle.ifBlank {
            if (playing) getString(R.string.tts_speaking) else getString(R.string.tts_paused)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable(),
        )

        val mediaStyle = MediaNotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setShowCancelButton(true)
            .setCancelButtonIntent(actionPi(ACTION_STOP, 4))
        mediaSession?.sessionToken?.let { mediaStyle.setMediaSession(it) }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (playing) R.drawable.ic_play else R.drawable.ic_pause)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_prev, getString(R.string.tts_prev), actionPi(ACTION_PREV, 1))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.tts_pause else R.string.tts_resume),
                actionPi(ACTION_PLAY_PAUSE, 2),
            )
            .addAction(R.drawable.ic_next, getString(R.string.tts_next), actionPi(ACTION_NEXT, 3))
            .setDeleteIntent(actionPi(ACTION_STOP, 5))
            .setStyle(mediaStyle)
            .build()
    }

    private fun actionPi(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TtsPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable(),
        )
    }

    private fun pendingImmutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        private const val TAG = "WhjTtsSvc"
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIF_ID = 7101
        /** 灭屏时更勤快刷会话/持锁（OEM 常在十几秒后掐） */
        private const val HEARTBEAT_MS = 8_000L

        const val ACTION_STOP = "com.whj.reader.tts.STOP"
        const val ACTION_PAUSE = "com.whj.reader.tts.PAUSE"
        const val ACTION_PLAY = "com.whj.reader.tts.PLAY"
        const val ACTION_PLAY_PAUSE = "com.whj.reader.tts.PLAY_PAUSE"
        const val ACTION_PREV = "com.whj.reader.tts.PREV"
        const val ACTION_NEXT = "com.whj.reader.tts.NEXT"
        const val ACTION_REFRESH = "com.whj.reader.tts.REFRESH"

        private var instanceRef: WeakReference<TtsPlaybackService>? = null

        fun start(context: Context) {
            val i = Intent(context, TtsPlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
            }
        }

        /** 状态/句变更时刷新通知与锁屏控件 */
        fun refresh(context: Context) {
            val svc = instanceRef?.get()
            if (svc != null) {
                svc.mainHandler.post { svc.refreshChrome() }
                return
            }
            val info = TtsManager.sessionInfo()
            if (info.active) {
                val i = Intent(context, TtsPlaybackService::class.java).setAction(ACTION_REFRESH)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(i)
                    } else {
                        context.startService(i)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "refresh start failed", e)
                }
            }
        }

        fun stop(context: Context) {
            val svc = instanceRef?.get()
            if (svc != null) {
                svc.mainHandler.post { svc.teardownAndStop() }
                return
            }
            try {
                context.stopService(Intent(context, TtsPlaybackService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stopService failed", e)
            }
        }
    }
}
