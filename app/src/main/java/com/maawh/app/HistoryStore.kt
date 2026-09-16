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

    /** 一条队列运行记录 */
    data class Entry(
        val time: String,          // 队列开始时刻 "09-16 07:44"
        val planCount: Int,        // 计划任务数
        val failed: List<String>,  // 失败任务名；空 + 未停止 = 全部成功
        val stopped: Boolean,      // 用户主动停止 / 队列异常中断
        val costText: String       // "44.9s"
    ) {
        val ok: Boolean get() = !stopped && failed.isEmpty()

        /** 一行摘要，如 `09-16 07:44  ✓ 5项 全部成功 · 44.9s` */
        fun line(): String = buildString {
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
            Entry(
                time = o.optString("time"),
                planCount = o.optInt("count"),
                failed = fails,
                stopped = o.optBoolean("stopped"),
                costText = o.optString("cost")
            )
        }
    } catch (e: Throwable) {
        emptyList()
    }

    /** 弹窗展示用文本（最近的在前） */
    fun summary(ctx: Context, max: Int = 40): String {
        val list = load(ctx).take(max)
        return if (list.isEmpty()) "暂无历史记录" else list.joinToString("\n") { it.line() }
    }
}
