package com.maawh.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * 引擎日志（`files/maa_logs/maafw.log`）的解析与归类 —— 回答"任务为什么失败"。
 *
 * 为什么读日志而不是收引擎事件：`MaaBridge` 只绑了轮询式 API（MaaTaskerWait 那种），
 * 没有注册引擎的通知/回调接口，所以逐节点的识别与超时只能从引擎自己写的日志里读。
 * 只读、不改手机上的任何文件。
 *
 * 每条日志翻译成一句人话，并标出**性质**：【识别失败】/【超时】/【节点失败】/
 * 【动作失败】/【引擎错误】/【任务包校验失败】。认不出来的行返回 null（引擎日志格式
 * 跨版本会变，绝不因为解析不了就报错）。
 */
object EngineLog {

    enum class Kind(val label: String, val level: LogLevel) {
        RECOG_FAIL("识别失败", LogLevel.WRN),
        TIMEOUT("超时", LogLevel.WRN),
        NODE_FAIL("节点失败", LogLevel.ERR),
        ACTION_FAIL("动作失败", LogLevel.ERR),
        ENGINE_ERR("引擎错误", LogLevel.ERR),
        PACK_FAIL("任务包校验失败", LogLevel.ERR),
    }

    class Ev(val kind: Kind, val node: String, val text: String) {
        /** 折叠键：同一种错 + 同一个节点，连续重复只提示一次 */
        val key: String get() = "${kind.name}|$node"
        val line: String get() = "【${kind.label}】$text"
    }

    // ★ 注意：Java 的 Pattern 不吃裸 `]`（Python 的 re 吃）—— 方括号一律写 \\] ，
    //   否则 object 初始化时抛 PatternSyntaxException → ExceptionInInitializerError，
    //   整个解析器直接失效（踩过一次）。
    private val RE_MSG = Pattern.compile("\\[msg=([A-Za-z.]+)\\]\\s*\\[details=(\\{.*\\})\\]\\s*$")
    private val RE_TIMEOUT = Pattern.compile(
        "Task timeout \\[pretask\\.name=([^\\]]+)\\]" +
            " \\[duration_since\\(start_clock\\)=(-?\\d+)ms\\]" +
            " \\[pretask\\.reco_timeout=(-?\\d+)ms\\]")
    private val RE_ERR = Pattern.compile(
        "\\[ERR\\]\\[Px\\d+\\]\\[Tx\\d+\\]\\[([A-Za-z_0-9]+)\\.cpp\\]\\[L\\d+\\]\\[[^\\]]*\\]\\s*(.*)$")

    /** App 自己传参的老毛病：每次运行都会出现，跟任务成败无关，别当故障吓人 */
    private val NOISE = listOf("invalid value size")

    /**
     * 解析一行引擎日志。`bundleDir` 用来把节点定义（模板/期望文字/阈值/ROI）补进说明。
     * 不是"错误行"就返回 null。
     */
    fun parse(line: String, bundleDir: File? = null): Ev? {
        if (line.isBlank()) return null

        // ① Task timeout：节点等它的"下一个"等超时了 —— 报的是【上一个】节点名
        RE_TIMEOUT.matcher(line).let { m ->
            if (m.find()) {
                val node = m.group(1) ?: ""
                val elapsed = m.group(2)?.toLongOrNull() ?: -1
                val limit = m.group(3)?.toLongOrNull() ?: -1
                return Ev(Kind.TIMEOUT, node,
                    "「$node」等它的下一个节点等超时了：等了 ${elapsed}ms，上限 ${limit}ms"
                        + " —— 也就是说下一个节点一直没被识别到" + hintFor(bundleDir, node))
            }
        }

        // ② 事件行（!!!OnEventNotify!!! [msg=Node.xxx] [details={...}]）
        RE_MSG.matcher(line).let { m ->
            if (m.find()) {
                val msg = m.group(1) ?: ""
                val details = try {
                    JSONObject(m.group(2) ?: "{}")
                } catch (_: Throwable) {
                    JSONObject()
                }
                val name = details.optString("name", "")
                return when (msg) {
                    "Node.Recognition.Failed" -> Ev(Kind.RECOG_FAIL, name,
                        "「$name」没识别到：${recoSummary(details)}"
                            + hintFor(bundleDir, name))
                    "Node.PipelineNode.Failed" -> Ev(Kind.NODE_FAIL, name, "「$name」失败")
                    "Node.Action.Failed" -> Ev(Kind.ACTION_FAIL, name, "「$name」的动作执行失败")
                    "Resource.Loading.Failed" -> Ev(Kind.PACK_FAIL, "",
                        "任务包加载失败 —— 引擎会拒绝【整个包】，手机上所有任务都跑不起来")
                    else -> null     // NextList.Failed / *.Starting / *.Succeeded 不逐条打（太吵）
                }
            }
        }

        // ③ 引擎自己的 ERR 行（截图失败、控制器报错、校验失败…）
        RE_ERR.matcher(line).let { m ->
            if (m.find()) {
                val src = m.group(1) ?: ""
                val text = (m.group(2) ?: "").trim()
                if (NOISE.any { text.contains(it) }) {
                    return Ev(Kind.ENGINE_ERR, "", "已知无害噪声（App 传参长度问题，与任务无关）：$text")
                }
                // 任务包校验类：这几条会让【整包】加载失败，最要紧
                for (kw in listOf("check_all_validity failed", "regex invalid",
                                  "Invalid next node name", "load_bundle failed")) {
                    if (text.contains(kw)) {
                        return Ev(Kind.PACK_FAIL, nodeOf(text),
                            "任务包没通过校验（$kw）：$text —— 整包会被拒绝，所有任务都跑不起来")
                    }
                }
                return Ev(Kind.ENGINE_ERR, "", "$src: $text")
            }
        }
        return null
    }

    /** 识别失败的说明：模板匹配给最高分，OCR 给本 ROI 里认出来的文字 */
    private fun recoSummary(details: JSONObject): String {
        val reco = details.optJSONObject("reco_details") ?: return "（引擎没给细节）"
        val algo = reco.optString("algorithm", "?")
        val all = reco.optJSONObject("detail")?.optJSONArray("all")
        if (all == null || all.length() == 0) return "$algo 没命中"
        val rows = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
        val best = rows.filter { it.has("score") }
            .maxByOrNull { it.optDouble("score", 0.0) }
        val head = when (algo) {
            "OCR" -> {
                val texts = rows.filter { it.has("text") }
                    .sortedByDescending { it.optDouble("score", 0.0) }
                    .take(3)
                    .joinToString("、") {
                        "「${it.optString("text")}」${fmt(it.optDouble("score", 0.0))}"
                    }
                if (texts.isBlank()) "OCR 什么文字都没读到"
                else "OCR 没读到期望文字；本区域认出来的是 $texts"
            }
            else -> if (best != null) "$algo 最高只有 ${fmt(best.optDouble("score", 0.0))}"
                    else "$algo 没命中"
        }
        return head
    }

    private fun nodeOf(text: String): String {
        val m = Pattern.compile("\\[name=([^\\]]+)\\]").matcher(text)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.3f", v)

    // ==================================================================
    // 节点定义（把"该认得什么、阈值多少"补进日志，光看节点名没法判断）
    // ==================================================================

    private var index: Map<String, JSONObject>? = null
    private var indexDir: String = ""

    /** 节点名 → 生成物里的定义索引（按需构建一次；任务包的 pipeline 就几百个节点） */
    private fun nodes(bundleDir: File?): Map<String, JSONObject> {
        if (bundleDir == null) return emptyMap()
        val dir = File(bundleDir, "pipeline")
        val cached = index
        if (cached != null && indexDir == dir.absolutePath) return cached
        val m = HashMap<String, JSONObject>()
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            try {
                val o = JSONObject(f.readText(Charsets.UTF_8))
                for (k in o.keys()) {
                    if (k.startsWith("$")) continue
                    o.optJSONObject(k)?.let { m[k] = it }
                }
            } catch (_: Throwable) {
                // 单个文件读坏了不影响其它
            }
        }
        index = m
        indexDir = dir.absolutePath
        return m
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it)?.takeIf { s -> s.isNotBlank() } }

    /** 这个节点在生成物里是"怎么认"的：模板/期望文字/阈值/ROI/等待上限 */
    fun hintFor(bundleDir: File?, node: String): String {
        if (node.isBlank()) return ""
        val nd = nodes(bundleDir)[node] ?: return ""
        val parts = ArrayList<String>()
        nd.optString("template").takeIf { it.isNotBlank() }?.let { parts += "模板 $it" }
        nd.optJSONArray("expected")?.toStringList()?.takeIf { it.isNotEmpty() }
            ?.let { parts += "期望「${it.joinToString("|")}」" }
        nd.optDouble("threshold", -1.0).takeIf { it > 0 }?.let { parts += "阈值 ${fmt(it)}" }
        nd.optJSONArray("roi")?.let { if (it.length() == 4) parts += "ROI ${it.toStringList().joinToString(",")}" }
        nd.optString("node", "").takeIf { it.isNotBlank() }?.let { parts += "公共节点 $it" }
        nd.optInt("timeout", -1).takeIf { it > 0 }?.let { parts += "本节点等待上限 ${it}ms" }
        return if (parts.isEmpty()) "" else "（${parts.joinToString("，")}）"
    }
}
