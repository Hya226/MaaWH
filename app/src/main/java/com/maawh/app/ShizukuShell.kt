package com.maawh.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Shizuku 封装：绑定 UserService，让其在 shell/root 进程里执行系统命令。
 *
 * 截图 = screencap -p（经管道取回 PNG）
 * 点击 = input tap x y
 * 滑动 = input swipe ...
 *
 * 为什么用 UserService 而不是 Shizuku.newProcess：
 * newProcess 在 Shizuku API 13 已被官方改为私有并弃用（14 移除），
 * 官方推荐 UserService —— 且截图字节(数MB)超过 Binder 1MB 上限，需用管道流式传回。
 */
object ShizukuShell {

    /** 虚拟屏基准尺寸：必须与 whmx 模板/坐标基准帧(1280×720)一致（标准 16:9 720p） */
    const val VD_W = 1280
    const val VD_H = 720

    private var appContext: Context? = null

    @Volatile
    private var service: IUserService? = null

    /** 引擎路由开关：true=截图/点击/滑动发往虚拟屏，false=默认屏 */
    @Volatile
    var vdMode = false
        private set

    @Volatile
    private var pendingLatch: CountDownLatch? = null

    @Volatile
    private var args: Shizuku.UserServiceArgs? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IUserService.Stub.asInterface(binder)
            pendingLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** 快速检查 Shizuku 服务是否在线（不做权限与绑定检查）。 */
    fun pingReady(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    /** M4 探测：让 UserService 尝试创建虚拟显示器并返回结果摘要 */
    fun virtualProbe(): String = try {
        ensureService().virtualProbe() ?: "(null)"
    } catch (e: Throwable) {
        "探测失败: $e"
    }

    /** M4-②：保活虚拟屏并尝试把游戏投过去 */
    fun startVirtualGame(): String {
        val r = try {
            ensureService().startVirtualGame() ?: "(null)"
        } catch (e: Throwable) {
            "启动虚拟屏失败: $e"
        }
        // 服务端权威状态：返回含 vd_ok/vd_exist 即虚拟屏已存活
        vdMode = r.contains("vd_ok") || r.contains("vd_exist")
        return r
    }

    /** 与 UserService 重新同步虚拟屏存活状态（服务端权威），并返回是否存活 */
    fun syncVdMode(): Boolean {
        vdMode = try {
            ensureService().isVdAlive()
        } catch (e: Throwable) {
            false
        }
        return vdMode
    }

    /** 抓一帧虚拟屏 JPEG */
    fun grabVirtualFrame(): ByteArray = try {
        ensureService().grabVirtualFrame() ?: ByteArray(0)
    } catch (e: Throwable) {
        ByteArray(0)
    }

    fun stopVirtual() = try {
        ensureService().stopVirtual()
        vdMode = false
    } catch (e: Throwable) {
    }

    /** 虚拟屏是否存活（服务端权威状态） */
    fun isVdAlive(): Boolean = try {
        ensureService().isVdAlive()
    } catch (e: Throwable) {
        false
    }

    /** 读取媒体流音量（服务端 AudioManager，规避 shell 命令不生效的问题） */
    fun getMusicVolume(): Int = try {
        ensureService().getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
    } catch (e: Throwable) {
        -1
    }

    /** 设置媒体流音量 */
    fun setMusicVolume(index: Int) = try {
        ensureService().setStreamVolume(android.media.AudioManager.STREAM_MUSIC, index)
    } catch (e: Throwable) {
    }

    /** 流式触摸注入（实时手势）：按下 */
    fun touchDown(x: Int, y: Int) = try {
        ensureService().touchDown(x, y)
    } catch (e: Throwable) {
    }

    /** 流式触摸注入（实时手势）：移动 */
    fun touchMove(x: Int, y: Int) = try {
        ensureService().touchMove(x, y)
    } catch (e: Throwable) {
    }

    /** 流式触摸注入（实时手势）：抬起 */
    fun touchUp(x: Int, y: Int) = try {
        ensureService().touchUp(x, y)
    } catch (e: Throwable) {
    }

    /** 向虚拟屏注入点击（原生分辨率坐标） */
    fun injectTapVD(x: Int, y: Int): String = try {
        ensureService().injectTapVD(x, y) ?: "(null)"
    } catch (e: Throwable) {
        "注入失败: $e"
    }

    /** 向虚拟屏注入滑动（原生分辨率坐标） */
    fun injectSwipeVD(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): String = try {
        ensureService().injectSwipeVD(x1, y1, x2, y2, duration) ?: "(null)"
    } catch (e: Throwable) {
        "滑动注入失败: $e"
    }

    /** 按当前目标屏点击（vdMode=true → 虚拟屏，否则默认屏）。引擎回调与宿主编排共用。 */
    fun clickOnTarget(x: Int, y: Int): String {
        if (vdMode) return injectTapVD(x, y)
        execBlocking("input", "tap", x.toString(), y.toString())
        return "input tap $x,$y"
    }

    /** 按当前目标屏滑动（vdMode=true → 虚拟屏，否则默认屏）。 */
    fun swipeOnTarget(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): String {
        if (vdMode) return injectSwipeVD(x1, y1, x2, y2, duration)
        execBlocking("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), duration.toString())
        return "input swipe $x1,$y1->$x2,$y2"
    }

    /** 在 Application/Activity 启动时注入 context（取 applicationContext 即可）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 截取当前屏幕，返回与屏幕物理分辨率一致的位图。 */
    suspend fun screencap(): Bitmap {
        val png = exec("screencap", "-p")
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw RuntimeException("截图解码失败")
        return bmp
    }

    /** 模拟点击屏幕坐标 (x, y)。 */
    suspend fun tap(x: Int, y: Int) {
        exec("input", "tap", x.toString(), y.toString())
    }

    /** 模拟滑动。 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        exec("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())
    }

    // ------------------------------------------------------------------

    private suspend fun exec(vararg cmd: String): ByteArray =
        withContext(Dispatchers.IO) { execBlocking(*cmd) }

    /** 阻塞执行命令（供 MaaFramework 回调线程直接调用，勿在主线程使用）。 */
    internal fun execBlocking(vararg cmd: String): ByteArray {
        val srv = ensureService()
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        val buffer = ByteArrayOutputStream()
        // 并发读管道另一端，避免服务端写入超过管道缓冲(约64KB)时双方互等
        val reader = thread(name = "shizuku-pipe-reader") {
            try {
                val fis = FileInputStream(readEnd.fileDescriptor)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = fis.read(buf)
                    if (n < 0) break
                    buffer.write(buf, 0, n)
                }
                fis.close()
            } finally {
                runCatching { readEnd.close() }
            }
        }

        val exit = try {
            srv.exec(cmd, writeEnd)
        } finally {
            runCatching { writeEnd.close() }
        }
        reader.join(30_000)

        if (exit != 0) {
            val snippet = buffer.toByteArray()
                .toString(Charsets.UTF_8)
                .take(200)
                .replace('\n', ' ')
            throw RuntimeException(
                "命令执行失败(exit=$exit): ${cmd.joinToString(" ")}" +
                    if (snippet.isBlank()) "" else " | $snippet"
            )
        }
        return buffer.toByteArray()
    }

    /** 获取/建立 UserService 连接。 */
    private fun ensureService(): IUserService {
        service?.let { if (it.asBinder().isBinderAlive) return it }
        val ctx = checkNotNull(appContext) { "ShizukuShell 未 init()" }

        synchronized(lock) {
            service?.let { if (it.asBinder().isBinderAlive) return it }
            require(Shizuku.pingBinder()) { "Shizuku 服务未运行" }

            val latch = CountDownLatch(1)
            pendingLatch = latch
            val a = Shizuku.UserServiceArgs(
                ComponentName(ctx, ShellUserService::class.java)
            )
                .processNameSuffix("shizuku")
                .debuggable(false)
                .version(1)
                .daemon(false)
            args = a

            Shizuku.bindUserService(a, conn)
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw RuntimeException("连接 Shizuku UserService 超时")
            }
            return checkNotNull(service) { "Shizuku UserService 连接失败" }
        }
    }

    private val lock = Any()
}
