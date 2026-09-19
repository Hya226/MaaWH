package com.maawh.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 抽卡记录 OCR 抽象层。
 *
 * 调用方（GachaCrawler）把裁好的小图（单行文字，放大 ocr.upscale 倍）
 * 压成 PNG → 标准 base64 喂进来，实现方返回识别出的文本行。
 * 单行小图一般只返回 0~1 行；返回列表是为将来整列识别留余地。
 *
 * 实现路线（2026-09-18 定）：本地 PP-OCR rec 模型转 ONNX + onnxruntime-android，
 * 模型文件放 files/gacha/ocr_models/（adb 推送，不进 APK）。接好后把 StubOcr 换掉。
 */
interface GachaOcr {
    suspend fun requestOCR(base64: String): List<GachaOcr.Line>

    data class Line(
        val text: String,
        val score: Float = 1f
    )

    companion object {
        /** Bitmap → PNG → base64（无损，小图体积可接受） */
        fun encode(bmp: android.graphics.Bitmap): String {
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }
}

/** 占位实现：什么都不识别，只保证全链路先跑通。 */
object StubOcr : GachaOcr {
    override suspend fun requestOCR(base64: String): List<GachaOcr.Line> {
        android.util.Log.w("MaaWH", "StubOcr.requestOCR: OCR 未接入（图 ${base64.length} chars）")
        return emptyList()
    }
}

/**
 * 本地 PP-OCR rec 实现（ch_PP-OCRv4_rec_infer.onnx，Apache-2.0）。
 * 模型放 files/gacha/ocr_models/（adb 直推，不进 APK）；字典内嵌在模型
 * metadata "character" 里（6623 字），无需单独文件。
 *
 * 预处理与 CTC 解码是 PC 端 gacha/_ocr_validate.py「手写通道」的原样移植，
 * 2026-09-18 已在真实帧上与 RapidOCR 引擎对拍一致（时间 10/10，名字 9/10）。
 */
class OnnxPpocrOcr(context: Context) : GachaOcr {

    private val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
    private val session: ai.onnxruntime.OrtSession
    internal val charset: List<String>

    init {
        val dir = File(context.filesDir, "gacha/ocr_models")
        val f = File(dir, "ch_PP-OCRv4_rec_infer.onnx")
        // APK assets 内置副本自动释放（首装自举）：模型 + 兜底字典
        GachaCrawler.Points.ensureAssetFile(context, "gacha/ocr_models/ch_PP-OCRv4_rec_infer.onnx", f)
        GachaCrawler.Points.ensureAssetFile(
            context, "gacha/ocr_models/ppocr_keys_v1.txt", File(dir, "ppocr_keys_v1.txt")
        )
        if (!f.isFile) throw IllegalStateException("OCR 模型缺失：${f.absolutePath}")
        session = env.createSession(f.absolutePath, ai.onnxruntime.OrtSession.SessionOptions())
        // 字典优先读模型内嵌 metadata；读不到退回同目录的 ppocr_keys_v1.txt
        charset = session.metadata.customMetadata["character"]?.split("\n")
            ?: File(context.filesDir, "gacha/ocr_models/ppocr_keys_v1.txt")
                .takeIf { it.isFile }
                ?.readText()
                ?.split("\n")
                ?.map { it.trimEnd('\r') }
                ?.also { if (it.size < 6000) throw IllegalStateException("字典文件行数异常：${it.size}") }
            ?: throw IllegalStateException("字典缺失：模型无 character 元数据，且 gacha/ocr_models/ppocr_keys_v1.txt 不存在")
    }

    override suspend fun requestOCR(base64: String): List<GachaOcr.Line> = withContext(Dispatchers.IO) {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext emptyList()
        // 等比缩到高 48；RGB 平面 CHW；(x/255-0.5)/0.5 —— 与 PC 验证脚本一致
        val h = 48
        val w = maxOf(8, Math.round(h.toDouble() * src.width / src.height).toInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== src) scaled.recycle()
        val plane = w * h
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            data[i] = norm(p shr 16 and 0xff)
            data[plane + i] = norm(p shr 8 and 0xff)
            data[2 * plane + i] = norm(p and 0xff)
        }
        ai.onnxruntime.OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong())
        ).use { t ->
            session.run(mapOf(session.inputNames.first() to t)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val out = res[0].value as Array<Array<FloatArray>>   // [1][T][6625]
                listOf(decode(out[0]))
            }
        }
    }

    private fun norm(v: Int): Float = (v / 255f - 0.5f) / 0.5f

    /** CTC 贪心：0=blank 跳过、重复折叠、charset.size+1 位是空格 */
    private fun decode(pred: Array<FloatArray>): GachaOcr.Line {
        val sb = StringBuilder()
        var last = -1
        var sum = 0f
        var n = 0
        for (probs in pred) {
            var bi = 0
            var bv = -Float.MAX_VALUE
            for (c in probs.indices) {
                if (probs[c] > bv) { bv = probs[c]; bi = c }
            }
            if (bi != 0 && bi != last) {
                sb.append(if (bi == charset.size + 1) ' ' else charset[bi - 1])
                sum += bv
                n++
            }
            last = bi
        }
        return GachaOcr.Line(sb.toString(), if (n == 0) 0f else sum / n)
    }
}

object GachaOcrFactory {
    /** 有模型走本地 ONNX；没有则退回 Stub（全链路可跑但抓不到数据）。失败详情落盘 ocr_error.txt */
    fun create(ctx: Context, onLog: (String) -> Unit = {}): GachaOcr =
        try {
            val o = OnnxPpocrOcr(ctx)
            onLog("OCR：本地 PP-OCR v4 已加载（${o.charset.size} 字）")
            o
        } catch (e: Throwable) {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            android.util.Log.e("MaaWH-Gacha", "OCR init failed", e)
            runCatching {
                File(ctx.filesDir, "gacha/ocr_error.txt").writeText(sw.toString())
            }
            onLog("OCR 模型加载失败，退回 Stub：$e")
            StubOcr
        }
}

/** OCR 名字纠错：编辑距离对字典，距离过大的保留原值（调用方决定要不要 WARN） */
object GachaDictionary {

    /** 器者名字典；后续从 files/gacha/names.json 读，先内置空表不影响流程 */
    val names: List<String> = emptyList()

    fun correct(raw: String, maxDistance: Int = 2): Pair<String, Boolean> {
        if (raw.isEmpty() || names.isEmpty()) return raw to true
        var best = raw
        var bestDist = Int.MAX_VALUE
        for (n in names) {
            val d = levenshtein(raw, n)
            if (d < bestDist) {
                bestDist = d
                best = n
            }
        }
        return if (bestDist in 1..maxDistance) best to true else raw to (bestDist == 0)
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 时间列正则校验：OCR 错一个字就整条作废，不能让坏时间入库 */
    private val TIME_RE = Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日(\\d{1,2})时(\\d{1,2})分")

    /** 解析 "2026年9月10日10时46分" → epoch 分钟（本地时区）；解析失败返回 null */
    fun parseTime(text: String): Long? {
        val m = TIME_RE.find(text) ?: return null
        return runCatching {
            val t = java.time.LocalDateTime.of(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                m.groupValues[4].toInt(), m.groupValues[5].toInt()
            )
            t.atZone(java.time.ZoneId.systemDefault()).toEpochSecond() / 60
        }.getOrNull()
    }

    /** epoch 分钟 → uid 用纯数字串 "202609101046" */
    fun formatMinute(ts: Long): String {
        val t = java.time.Instant.ofEpochSecond(ts * 60).atZone(java.time.ZoneId.systemDefault())
        return "%04d%02d%02d%02d%02d".format(
            Locale.US, t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute
        )
    }

    /** epoch 分钟 → 游戏风格原文 "2026年8月1日10时30分"（手动补录的 time 字段用） */
    fun chineseTime(ts: Long): String {
        val t = java.time.Instant.ofEpochSecond(ts * 60).atZone(java.time.ZoneId.systemDefault())
        return "%d年%d月%d日%d时%d分".format(
            Locale.US, t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute
        )
    }

    /** 手动补录的时间解析：2026-08-01 10:30 / 2026/8/1 10:30 / 2026年8月1日10时30分 / 2026-08-01（默认12:00） */
    fun parseManualTime(text: String): Long? {
        val zone = java.time.ZoneId.systemDefault()
        val attempts = listOf(
            {
                java.time.LocalDateTime.parse(
                    text, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                )
            },
            {
                java.time.LocalDateTime.parse(
                    text, java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d H:mm")
                )
            },
            {
                java.time.LocalDateTime.parse(
                    text, java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日H时m分")
                )
            },
            {
                java.time.LocalDate.parse(
                    text, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                ).atTime(12, 0)
            },
            {
                java.time.LocalDate.parse(
                    text, java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d")
                ).atTime(12, 0)
            }
        )
        for (attempt in attempts) {
            val t = runCatching { attempt() }.getOrNull() ?: continue
            return t.atZone(zone).toEpochSecond() / 60
        }
        return null
    }
}
