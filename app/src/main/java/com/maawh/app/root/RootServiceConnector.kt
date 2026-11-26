package com.maawh.app.root

import android.content.Context
import android.os.IBinder
import com.maawh.app.IUserService
import com.maawh.app.LogLevel
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 后端的 App 侧连接器：su 拉起 RootServiceStarter（app_process），
 * 服务 binder 经 BinderHolderService 回路注册回来，包装成与 Shizuku 模式
 * 完全同构的 IUserService 返回。
 */
object RootServiceConnector {

    /** 最近一次启动/连接失败的描述（设置页状态展示用） */
    @Volatile
    var lastError: String? = null
        private set

    /** su 可执行性探测（PATH 找 su 或试跑 su -c id；首次会触发 root 授权弹窗） */
    fun isSuAvailable(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").start()
        p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (t: Throwable) {
        false
    }

    /**
     * 拉起（或复用已拉起的）root 服务进程并阻塞拿到 IUserService。
     * 启动命令在 root shell 里后台化（&），su 立即返回；binder 经
     * BinderHolderService 回路到达，这里最多等 15s。
     *
     * @throws RuntimeException 授权超时/启动失败/binder 未回投
     */
    fun ensure(context: Context, onLog: (String) -> Unit): IUserService {
        val deferred = RootServiceRegistry.register()
        lastError = null
        val apk = context.applicationInfo.sourceDir
        val uid = context.applicationInfo.uid
        val nice = context.packageName + ":root_service"
        // nsenter 进 init 挂载命名空间（su 默认继承 App 的命名空间，进不了 Android/data，
        // 与 Shizuku（adbd 拉起）同一环境）；不可用则直接跑。
        // 后台化 + stdio 断开：su 立即返回不挂会话，服务进程孤儿化由 init 收养继续跑
        val inner = "CLASSPATH=$apk exec app_process /system/bin --nice-name=$nice " +
                "com.maawh.app.root.RootServiceStarter --package=${context.packageName} --uid=$uid"
        val wrapped = "if nsenter -t 1 -m -- true 2>/dev/null; then " +
                "nsenter -t 1 -m -- $inner >/dev/null 2>&1 & " +
                "else $inner >/dev/null 2>&1 & fi"

        onLog("root: su 启动服务进程（首次会弹 root 授权）…")
        val p = try {
            ProcessBuilder("su", "-c", wrapped).start()
        } catch (t: Throwable) {
            lastError = "su 不可用：${t.message}"
            throw RuntimeException(lastError!!, t)
        }
        // su 首次运行会阻塞在授权弹窗：15s 内未退出视为授权未完成
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroy()
            lastError = "su 授权超时——请在 root 管理器里允许 MaaWH 获取 root"
            throw RuntimeException(lastError!!)
        }
        if (p.exitValue() != 0) {
            val err = runCatching { p.errorStream.bufferedReader().readText().take(300) }.getOrNull()
            lastError = "root 启动命令失败：${err?.ifBlank { "exit=${p.exitValue()}" }}"
            throw RuntimeException(lastError!!)
        }

        val binder = try {
            deferred.get(15, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            lastError = "root 服务 binder 未回投（15s 超时）——详见 root_launch 日志"
            throw RuntimeException(lastError!!, t)
        }
        onLog("root: 服务 binder 已回投")
        return IUserService.Stub.asInterface(binder)
    }

    /** 清理同名残留进程（模式切换/异常退出后的孤儿） */
    fun killResidual(): Boolean = try {
        val p = ProcessBuilder("su", "-c",
            "kill \$(pidof ${contextNiceName()}) 2>/dev/null").start()
        p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (t: Throwable) {
        false
    }

    private fun contextNiceName(): String =
        "com.maawh.app:root_service"

    /** root 可用性：PATH 里的 su 可执行文件（不触发授权弹窗） */
    fun isRootAvailable(ctx: Context): Boolean {
        // PATH 探测在 App 进程内意义有限（App 的 PATH 可能不带 sbin），
        // 用常见 root 挂载点兜底
        for (path in listOf("/system/bin/su", "/system/xbin/su", "/sbin/su",
                            "/su/bin/su", "/magisk/.core/bin/su")) {
            if (File(path).canExecute()) return true
        }
        return isSuAvailable()
    }
}
