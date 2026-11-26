package com.maawh.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

/**
 * 智能刷材料：宿主编排器（对标 M9A「智能均衡刷材料」的库存保持模式，引擎不参与主循环）。
 *
 * 决策链：目标库存（仓库识别结果页 → 目标配置 → targets.json）
 * → 仓库缓存（三仓 scan_<分类>.json 合并）
 * → 材料关卡目录（MaterialCatalog，关卡掉落组合表）
 * → 挑覆盖缺口最多的关卡 → ceil(缺口/掉落) = 次数（单次任务上限 9 局，剩余缺口
 *   下轮继续）→ 动态构造 pipeline_override 跑 VF_刷活动关/VF_清体力/VF_实验室
 * → 本地库存扣账 → 全部候选处理完后重算缺口，直到全部达标 / 刷关失败（体力不足等）
 *   / 轮数上限。
 */
class MaterialKeepRunner(
    private val context: Context,
    private val whmxDir: File,
    private val logDir: File,
    private val selection: TaskPack.Selection,
    private val onLog: (String, LogLevel) -> Unit,
    private val onRunState: (String) -> Unit,
    private val isStopped: () -> Boolean
) {

    // ---------------- 编排主循环 ----------------

    fun run(): Boolean {
        val started = System.currentTimeMillis()
        val catalog = MaterialCatalog.ensure(context)
        if (catalog.isEmpty()) {
            onLog("材料目录为空——assets/warehouse/material_catalog.json 缺失或格式错误", LogLevel.ERR)
            return false
        }
        val targets = MaterialCatalog.loadTargets(context)
        if (targets.isEmpty()) {
            onLog("未设置任何目标数量（仓库识别结果页 → 目标配置），跳过刷取", LogLevel.WRN)
            return true
        }
        val covered = catalog.flatMap { it.drop.keys }.toSet()
        val unplannable = targets.keys.filter { it !in covered }
        if (unplannable.isNotEmpty()) {
            onLog("以下目标材料目录未覆盖（无刷取关卡），跳过：${unplannable.joinToString("、")}", LogLevel.WRN)
        }
        val inventory = MaterialCatalog.loadInventory(context).toMutableMap()
        val deficits = HashMap<String, Long>()
        for ((name, target) in targets) {
            val have = inventory[name]
            if (have == null) {
                onLog("$name 没有库存读数（先跑一次仓库识别），本轮不刷它", LogLevel.WRN)
                continue
            }
            val d = target - have
            if (d > 0) deficits[name] = d
        }
        if (deficits.isEmpty()) {
            onLog("目标材料均已达标，无需刷取", LogLevel.SUCCESS)
            return true
        }
        onLog(
            "智能刷材料开始：目标 ${targets.size} 种，有缺口 ${deficits.size} 种（" +
                deficits.entries.sortedByDescending { it.value }.take(5)
                    .joinToString("、") { "${it.key} 缺 ${it.value}" } + "）",
            LogLevel.INFO
        )

        // 体力预算：任务编辑里「刷取体力上限」（空/0 = 不限），逐轮累计达到即停
        val budget = selection.input("刷取体力上限", "").trim().toLongOrNull() ?: 0L
        var spent = 0L
        var rounds = 0
        var stopReason: String? = null
        while (true) {
            if (isStopped()) {
                stopReason = "已停止"
                break
            }
            if (rounds >= 30) {
                stopReason = "已达轮数上限 30"
                break
            }
            // 缺口重算（本地缓存随每轮刷取扣账）
            for ((name, target) in targets) {
                inventory[name]?.let {
                    val d = target - it
                    if (d > 0) deficits[name] = d else deficits.remove(name)
                }
            }
            if (deficits.isEmpty()) {
                onLog("全部目标已达标 ✓", LogLevel.SUCCESS)
                break
            }
            // 挑关卡：覆盖缺口材料数最多 → 覆盖缺口总量最大 → 目录顺序
            val picked = catalog.map { e ->
                val defs = e.drop.keys.mapNotNull { deficits[it] }
                Triple(e, defs.size, defs.sum())
            }.filter { it.second > 0 }
                .maxWithOrNull(compareBy({ it.second }, { it.third }, { -it.first.index }))
                ?.first
            if (picked == null) {
                stopReason = "剩余缺口没有目录关卡可覆盖"
                break
            }
            var runs = picked.drop.mapNotNull { (m, per) ->
                deficits[m]?.let { ceil(it.toDouble() / per).toInt() }
            }.maxOrNull() ?: 1
            runs = runs.coerceIn(1, 9)
            if (budget > 0) {
                if (spent + picked.cost > budget) {
                    stopReason = "已达体力预算 $budget（已消耗 $spent）"
                    break
                }
                runs = runs.coerceAtMost(((budget - spent) / picked.cost).toInt())
            }
            val coveredNames = picked.drop.keys.filter { deficits.containsKey(it) }
            onLog(
                "第 ${rounds + 1} 轮：${taskDesc(picked)} ×$runs（体力约 ${picked.cost * runs}" +
                    (if (budget > 0) "，预算已用 $spent/$budget" else "") + "），补 $coveredNames",
                LogLevel.INFO
            )
            onRunState("智能刷材料：第 ${rounds + 1} 轮 ${taskDesc(picked)} ×$runs")
            val ok = MaaBridge.runTask(whmxDir, picked.task, logDir, buildOverride(picked, runs)) { msg ->
                onLog(msg, LogLevel.TRACE)
            }
            if (!ok) {
                stopReason = "刷关任务失败（体力不足或导航失败）——体力道具暂不支持自动使用"
                break
            }
            for ((m, per) in picked.drop) {
                inventory[m] = (inventory[m] ?: 0L) + per * runs
            }
            spent += picked.cost * runs
            rounds++
        }

        val remain = targets.mapNotNull { (name, target) ->
            inventory[name]?.let { target - it }?.takeIf { it > 0 }?.let { "$name 缺 $it" }
        }
        val elapsed = (System.currentTimeMillis() - started) / 1000
        val ok = remain.isEmpty() && stopReason == null
        onLog(
            when {
                remain.isEmpty() -> "✓ 智能刷材料完成：$rounds 轮 · 耗时 ${elapsed}s · 目标全部达标"
                else -> "智能刷材料结束：$rounds 轮 · 耗时 ${elapsed}s · 未达标 ${remain.size} 种（${remain.take(6).joinToString("、")}）—— $stopReason"
            },
            if (remain.isEmpty()) LogLevel.SUCCESS else LogLevel.WRN
        )
        return ok
    }

    private fun taskDesc(e: MaterialCatalog.CatalogEntry): String = when (e.task) {
        "VF_刷活动关" -> "活动关 ${e.params["关卡名"]}"
        "VF_清体力" -> "考核 ${e.params["类型"]}-${e.params["关卡"]}"
        "VF_实验室" -> "实验室"
        else -> e.task
    }

    /** 按任务类型构造 pipeline_override（节点名与 interface.json 各 option cases 同源） */
    private fun buildOverride(e: MaterialCatalog.CatalogEntry, runs: Int): String {
        val ov = JSONObject()
        when (e.task) {
            "VF_刷活动关" -> {
                val lv = e.params["关卡名"] ?: "01"
                ov.put("VF_刷活动关_04", JSONObject().put("expected", lv))
                ov.put("VF_刷活动关_03_Hit", JSONObject().put("expected", lv))
                ov.put("VF_刷活动关_07", JSONObject().put("repeat", runs))
            }
            "VF_清体力" -> {
                ov.put("VF_清体力_25_Hit", JSONObject().put("expected", "考核"))
                val t = e.params["类型"] ?: ""
                ov.put("VF_清体力_30_Hit", JSONObject().put("expected", t))
                ov.put("VF_清体力_32_Hit", JSONObject().put("expected", t))
                ov.put("VF_清体力_33_Hit", JSONObject().put("expected", e.params["关卡"] ?: "01"))
                ov.put("VF_清体力_07", JSONObject().put("repeat", runs))
            }
            "VF_实验室" -> {
                ov.put("VF_实验室_08", JSONObject().put("repeat", runs))
            }
        }
        return ov.toString()
    }
}
