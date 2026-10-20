package com.smsforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 常驻前台服务。它本身不负责捕获或转发（那两条路径分别由广播和 WorkManager 独立完成），
 * 职责是：保持进程温热、显示常驻状态通知、并确保周期性对账任务已安排。
 * START_STICKY + 前台通知 + 电池优化白名单，共同保证长期存活。
 *
 * 可选的「静音音频保活」（Prefs.KEY_KEEPALIVE_AUDIO）：循环播放一段无声音频，
 * 并以 mediaPlayback 前台服务类型运行，让系统当媒体 App 对待、更不易杀。默认关闭；
 * 适合插电常开的机器（代价是一点点 CPU/耗电）。
 */
class ForwardService : Service() {

    companion object {
        const val CHANNEL_ID = "sms_forward_status"
        const val NOTIF_ID = 2001
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loop: Job? = null
    private var player: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val keepalive = Prefs.get(this).getBoolean(Prefs.KEY_KEEPALIVE_AUDIO, false)
        // 先起音频，再以媒体类型进入前台（Android 14 对 mediaPlayback 要求确实在播放）
        if (keepalive) startKeepaliveAudio() else stopKeepaliveAudio()

        // Android 7 没有 NotificationChannel，通知/前台调用可能降级失败；异常不阻断服务启动
        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (keepalive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                try {
                    startForeground(NOTIF_ID, notif, type)
                } catch (_: Exception) {
                    // 媒体类型被拒时回退到 specialUse，保证服务仍能进前台
                    try {
                        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } catch (_: Exception) {
                    }
                }
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            DebugLog.log(this, "前台通知构建/启动异常：${e.message}")
        }
        Prefs.get(this).edit().putBoolean(Prefs.KEY_RUNNING, true).apply()
        try {
            WorkScheduler.scheduleReconcile(this)
        } catch (e: Exception) {
            DebugLog.log(this, "对账任务调度异常：${e.message}")
        }
        DebugLog.log(this, "服务启动（轮询间隔 ${Prefs.pollSeconds(this)}s）")
        startHeartbeat()
        return START_STICKY  // 被系统杀掉后自动重建
    }

    override fun onDestroy() {
        Prefs.get(this).edit().putBoolean(Prefs.KEY_RUNNING, false).apply()
        DebugLog.log(this, "服务停止")
        stopKeepaliveAudio()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 循环播放无声音频（音量 0、不抢音频焦点，与用户正在放的音乐互不干扰） */
    private fun startKeepaliveAudio() {
        if (player != null) return
        try {
            player = MediaPlayer.create(this, R.raw.silence)?.apply {
                isLooping = true
                setVolume(0f, 0f)
                start()
            }
        } catch (_: Exception) {
            player = null
        }
    }

    private fun stopKeepaliveAudio() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    /**
     * 心跳循环：每 N 秒(可配)轮询一次短信库抓新短信(华为等不下发广播时的主力抓取)，
     * 并刷新通知里的计数。轮询走 IO 线程。
     */
    private fun startHeartbeat() {
        loop?.cancel()
        loop = scope.launch {
            DebugLog.log(applicationContext, "心跳循环已启动")
            var n = 0
            while (isActive) {
                n++
                // 每一步都兜住异常：任何单次错误都不能杀死整个轮询循环
                try {
                    withContext(Dispatchers.IO) { SmsScanner.scan(applicationContext) }
                } catch (e: Exception) {
                    DebugLog.log(applicationContext, "心跳#$n 轮询异常：${e.message}")
                }
                try {
                    val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    mgr.notify(NOTIF_ID, buildNotification())
                } catch (e: Exception) {
                    DebugLog.log(applicationContext, "心跳#$n 通知异常：${e.message}")
                }
                delay(Prefs.pollSeconds(applicationContext) * 1000L)
            }
            DebugLog.log(applicationContext, "心跳循环结束(isActive=false)")
        }
    }

    private fun buildNotification(): Notification {
        val logs = ForwardLog.all(this)
        val total = logs.size
        val failed = logs.count { !it.ok }
        val text = if (!Prefs.isLoggedIn(this)) {
            getString(R.string.notif_not_logged_in)
        } else {
            getString(R.string.notif_running_fmt, total, failed)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
}
