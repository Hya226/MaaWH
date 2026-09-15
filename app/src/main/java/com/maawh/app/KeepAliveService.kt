package com.maawh.app

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

/**
 * 「任务运行中 / 虚拟屏存活」期间的前台服务（保活）。
 *
 * 为什么需要：ColorOS 会把 MaaWH 当缓存进程清掉（实测 `dumpsys activity exit-info com.maawh.app`
 * 里有 `reason=13 subreason=2040 description=stop com.maawh.app due to o-stop(40)`，importance=400）。
 * 而虚拟屏挂在 Shizuku UserService（`daemon(false)`，随 App 进程一起销毁）上，App 一死：
 * 虚拟屏、游戏会话、Shizuku 连接全断，用户下次用还得重新建屏——这正是"跑完任务后偶尔要重新折腾"的来源。
 * 前台服务把 App 提到 FOREGROUND_SERVICE 重要性并常驻通知，是 App 侧唯一有效的保活手段。
 *
 * 系统侧的另一半（App 做不了、只能引导用户）见 MainActivity 的「防后台被杀设置」：
 * 电池优化白名单 + ColorOS 自启动/应用速冻 + Shizuku 自身的 Watchdog。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.keepalive_default)
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        // 不用 STICKY：进程被杀说明会话已经散了，重启一个空服务没意义，由 App 按需再拉起
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keepalive_channel),
                    NotificationManager.IMPORTANCE_LOW      // 不响不震，只占一行常驻
                )
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "maawh_keepalive"
        private const val NOTIF_ID = 1001
        private const val EXTRA_TEXT = "text"

        /** 拉起/更新保活服务（已在前台服务中时只刷新通知文案） */
        fun start(ctx: Context, text: String) {
            val i = Intent(ctx, KeepAliveService::class.java).putExtra(EXTRA_TEXT, text)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Throwable) {
                // 前台服务被系统拒绝（如通知权限被关）不该影响任务本身
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } catch (e: Throwable) {
            }
        }
    }
}
