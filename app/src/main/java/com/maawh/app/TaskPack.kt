package com.maawh.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 任务包清单（interface.json）加载与参数化。
 *
 * 对标 MaaFramework ProjectInterface v2 与 MWA/MaaEnd 的做法：
 *  - 任务清单（task[]）声明 entry / option，宿主据此渲染队列与编辑面板，不再硬编码任务名
 *  - 参数（option）用 cases + pipeline_override 表达，选中值直接生成引擎的 pipeline_override
 *  - input 类型用 {占位符} 模板替换，避免宿主复制模板文件这类 hack
 *
 * 只实现本项目实际用到的子集：select / switch / input 三种 option。
 */
object TaskPack {

    /**
     * 将 APK 内置任务包（assets/whmx）释放到内部存储 files/taskpacks/whmx。
     *  - 首次安装：全量释放
     *  - version.txt 与内置版本不同（覆盖安装了新版 APK）：重新释放，
     *    但保留 pipeline/vf_*.json（流程编辑器同步的可视化流程优先于内置版本）
     *  - 版本相同：直接跳过（启动零开销）
     * 返回是否发生了释放。version.txt 在全部文件拷贝完成后才写入，中途失败下次会重试。
     */
    fun ensureBundledTaskpack(ctx: android.content.Context): Boolean {
        val assetsVer = try {
            ctx.assets.open("whmx/version.txt").bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            return false   // APK 未内置任务包
        }
        val dest = File(ctx.filesDir, "taskpacks/whmx")
        val marker = File(dest, "version.txt")
        if (marker.exists() && marker.readText().trim() == assetsVer) return false

        // 备份可视化流程（vf_*.json），释放后恢复
        val pipeDir = File(dest, "pipeline")
        val backup = File(ctx.filesDir, "taskpacks/_vf_backup")
        backup.deleteRecursively()
        backup.mkdirs()
        pipeDir.listFiles { f -> f.name.startsWith("vf_") && f.name.endsWith(".json") }
            ?.forEach { it.copyTo(File(backup, it.name), overwrite = true) }

        dest.deleteRecursively()
        dest.mkdirs()
        copyAssetsDir(ctx, "whmx", dest)

        backup.listFiles()?.forEach {
            File(pipeDir, it.name).parentFile?.mkdirs()
            it.copyTo(File(pipeDir, it.name), overwrite = true)
        }
        backup.deleteRecursively()
        marker.writeText(assetsVer)
        return true
    }

    private fun copyAssetsDir(ctx: android.content.Context, assetsPath: String, destDir: File) {
        for (child in ctx.assets.list(assetsPath) ?: return) {
            val childPath = "$assetsPath/$child"
            val isDir = (ctx.assets.list(childPath) ?: emptyArray()).isNotEmpty()
            if (isDir) {
                copyAssetsDir(ctx, childPath, File(destDir, child))   // 保留子目录结构
            } else {
                if (childPath.endsWith("version.txt")) return          // 完成标记最后写
                destDir.mkdirs()
                ctx.assets.open(childPath).use { input ->
                    File(destDir, child).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }


    /** 一个任务的声明 */
    data class TaskDef(
        val name: String,
        val label: String,
        val entry: String,
        val group: List<String> = emptyList(),
        val options: List<String> = emptyList(),
        val defaultCheck: Boolean = false,
        val description: String = ""
    )

    /** 一个 option 的声明 */
    data class OptionDef(
        val key: String,
        val type: String,          // select | switch | input
        val label: String,
        val defaultCase: String,
        val cases: List<CaseDef> = emptyList(),
        val inputs: List<InputDef> = emptyList(),
        /** input 类型的模板 override（含 {占位符}） */
        val pipelineOverride: JSONObject? = null
    )

    data class CaseDef(val name: String, val label: String, val override: JSONObject?)

    data class InputDef(
        val name: String,
        val label: String,
        val default: String,
        val pipelineType: String = "string",
        val verify: String? = null,
        val patternMsg: String? = null
    )

    /** 一个任务包的完整清单 */
    class Manifest(
        val name: String,
        val label: String,
        val version: String,
        val tasks: List<TaskDef>,
        val options: Map<String, OptionDef>
    ) {
        fun option(key: String): OptionDef? = options[key]
    }

    /** 解析 interface.json；失败返回 null（宿主回退到内置默认清单） */
    fun load(bundleDir: File): Manifest? = try {
        val f = File(bundleDir, "interface.json")
        if (!f.exists()) null
        else parse(JSONObject(f.readText(Charsets.UTF_8)))
    } catch (e: Throwable) {
        null
    }

    private fun parse(root: JSONObject): Manifest {
        val tasks = ArrayList<TaskDef>()
        root.optJSONArray("task")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                tasks.add(
                    TaskDef(
                        name = t.getString("name"),
                        label = t.optString("label", t.getString("name")),
                        entry = t.getString("entry"),
                        group = t.optJSONArray("group").toStringList(),
                        options = t.optJSONArray("option").toStringList(),
                        defaultCheck = t.optBoolean("default_check", false),
                        description = t.optString("description", "")
                    )
                )
            }
        }
        val options = LinkedHashMap<String, OptionDef>()
        root.optJSONObject("option")?.let { obj ->
            for (key in obj.keys()) {
                val o = obj.getJSONObject(key)
                val cases = ArrayList<CaseDef>()
                o.optJSONArray("cases")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONObject(i)
                        cases.add(
                            CaseDef(
                                name = c.getString("name"),
                                label = c.optString("label", c.getString("name")),
                                override = c.optJSONObject("pipeline_override")
                            )
                        )
                    }
                }
                val inputs = ArrayList<InputDef>()
                o.optJSONArray("inputs")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val v = arr.getJSONObject(i)
                        inputs.add(
                            InputDef(
                                name = v.getString("name"),
                                label = v.optString("label", v.getString("name")),
                                default = v.optString("default", ""),
                                pipelineType = v.optString("pipeline_type", "string"),
                                verify = v.optString("verify").ifBlank { null },
                                patternMsg = v.optString("pattern_msg").ifBlank { null }
                            )
                        )
                    }
                }
                options[key] = OptionDef(
                    key = key,
                    type = o.optString("type", "select"),
                    label = o.optString("label", key),
                    defaultCase = o.optString("default_case", cases.firstOrNull()?.name ?: ""),
                    cases = cases,
                    inputs = inputs,
                    pipelineOverride = o.optJSONObject("pipeline_override")
                )
            }
        }
        return Manifest(
            name = root.optString("name", "whmx"),
            label = root.optString("label", "物华弥新"),
            version = root.optString("version", ""),
            tasks = tasks,
            options = options
        )
    }

    // ==================================================================
    // 参数 → pipeline_override
    // ==================================================================

    /** 用户在某个任务上的参数取值：option key → 选中 case 名（select/switch）或输入值（input 字段名→值） */
    class Selection {
        val caseOf = HashMap<String, String>()
        val inputOf = HashMap<String, String>()

        fun get(key: String, def: String): String = caseOf[key] ?: def
        fun input(key: String, def: String): String = inputOf[key] ?: def
    }

    /**
     * 把某个任务的参数选择合并成一份 pipeline_override JSON 字符串。
     * 多个 option 的 override 按声明顺序深合并（后者覆盖前者同名字段）。
     */
    fun buildOverride(manifest: Manifest, task: TaskDef, sel: Selection): String {
        val merged = JSONObject()
        for (key in task.options) {
            val opt = manifest.option(key) ?: continue
            when (opt.type) {
                "select", "switch" -> {
                    val chosen = sel.get(key, opt.defaultCase)
                    val case = opt.cases.firstOrNull { it.name == chosen } ?: continue
                    case.override?.let { deepMerge(merged, it) }
                }
                "input" -> {
                    val tpl = opt.pipelineOverride ?: continue
                    // 用输入值替换 {占位符}，并按 pipeline_type 转换类型
                    val substituted = substitute(tpl, opt, sel)
                    deepMerge(merged, substituted)
                }
            }
        }
        return if (merged.length() == 0) "{}" else merged.toString()
    }

    /** 把模板里的 {名称} 替换为输入值，并按 pipeline_type 转成 int/bool */
    private fun substitute(tpl: JSONObject, opt: OptionDef, sel: Selection): JSONObject {
        val out = JSONObject()
        for (key in tpl.keys()) {
            val node = tpl.getJSONObject(key)
            val newNode = JSONObject()
            for (field in node.keys()) {
                val raw = node.get(field)
                if (raw is String && raw.contains("{")) {
                    newNode.put(field, renderValue(raw, opt, sel))
                } else {
                    newNode.put(field, raw)
                }
            }
            out.put(key, newNode)
        }
        return out
    }

    /** 渲染单个值：整串就是一个占位符时按类型转换，否则做字符串拼接 */
    private fun renderValue(template: String, opt: OptionDef, sel: Selection): Any {
        val full = Regex("^\\{(.+)\\}$").find(template)
        if (full != null) {
            val fieldName = full.groupValues[1]
            val def = opt.inputs.firstOrNull { it.name == fieldName }
            val value = sel.input(fieldName, def?.default ?: "")
            return when (def?.pipelineType) {
                "int" -> value.toIntOrNull() ?: 0
                "bool" -> value.toBoolean()
                else -> value
            }
        }
        // 混合文本：只做字符串替换
        var s = template
        for (inp in opt.inputs) {
            s = s.replace("{${inp.name}}", sel.input(inp.name, inp.default))
        }
        return s
    }

    /** 深合并：src 的字段覆盖 dst 同名字段（对象递归合并，其他类型直接覆盖） */
    private fun deepMerge(dst: JSONObject, src: JSONObject) {
        for (key in src.keys()) {
            val sv = src.get(key)
            val dv = dst.opt(key)
            if (sv is JSONObject && dv is JSONObject) deepMerge(dv, sv)
            else dst.put(key, sv)
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }
}
