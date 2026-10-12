package com.maawh.app

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 定时闹钟的恢复入口（全部 → ScheduleManager.armAll 整表重挂，幂等）：
 * - BOOT_COMPLETED / MY_PACKAGE_REPLACED：闹钟不跨重启，覆盖安装后系统也会清掉，必须补挂，
 *   否则"定了个每天早上的定时，重启一次就永远不响"。
 * - TIMEZONE_CHANGED：「每天 04:30」是墙钟语义，时区一变已挂的绝对时刻就不再对应原墙钟点，
 *   重排让规则跟着新时区走。
 * - ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED：用户在系统设置里（重新）授权精确
 *   闹钟的瞬间自动重挂——权限被拒期间降级成非精确闹钟的条目由此恢复准点（对标 maameow）。
 * - ACTION_RESYNC（App 内部显式投递）：触发读不到条目重试用尽后的兜底整批重排。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            ScheduleManager.ACTION_RESYNC ->
                runCatching { ScheduleManager.armAll(context) }
        }
    }
}
