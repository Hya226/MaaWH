package com.maawh.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 抽卡记录的数据层（files/gacha/）。
 *
 * - all_records.json：全量记录，新→旧。游戏只留 30 天，这里永久累积（>30 天的
 *   记录游戏端再也拉不到了，不能丢）。
 * - config.json：每池最新 ANCHORS_PER_POOL 条 uid 作「锚点」+ 上次抓取时间。
 *   后续抓取命中锚点即截断翻页；翻到最后一页都没命中（记录过期 >30 天）就靠
 *   uid 去重全量合并，天然兜底。
 *
 * uid 规则：[纯数字时间]_[池子]_[器者名]_[序号]，例 202609101046_限时渠道_蛙锣_1。
 * 序号按「同分钟同池」分组编号（不按名字分组）——某一行名字 OCR 失败被跳过时，
 * 不会扰动同组其他记录的编号，重爬 uid 才能对得上锚点。
 */
object GachaStore {

    private const val DIR = "gacha"
    private const val RECORDS = "all_records.json"
    private const val CONFIG = "config.json"
    private const val ACCOUNTS_FILE = "accounts.json"
    private const val ACCOUNTS_DIR = "accounts"
    private const val UP_MARKS = "up_marks.json"
    private const val NAMES_FILE = "names.json"
    private const val ANCHORS_PER_POOL = 5

    const val RARITY_TOP = "特出"
    const val RARITY_MID = "优异"
    const val RARITY_LOW = "新生"

    data class Record(
        val uid: String,
        val ts: Long,       // epoch 分钟（本地时区），排序/统计用，不依赖字符串
        val time: String,   // 游戏原文 "2026年9月10日10时46分"
        val pool: String,
        val banner: String, // 小类（招集列原文，如「限时/至乐如真」），不影响 uid
        val name: String,
        val rarity: String, // 特出/优异/新生
        val manual: Boolean = false // 手动补录的记录（编辑面板里可删除）
    )

    /** 爬虫产出的一行（uid 未定），交 buildRecords 编号 */
    data class RawRow(
        val ts: Long,
        val time: String,
        val name: String,
        val rarity: String,
        val banner: String
    )

    /** 锚点：某分钟 + 器者名。名字比对容忍 OCR 小误差，不用 uid 死比 */
    data class Anchor(val t: Long, val n: String)

    data class Config(
        val anchors: Map<String, List<Anchor>> = emptyMap(),
        val lastCrawlMs: Long = 0L
    ) {
        val isFirstRun: Boolean get() = anchors.isEmpty()
    }

    // ---------- uid ----------

    fun uidOf(ts: Long, pool: String, name: String, seq: Int): String =
        "${GachaDictionary.formatMinute(ts)}_${pool}_${name}_$seq"

    /**
     * 把一个池子本页抓到的行（按游戏列表顺序，新→旧）编成 Record。
     * 序号在（分钟, 池子）内从 1 递增；runningSeq 由爬虫跨页持有——
     * 同一分钟跨页时只要按抓取顺序连续喂进来，编号就稳定。
     */
    fun buildRecords(
        pool: String,
        rows: List<RawRow>,
        runningSeq: MutableMap<Long, Int> = HashMap()
    ): List<Record> = rows.map { r ->
        val n = (runningSeq[r.ts] ?: 0) + 1
        runningSeq[r.ts] = n
        Record(uidOf(r.ts, pool, r.name, n), r.ts, r.time, pool, r.banner, r.name, r.rarity)
    }

    /** epoch 分钟 → 面板短格式 "09-10 10:46" */
    fun shortTime(ts: Long): String =
        java.time.Instant.ofEpochSecond(ts * 60).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))

    // ---------- 读写 ----------

    private fun rootDir(ctx: Context) = File(ctx.filesDir, DIR)
    private fun registryFile(ctx: Context) = File(rootDir(ctx), ACCOUNTS_FILE)
    private fun accountDir(ctx: Context, id: String) = File(File(rootDir(ctx), ACCOUNTS_DIR), id)

    /**
     * 当前激活账号的数据目录。所有记录/锚点读写都落到这里——
     * 抓取写哪个账号 = 界面上选中的账号，各账号锚点互不干扰。
     */
    private fun dir(ctx: Context) = accountDir(ctx, ensureRegistry(ctx).activeId).apply { mkdirs() }
    private fun recordsFile(ctx: Context) = File(dir(ctx), RECORDS)
    private fun configFile(ctx: Context) = File(dir(ctx), CONFIG)

    /** 原子写：先写临时文件再改名，避免写一半被读/崩。父目录不存在时自动创建 */
    private fun atomicWrite(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    fun loadRecords(ctx: Context): List<Record> = try {
        val arr = JSONArray(recordsFile(ctx).takeIf { it.isFile }?.readText() ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Record(
                uid = o.optString("uid"),
                ts = o.optLong("ts"),
                time = o.optString("time"),
                pool = o.optString("pool"),
                banner = o.optString("banner"),
                name = o.optString("name"),
                rarity = o.optString("rarity"),
                manual = o.optBoolean("manual", false)
            )
        }
    } catch (e: Throwable) {
        emptyList()
    }

    fun loadConfig(ctx: Context): Config = try {
        val o = JSONObject(configFile(ctx).takeIf { it.isFile }?.readText() ?: "{}")
        val a = o.optJSONObject("anchors") ?: JSONObject()
        val map = HashMap<String, List<Anchor>>()
        for (key in a.keys()) {
            val arr = a.optJSONArray(key) ?: JSONArray()
            map[key] = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                Anchor(e.optLong("t"), e.optString("n"))
            }
        }
        Config(map, o.optLong("lastCrawlMs"))
    } catch (e: Throwable) {
        Config()
    }

    /**
     * 抓取收口：合并新记录（uid 去重）→ 刷新每池锚点 → 记时间，一步完成。
     * freshByPool：池子 → 本次新抓的记录（任意顺序）。返回新增条数。
     */
    fun commitCrawl(ctx: Context, freshByPool: Map<String, List<Record>>): Int {
        synchronized(this) {
            val existing = ArrayList(loadRecords(ctx))
            val seen = HashSet(existing.map { it.uid })
            var added = 0
            for ((_, fresh) in freshByPool) {
                for (r in fresh) {
                    if (r.uid in seen) continue
                    seen.add(r.uid)
                    existing.add(r)
                    added++
                }
            }
            // 新→旧；同分钟保持抓取顺序（mergeSort 稳定）
            existing.sortByDescending { it.ts }
            atomicWrite(recordsFile(ctx), serializeRecords(existing))

            val anchors = JSONObject()
            existing.groupBy { it.pool }.forEach { (pool, rs) ->
                anchors.put(pool, JSONArray(rs.take(ANCHORS_PER_POOL).map {
                    JSONObject().apply {
                        put("t", it.ts)
                        put("n", it.name)
                    }
                }))
            }
            val cfg = JSONObject().apply {
                put("anchors", anchors)
                put("lastCrawlMs", System.currentTimeMillis())
            }
            atomicWrite(configFile(ctx), cfg.toString())
            return added
        }
    }

    // ---------- 统计（面板用；rs 必须新→旧） ----------

    data class PoolStats(
        val total: Int,        // 30 天窗口内总抽数
        val teCount: Int,      // 特出数
        val avgText: String,   // 平均抽数（total/teCount），无特出时 "—"
        val dian: Int          // 当前垫抽：最新一条往回数到最近一次特出为止
    )

    fun poolStats(rs: List<Record>): PoolStats {
        val total = rs.size
        val te = rs.count { it.rarity == RARITY_TOP }
        var dian = 0
        for (r in rs) {
            if (r.rarity == RARITY_TOP) break
            dian++
        }
        return PoolStats(
            total = total,
            teCount = te,
            avgText = if (te == 0) "—" else String.format(Locale.US, "%.1f", total.toDouble() / te),
            dian = dian
        )
    }

    /**
     * 每条特出的消耗抽数 = 从上一个（更旧的）特出【之后】到这一发【含】的抽数，
     * 即两特出在列表里的位置差。例：百花图卷 →(2个新生)→ 银香囊，银香囊 = 3 抽。
     * rs 新→旧。窗口内最早的特出按「窗口首条 = 第一抽」默认计：它的消耗 =
     * 总条数 - 它的索引（它之前垫的新生数 + 它自己）。
     */
    fun teListWithCost(rs: List<Record>): List<Pair<Record, String>> {
        val teIdx = rs.withIndex().filter { it.value.rarity == RARITY_TOP }.map { it.index }
        val out = ArrayList<Pair<Record, String>>(teIdx.size)
        for ((k, idx) in teIdx.withIndex()) {
            // rs 新→旧，更旧的特出索引更大
            val cost = if (k + 1 < teIdx.size) "${teIdx[k + 1] - idx} 抽" else "${rs.size - idx} 抽"
            out.add(rs[idx] to cost)
        }
        return out
    }

    /**
     * 手动补录一条记录（如 30 天窗口外的旧卡池）。不改动锚点——补录多为旧数据，
     * 不应影响下次抓取的截断点。序号取该（分钟,池）组内已有最大序号 +1。
     */
    fun addManualRecord(
        ctx: Context,
        pool: String,
        banner: String,
        name: String,
        rarity: String,
        ts: Long
    ): Record = synchronized(this) {
        val all = ArrayList(loadRecords(ctx))
        var maxSeq = 0
        for (r in all) {
            if (r.pool == pool && r.ts == ts) {
                val s = r.uid.substringAfterLast("_").toIntOrNull() ?: continue
                if (s > maxSeq) maxSeq = s
            }
        }
        val taken = HashSet(all.map { it.uid })
        var seq = maxSeq + 1
        var uid = uidOf(ts, pool, name, seq)
        while (uid in taken) {
            seq++
            uid = uidOf(ts, pool, name, seq)
        }
        val rec = Record(uid, ts, GachaDictionary.chineseTime(ts), pool, banner, name, rarity, manual = true)
        all.add(rec)
        all.sortByDescending { it.ts }
        atomicWrite(recordsFile(ctx), serializeRecords(all))
        rec
    }

    private fun serializeRecords(list: List<Record>): String {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("uid", r.uid)
                put("ts", r.ts)
                put("time", r.time)
                put("pool", r.pool)
                put("banner", r.banner)
                put("name", r.name)
                put("rarity", r.rarity)
                if (r.manual) put("manual", true)
            })
        }
        return arr.toString()
    }

    /** 删除一条记录（编辑面板只对手动补录的记录开放删除）。返回是否删除成功。不动锚点 */
    fun deleteRecord(ctx: Context, uid: String): Boolean = synchronized(this) {
        val all = ArrayList(loadRecords(ctx))
        if (all.removeIf { it.uid == uid }) {
            atomicWrite(recordsFile(ctx), serializeRecords(all))
            true
        } else false
    }

    // ---------- UP 标注（pool → banner → UP 器者名） ----------

    /**
     * 「标注UP器者」的存储，单独文件（config.json 会被 commitCrawl 整体重写，
     * 混进去容易被覆盖）。key 与记录的 banner 原文一致；值为空串视同未标注。
     */
    fun loadUpMarks(ctx: Context): MutableMap<String, MutableMap<String, String>> = try {
        val o = JSONObject(File(dir(ctx), UP_MARKS).takeIf { it.isFile }?.readText() ?: "{}")
        val out = LinkedHashMap<String, MutableMap<String, String>>()
        for (pk in o.keys()) {
            val po = o.optJSONObject(pk) ?: continue
            val m = LinkedHashMap<String, String>()
            for (bk in po.keys()) m[bk] = po.optString(bk)
            out[pk] = m
        }
        out
    } catch (e: Throwable) {
        LinkedHashMap()
    }

    fun saveUpMarks(ctx: Context, marks: Map<String, Map<String, String>>) = synchronized(this) {
        val o = JSONObject()
        for ((pk, m) in marks) {
            val po = JSONObject()
            for ((bk, v) in m) if (v.isNotBlank()) po.put(bk, v.trim())
            if (po.length() > 0) o.put(pk, po)
        }
        atomicWrite(File(dir(ctx), UP_MARKS), o.toString())
    }

    // ---------- 器者名单（OCR 纠错字典，账号无关的全局文件） ----------

    /**
     * 名单 = 后台预置（assets/gacha/names.json 随包，首装文件不存在时释放一次）
     * + 用户手动编辑（编辑面板/adb 直推覆盖），全局共享，与账号和抽卡记录完全解耦。
     */
    fun loadNames(ctx: Context): List<String> = synchronized(this) {
        val f = File(rootDir(ctx), NAMES_FILE)
        if (!f.isFile) {
            runCatching {
                ctx.assets.open("gacha/$NAMES_FILE").use { input ->
                    f.parentFile?.mkdirs()
                    f.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        try {
            val arr = JSONArray(f.takeIf { it.isFile }?.readText() ?: "[]")
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() } }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun saveNames(ctx: Context, names: List<String>) = synchronized(this) {
        val arr = JSONArray()
        names.filter { it.isNotBlank() }.distinct().forEach { arr.put(it.trim()) }
        atomicWrite(File(rootDir(ctx), NAMES_FILE), arr.toString())
    }

    /**
     * 名单改名联动（当前激活账号）：记录里所有 old 改为 newName（uid 按原序号重建），
     * 锚点与 UP 标注同步替换。返回改动的记录条数。
     * 其他账号的记录不动——下次抓取时靠字典纠错自动归正。
     */
    fun renameEverywhere(ctx: Context, old: String, newName: String): Int = synchronized(this) {
        if (old == newName || newName.isBlank()) return 0
        val all = ArrayList(loadRecords(ctx))
        var changed = 0
        for (i in all.indices) {
            val r = all[i]
            if (r.name != old) continue
            val seq = r.uid.substringAfterLast("_").toIntOrNull() ?: 1
            all[i] = r.copy(name = newName, uid = uidOf(r.ts, r.pool, newName, seq))
            changed++
        }
        if (changed > 0) atomicWrite(recordsFile(ctx), serializeRecords(all))

        val cfg = loadConfig(ctx)
        saveConfigAnchors(
            ctx,
            cfg.anchors.mapValues { (_, list) -> list.map { if (it.n == old) it.copy(n = newName) else it } },
            cfg.lastCrawlMs
        )

        val marks = loadUpMarks(ctx)
        var dirty = false
        for ((_, m) in marks) {
            for ((k, v) in m) if (v == old) { m[k] = newName; dirty = true }
        }
        if (dirty) saveUpMarks(ctx, marks)
        changed
    }

    /**
     * 名单删除联动（当前激活账号）：从记录中删掉所有该名字的记录，锚点同步清理。
     * 名单条目本身由调用方维护。返回删除的记录条数。
     */
    fun deleteRecordsByName(ctx: Context, name: String): Int = synchronized(this) {
        val all = ArrayList(loadRecords(ctx))
        val before = all.size
        all.removeAll { it.name == name }
        val removed = before - all.size
        if (removed > 0) atomicWrite(recordsFile(ctx), serializeRecords(all))

        val cfg = loadConfig(ctx)
        saveConfigAnchors(
            ctx,
            cfg.anchors.mapValues { (_, list) -> list.filter { it.n != name } },
            cfg.lastCrawlMs
        )

        val marks = loadUpMarks(ctx)
        var dirty = false
        for ((_, m) in marks) {
            for ((k, v) in m) if (v == name) { m.remove(k); dirty = true }
        }
        if (dirty) saveUpMarks(ctx, marks)
        removed
    }

    private fun saveConfigAnchors(ctx: Context, anchors: Map<String, List<Anchor>>, lastCrawlMs: Long) {
        val a = JSONObject()
        for ((pool, list) in anchors) {
            a.put(pool, JSONArray(list.map {
                JSONObject().apply { put("t", it.t); put("n", it.n) }
            }))
        }
        atomicWrite(configFile(ctx), JSONObject().apply {
            put("anchors", a)
            put("lastCrawlMs", lastCrawlMs)
        }.toString())
    }

    // ---------- UP 统计（面板顶部统计卡片用；rs 必须新→旧） ----------

    data class UpStats(
        val total: Int,        // 总抽数
        val teCount: Int,      // 特出（出卡）数
        val waiCount: Int,     // 歪：特出中与所属小类标注 UP 不符的数量
        val upAvgText: String, // 总抽数 / UP出卡数（无 UP 出卡时 "—"）
        val marked: Boolean    // 该池标注过 UP 才显示「出卡数/歪 + UP平均」，否则「出卡数 + 六星平均」
    )

    /**
     * 歪的判定按特出所属小类（banner）精确对号：该小类标注了 UP 且 ≠ 特出名才算歪，
     * 未标注的小类不判歪——历史小类未逐一标注时不至于全红。
     */
    fun upStats(rs: List<Record>, poolUpMarks: Map<String, String>): UpStats {
        val total = rs.size
        val te = rs.count { it.rarity == RARITY_TOP }
        var wai = 0
        for (r in rs) {
            if (r.rarity != RARITY_TOP) continue
            val up = poolUpMarks[r.banner] ?: continue
            if (up.isNotBlank() && up != r.name) wai++
        }
        val marked = poolUpMarks.values.any { it.isNotBlank() }
        val upCount = te - wai
        val upAvg = if (!marked || upCount <= 0) "—" else
            String.format(Locale.US, "%.1f", total.toDouble() / upCount)
        return UpStats(total, te, wai, upAvg, marked)
    }

    // ---------- 多账号 ----------

    data class AccountInfo(val id: String, val name: String, val createdAt: Long)

    private class Registry(val activeId: String, val accounts: MutableList<AccountInfo>)

    private fun newAccountId() = "a" + System.currentTimeMillis()

    /**
     * 读取账号注册表；首次访问时把旧的散装数据（files/gacha/all_records.json、
     * config.json）整体迁移成「默认」账号，用户无感。
     */
    private fun ensureRegistry(ctx: Context): Registry = synchronized(this) {
        val f = registryFile(ctx)
        if (f.isFile) {
            try {
                val o = JSONObject(f.readText())
                val arr = o.optJSONArray("accounts") ?: JSONArray()
                val list = (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    AccountInfo(e.optString("id"), e.optString("name"), e.optLong("createdAt"))
                }.toMutableList()
                if (list.isNotEmpty()) {
                    val active = o.optString("activeId")
                    val act = if (list.any { it.id == active }) active else list[0].id
                    return Registry(act, list)
                }
            } catch (ignored: Throwable) {
            }
        }
        val legacyRecords = File(rootDir(ctx), RECORDS)
        val legacyConfig = File(rootDir(ctx), CONFIG)
        val hasLegacy = legacyRecords.isFile || legacyConfig.isFile
        val id = if (hasLegacy) "default" else newAccountId()
        val name = if (hasLegacy) "默认" else "账号1"
        if (hasLegacy) {
            val d = accountDir(ctx, id)
            d.mkdirs()
            legacyRecords.renameTo(File(d, RECORDS))
            legacyConfig.renameTo(File(d, CONFIG))
        }
        val reg = Registry(id, mutableListOf(AccountInfo(id, name, System.currentTimeMillis())))
        saveRegistry(ctx, reg)
        reg
    }

    private fun saveRegistry(ctx: Context, reg: Registry) {
        val arr = JSONArray()
        reg.accounts.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("createdAt", it.createdAt)
            })
        }
        atomicWrite(registryFile(ctx), JSONObject().apply {
            put("activeId", reg.activeId)
            put("accounts", arr)
        }.toString())
    }

    fun listAccounts(ctx: Context): List<AccountInfo> = ensureRegistry(ctx).accounts.toList()

    fun activeAccountId(ctx: Context): String = ensureRegistry(ctx).activeId

    fun activeAccountName(ctx: Context): String =
        ensureRegistry(ctx).let { reg -> reg.accounts.find { it.id == reg.activeId }?.name ?: "" }

    fun setActiveAccount(ctx: Context, id: String) = synchronized(this) {
        val reg = ensureRegistry(ctx)
        if (reg.accounts.any { it.id == id } && reg.activeId != id) {
            saveRegistry(ctx, Registry(id, reg.accounts))
        }
    }

    /** 新建账号并立即激活（建完就能直接抓） */
    fun createAccount(ctx: Context, name: String): AccountInfo = synchronized(this) {
        val reg = ensureRegistry(ctx)
        val info = AccountInfo(
            newAccountId(),
            name.ifBlank { "账号${reg.accounts.size + 1}" },
            System.currentTimeMillis()
        )
        accountDir(ctx, info.id).mkdirs()
        reg.accounts.add(info)
        saveRegistry(ctx, Registry(info.id, reg.accounts))
        info
    }

    fun renameAccount(ctx: Context, id: String, name: String) = synchronized(this) {
        if (name.isBlank()) return
        val reg = ensureRegistry(ctx)
        val idx = reg.accounts.indexOfFirst { it.id == id }
        if (idx >= 0) {
            reg.accounts[idx] = reg.accounts[idx].copy(name = name)
            saveRegistry(ctx, Registry(reg.activeId, reg.accounts))
        }
    }

    /** 删除账号及其全部记录；至少保留一个。返回是否删除成功 */
    fun deleteAccount(ctx: Context, id: String): Boolean = synchronized(this) {
        val reg = ensureRegistry(ctx)
        if (reg.accounts.size <= 1) return false
        if (!reg.accounts.removeIf { it.id == id }) return false
        val newActive = if (reg.activeId == id) reg.accounts[0].id else reg.activeId
        saveRegistry(ctx, Registry(newActive, reg.accounts))
        accountDir(ctx, id).deleteRecursively()
        true
    }
}
