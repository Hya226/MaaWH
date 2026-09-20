package com.maawh.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min

/**
 * 抽卡记录爬虫：游戏停在「招募记录」页（虚拟屏内），本类接管取数循环。
 *
 * 分工：行定位/取色/翻页判定全部在 App 侧（固定坐标 + 像素分析，points.json 提供
 * 几何），OCR 走注入的 GachaOcr；引擎(MaaFramework)不参与——记录页是只读 UI，
 * 不值得为它起引擎任务。
 *
 * 抓帧必须走 ShizukuShell.grabVirtualFrame()（全分辨率 1280x720，与 points.json
 * 坐标同基准）。不能用 VdShared.frame：那是 VdStreamer inSampleSize=2 的半分辨率
 * 预览帧。
 *
 * 停止条件三重保险：锚点命中 / 空页（连续两帧无行带）/ 翻页后表格区域画面不变
 * （= 最后一页）。锚点截断失败（记录过期 >30 天）自然退化为全量抓取，commitCrawl
 * 按 uid 去重合并。
 */
class GachaCrawler(
    private val ctx: Context,
    private val ocr: GachaOcr,
    private val onLog: (String, LogLevel) -> Unit
) {

    class CrawlException(msg: String) : Exception(msg)

    data class Report(val added: Int, val perPool: Map<String, Int>)

    // ---------------- points.json ----------------

    data class Points(
        val pools: List<String>,
        val dropdown: Pt,
        val poolOption: Map<String, Pt>,
        val nextPage: Pt,
        val pageOne: Pt,
        val nameCol: R,
        val timeCol: R,
        val zhaoCol: R?,
        val rarityStrip: R,
        val fullRegion: R,
        val pagerStrip: R?,
        val colors: Map<String, IntArray>,
        val afterNextMs: Long,
        val afterDropdownMs: Long,
        val afterPoolSwitchMs: Long,
        val upscale: Int
    ) {
        data class Pt(val x: Int, val y: Int)
        data class R(val x: Int, val y: Int, val w: Int, val h: Int)

        companion object {
            /** assets 里的内置副本释放到内部存储（首装自举；已存在则不动，允许 adb 覆盖定制） */
            internal fun ensureAssetFile(ctx: Context, assetPath: String, outFile: File) {
                if (outFile.isFile) return
                runCatching {
                    ctx.assets.open(assetPath).use { input ->
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }

            private fun pt(o: JSONObject, key: String): Pt {
                val a = o.getJSONArray(key)
                return Pt(a.optInt(0), a.optInt(1))
            }

            private fun r(o: JSONObject, key: String): R {
                val a = o.getJSONObject(key)
                return R(a.optInt("x"), a.optInt("y"), a.optInt("w"), a.optInt("h"))
            }

            /** 读取并解析 points.json（允许 // 注释行/行尾注释，读取时剥掉） */
            fun load(ctx: Context): Points {
                val f = File(File(ctx.filesDir, "gacha"), "points.json")
                if (!f.isFile) ensureAssetFile(ctx, "gacha/points.json", f)
                if (!f.isFile) {
                    throw CrawlException("缺少配置 ${f.absolutePath}（assets 也没有内置副本）")
                }
                val clean = f.readText().split("\n").joinToString("\n") { it.substringBefore("//") }
                val root = JSONObject(clean)
                val click = root.getJSONObject("click")
                val opts = HashMap<String, Pt>()
                val optObj = click.getJSONObject("poolOption")
                for (k in optObj.keys()) opts[k] = pt(optObj, k)
                val colors = HashMap<String, IntArray>()
                val colorObj = root.getJSONObject("colors")
                for (k in colorObj.keys()) {
                    val a: JSONArray = colorObj.getJSONArray(k)
                    colors[k] = intArrayOf(a.optInt(0), a.optInt(1), a.optInt(2))
                }
                val timings = root.getJSONObject("timings")
                return Points(
                    pools = root.getJSONArray("pools").let { a -> (0 until a.length()).map { a.optString(it) } },
                    dropdown = pt(click, "dropdown"),
                    poolOption = opts,
                    nextPage = pt(click, "nextPage"),
                    pageOne = pt(click, "pageOne"),
                    nameCol = r(root.getJSONObject("crop"), "nameCol"),
                    timeCol = r(root.getJSONObject("crop"), "timeCol"),
                    zhaoCol = root.getJSONObject("crop").optJSONObject("zhaoCol")?.let {
                        R(it.optInt("x"), it.optInt("y"), it.optInt("w"), it.optInt("h"))
                    },
                    rarityStrip = r(root.getJSONObject("crop"), "rarityStrip"),
                    fullRegion = r(root.getJSONObject("table"), "fullRegion"),
                    pagerStrip = root.optJSONObject("pagerStrip")?.let {
                        R(it.optInt("x"), it.optInt("y"), it.optInt("w"), it.optInt("h"))
                    },
                    colors = colors,
                    afterNextMs = timings.optLong("afterNextMs", 400L),
                    afterDropdownMs = timings.optLong("afterDropdownMs", 500L),
                    afterPoolSwitchMs = timings.optLong("afterPoolSwitchMs", 600L),
                    upscale = root.optJSONObject("ocr")?.optInt("upscale", 2) ?: 2
                )
            }
        }
    }

    /** 稀有度竖条里扫出的一行 */
    data class RowBand(val y1: Int, val y2: Int, val r: Int, val g: Int, val b: Int)

    // ---------------- 主流程 ----------------

    suspend fun crawl(onProgress: (String) -> Unit): Report = withContext(Dispatchers.IO) {
        val pts = Points.load(ctx)
        if (!ShizukuShell.isVdAlive()) {
            throw CrawlException("虚拟屏未启动：请先启动虚拟屏，并把游戏打开到「招募记录」页")
        }
        onLog("抽卡记录抓取开始（OCR = ${ocr.javaClass.simpleName}）", LogLevel.INFO)
        // 不做"当前页必须有行"的预检：当前池子可能是空的。页面正确性由
        // switchPool 后的 verifyPool（OCR 下拉框文字）把关，行扫描只管取数。

        val cfg = GachaStore.loadConfig(ctx)
        if (cfg.isFirstRun) onLog("首次运行（无锚点）：全量抓取所有池子", LogLevel.INFO)
        val fresh = HashMap<String, MutableList<GachaStore.Record>>()
        val perPool = HashMap<String, Int>()

        for (pool in pts.pools) {
            ensureActive()
            onProgress("切换：$pool")
            switchPool(pool, pts)
            verifyPool(pool, pts)
            val n = crawlPool(pool, pts, cfg, fresh, onProgress)
            perPool[pool] = n
            onLog("池[$pool] 完成：新增 $n 条", LogLevel.SUCCESS)
        }

        val added = GachaStore.commitCrawl(ctx, fresh)
        onLog("抓取收口：新增 $added 条，${perPool.entries.joinToString("，") { "${it.key} +${it.value}" }}", LogLevel.SUCCESS)
        Report(added, perPool)
    }

    /** 一个池子：翻页循环 + 锚点截断，返回新增条数 */
    private suspend fun crawlPool(
        pool: String,
        pts: Points,
        cfg: GachaStore.Config,
        fresh: HashMap<String, MutableList<GachaStore.Record>>,
        onProgress: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val anchors = cfg.anchors[pool] ?: emptyList()
        val seqMap = HashMap<Long, Int>()
        val seenPages = ArrayList<List<GachaStore.Record>>()
        var lastPage: List<GachaStore.Record>? = null
        var missed = 0
        var added = 0
        while (true) {
            ensureActive()
            val rows = scanPage(pool, pts, seqMap)
            if (rows.isEmpty()) {
                onLog("池[$pool] 无记录行，本池到底", LogLevel.TRACE)
                break
            }
            // 页面指纹 = 前 3 行（时间+稀有度+名字），名字比对容忍 OCR 小误差。
            // 实测同一页两次识别可能出现「天气卜骨」→「三天气卜骨」这类变形，
            // 精确字符串比对会把同一页当成新页，造成整页重复录入。
            val head = rows.take(3)
            if (lastPage != null && pageMatch(head, lastPage!!)) {
                missed++
                if (missed >= 3) {
                    onLog("池[$pool] 连续 $missed 次内容未变化——判定已到最后一页", LogLevel.INFO)
                    break
                }
                onLog("池[$pool] 翻页未生效（$missed/3），重试", LogLevel.WRN)
                clickNextPage(pts)
                continue
            }
            if (seenPages.any { pageMatch(head, it) }) {
                onLog("池[$pool] 绕回了已抓过的页面——全部记录页已覆盖", LogLevel.INFO)
                break
            }
            seenPages.add(head)
            lastPage = head
            missed = 0
            var hit = false
            for (r in rows) {
                if (!cfg.isFirstRun && hitsAnchor(r, anchors)) {
                    onLog("池[$pool] 命中锚点（${r.time} ${r.name}），停止翻页", LogLevel.TRACE)
                    hit = true
                    break
                }
                fresh.getOrPut(pool) { ArrayList() }.add(r)
                added++
            }
            if (hit) break
            if (seenPages.size >= MAX_PAGES) {
                onLog("池[$pool] 已扫 ${seenPages.size} 页仍未命中锚点（30 天前的记录已过期？），按全量收尾", LogLevel.WRN)
                break
            }
            clickNextPage(pts)
            onProgress("$pool：第 ${seenPages.size} 页")
        }
        added
    }

    /** 页面指纹比对：行数一致，且每行时间、稀有度相同，名字编辑距离 ≤2 */
    private fun pageMatch(
        a: List<GachaStore.Record>,
        b: List<GachaStore.Record>
    ): Boolean {
        if (a.size != b.size || a.isEmpty()) return false
        return a.zip(b).all { (x, y) ->
            x.ts == y.ts && x.rarity == y.rarity &&
                (x.name == y.name || GachaDictionary.levenshtein(x.name, y.name) <= 2)
        }
    }

    /**
     * 锚点命中：同一分钟 + 名字相近（OCR 单行偶发掉字/变形，如「天气卜骨」→
     * 「天气骨」，uid 死比拦不住）。短名（≤3字）容差 1 字，长名容差 2 字。
     */
    private fun hitsAnchor(r: GachaStore.Record, anchors: List<GachaStore.Anchor>): Boolean =
        anchors.any { a ->
            a.t == r.ts && (
                a.n == r.name ||
                    GachaDictionary.levenshtein(a.n, r.name) <=
                    (if (minOf(a.n.length, r.name.length) <= 3) 1 else 2)
                )
        }

    /**
     * 扫描当前页 → 行记录。内部含两个自适应：
     * - 空页判定：连续两帧稀有度竖条都无行带才算空页（防公告/加载动画误判）；
     * - 单页重试：有行但时间解析失败 ≥1 行时重抓一帧重识别一次（JPEG 抖动/鼠标遮挡）。
     */
    private suspend fun scanPage(
        pool: String,
        pts: Points,
        seqMap: HashMap<Long, Int>
    ): List<GachaStore.Record> = withContext(Dispatchers.IO) {
        var emptyStrikes = 0
        var attempt = 0
        while (true) {
            ensureActive()
            val frame = grabStable(pts) ?: throw CrawlException("抓帧失败")
            val bands = detectRows(frame, pts)
            if (bands.isEmpty()) {
                emptyStrikes++
                if (emptyStrikes >= 2) return@withContext emptyList()
                delay(700)
                continue
            }
            var bad = 0
            var badTime = false
            val raws = ArrayList<GachaStore.RawRow>(bands.size)
            for (b in bands) {
                val rarity = classifyRarity(b, pts.colors)
                if (rarity == null) {
                    onLog("行 y=${(b.y1 + b.y2) / 2} 稀有度取色异常（RGB ${b.r},${b.g},${b.b}），跳过", LogLevel.WRN)
                    bad++
                    continue
                }
                val nameRaw = ocrLine(frame, pts.nameCol.x, b.y1, pts.nameCol.w, b.y2 - b.y1, pts.upscale)
                val (name, exact) = GachaDictionary.correct(nameRaw)
                if (nameRaw.isNotEmpty() && !exact) {
                    onLog("名字「$nameRaw」按字典纠错为「$name」", LogLevel.TRACE)
                }
                if (nameRaw.isEmpty()) {
                    onLog("行 y=${(b.y1 + b.y2) / 2} 名字识别为空，跳过", LogLevel.WRN)
                    bad++
                    continue
                }
                val timeRaw = ocrLine(frame, pts.timeCol.x, b.y1, pts.timeCol.w, b.y2 - b.y1, pts.upscale)
                val ts = GachaDictionary.parseTime(timeRaw)
                if (ts == null) {
                    onLog("时间解析失败：\"$timeRaw\"，跳过该行", LogLevel.WRN)
                    bad++
                    badTime = true
                    continue
                }
                // 招集列（小类）：识别失败不影响本行入库，仅归到"未识别"组
                val banner = pts.zhaoCol
                    ?.let { ocrLine(frame, it.x, b.y1, it.w, b.y2 - b.y1, pts.upscale) }
                    ?.trim()
                    ?: ""
                raws.add(GachaStore.RawRow(ts, timeRaw, name, rarity, banner))
            }
            if (bad == 0 || attempt >= 1) return@withContext GachaStore.buildRecords(pool, raws, seqMap)
            if (badTime) {
                // 时间列识别失败最常见的原因：下拉面板没收起、遮住了时间列
                // （面板 x1047~1232 与 timeCol x924~1225 重叠）——先点面板外收起再重抓整页
                onLog("存在时间解析失败：点 (${PANEL_DISMISS.x},${PANEL_DISMISS.y}) 关闭可能未收起的下拉面板，重新识别本页", LogLevel.WRN)
                tap(PANEL_DISMISS)
                delay(pts.afterDropdownMs)
                waitStableRegion(pts)
            }
            onLog("本页 $bad 行识别异常，重抓一帧重试", LogLevel.WRN)
            attempt++
            delay(500)
        }
        error("unreachable")
    }

    // ---------------- 像素分析 ----------------

    /**
     * 稀有度竖条 → 行带。饱和色像素（稀有度文字是竖条里唯一的彩色来源）按 y
     * 投影聚类，每带的均值色交给 classifyRarity。
     */
    private fun detectRows(bmp: Bitmap, pts: Points): List<RowBand> {
        val s = pts.rarityStrip
        val x1 = s.x.coerceIn(0, bmp.width - 1)
        val x2 = min(s.x + s.w, bmp.width)
        val y1 = s.y.coerceIn(0, bmp.height - 1)
        val y2 = min(s.y + s.h, bmp.height)
        if (x2 <= x1 || y2 <= y1) return emptyList()

        val counts = IntArray(y2 - y1)
        for (y in y1 until y2) {
            var c = 0
            for (x in x1 until x2) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                if (mx - mn > 50 && mx > 70) c++
            }
            counts[y - y1] = c
        }
        // 聚带（间隙 ≤3 行并成一带，带上至少 2 个彩色像素行）
        val bands = ArrayList<RowBand>()
        var start = -1
        var last = -1
        for (i in counts.indices) {
            if (counts[i] >= 2) {
                if (start < 0) start = i
                last = i
            } else if (start >= 0 && i - last > 3) {
                bands.add(colorOf(bmp, x1, x2, y1 + start, y1 + last))
                start = -1
            }
        }
        if (start >= 0) bands.add(colorOf(bmp, x1, x2, y1 + start, y1 + last))
        return bands
    }

    /** 对已定带重新扫描均值色 */
    private fun colorOf(bmp: Bitmap, x1: Int, x2: Int, ya: Int, yb: Int): RowBand {
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
        for (y in ya..yb) {
            for (x in x1 until x2) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                if (mx - mn > 50 && mx > 70) {
                    sr += r; sg += g; sb += b; n++
                }
            }
        }
        if (n == 0L) return RowBand(ya, yb, 0, 0, 0)
        return RowBand(ya, yb, (sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    /**
     * 均值色 → 稀有度名。按 HSV 色相角就近匹配（points.json 的基准色在运行时算
     * 色相），不用逐通道比对——JPEG/渲染差异只影响明度饱和度，色相稳定。
     * 三个基准的色相：特出≈353°、优异≈52°、新生≈173°，彼此相距 60°+，容差 ±35°。
     */
    private fun classifyRarity(band: RowBand, colors: Map<String, IntArray>): String? {
        val mx = maxOf(band.r, band.g, band.b)
        val mn = minOf(band.r, band.g, band.b)
        if (mx - mn < 25) return null
        val hue = hueOf(band.r, band.g, band.b) ?: return null
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        for ((name, ref) in colors) {
            val rh = hueOf(ref[0], ref[1], ref[2]) ?: continue
            // 色相环距离（0~180）
            var d = abs(hue - rh)
            if (d > 180) d = 360 - d
            if (d < bestDist) {
                bestDist = d
                best = name
            }
        }
        return if (bestDist <= 35) best else null
    }

    /** 色相角 0~360；灰色返回 null */
    private fun hueOf(r: Int, g: Int, b: Int): Int? {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        if (d == 0) return null
        val h = when (mx) {
            r -> 60.0 * (g - b) / d
            g -> 120.0 + 60.0 * (b - r) / d
            else -> 240.0 + 60.0 * (r - g) / d
        }
        return ((h % 360 + 360) % 360).toInt()
    }

    // ---------------- OCR 辅助 ----------------

    /** 裁一行小图 → 放大 → PNG base64 → OCR，取最高分行文本 */
    private suspend fun ocrLine(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int, upscale: Int): String {
        val yy1 = (y - 5).coerceAtLeast(0)
        val yy2 = (y + h + 5).coerceAtMost(bmp.height)
        val xx2 = (x + w).coerceAtMost(bmp.width)
        if (xx2 <= x || yy2 <= yy1) return ""
        var c = Bitmap.createBitmap(bmp, x, yy1, xx2 - x, yy2 - yy1)
        if (upscale > 1) {
            c = Bitmap.createScaledBitmap(c, c.width * upscale, c.height * upscale, true)
        }
        val lines = ocr.requestOCR(GachaOcr.encode(c))
        c.recycle()
        return lines.maxByOrNull { it.score }?.text?.trim() ?: ""
    }

    /** 切池后校验下拉按钮文字（OCR 可用时）；对不上说明点错地方了 */
    private suspend fun verifyPool(pool: String, pts: Points) {
        val f = grabStable(pts) ?: return
        // 下拉按钮框：按钮中心 ±（92,17）≈ 实测按钮 184x34，误差不影响文字判定
        val text = ocrLine(f, pts.dropdown.x - 92, pts.dropdown.y - 17, 184, 34, pts.upscale)
        if (text.isEmpty()) return  // Stub 或识别失败：不阻塞流程
        if (pool.take(2) !in text) {
            throw CrawlException("切池校验失败：下拉框显示「$text」，期望「$pool」。请核对 points.json 的 poolOption 坐标")
        }
    }

    // ---------------- 注入与翻页 ----------------

    private suspend fun switchPool(pool: String, pts: Points) = withContext(Dispatchers.IO) {
        tap(pts.dropdown)
        delay(pts.afterDropdownMs)
        waitStableRegion(pts)
        val opt = pts.poolOption[pool]
            ?: throw CrawlException("points.json 缺少池子「$pool」的选项坐标（poolOption）")
        // 面板区域快照：点选项后面板是否收起（点的是当前已选中池子时，游戏不会自动收起）
        val panelBefore = grabStable(pts)?.let { regionHash(it, DROPDOWN_PANEL) }
        tap(opt)
        delay(pts.afterPoolSwitchMs)
        waitStableRegion(pts)
        // 收起复验：哈希仍相同 = 面板还开着（点按钮收不起/点击丢失），点面板外收起并重试
        var panelAfter = grabStable(pts)?.let { regionHash(it, DROPDOWN_PANEL) }
        var retries = 0
        while (panelBefore != null && panelAfter != null && panelBefore == panelAfter && retries < 3) {
            retries++
            onLog("下拉面板未自动收起（选中的是当前池子），点面板外收起（第 $retries 次）", LogLevel.INFO)
            tap(PANEL_DISMISS)
            delay(pts.afterDropdownMs)
            waitStableRegion(pts)
            panelAfter = grabStable(pts)?.let { regionHash(it, DROPDOWN_PANEL) }
        }
        if (panelBefore != null && panelAfter != null && panelBefore == panelAfter) {
            onLog("下拉面板收起失败（已重试 $retries 次），面板会遮挡时间列导致识别异常", LogLevel.WRN)
        }
        // 兜底点页码 1：有的切池会记住上次页码
        tap(pts.pageOne)
        delay(300)
        waitStableRegion(pts)
    }

    private fun tap(p: Points.Pt) {
        ShizukuShell.injectTapVD(p.x, p.y)
    }

    /**
     * 点「下一页」：pagerStrip 就是用户框定的按钮精确范围——在范围内找亮色
     * 内容（按钮文字）的包围盒点其中心；范围内没有内容（按钮置灰/消失，
     * 常见于最后一页）就点范围中心；连 pagerStrip 都没配才回退固定坐标。
     */
    private suspend fun tapNextPageSmart(pts: Points) = withContext(Dispatchers.IO) {
        val band = pts.pagerStrip
        if (band == null) {
            tap(pts.nextPage)
            return@withContext
        }
        val frame = grabFrame()
        val target = frame?.let { brightContentCenter(it, band) }
            ?: Points.Pt(band.x + band.w / 2, band.y + band.h / 2)
        tap(target)
    }

    /**
     * 区域内亮色像素按列投影分块（块间暗隙 ≥6 列即切开），取**最右**亮块的包围盒
     * 中心——「下一页」按钮固定在 pagerStrip 最右端，页码/上一页等更靠左的亮色
     * 内容（用户扩宽区域后会被框进来）不参与定位。最右块亮像素过少视为噪声返回
     * null（调用方回退区域中心，恰好也是按钮位置）。绝不取次右块，防止点到页码。
     */
    private fun brightContentCenter(bmp: Bitmap, band: Points.R): Points.Pt? {
        val x1 = band.x.coerceAtLeast(0)
        val x2 = min(band.x + band.w, bmp.width)
        val y1 = band.y.coerceAtLeast(0)
        val y2 = min(band.y + band.h, bmp.height)
        if (x2 <= x1 || y2 <= y1) return null
        val cols = x2 - x1
        val colHas = BooleanArray(cols)
        val colCount = IntArray(cols)
        val colMinY = IntArray(cols) { Int.MAX_VALUE }
        val colMaxY = IntArray(cols) { -1 }
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                val p = bmp.getPixel(x, y)
                if ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff) > 270) {  // 亮度均值>90
                    val ci = x - x1
                    colHas[ci] = true
                    colCount[ci]++
                    if (y < colMinY[ci]) colMinY[ci] = y
                    if (y > colMaxY[ci]) colMaxY[ci] = y
                }
            }
        }
        // 从右往左扫：最近一个亮块，允许块内 <6 列的暗隙（笔画间隙），≥6 列即块边界
        var right = -1
        var left = -1
        var gapRun = 0
        for (i in cols - 1 downTo 0) {
            if (colHas[i]) {
                if (right == -1) right = i
                left = i
                gapRun = 0
            } else if (right != -1) {
                gapRun++
                if (gapRun >= 6) break
            }
        }
        if (right == -1) return null
        var miny = Int.MAX_VALUE
        var maxy = -1
        var n = 0
        for (ci in left..right) {
            if (!colHas[ci]) continue
            n += colCount[ci]
            if (colMinY[ci] < miny) miny = colMinY[ci]
            if (colMaxY[ci] > maxy) maxy = colMaxY[ci]
        }
        if (n < 20 || maxy < miny) return null
        return Points.Pt(x1 + (left + right) / 2, (miny + maxy) / 2)
    }

    /**
     * 点下一页并等画面过渡。翻页是否真正生效由 crawlPool 拿页面内容比对判定，
     * 本函数不产生任何"成功/失败"结论——避免把点击延迟误判成最后一页，
     * 也避免盲目补点一次跳两页。
     */
    private suspend fun clickNextPage(pts: Points) = withContext(Dispatchers.IO) {
        tapNextPageSmart(pts)
        delay(pts.afterNextMs)
    }

    private suspend fun waitStableRegion(pts: Points, timeoutMs: Long = 4000) = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var prev = -1
        var has = false
        while (System.currentTimeMillis() < deadline) {
            ensureActive()
            val f = grabFrame()
            if (f == null) { delay(150); continue }
            val h = regionHash(f, pts.fullRegion)
            if (has && h == prev) return@withContext
            prev = h
            has = true
            delay(250)
        }
    }

    /** 抓一帧并等表格区域连续两帧一致（页面静止）；超时返回最后一帧 */
    private suspend fun grabStable(pts: Points, timeoutMs: Long = 5000): Bitmap? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Bitmap? = null
        var prev = -1
        var has = false
        while (System.currentTimeMillis() < deadline) {
            ensureActive()
            val f = grabFrame()
            if (f == null) { delay(150); continue }
            val h = regionHash(f, pts.fullRegion)
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

    /** 表格区域的量化亮度 FNV 哈希（对 JPEG 逐帧微抖动鲁棒） */
    private fun regionHash(bmp: Bitmap, r: Points.R): Int {
        val x2 = min(r.x + r.w, bmp.width)
        val y2 = min(r.y + r.h, bmp.height)
        var x = r.x.coerceAtLeast(0)
        val yTop = r.y.coerceAtLeast(0)
        if (x2 <= x || y2 <= yTop) return 0
        var h = 1469598103L
        var y = yTop
        while (y < y2) {
            x = r.x
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

    private suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }

    private suspend fun ensureActive() = coroutineContext.ensureActive()

    companion object {
        /** 游戏单池最多 18 页左右；60 是保险上限，防止异常页面无限翻 */
        private const val MAX_PAGES = 60

        /** 下拉展开面板的区域（frame_dropdown.jpg 实测 ≈x1047..1232, y178..362），用于判"面板是否还开着" */
        private val DROPDOWN_PANEL = Points.R(1047, 178, 185, 184)

        /**
         * 下拉面板的「面板外收起」点击点（面板右上外侧空白，用户实测可关闭面板；
         * 点下拉按钮本身收不起——游戏把展开态的按钮点击吞掉了）。两处使用：
         * switchPool 补收起、scanPage 时间解析失败时先关面板再重识别。
         */
        private val PANEL_DISMISS = Points.Pt(1209, 155)
    }
}
