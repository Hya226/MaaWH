package com.maawh.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * 【仓库识别】资源仓库扫描器（小工具，学 GachaCrawler/WaiQinScan 的宿主侧识别模式——
 * 引擎不参与，App 直接抓虚拟屏帧做识别，滑动/返回键走既有注入通道）。
 *
 * 识别思路对标 MAA 的 DepotImageAnalyzer：
 * - 物华资源仓库是等间距网格（720p 实测：6 列、列周期 120px、行周期 130px、首格 ≈355,118、
 *   格子含边框 112×112、图标内缩 ~8px、数量白字在右下角），按网格切 ROI 逐格识别；
 * - 物品种类 = 图标模板库匹配（模板来自素材库 128×128 图标，缩到 40×40 灰度做 NCC，
 *   取全库最高分，阈值 0.72；素材按小仓库分目录，资源仓库用 resource/）；
 * - 数量 = 格子右下角裁片放大后走 PP-OCR（复用 GachaOcr），只对「本页新识别的资源」识别
 *   （同名资源跨页数量恒定，已有记录的直接复用，控制 OCR 次数）；
 * - 翻页 = (800,630)→(800,350) 竖直滑动，不追求对齐整行；到底判据 = 识别集合连续两轮
 *   无新增（比画面哈希更稳，不怕滑动残影）；
 * - 收尾 = 返回键回主页（资源页→BACK→物资库→BACK→主页，按帧验主页特征决定是否补按）。
 *
 * 模板自举：assets/warehouse/resource/ 随包内置，首用整目录释放到 files/warehouse/resource/
 * （已存在则不动，adb 直推新图标即增补——新资源补充图标后重新识别即可）。
 * 结果：日志逐行 + files/warehouse/resource_scan.json（{资源名: 数量}）。
 */
class ResourceWarehouseScan(
    private val ctx: Context,
    /** 小仓库分类：resource/badge/ticket——决定模板目录 assets|files/warehouse/<cat>/ 与日志文案 */
    private val category: String,
    private val ocr: GachaOcr,
    private val onLog: (String, LogLevel) -> Unit,
    private val isStopped: () -> Boolean
) {

    class ScanException(msg: String) : Exception(msg)

    /** 数量 OCR 预处理通道（双通道级联，见 ocrQuantity） */
    private val QTY_MODE_OTSU = 0
    private val QTY_MODE_INVERT = 1

    data class Result(val counts: LinkedHashMap<String, String>, val pages: Int, val elapsedMs: Long)

    // ---- 网格参数（用户按帧逐格校准：首格 (356,122) 卡面 114×116，列周期 150 行周期 130）----
    private val gridX0 = 356
    private val gridY0 = 122
    private val colPeriod = 150
    private val rowPeriod = 130
    private val cellW = 114         // 卡面宽
    private val cellH = 116         // 卡面高
    private val iconInset = 4       // 边缘内缩
    private val cols = 6
    private val rows = 4            // 屏内完整行数（第 5 行只露出边缘，放弃）
    private val slideFrom = 800 to 630
    private val slideTo = 800 to 350

    /** 物品区竖带扫描的 y 范围（帧坐标）：覆盖全部可见行 + 滚动偏移余量 */
    private val Y0_SCAN = 100
    private val SCAN_BOTTOM = 660

    private val matchThreshold = 0.72f
    /**
     * 统一 0.5 等比缩放（**保持宽高比**）：此前 ROI 被强压成正方而模板近方形——
     * 变形比例不一致 NCC 全崩（真机 0 命中根因之一）。只降采样不变形；
     * 像素减半让纯 Kotlin 滑窗够快。模板与竖带必须用同一缩放因子（尺度一致）。
     */
    private val sampleScale = 0.5f

    /** 单页里每个格子的识别结果 */
    private data class CellHit(val name: String, val score: Float, val gx: Int, val gy: Int)

    // ---------------- 主流程 ----------------

    suspend fun scanSuspend(
        onProgress: (String) -> Unit,
        onCounts: (Map<String, String>) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val templates = ensureTemplates(ctx)
        if (templates.isEmpty()) {
            throw ScanException("图标模板库为空（assets/warehouse/$category 缺失或释放失败）")
        }
        onLog("仓库识别开始（${WarehouseStore.catName(category)}仓库）：模板 ${templates.size} 个", LogLevel.INFO)

        val counts = LinkedHashMap<String, String>()
        var pages = 0
        var retries = 0
        var prevKeys = emptySet<String>()
        var stableRuns = 0
        var firstPageChecked = false
        var lastSwipeHash: Int? = null   // 滑动前画面哈希：翻页后画面没变 = 到底

        while (true) {
            coroutineContext.ensureActive()
            if (isStopped()) throw ScanException("已停止")

            val frame = grabStable()
            if (frame == null) {
                onLog("抓帧失败，重试…", LogLevel.WRN)
                kotlinx.coroutines.delay(800)
                continue
            }
            // 翻页后画面与滑动前完全一致 = 没滚动 = 到底（省掉「同集合再翻 2 页」的确认轮——
            // 票券类一页装得下的仓库不再白滑 2 次）
            if (lastSwipeHash != null && quickHash(frame) == lastSwipeHash) {
                onLog("画面未滚动——已到底", LogLevel.INFO)
                break
            }

            if (pages == 0) {
                // 诊断：把第一页识别实际用的帧存下来（排障「零识别」用，与素材/网格对照；按分类命名不互覆）
                runCatching {
                    val d = File(ctx.filesDir, "warehouse")
                    d.mkdirs()
                    val out = File(d, "debug_first_$category.jpg")
                    out.outputStream().use { o -> frame.compress(Bitmap.CompressFormat.JPEG, 90, o) }
                    onLog("诊断：第一页帧已存 ${out.path}（${frame.width}×${frame.height}）", LogLevel.TRACE)
                }
            }
            val hits = scanPage(frame, templates, counts, onProgress, pages == 0, onCounts)
            val keys = hits.map { it.name }.toSet()
            pages++
            onLog(
                "第 $pages 页：识别 ${keys.size} 种资源" +
                    (if (keys.isNotEmpty()) "（新增 ${keys.count { it !in prevKeys }}）" else ""),
                LogLevel.INFO
            )

            if (!firstPageChecked) {
                firstPageChecked = true
                if (hits.isEmpty()) {
                    // 第一页 0 命中：可能是切换页面的过渡帧被抓到（grabStable 超时兜底返回
                    // 最后一帧）——重抓重试一次再下结论，避免时机问题一击致命
                    retries++
                    if (retries <= 2) {
                        onLog("第 1 页未识别到资源（第 $retries 次重试）——可能是过渡帧，重抓…", LogLevel.WRN)
                        prevKeys = emptySet()
                        stableRuns = 0
                        pages--
                        kotlinx.coroutines.delay(1200)
                        continue
                    }
                    throw ScanException("未识别到任何资源——请确认游戏已停在【物资库-${WarehouseStore.catName(category)}】页（可先跑【仓库识别】流程导航）")
                }
            }

            if (keys == prevKeys) stableRuns++ else stableRuns = 0
            if (stableRuns >= 2) {
                onLog("连续 2 页识别集合无新增——资源仓库已到底", LogLevel.INFO)
                break
            }
            prevKeys = keys

            coroutineContext.ensureActive()
            if (isStopped()) throw ScanException("已停止")
            lastSwipeHash = quickHash(frame)
            ShizukuShell.injectSwipeVD(slideFrom.first, slideFrom.second, slideTo.first, slideTo.second, 600)
            kotlinx.coroutines.delay(900)  // 等滑动惯性+页面稳定
        }

        backToHome(onProgress)

        val elapsed = System.currentTimeMillis() - started
        // 结果落盘由调用方（MainActivity → WarehouseStore.save，含上次同步/变化对比）统一负责
        onLog(
            "仓库识别完成（${WarehouseStore.catName(category)}）：共 $pages 页，${counts.size} 种" +
                "（数量识别成功 ${counts.count { it.value.isNotBlank() }}）" +
                " · 耗时 ${elapsed / 1000}s · 明细见 files/warehouse/${category}_scan.json",
            LogLevel.SUCCESS
        )
        Result(counts, pages, elapsed)
    }

    /**
     * 单页识别：**列竖带全高滑窗（x+y 二维滑动）**（滚动列表适配版，对标 MAA
     * DepotImageAnalyzer 的「模板在区域内滑窗找最佳位置」而非固定网格）。
     *
     * 固定网格的前提是「物品行恰好对齐网格」——滑动翻页后物品停在任意滚动偏移上
     * （用户实测纠偏：左右列不变、上下会移动）。x 方向也必须滑动（诊断帧铁证：
     * 55 模板最佳位置 ox=2..13，x 固定 0 会把绝大多数模板压到阈值之下——离线
     * cv2 全量扫描：x 滑动 26/55 ≥0.72，x 固定只有 1/55）。尺度不需要多档
     * （同一扫描里全部模板 scale=1.0 得胜：素材裁片与帧图标本就同尺度）。
     *
     * 性能：竖带 53×280、模板 ≤50×50，每模板 ~1600 个位置 × 2000 像素 ≈ 340 万
     * 乘加，55 模板 × 6 列 ≈ 11 亿/页，Kotlin 约 1~3s/页。
     * 命中去重：同列不同模板命中同一 y（同物品误双认/同族近似牌互相串认）保留分高者。
     */
    /** 阻塞包装（QueueRunner 宿主分支用——额外队列的「仓库识别」依次扫三仓） */
    fun scanBlocking(
        onProgress: (String) -> Unit,
        onCounts: (Map<String, String>) -> Unit = {}
    ): Result = kotlinx.coroutines.runBlocking { scanSuspend(onProgress, onCounts) }

    private suspend fun scanPage(
        frame: Bitmap,
        templates: List<Template>,
        counts: LinkedHashMap<String, String>,
        onProgress: (String) -> Unit,
        verbose: Boolean,
        onCounts: (Map<String, String>) -> Unit
    ): List<CellHit> {
        val hits = ArrayList<CellHit>()
        var newOnes = 0

        // 竖带物理区域：宽 = 卡面内缩后物品区 106（>= 模板物理宽 74~100），y 覆盖物品区全高。
        // dst 同 0.5 缩放（53×280）；滑窗步长/范围一律用 dst 尺寸——此前步长误用物理宽
        // 106（数组每行只有 53）→ strip[14840] 越界崩溃（真机 21:17 ERR 实锤）。
        val stripW = 106
        val stripXInset = 4
        val stripTop = Y0_SCAN          // 100（物理）
        val stripH = SCAN_BOTTOM - Y0_SCAN  // 560（物理）
        val dstW = (stripW * sampleScale).toInt()  // 53
        val dstH = (stripH * sampleScale).toInt()  // 280

        for (c in 0 until cols) {
            coroutineContext.ensureActive()
            if (isStopped()) throw ScanException("已停止")
            val gx = gridX0 + c * colPeriod + stripXInset
            if (gx + stripW > frame.width) continue
            val cardX = gridX0 + c * colPeriod      // 卡面左缘物理 x（数量 OCR 锚点，与命中位置无关）
            val strip = grayOf(frame, gx, stripTop, stripW, stripH, dstW, dstH)

            val colHits = ArrayList<CellHit>()
            for (t in templates) {
                coroutineContext.ensureActive()
                val m = nccBest(strip, dstW, dstH, t.gray, t.grayW, t.grayH) ?: continue
                if (m.score < matchThreshold) continue
                // 命中：dst 位置 ×2 还原物理坐标（gx 记卡面左缘，数量 OCR 不依赖命中 x）
                colHits.add(CellHit(t.name, m.score, cardX, stripTop + m.oy * 2))
            }

            // 同 y 去重（不同模板认同一物品时保留分高者）
            colHits.sortByDescending { it.score }
            val kept = ArrayList<CellHit>()
            for (h in colHits) {
                if (kept.none { kotlin.math.abs(it.gy - h.gy) < cellH * 2 / 3 }) kept.add(h)
            }

            for (h in kept) {
                if (!counts.containsKey(h.name)) {
                    newOnes++
                    counts[h.name] = ocrQuantity(frame, h.gx, h.gy, h.name)
                }
            }
            onLog("列${c + 1}：命中 ${kept.size} 种", LogLevel.TRACE)
            hits.addAll(kept)
        }

        if (hits.isNotEmpty()) onProgress("仓库识别：累计 ${counts.size} 种 · 本页 ${hits.size} 格")
        if (newOnes > 0) onLog("本页新增资源 $newOnes 种", LogLevel.TRACE)
        onCounts(counts.toMap())
        return hits
    }

    /** 数量 OCR：卡面右下角数量白字（右对齐卡面右缘、贴底缘）。
     *  ★ 命中 y 含素材顶部留白 m（手裁 0~22px），数字位置随 m 漂移。单窗/三窗都
     *  不够：m 恰好让「截断窗」成为唯一有读数的窗时投票也被错误读数绑架（真机
     *  11→7、1531.04万→1331.04万 两轮实锤）。改 **7 条密集窗**（步长 6px，扫
     *  gy+76..136）：数字带无论在哪都有 1~2 条「居中」窗完整覆盖（离线矩阵证实
     *  居中窗读数 100% 正确且在多窗重现），全部读数按数值众数投票，边缘窗的
     *  截断错读（17、5908、1331）票数压不过正确读数。
     *  显示串保留「万」（用户要求）；带点无万自动补（游戏格式只有纯整数/X.XX万）。 */
    private suspend fun ocrQuantity(frame: Bitmap, cardX: Int, gy: Int, name: String): String {
        return try {
            if (cardX < 0 || gy < 0) return ""
            val votes = ArrayList<Triple<String, String, Float>>()   // (等价类key, 读数串, 置信度)
            // 阶段1：OTSU 通道（贴卡面外缘/低对比个位数字的救星——浆糊/远击I 实锤）
            for (dy in intArrayOf(76, 82, 88, 94, 100, 106, 112)) {
                val (qs, qconf) = ocrDigits(frame, cardX + 16, gy + dy, 98, 24, 2, QTY_MODE_OTSU) ?: continue
                votes.add(Triple(voteClass(qs), qs, qconf))
            }
            // 阶段2（级联）：OTSU 票少或置信度低（<0.75）才补跑反色通道——白底白字的
            // 数量字反色成黑字白描边后全场稳定正确（金卡 3/33/3、深背景样本 0.98+ 实锤）；
            // 多数物品阶段1 高置信直接结束（省一半 rec）
            if (votes.size < 2 || (votes.maxOfOrNull { it.third } ?: 0f) < 0.75f) {
                for (dy in intArrayOf(76, 82, 88, 94, 100, 106, 112)) {
                    val (qs, qconf) = ocrDigits(frame, cardX + 16, gy + dy, 98, 24, 2, QTY_MODE_INVERT) ?: continue
                    votes.add(Triple(voteClass(qs), qs, qconf))
                }
            }
            if (votes.isEmpty()) {
                runCatching {
                    val d = File(ctx.filesDir, "warehouse")
                    d.mkdirs()
                    val cx = (cardX + 2).coerceAtLeast(0)
                    val cy = (gy - 2).coerceAtLeast(0)
                    val cw = 110.coerceAtMost(frame.width - cx)
                    val ch = 120.coerceAtMost(frame.height - cy)
                    if (cw > 0 && ch > 0) {
                        val bmp = Bitmap.createBitmap(frame, cx, cy, cw, ch)
                        File(d, "qty_fail_$name.png").outputStream().use { o ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
                        }
                        bmp.recycle()
                    }
                }
                onLog("数量 OCR 失败：$name（已存诊断卡面 warehouse/qty_fail_$name.png）", LogLevel.WRN)
                return ""
            }
            // 众数投票（按等价类）：正确读数在多数窗口稳定重现；重复数字（1/11/111）
            // 合并同类后取**类内置信度最高**的读数（完整覆盖窗读得准且置信高——红卡
            // 11 的 conf 0.91 高于截断窗 1 的 0.78）；类间并列时也比最高置信度
            val bestGroup = votes.groupBy { it.first }
                .maxWithOrNull(compareBy({ it.value.size }, { it.value.maxOf { it.third } }))!!
            var display = bestGroup.value.maxByOrNull { it.third }!!.second
            if (display.contains('.') && !display.contains('万')) display += "万"
            display
        } catch (e: Throwable) {
            onLog("数量 OCR 失败：${e.message}", LogLevel.WRN)
            ""
        }
    }

    /** 投票等价类：全同字符读数（1/11/111）在截断/边缘窗下位数不定（红卡 11 的一轮
     *  实锤：同一物品的窗读出 111/1/1/11 四种），合并为同一类计票；其余按数值 key。 */
    private fun voteClass(s: String): String {
        if (s.all { it.isDigit() && it == s[0] }) return "rep:${s[0]}"
        return "n:${qtyKey(s)}"
    }

    /** 数值化投票 key：「1531.04万」「153104万」（丢点）「1531.04」（丢万字）都归到同值。
     *  游戏 ≥1万 格式固定 X.XX万（两位小数）——含万丢点按后两位小数还原；
     *  纯整数是字面值不乘万（与 WarehouseStore.qtyValue 同口径）。 */
    private fun qtyKey(s: String): Long {
        val wan = s.contains('万')
        val body = s.replace("万", "")
        val dot = body.indexOf('.')
        val digits = body.replace(".", "").toLongOrNull() ?: return -1
        val frac = when {
            wan && dot < 0 -> 2          // 丢点：X.XX万 固定两位小数
            dot >= 0 -> body.length - dot - 1
            else -> 0
        }
        return when {
            wan -> digits * (10000L / WarehouseStore.pow10Of(frac))
            dot >= 0 -> digits * 100L
            else -> digits
        }
    }

    /** 裁片放大 scale 倍送 OCR，按置信度降序找第一条能解析出数量的行，返回显示串（失败 ""）。
     *  放大后做 **反色**：数量字是「白字+黑描边」，深卡面上对比够、但贴卡面外缘/
     *  浅色网格页时变成白底白字（真机 浆糊/远击I 7 窗全空、金卡 11111/1 连环翻车）。
     *  反色统一成「黑字+白描边」的稳定形态——离线 6 样本全对且高置信（0.8~1.0），
     *  全面优于 OTSU 二值化（金卡 3 被 OTSU 背景切白误读、远击I 锐化也救不全）。*/
    private suspend fun ocrDigits(
        frame: Bitmap, rx: Int, ry: Int, rw: Int, rh: Int, scale: Int, mode: Int
    ): Pair<String, Float>? {
        if (rx + rw > frame.width || ry + rh > frame.height || rx < 0 || ry < 0) return null
        var crop = Bitmap.createBitmap(frame, rx, ry, rw, rh)
        crop = Bitmap.createScaledBitmap(crop, rw * scale, rh * scale, true)
        crop = when (mode) {
            QTY_MODE_INVERT -> invertBitmap(crop)
            else -> otsuBinarize(crop) ?: return null
        }
        val lines = ocr.requestOCR(GachaOcr.encode(crop))
        crop.recycle()
        for (l in lines.sortedByDescending { it.score }) {
            val s = parseQuantity(l.text)
            if (s.isNotEmpty()) return s to l.score
        }
        return null
    }

    /** 反色（RGB 各通道取反，alpha 保留）：白底白字 → 黑底白字 */
    private fun invertBitmap(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val aMask = 0xFF000000.toInt()
        for (i in px.indices) px[i] = (px[i].inv() and 0x00FFFFFF) or aMask
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** OTSU 自适应阈值二值化：亮（数量白字）→白、其余→黑。
     *  ★ 白占比 >55% = 数字泡在大片浅色背景（浅网格页/卡面外缘）——OTSU 把背景整片
     *  切白吞掉字形（真机金卡 3→11111 实锤），返回 null 让调用方跳过该窗。 */
    private fun otsuBinarize(bmp: Bitmap): Bitmap? {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(px.size)
        val hist = IntArray(256)
        for (i in px.indices) {
            val p = px[i]
            val g = (0.299f * (p shr 16 and 0xff) + 0.587f * (p shr 8 and 0xff) + 0.114f * (p and 0xff)).toInt().coerceIn(0, 255)
            gray[i] = g
            hist[g]++
        }
        val total = px.size
        var sumAll = 0L
        for (t in 0..255) sumAll += t.toLong() * hist[t]
        var sumB = 0L
        var wB = 0
        var bestT = 128
        var bestVar = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > bestVar) {
                bestVar = v
                bestT = t
            }
        }
        val out = IntArray(px.size)
        val white = -0x1
        val black = 0xFF000000.toInt()
        var whiteCnt = 0
        for (i in px.indices) {
            if (gray[i] > bestT) {
                out[i] = white
                whiteCnt++
            } else {
                out[i] = black
            }
        }
        if (whiteCnt * 100L > px.size * 55L) {
            bmp.recycle()
            return null
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 数量显示串（保留「万」字，用户要求）：取最长「数字+可选小数点+可选万」组，
     *  规范化全角点/萬。游戏格式只有纯整数与「X.XX万」两种，原样展示（x3.99万）。 */
    private fun parseQuantity(text: String): String {
        val m = Regex("[0-9]+(?:[.．,，][0-9]+)?\\s*[万萬]?").findAll(text)
            .filter { it.value.any { ch -> ch.isDigit() } }
            .maxByOrNull { it.value.length } ?: return ""
        return m.value.replace(" ", "")
            .replace('．', '.').replace('，', '.').replace(',', '.').replace('萬', '万')
    }

    // ---------------- 收尾：返回键回主页 ----------------

    private suspend fun backToHome(onProgress: (String) -> Unit) {
        onProgress("仓库识别：返回主页…")
        ShizukuShell.keyEventOnTarget(4)  // BACK：资源页 → 物资库
        kotlinx.coroutines.delay(1500)
        val frame = grabStable() ?: return
        if (!looksLikeHome(frame)) {
            ShizukuShell.keyEventOnTarget(4)  // 仍在深层（物资库）→ 再按一次
            kotlinx.coroutines.delay(1500)
        }
    }

    /** 主页特征：底部行「游历/商亭/物资库/任务」按钮列（x≈640..1250, y≈640..700 的亮色块分布）——
     *  粗验即可，仅用于决定是否补按 BACK */
    private fun looksLikeHome(frame: Bitmap): Boolean {
        // 主页底部导航行的按钮底色与物资库页（左侧深绿分类栏+右侧白格）差异巨大：
        // 物资库页左栏是深绿色带（x 30..300, y 60..640 深绿占比高），主页没有。
        var darkGreen = 0
        var total = 0
        for (y in 80 until 600 step 8) {
            for (x in 40 until 290 step 8) {
                if (x >= frame.width || y >= frame.height) continue
                val p = frame.getPixel(x, y)
                val r = p shr 16 and 0xff
                val g = p shr 8 and 0xff
                val b = p and 0xff
                total++
                if (g > r && g > b * 0.8f && g < 150 && r < 110) darkGreen++
            }
        }
        return total == 0 || darkGreen * 100 / total.coerceAtLeast(1) < 30
    }

    // ---------------- 模板库 ----------------

    /** mt=模板灰度均值，dt=Σ(t-mt)²（滑窗快速 NCC 分母的预计算项） */
    private class Template(
        val name: String, val gray: FloatArray, val meanColor: FloatArray,
        val grayW: Int, val grayH: Int, val mt: Float, val dt: Float
    )

    /** assets/warehouse/<category>/ → files/warehouse/<category>/ 自举释放，然后加载为灰度模板。
     *  用「assets 清单指纹」比对 files 端记录：素材更新（打包）后自动整目录重建——
     *  此前「目录非空不释放」导致 assets 更新后手机旧模板永不失效（实锤：换游戏内
     *  裁片后真机仍用旧裸图标模板，怎么调算法都是 0 命中）。 */
    private fun ensureTemplates(ctx: Context): List<Template> {
        val dir = File(ctx.filesDir, "warehouse/$category")
        val stamp = File(ctx.filesDir, "warehouse/${category}_fingerprint.txt")
        val names = runCatching { ctx.assets.list("warehouse/$category") ?: emptyArray() }.getOrDefault(emptyArray())
        val fingerprint = names.sorted().joinToString("\n") { n ->
            val l = runCatching { ctx.assets.open("warehouse/$category/$n").use { it.available() } }.getOrDefault(-1)
            "$n:$l"
        } + "|${names.size}"
        val local = if (stamp.isFile) stamp.readText().trim() else null
        if (!dir.isDirectory || dir.listFiles().isNullOrEmpty() || local != fingerprint) {
            dir.deleteRecursively()
            dir.mkdirs()
            for (n in names) {
                ctx.assets.open("warehouse/$category/$n").use { input ->
                    File(dir, n).outputStream().use { output -> input.copyTo(output) }
                }
            }
            stamp.writeText(fingerprint)
            onLog("模板库已重建（${WarehouseStore.catName(category)} ${names.size} 个，指纹${if (local == null) "首建" else "更新"}）", LogLevel.INFO)
        }
        val files = dir.listFiles { f -> f.extension.equals("png", true) }?.sortedBy { it.name } ?: emptyList()
        val out = ArrayList<Template>(files.size)
        for (f in files) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            val g = toGrayNormalized(bmp)
            val dims = scaledDims(bmp.width, bmp.height)
            var mt = 0f
            for (v in g) mt += v
            mt /= g.size
            var dt = 0f
            for (v in g) {
                val d = v - mt
                dt += d * d
            }
            out.add(
                Template(
                    f.nameWithoutExtension,
                    g,
                    colorMeanOf(bmp, 0, 0, bmp.width, bmp.height),
                    dims.first, dims.second, mt, dt
                )
            )
            bmp.recycle()
        }
        return out
    }

    // ---------------- 图像原语 ----------------

    /** ROI 彩色均值（颜色预筛用） */
    private fun scaledDims(w: Int, h: Int): Pair<Int, Int> =
        Pair((w * sampleScale).toInt().coerceAtLeast(8), (h * sampleScale).toInt().coerceAtLeast(8))

    private fun colorMeanOf(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): FloatArray {
        val safeW = w.coerceAtMost(bmp.width - x)
        val safeH = h.coerceAtMost(bmp.height - y)
        if (safeW <= 0 || safeH <= 0) return floatArrayOf(0f, 0f, 0f)
        val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(bmp, x, y, safeW, safeH), 8, 8, true)
        val px = IntArray(64)
        small.getPixels(px, 0, 8, 0, 0, 8, 8)
        small.recycle()
        var r = 0f; var g = 0f; var b = 0f
        for (p in px) {
            r += (p shr 16 and 0xff)
            g += (p shr 8 and 0xff)
            b += (p and 0xff)
        }
        return floatArrayOf(r / px.size, g / px.size, b / px.size)
    }

    private fun colorDist(a: FloatArray, b: FloatArray): Float {
        val d0 = a[0] - b[0]; val d1 = a[1] - b[1]; val d2 = a[2] - b[2]
        return d0 * d0 + d1 * d1 + d2 * d2
    }

    /** 稳定抓帧：连续两帧表格区域一致才用（滑动/动画干扰下防抖） */
    private suspend fun grabStable(timeoutMs: Long = 4000): Bitmap? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Bitmap? = null
        var prevHash = -1
        var has = false
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val f = grabFrame()
            if (f == null) {
                kotlinx.coroutines.delay(150)
                continue
            }
            val h = quickHash(f)
            if (has && h == prevHash) return f
            prevHash = h
            has = true
            last = f
            kotlinx.coroutines.delay(220)
        }
        return last
    }

    private fun grabFrame(): Bitmap? = try {
        val bytes = ShizukuShell.grabVirtualFrame()
        if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Throwable) {
        null
    }

    /** 全帧粗哈希（抽样亮度），仅用于稳定判定 */
    private fun quickHash(bmp: Bitmap): Int {
        var h = 17L
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                h = h * 31 + ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff))
                x += 24
            }
            y += 24
        }
        return h.toInt()
    }

    /** 裁 ROI → 0.5 等比缩放 → 灰度 FloatArray；模板侧先合成中性灰底消透明角 */
    private fun toGrayNormalized(bmp: Bitmap): FloatArray {
        var src: Bitmap = bmp
        var made = false
        if (bmp.hasAlpha()) {
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(flat)
            c.drawColor(Color.rgb(118, 118, 118))
            c.drawBitmap(bmp, 0f, 0f, null)
            src = flat
            made = true
        }
        val w = (src.width * sampleScale).toInt().coerceAtLeast(12)
        val h = (src.height * sampleScale).toInt().coerceAtLeast(12)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val g = grayOf(small, 0, 0, w, h, w, h)
        small.recycle()
        if (made) src.recycle()
        return g
    }

    /** 裁 (x,y,cropW,cropH) 并缩放到 dstW×dstH 的灰度 FloatArray（缩放因子由调用方统一决定） */
    private fun grayOf(bmp: Bitmap, x: Int, y: Int, cropW: Int, cropH: Int, dstW: Int, dstH: Int): FloatArray {
        val safeW = cropW.coerceAtMost(bmp.width - x)
        val safeH = cropH.coerceAtMost(bmp.height - y)
        if (safeW <= 0 || safeH <= 0) return FloatArray(dstW * dstH)
        val crop = Bitmap.createBitmap(bmp, x, y, safeW, safeH)
        val small = Bitmap.createScaledBitmap(crop, dstW, dstH, true)
        crop.recycle()
        val px = IntArray(dstW * dstH)
        small.getPixels(px, 0, dstW, 0, 0, dstW, dstH)
        small.recycle()
        val g = FloatArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            g[i] = 0.299f * (p shr 16 and 0xff) + 0.587f * (p shr 8 and 0xff) + 0.114f * (p and 0xff)
        }
        return g
    }

    /** 滑窗 NCC 最佳命中：模板在 ROI 内 x+y 全程滑动，返回（分数, ox, oy）。
     *  积分图把每个窗口的均值/方差降到 O(1)，分子逐像素累加。
     *  （模板裁片与帧图标的手裁留白差异由滑窗吸收——离线 cv2 验证采用的同款算法。） */
    private fun nccBest(
        roi: FloatArray, roiW: Int, roiH: Int,
        tpl: FloatArray, tplW: Int, tplH: Int
    ): MatchResult? {
        if (tplW > roiW || tplH > roiH) {
            // 模板大于 ROI：降采样模板到 ROI 尺寸再整幅 NCC（无位置语义，位置记 0）。
            // ★ 旧实现直接遍历 roi 访问更短的 tpl 数组（模板任一维 > ROI 时必越界）。
            //   当前素材全部 ≤50×50 ≤ 53×280，此分支实际不会触发，仅防御。
            val tw = minOf(tplW, roiW)
            val th = minOf(tplH, roiH)
            val t2 = FloatArray(tw * th)
            for (y in 0 until th) {
                for (x in 0 until tw) {
                    t2[y * tw + x] = tpl[(y * tplH / th) * tplW + (x * tplW / tw)]
                }
            }
            return MatchResult(ncc(roi, t2), 0, 0)
        }
        // ROI 积分图（sum 与 sumsq）
        val iw = roiW + 1
        val sum = FloatArray(iw * (roiH + 1))
        val sq = FloatArray(iw * (roiH + 1))
        for (y in 0 until roiH) {
            var rs = 0f
            var rq = 0f
            for (x in 0 until roiW) {
                val v = roi[y * roiW + x]
                rs += v
                rq += v * v
                sum[(y + 1) * iw + (x + 1)] = sum[y * iw + (x + 1)] + rs
                sq[(y + 1) * iw + (x + 1)] = sq[y * iw + (x + 1)] + rq
            }
        }
        var mt = 0f
        for (v in tpl) mt += v
        mt /= tpl.size
        var dt = 0f
        for (v in tpl) {
            val d = v - mt
            dt += d * d
        }
        var best = 0f
        var bx = 0
        var by = 0
        val n = tplW * tplH
        for (oy in 0..roiH - tplH) {
            for (ox in 0..roiW - tplW) {
                val s = areaSum(sum, iw, ox, oy, tplW, tplH)
                val q = areaSum(sq, iw, ox, oy, tplW, tplH)
                val mr = s / n
                var num = 0f
                for (ty in 0 until tplH) {
                    for (tx in 0 until tplW) {
                        val v = roi[(oy + ty) * roiW + (ox + tx)]
                        num += (v - mr) * (tpl[ty * tplW + tx] - mt)
                    }
                }
                val wVar = q - s * s / n
                val den = sqrt(wVar * dt)
                val v = if (den <= 0f) 0f else (num / den).toFloat()
                if (v > best) {
                    best = v
                    bx = ox
                    by = oy
                }
            }
        }
        return MatchResult(best.coerceIn(0f, 1f), bx, by)
    }

    private class MatchResult(val score: Float, val ox: Int, val oy: Int)

    private fun areaSum(sum: FloatArray, iw: Int, x: Int, y: Int, w: Int, h: Int): Float {
        return sum[(y + h) * iw + (x + w)] - sum[y * iw + (x + w)] - sum[(y + h) * iw + x] + sum[y * iw + x]
    }

    /** 归一化互相关 NCC（灰度、已含均值归一），负相关按 0 处理 */
    private fun ncc(a: FloatArray, b: FloatArray): Float {
        var ma = 0f
        var mb = 0f
        for (i in a.indices) {
            ma += a[i]
            mb += b[i]
        }
        ma /= a.size
        mb /= b.size
        var num = 0f
        var da = 0f
        var db = 0f
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val den = sqrt(da * db)
        return if (den <= 0f) 0f else (num / den).coerceIn(0f, 1f)
    }
}
