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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val ocr: GachaOcr,
    private val onLog: (String, LogLevel) -> Unit,
    private val isStopped: () -> Boolean
) {

    class ScanException(msg: String) : Exception(msg)

    data class Result(val counts: LinkedHashMap<String, Int>, val pages: Int, val elapsedMs: Long)

    // ---- 网格参数（720p 帧实测，物资库-资源页）----
    private val gridX0 = 355
    private val gridY0 = 118
    private val colPeriod = 120
    private val rowPeriod = 130
    private val cell = 112          // 格子含边框尺寸
    private val iconInset = 8       // 图标内缩（去格子边框装饰）
    private val cols = 6
    private val rows = 4            // 屏内完整行数（第 5 行只露出边缘，放弃）
    private val slideFrom = 800 to 630
    private val slideTo = 800 to 350

    private val matchThreshold = 0.72f
    /**
     * 模板/ROI 的降采样尺寸必须**同尺度**：ROI 采样 96px→44（缩 0.458），模板原图
     * ≈88px→40（缩 0.454）——滑窗匹配要求两者像素密度一致，尺度不一致 NCC 全崩
     * （tplGraySize=28 的首版实锤：第 1 页 0 命中）。模板略小于 ROI 是刻意的：
     * 图标本体 ≈88px < 格子内容区 96px，滑窗在 44×44 内找 40×40 的最佳位置。
     */
    private val tplGraySize = 40
    private val roiGraySize = 44

    /** 单页里每个格子的识别结果 */
    private data class CellHit(val name: String, val score: Float, val gx: Int, val gy: Int)

    // ---------------- 主流程 ----------------

    suspend fun scanSuspend(onProgress: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val templates = ensureTemplates(ctx)
        if (templates.isEmpty()) {
            throw ScanException("图标模板库为空（assets/warehouse/resource 缺失或释放失败）")
        }
        onLog("仓库识别开始：模板 ${templates.size} 个", LogLevel.INFO)

        val counts = LinkedHashMap<String, Int>()
        var pages = 0
        var retries = 0
        var prevKeys = emptySet<String>()
        var stableRuns = 0
        var firstPageChecked = false

        while (true) {
            coroutineContext.ensureActive()
            if (isStopped()) throw ScanException("已停止")

            val frame = grabStable()
            if (frame == null) {
                onLog("抓帧失败，重试…", LogLevel.WRN)
                kotlinx.coroutines.delay(800)
                continue
            }

            if (pages == 0) {
                // 诊断：把第一页识别实际用的帧存下来（排障「零识别」用，与素材/网格对照）
                runCatching {
                    val d = File(ctx.filesDir, "warehouse")
                    d.mkdirs()
                    val out = File(d, "debug_first_frame.jpg")
                    out.outputStream().use { o -> frame.compress(Bitmap.CompressFormat.JPEG, 90, o) }
                    onLog("诊断：第一页帧已存 ${out.path}（${frame.width}×${frame.height}）", LogLevel.TRACE)
                }
            }
            val hits = scanPage(frame, templates, counts, onProgress, pages == 0)
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
                    throw ScanException("未识别到任何资源——请确认游戏已停在【物资库-资源】页（可先跑【仓库识别】流程导航）")
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
            ShizukuShell.injectSwipeVD(slideFrom.first, slideFrom.second, slideTo.first, slideTo.second, 600)
            kotlinx.coroutines.delay(900)  // 等滑动惯性+页面稳定
        }

        backToHome(onProgress)

        val elapsed = System.currentTimeMillis() - started
        saveResult(counts)
        onLog(
            "仓库识别完成：共 $pages 页，资源 ${counts.size} 种（数量识别成功 ${counts.count { it.value >= 0 }}）" +
                " · 耗时 ${elapsed / 1000}s · 明细见 files/warehouse/resource_scan.json",
            LogLevel.SUCCESS
        )
        Result(counts, pages, elapsed)
    }

    /** 单页：按网格逐格滑窗匹配 + 新资源 OCR 数量，返回本页命中（同时把数量并入 counts） */
    private suspend fun scanPage(
        frame: Bitmap,
        templates: List<Template>,
        counts: LinkedHashMap<String, Int>,
        onProgress: (String) -> Unit,
        verbose: Boolean
    ): List<CellHit> {
        val hits = ArrayList<CellHit>()
        var newOnes = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                coroutineContext.ensureActive()
                if (isStopped()) throw ScanException("已停止")
                val gx = gridX0 + c * colPeriod
                val gy = gridY0 + r * rowPeriod
                if (gx + cell > frame.width || gy + cell > frame.height) continue

                val icon = grayOf(frame, gx + iconInset, gy + iconInset, cell - iconInset * 2, cell - iconInset * 2, roiGraySize)
                val colorMean = colorMeanOf(frame, gx + iconInset, gy + iconInset, cell - iconInset * 2, cell - iconInset * 2)
                // 颜色预筛：ROI 中心均值色与模板均值色的 RGB 距离排序，取前 10 候选（55 模板全滑窗太慢）
                val candidates = templates
                    .map { it to colorDist(colorMean, it.meanColor) }
                    .sortedBy { it.second }
                    .take(10)
                    .map { it.first }
                var best: Pair<Template, Float>? = null
                for (t in candidates) {
                    val s = nccSearch(icon, roiGraySize, roiGraySize, t.gray, tplGraySize, tplGraySize)
                    if (best == null || s > best!!.second) best = t to s
                }
                val (t, s) = best ?: continue
                if (s < matchThreshold) {
                    if (verbose) onLog("格子($r,$c) 未过阈值 best=${t.name}:${"%.3f".format(s)}", LogLevel.TRACE)
                    continue  // 空槽或未知物品（模板库未覆盖）
                }
                if (verbose) onLog("格子($r,$c) 命中 ${t.name}:${"%.3f".format(s)}", LogLevel.TRACE)
                hits.add(CellHit(t.name, s, gx, gy))

                if (!counts.containsKey(t.name)) {
                    newOnes++
                    counts[t.name] = ocrQuantity(frame, gx, gy)
                }
            }
        }
        if (hits.isNotEmpty()) onProgress("仓库识别：累计 ${counts.size} 种 · 本页 ${hits.size} 格")
        if (newOnes > 0) onLog("本页新增资源 $newOnes 种", LogLevel.TRACE)
        return hits
    }

    /** 数量 OCR：格子右下角数量白字区域，裁片放大 2 倍走 PP-OCR，取整数 */
    private suspend fun ocrQuantity(frame: Bitmap, gx: Int, gy: Int): Int {
        return try {
            // 数量角标在图标右下角（实测 ≈x+52..x+106, y+76..y+106），留 2px 余量
            val rx = gx + 50
            val ry = gy + 74
            val rw = 58.coerceAtMost(frame.width - rx)
            val rh = 34.coerceAtMost(frame.height - ry)
            if (rw <= 0 || rh <= 0) return -1
            var crop = Bitmap.createBitmap(frame, rx, ry, rw, rh)
            crop = Bitmap.createScaledBitmap(crop, rw * 2, rh * 2, true)
            val lines = ocr.requestOCR(GachaOcr.encode(crop))
            crop.recycle()
            val text = lines.maxByOrNull { it.score }?.text?.trim().orEmpty()
            val digits = text.filter { it.isDigit() }
            digits.toIntOrNull() ?: -1  // -1 = 数量识别失败（资源本身仍记录）
        } catch (e: Throwable) {
            onLog("数量 OCR 失败：${e.message}", LogLevel.WRN)
            -1
        }
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

    private class Template(val name: String, val gray: FloatArray, val meanColor: FloatArray)

    /** assets/warehouse/resource/ → files/warehouse/resource/ 自举释放，然后加载为灰度模板。
     *  用「assets 清单指纹」比对 files 端记录：素材更新（打包）后自动整目录重建——
     *  此前「目录非空不释放」导致 assets 更新后手机旧模板永不失效（实锤：换游戏内
     *  裁片后真机仍用旧裸图标模板，怎么调算法都是 0 命中）。 */
    private fun ensureTemplates(ctx: Context): List<Template> {
        val dir = File(ctx.filesDir, "warehouse/resource")
        val stamp = File(ctx.filesDir, "warehouse/resource_fingerprint.txt")
        val names = runCatching { ctx.assets.list("warehouse/resource") ?: emptyArray() }.getOrDefault(emptyArray())
        val fingerprint = names.sorted().joinToString("\n") { n ->
            val l = runCatching { ctx.assets.open("warehouse/resource/$n").use { it.available() } }.getOrDefault(-1)
            "$n:$l"
        } + "|${names.size}"
        val local = if (stamp.isFile) stamp.readText().trim() else null
        if (!dir.isDirectory || dir.listFiles().isNullOrEmpty() || local != fingerprint) {
            dir.deleteRecursively()
            dir.mkdirs()
            for (n in names) {
                ctx.assets.open("warehouse/resource/$n").use { input ->
                    File(dir, n).outputStream().use { output -> input.copyTo(output) }
                }
            }
            stamp.writeText(fingerprint)
            onLog("模板库已重建（${names.size} 个，指纹${if (local == null) "首建" else "更新"}）", LogLevel.INFO)
        }
        val files = dir.listFiles { f -> f.extension.equals("png", true) }?.sortedBy { it.name } ?: emptyList()
        val out = ArrayList<Template>(files.size)
        for (f in files) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            out.add(
                Template(
                    f.nameWithoutExtension,
                    toGrayNormalized(bmp, tplGraySize),
                    colorMeanOf(bmp, 0, 0, bmp.width, bmp.height)
                )
            )
            bmp.recycle()
        }
        return out
    }

    // ---------------- 图像原语 ----------------

    /** ROI 彩色均值（颜色预筛用） */
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

    /** 裁 ROI → 缩放 → 灰度 FloatArray；模板侧先合成中性灰底消透明角 */
    private fun toGrayNormalized(bmp: Bitmap, size: Int): FloatArray {
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
        val small = Bitmap.createScaledBitmap(src, size, size, true)
        val g = grayOf(small, 0, 0, size, size, size)
        small.recycle()
        if (made) src.recycle()
        return g
    }

    private fun grayOf(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int, size: Int): FloatArray {
        val safeW = w.coerceAtMost(bmp.width - x)
        val safeH = h.coerceAtMost(bmp.height - y)
        if (safeW <= 0 || safeH <= 0) return FloatArray(size * size)
        var crop = Bitmap.createBitmap(bmp, x, y, safeW, safeH)
        val small = Bitmap.createScaledBitmap(crop, size, size, true)
        crop.recycle()
        val px = IntArray(size * size)
        small.getPixels(px, 0, size, 0, 0, size, size)
        small.recycle()
        val g = FloatArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            g[i] = 0.299f * (p shr 16 and 0xff) + 0.587f * (p shr 8 and 0xff) + 0.114f * (p and 0xff)
        }
        return g
    }

    /**
     * 滑窗 NCC：模板在 ROI 内逐位置取归一化互相关的最大值。
     * 用积分图把每个窗口的均值/方差降到 O(1)，分子逐像素累加。
     * （模板裁片与 ROI 的构图不必严格对齐——用户素材是手裁的，尺寸/留白有差异，
     *   滑窗能吸收这些差异；这也是离线 cv2 验证采用的同款算法。）
     */
    private fun nccSearch(
        roi: FloatArray, roiW: Int, roiH: Int,
        tpl: FloatArray, tplW: Int, tplH: Int
    ): Float {
        if (tplW > roiW || tplH > roiH) {
            // 模板比 ROI 还大：退化成整幅 NCC（模板缩放语义不变）
            return ncc(roi, tpl)
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
        val dtDen = sqrt(dt * tpl.size)
        var best = 0f
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
                if (v > best) best = v
            }
        }
        return best.coerceIn(0f, 1f)
    }

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

    // ---------------- 结果落盘 ----------------

    private fun saveResult(counts: Map<String, Int>) {
        try {
            val dir = File(ctx.filesDir, "warehouse")
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val sb = StringBuilder("{\n  \"scannedAt\": \"$ts\",\n  \"counts\": {\n")
            val entries = counts.entries.sortedBy { it.key }
            entries.forEachIndexed { i, (name, qty) ->
                sb.append("    \"").append(name.replace("\"", "\\\"")).append("\": ").append(qty)
                sb.append(if (i == entries.lastIndex) "\n" else ",\n")
            }
            sb.append("  }\n}")
            val tmp = File(dir, "resource_scan.json.tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(File(dir, "resource_scan.json"))) {
                File(dir, "resource_scan.json").writeText(sb.toString())
                tmp.delete()
            }
        } catch (e: Throwable) {
            onLog("结果落盘失败：${e.message}", LogLevel.WRN)
        }
    }
}
