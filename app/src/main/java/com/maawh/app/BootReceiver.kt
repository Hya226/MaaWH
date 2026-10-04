package com.maawh.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新完成 → 重挂全部定时闹钟。
 * AlarmManager 的闹钟不跨重启，覆盖安装后系统也会清掉，必须在这里补挂，
 * 否则"定了个每天早上的定时，重启一次就永远不响"。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                runCatching { ScheduleManager.armAll(context) }
        }
    }
}
