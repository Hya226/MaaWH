package com.maawh.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 材料关卡目录 + 目标库存存取（纯 context 操作，UI 与编排器共用） */
object MaterialCatalog {

    data class CatalogEntry(
        val task: String,
        val params: Map<String, String>,
        val cost: Int,
        val expiring: Boolean,
        val drop: Map<String, Long>,
        val index: Int
    )

    /** assets/warehouse/material_catalog.json → files 同名文件（指纹释放，活动更替换文件即自动重建） */
    fun ensure(context: Context): List<CatalogEntry> {
        val dir = File(context.filesDir, "warehouse")
        dir.mkdirs()
        val stamp = File(dir, "material_catalog_fingerprint.txt")
        val dst = File(dir, "material_catalog.json")
        val fingerprint = runCatching {
            context.assets.open("warehouse/material_catalog.json").use { it.available().toString() }
        }.getOrDefault("-1")
        if (!dst.isFile || stamp.readText().trim() != fingerprint) {
            runCatching {
                context.assets.open("warehouse/material_catalog.json").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                stamp.writeText(fingerprint)
            }
        }
        val out = ArrayList<CatalogEntry>()
        runCatching {
            val arr = JSONArray(dst.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val params = HashMap<String, String>()
                o.optJSONObject("params")?.let { pj ->
                    pj.keys().forEach { k -> params[k] = pj.optString(k) }
                }
                val drop = HashMap<String, Long>()
                o.optJSONObject("drop")?.let { dj ->
                    dj.keys().forEach { k -> drop[k] = dj.optLong(k, 1L) }
                }
                out.add(
                    CatalogEntry(
                        o.optString("task"),
                        params,
                        o.optInt("cost", 0),
                        o.optBoolean("expiring", false),
                        drop,
                        i
                    )
                )
            }
        }
        return out
    }

    /** 目录覆盖的全部材料名 */
    fun coveredMaterials(context: Context): Set<String> =
        ensure(context).flatMap { it.drop.keys }.toSet()

    /** 目标库存：files/warehouse/targets.json {"分类": {"材料": 数量}}，0/负值视为未设置 */
    fun loadTargets(context: Context): Map<String, Long> {
        val out = HashMap<String, Long>()
        runCatching {
            val o = JSONObject(File(context.filesDir, "warehouse/targets.json").readText())
            o.keys().forEach { cat ->
                val c = o.optJSONObject(cat) ?: return@forEach
                c.keys().forEach { n ->
                    val v = c.optLong(n, 0L)
                    if (v > 0) out[n] = v
                }
            }
        }
        return out
    }

    fun saveTargets(context: Context, byCat: Map<String, Map<String, Long>>) {
        runCatching {
            val dir = File(context.filesDir, "warehouse")
            dir.mkdirs()
            val o = JSONObject()
            byCat.forEach { (cat, m) ->
                val c = JSONObject()
                m.forEach { (k, v) -> if (v > 0) c.put(k, v) }
                o.put(cat, c)
            }
            val tmp = File(dir, "targets.json.tmp")
            tmp.writeText(o.toString(2))
            if (!tmp.renameTo(File(dir, "targets.json"))) {
                File(dir, "targets.json").writeText(o.toString(2))
                tmp.delete()
            }
        }
    }

    /** 三仓识别缓存合并（同名材料以 resource 为准；读取失败的分仓静默跳过） */
    fun loadInventory(context: Context): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (cat in listOf("ticket", "badge", "resource")) {
            runCatching {
                val o = JSONObject(File(context.filesDir, "warehouse/${cat}_scan.json").readText())
                val c = o.optJSONObject("counts") ?: return@runCatching
                c.keys().forEach { n -> out[n] = c.optLong(n, out[n] ?: 0L) }
            }
        }
        return out
    }
}
