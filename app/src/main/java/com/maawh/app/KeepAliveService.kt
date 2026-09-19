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

    /** 挂机守护轮询（autoMute 开启时）：检测游戏被切到物理屏玩 → 挂机静音让位 */
    private val watchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val watchRunnable = object : Runnable {
        override fun run() {
            try {
                // 守护条件：autoMute 开、游戏处于管控静音中（标记在）、队列没在跑
                if (autoMuteWatch &&
                    GameAudioMarker.marked(applicationContext) != null &&
                    !QueueRunner.isAnyRunning()
                ) {
                    val top = ShizukuShell.topForegroundPkg()
                    if (top == MaaConst.GAME_PKG) {
                        // 游戏被用户切到物理屏前台玩：挂机静音让位（清标记 + 恢复 appops）
                        if (GameAudioMarker.restoreIfNeeded(applicationContext)) {
                            android.util.Log.i("MaaWH", "挂机守护：游戏切到物理屏，已恢复游戏声音")
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            watchHandler.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        watchHandler.postDelayed(watchRunnable, WATCH_INTERVAL_MS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉 MaaWH：只要队列没在跑就清掉 appops 静音残留（PLAY_AUDIO deny
        // 是持久系统设置，没人恢复的话用户转头打开物华弥新也没声）。队列还在跑则不动——
        // 静音是任务期间的预期行为，收尾自会恢复。
        // 必须用孤儿 shell 派发（requestGameAudioRestore）：划掉瞬间进程随时被杀，普通
        // 异步恢复跑不完（2026-09-19 实测 deny 残留就是这么来的）；命令毫秒级，同步调用即可。
        if (!QueueRunner.isAnyRunning()) {
            ShizukuShell.requestGameAudioRestore()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        watchHandler.removeCallbacks(watchRunnable)
        // 服务停止 = 管控态彻底结束（虚拟屏已死/队列收尾停保活），顺手派发一次静音恢复，
        // 兜住"虚拟屏悄悄死了但 deny 还挂着"的场合；幂等（无 deny 时不动作）。
        ShizukuShell.requestGameAudioRestore()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知「停止任务」按钮：转发给队列执行器，不重建通知（停止后由队列收尾更新通知）
        if (intent?.action == ACTION_STOP) {
            QueueRunner.requestStopCurrent()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.keepalive_default)
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, -1) ?: -1
        val total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0
        val notification = buildNotification(text, progress, total)
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

    private fun buildNotification(text: String, progress: Int, total: Int): Notification {
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
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
        // 任务运行中的通知带队列进度条 + 停止按钮（total=0 是虚拟屏保活通知，不加）
        if (total > 0) {
            b.setProgress(total, progress.coerceIn(0, total), false)
            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(null, getString(R.string.quick_stop), stop).build())
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL_ID = "maawh_keepalive"
        private const val NOTIF_ID = 1001
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PROGRESS = "progress"
        private const val EXTRA_TOTAL = "total"
        private const val WATCH_INTERVAL_MS = 10_000L

        /** 通知「停止任务」按钮的 action */
        const val ACTION_STOP = "com.maawh.app.action.STOP_TASK"

        /** 挂机守护开关（autoMute 开启时由 MainActivity 置 true）：轮询检测游戏切到物理屏 */
        @Volatile
        var autoMuteWatch = false

        /** 拉起/更新保活服务（已在前台服务中时只刷新通知文案）；total>0 时显示进度条与停止按钮 */
        fun start(ctx: Context, text: String, progress: Int = -1, total: Int = 0) {
            val i = Intent(ctx, KeepAliveService::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PROGRESS, progress)
                .putExtra(EXTRA_TOTAL, total)
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
