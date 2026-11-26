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
 *
 * json 里同时存上一份 counts（"prev"）——【仅看变化】与物品卡片红色增减量
 * （+400 / -3）需要「本次 vs 上次」的逐项对比，链条 = 每次保存把当时的旧值留档。
 */
object WarehouseStore {
    const val CAT_RESOURCE = "resource"
    const val CAT_BADGE = "badge"
    const val CAT_TICKET = "ticket"

    data class Snapshot(
        val scannedAt: String,
        /** 数量显示串（保留「万」，如 "3.99万"）；"" = 数量识别失败（x?）。旧版 Int 值读入自动转串 */
        val counts: LinkedHashMap<String, String>,
        /** 上一份的数量串（无上一份 = 空 map）——增减量/仅看变化的对比基准 */
        val prevCounts: Map<String, String>,
        /** 与上一份的物品差异（种类增减或数量变化），首份为 0 */
        val changedFromPrev: Int
    )

    private fun file(ctx: Context, cat: String) = File(ctx.filesDir, "warehouse/${cat}_scan.json")

    /** 读一个 counts json 对象 → 有序表（旧版 Int 值自动转串，-1 视为识别失败空串） */
    private fun readCounts(o: JSONObject?): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        if (o == null) return m
        o.keys().asSequence().sorted().forEach { k ->
            m[k] = o.optString(k, "").let { if (it == "-1") "" else it }
        }
        return m
    }

    fun load(ctx: Context, cat: String): Snapshot? = runCatching {
        val f = file(ctx, cat)
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        val counts = readCounts(o.optJSONObject("counts"))
        val prev = readCounts(o.optJSONObject("prev"))
        Snapshot(o.optString("scannedAt"), counts, prev, o.optInt("changedFromPrev", 0))
    }.getOrNull()

    fun catName(cat: String): String = when (cat) {
        CAT_BADGE -> "徽章"
        CAT_TICKET -> "票券"
        else -> "资源"
    }

    /** 保存：当场与上一份对比差异条数，并把旧值留档为 prev（供增减量/仅看变化） */
    fun save(ctx: Context, cat: String, counts: Map<String, String>): Snapshot {
        val prev = load(ctx, cat)
        val prevCounts = prev?.counts ?: LinkedHashMap()
        val changed = (prevCounts.keys + counts.keys)
            .count { prevCounts[it] != counts[it] }
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
            if (prevCounts.isNotEmpty()) {
                val p = JSONObject()
                prevCounts.forEach { (k, v) -> p.put(k, v) }
                o.put("prev", p)
            }
            val tmp = File(dir, "${cat}_scan.json.tmp")
            tmp.writeText(o.toString(2))
            if (!tmp.renameTo(file(ctx, cat))) {
                file(ctx, cat).writeText(o.toString(2))
                tmp.delete()
            }
        }
        val m = LinkedHashMap<String, String>()
        counts.entries.sortedBy { it.key }.forEach { (k, v) -> m[k] = v }
        return Snapshot(ts, m, prevCounts, changed)
    }

    /** 数量显示串 → 数值（「3.99万」=39900；""/无法解析 = null）。与识别侧 qtyKey 同口径 */
    fun qtyValue(s: String): Long? {
        if (s.isBlank()) return null
        val wan = s.contains('万')
        val body = s.replace("万", "")
        val dot = body.indexOf('.')
        val digits = body.replace(".", "")
        val d = digits.toLongOrNull() ?: return null
        val frac = when {
            wan && dot < 0 -> 2
            dot >= 0 -> body.length - dot - 1
            else -> 0
        }
        var scale = 10000L
        repeat(frac) { scale /= 10 }
        return d * scale
    }

    /** 数值 → 紧凑显示：≥1万 用「X.XX万」（去尾 0），否则纯数字 */
    fun fmtQty(v: Long): String {
        if (v < 10000 && v > -10000) return v.toString()
        val neg = v < 0
        val wan = kotlin.math.abs(v) / 10000.0
        var s = String.format(Locale.US, "%.2f", wan)
        if (s.endsWith("00")) s = s.substring(0, s.length - 3)
        else if (s.endsWith("0")) s = s.substring(0, s.length - 1)
        return (if (neg) "-" else "") + s + "万"
    }
}
