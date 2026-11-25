package com.maawh.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 仓库识别结果持久化（对标 MAA 仓库页「上次同步」）：每个小仓库一份
 * files/warehouse/<cat>_scan.json。resource 恰好沿用既有的 resource_scan.json
 * 文件名——旧扫描数据直接兼容读入；徽章/票券录入后自动启用同名文件。
 */
object WarehouseStore {
    const val CAT_RESOURCE = "resource"
    const val CAT_BADGE = "badge"
    const val CAT_TICKET = "ticket"

    data class Snapshot(
        val scannedAt: String,
        val counts: LinkedHashMap<String, Int>,
        /** 与上一份的物品差异（种类增减或数量变化），首份为 0 */
        val changedFromPrev: Int
    )

    private fun file(ctx: Context, cat: String) = File(ctx.filesDir, "warehouse/${cat}_scan.json")

    fun load(ctx: Context, cat: String): Snapshot? = runCatching {
        val f = file(ctx, cat)
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        val c = o.optJSONObject("counts") ?: return null
        val m = LinkedHashMap<String, Int>()
        c.keys().asSequence().sorted().forEach { k -> m[k] = c.optInt(k, -1) }
        Snapshot(o.optString("scannedAt"), m, o.optInt("changedFromPrev", 0))
    }.getOrNull()

    fun catName(cat: String): String = when (cat) {
        CAT_BADGE -> "徽章"
        CAT_TICKET -> "票券"
        else -> "资源"
    }

    /** 保存并当场与上一份对比（差异条数写进 changedFromPrev，供「较上次 N 种有变化」显示） */
    fun save(ctx: Context, cat: String, counts: Map<String, Int>): Snapshot {
        val prev = load(ctx, cat)
        val changed = if (prev == null) 0 else (prev.counts.keys + counts.keys)
            .count { prev.counts[it] != counts[it] }
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        runCatching {
            val dir = File(ctx.filesDir, "warehouse")
            dir.mkdirs()
            val o = JSONObject()
            o.put("scannedAt", ts)
            o.put("changedFromPrev", changed)
            val c = JSONObject()
            counts.forEach { (k, v) -> c.put(k, v) }
            o.put("counts", c)
            val tmp = File(dir, "${cat}_scan.json.tmp")
            tmp.writeText(o.toString(2))
            if (!tmp.renameTo(file(ctx, cat))) {
                file(ctx, cat).writeText(o.toString(2))
                tmp.delete()
            }
        }
        val m = LinkedHashMap<String, Int>()
        counts.entries.sortedBy { it.key }.forEach { (k, v) -> m[k] = v }
        return Snapshot(ts, m, changed)
    }
}
