package com.maawh.app

import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * 预览直渲通路协调器（App 进程）：管理「唯一渲染窗口」的认领与降级。
 *
 * 服务端（ShellUserService + libmaawh_vd）同一时刻只向一个 Surface GPU 直绘；
 * 三处预览（主页 imageShot、全屏页、悬浮窗）谁可见谁 claim，后 claim 者接管。
 * 降级契约：[nativeActive]=false 期间各处保留原 JPEG 轮询位图路径（VdStreamer），
 * 直渲通路任何一环不健康（服务端不支持 / 挂载后帧计数不增长 / 渲染中途停摆）
 * 都回到位图路径，功能不丢，只是回到旧帧率。
 *
 * 线程：claim/release 必须主线程（SurfaceView 回调即主线程）；attach/确认查询走工作线程。
 */
object VdPreview {

    /** 直渲通路健康且当前在用（SurfaceView 据此切换 Surface/Bitmap 显示路径） */
    @Volatile
    var nativeActive = false
        private set

    private val main = Handler(Looper.getMainLooper())

    private var claimedOwner: Any? = null
    private var claimedSurface: android.view.Surface? = null
    private var confirmRun: Runnable? = null
    private var watchdogRun: Runnable? = null
    private var reclaimRun: Runnable? = null

    private const val CONFIRM_STEP_MS = 700L
    private const val CONFIRM_ATTEMPTS = 4
    private const val WATCHDOG_STEP_MS = 4000L
    private const val WATCHDOG_MISSES = 3      // 3 个周期无新帧（≈12s，游戏画面完全静止）→ 判死
    private const val RECLAIM_RETRY_MS = 15_000L

    /** 认领渲染窗口（后到者接管；主线程调用）。 */
    fun claim(owner: Any, surface: android.view.Surface) {
        claimedOwner = owner
        claimedSurface = surface
        cancelTimers()
        nativeActive = false
        thread(name = "vd-preview-attach") {
            val ok = ShizukuShell.setPreviewSurface(surface)
            main.post {
                if (claimedOwner !== owner) return@post   // 已被接管/释放
                if (ok) scheduleConfirm(owner) else nativeActive = false
            }
        }
    }

    /** 释放渲染窗口（仅当 owner 仍是当前认领者时生效；主线程调用）。 */
    fun release(owner: Any) {
        if (claimedOwner !== owner) return
        claimedOwner = null
        claimedSurface = null
        cancelTimers()
        nativeActive = false
        thread(name = "vd-preview-detach") {
            ShizukuShell.releasePreviewSurface()
        }
    }

    private fun cancelTimers() {
        confirmRun?.let { main.removeCallbacks(it) }
        watchdogRun?.let { main.removeCallbacks(it) }
        reclaimRun?.let { main.removeCallbacks(it) }
        confirmRun = null
        watchdogRun = null
        reclaimRun = null
    }

    /** 挂载后轮询服务端帧计数：涨了 = 直渲通路健康；连试多次全 0 = 保持降级。 */
    private fun scheduleConfirm(owner: Any) {
        confirmRun?.let { main.removeCallbacks(it) }
        var attempt = 0
        val r = object : Runnable {
            override fun run() {
                if (claimedOwner !== owner) return
                attempt++
                if (ShizukuShell.previewFrameCount() > 0) {
                    nativeActive = true
                    startWatchdog(owner)
                    return
                }
                if (attempt < CONFIRM_ATTEMPTS) {
                    main.postDelayed(this, CONFIRM_STEP_MS)
                } else {
                    scheduleRetry(owner)
                }
            }
        }
        confirmRun = r
        main.postDelayed(r, CONFIRM_STEP_MS)
    }

    /** 直渲在用期间的健康看门狗：帧计数停摆（EGL 静默死亡等）→ 判死降级。 */
    private fun startWatchdog(owner: Any) {
        watchdogRun?.let { main.removeCallbacks(it) }
        var last = -1L
        var misses = 0
        val r = object : Runnable {
            override fun run() {
                if (!nativeActive || claimedOwner !== owner) return
                val n = ShizukuShell.previewFrameCount()
                if (n < 0) {
                    // 服务没了（Shizuku 掉线）：直接降级
                    nativeActive = false
                    return
                }
                if (n == last) {
                    misses++
                    if (misses >= WATCHDOG_MISSES) {
                        nativeActive = false
                        scheduleRetry(owner)
                        return
                    }
                } else {
                    misses = 0
                    last = n
                }
                main.postDelayed(this, WATCHDOG_STEP_MS)
            }
        }
        watchdogRun = r
        main.postDelayed(r, WATCHDOG_STEP_MS)
    }

    /** 降级后定时自愈重试（只重试认领过的 owner）：游戏恢复渲染就自动切回直渲。 */
    private fun scheduleRetry(owner: Any) {
        reclaimRun?.let { main.removeCallbacks(it) }
        val r = Runnable {
            reclaimRun = null
            if (claimedOwner !== owner) return@Runnable
            val surface = claimedSurface
            if (owner is VdSurfaceView && surface != null && surface.isValid) {
                claim(owner, surface)
            }
        }
        reclaimRun = r
        main.postDelayed(r, RECLAIM_RETRY_MS)
    }
}
