package com.maawh.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 定时任务的闹钟调度（对标 maameow 的 ScheduleAlarmManager）。
 *
 * 每条启用的定时各挂一个闹钟（requestCode = 条目 id 哈希），编辑/触发/开机后都整表重挂，幂等。
 * 首选 setAlarmClock（准点 + 豁免 Doze；realme/ColorOS 实测（Android 16，USE_EXACT_ALARM 已 granted）
 * 仍会把 setExactAndAllowWhileIdle 按 policy_permission 降级成 1 小时窗口——04:30 的定时可能在
 * 05:30 前任何时刻才响。setAlarmClock 是用户闹钟语义，任何 ROM 都准点；代价只是状态栏常驻
 * 一个闹钟图标（触发后消失））。
 *
 * ★ 注册防御（2026-10-10，修「点保存闪退」）：部分 ROM 把 AOSP 对 setAlarmClock 的精确闹钟
 * 豁免收紧成了「未声明精确闹钟权限的 App 一律 SecurityException」（用户实测机型，realme 按
 * 原生实现不触发），且 maameow 也按「setAlarmClock 也要权限」处理——所以注册前先
 * [canScheduleExact] 预检、预检过了仍包 runCatching（防检查与注册之间权限被撤），被拒就逐级
 * 降级到无需任何权限的 [AlarmManager.set]，保证调度路径永不抛异常（闪退会让
 * SharedPreferences.apply 的异步落盘一起丢，表现为「任务存不上」）。配套 manifest 声明
 * SCHEDULE_EXACT_ALARM + USE_EXACT_ALARM，被用户/系统拒绝时 UI 提示卡引导授权，
 * 授权广播回来 BootReceiver 自动重挂。
 */
object ScheduleManager {

    const val ACTION_TRIGGER = "com.maawh.app.action.SCHEDULE_TRIGGER"

    /** 整批重排闹钟（resync 兜底/授权恢复/时区变更），显式投递给 BootReceiver */
    const val ACTION_RESYNC = "com.maawh.app.action.SCHEDULE_RESYNC"

    const val EXTRA_ID = "schedule_id"

    /** 触发广播里已补试过的次数（0 = 正常触发；>0 = 第 N 次重试闹钟） */
    const val EXTRA_RETRY = "schedule_retry"

    /** 触发读不到条目（存储坏数据回落空表等）时的补试：最多 3 次、每次 +60s，用尽转 resync */
    private const val MAX_RETRY = 3
    private const val RETRY_DELAY_MS = 60_000L

    /** 重试用尽后的整批重排延迟——不拉起界面，只从持久化存档把启用条目的闹钟补挂回去 */
    private const val RESYNC_DELAY_MS = 30 * 60_000L
    private const val RESYNC_REQUEST_CODE = 0x5C4E

    /** armItem 的结果：挂上 / 无下次（非失败）/ 系统拒绝 */
    enum class ArmResult {
        /** 闹钟已挂上（首选 setAlarmClock，或权限受限时降级为 set 的非精确闹钟） */
        ARMED,

        /** 无下次触发（单次已过期/每周未勾任何天）——只撤不立，不算失败 */
        NO_NEXT,

        /** 降级链全部被系统拒绝（含 set() 都抛的极端情况），调度链断了，调用方应走 resync 自愈 */
        FAILED
    }

    /** 全表重挂：先撤后立（停用/删除条目的残留闹钟一并清掉）。返回没挂上的（启用）条目名单 */
    fun armAll(ctx: Context): List<String> {
        val all = runCatching { ScheduleStore.load(ctx) }.getOrDefault(emptyList())
        val failed = mutableListOf<String>()
        all.forEach { cancel(ctx, it.id) }
        all.filter { it.enabled }.forEach { item ->
            val r = runCatching { armItem(ctx, item) }.getOrDefault(ArmResult.FAILED)
            if (r == ArmResult.FAILED) failed.add(item.name.ifBlank { describeRule(item) })
        }
        return failed
    }

    /** 挂单条的下一次闹钟；规则已无下次（如单次已过期）则只撤不立 */
    fun armItem(ctx: Context, item: ScheduleStore.Item): ArmResult {
        cancel(ctx, item.id)
        // 时钟被往回拨过（lastRun 落在「未来」）时从上次触发点之后排，免得回拨期间已触发过的
        // 时段再跑一遍；拨回超过一天也按 lastRun 排——宁可少跑一轮不多跑，不引入 maameow 的
        // 回拨窗口（那会把「时钟纠正」当例外按 now 排，多一条要解释的路径）。正常场景
        // lastRun ≤ now，max 取 now，行为与旧版完全一致。
        val now = System.currentTimeMillis()
        val at = nextTrigger(item, maxOf(now, item.lastRun)) ?: return ArmResult.NO_NEXT
        return if (postAlarm(ctx, at, triggerPi(ctx, item.id), item.id)) ArmResult.ARMED else ArmResult.FAILED
    }

    fun cancel(ctx: Context, id: String) {
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(triggerPi(ctx, id))
            am.cancel(triggerPi(ctx, id, retryCount = 1))
        }
    }

    /** 触发时读不到条目：60s 后补试（重试槽独立于下次触发的槽，互不冲掉），用尽转 resync */
    fun scheduleRetry(ctx: Context, id: String, doneCount: Int): Boolean {
        if (doneCount >= MAX_RETRY) {
            Log.w("MaaWH", "定时 $id 重试 $doneCount 次仍读不到条目，转 $RESYNC_DELAY_MS 分钟后整批重排")
            return scheduleResync(ctx)
        }
        return postAlarm(
            ctx,
            System.currentTimeMillis() + RETRY_DELAY_MS,
            triggerPi(ctx, id, retryCount = doneCount + 1),
            "retry${doneCount + 1}:$id"
        )
    }

    /** 稍后整批重排：只挂一个 30 分钟后的闹钟让 BootReceiver 从存档重挂全部启用条目 */
    fun scheduleResync(ctx: Context): Boolean = postAlarm(
        ctx,
        System.currentTimeMillis() + RESYNC_DELAY_MS,
        resyncPi(ctx),
        "resync"
    )

    /**
     * 挂闹钟的唯一入口，永不向调用方抛异常：setAlarmClock → setExactAndAllowWhileIdle →
     * setAndAllowWhileIdle → set 逐级降级。exact 系被拒（无权限/ROM 收紧）时最后落到 set()——
     * 它没有任何权限要求，最差情况是「可能被 Doze 拖延的非精确闹钟」，功能不丢。
     */
    private fun postAlarm(ctx: Context, at: Long, pi: PendingIntent, tag: String): Boolean {
        val am = ctx.getSystemService()
        if (canScheduleExact(ctx)) {
            runCatching {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi(ctx)), pi)
            }.onSuccess { return true }
                .onFailure { e -> Log.w("MaaWH", "setAlarmClock 被拒(${e.javaClass.simpleName}: ${e.message})，降级重试: $tag") }
            // 预检通过仍可能被拒（检查与注册之间权限被撤/ROM 单独门控 setAlarmClock），exact 系再试
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }.onSuccess { return true }
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }.onSuccess { return true }
        } else {
            Log.w("MaaWH", "精确闹钟权限未授予，定时降级为非精确闹钟（到点可能延迟，授权后自动恢复准点）: $tag")
        }
        return runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
            .onFailure { e -> Log.w("MaaWH", "闹钟挂载全部失败: $tag (${e.javaClass.simpleName}: ${e.message})") }
            .isSuccess
    }

    /**
     * API 31 起系统可拒绝精确闹钟（maameow 的实测口径：setAlarmClock 也在管控内）；
     * 31 以下没有这个机制，恒可。查询本身异常的 ROM 按「可」处理——别因查询失败把功能
     * 整个判死，注册路径还有 runCatching 兜底。
     */
    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService()
        val granted = runCatching { am.canScheduleExactAlarms() }.getOrDefault(true)
        return granted
    }

    private fun Context.getSystemService() =
        getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun triggerPi(ctx: Context, id: String, retryCount: Int = 0): PendingIntent {
        val it = Intent(ctx, ScheduleReceiver::class.java).apply {
            action = ACTION_TRIGGER
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_RETRY, retryCount)
        }
        // 下次触发与重试各占一个槽（requestCode 区分），重排/补试互不冲掉对方的在等闹钟
        val code = (if (retryCount > 0) "$id#retry" else id).hashCode() and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            ctx, code, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** resync 闹钟：显式投递 BootReceiver（无需导出/无需 intent-filter） */
    private fun resyncPi(ctx: Context): PendingIntent {
        val it = Intent(ctx, BootReceiver::class.java).apply { action = ACTION_RESYNC }
        return PendingIntent.getBroadcast(
            ctx, RESYNC_REQUEST_CODE, it,
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

    /** 下一次触发时刻（严格晚于 fromMs）；单次已过期 / 每周未勾任何天 / 间隔非法 → null */
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
            ScheduleStore.REPEAT_INTERVAL -> {
                if (item.intervalMin <= 0) return null
                val step = item.intervalMin * 60_000L
                val anchor = if (item.anchorMs > 0) item.anchorMs else fromMs
                if (fromMs < anchor) return anchor
                // 锚点后第一个严格晚于 fromMs 的网格点；floorDiv 防整除时 k 偏小（那会取到过去时刻）
                val k = Math.floorDiv(fromMs - anchor, step) + 1
                anchor + k * step
            }
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

    /** UI 展示口径的「下次触发」：与 armItem 同源（回拨防护 max(now, lastRun)），两边不会各说各话 */
    fun nextTriggerFromNow(item: ScheduleStore.Item, nowMs: Long): Long? =
        nextTrigger(item, maxOf(nowMs, item.lastRun))

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

    /** 规则的展示文案："每天 04:30" / "每周 一、四 04:30" / "单次 10-05 04:30" / "每 2 小时" */
    fun describeRule(item: ScheduleStore.Item): String {
        val hm = String.format(Locale.US, "%02d:%02d", item.hour, item.minute)
        return when (item.repeat) {
            ScheduleStore.REPEAT_DAILY -> "每天 $hm"
            ScheduleStore.REPEAT_WEEKLY -> "每周 ${weekdayText(item.days)} $hm"
            ScheduleStore.REPEAT_ONCE ->
                if (item.dateIso.length >= 10) "单次 ${item.dateIso.substring(5)} $hm" else "单次 $hm"
            ScheduleStore.REPEAT_INTERVAL -> intervalText(item.intervalMin)
            else -> hm
        }
    }

    /** 间隔分钟数 → "每 2 小时" / "每 90 分钟" / "每 1 小时 30 分钟" */
    private fun intervalText(min: Int): String = when {
        min <= 0 -> "间隔"
        min % 60 == 0 -> "每 ${min / 60} 小时"
        min > 60 -> "每 ${min / 60} 小时 ${min % 60} 分钟"
        else -> "每 $min 分钟"
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
