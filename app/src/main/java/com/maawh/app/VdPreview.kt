package com.maawh.app

import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * 预览直渲通路协调器（App 进程）：管理「唯一渲染窗口」的认领与降级 + 帧率角标数据。
 *
 * 服务端（ShellUserService + libmaawh_vd）同一时刻只向一个 Surface GPU 直绘；
 * 三处预览（主页 imageShot、全屏页、悬浮窗）谁可见谁 claim，后 claim 者接管。
 * 降级契约：[nativeActive]=false 期间各处保留原 JPEG 轮询位图路径（VdStreamer），
 * 直渲通路任何一环不健康（服务端不支持 / 挂载后帧计数不增长 / 渲染中途停摆）
 * 都回到位图路径，功能不丢，只是回到旧帧率。
 *
 * ★ 线程纪律（2026-09-24 ANR 实测教训）：所有 Binder 调用（previewFrameCount 等）
 * 必须在后台线程做——ensureService 断连重绑最长阻塞 15s，跑在主线程直接 ANR。
 * 本类的结构：claim/release 在主线程只改状态，确认+看门狗+帧率采样合并为
 * 一个后台循环（[confirmAndSampleLoop]），结果用 @Volatile 字段交给 UI tick 读取。
 */
object VdPreview {

    /** 直渲通路健康且当前在用（SurfaceView 据此切换 Surface/Bitmap 显示路径） */
    @Volatile
    var nativeActive = false
        private set

    /** 预览左上角的实时帧率文案（每秒刷新）：直渲时 = 游戏帧率（native drawn 计数差分），
     *  降级时 = 「JPEG N」（VdStreamer 解码计数差分，兼直渲健康指示灯）；null = 不显示 */
    @Volatile
    var fpsText: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    // 单线程池：确认/看门狗/采样循环都在这里跑（Binder 调用绝不进主线程）
    private val samplerPool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "vd-preview-sampler")
    }

    @Volatile
    private var claimedOwner: Any? = null
    private var claimedSurface: android.view.Surface? = null
    private var reclaimRun: Runnable? = null

    /** 采样循环存活标志（executor 内单线程串行，无竞争）；防重复起循环 */
    @Volatile
    private var loopAlive = false

    /** 认领渲染窗口（后到者接管；主线程调用）。 */
    fun claim(owner: Any, surface: android.view.Surface) {
        claimedOwner = owner
        claimedSurface = surface
        cancelTimers()
        nativeActive = false
        samplerPool.execute { confirmAndSampleLoop(owner) }
        thread(name = "vd-preview-attach") {
            val ok = ShizukuShell.setPreviewSurface(surface)
            // 挂载结果不需要额外动作：后台确认循环会凭帧计数判断直渲是否真的活了
            if (!ok) android.util.Log.i("MaaWH", "预览直渲不可用（服务端拒绝挂载），走 JPEG 降级")
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

    /** VdStreamer 启动时叫一声，保证降级路径也有帧率可显示 */
    fun onStreamerStarted() {
        if (!loopAlive) {
            claimedOwner?.let { samplerPool.execute { confirmAndSampleLoop(it) } }
        }
    }

    private fun cancelTimers() {
        reclaimRun?.let { main.removeCallbacks(it) }
        reclaimRun = null
    }

    /**
     * 确认 + 看门狗 + 帧率采样三合一后台循环（按 owner 生命周期）：
     * 1) 挂载后轮询帧计数：涨了 = 直渲通路健康；
     * 2) 采样阶段每秒差分出 fpsText，同时承担看门狗（连续无新帧 → 判死降级）；
     * 3) owner 被接管/释放后自然退出。
     */
    private fun confirmAndSampleLoop(owner: Any) {
        loopAlive = true
        try {
            runConfirmAndSampleLoop(owner)
        } finally {
            loopAlive = false
        }
    }

    private fun runConfirmAndSampleLoop(owner: Any) {
        // —— 确认阶段 ——
        var attempt = 0
        var base = -1L
        while (attempt < CONFIRM_ATTEMPTS) {
            if (claimedOwner !== owner) return
            try { Thread.sleep(CONFIRM_STEP_MS) } catch (e: InterruptedException) { return }
            val n = ShizukuShell.previewFrameCount()
            if (n > 0) {
                base = n
                nativeActive = true
                break
            }
            attempt++
        }
        // —— 采样 + 看门狗阶段 ——
        var lastNative = base   // 用确认阶段的计数当基线，首秒帧率才不会算出垃圾值
        var lastDecoded = 0L
        var lastConsumed = -1L
        var wdRef = -1L
        var misses = 0
        while (true) {
            if (claimedOwner !== owner && !VdStreamer.isRunning) {
                fpsText = null
                return
            }
            val n = if (claimedOwner === owner) ShizukuShell.previewFrameCount() else -1L
            val c = if (claimedOwner === owner) ShizukuShell.previewConsumedCount() else -1L
            val d = VdStreamer.decodedCount
            fpsText = when {
                nativeActive && n >= 0 && lastNative >= 0 -> {
                    val delta = n - lastNative
                    if (delta >= 0) "${delta}fps" else "0fps"   // 计数回卷（服务重启）按 0 显示
                }
                VdStreamer.isRunning -> "JPEG ${(d - lastDecoded).coerceAtLeast(0)}"
                else -> null
            }
            if (n >= 0 && nativeActive) {
                // 看门狗判据（区分「游戏静止」和「渲染器挂了」）：
                // drawn 停 + consumed 涨 = 有帧画不出，渲染器真死 → 降级；
                // drawn 停 + consumed 也停 = 画面完全静止（0fps 是如实反映），不动
                if (n == wdRef && c >= 0 && c != lastConsumed) {
                    misses++
                    if (misses >= WATCHDOG_MISSES) {
                        misses = 0
                        main.post { degrade(owner) }
                    }
                } else if (n != wdRef) {
                    misses = 0
                }
                wdRef = n
            }
            if (n >= 0) lastNative = n
            if (c >= 0) lastConsumed = c
            lastDecoded = d
            try { Thread.sleep(SAMPLER_STEP_MS) } catch (e: InterruptedException) { return }
        }
    }

    /** 直渲判死降级（主线程）：交还位图路径，并安排定时自愈重试。 */
    private fun degrade(owner: Any) {
        if (claimedOwner !== owner || !nativeActive) return
        nativeActive = false
        scheduleRetry(owner)
    }

    /** 降级后定时自愈重试（只重试认领过的 owner）：游戏恢复渲染就自动切回直渲。 */
    private fun scheduleRetry(owner: Any) {
        reclaimRun?.let { main.removeCallbacks(it) }
        val r = Runnable {
            reclaimRun = null
            if (claimedOwner !== owner) return@Runnable
            val surface = claimedSurface
            if (owner is VdPreviewView && surface != null && surface.isValid) {
                claim(owner, surface)
            }
        }
        reclaimRun = r
        main.postDelayed(r, RECLAIM_RETRY_MS)
    }

    private const val CONFIRM_STEP_MS = 700L
    private const val CONFIRM_ATTEMPTS = 4
    private const val WATCHDOG_MISSES = 3      // 3 个周期无新帧（≈12s，游戏画面完全静止）→ 判死
    private const val RECLAIM_RETRY_MS = 15_000L
    private const val SAMPLER_STEP_MS = 1000L  // 帧率角标采样周期
}
