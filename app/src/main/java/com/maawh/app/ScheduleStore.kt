package com.maawh.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时任务的持久化（对标 MAA-Meow 的「定时」页）。
 *
 * 一条定时 = 时刻 + 重复规则 + 要执行的队列配置（QueueStore 里的某个配置）：
 *  - REPEAT_DAILY    每天 HH:mm
 *  - REPEAT_WEEKLY   每周勾选的星期 HH:mm
 *  - REPEAT_ONCE     指定日期 HH:mm，触发一次后自动停用
 *  - REPEAT_INTERVAL 间隔循环：自锚点（anchorMs，= 保存该间隔的时刻）起每 intervalMin 分钟
 *    一个网格点，取严格晚于当前的第一个；重启/重挂不漂移（网格锚在存储的时刻上）
 * profile 为空 = 跟随「当前生效配置」（触发那一刻一键长草里生效的那套队列）。
 *
 * 触发与收尾都会写回 lastRun/lastResult，列表上直接能看到上一次跑成了没有。
 * 存 SharedPreferences（对齐 QueueStore 的 org.json 手写序列化），坏数据整体回落空表。
 */
object ScheduleStore {

    const val REPEAT_DAILY = 0
    const val REPEAT_WEEKLY = 1
    const val REPEAT_ONCE = 2
    const val REPEAT_INTERVAL = 3

    data class Item(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val repeat: Int,
        val hour: Int,
        val minute: Int,
        /** REPEAT_WEEKLY：勾选的星期（Calendar.DAY_OF_WEEK，SUNDAY=1 … SATURDAY=7） */
        val days: Set<Int> = emptySet(),
        /** REPEAT_ONCE：目标日期 "yyyy-MM-dd" */
        val dateIso: String = "",
        /** 要执行的配置名；空 = 跟随当前生效配置 */
        val profile: String = "",
        /** 上次触发结果（触发时写「已触发」，队列收尾改写成功/失败/跳过）；空 = 从未触发 */
        val lastResult: String = "",
        /** 上次触发时间（epoch ms）；0 = 从未 */
        val lastRun: Long = 0,
        /** REPEAT_INTERVAL：间隔分钟数；其他类型 0 */
        val intervalMin: Int = 0,
        /** REPEAT_INTERVAL：间隔网格锚点（= 保存该间隔的时刻）；0 = 尚未设置（按当前时刻起算） */
        val anchorMs: Long = 0
    )

    private const val PREF = "maawh_schedule"
    private const val KEY = "items"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Item> = try {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            if (id.isBlank()) return@mapNotNull null
            Item(
                id = id,
                name = o.optString("name", ""),
                enabled = o.optBoolean("enabled", true),
                repeat = o.optInt("repeat", REPEAT_DAILY),
                hour = o.optInt("hour", 4).coerceIn(0, 23),
                minute = o.optInt("minute", 30).coerceIn(0, 59),
                days = toIntSet(o.optJSONArray("days")),
                dateIso = o.optString("date", ""),
                profile = o.optString("profile", ""),
                lastResult = o.optString("lastResult", ""),
                lastRun = o.optLong("lastRun", 0L),
                intervalMin = o.optInt("interval", 0),
                anchorMs = o.optLong("anchor", 0L)
            )
        }
    } catch (e: Throwable) {
        emptyList()
    }

    fun get(ctx: Context, id: String): Item? = load(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, items: List<Item>) {
        try {
            val arr = JSONArray()
            items.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    if (t.name.isNotEmpty()) put("name", t.name)
                    put("enabled", t.enabled)
                    put("repeat", t.repeat)
                    put("hour", t.hour)
                    put("minute", t.minute)
                    if (t.days.isNotEmpty()) put("days", JSONArray().apply { t.days.forEach { put(it) } })
                    if (t.dateIso.isNotEmpty()) put("date", t.dateIso)
                    if (t.profile.isNotEmpty()) put("profile", t.profile)
                    if (t.lastResult.isNotEmpty()) put("lastResult", t.lastResult)
                    if (t.lastRun > 0) put("lastRun", t.lastRun)
                    if (t.intervalMin > 0) put("interval", t.intervalMin)
                    if (t.anchorMs > 0) put("anchor", t.anchorMs)
                })
            }
            prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        } catch (e: Throwable) {
            // 存档失败不打断使用，下次编辑会再试
        }
    }

    fun upsert(ctx: Context, item: Item) {
        val cur = load(ctx).toMutableList()
        val i = cur.indexOfFirst { it.id == item.id }
        if (i >= 0) cur[i] = item else cur.add(item)
        save(ctx, cur)
    }

    fun remove(ctx: Context, id: String) = save(ctx, load(ctx).filter { it.id != id })

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        get(ctx, id)?.let { upsert(ctx, it.copy(enabled = on)) }
    }

    /** 触发时刻落账（lastRun=触发那一刻）；队列收尾用 setResult 只改结果文本，保留触发时刻 */
    fun recordTrigger(ctx: Context, item: Item) {
        upsert(ctx, item.copy(lastRun = System.currentTimeMillis(), lastResult = "已触发"))
    }

    fun setResult(ctx: Context, id: String, result: String) {
        get(ctx, id)?.let { upsert(ctx, it.copy(lastResult = result)) }
    }

    private fun toIntSet(arr: JSONArray?): Set<Int> {
        if (arr == null) return emptySet()
        val out = LinkedHashSet<Int>()
        for (i in 0 until arr.length()) out.add(arr.optInt(i))
        return out
    }
}
