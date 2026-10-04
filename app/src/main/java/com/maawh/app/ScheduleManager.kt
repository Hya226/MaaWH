package com.maawh.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 定时任务的闹钟调度（对标 maameow 的 ScheduleAlarmManager）。
 *
 * 每条启用的定时各挂一个闹钟（requestCode = 条目 id 哈希），编辑/触发/开机后都整表重挂，幂等。
 * 统一用 setAlarmClock（准点 + 豁免 Doze，无需任何权限；realme/ColorOS 会把
 * setExactAndAllowWhileIdle 降级成 1 小时窗口，见 armItem 注释）。
 */
object ScheduleManager {

    const val ACTION_TRIGGER = "com.maawh.app.action.SCHEDULE_TRIGGER"
    const val EXTRA_ID = "schedule_id"

    /** 全表重挂：先撤后立（停用/删除条目的残留闹钟一并清掉） */
    fun armAll(ctx: Context) {
        val all = runCatching { ScheduleStore.load(ctx) }.getOrDefault(emptyList())
        all.forEach { cancel(ctx, it.id) }
        all.filter { it.enabled }.forEach { armItem(ctx, it) }
    }

    /** 挂单条的下一次闹钟；规则已无下次（如单次已过期）则只撤不立 */
    fun armItem(ctx: Context, item: ScheduleStore.Item) {
        cancel(ctx, item.id)
        val at = nextTrigger(item, System.currentTimeMillis()) ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 统一走 setAlarmClock：realme/ColorOS 实测（Android 16，USE_EXACT_ALARM 已 granted）
        // 仍会把 setExactAndAllowWhileIdle 按 policy_permission 降级成 1 小时窗口——
        // 04:30 的定时的可能在 05:30 前任何时刻才响。setAlarmClock 是用户闹钟语义，
        // 任何 ROM 都准点触发且豁免 Doze；代价只是状态栏常驻一个闹钟图标（触发后消失）。
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi(ctx)), triggerPi(ctx, item.id))
    }

    fun cancel(ctx: Context, id: String) {
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(triggerPi(ctx, id))
        }
    }

    private fun triggerPi(ctx: Context, id: String): PendingIntent {
        val it = Intent(ctx, ScheduleReceiver::class.java).apply {
            action = ACTION_TRIGGER
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            ctx, id.hashCode() and 0x7FFFFFFF, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** setAlarmClock 的展示 intent：点状态栏闹钟图标回到 App（无特殊 extras，进主页即可） */
    private fun showPi(ctx: Context): PendingIntent {
        val it = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            ctx, 1, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 下一次触发时刻（严格晚于 fromMs）；单次已过期 / 每周未勾任何天 → null */
    fun nextTrigger(item: ScheduleStore.Item, fromMs: Long): Long? {
        val base = Calendar.getInstance().apply {
            timeInMillis = fromMs
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return when (item.repeat) {
            ScheduleStore.REPEAT_DAILY -> atTime(base, item.hour, item.minute, fromMs, shiftDaysIfPassed = 1)
            ScheduleStore.REPEAT_ONCE -> parseDate(item.dateIso)
                ?.let { atTime(it, item.hour, item.minute, fromMs, shiftDaysIfPassed = 0) }
            ScheduleStore.REPEAT_WEEKLY -> {
                if (item.days.isEmpty()) return null
                // 未来 8 天里找第一个「星期命中且时刻未过」的日子（兜底 8 天防漏，正常 7 天内必命中）
                for (add in 0..7) {
                    val c = (base.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, add)
                        set(Calendar.HOUR_OF_DAY, item.hour)
                        set(Calendar.MINUTE, item.minute)
                    }
                    if (c.timeInMillis > fromMs && item.days.contains(c.get(Calendar.DAY_OF_WEEK))) {
                        return c.timeInMillis
                    }
                }
                null
            }
            else -> null
        }
    }

    private fun atTime(base: Calendar, hour: Int, minute: Int, fromMs: Long, shiftDaysIfPassed: Int): Long? {
        val c = (base.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        if (c.timeInMillis <= fromMs) {
            if (shiftDaysIfPassed <= 0) return null
            c.add(Calendar.DAY_OF_YEAR, shiftDaysIfPassed)
        }
        return c.timeInMillis
    }

    private fun parseDate(iso: String): Calendar? = try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso) ?: return null
        Calendar.getInstance().apply { timeInMillis = d.time }
    } catch (e: Throwable) {
        null
    }

    /** 规则的展示文案："每天 04:30" / "每周 一、四 04:30" / "单次 10-05 04:30" */
    fun describeRule(item: ScheduleStore.Item): String {
        val hm = String.format(Locale.US, "%02d:%02d", item.hour, item.minute)
        return when (item.repeat) {
            ScheduleStore.REPEAT_DAILY -> "每天 $hm"
            ScheduleStore.REPEAT_WEEKLY -> "每周 ${weekdayText(item.days)} $hm"
            ScheduleStore.REPEAT_ONCE ->
                if (item.dateIso.length >= 10) "单次 ${item.dateIso.substring(5)} $hm" else "单次 $hm"
            else -> hm
        }
    }

    /** Calendar.DAY_OF_WEEK 集合 → 「一、四」（按周一起算，符合中文习惯） */
    fun weekdayText(days: Set<Int>): String {
        val order = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
        val names = mapOf(
            Calendar.MONDAY to "一", Calendar.TUESDAY to "二", Calendar.WEDNESDAY to "三",
            Calendar.THURSDAY to "四", Calendar.FRIDAY to "五", Calendar.SATURDAY to "六",
            Calendar.SUNDAY to "日"
        )
        return order.filter { days.contains(it) }.mapNotNull { names[it] }.joinToString("、")
    }
}
