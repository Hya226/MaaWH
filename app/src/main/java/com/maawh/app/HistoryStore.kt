package com.maawh.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 队列运行历史的持久化（files/history.json，滚动保留最近 100 条）。
 *
 * 为什么不用日志页回溯：TaskLogView 是内存环形缓冲，一清空/一重启就没了；
 * 引擎 maafw.log 又太底层。历史记的是"队列级"一笔：什么时候跑的、几项、成没成、
 * 谁失败了、耗时多少——用户回看"昨天跑了什么"看这个就够了。
 */
object HistoryStore {

    private const val FILE = "history.json"
    private const val MAX = 100

    const val TYPE_QUEUE = "queue"
    /** 存档写盘失败/解析失败等持久化事件：跨会话可查（日志页内存态重启即清，历史不会） */
    const val TYPE_SAVE_ERROR = "save_error"

    /** 一条队列运行记录 */
    data class Entry(
        val time: String,          // 队列开始时刻 "09-16 07:44"
        val planCount: Int,        // 计划任务数
        val failed: List<String>,  // 失败任务名；空 + 未停止 = 全部成功
        val stopped: Boolean,      // 用户主动停止 / 队列异常中断
        val costText: String,      // "44.9s"
        /** 【OCR记录】的值：任务名 → (参数名 → 识别文本)。空 = 本队列没配记录节点 */
        val records: Map<String, Map<String, String>> = emptyMap(),
        val type: String = TYPE_QUEUE,
        /** type=save_error 时的事件描述 */
        val summary: String = ""
    ) {
        val ok: Boolean get() = !stopped && failed.isEmpty()

        /** 一行摘要，如 `09-16 07:44  ✓ 5项 全部成功 · 44.9s` */
        fun line(): String = when (type) {
            TYPE_SAVE_ERROR -> "$time  ⚠ $summary"
            else -> buildString {
                append(time).append("  ")
                append(if (ok) "✓" else if (stopped) "◼" else "✗")
                append(" ${planCount}项 ")
                append(
                    when {
                        ok -> "全部成功"
                        stopped -> "已停止"
                        else -> "失败：" + failed.joinToString("、")
                    }
                )
                append(" · ").append(costText)
            }
        }

        /** 记录的几行展示文本（每任务一行），没记录返回空列表 */
        fun recordLines(): List<String> = records.map { (task, recs) ->
            "  📋 $task：" + recs.entries.joinToString("、") { (k, v) ->
                if (v.isBlank()) "$k=未识别" else "$k=$v"
            }
        }
    }

    /** 追加一条（新记录在前）；写失败静默——历史只是辅助，不能影响任务 */
    fun append(ctx: Context, e: Entry) {
        synchronized(this) {
            try {
                val list = ArrayList(load(ctx))
                list.add(0, e)
                while (list.size > MAX) list.removeAt(list.size - 1)
                val arr = JSONArray()
                list.forEach { x ->
                    arr.put(JSONObject().apply {
                        put("time", x.time)
                        put("count", x.planCount)
                        put("failed", JSONArray(x.failed))
                        put("stopped", x.stopped)
                        put("cost", x.costText)
                        if (x.type != TYPE_QUEUE) put("type", x.type)
                        if (x.summary.isNotEmpty()) put("summary", x.summary)
                        if (x.records.isNotEmpty()) {
                            val recs = JSONObject()
                            for ((task, rec) in x.records) {
                                recs.put(task, JSONObject(rec))
                            }
                            put("records", recs)
                        }
                    })
                }
                File(ctx.filesDir, FILE).writeText(arr.toString())
            } catch (ignored: Throwable) {
            }
        }
    }

    fun load(ctx: Context): List<Entry> = try {
        val arr = JSONArray(File(ctx.filesDir, FILE).takeIf { it.isFile }?.readText() ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val fails = ArrayList<String>()
            val fa = o.optJSONArray("failed") ?: JSONArray()
            for (j in 0 until fa.length()) fails.add(fa.optString(j))
            val records = LinkedHashMap<String, Map<String, String>>()
            o.optJSONObject("records")?.let { recs ->
                for (task in recs.keys()) {
                    val rec = recs.optJSONObject(task) ?: continue
                    val m = LinkedHashMap<String, String>()
                    for (k in rec.keys()) m[k] = rec.optString(k)
                    records[task] = m
                }
            }
            Entry(
                time = o.optString("time"),
                planCount = o.optInt("count"),
                failed = fails,
                stopped = o.optBoolean("stopped"),
                costText = o.optString("cost"),
                records = records,
                type = o.optString("type", TYPE_QUEUE),
                summary = o.optString("summary")
            )
        }
    } catch (e: Throwable) {
        emptyList()
    }

    /** 弹窗展示用文本（最近的在前）；带记录的条目把记录行跟在摘要后 */
    fun summary(ctx: Context, max: Int = 40): String {
        val list = load(ctx).take(max)
        if (list.isEmpty()) return "暂无历史记录"
        return list.joinToString("\n") { e ->
            (listOf(e.line()) + e.recordLines()).joinToString("\n")
        }
    }

    private val timeFmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)

    /**
     * 记一条存档持久化事件（写盘失败/解析失败）。跨会话可查——日志页是内存环形缓冲，
     * 重启即清，「第二天才发现配置丢了」时只有历史能回答"当晚发生了什么"。
     * 去重：同一摘要 5 分钟内只记一条（保存高频路径连续失败时不刷屏）。
     * 注意物理边界：若失败原因是存储整体故障，本记录与存档同卷，多半也写不进去。
     */
    fun appendError(ctx: Context, summary: String) {
        synchronized(this) {
            try {
                val list = load(ctx)
                val last = list.firstOrNull()
                if (last != null && last.type == TYPE_SAVE_ERROR && last.summary == summary) {
                    val t = timeFmt.parse(last.time)
                    if (t != null && System.currentTimeMillis() - t.time < 5 * 60_000L) return
                }
                append(
                    ctx,
                    Entry(
                        time = timeFmt.format(java.util.Date()), planCount = 0,
                        failed = emptyList(), stopped = false, costText = "",
                        type = TYPE_SAVE_ERROR, summary = summary
                    )
                )
            } catch (ignored: Throwable) {
            }
        }
    }
}
