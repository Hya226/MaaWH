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

    /**
     * 错误上报钩子：App 侧挂到日志页（ShizukuShell 不依赖 UI）。
     * 此前一批 catch (Throwable) {} 把真实故障吞成"没反应"，这里分级上报：
     * 用户主动操作失败 → WRN；高频路径（引擎截图/手势 MOVE）→ TRACE 防刷屏。
     */
    @Volatile
    var errorHook: ((LogLevel, String) -> Unit)? = null

    private fun report(level: LogLevel, msg: String) {
        try { errorHook?.invoke(level, msg) } catch (ignored: Throwable) {}
    }

    private var appContext: Context? = null

    @Volatile
    private var service: IUserService? = null

    /** 引擎路由开关：true=截图/点击/滑动发往虚拟屏，false=默认屏 */
    @Volatile
    var vdMode = false
        private set

    @Volatile
    private var pendingLatch: CountDownLatch? = null

    /**
     * UserService「新绑定完成」钩子（对齐 maameow startAutoRestore 的"服务连接建立"时机：
     * 连接建立 = 上一个游戏会话必然已结束，此刻的静音标记必是残留）。只在真正新建连接后
     * 触发一次，已绑定的快路径不触发；在工作线程回调，钩子内部自行处理线程与耗时。
     */
    @Volatile
    var onServiceReady: (() -> Unit)? = null

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
            report(LogLevel.WRN, "虚拟屏状态同步失败（Shizuku 未就绪？）: ${e.message}")
            false
        }
        return vdMode
    }

    /** 抓一帧虚拟屏 JPEG */
    fun grabVirtualFrame(): ByteArray = try {
        ensureService().grabVirtualFrame() ?: ByteArray(0)
    } catch (e: Throwable) {
        // 引擎截图每轮都走这里，Shizuku 掉线时会高频触发 → TRACE 防刷屏
        report(LogLevel.TRACE, "抓虚拟屏帧失败: ${e.message}")
        ByteArray(0)
    }

    fun stopVirtual() = try {
        ensureService().stopVirtual()
        vdMode = false
    } catch (e: Throwable) {
        report(LogLevel.WRN, "停止虚拟屏失败: $e")
    }

    /** 虚拟屏是否存活（服务端权威状态） */
    fun isVdAlive(): Boolean = try {
        ensureService().isVdAlive()
    } catch (e: Throwable) {
        false
    }

    /**
     * 仅对游戏静音：appops 按包拒绝其播放音频（PLAY_AUDIO deny，同 maameow 的按包+uid 语义）。
     * 不动系统音量，音量键调节也不影响；appops 状态持久，结束时必须恢复（见 resetGameAudio）。
     * 不要用 --uid：uid 级 mode 包级 reset 清不掉，是静音残留反复出现的根因之一。
     */
    fun setGameAudioMuted(muted: Boolean): Boolean {
        return try {
            if (muted) {
                execBlocking("appops", "set", MaaConst.GAME_PKG, "PLAY_AUDIO", "deny")
                // uid 级收回 default：避免与按包 deny 产生 allow/deny 叠加歧义
                runCatching { execBlocking("appops", "set", "--uid", MaaConst.GAME_PKG, "PLAY_AUDIO", "default") }
            } else {
                execBlocking("appops", "set", MaaConst.GAME_PKG, "PLAY_AUDIO", "allow")
            }
            true
        } catch (e: Throwable) {
            report(LogLevel.WRN, "游戏音频静音失败: ${e.message}")
            false
        }
    }

    /**
     * 主动放行游戏音频（ColorOS AudioHardening 反制，进虚拟屏时调用）：
     * uid 级 PLAY_AUDIO + CONTROL_AUDIO(_PARTIAL) 显式 allow。
     * AudioHardening 的「后台闩锁」会跨游戏重启、跨虚拟屏重建存活——虚拟屏里的游戏每条
     * 新音轨一启动就被打 muted(opPlayAudio)，appops 值看着全干净也被压（2026-09-19 实测，
     * 反制当场验证有效：显式 allow 后 muted source:none 立即解除）。
     * 静音开启时不要调（包级 deny 优先于 uid 级 allow，但别依赖这个优先级）。
     * AOSP 设备没有后两个 op，set 失败无害。返回是否全部执行成功。
     */
    fun assertGameAudioAllowed(): Boolean {
        if (runCatching { execBlocking("appops", "set", "--uid", MaaConst.GAME_PKG, "PLAY_AUDIO", "allow") }.isFailure) {
            return false
        }
        val a = runCatching { execBlocking("appops", "set", MaaConst.GAME_PKG, "CONTROL_AUDIO", "allow") }.isSuccess
        val b = runCatching { execBlocking("appops", "set", MaaConst.GAME_PKG, "CONTROL_AUDIO_PARTIAL", "allow") }.isSuccess
        return a && b
    }

    /** 游戏当前是否被 appops 拒绝播放音频（包级或 uid 级 deny 都算）；Shizuku 不可用时按未静音处理 */
    fun isGameAudioMuted(): Boolean = try {
        String(execBlocking("appops", "get", MaaConst.GAME_PKG, "PLAY_AUDIO"), Charsets.UTF_8)
            .contains("deny")
    } catch (e: Throwable) {
        false
    }

    /**
     * 把游戏音频恢复成系统默认（对齐 maameow 的 resetPackage：reset 整包 op 回默认态，
     * 而不是显式 allow）；--uid default 兜住历史版本用 --uid deny 设下的 uid 级残留。
     *
     * ColorOS AudioHardening 反制（2026-09-19 实测）：静音 deny→allow 切换后，系统音频加固
     * 会对虚拟屏里的游戏进入「后台评估」状态，每 ~7s 把声音重新压上（音频事件
     * muted source:opPlayAudio，解除后 58ms 内复压），且 appops 值看着全是干净也被压。
     * 恢复时把 PLAY_AUDIO 提到 uid 级显式 allow、CONTROL_AUDIO(_PARTIAL) 显式 allow，
     * 声明「此包音频不受加固管理」。AOSP 设备没有这几个 op，set 失败无害（runCatching）。
     * 返回恢复后 PLAY_AUDIO 是否已不在 deny。
     */
    fun resetGameAudio(): Boolean = try {
        execBlocking("appops", "reset", MaaConst.GAME_PKG)
        runCatching { execBlocking("appops", "set", "--uid", MaaConst.GAME_PKG, "PLAY_AUDIO", "default") }
        runCatching { execBlocking("appops", "set", "--uid", MaaConst.GAME_PKG, "PLAY_AUDIO", "allow") }
        runCatching { execBlocking("appops", "set", MaaConst.GAME_PKG, "CONTROL_AUDIO", "allow") }
        runCatching { execBlocking("appops", "set", MaaConst.GAME_PKG, "CONTROL_AUDIO_PARTIAL", "allow") }
        !isGameAudioMuted()
    } catch (e: Throwable) {
        report(LogLevel.WRN, "恢复游戏声音失败: ${e.message}")
        false
    }

    /**
     * 幂等恢复游戏声音：检测到 PLAY_AUDIO 仍是 deny（残留）才 reset 回默认。
     * appops 是持久系统设置——MaaWH 所有「不再管控游戏」的出口（队列收尾/关虚拟屏/
     * 退出/划掉最近任务、以及下次启动凭标记的自愈）都必须走这里，否则残留 deny
     * 会让用户自己打开游戏也没声。返回是否执行了恢复。
     */
    fun restoreGameAudioIfMuted(): Boolean {
        if (!isGameAudioMuted()) return false
        val ok = resetGameAudio()
        if (ok) report(LogLevel.INFO, "检测到游戏静音残留（appops PLAY_AUDIO deny），已恢复游戏声音")
        return ok
    }

    /**
     * 派发「恢复游戏声音」到独立 shell 后台进程（孤儿进程，主 sh 立即返回）。
     * 与 [restoreGameAudioIfMuted] 的区别：退出瞬间（划掉最近任务/onDestroy）ColorOS 会
     * 立刻杀掉 MaaWH 进程，普通恢复线程根本跑不完（2026-09-19 实测残留 deny 就是这么来的）；
     * 孤儿进程挂在 init 下，MaaWH 死了照样把恢复执行完。命令毫秒级返回，可在主线程同步调用。
     * 只在 UserService 已绑定时派发（静音本来就是它设的，此时必已绑定）；未绑定则静默跳过。
     */
    /**
     * 当前物理屏（display 0）前台 Activity 的包名；Shizuku 断线/查询失败返回 null。
     * 挂机守护用：游戏被用户切到物理屏玩 = 该值等于游戏包名（虚拟屏 display 的
     * topResumedActivity 行排在物理屏行之后，取第一行即物理屏）。
     */
    fun topForegroundPkg(): String? {
        return try {
            val out = execBlocking("/system/bin/sh", "-c",
                "dumpsys activity a | grep topResumedActivity | head -1"
            ).toString(Charsets.UTF_8)
            Regex("topResumedActivity=ActivityRecord\\{[^}]*u0 ([^/ }]+)").find(out)?.groupValues?.get(1)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 预埋延时恢复孤儿：静音生效期间调用，每次派发一个「90 秒后自检」的独立 sh 进程
     * （挂在 init 下，force-stop 杀不到——它不属于 MaaWH 包）。自检条件：虚拟屏已消失
     * （= MaaWH 会话已结束，无论 App 是被划卡片/一键清理/LMKD 杀掉还是正常退出）且
     * deny 还挂着 → 清除恢复。MaaWH 若还活着正常挂机，虚拟屏在 → 不动作。
     * 由挂机守护轮询（10s）持续续埋，App 任意方式死亡后 90 秒内必有孤儿醒来收尾。
     * ColorOS 划卡片被处理成 force-stop、任何回调都不触发的场景（2026-09-19 实测）
     * 靠这个兜底。
     *
     * ⚠ 派发的孤儿是 & 后台进程，会持有管道写端直到它退出（90 秒）——绝不能走
     * execBlocking（reader.join 30s 会阻塞调用方，QueueRunner 启动被拖 30s 的实测
     * 来源）。这里 fire-and-forget：srv.exec 返回即孤儿已 fork，读端回收挂在
     * 独立 daemon 线程等 EOF 自清理，调用方立即返回。
     */
    fun scheduleGameAudioRestoreGuard(delaySec: Long = 90): Boolean {
        val srv = service?.takeIf { it.asBinder().isBinderAlive } ?: return false
        val pipe = ParcelFileDescriptor.createPipe()
        return try {
            val cmd = arrayOf(
                "/system/bin/sh", "-c",
                "( sleep $delaySec; dumpsys display | grep -q MaaWH-VD || " +
                    "{ appops get ${MaaConst.GAME_PKG} PLAY_AUDIO | grep -q deny && " +
                    "{ appops reset ${MaaConst.GAME_PKG}; " +
                    "appops set --uid ${MaaConst.GAME_PKG} PLAY_AUDIO allow; " +
                    "appops set ${MaaConst.GAME_PKG} CONTROL_AUDIO allow; " +
                    "appops set ${MaaConst.GAME_PKG} CONTROL_AUDIO_PARTIAL allow; }; } ) >/dev/null 2>&1 &"
            )
            thread(name = "audio-guard-dispatch", isDaemon = true) {
                try {
                    srv.exec(cmd, pipe[1])
                } catch (_: Throwable) {
                } finally {
                    runCatching { pipe[0].close() }
                    runCatching { pipe[1].close() }
                }
            }
            true
        } catch (e: Throwable) {
            report(LogLevel.WRN, "预埋延时恢复失败: ${e.message}")
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            false
        }
    }

    fun requestGameAudioRestore(): Boolean {        val srv = service?.takeIf { it.asBinder().isBinderAlive } ?: return false
        val pipe = ParcelFileDescriptor.createPipe()
        return try {
            runCatching { pipe[0].close() }   // 读端 App 侧不用（后台进程输出已重定向 /dev/null）
            srv.exec(
                arrayOf(
                    "/system/bin/sh", "-c",
                    "( appops get ${MaaConst.GAME_PKG} PLAY_AUDIO | grep -q deny " +
                        "&& { appops reset ${MaaConst.GAME_PKG}; " +
                        "appops set --uid ${MaaConst.GAME_PKG} PLAY_AUDIO default; " +
                        "appops set --uid ${MaaConst.GAME_PKG} PLAY_AUDIO allow; " +
                        "appops set ${MaaConst.GAME_PKG} CONTROL_AUDIO allow; " +
                        "appops set ${MaaConst.GAME_PKG} CONTROL_AUDIO_PARTIAL allow; } ) >/dev/null 2>&1 &"
                ),
                pipe[1]
            )
            true
        } catch (e: Throwable) {
            report(LogLevel.WRN, "派发恢复游戏声音失败: ${e.message}")
            false
        } finally {
            runCatching { pipe[1].close() }
        }
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
        report(LogLevel.WRN, "设置音量失败: ${e.message}")
    }

    /** 流式触摸注入（实时手势）：按下 */
    fun touchDown(x: Int, y: Int) = try {
        ensureService().touchDown(x, y)
    } catch (e: Throwable) {
        report(LogLevel.WRN, "触摸注入(按下)失败: ${e.message}")
    }

    /** 流式触摸注入（实时手势）：移动（手势中逐点调用，失败只打 TRACE 防刷屏） */
    fun touchMove(x: Int, y: Int) = try {
        ensureService().touchMove(x, y)
    } catch (e: Throwable) {
        report(LogLevel.TRACE, "触摸注入(移动)失败: ${e.message}")
    }

    /** 流式触摸注入（实时手势）：抬起 */
    fun touchUp(x: Int, y: Int) = try {
        ensureService().touchUp(x, y)
    } catch (e: Throwable) {
        report(LogLevel.TRACE, "触摸注入(抬起)失败: ${e.message}")
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

    /**
     * 以 shell 权限执行系统命令（appops set 这类自授权小操作）。
     * 阻塞调用，勿在主线程直接使用；失败抛异常。
     */
    fun execShellCommand(vararg cmd: String): ByteArray = execBlocking(*cmd)

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
            checkNotNull(service) { "Shizuku UserService 连接失败" }
            onServiceReady?.invoke()
            return service!!
        }
    }

    private val lock = Any()
}
