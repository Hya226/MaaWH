package com.maawh.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * 定时触发的广播（闹钟到期）：续约下一次闹钟 → 点亮屏幕 → 拉起 MainActivity 开跑。
 *
 * 执行收敛在 MainActivity（队列构建/清单/预览/停止按钮都在那儿，与手动开始同一套链路），
 * 这里只负责调度续约与唤醒转发；清单未就绪时 MainActivity 会挂起补跑
 * （与 adb 直达入口同一套 pending 机制）。本 App 申请过悬浮窗权限，后台启动 Activity
 * 在各 ROM 上都被放行（SYSTEM_ALERT_WINDOW 是 background-activity-launch 的豁免项）。
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduleManager.ACTION_TRIGGER) return
        val id = intent.getStringExtra(ScheduleManager.EXTRA_ID) ?: return
        val item = ScheduleStore.get(context, id)
        if (item == null) {
            // 读不到条目：已删除条目的残留闹钟，或存储坏数据回落空表（save 是整 key 覆写，
            // 坏一次全没）。两者区分不了，统一走有上限的重试链（60s×3，对已删除条目是幂等
            // 空操作）；用尽转 30 分钟后整批重排——调度链不在这里静默断掉。
            ScheduleManager.scheduleRetry(context, id, intent.getIntExtra(ScheduleManager.EXTRA_RETRY, 0))
            return
        }

        // 先续约再执行：调度链不能被执行侧的任何失败打断
        // （单次 → 触发即停用并落账；循环 → lastRun 落账 + 挂下一次）
        if (item.repeat == ScheduleStore.REPEAT_ONCE) {
            ScheduleStore.recordTrigger(context, item)
            ScheduleStore.setEnabled(context, id, false)
        } else {
            ScheduleStore.recordTrigger(context, item)
            if (ScheduleManager.armItem(context, item) == ScheduleManager.ArmResult.FAILED) {
                // 闹钟被系统全拒（理论上 set() 兜底后到不了这）：挂个重排兜底，别让链条断掉
                ScheduleManager.scheduleResync(context)
            }
        }

        // 点亮屏幕（不解锁）：部分 ROM 熄屏会把虚拟屏一起断电，帧流黑屏任务必挂；
        // 亮屏至少保证冷启动路径与画面预览走得通。拿不到锁也无害。
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
                "maawh:schedule"
            ).acquire(15_000L)
        }

        val launch = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_RUN_SCHEDULE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(ScheduleManager.EXTRA_ID, id)
        }
        runCatching { context.startActivity(launch) }.onFailure {
            // 拉不起界面（少数 ROM 连悬浮窗豁免也拦）：至少让通知说一声，别无声无息
            runCatching {
                KeepAliveService.start(context, "定时「${item.name}」到点，但无法自动打开界面，请手动打开 MaaWH 查看")
            }
        }
    }
}
