package com.maawh.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * 外勤见闻识别（小工具）：游戏停在【外勤见闻】列表页（虚拟屏内，用户预先手动进入），
 * 本类接管扫描循环。引擎不参与——纯只读 OCR 识别，与抽卡爬虫（GachaCrawler）同路线。
 *
 * 流程：抓帧等稳 → 裁左右两个标题框 OCR → 归一后对名单匹配（精确 → 编辑距离≤2 唯一
 * 最近邻）→ 命中置 1（内存 Set，任务结束丢弃，每次运行从全 0 开始）→ 点 (1161,352) 翻页。
 * 终止：本页两个名字与上一页完全一致累计 3 次（= 翻到底/点击失效）；兜底：连续 3 页
 * 识别不完整判 OCR/页面异常退出；MAX_PAGES 防死循环。
 * 结束输出名单中本轮没出现的条目（带器者括注）到日志区 + files/waiqin/missing.txt。
 *
 * 名单随包（assets/waiqin/roster.json，构建时由仓库 waiqin/ 同步）：运行时直读，
 * 覆盖升级——更新名单只需改仓库正本重编译装机，无本地副本无合并（与 names.json 相反）。
 * 名单匹配阈值依据：40 个标题两两编辑距离最小为 3，≤2 的纠错永不歧义。
 */
class WaiQinScan(
    private val ctx: Context,
    private val ocr: GachaOcr,
    private val onLog: (String, LogLevel) -> Unit,
    private val isStopped: () -> Boolean
) {

    class ScanException(msg: String) : Exception(msg)

    data class Entry(val name: String, val who: String) {
        /** 展示形式「见闻名（器者）」；无器者时只报名字 */
        fun display() = if (who.isEmpty()) name else "$name（$who）"
    }

    private data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    // ---- 几何（1280x720 虚拟屏帧，用户框定；翻页箭头右侧中部）----
    private val boxL = Box(140, 110, 493, 59)
    private val boxR = Box(654, 112, 488, 56)
    /** 两框包围盒：翻页后等画面稳定用 */
    private val boxesBand = Box(140, 106, 1002, 67)
    private val nextPageX = 1161
    private val nextPageY = 352

    private var roster: List<Entry> = emptyList()
    private var normNames: List<String> = emptyList()

    /** 与上一页完全一致累计到此即判翻到底 */
    private val matchesToEnd = 3
    /** 连续识别不完整页到此判异常 */
    private val badPagesToAbort = 3
    /** 40 条/每页 2 条 = 20 页 + 重合 3 页 + 余量 */
    private val maxPages = 30

    /**
     * 主入口（阻塞版）：QueueRunner 的阻塞上下文用。返回 true=正常扫完（重合 3 次
     * 终止）；false=用户停止。异常抛 ScanException。
     */
    fun scan(): Boolean = runBlocking { scanSuspend() }

    /** suspend 版：小工具面板的 lifecycleScope 协程用（同一段扫描逻辑） */
    suspend fun scanSuspend(): Boolean = scanLoop()

    private suspend fun scanLoop(): Boolean = withContext(Dispatchers.IO) {
        loadRoster()
        val seen = HashSet<Int>()
        onLog("外勤见闻识别：名单 ${roster.size} 条，开始扫描当前见闻列表…", LogLevel.INFO)

        var prevL = -1
        var prevR = -1
        var matchRun = 0
        var badRun = 0
        var pages = 0

        while (true) {
            coroutineContext.ensureActive()
            if (isStopped()) {
                onLog("外勤见闻识别：收到停止请求，中止扫描", LogLevel.WRN)
                return@withContext false
            }

            val frame = grabStable()
                ?: throw ScanException("抓帧失败：虚拟屏是否还活着？（先跑【启动】重建虚拟屏）")
            pages++
            if (pages > maxPages) {
                throw ScanException("翻页超过 $maxPages 页仍未终止，已中止（页面结构或翻页坐标是否变化？）")
            }

            val (li, lraw) = ocrBox(frame, boxL)
            val (ri, rraw) = ocrBox(frame, boxR)
            li?.let { seen.add(it) }
            ri?.let { seen.add(it) }

            when {
                li == null && ri == null -> {
                    if (seen.isEmpty()) {
                        throw ScanException(
                            "首页两框都匹配不到名单内见闻——请确认已停在【外勤见闻】列表页；" +
                                "若确认在页，则是名单缺新见闻（识别原文：左「$lraw」右「$rraw」）"
                        )
                    }
                    badRun++
                    if (badRun >= badPagesToAbort) {
                        throw ScanException("连续 $badRun 页无法识别，已中止（OCR 异常或页面异常）")
                    }
                    onLog("第 $pages 页两框都识别失败（左「$lraw」右「$rraw」），跳过继续（$badRun/$badPagesToAbort）", LogLevel.WRN)
                    prevL = -1; prevR = -1; matchRun = 0
                }
                li == null || ri == null -> {
                    // 框级处理：识别成功的框可靠（能对上名单），照常置 1；
                    // 但页不完整不能参与重合判定（另一框是否与上页相同不可知）
                    badRun++
                    val badSide = if (li == null) "左" else "右"
                    val raw = if (li == null) lraw else rraw
                    if (badRun >= badPagesToAbort) {
                        throw ScanException("连续 $badRun 页识别不完整（本页${badSide}框「$raw」），已中止（框位或名单是否需要更新？）")
                    }
                    onLog("第 $pages 页${badSide}框识别失败（「$raw」），本页不计重合，继续（$badRun/$badPagesToAbort）", LogLevel.WRN)
                    prevL = -1; prevR = -1; matchRun = 0
                }
                else -> {
                    badRun = 0
                    if (li == prevL && ri == prevR) {
                        matchRun++
                        onLog("第 $pages 页与上一页相同（${roster[li].name}、${roster[ri].name}），重合 $matchRun/$matchesToEnd", LogLevel.TRACE)
                    } else {
                        matchRun = 0
                        onLog("第 $pages 页：${roster[li].name}、${roster[ri].name}", LogLevel.INFO)
                    }
                    if (matchRun >= matchesToEnd) break
                    prevL = li; prevR = ri
                }
            }

            ShizukuShell.injectTapVD(nextPageX, nextPageY)
            delay(350)   // 让翻页动画先跑起来，grabStable 会等画面真正静止
        }

        report(seen)
        true
    }

    // ---------------- 名单与匹配 ----------------

    /** 随包名单直读 assets（无 files 副本，装包即最新） */
    private fun loadRoster() {
        val text = runCatching {
            ctx.assets.open("waiqin/roster.json").bufferedReader().readText()
        }.getOrElse { throw ScanException("名单读取失败：assets/waiqin/roster.json（$it）") }
        val arr = runCatching { JSONArray(text) }
            .getOrElse { throw ScanException("名单解析失败：assets/waiqin/roster.json 不是合法 JSON（$it）") }
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val n = o.optString("name").trim()
            if (n.isNotEmpty()) list.add(Entry(n, o.optString("who").trim()))
        }
        if (list.isEmpty()) throw ScanException("名单为空：assets/waiqin/roster.json 没有有效条目")
        roster = list
        normNames = list.map { normalize(it.name) }
    }

    /**
     * 归一：全角→半角、转小写、去空白与引号类字符（名单「大侠在民间」已去引号，
     * OCR 侧引号识别不稳，两侧都剔掉双保险）。
     */
    private fun normalize(s: String): String {
        val quotes = "\"\uFF02\u201C\u201D\u301D\u301E\'\u2018\u2019\u2032\u300C\u300D\u300E\u300F\u300A\u300B\u3010\u3011\uFF03#"
        val sb = StringBuilder(s.length)
        for (ch in s) {
            var c = ch
            val code = c.code
            if (code == 0x3000) c = ' '
            if (code in 0xFF01..0xFF5E) c = (code - 0xFEE0).toChar()
            if (c == ' ' || c in quotes) continue
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /** 裁标题框 → OCR → 名单匹配。返回 (名单下标或 null, 识别原文) */
    private suspend fun ocrBox(frame: Bitmap, b: Box): Pair<Int?, String> = withContext(Dispatchers.IO) {
        val y = (b.y - 4).coerceAtLeast(0)
        val h = min(b.h + 8, frame.height - y)
        if (b.x + b.w > frame.width || h <= 0) return@withContext null to ""
        val c = Bitmap.createBitmap(frame, b.x, y, b.w, h)
        val lines = ocr.requestOCR(GachaOcr.encode(c))
        c.recycle()
        val raw = lines.maxByOrNull { it.score }?.text?.trim() ?: ""
        if (raw.isEmpty()) return@withContext null to ""
        val t = normalize(raw)
        if (t.isEmpty()) return@withContext null to raw
        normNames.indexOf(t).takeIf { it >= 0 }?.let { return@withContext it to raw }
        // 编辑距离 ≤2 的唯一最近邻（名单两两最小距离 3，不会归错条目）
        var best = -1
        var bestD = Int.MAX_VALUE
        var tie = false
        for ((i, n) in normNames.withIndex()) {
            val d = GachaDictionary.levenshtein(t, n)
            when {
                d < bestD -> { bestD = d; best = i; tie = false }
                d == bestD -> tie = true
            }
        }
        if (bestD in 1..2 && !tie) best to raw else null to raw
    }

    // ---------------- 抓帧与稳定检测 ----------------

    /** 抓一帧并等标题区连两帧哈希一致（页面静止）；超时返回最后一帧 */
    private suspend fun grabStable(timeoutMs: Long = 5000): Bitmap? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Bitmap? = null
        var prev = -1
        var has = false
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val f = grabFrame()
            if (f == null) { delay(150); continue }
            val h = regionHash(f)
            if (has && h == prev) return@withContext f
            prev = h
            has = true
            last = f
            delay(220)
        }
        last
    }

    private fun grabFrame(): Bitmap? = try {
        val bytes = ShizukuShell.grabVirtualFrame()
        if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Throwable) {
        null
    }

    /** 标题区量化亮度 FNV 哈希（对 JPEG 逐帧微抖动鲁棒，GachaCrawler 同款） */
    private fun regionHash(bmp: Bitmap): Int {
        val x2 = min(boxesBand.x + boxesBand.w, bmp.width)
        val y2 = min(boxesBand.y + boxesBand.h, bmp.height)
        var x = boxesBand.x.coerceAtLeast(0)
        val yTop = boxesBand.y.coerceAtLeast(0)
        if (x2 <= x || y2 <= yTop) return 0
        var h = 1469598103L
        var y = yTop
        while (y < y2) {
            x = boxesBand.x
            while (x < x2) {
                val p = bmp.getPixel(x, y)
                val lum = (((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)) / 24
                h = (h xor lum.toLong()) * 16777619L
                x += 3
            }
            y += 3
        }
        return (h xor (h ushr 32)).toInt()
    }

    // ---------------- 输出 ----------------

    private suspend fun report(seen: Set<Int>) {
        val missing = roster.withIndex().filter { it.index !in seen }
        onLog(
            "扫描完成：名单 ${roster.size} 条 · 本轮出现 ${seen.size} 条 · 未出现 ${missing.size} 条",
            LogLevel.SUCCESS
        )
        if (missing.isEmpty()) {
            onLog("名单内见闻本轮全部出现，没有缺失", LogLevel.SUCCESS)
            return
        }
        onLog("—— 未出现的见闻（${missing.size} 条）——", LogLevel.INFO)
        missing.forEachIndexed { i, e -> onLog("${i + 1}. ${e.value.display()}", LogLevel.INFO) }
        runCatching {
            val dir = File(ctx.filesDir, "waiqin")
            dir.mkdirs()
            val f = File(dir, "missing.txt")
            f.writeText(missing.joinToString("\n") { it.value.display() } + "\n")
            onLog("已写入 ${f.absolutePath}", LogLevel.TRACE)
        }.onFailure { onLog("missing.txt 写入失败：$it", LogLevel.WRN) }
    }
}
