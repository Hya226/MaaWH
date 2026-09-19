package com.maawh.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话日志落盘：每次队列/抓卡运行的日志写成一个独立文件（files/logs/log_时间.log）。
 * begin → append(每行) → end；没有活动会话时 append 是空操作。
 * 历史日志页（HistoryActivity）按文件列表浏览/删除/清理。
 */
object RunLogStore {

    private const val DIR = "logs"
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var file: File? = null

    @Synchronized
    fun begin(ctx: Context, label: String, taskCount: Int) {
        val d = File(ctx.filesDir, DIR).apply { mkdirs() }
        val now = Date()
        val f = File(d, "log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".log")
        file = f
        val head = buildString {
            append("=== $label")
            if (taskCount >= 0) append(" · $taskCount 个任务")
            append(" · ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now))
            append(" ===")
        }
        runCatching { f.appendText(head + "\n") }
    }

    /** 追加一行；无活动会话时空操作。levelText 直接用日志级别的名字 */
    @Synchronized
    fun append(levelText: String, msg: String) {
        val f = file ?: return
        runCatching {
            f.appendText("${timeFmt.format(Date())} $levelText $msg\n")
        }
    }

    /** 结束本次会话（写收尾行）。summary 传空则只写结束标记 */
    @Synchronized
    fun end(summary: String) {
        val f = file ?: return
        file = null
        runCatching {
            f.appendText(if (summary.isBlank()) "=== 结束 ===\n" else "=== 结束：$summary ===\n")
        }
    }

    /** 全部会话日志文件，新→旧 */
    fun list(ctx: Context): List<File> =
        File(ctx.filesDir, DIR)
            .listFiles { f -> f.isFile && f.name.startsWith("log_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /** 删除单个日志文件 */
    fun delete(f: File): Boolean = f.delete()

    /** 清理 30 天前的日志，返回删除个数 */
    fun purgeOlderThan30Days(ctx: Context): Int {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        var n = 0
        list(ctx).forEach { if (it.lastModified() < cutoff && it.delete()) n++ }
        return n
    }

    /** 文件名 → 展示时间 "2026-09-18 16:39:27" */
    fun displayTime(f: File): String {
        val m = Regex("log_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})").find(f.name)
            ?: return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(f.lastModified()))
        val (y, mo, d, h, mi, s) = m.destructured
        return "$y-$mo-$d $h:$mi:$s"
    }
}
