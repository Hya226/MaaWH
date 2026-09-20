package com.maawh.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务队列「配置」的持久化（对标 MAA-Meow 的配置管理）。
 *
 * 一套配置 = 一整套主队列：任务顺序、勾选、option 取值（次数/角色/开关）。
 * 额外队列与快捷选项（静音/跑完关游戏）是 App 级状态，不随配置走。
 *
 * 存的是按任务 name 的取值而不是队列快照，恢复时逐条与当前 interface.json 核对：
 * 清单里已删除的任务丢弃、清单新增的任务用默认值追加、值已不在该 option 的 cases 里就回默认，
 * 所以升级任务包（新增任务/改 option）后，旧配置不会把过期参数带进 pipeline_override。
 */
object QueueStore {

    private const val PREF = "maawh_queue"
    private const val KEY = "state"
    private const val SCHEMA = 2
    /** schema 1 是单队列存档，迁移成这个名字的配置 */
    private const val MIGRATED_NAME = "默认"

    /** 一个任务在配置里的取值 */
    class SavedTask(
        val name: String,
        val entry: String,
        val enabled: Boolean,
        val caseOf: Map<String, String>,
        val inputOf: Map<String, String>
    )

    /** 一套配置（命名 + 主队列内容）；main 为空 = 空存档，恢复时全部任务取清单默认值 */
    class Profile(val name: String, val main: List<SavedTask>)

    /** 整份存档 */
    class State(
        val pack: String,
        val manifestVersion: String,
        val active: String,
        val profiles: List<Profile>,
        val tools: List<SavedTask>,
        val tab: String,
        val mute: Boolean,
        val closeAfter: Boolean,
        /**
         * 任务归属：任务名 → "main"（一键长草）/ "tools"（额外队列）。
         * 清单的 group 只决定**默认**归属；用户手动挪过位置的任务记在这里，
         * 否则每次开机又会被 group 拉回原处（「调试完转正式任务」就靠它）。
         * 老存档没有这个字段 → 空表 → 全部按 group 默认。
         */
        val home: Map<String, String> = emptyMap(),
        /** 「后台运行时自动画中画」开关（App 级状态）；老存档缺字段默认开 */
        val pipOn: Boolean = false,
        /**
         * 「游戏启动后关闭游戏声音」：启动任务/进虚拟屏把游戏跑起来时自动静音，
         * 游戏还挂在虚拟屏里就保持。与手动「关闭游戏声音」是两个独立开关。
         * 老存档缺字段默认关。
         */
        val autoMute: Boolean = false
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 读存档；没有存档 / 版本不认识 / 损坏 → null，宿主回退清单默认队列 */
    fun load(ctx: Context): State? {
        return try {
            val raw = prefs(ctx).getString(KEY, null) ?: return null
            val root = JSONObject(raw)
            when (root.optInt("schema", 0)) {
                SCHEMA -> parse(root)
                1 -> migrateV1(root)
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    fun save(ctx: Context, state: State) {
        try {
            prefs(ctx).edit().putString(KEY, toJson(state).toString()).apply()
        } catch (e: Throwable) {
            // 存档失败不打断使用，下次编辑会再试
        }
    }

    fun clear(ctx: Context) {
        try {
            prefs(ctx).edit().remove(KEY).apply()
        } catch (e: Throwable) {
        }
    }

    // ==================================================================
    // JSON 读写
    // ==================================================================

    private fun parse(root: JSONObject): State {
        val profiles = ArrayList<Profile>()
        root.optJSONArray("profiles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                if (name.isBlank()) continue
                profiles.add(Profile(name, parseList(o.optJSONArray("main"))))
            }
        }
        return State(
            pack = root.optString("pack", ""),
            manifestVersion = root.optString("manifestVersion", ""),
            active = root.optString("active", ""),
            profiles = profiles,
            tools = parseList(root.optJSONArray("tools")),
            tab = root.optString("tab", "oneclick"),
            mute = root.optBoolean("mute", false),
            closeAfter = root.optBoolean("closeAfter", false),
            home = toMap(root.optJSONObject("home")),
            pipOn = root.optBoolean("pipOn", false),
            autoMute = root.optBoolean("autoMute", false)
        )
    }

    /** schema 1（单份队列存档）→ 一个名为「默认」的配置，已装机的老用户升级不丢编辑 */
    private fun migrateV1(root: JSONObject) = State(
        pack = root.optString("pack", ""),
        manifestVersion = root.optString("manifestVersion", ""),
        active = MIGRATED_NAME,
        profiles = listOf(Profile(MIGRATED_NAME, parseList(root.optJSONArray("main")))),
        tools = parseList(root.optJSONArray("tools")),
        tab = if (root.optBoolean("tabTools", false)) "tools" else "oneclick",
        mute = root.optBoolean("mute", false),
        closeAfter = root.optBoolean("closeAfter", false)
    )

    private fun parseList(arr: JSONArray?): List<SavedTask> {
        if (arr == null) return emptyList()
        val out = ArrayList<SavedTask>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            if (name.isBlank()) continue
            out.add(
                SavedTask(
                    name = name,
                    entry = o.optString("entry", name),
                    enabled = o.optBoolean("enabled", true),
                    caseOf = toMap(o.optJSONObject("case")),
                    inputOf = toMap(o.optJSONObject("input"))
                )
            )
        }
        return out
    }

    private fun toMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = HashMap<String, String>()
        for (k in obj.keys()) out[k] = obj.optString(k, "")
        return out
    }

    private fun toJson(state: State) = JSONObject().apply {
        put("schema", SCHEMA)
        put("pack", state.pack)
        put("manifestVersion", state.manifestVersion)
        put("active", state.active)
        put("tab", state.tab)
        put("mute", state.mute)
        put("closeAfter", state.closeAfter)
        put("autoMute", state.autoMute)
        put("pipOn", state.pipOn)
        put("profiles", JSONArray().apply {
            state.profiles.forEach { p ->
                put(JSONObject().apply {
                    put("name", p.name)
                    put("main", listJson(p.main))
                })
            }
        })
        put("tools", listJson(state.tools))
        if (state.home.isNotEmpty()) put("home", mapJson(state.home))
    }

    private fun listJson(list: List<SavedTask>) = JSONArray().apply {
        list.forEach { t ->
            put(JSONObject().apply {
                put("name", t.name)
                put("entry", t.entry)
                put("enabled", t.enabled)
                if (t.caseOf.isNotEmpty()) put("case", mapJson(t.caseOf))
                if (t.inputOf.isNotEmpty()) put("input", mapJson(t.inputOf))
            })
        }
    }

    private fun mapJson(m: Map<String, String>) = JSONObject().apply {
        m.forEach { (k, v) -> put(k, v) }
    }
}
