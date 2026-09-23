package com.maawh.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 队列执行器：从 MainActivity 抽出的调度核心（第 1 步结构收口，逻辑零改动）。
 *
 * 职责：按计划顺序跑任务（「启动」「关闭游戏」两个特殊分支 + 清单任务），
 * 跟读引擎日志按性质报错、失败不中断队列、静音/跑完关游戏等收尾。
 * UI 触点全部经由 [Callbacks]（已在主线程回调），本类不持有任何视图。
 *
 * 线程约定：[run] 是阻塞函数，在后台线程调用；除 [Callbacks.onLog]（线程安全，
 * 任意线程可调）外，其余回调均切到主线程发出。
 */
class QueueRunner(
    private val context: Context,
    private val whmxDir: File,
    private val logDir: File,
    private val manifest: TaskPack.Manifest?,
    private val cb: Callbacks
) {

    interface Callbacks {
        fun onLog(msg: String, level: LogLevel)
        fun onRunState(text: String, colorRes: Int)
        /** 队列开始：开始按钮切「停止任务」 */
        fun onQueueStarted()
        /** 队列结束：按钮复位为「开始任务」 */
        fun onQueueFinished()
        /** 「启动」任务投屏前先置虚拟屏标志（App 内存态） */
        fun onVdFlagSet()
        /** 「启动」任务已投屏（收口前）：刷新预览 + 状态行「游戏已进虚拟屏，收口中…」 */
        fun onGameEnteredVd()
        /** 「关闭游戏」任务执行完：虚拟屏一并关闭（App 侧清标志/收悬浮窗/停保活） */
        fun onVdClosed()
        /** 虚拟屏标志当前值（收尾决定保活去留用） */
        fun isVdOn(): Boolean
        /** 队列收尾是否保持游戏静音：手动「关闭游戏声音」开着，或「游戏启动后关闭游戏声音」开着且虚拟屏还活着 */
        fun holdGameMute(vdAlive: Boolean): Boolean
        fun onToast(msg: String)
    }

    @Volatile
    var running = false
        private set

    @Volatile
    var stopRequested = false
        private set

    /** 当前在跑的任务显示名（状态行用；任务循环写、引擎事件线程读） */
    @Volatile
    private var currentLabel: String? = null

    /** 最近一次上报到状态行的节点名（同节点不重复刷） */
    private var lastNode: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun notify(block: () -> Unit) {
        mainHandler.post(block)
    }

    // ---- 引擎实时事件（第 2 步）----
    // 事件在引擎线程进来，这里只挑关键的处理：当前节点刷状态行、控制器动作失败即时上报。
    // 节点级成败细节（识别没过/等超时的归因）仍由 EngineLog 日志轮询负责——日志文本里有
    // 模板/阈值/ROI 提示，事件 json 里没有。
    private val engineListener: (MaaEvent) -> Unit = { ev -> onEngineEvent(ev) }

    private fun onEngineEvent(ev: MaaEvent) {
        when (ev.msg) {
            MaaBridge.MaaMsg.NODE_STARTING -> {
                val n = ev.nodeName ?: return
                if (n == lastNode) return
                lastNode = n
                val label = currentLabel ?: return
                // 状态行显示当前节点（running 检查防收尾结算态被迟到事件覆盖）
                notify { if (running) cb.onRunState("执行中: $label · 节点 $n", R.color.accent) }
            }
            MaaBridge.MaaMsg.CTRL_FAILED ->
                cb.onLog("控制器动作失败（截图/点击/滑动注入，详见引擎日志）", LogLevel.WRN)
            MaaBridge.MaaMsg.TASK_STARTING ->
                cb.onLog("引擎任务开始: ${ev.entry ?: ev.nodeName ?: "?"}", LogLevel.TRACE)
        }
    }

    fun requestStop() {
        stopRequested = true
        MaaBridge.requestStop()
    }

    /** 执行队列（阻塞，后台线程调用）。muteEnabled=手动静音；autoMuteEnabled=游戏启动后自动静音 */
    fun run(
        planTasks: List<TaskItem>,
        muteEnabled: Boolean,
        autoMuteEnabled: Boolean,
        closeAfterEnabled: Boolean
    ) {
        current = this
        RunLogStore.begin(context, "队列", planTasks.size)
        // 队列抬头（对标 maameow 的「开始执行任务，共 N 项」+ 设备内存）
        cb.onLog("开始执行任务，共 ${planTasks.size} 项：${planTasks.joinToString(" → ") { it.label }}", LogLevel.INFO)
        cb.onLog(memoryInfoText(), LogLevel.INFO)
        val queueStart = android.os.SystemClock.elapsedRealtime()
        val startWall = System.currentTimeMillis()   // 历史记录用墙上时钟
        running = true
        stopRequested = false
        MaaBridge.clearStop()
        notify {
            cb.onQueueStarted()
            cb.onRunState("任务运行中…", R.color.accent)
            KeepAliveService.start(context, "准备执行 ${planTasks.size} 项任务", 0, planTasks.size)
        }

        // 仅对游戏静音（appops 按包拒绝其播放音频）：不动系统音量，音量键也不影响。
        // 两个来源：手动「关闭游戏声音」开关、「游戏启动后关闭游戏声音」开关。
        // 先落持久化标记再 deny（顺序不能反，对齐 maameow）：进程崩溃后重启凭标记自愈，
        // 没标记的静音 = 永久静音。收尾按 holdGameMute 决定保持还是恢复。
        val wantMute = muteEnabled || autoMuteEnabled
        if (wantMute) {
            GameAudioMarker.mark(context, MaaConst.GAME_PKG)
            val ok = ShizukuShell.setGameAudioMuted(true)
            // 预埋延时恢复孤儿：任务中 App 被强杀（force-stop 无任何回调）时，
            // 死亡前最后一个孤儿在 90 秒内醒来（虚拟屏已死且 deny 挂着 → 清除）
            runCatching { ShizukuShell.scheduleGameAudioRestoreGuard() }
            if (ok) {
                cb.onLog(
                    if (autoMuteEnabled && !muteEnabled) "已按「游戏启动后关闭游戏声音」静音游戏" else "已单独静音游戏（系统音量不受影响）",
                    LogLevel.INFO
                )
            } else {
                cb.onLog("游戏静音失败（Shizuku 是否在线？），本次运行游戏仍有声音", LogLevel.WRN)
            }
        }

        // 订阅引擎实时事件（finally 里必须摘掉：Tasker 每任务重建，但监听器按队列生命周期走）
        MaaBridge.addEngineEventListener(engineListener)
        try {
            // M4-④a：以服务端权威状态同步虚拟屏路由(截图/点击目标屏)
            ShizukuShell.syncVdMode()
            var ok = true
            val failed = ArrayList<String>()
            // 跟读引擎日志：把"识别失败 / 超时 / 节点失败 / 包校验失败"按性质打进日志区
            val stopTail = startEngineLogTail()
            try {
                for ((idx, item) in planTasks.withIndex()) {
                    if (stopRequested) {
                        cb.onLog("任务已停止：剩余队列不再执行", LogLevel.INFO)
                        break
                    }
                    notify {
                        cb.onRunState("执行中: ${item.label}", R.color.accent)
                        // 通知进度：total>0 时通知带进度条 + 停止按钮（锁屏可停）
                        KeepAliveService.start(
                            context, "正在 ${idx + 1}/${planTasks.size}：${item.label}",
                            idx + 1, planTasks.size
                        )
                        // 悬浮进度条同步（App 在后台时由 MainActivity.onStop 弹出）
                        FloatingPanel.update("▶ ${idx + 1}/${planTasks.size} ${item.label}")
                    }
                    currentLabel = item.label
                    lastNode = null
                    cb.onLog("开始任务：${item.label}", LogLevel.TRACE)
                    val taskStart = android.os.SystemClock.elapsedRealtime()
                    val r = try {
                        if (item.entry == "启动" || item.name == "启动") {
                            // 「启动」= 先进虚拟屏把游戏投进去，再用引擎收口到主页
                            notify { cb.onVdFlagSet() }
                            val vd = ShizukuShell.startVirtualGame()
                            cb.onLog(vd, LogLevel.INFO)
                            // AudioHardening 反制：闩锁跨会话存活，进虚拟屏就主动放行游戏音频
                            // （任一静音开关开着则改为确保静音——游戏本来就该被静音）
                            if (muteEnabled || autoMuteEnabled) {
                                runCatching { GameAudioMarker.mark(context, MaaConst.GAME_PKG) }
                                runCatching { ShizukuShell.setGameAudioMuted(true) }
                            } else {
                                runCatching { ShizukuShell.assertGameAudioAllowed() }
                            }
                            notify { cb.onGameEnteredVd() }
                            if (ShizukuShell.syncVdMode()) {
                                val rr = MaaBridge.runTask(whmxDir, item.entry, logDir, "{}") { msg ->
                                    notify { cb.onLog(msg, LogLevel.TRACE) }
                                }
                                notify {
                                    cb.onRunState(
                                        if (rr) "游戏已进入主页(虚拟屏)" else "✗ 到主页失败",
                                        if (rr) R.color.ok_green else R.color.err_red
                                    )
                                }
                                rr
                            } else {
                                cb.onLog("虚拟屏未就绪，跳过收口", LogLevel.INFO)
                                true
                            }
                        } else if (item.entry == "关闭游戏" || item.name == "关闭游戏") {
                            // 关闭游戏：强杀游戏进程（放在一键长草末尾，跑完即退出游戏）
                            cb.onLog("关闭游戏：force-stop ${MaaConst.GAME_PKG}", LogLevel.INFO)
                            val wasRunning = gameAlive()
                            val cmdOk = runCatching {
                                ShizukuShell.execBlocking("am", "force-stop", MaaConst.GAME_PKG)
                            }.isSuccess
                            // force-stop 是异步清理：本机游戏主进程约 1GB，实测要几秒才真正消失，
                            // 所以轮询到 10s 再下结论，别在进程还在收尾时就报失败
                            var alive = wasRunning
                            var waited = 0
                            while (cmdOk && alive && waited < 10_000) {
                                Thread.sleep(500)
                                waited += 500
                                alive = gameAlive()
                            }
                            when {
                                !cmdOk -> cb.onLog("✗ 关闭游戏失败：force-stop 未能执行（Shizuku 是否在线？）", LogLevel.INFO)
                                !wasRunning -> cb.onLog("游戏本来就没在运行", LogLevel.INFO)
                                alive -> cb.onLog("✗ 关闭游戏：命令已执行，但 10s 后游戏进程仍在 ${gamePids()}", LogLevel.INFO)
                                else -> cb.onLog("✓ 游戏已退出（force-stop 后 ${waited}ms 内）", LogLevel.INFO)
                            }
                            if (cmdOk && !alive && cb.isVdOn()) {
                                // 游戏关了虚拟屏也没存在意义：一并关闭，切后台不再弹悬浮窗
                                runCatching { ShizukuShell.stopVirtual() }
                                notify { cb.onVdClosed() }
                                cb.onLog("虚拟屏已同步关闭", LogLevel.INFO)
                            }
                            cmdOk && !alive
                        } else if (item.entry == "外勤见闻识别" || item.name == "外勤见闻识别") {
                            // 外勤见闻识别：宿主 OCR 扫描（引擎不参与），用户须先手动停在见闻列表页；
                            // 名单随包 assets/waiqin/roster.json，每次运行从全 0 开始
                            val ocr = GachaOcrFactory.create(context) { msg ->
                                cb.onLog(msg, LogLevel.TRACE)
                            }
                            WaiQinScan(context, ocr, { msg, lv -> cb.onLog(msg, lv) }) { stopRequested }
                                .scan()
                        } else {
                            // 其余任务：按清单 option 生成 pipeline_override 后交给引擎
                            // （name 对不上时用 entry 兜底：adb 直达入口的 TaskItem 只有 entry 可用）
                            val override = manifest?.let { m ->
                                m.tasks.firstOrNull { it.name == item.name || it.entry == item.entry }
                                    ?.let { TaskPack.buildOverride(m, it, item.selection) }
                            } ?: "{}"
                            cb.onLog("pipeline_override: $override", LogLevel.INFO)
                            MaaBridge.runTask(whmxDir, item.entry, logDir, override) { msg ->
                                notify { cb.onLog(msg, LogLevel.TRACE) }
                            }
                        }
                    } catch (e: Throwable) {
                        cb.onLog("任务异常: $e", LogLevel.ERR)
                        false
                    }
                    cb.onLog(
                        when {
                            stopRequested -> "已停止任务：${item.label}（耗时 ${costText(taskStart)}）"
                            r -> "完成任务：${item.label} · 耗时 ${costText(taskStart)}"
                            else -> "任务失败：${item.label} · 耗时 ${costText(taskStart)}"
                        },
                        when {
                            stopRequested -> LogLevel.WRN
                            r -> LogLevel.SUCCESS
                            else -> LogLevel.ERR
                        }
                    )
                    if (!r) {
                        // 单个任务失败不再中断整个队列：否则末尾的收尾项（如「关闭游戏」）
                        // 会因为前面任一任务失败而静默不执行——用户勾了却没生效，很难查。
                        // 用户主动停止不算失败，也不提示"继续执行"（后面本来就不跑了）
                        ok = false
                        if (!stopRequested) {
                            failed += item.label
                            cb.onLog("↷ ${item.label} 失败，继续执行后续任务（失败的会在结束时汇总）", LogLevel.WRN)
                        }
                    }
                }
            } finally {
                stopTail()          // 收尾：把折叠掉的重复次数与归类小结打出来
            }
            // 任务结束：勾选「游戏启动后关闭游戏声音」时，
            // 若游戏仍在运行（虚拟屏未退出）则保持静音效果，不主动恢复声音；
            // 仅当游戏已退出（如本队列末尾勾选了关闭游戏）才恢复音量。
            // 这里的调用必须兜异常：Shizuku 若在任务期间被系统回收，execBlocking 会抛异常，
            // 而本协程没有外层 catch —— 未捕获异常会直接把 App 打崩（虚拟屏也随之没）。
            // 「任务自动结束时关闭游戏」只在非手动停止时执行：用户点「停止任务」是主动介入，
            // 多半还要继续手动操作游戏/挂机，此时关游戏不符合预期；自然跑完与失败结束才关
            if (closeAfterEnabled && !stopRequested) {
                // 收尾时检测：游戏还在运行才 force-stop，不在（途中已自行退出/被关）就跳过
                if (gameAlive()) {
                    runCatching { ShizukuShell.execBlocking("am", "force-stop", MaaConst.GAME_PKG) }
                    cb.onLog("✓ 检测到游戏仍在运行，已关闭游戏", LogLevel.INFO)
                } else {
                    cb.onLog("游戏已不在运行，跳过关闭", LogLevel.INFO)
                }
                // 与【关闭游戏】任务同口径：游戏关了虚拟屏一并关闭，切后台不再弹悬浮窗
                if (cb.isVdOn()) {
                    runCatching { ShizukuShell.stopVirtual() }
                    notify { cb.onVdClosed() }
                    cb.onLog("虚拟屏已同步关闭", LogLevel.INFO)
                }
            }
            // 收尾体检：Shizuku 掉了要说清"不是 MaaWH 关的"并给出保活办法，
            // 否则用户只会看到下次打开时必须重新启用 Shizuku
            if (!shizukuRunning()) {
                cb.onLog(
                    "Shizuku 已离线（MaaWH 全程只对游戏包名下命令，不会关闭 Shizuku；" +
                        "通常是 ColorOS 回收了后台进程）。请到【快捷选项 → 防后台被杀设置】" +
                        "把 MaaWH 与 Shizuku 加入电池优化白名单，并在 Shizuku 里开启 Watchdog。",
                    LogLevel.WRN
                )
            }

            // 队列历史：落盘一条（时间/任务数/失败名单/是否停止/耗时），重启可查（files/history.json）
            val totalText = costText(queueStart)
            HistoryStore.append(
                context,
                HistoryStore.Entry(
                    time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(startWall)),
                    planCount = planTasks.size,
                    failed = failed.toList(),
                    stopped = stopRequested,
                    costText = totalText
                )
            )
            RunLogStore.end(
                when {
                    stopRequested -> "已停止 · $totalText"
                    ok -> "全部完成 · $totalText"
                    else -> "失败 ${failed.size}/${planTasks.size} · $totalText"
                }
            )

            notify {
                running = false
                cb.onQueueFinished()
                // 队列收工：虚拟屏还活着就继续保活（游戏还在屏上，App 一死屏就没了），否则停服务
                if (cb.isVdOn()) {
                    KeepAliveService.start(context, context.getString(R.string.keepalive_vd))
                } else {
                    KeepAliveService.stop(context)
                }
                // 悬浮窗不收（虚拟屏画面还在看），只更新状态行
                FloatingPanel.update(
                    when {
                        stopRequested -> "◼ 已停止 · ${totalText}"
                        ok -> "✓ 队列完成 · ${totalText}"
                        else -> "✗ 失败 ${failed.size}/${planTasks.size} · ${totalText}"
                    }
                )
                when {
                    stopRequested -> {
                        cb.onRunState("✓ 任务已停止", R.color.ok_green)
                        cb.onToast("任务已停止")
                        cb.onLog("任务已停止（已跑 ${totalText}）", LogLevel.WRN)
                    }
                    ok -> {
                        cb.onRunState("✓ 全部任务完成", R.color.ok_green)
                        cb.onToast("全部任务完成")
                        cb.onLog("全部任务完成，共 ${planTasks.size} 项 · 总耗时 ${totalText}", LogLevel.SUCCESS)
                    }
                    else -> {
                        // 失败任务点名到运行状态行：否则"哪个任务失败了"只能去日志里翻
                        val names = failed.joinToString("、")
                        cb.onRunState("✗ 失败：$names", R.color.err_red)
                        cb.onToast("有任务失败：$names")
                        cb.onLog("任务结束：${failed.size}/${planTasks.size} 项失败（$names）· 总耗时 $totalText", LogLevel.ERR)
                    }
                }
            }
        } catch (e: Throwable) {
            // 兜底：run() 里任何未捕获异常都不能留着打死 App（虚拟屏随之没）
            running = false
            cb.onLog("队列执行异常: $e", LogLevel.ERR)
            HistoryStore.append(
                context,
                HistoryStore.Entry(
                    time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(startWall)),
                    planCount = planTasks.size,
                    failed = listOf("队列异常"),
                    stopped = true,
                    costText = costText(queueStart)
                )
            )
            FloatingPanel.update("◼ 队列异常")
            notify {
                cb.onQueueFinished()
                if (!cb.isVdOn()) KeepAliveService.stop(context)
            }
        } finally {
            MaaBridge.removeEngineEventListener(engineListener)
            // 游戏静音保持/恢复决策（2026-09-19 拆双开关）：
            // 游戏已不在跑（跑完关游戏/自己退出）→ 一律恢复，杜绝残留；
            // 游戏还活着且宿主说保持（手动「关闭游戏声音」开着，或「游戏启动后关闭游戏声音」
            // 开着且虚拟屏还活着=挂机静音）→ 不动；否则恢复。
            // 标记是唯一事实来源（手动开关的立即动作也走标记），恢复幂等：标记不在就什么都不做。
            if (GameAudioMarker.marked(context) != null) {
                val vdAlive = runCatching { ShizukuShell.syncVdMode() }.getOrDefault(false)
                val gameAlive = gameAlive()
                if (!gameAlive || !cb.holdGameMute(vdAlive)) {
                    if (GameAudioMarker.restoreIfNeeded(context)) {
                        cb.onLog("已恢复游戏声音", LogLevel.TRACE)
                    } else if (ShizukuShell.isGameAudioMuted()) {
                        cb.onLog(
                            "⚠ 游戏静音恢复失败（Shizuku 掉线？）：游戏会暂时没声，" +
                                "下次打开 MaaWH 会自动恢复，或执行 appops reset ${MaaConst.GAME_PKG}",
                            LogLevel.WRN
                        )
                    }
                }
            }
            lastNode = null
            if (current === this) current = null
        }
    }

    // ==================================================================
    // 游戏进程 / 引擎日志跟读 / 额外队列（从 MainActivity 原样搬入）
    // ==================================================================

    /** 游戏主进程是否还在跑（精确匹配进程名；Shizuku 不可用时按"在跑"处理，避免误报"已关闭"） */
    private fun gameAlive(): Boolean = gamePids().isNotEmpty()

    /**
     * 游戏主进程的 "pid name" 列表，空 = 没在跑。
     * 用 ps 精确比对进程名（pidof 只认精确名、且拿不到 pid 文案；子进程 :pushservice 不算主进程）。
     */
    private fun gamePids(): List<String> = try {
        ShizukuShell.execBlocking("ps", "-A", "-o", "PID,NAME")
            .toString(Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .filter { it.split(Regex("\\s+")).lastOrNull() == MaaConst.GAME_PKG }
            .toList()
    } catch (e: Throwable) {
        listOf("查询失败(Shizuku 不可用)")
    }

    private fun getMusicVolume(): Int = ShizukuShell.getMusicVolume()

    private fun setMusicVolume(v: Int) = ShizukuShell.setMusicVolume(v)

    private fun shizukuRunning() = try { rikka.shizuku.Shizuku.pingBinder() } catch (e: Throwable) { false }

    /**
     * 跟读引擎日志，把"任务为什么失败"按性质打进日志区。
     *
     * 引擎只把逐节点细节写进 `files/maa_logs/maafw.log`（App 没注册引擎的通知接口，
     * 所以收不到事件流）—— 这里增量读它，解析成【识别失败】/【超时】/【节点失败】/
     * 【动作失败】/【引擎错误】/【任务包校验失败】，并**把该节点该认得什么（模板/期望文字/
     * 阈值/ROI）补在括号里**：光看节点名判断不出"是识别没过还是等超时"。
     * 同一种错 + 同一个节点连续重复只提示一次（一次运行里能重复几百次，刷屏会把
     * 有用的信息顶掉），收尾时再打一条归类小结。
     *
     * 返回一个 stop()：停止跟读并等它把小结算打完（在任务队列收尾时调用）。
     */
    private fun startEngineLogTail(): () -> Unit {
        val file = File(logDir, "maafw.log")
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread {
            var offset = if (file.exists()) file.length() else 0L   // 只读"从现在往后"
            var partial = ""                      // 上一轮读到的半行
            var lastKey = ""
            var repeats = 0
            var readOnce = false
            var errOnce = false
            val totals = LinkedHashMap<String, Int>()
            cb.onLog("引擎日志跟读已启动：只提示【识别失败】【超时】等错误（同一条不重复刷屏）", LogLevel.TRACE)
            fun flushRepeats() {
                if (repeats > 0) {
                    cb.onLog("   ↳ 上面这条又连续重复了 $repeats 次（同一条只提示一次）", LogLevel.TRACE)
                    repeats = 0
                }
            }
            while (!stopped.get()) {
                try {
                    if (file.exists() && file.length() < offset) {
                        offset = 0L                    // 日志轮转过 → 从头再跟
                        partial = ""
                    }
                    if (file.exists() && file.length() > offset) {
                        val len = (file.length() - offset).toInt()
                        java.io.RandomAccessFile(file, "r").use { raf ->
                            raf.seek(offset)
                            val buf = ByteArray(len)
                            raf.readFully(buf)
                            offset += len
                            val lines = (partial + String(buf, Charsets.UTF_8)).split("\n")
                            partial = lines.last()     // 可能是半行，留到下一轮
                            if (!readOnce) {
                                readOnce = true
                                cb.onLog("   ↳ 已接到引擎日志（+$len 字节，共 ${lines.size - 1} 行）", LogLevel.TRACE)
                            }
                            for (i in 0 until lines.size - 1) {
                                val ev = EngineLog.parse(lines[i], whmxDir) ?: continue
                                if (ev.key == lastKey) {
                                    repeats++
                                    continue
                                }
                                flushRepeats()
                                lastKey = ev.key
                                totals[ev.line] = (totals[ev.line] ?: 0) + 1
                                cb.onLog(ev.line, ev.kind.level)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // 读日志失败不能影响任务本身；但第一条要说一声，否则"什么都没输出"很难查
                    if (!errOnce) {
                        errOnce = true
                        val why = e.cause?.let { " ← ${it::class.java.name}: ${it.message}" } ?: ""
                        val at = e.stackTrace.firstOrNull()?.toString() ?: ""
                        cb.onLog("引擎日志跟读出错（不影响任务）：$e$why @$at", LogLevel.TRACE)
                    }
                }
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    break
                }
            }
            flushRepeats()
            if (totals.isNotEmpty()) {
                cb.onLog("—— 本次运行的报错归类（按出现次数） ——", LogLevel.WRN)
                totals.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                    cb.onLog("   ${if (v > 1) "$v × " else ""}$k", LogLevel.TRACE)
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        return {
            stopped.set(true)
            thread.join(2000)      // 等小结打完，别让它在"任务失败"之后才冒出来
            Unit
        }
    }

    /** 设备内存摘要（maameow 的日志里也有这行，顺带能看到跑任务前的内存压力） */
    private fun memoryInfoText(): String = try {
        val mi = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
        val avail = mi.availMem / 1024 / 1024
        val total = mi.totalMem / 1024 / 1024
        val used = if (mi.totalMem > 0) (mi.totalMem - mi.availMem) * 100 / mi.totalMem else 0
        "设备内存：可用 ${avail}MB / 共 ${total}MB（已用 ${used}%）"
    } catch (e: Throwable) {
        "设备内存：查询失败"
    }

    private fun costText(startMs: Long): String =
        String.format(Locale.getDefault(), "%.2fs", (android.os.SystemClock.elapsedRealtime() - startMs) / 1000.0)

    companion object {
        /** 当前队列执行器：通知栏「停止任务」按钮没有 Activity 引用，走静态入口 */
        @Volatile
        private var current: QueueRunner? = null

        /** 停止当前队列（无队列在跑时是空操作；完整语义 = stopRequested + 引擎 PostStop） */
        fun requestStopCurrent() {
            current?.requestStop()
        }

        /** 是否有队列在跑（KeepAliveService.onTaskRemoved 等无 Activity 引用的场合用） */
        fun isAnyRunning(): Boolean = current?.running == true
    }
}
