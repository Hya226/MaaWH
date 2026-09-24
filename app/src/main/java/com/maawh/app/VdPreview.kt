package com.maawh.app

import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * 预览直渲通路协调器（App 进程）：管理「唯一渲染窗口」的认领与降级 + 帧率角标数据。
 *
 * 服务端（ShellUserService + libmaawh_vd）同一时刻只向一个 Surface GPU 直绘；
 * 三处预览（主页 previewBox、全屏页、悬浮窗）谁可见谁 claim，后 claim 者接管。
 * 降级契约：[nativeActive]=false 期间各处保留原 JPEG 轮询位图路径（VdStreamer），
 * 直渲通路任何一环不健康（服务端不支持 / 挂载后帧计数不增长 / 渲染中途停摆）
 * 都回到位图路径，功能不丢，只是回到旧帧率。
 *
 * ★ 线程纪律（2026-09-24 两条实测教训）：
 * 1) 所有 Binder 调用（previewFrameCount 等）必须在后台线程——ensureService 断连重绑
 *    最长阻塞 15s，主线程调用直接 ANR；
 * 2) 确认/采样循环绝不能跑在单线程执行器里排队——循环随 VdStreamer 常驻不退出，
 *    会把后面所有 claim 的确认循环饿死（表现：切换一次前后台后 nativeActive 永远
 *    回不来，预览冻结 + 永远 JPEG）。因此每次 claim 起独立守护线程，用代际计数
 *    （[loopGen]）淘汰旧循环。
 */
object VdPreview {

    /** 直渲通路健康且当前在用（各处 tick 据此切换 TextureView/位图显示路径与透明度） */
    @Volatile
    var nativeActive = false
        private set

    /** 预览左上角的实时帧率文案（每秒刷新）：直渲时 = 游戏帧率（native drawn 计数差分），
     *  降级时 = 「JPEG N」（VdStreamer 解码计数差分，兼直渲健康指示灯）；null = 不显示 */
    @Volatile
    var fpsText: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    // 循环代际：每次 claim 递增，旧循环发现代际落后立即退场
    private val loopGen = java.util.concurrent.atomic.AtomicLong(0)
    private val NULL_OWNER = Any()   // 无认领者时的纯降级采样占位 owner

    @Volatile
    private var claimedOwner: Any? = null
    private var claimedSurface: android.view.Surface? = null
    private var reclaimRun: Runnable? = null

    /** 认领渲染窗口（后到者接管；主线程调用）。 */
    fun claim(owner: Any, surface: android.view.Surface) {
        claimedOwner = owner
        claimedSurface = surface
        cancelTimers()
        nativeActive = false
        startLoop(owner)
        thread(name = "vd-preview-attach") {
            val ok = ShizukuShell.setPreviewSurface(surface)
            // 挂载结果不需要额外动作：后台确认循环会凭帧计数增长判断直渲是否真的活了
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

    /** VdStreamer 启动时叫一声：没有任何认领者（无预览面在显示）时也保持 JPEG 帧率显示 */
    fun onStreamerStarted() {
        if (claimedOwner == null) startLoop(NULL_OWNER)
    }

    private fun startLoop(owner: Any) {
        val gen = loopGen.incrementAndGet()
        thread(name = "vd-preview-sampler", isDaemon = true) {
            confirmAndSampleLoop(owner, gen)
        }
    }

    private fun cancelTimers() {
        reclaimRun?.let { main.removeCallbacks(it) }
        reclaimRun = null
    }

    /**
     * 确认 + 看门狗 + 帧率采样三合一循环（独立守护线程，随 claim 起、被新代际淘汰）：
     * 1) 确认阶段轮询帧计数：超过基线（claim 时刻的全局计数）= 直渲通路真的在出新帧；
     * 2) 确认失败（如 App 刚重启时虚拟屏还没建、画面静止无新帧）→ 每 5s 自动重试确认，
     *    帧一开始流就自动切回直渲——绝不能一次失败就永久放弃；
     * 3) 采样阶段每秒差分出 fpsText，同时承担看门狗；
     * 4) 代际落后（被新 claim 淘汰）或 owner 释放且流已停 → 退出。
     */
    private fun confirmAndSampleLoop(owner: Any, gen: Long) {
        val mine = { claimedOwner === owner || (owner === NULL_OWNER && claimedOwner == null) }

        while (true) {
            if (!mine() || gen != loopGen.get()) {
                fpsText = null
                return
            }

            // —— 确认阶段 ——
            // 基线取此刻的全局计数：attach 补画与后续绘制会让它增长，
            // 直接判 n>0 会被上一任窗口留下的旧计数瞬间"伪通过"
            val base = ShizukuShell.previewFrameCount()
            android.util.Log.i("MaaWH", "preview confirm start base=$base gen=$gen")
            var attempt = 0
            var lastCount = if (base > 0) base else 0L
            var confirmed = false
            while (attempt < CONFIRM_ATTEMPTS) {
                if (!mine() || gen != loopGen.get()) {
                    // 只有「当前代」退出才清角标；被新一代淘汰时绝不能清——
                    // 会把新一代刚写好的 fpsText 抹掉（角标闪烁/消失的根因）
                    if (gen == loopGen.get()) fpsText = null
                    return
                }
                try { Thread.sleep(CONFIRM_STEP_MS) } catch (e: InterruptedException) { return }
                val n = ShizukuShell.previewFrameCount()
                if (n > lastCount) {
                    lastCount = n
                    confirmed = true
                    break
                }
                lastCount = n
                attempt++
            }
            if (confirmed) {
                nativeActive = true
                android.util.Log.i("MaaWH", "preview confirm OK n=$lastCount")
            } else {
                nativeActive = false
                fpsText = if (VdStreamer.isRunning) null else null   // 降级文案由采样分支给出
                android.util.Log.i("MaaWH", "preview confirm FAIL，5s 后重试")
                try { Thread.sleep(CONFIRM_RETRY_MS) } catch (e: InterruptedException) { return }
                continue
            }

            // —— 采样 + 看门狗阶段 ——
            var lastNative = lastCount   // 用确认阶段最后的计数当基线，首秒帧率不会算出垃圾值
            var lastDecoded = 0L
            var lastConsumed = -1L
            var wdRef = -1L
            var misses = 0
            var degraded = false
            while (true) {
                if (!mine() || gen != loopGen.get()) {
                    if (gen == loopGen.get()) fpsText = null   // 同上：被淘汰时不许清角标
                    return
                }
                val n = if (claimedOwner !== null) ShizukuShell.previewFrameCount() else -1L
                val c = if (claimedOwner !== null) ShizukuShell.previewConsumedCount() else -1L
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
                        android.util.Log.w("MaaWH", "preview stall miss=$misses drawn=$n consumed=$c")
                        if (misses >= WATCHDOG_MISSES) {
                            misses = 0
                            degraded = true
                            nativeActive = false
                            android.util.Log.w("MaaWH", "preview degrade（drawn 停摆而 consumed 涨）→ JPEG，重试确认中")
                        }
                    } else if (n != wdRef) {
                        misses = 0
                    }
                    wdRef = n
                }
                if (n >= 0) lastNative = n
                if (c >= 0) lastConsumed = c
                lastDecoded = d
                if (degraded) break   // 回外层重走确认（循环重试，无需 scheduleRetry）
                try { Thread.sleep(SAMPLER_STEP_MS) } catch (e: InterruptedException) { return }
            }
        }
    }

    /** 直渲判死降级已在采样循环内处理（nativeActive=false 后回外层重走确认，自动恢复） */
    private const val CONFIRM_STEP_MS = 700L
    private const val CONFIRM_ATTEMPTS = 4
    private const val CONFIRM_RETRY_MS = 5000L  // 确认失败后的重试周期（帧一流就自动切回直渲）
    private const val WATCHDOG_MISSES = 3       // 3 个周期有帧进却画不出 → 渲染器判死
    private const val SAMPLER_STEP_MS = 1000L   // 帧率角标采样周期
}
