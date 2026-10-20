package com.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机 / App 更新后，如果之前服务在运行，则重新拉起前台服务并恢复对账任务。
 * （WorkManager 的周期任务本身也能跨重启恢复，这里再确保一次。）
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!Prefs.isRunning(context)) return

        try {
            WorkScheduler.scheduleReconcile(context)
        } catch (_: Exception) {
            // Android 7 上 androidx.work 可能降级；对账只是兜底，失败不应影响重启服务
        }
        val svc = Intent(context, ForwardService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (_: Exception) {
            // Android 12+ 后台启动前台服务可能被拒；忽略，用户回 App 手动启动即可
        }
    }
}
