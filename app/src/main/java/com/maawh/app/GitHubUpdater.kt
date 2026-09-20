package com.maawh.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内 GitHub 更新（对标 maameow 的更新管理，简化为设置页手动检查）。
 *
 * 渠道语义（对齐本项目发布惯例）：
 * - 稳定版 = releases 里第一个正式发行版（draft=false 且 prerelease=false）中的 `*release*.apk`
 * - 测试版 = releases 里第一个预发行版（draft=false 且 prerelease=true）中的 `*beta*.apk`
 *
 * 网络全部 HttpURLConnection 不引库，必须在 IO 线程调用。
 * 安装经 FileProvider 把 externalFilesDir/update/ 下的 APK 共享给系统安装器
 * （REQUEST_INSTALL_PACKAGES，用户首次需在系统设置允许「安装未知应用」）。
 */
object GitHubUpdater {

    private const val REPO = "Hya226/MaaWH"
    private const val API_RELEASES = "https://api.github.com/repos/$REPO/releases?per_page=20"
    private const val PREFS = "maawh_update"
    private const val KEY_CHANNEL = "channel"

    const val CHANNEL_STABLE = "stable"
    const val CHANNEL_BETA = "beta"

    /** 检查到的新版本 */
    data class AvailableUpdate(
        val tag: String,       // v0.2.1
        val version: String,   // 0.2.1
        val channel: String,   // stable / beta
        val apkName: String,   // MaaWH-release-v0.2.1.apk
        val url: String,       // browser_download_url
        val size: Long,
        val notes: String,     // 发行版说明原文
    )

    fun savedChannel(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHANNEL, CHANNEL_STABLE) ?: CHANNEL_STABLE

    fun saveChannel(ctx: Context, channel: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CHANNEL, channel).apply()
    }

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /** 数值逐段比较版本号（v 前缀与不足段已容忍）：a>b → 1，a<b → -1，相等 → 0 */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /**
     * 检查指定渠道的更新。返回 null = 已是最新或该渠道暂无发行版；
     * 抛 IOException = 连不上 GitHub / API 非 200（调用方提示检查网络）。
     */
    fun checkUpdate(ctx: Context, channel: String): AvailableUpdate? {
        val releases = api(
            API_RELEASES
        ) { conn ->
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONArray(body)
        }
        val wantPrerelease = channel == CHANNEL_BETA
        val assetKeyword = if (wantPrerelease) "beta" else "release"
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            if (r.optBoolean("draft", false)) continue
            if (r.optBoolean("prerelease", false) != wantPrerelease) continue
            val tag = r.optString("tag_name")
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                val name = a.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                if (!name.contains(assetKeyword, ignoreCase = true)) continue
                val ver = tag.removePrefix("v")
                if (compareVersion(ver, currentVersion(ctx)) <= 0) return null
                return AvailableUpdate(
                    tag = tag,
                    version = ver,
                    channel = channel,
                    apkName = name,
                    url = a.optString("browser_download_url"),
                    size = a.optLong("size", 0L),
                    notes = r.optString("body", ""),
                )
            }
            // 该发行版没挂对应渠道的 apk → 继续找更早的发行版
        }
        return null
    }

    private fun <T> api(url: String, parse: (HttpURLConnection) -> T): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "MaaWH-App")
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("GitHub API HTTP ${conn.responseCode}")
            }
            return parse(conn)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载更新包到 externalFilesDir/update/（.part 临时文件，完成后改名为正式包名，
     * 供安装器与 FileProvider 使用）。onProgress 收到 0..100。
     * 抛 IOException = 网络中断/HTTP 非 200；调用方负责提示并清理。
     */
    fun download(ctx: Context, upd: AvailableUpdate, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.getExternalFilesDir(null), "update").apply { mkdirs() }
        val out = File(dir, upd.apkName)
        val part = File(dir, upd.apkName + ".part")
        val conn = URL(upd.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "MaaWH-App")
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("下载 HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: upd.size
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) throw IOException("重命名下载文件失败")
            return out
        } finally {
            conn.disconnect()
            if (part.exists() && !out.exists()) part.delete()
        }
    }

    /** 拉起系统安装器。canInstall 为 false 时跳转「安装未知应用」授权页并返回 false。 */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
