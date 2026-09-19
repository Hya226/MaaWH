package com.maawh.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.maawh.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MaaWH 控制台（竖屏，任务队列版）
 * 任务清单与参数全部来自任务包 interface.json（对标 MWA/MaaEnd 的 ProjectInterface），
 * 宿主只负责渲染队列、收集参数、生成 pipeline_override 并驱动引擎。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val tasks = mutableListOf<TaskItem>()
    private val enabled = mutableListOf<Boolean>()
    private lateinit var adapter: TaskQueueAdapter

    // 额外队列：adb 入口触发的测试任务，独立于一键长草主队列（不互相清空）
    private val toolsTasks = mutableListOf<TaskItem>()
    private val toolsEnabled = mutableListOf<Boolean>()
    private lateinit var toolsAdapter: TaskQueueAdapter

    /** 当前任务包清单（interface.json）；加载失败为 null，此时队列为空并提示 */
    private var manifest: TaskPack.Manifest? = null
    /** 清单未就绪时到达的 adb 直达入口：任务包释放完成、清单加载后自动补跑（否则会拿 intent 字面量当 entry，跑错老节点） */
    private var pendingLaunchIntent: Intent? = null

    @Volatile
    private var muteEnabled = false
    @Volatile
    private var autoMuteEnabled = false
    @Volatile
    private var closeAfterEnabled = false

    /** 「后台运行时自动画中画」开关：切后台是否自动弹虚拟屏悬浮窗 */
    @Volatile
    private var pipEnabled = true

    private var vdOn = false

    /** 当前队列执行器（null = 本此 App 启动还没跑过队列）；运行状态以它为准 */
    private var queueRunner: QueueRunner? = null
    private val isTaskRunning: Boolean get() = queueRunner?.running == true

    /** 悬浮进度条权限缺提示：只提示一次 */
    private var overlayPrompted = false

    /** 新手引导（对标 maameow 聚光灯引导）；onCreate 里初始化，首启自动弹、设置页可重看 */
    private lateinit var onboarding: Onboarding

    private val vdHandler = Handler(Looper.getMainLooper())
    private lateinit var vdOverlay: FrameLayout
    private lateinit var vdFullImg: ImageView

    /** 任务日志视图（时间 + 级别徽标 + 颜色），onCreate 里初始化 */
    private var logView: TaskLogView? = null

    private var lastBitmap: Bitmap? = null
    private var downX = 0f
    private var downY = 0f
    private var gestureActive = false
    private var queuedStart: Runnable? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ShizukuShell.init(applicationContext)
        // ShizukuShell 内部吞掉的故障（停虚拟屏失败、注入失败等）上报到日志页
        ShizukuShell.errorHook = { level, msg -> log(msg, level) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 静音残留自愈（maameow 的 startAutoRestore 语义：上个会话结束 = 静音标记必是残留）：
        // 上次会话静音了游戏却没恢复成（进程被系统直接杀没有任何回调）→ 凭持久化标记恢复。
        // 冷启动试一次 + UserService 每次新绑定完成再试一次（首次绑定可能晚于 onCreate）。
        // 只认队列没跑——队列运行中的静音是本会话的合法状态，不能撤销。
        lifecycleScope.launch(Dispatchers.IO) { runCatching { selfHealGameAudio() } }
        ShizukuShell.onServiceReady = {
            lifecycleScope.launch(Dispatchers.IO) { runCatching { selfHealGameAudio() } }
        }

        // 任务日志：时间 + 级别徽标 + 颜色（TRACE 灰 / INFO 蓝 / SUCCESS 绿 / WRN 橙 / ERR 红）
        logView = TaskLogView(binding.tvLog, binding.scrollLog) { level ->
            getColorCompat(
                when (level) {
                    LogLevel.TRACE -> R.color.text_secondary
                    LogLevel.INFO -> R.color.accent
                    LogLevel.SUCCESS -> R.color.ok_green
                    LogLevel.WRN -> R.color.warn_orange
                    LogLevel.ERR -> R.color.err_red
                }
            )
        }
        binding.btnClearLog.setOnClickListener {
            logView?.clear()
            log("日志已清空")
        }
        binding.btnHistory.setOnClickListener { showHistory() }
        binding.switchPip.isChecked = pipEnabled
        binding.switchPip.setOnCheckedChangeListener { _, checked ->
            pipEnabled = checked
            scheduleSave()
            // 画中画依赖悬浮窗权限。优先用 Shizuku 直接授权（shell 可改 appops，
            // 对 adb 侧载应用尤其重要——系统设置页会以「受限设置」拒绝授予）；
            // Shizuku 不在线才跳系统授权页。
            if (checked && !Settings.canDrawOverlays(this)) {
                lifecycleScope.launch {
                    val granted = withContext(Dispatchers.IO) {
                        runCatching {
                            ShizukuShell.execShellCommand(
                                "appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow"
                            )
                            Settings.canDrawOverlays(this@MainActivity)
                        }.getOrDefault(false)
                    }
                    if (granted) {
                        toast("悬浮窗权限已自动开启")
                        log("悬浮窗权限已通过 Shizuku 自动授予", LogLevel.INFO)
                    } else {
                        toast("需要悬浮窗权限：请启动 Shizuku 后重开此开关，或到设置页手动允许")
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }.onFailure {
                            runCatching {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:$packageName")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 内置任务包释放（首次安装/覆盖升级时拷 assets/whmx，平时零开销）；
        // 释放完成前禁用开始队列，避免引擎加载到不完整的资源
        binding.btnStartQueue.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val did = try {
                TaskPack.ensureBundledTaskpack(applicationContext)
            } catch (e: Throwable) {
                log("任务包初始化异常: $e")
                false
            }
            runOnUiThread {
                binding.btnStartQueue.isEnabled = true
                if (did) log("✓ 内置任务包已初始化（首次安装或 APK 版本更新）")
                // 首装/升级时 interface.json 此刻才落盘，清单必须在释放完成后加载
                loadManifestIntoQueue()
                // 冷启动期间收到直达入口的：清单就绪了，现在按最新清单补跑
                pendingLaunchIntent?.let {
                    pendingLaunchIntent = null
                    log("任务包就绪，补跑挂起的直达入口: ${it.getStringExtra("entry")}")
                    handleLaunchIntent(it)
                }
            }
        }

        Shizuku.addBinderReceivedListenerSticky { runOnUiThread { refreshStatus(); maybePromptShizuku() } }

        binding.btnRequest.setOnClickListener { requestShizukuPermission() }
        binding.btnRefresh.setOnClickListener { refreshStatus() }
        binding.imageShot.setOnTouchListener { _, ev -> handleImageTouch(ev) }
        binding.btnStartQueue.setOnClickListener {
            // 小工具 tab：开始/停止抽卡识别；其他 tab：跑对应队列
            if (homeTab == HomeTab.TOOLBOX) startGachaCrawl() else startQueue()
        }
        binding.btnQuick.setOnClickListener { showQuickMenu() }
        // tab 切换：一键长草 / 额外队列
        binding.btnTabOneClick.setOnClickListener { switchTab(HomeTab.ONECLICK) }
        binding.btnTabTools.setOnClickListener { switchTab(HomeTab.TOOLS) }
        binding.btnTabToolbox.setOnClickListener { switchTab(HomeTab.TOOLBOX) }
        // 「编辑配置」：一键长草里切到配置管理（对标 maameow 的编辑配置/完成）
        binding.btnEditConfig.setOnClickListener { setConfigMode(!configMode) }
        binding.btnNewProfile.setOnClickListener { createProfile() }
        binding.btnGachaRun.setOnClickListener { startGachaCrawl() }
        GachaDictionary.names = GachaStore.loadNames(this)
        setupGachaAccounts()
        renderGachaPanel()
        buildVdOverlay()

        // 视图归位（默认队列视图）；之后不再重置，免得把用户刚点的「编辑配置」撤掉
        setConfigMode(false)

        setupQueue()
        // 清单加载移至任务包释放完成之后（见下方 ensureBundledTaskpack 回调）

        setupNav()

        refreshStatus()
        updateInfoTexts()
        checkKeepAliveSetup()
        log("MaaWH 任务队列版启动")

        // Shizuku 服务离线时 sticky listener 不会回调，延迟兜底检测一次
        // （新手引导 1.6s 先弹，这里放 2.2s 错开：引导展示中 maybePromptShizuku 会直接让路）
        vdHandler.postDelayed({ maybePromptShizuku() }, 2200)

        // 新手引导：首次启动自动弹全局主引导；场景引导（抽卡页/配置管理）各自首次进入时弹；
        // 设置页「查看新手引导」重看主引导
        onboarding = Onboarding(
            this,
            ::guideShowPage,
            ::guideEnsureHome,
        ) { maybePromptShizuku() }
        binding.btnGuide.setOnClickListener { showGuideReplayDialog() }
        binding.btnAnnouncement.setOnClickListener { showAnnouncement() }
        // 公告自动弹出：首次启动时让位给新手引导，之后每次打开若公告有更新（内容变化）则弹出
        vdHandler.postDelayed({
            if (!isFinishing && !onboarding.isActive) showAnnouncement(auto = true)
        }, 2500)
        binding.btnAnnouncement.setOnClickListener { showAnnouncement() }
        if (!GuideStore.isDone(this, GuideStore.KEY_MAIN)) {
            vdHandler.postDelayed({
                if (!isFinishing && !isTaskRunning) {
                    // 主引导已含配置管理内容，看完同时标记 config，场景引导不再重复弹
                    onboarding.start(
                        buildGuideSteps(),
                        listOf(GuideStore.KEY_MAIN, GuideStore.KEY_CONFIG),
                    )
                }
            }, 1600)
        }

        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 设备在栈顶已有本 Activity 时把 adb 直达 intent 交给 onNewIntent，
        // 这里与 onCreate 走同一入口，保证冷/热启动行为一致
        handleLaunchIntent(intent)
    }

    /**
     * 按任务包清单（interface.json）构建队列。
     * group 含 "tools" 的任务进「额外队列」tab（如查找器者），其余进「一键长草」主队列。
     * 清单缺失时回退到最小可用清单（仅「启动」），避免界面空白无法操作。
     */
    private fun loadManifestIntoQueue() {
        val dir = resolveBundleDir()
        val m = if (dir != null) TaskPack.load(dir) else null
        manifest = m
        // 存档要先读：用户手动挪过位置的任务（home）得在按清单铺队列之前生效
        val saved = QueueStore.load(this)
        val usable = saved != null && (m == null || saved.pack == m.name)
        homeOf.clear()
        if (usable) homeOf.putAll(saved!!.home)
        tasks.clear()
        enabled.clear()
        toolsTasks.clear()
        toolsEnabled.clear()
        if (m == null) {
            log("未找到 interface.json，回退最小清单")
            addTask(TaskItem("whmx", "启动", "到主页"))
        } else {
            log("任务包清单 ${m.label} v${m.version}：${m.tasks.size} 个任务")
            m.tasks.forEach { t ->
                val item = taskItemOf(t)
                // 归属：清单 group 只给默认值，用户挪过位置的以 home 为准
                if (taskHome(t.name, t.group) == HOME_TOOLS) addToolTask(item) else addTask(item)
            }
            val toolCount = toolsTasks.size
            if (toolCount > 0) log("额外队列任务 $toolCount 个（在「额外队列」tab）")
        }
        // 配置列表 + 各配置内容（下面 restore 要用），并决定当前生效的配置
        profileData.clear()
        saved?.profiles?.forEach { profileData[it.name] = it.main }
        if (profileData.isEmpty()) profileData[DEFAULT_PROFILE] = emptyList()
        activeProfile = if (usable && profileData.containsKey(saved!!.active)) saved.active
                        else profileData.keys.first()
        // 按当前配置覆盖默认队列（勾选/参数/顺序/删除 + 快捷选项 + 所在 tab）
        restoreSavedEdits(if (usable) saved else null)
        refreshProfilePanel()
        adapter.notifyDataSetChanged()
        toolsAdapter.notifyDataSetChanged()
        if (tasks.isNotEmpty()) selectTask(0)
    }

    // ==================================================================
    // 配置（多套任务队列）的恢复与保存
    // ==================================================================

    /** 按清单声明构建队列项：option 用清单默认值初始化（主队列与额外队列共用） */
    private fun taskItemOf(t: TaskPack.TaskDef): TaskItem {
        val m = manifest
        val sel = TaskPack.Selection()
        t.options.forEach { key ->
            val o = m?.option(key) ?: return@forEach
            when (o.type) {
                "input" -> o.inputs.forEach { sel.inputOf[it.name] = it.default }
                else -> sel.caseOf[key] = o.defaultCase
            }
        }
        return TaskItem(
            pack = m?.name ?: "whmx",
            name = t.name,
            entry = t.entry,
            label = t.label,
            options = t.options,
            selection = sel
        ).also { it.summary = summarize(it) }
    }

    /** 清单里按 name（优先）或 entry 找任务并构建队列项；清单没声明 → null */
    private fun manifestItemOf(name: String, entry: String): TaskItem? {
        val m = manifest ?: return null
        val def = m.tasks.firstOrNull { it.name == name }
            ?: m.tasks.firstOrNull { it.entry == entry }
        return def?.let { taskItemOf(it) }
    }

    // ==================================================================
    // 任务归属：一键长草（main）⇄ 额外队列（tools）
    // ==================================================================

    /** 用户手动挪过位置的任务：任务名 → HOME_MAIN / HOME_TOOLS。清单 group 只给默认值。 */
    private val homeOf = HashMap<String, String>()

    /** 任务当前归属：手动挪过以 homeOf 为准，否则看清单 group */
    private fun taskHome(name: String, group: List<String>): String =
        homeOf[name] ?: if (group.contains("tools")) HOME_TOOLS else HOME_MAIN

    /** 该任务对象此刻在哪个队列里（返回 HOME_* ；两边都不在 → null） */
    private fun homeOfItem(item: TaskItem): String? =
        if (tasks.any { it === item }) HOME_MAIN
        else if (toolsTasks.any { it === item }) HOME_TOOLS else null

    /**
     * 把一个任务在【一键长草】与【额外队列】之间挪位置（调试完转正式任务就是靠它）。
     * 勾选状态与已设的参数跟着走，并在两个 tab 之间自动切过去、选中它，让结果看得见。
     */
    private fun moveTaskTo(item: TaskItem, target: String) {
        val from = homeOfItem(item) ?: return
        if (from == target) {
            toast("它已经在" + (if (target == HOME_TOOLS) "额外队列" else "一键长草") + "里了")
            return
        }
        val wasEnabled = if (from == HOME_MAIN) enabled.getOrElse(tasks.indexOf(item)) { true }
                         else toolsEnabled.getOrElse(toolsTasks.indexOf(item)) { true }
        if (from == HOME_MAIN) {
            val i = tasks.indexOf(item)
            tasks.removeAt(i)
            enabled.removeAt(i)
            // 旧面板还停在它身上，清掉免得留下一个「已经不在这个 tab」的编辑面板
            binding.layoutOptionsTools.removeAllViews()
            binding.tvToolsEditTitle.text = getString(R.string.edit_title)
            binding.tvToolsEditHint.text = getString(R.string.tools_edit_hint)
            binding.btnToolsEditMove.visibility = View.GONE
        } else {
            val i = toolsTasks.indexOf(item)
            toolsTasks.removeAt(i)
            toolsEnabled.removeAt(i)
            binding.layoutOptionsMain.removeAllViews()
            binding.tvEditTitle.text = getString(R.string.edit_title)
            binding.tvEditHint.text = getString(R.string.edit_hint)
            binding.btnEditMove.visibility = View.GONE
        }
        homeOf[item.name] = target
        if (target == HOME_TOOLS) {
            toolsTasks.add(item)
            toolsEnabled.add(wasEnabled)
            switchTab(HomeTab.TOOLS)
            toolsAdapter.notifyDataSetChanged()
            selectToolTask(toolsTasks.size - 1)
        } else {
            tasks.add(item)
            enabled.add(wasEnabled)
            switchTab(HomeTab.ONECLICK)
            adapter.notifyDataSetChanged()
            selectTask(tasks.size - 1)
        }
        saveNow()
        log("已把「${item.label}」移到" +
            (if (target == HOME_TOOLS) "【额外队列】" else "【一键长草】（主队列）"))
    }

    /**
     * 用存档覆盖刚按清单构建的默认队列。
     * 存档按任务 name 匹配：对不上的（清单已改）保持默认，不会带进过期参数。
     */
    private fun restoreSavedEdits(saved: QueueStore.State?) {
        val m = manifest
        if (saved == null || m == null || saved.pack != m.name) return
        restoreMainQueue(profileData[activeProfile] ?: emptyList())
        restoreToolsQueue(saved.tools)
        muteEnabled = saved.mute
        autoMuteEnabled = saved.autoMute
        closeAfterEnabled = saved.closeAfter
        pipEnabled = saved.pipOn
        binding.switchPip.isChecked = saved.pipOn
        // 启动固定归位「一键长草」（上次所在 tab 不恢复——挂机/测试后重开都从主队列开始）
        switchTab(HomeTab.ONECLICK)
        // 旧存档里的 tab="config"（配置曾是与两个 tab 平级的分区）→ 落到一键长草的队列视图。
        // 这里**不能**再重置配置模式：清单加载比窗口可点慢，重置会把用户启动瞬间点的「编辑配置」撤销掉
        log("已载入配置「$activeProfile」（队列 ${tasks.size} 项 · 额外队列 ${toolsTasks.size} 项）")
    }

    /** 主队列：存档顺序即配置里的顺序，清单删掉的任务丢弃，清单新增的按默认追加到末尾 */
    private fun restoreMainQueue(saved: List<QueueStore.SavedTask>) {
        val newTasks = ArrayList<TaskItem>()
        val newEnabled = ArrayList<Boolean>()
        val seen = HashSet<String>()
        for (s in saved) {
            val idx = tasks.indexOfFirst { it.name == s.name }
            if (idx < 0 || !seen.add(s.name)) continue
            val item = tasks[idx]
            applySavedSelection(item, s)
            item.summary = summarize(item)
            newTasks.add(item)
            newEnabled.add(s.enabled)
        }
        tasks.forEachIndexed { i, item ->
            if (seen.contains(item.name)) return@forEachIndexed
            newTasks.add(item)
            newEnabled.add(enabled.getOrElse(i) { true })
        }
        tasks.clear()
        tasks.addAll(newTasks)
        enabled.clear()
        enabled.addAll(newEnabled)
    }

    /** 额外队列：清单声明的工具任务取原对象（带 option），
     *  存档里按 entry 记的 adb 直达条目也按清单声明重建（否则参数面板是空的） */
    private fun restoreToolsQueue(saved: List<QueueStore.SavedTask>) {
        val newTasks = ArrayList<TaskItem>()
        val newEnabled = ArrayList<Boolean>()
        val seen = HashSet<String>()
        val seenEntries = HashSet<String>()
        for (s in saved) {
            if (!seen.add(s.name)) continue
            val idx = toolsTasks.indexOfFirst { it.name == s.name }
            val fromManifest = if (idx < 0) manifestItemOf(s.name, s.entry) else null
            if (fromManifest != null && !seenEntries.add(fromManifest.entry)) continue
            // 清单与现有队列都没有 = 已取消注册/失效的条目，不再以字面量恢复——
            // 额外队列只显示当前清单里存在的任务，与主队列的核对口径一致
            if (idx < 0 && fromManifest == null) continue
            val item = when {
                idx >= 0 -> toolsTasks[idx]
                else -> fromManifest!!
            }
            if (idx >= 0 || fromManifest != null) applySavedSelection(item, s)
            item.summary = summarize(item)
            newTasks.add(item)
            newEnabled.add(s.enabled)
            seenEntries.add(item.entry)
        }
        toolsTasks.forEachIndexed { i, item ->
            if (seen.contains(item.name) || !seenEntries.add(item.entry)) return@forEachIndexed
            newTasks.add(item)
            newEnabled.add(toolsEnabled.getOrElse(i) { true })
        }
        toolsTasks.clear()
        toolsTasks.addAll(newTasks)
        toolsEnabled.clear()
        toolsEnabled.addAll(newEnabled)
    }

    /** 把存档取值填回任务：只认清单声明的 option，case 名已不存在时保持清单默认 */
    private fun applySavedSelection(item: TaskItem, s: QueueStore.SavedTask) {
        val m = manifest ?: return
        val def = m.tasks.firstOrNull { it.name == item.name }
            ?: m.tasks.firstOrNull { it.entry == item.entry } ?: return
        for (key in def.options) {
            val o = m.option(key) ?: continue
            if (o.type == "input") {
                for (inp in o.inputs) {
                    s.inputOf[inp.name]?.let { item.selection.inputOf[inp.name] = it }
                }
            } else {
                val v = s.caseOf[key] ?: continue
                if (o.cases.any { it.name == v }) item.selection.caseOf[key] = v
            }
        }
    }

    private fun savedTaskOf(item: TaskItem, on: Boolean) = QueueStore.SavedTask(
        name = item.name,
        entry = item.entry,
        enabled = on,
        caseOf = HashMap(item.selection.caseOf),
        inputOf = HashMap(item.selection.inputOf)
    )

    /** 当前编辑中的主队列的存档形态 */
    private fun liveMainSaved(): List<QueueStore.SavedTask> =
        tasks.mapIndexed { i, it -> savedTaskOf(it, enabled.getOrElse(i) { true }) }

    /**
     * 写盘。
     * flushLive = true：把当前编辑中的队列写进生效配置（常规保存 / 切配置前）；
     * false：只写配置表本身——刚新建或复制出配置、队列还没按它重建时用，
     *        否则会把上一个配置的队列内容误写进新配置。
     */
    private fun persist(flushLive: Boolean) {
        val m = manifest ?: return   // 清单还没加载完，别把空队列写进存档
        if (flushLive) profileData[activeProfile] = liveMainSaved()
        try {
            QueueStore.save(this, QueueStore.State(
                pack = m.name,
                manifestVersion = m.version,
                active = activeProfile,
                profiles = profileData.map { (name, main) -> QueueStore.Profile(name, main) },
                tools = toolsTasks.mapIndexed { i, it -> savedTaskOf(it, toolsEnabled.getOrElse(i) { true }) },
                tab = when (homeTab) {
                    HomeTab.TOOLS -> "tools"
                    HomeTab.TOOLBOX -> "toolbox"
                    else -> "oneclick"
                },
                mute = muteEnabled,
                autoMute = autoMuteEnabled,
                closeAfter = closeAfterEnabled,
                pipOn = pipEnabled,
                home = HashMap(homeOf)
            ))
        } catch (e: Throwable) {
            log("保存配置失败: $e")
        }
    }

    /** 编辑变更后延迟落盘（合并连续输入/拖动），队列重建与 onPause 时立即写 */
    private fun scheduleSave() {
        savePending?.let { vdHandler.removeCallbacks(it) }
        val r = Runnable {
            savePending = null
            saveNow()
        }
        savePending = r
        vdHandler.postDelayed(r, 400)
    }

    private fun saveNow() {
        savePending?.let { vdHandler.removeCallbacks(it) }
        savePending = null
        persist(flushLive = true)
    }

    // ==================================================================
    // 配置管理（新建 / 改名 / 复制 / 删除 / 切换生效）
    // ==================================================================

    /** 已生成的配置名：名字 → 该配置的主队列内容（生效配置的内容可能滞后，以 tasks 为准） */
    private val profileData = LinkedHashMap<String, List<QueueStore.SavedTask>>()
    private var activeProfile = ""

    /**
     * 配置表改动 → 落盘 → 重建队列。
     * `loadManifestIntoQueue()` 是「以存档为准」重建的，所以配置表必须**先落盘**再重建，
     * 否则内存里的改动会被磁盘上的旧存档盖回去（新建/切换会被静默撤销）。
     */
    private fun applyProfileTableChange(change: () -> Unit) {
        saveNow()                  // 当前队列的编辑先写回旧生效配置
        change()                   // 改内存里的配置表（含 activeProfile）
        persist(flushLive = false) // 配置表落盘，后面重建才读得到
        loadManifestIntoQueue()    // 按新生效配置重建队列（内部会重画配置列表）
        saveNow()                  // 把重建出来的队列写进新生效配置
    }

    /** 一个还没被占用的配置名，如「配置-2」 */
    private fun uniqueProfileName(base: String): String {
        var i = 1
        while (profileData.containsKey("$base-$i")) i++
        return "$base-$i"
    }

    /** 切换生效配置 */
    private fun activateProfile(name: String) {
        if (name == activeProfile || !profileData.containsKey(name)) return
        applyProfileTableChange { activeProfile = name }
        log("已切换到配置「$name」")
        toast("配置：$name")
    }

    /** 新建配置：内容留空 = 全部任务取清单默认值（要保留当前队列用「复制」） */
    private fun createProfile() {
        val name = uniqueProfileName("配置")
        applyProfileTableChange {
            profileData[name] = emptyList()
            activeProfile = name
        }
        log("已新建配置「$name」（全默认队列）")
        toast("已新建：$name")
    }

    /** 复制配置：内容照搬源配置，插在源配置后面别打乱列表顺序 */
    private fun duplicateProfile(src: String) {
        if (!profileData.containsKey(src)) return
        val name = uniqueProfileName(src)
        applyProfileTableChange {
            val rebuilt = LinkedHashMap<String, List<QueueStore.SavedTask>>()
            profileData.forEach { (k, v) ->
                rebuilt[k] = v
                if (k == src) rebuilt[name] = v
            }
            profileData.clear()
            profileData.putAll(rebuilt)
            activeProfile = name
        }
        log("已复制配置「$src」→「$name」")
        toast("已复制：$name")
    }

    /** 重命名配置（不改内容，保持列表顺序） */
    private fun renameProfile(name: String) {
        val et = EditText(this).apply {
            setText(name)
            setSelection(name.length)
            setSingleLine()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.config_rename)
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val nn = et.text.toString().trim()
                when {
                    nn.isEmpty() -> toast("名称不能为空")
                    nn == name -> {}
                    profileData.containsKey(nn) -> toast("已有同名配置：$nn")
                    else -> {
                        saveNow()
                        val rebuilt = LinkedHashMap<String, List<QueueStore.SavedTask>>()
                        profileData.forEach { (k, v) -> rebuilt[if (k == name) nn else k] = v }
                        profileData.clear()
                        profileData.putAll(rebuilt)
                        if (activeProfile == name) activeProfile = nn
                        refreshProfilePanel()
                        saveNow()
                        log("配置「$name」已改名为「$nn」")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 删除配置；删的是生效配置时自动落到第一个，至少留一个 */
    private fun deleteProfile(name: String) {
        if (!profileData.containsKey(name)) return
        if (profileData.size <= 1) {
            toast("至少保留一个配置")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.config_delete)
            .setMessage("删除配置「$name」？该配置里的任务编辑会一起丢掉。")
            .setPositiveButton("删除") { _, _ ->
                applyProfileTableChange {
                    profileData.remove(name)
                    if (activeProfile == name) activeProfile = profileData.keys.first()
                }
                log("已删除配置「$name」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 重画「配置」tab 里的配置列表（单选=生效 / ✎改名 / ⧉复制 / ✕删除） */
    private fun refreshProfilePanel() {
        val box = binding.layoutProfiles
        box.removeAllViews()
        if (manifest == null) {
            box.addView(hintText(getString(R.string.config_no_manifest)))
            return
        }
        box.addView(hintText(getString(R.string.config_hint)))
        for ((name, main) in profileData) {
            val active = name == activeProfile
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(10), dp(10), dp(8), dp(10))
                val lp = LinearLayout.LayoutParams(match(), wrap())
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            val label = TextView(this).apply {
                // 生效配置以内存里的队列为准（正编辑的那个），其余显示存档里的勾选数
                val on = if (active) enabled.count { it } else main.count { it.enabled }
                val total = if (active) tasks.size else main.size
                text = "${if (active) "◉" else "○"}  $name"
                append("\n$on/$total 项勾选${if (active) " · 生效中" else ""}")
                textSize = 13f
                setTextColor(getColorCompat(if (active) R.color.accent else R.color.text_primary))
                setPadding(0, 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f)
                isClickable = true
                setOnClickListener {
                    // 生效配置 → 回队列改勾选/参数；其它 → 切换生效
                    if (active) switchTab(HomeTab.ONECLICK) else activateProfile(name)
                }
            }
            row.addView(label)
            row.addView(glyphButton("✎", R.string.config_rename) { renameProfile(name) })
            row.addView(glyphButton("⧉", R.string.config_duplicate) { duplicateProfile(name) })
            row.addView(glyphButton("✕", R.string.config_delete) { deleteProfile(name) })
            box.addView(row)
        }
    }

    private fun hintText(s: String) = TextView(this).apply {
        text = s
        textSize = 11f
        setTextColor(getColorCompat(R.color.text_secondary))
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun glyphButton(glyph: String, labelRes: Int, onClick: () -> Unit) = TextView(this).apply {
        text = glyph
        textSize = 17f
        setTextColor(getColorCompat(R.color.text_secondary))
        setPadding(dp(11), dp(4), dp(11), dp(4))
        contentDescription = getString(labelRes)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 计算任务参数摘要（显示在列表右侧），如「次数 3」「办公室物资购买 开」 */
    private fun summarize(item: TaskItem): String {
        val m = manifest ?: return ""
        val parts = ArrayList<String>()
        for (key in item.options) {
            val o = m.option(key) ?: continue
            when (o.type) {
                "input" -> {
                    val v = o.inputs.joinToString("/") { item.selection.input(it.name, it.default) }
                    if (v.isNotBlank()) parts.add(v)
                }
                "switch" -> {
                    val v = item.selection.get(key, o.defaultCase)
                    if (v.equals("Yes", true) || v.equals("Y", true)) parts.add(o.label)
                }
                else -> parts.add(item.selection.get(key, o.defaultCase))
            }
        }
        return parts.joinToString(" · ")
    }

    /** 任务包目录：内部 files/taskpacks/<pack>，缺失时回退外部目录 */
    private fun resolveBundleDir(): File? {
        val internal = File(filesDir, "taskpacks/whmx")
        if (internal.isDirectory) return internal
        val external = File(getExternalFilesDir(null), "taskpacks/whmx")
        return if (external.isDirectory) external else null
    }

    /**
     * adb 直达运行入口：
     *  - entry=M4Probe/M4Go/M4Frames：虚拟屏探测/建屏抓帧
     *  - 其余 entry：入队后自动执行；extra vd=true 时先建虚拟屏投游戏再跑该入口
     */
    private fun handleLaunchIntent(intent: Intent?) {
        val entry = intent?.getStringExtra("entry") ?: return
        // FrameSave：只抓一帧虚拟屏画面存盘（不重启游戏/不变虚拟屏状态），供排查模板位置
        if (entry == "FrameSave") {
            log("FrameSave 开始…")
            lifecycleScope.launch(Dispatchers.IO) {
                val f = ShizukuShell.grabVirtualFrame()
                if (f.isEmpty()) {
                    runOnUiThread { log("FrameSave: 帧为空") }
                } else {
                    val file = File(filesDir, "cur_frame.jpg")
                    file.writeBytes(f)
                    runOnUiThread { log("FrameSave: ${file.absolutePath} (${f.size}b)") }
                }
            }
            return
        }
        if (entry == "M4Probe" || entry == "M4Go" || entry == "M4Frames") {
            log("$entry 开始…")
            lifecycleScope.launch(Dispatchers.IO) {
                if (entry == "M4Go" || entry == "M4Frames") {
                    val r = ShizukuShell.startVirtualGame()
                    runCatching { File(filesDir, "m4result.txt").writeText(r) }
                    runOnUiThread { log(r) }
                    if (entry == "M4Frames") {
                        for (i in 0..7) {
                            Thread.sleep(if (i == 0) 2000 else 8000)
                            val f = ShizukuShell.grabVirtualFrame()
                            if (f.isNotEmpty()) {
                                val file = File(filesDir, "vd_f$i.jpg")
                                file.writeBytes(f)
                                runOnUiThread { log("帧$i: ${file.name} ${f.size}b") }
                            } else {
                                runOnUiThread { log("帧$i: 空") }
                            }
                        }
                    } else {
                        Thread.sleep(10000)
                        val frame = ShizukuShell.grabVirtualFrame()
                        if (frame.isNotEmpty()) {
                            val f = File(filesDir, "vd_frame.jpg")
                            f.writeBytes(frame)
                            runOnUiThread { log("已抓帧 ${f.absolutePath} (${frame.size} bytes)") }
                        } else {
                            runOnUiThread { log("未抓到帧（虚拟屏可能没有内容）") }
                        }
                    }
                } else {
                    val res = ShizukuShell.virtualProbe()
                    runOnUiThread { log(res) }
                }
                runOnUiThread { setRunState("完成", R.color.ok_green) }
            }
            return
        }
        val vdFirst = intent.getBooleanExtra("vd", false) && !vdOn
        val pack = intent.getStringExtra("pack") ?: "whmx"
        // 清单还没加载完（装包后冷启动、释放还在后台跑）：挂起等清单就绪补跑。
        // 否则队列/清单都是空的，会 fallback 成 TaskItem(entry=字面量)——
        // 撞上 bundle 里的同名老节点（如 login.json 的「启动」），跑错流程
        if (manifest == null) {
            pendingLaunchIntent = intent
            log("清单未就绪，[$entry] 将在任务包初始化完成后自动执行")
            return
        }
        // 清单里声明过的入口：复用它在本机队列里的那一条（带着你设好的参数），
        // 别新建一个只有 entry 的空条目 —— 否则编辑器点「▶ 同步并运行」跑的是清单默认值
        val queued = tasks.firstOrNull { it.name == entry || it.entry == entry }
            ?: toolsTasks.firstOrNull { it.name == entry || it.entry == entry }
        val toolItem = queued ?: manifestItemOf(entry, entry) ?: TaskItem(pack, entry, entry)
        if (queued != null && tasks.any { it === queued }) {
            // 已经归在【一键长草】的任务：只运行，不往额外队列里塞重复条目
            log("工具入口: [$entry] 是【一键长草】任务，直接运行（不加入额外队列）")
        } else {
            // 额外队列里的任务 / adb 直达的临时入口：照旧入队并切到额外队列 tab
            addToolTask(toolItem)
            switchTab(HomeTab.TOOLS)
            log("工具入口: [$entry] 已加入额外队列")
        }
        if (vdFirst) {
            log("vd=1：先建虚拟屏并投游戏，再跑 [$entry]")
            vdOn = true
            lifecycleScope.launch(Dispatchers.IO) {
                val r = ShizukuShell.startVirtualGame()
                // 与 toggleVd 同口径：进虚拟屏即按开关应用静音（任务稍后在 run() 里还会应用一次）
                if (muteEnabled || autoMuteEnabled) {
                    runCatching { GameAudioMarker.mark(this@MainActivity, MaaConst.GAME_PKG) }
                    runCatching { ShizukuShell.setGameAudioMuted(true) }
                } else {
                    runCatching { ShizukuShell.assertGameAudioAllowed() }
                }
                runOnUiThread { log(r) }
                runOnUiThread {
                    VdStreamer.start()
                    vdHandler.removeCallbacks(vdUiTick)
                    vdHandler.post(vdUiTick)
                    setRunState("游戏已进虚拟屏，即将执行任务", R.color.ok_green)
                }
            }
        }
        // 只跑本次入口这一个任务，不重跑额外队列里的历史条目
        queuedStart?.let { binding.btnStartQueue.removeCallbacks(it) }
        queuedStart = Runnable {
            when {
                isTaskRunning -> {}
                !shizukuRunning() -> {
                    log("Shizuku 未运行，任务未执行：请启动 Shizuku 后重新触发", LogLevel.ERR)
                    refreshStatus()
                }
                !shizukuReady() -> {
                    // 卸载重装后授权会失效：给出明确提示并主动发起授权请求
                    log("Shizuku 未授权，任务未执行：请在弹出的授权框中允许，再重新触发任务", LogLevel.ERR)
                    refreshStatus()
                    requestShizukuPermission()
                }
                else -> runQueue(listOf(toolItem))
            }
        }
        binding.btnStartQueue.postDelayed(queuedStart!!, 1800)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 回前台收起悬浮窗（弹出的条件见 onStop）
        FloatingPanel.hide()
        // 虚拟屏是服务端权威状态(app 重启/切后台会丢内存标志)，回前台自动同步 UI
        syncVdUi()
    }

    override fun onStop() {
        super.onStop()
        // 切后台且虚拟屏活着/任务在跑 → 自动弹虚拟屏悬浮窗（设置页可关）
        if (pipEnabled && (vdOn || queueRunner?.running == true || gachaRunning)) {
            if (gachaRunning) FloatingPanel.update("▶ 抽卡记录识别中")
            if (FloatingPanel.isPermissionGranted(this)) {
                FloatingPanel.showForBackground(this)
            } else if (!overlayPrompted) {
                overlayPrompted = true
                log("悬浮窗需要「显示在其他应用上层」权限：设置 → 应用管理 → MaaWH → 显示在其他应用上层", LogLevel.WRN)
                toast("开启悬浮窗权限后，切后台也能看到虚拟屏画面")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 切后台可能被 LMKD 直接回收，编辑状态在这里立即落盘，不等 400ms 防抖
        saveNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        ShizukuShell.onServiceReady = null
        if (queueRunner?.running == true) return
        if (isFinishing && vdOn) {
            // 用户主动关闭主界面 = 结束 MaaWH 会话：收虚拟屏、恢复游戏声音、停保活。
            // 挂机静音只覆盖「Home 键切后台」（onStop，Activity 不销毁）——主动退出后
            // 用户回物理屏打开游戏必须有声（appops deny 是包级设置，不清就永久无声）。
            vdOn = false
            FloatingPanel.hide()
            KeepAliveService.stop(this)
            Thread {
                runCatching { ShizukuShell.stopVirtual() }
                runCatching { GameAudioMarker.restoreIfNeeded(applicationContext) }
            }.start()
        } else if (!vdOn) {
            // 退出兜底：appops 静音是持久系统设置，MaaWH 不再管控游戏（队列没跑、虚拟屏已关）
            // 时必须清掉 deny 残留，否则用户退出后自己打开物华弥新也没声。
            // 必须用孤儿 shell 派发（requestGameAudioRestore）——退出瞬间进程随时被杀，
            // 异步线程/同步 appops 都可能跑一半就没了，孤儿进程挂在 init 下必然执行完。
            ShizukuShell.requestGameAudioRestore()
        }
        // 其余情况（切后台后被系统销毁、但虚拟屏仍存活）：挂机继续，静音保持
    }

    /** 按服务端虚拟屏状态同步 UI：避免 vdOn 标志丢失导致预览黑屏/点击不注入 */
    private fun syncVdUi() {
        lifecycleScope.launch(Dispatchers.IO) {
            val alive = try { ShizukuShell.isVdAlive() } catch (e: Throwable) { false }
            runOnUiThread {
                if (alive && !vdOn) {
                    vdOn = true
                    VdStreamer.start()
                    vdHandler.removeCallbacks(vdUiTick)
                    vdHandler.post(vdUiTick)
                    // 虚拟屏活着就得让 App 也活着：屏挂在 App 的用户服务进程上
                    keepAlive(getString(R.string.keepalive_vd))
                    log("虚拟屏状态已同步")
                } else if (!alive && vdOn) {
                    vdOn = false
                    if (!isTaskRunning) stopKeepAlive()
                }
            }
        }
    }

    // ==================================================================
    // 队列
    // ==================================================================

    private fun setupQueue() {
        adapter = TaskQueueAdapter(
            tasks,
            enabled,
            { i, checked ->
                if (i < enabled.size) {
                    enabled[i] = checked
                    scheduleSave()
                }
            },
            { i -> deleteTask(i) },
            { i -> selectTask(i) }
        )
        binding.rvTaskList.layoutManager = LinearLayoutManager(this)
        binding.rvTaskList.adapter = adapter
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                adapter.onMove(from, to)
                val e = enabled.removeAt(from)
                enabled.add(to, e)
                scheduleSave()
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        })
        helper.attachToRecyclerView(binding.rvTaskList)

        // 额外队列：同一适配器，仅绑到额外队列 tab 的列表
        toolsAdapter = TaskQueueAdapter(
            toolsTasks,
            toolsEnabled,
            { i, checked ->
                if (i < toolsEnabled.size) {
                    toolsEnabled[i] = checked
                    scheduleSave()
                }
            },
            { i -> deleteToolTask(i) },
            { i -> selectToolTask(i) }
        )
        binding.rvToolsList.layoutManager = LinearLayoutManager(this)
        binding.rvToolsList.adapter = toolsAdapter
    }

    private fun addTask(item: TaskItem) {
        tasks.add(item)
        enabled.add(true)
        adapter.notifyItemInserted(tasks.size - 1)
    }

    private fun deleteTask(i: Int) {
        if (i < 0 || i >= tasks.size) return
        val removed = tasks.removeAt(i)
        if (i < enabled.size) enabled.removeAt(i)
        adapter.notifyItemRemoved(i)
        log("已删除: ${removed.label}")
        scheduleSave()
    }

    // ==================================================================
    // 额外队列（清单声明的工具任务 + adb 入口触发的测试任务）
    // ==================================================================

    /**
     * 加入额外队列（同入口去重后移到最新位，保留最近 12 条，超出丢弃最旧）。
     * adb 直达入口（`--es entry VF_xxx`）带的 TaskItem 只有 entry、没有清单声明，
     * 直接入队会让参数面板显示成「无额外参数」、运行时 override 也拿不到清单里的 option
     * （刷活动关的「刷取次数」就是这么丢的）→ 这里优先换成清单里同 entry 的那一条。
     */
    private fun addToolTask(item: TaskItem) {
        val keep = toolsTasks.firstOrNull { it.pack == item.pack && it.entry == item.entry }
        val use = when {
            // 队列里已有清单项：留住它（带着 option 和用户已改的参数）
            keep != null && keep.options.isNotEmpty() -> keep
            item.options.isNotEmpty() -> item
            else -> manifestItemOf(item.name, item.entry) ?: item
        }
        val dup = toolsTasks.indexOfFirst { it.pack == use.pack && it.entry == use.entry }
        if (dup >= 0) {
            toolsTasks.removeAt(dup)
            toolsEnabled.removeAt(dup)
        }
        toolsTasks.add(use)
        toolsEnabled.add(true)
        if (toolsTasks.size > 12) {
            toolsTasks.removeAt(0)
            toolsEnabled.removeAt(0)
        }
        toolsAdapter.notifyDataSetChanged()
        scheduleSave()
    }

    private fun deleteToolTask(i: Int) {
        if (i < 0 || i >= toolsTasks.size) return
        val removed = toolsTasks.removeAt(i)
        if (i < toolsEnabled.size) toolsEnabled.removeAt(i)
        toolsAdapter.notifyItemRemoved(i)
        log("已从工具队列删除: ${removed.label}")
        scheduleSave()
    }

    private fun selectToolTask(i: Int) {
        if (i < 0 || i >= toolsTasks.size) return
        val item = toolsTasks[i]
        android.util.Log.i("MaaWH", "SELECT tools[$i]=${item.name} tools=${toolsTasks.map { it.name }}")
        binding.tvToolsEditTitle.text = "编辑: ${item.label}"
        // 归属切换按钮：清单声明的任务才给（adb 直达的临时条目挪过去没意义）
        val declared = manifest?.tasks?.any { it.name == item.name || it.entry == item.entry } == true
        binding.btnToolsEditMove.visibility = if (declared) View.VISIBLE else View.GONE
        binding.btnToolsEditMove.setOnClickListener { moveTaskTo(item, HOME_MAIN) }
        val container = binding.layoutOptionsTools
        container.removeAllViews()
        binding.tvToolsEditHint.text = if (renderOptionsFor(container, item))
            "参数来自任务包清单 interface.json"
        else
            "该入口为 adb 直达测试任务，无额外参数"
        log("选中工具任务: ${item.label}")
    }

    // ==================================================================
    // 参数编辑（按清单 option 动态渲染）
    // ==================================================================

    private var editingIndex = -1

    /** 待落盘的上次编辑（scheduleSave 的延迟任务） */
    private var savePending: Runnable? = null

    /** 主页里的两个分区 */
    private enum class HomeTab { ONECLICK, TOOLS, TOOLBOX }

    private var homeTab = HomeTab.ONECLICK

    /** 一键长草里的「配置管理」子视图是否展开（对标 maameow 的编辑配置/完成） */
    private var configMode = false

    /** 一键长草 / 额外队列 / 小工具 分区切换 */
    private fun switchTab(tab: HomeTab) {
        homeTab = tab
        binding.panelOneClick.visibility = if (tab == HomeTab.ONECLICK) View.VISIBLE else View.GONE
        binding.panelTools.visibility = if (tab == HomeTab.TOOLS) View.VISIBLE else View.GONE
        binding.panelToolbox.visibility = if (tab == HomeTab.TOOLBOX) View.VISIBLE else View.GONE
        binding.btnTabOneClick.isChecked = tab == HomeTab.ONECLICK
        binding.btnTabTools.isChecked = tab == HomeTab.TOOLS
        binding.btnTabToolbox.isChecked = tab == HomeTab.TOOLBOX
        if (tab == HomeTab.TOOLBOX) {
            binding.btnStartQueue.text =
                getString(if (gachaRunning) R.string.quick_stop else R.string.btn_start_queue)
        } else if (!isTaskRunning) {
            binding.btnStartQueue.text = getString(R.string.btn_start_queue)
        }
        scheduleSave()
    }

    /** 一键长草：队列视图 ⇄ 配置管理 */
    private fun setConfigMode(on: Boolean) {
        configMode = on
        binding.panelQueue.visibility = if (on) View.GONE else View.VISIBLE
        binding.panelConfig.visibility = if (on) View.VISIBLE else View.GONE
        binding.tvQueueHint.text = if (on) "当前生效：$activeProfile" else getString(R.string.hint_queue)
        binding.btnEditConfig.text = getString(if (on) R.string.config_done else R.string.config_edit)
        if (on) {
            refreshProfilePanel()
            // 配置管理首访引导
            vdHandler.postDelayed({
                if (canShowScenarioGuide(GuideStore.KEY_CONFIG)) {
                    onboarding.start(buildConfigGuideSteps(), listOf(GuideStore.KEY_CONFIG), homeFirst = false)
                }
            }, 450)
        }
    }

    /** 刷新某任务的列表摘要显示（主队列与额外队列各刷一次） */
    private fun refreshRow(item: TaskItem) {
        val idx = tasks.indexOf(item)
        if (idx >= 0) adapter.notifyItemChanged(idx)
        if (::toolsAdapter.isInitialized) {
            val tIdx = toolsTasks.indexOf(item)
            if (tIdx >= 0) toolsAdapter.notifyItemChanged(tIdx)
        }
    }

    /**
     * 按清单 option 动态渲染参数面板（主队列与额外队列共用）。
     * 返回 false = 清单里没给这个任务声明 option（如 adb 直达的临时探针），调用方显示提示。
     */
    private fun renderOptionsFor(container: LinearLayout, item: TaskItem): Boolean {
        val m = manifest ?: return false
        val def = m.tasks.firstOrNull { it.name == item.name }
            ?: m.tasks.firstOrNull { it.entry == item.entry }
            ?: return false
        if (def.options.isEmpty()) return false
        for (key in def.options) {
            val o = m.option(key) ?: continue
            when (o.type) {
                "input" -> renderInputOption(container, item, o)
                "switch" -> renderSwitchOption(container, item, o)
                else -> renderSelectOption(container, item, o)
            }
        }
        return true
    }

    /** 单击任务行：右侧按 interface.json 的 option 动态渲染编辑面板 */
    private fun selectTask(i: Int) {
        if (i < 0 || i >= tasks.size) return
        editingIndex = i
        val item = tasks[i]
        // 错位排障（2026-09-17：点 A 行跳出 B 的编辑面板）：记录点击位置与全表，
        // 若屏幕行文字与这里的 name 对不上，即为列表渲染与数据不同步的竞态现场
        android.util.Log.i("MaaWH", "SELECT main[$i]=${item.name} tasks=${tasks.map { it.name }}")
        binding.tvEditTitle.text = "编辑: ${item.label}"
        // 归属切换按钮：清单声明的任务才给（挪到额外队列当临时调试项）
        val declared = manifest?.tasks?.any { it.name == item.name || it.entry == item.entry } == true
        binding.btnEditMove.visibility = if (declared) View.VISIBLE else View.GONE
        binding.btnEditMove.setOnClickListener { moveTaskTo(item, HOME_TOOLS) }
        val container = binding.layoutOptionsMain
        container.removeAllViews()
        if (!renderOptionsFor(container, item)) {
            binding.tvEditHint.text = "该任务无可配置参数"
            return
        }
        binding.tvEditHint.text = "参数来自任务包清单 interface.json"
    }

    /** select：下拉框（次数、关卡等枚举） */
    private fun renderSelectOption(parent: LinearLayout, item: TaskItem, o: TaskPack.OptionDef) {
        val ctx = this
        val label = TextView(ctx).apply {
            text = o.label
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        }
        val spinner = Spinner(ctx).apply {
            background = getDrawable(R.drawable.bg_card)
            adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_item, o.cases.map { it.label }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(o.cases.indexOfFirst { it.name == item.selection.get(o.key, o.defaultCase) }
                .let { if (it < 0) 0 else it }, false)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    o.cases.getOrNull(pos)?.let {
                        item.selection.caseOf[o.key] = it.name
                        item.summary = summarize(item)
                        refreshRow(item)
                        scheduleSave()
                    }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        parent.addView(label)
        parent.addView(spinner, LinearLayout.LayoutParams(match(), wrap()))
    }

    /** switch：勾选框（子任务开关，Yes/No） */
    private fun renderSwitchOption(parent: LinearLayout, item: TaskItem, o: TaskPack.OptionDef) {
        val ctx = this
        val cur = item.selection.get(o.key, o.defaultCase)
        val cb = CheckBox(ctx).apply {
            text = o.label
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 13f
            isChecked = cur.equals("Yes", true) || cur.equals("Y", true)
            setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                item.selection.caseOf[o.key] = if (checked) "Yes" else "No"
                item.summary = summarize(item)
                refreshRow(item)
                scheduleSave()
            }
        }
        parent.addView(cb)
    }

    /** input：文本框（角色名、次数等自由输入），带正则校验 */
    private fun renderInputOption(parent: LinearLayout, item: TaskItem, o: TaskPack.OptionDef) {
        val ctx = this
        for (inp in o.inputs) {
            val label = TextView(ctx).apply {
                // 字段标题留空会被编辑器回退成参数名；与参数名同名时只显示一段，避免「刷取次数 · 刷取次数」
                text = if (inp.label.isBlank() || inp.label == o.label) o.label
                       else "${o.label} · ${inp.label}"
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(10), 0, 0)
            }
            val et = EditText(ctx).apply {
                setText(item.selection.input(inp.name, inp.default))
                background = getDrawable(R.drawable.bg_card)
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 14f
                inputType = if (inp.pipelineType == "int") InputType.TYPE_CLASS_NUMBER
                            else InputType.TYPE_CLASS_TEXT
                maxLines = 1
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val v = s?.toString() ?: ""
                        item.selection.inputOf[inp.name] = v
                        item.summary = summarize(item)
                        val bad = inp.verify?.let { v.isNotEmpty() && !Regex(it).matches(v) } ?: false
                        error = if (bad) inp.patternMsg ?: "输入不合法" else null
                        refreshRow(item)
                        scheduleSave()
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            parent.addView(label)
            parent.addView(et, LinearLayout.LayoutParams(match(), wrap()))
        }
    }

    /** 任务历史弹窗：队列级记录（何时跑的/几项/成败/耗时），跨重启保留在 files/history.json */
    private fun showHistory() {
        startActivity(Intent(this, HistoryActivity::class.java))
    }

    // ==================================================================
    // 运行
    // ==================================================================

    private fun startQueue() {
        // 运行中再点本按钮 = 停止任务（按钮文字已切换为「停止任务」）
        if (isTaskRunning) {
            stopNow()
            return
        }
        // 按当前 tab 决定跑哪个队列：额外队列 tab 跑工具队列，其余（含配置 tab）跑生效配置的主队列
        val onTools = homeTab == HomeTab.TOOLS
        val plan = if (onTools) {
            toolsTasks.indices.filter { toolsEnabled.getOrElse(it) { false } }.map { toolsTasks[it] }
        } else {
            tasks.indices.filter { enabled.getOrElse(it) { false } }.map { tasks[it] }
        }
        if (plan.isEmpty()) {
            toast("请先勾选要运行的任务")
            return
        }
        if (!shizukuReady()) {
            log("无法运行：Shizuku ${if (!shizukuRunning()) "未运行" else "未授权"}", LogLevel.ERR)
            refreshStatus()
            return
        }
        runQueue(plan)
    }

    private fun runQueue(planTasks: List<TaskItem>) {
        // Android 16 FUSE 下，adb 以 shell 身份推入外部目录的文件 App 自身无权读取，
        // MaaFramework 原生层 stat 失败会抛未捕获异常直接 abort 整个进程；
        // 故优先用内部 taskpacks（adb 可经 run-as 铺内），不存在时回退外部目录
        val whmxDir = resolveBundleDir()
        if (whmxDir == null) {
            log("任务包缺失：files/taskpacks/whmx", LogLevel.ERR)
            toast("未找到任务包 whmx，详见日志")
            return
        }
        // 保活：任务期间挂前台服务（通知权限顺手要一下，通知可见性影响系统对待方式）
        requestNotifPermission()
        queueRunner = QueueRunner(
            context = applicationContext,
            whmxDir = whmxDir,
            logDir = File(filesDir, "maa_logs"),
            manifest = manifest,
            cb = object : QueueRunner.Callbacks {
                override fun onLog(msg: String, level: LogLevel) = log(msg, level)
                override fun onRunState(text: String, colorRes: Int) = setRunState(text, colorRes)
                override fun onQueueStarted() {
                    binding.btnStartQueue.text = getString(R.string.quick_stop)
                }
                override fun onQueueFinished() {
                    binding.btnStartQueue.isEnabled = true
                    binding.btnStartQueue.text = getString(R.string.btn_start_queue)
                }
                override fun onVdFlagSet() { vdOn = true }
                override fun onGameEnteredVd() {
                    VdStreamer.start()
                    vdHandler.removeCallbacks(vdUiTick)
                    vdHandler.post(vdUiTick)
                    setRunState("游戏已进虚拟屏，收口中…", R.color.accent)
                }
                override fun isVdOn() = vdOn
                /** 队列收尾是否保持游戏静音：手动静音开着，或「游戏启动后静音」开着且虚拟屏还活着 */
                override fun holdGameMute(vdAlive: Boolean) = muteEnabled || (autoMuteEnabled && vdAlive)
                override fun onToast(msg: String) = toast(msg)
            }
        )
        lifecycleScope.launch(Dispatchers.IO) {
            queueRunner?.run(planTasks, muteEnabled, autoMuteEnabled, closeAfterEnabled)
        }
    }

    /**
     * 「关闭游戏声音」确认框（仿 MAA-Meow 的静音警告弹窗）：appops 依赖系统接口，
     * 部分机型可能无效或无法自动恢复，先警告再执行。恢复方向不弹框（无风险）。
     */
    private fun showMuteConfirmDialog(onConfirm: () -> Unit) {
        var dlg: android.app.Dialog? = null
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(getColor(R.color.bg_card))
            }
        }
        // 标题行：圆形色盘里的静音图标 + 大标题
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(this@MainActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x264A90E2)
                }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_q_mute)
                    setColorFilter(getColor(R.color.accent))
                    layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER }
                })
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
            })
            addView(TextView(this@MainActivity).apply {
                text = "确认关闭游戏声音？"
                setTextColor(getColor(R.color.text_primary))
                textSize = 17f
                paint.isFakeBoldText = true
            })
        })
        card.addView(
            TextView(this).apply {
                text = "该功能依赖系统接口，部分机型可能无效或者无法自动恢复声音。\n更稳妥的做法是直接用音量键把媒体音量调整到 0。"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 14f
                setLineSpacing(dp(2).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        )
        val ok = TextView(this).apply {
            text = "仍要静音"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(getColor(R.color.accent))
            }
            isClickable = true
            setOnClickListener { dlg?.dismiss(); onConfirm() }
        }
        card.addView(
            ok,
            LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        )
        card.addView(
            TextView(this).apply {
                text = "取消"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { dlg?.dismiss() }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        dlg = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(card)
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                setGravity(Gravity.CENTER)
                setLayout((resources.displayMetrics.widthPixels * 0.84f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        dlg.show()
    }

    /** 快捷选项：Meow 式底部卡片面板——快捷操作按钮区 + 自动设置勾选区 */
    private fun showQuickMenu() {
        fun sectionLabel(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.accent))
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(0, dp(6), 0, dp(2))
        }

        fun actionButton(text: String, iconRes: Int, danger: Boolean = false, onClick: () -> Unit): View {
            val tint = getColor(if (danger) R.color.err_red else R.color.text_primary)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(
                    android.widget.ImageView(this@MainActivity).apply {
                        setImageResource(iconRes)
                        setColorFilter(tint)
                        layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(7) }
                    }
                )
                addView(
                    TextView(this@MainActivity).apply {
                        this.text = text
                        setTextColor(tint)
                        textSize = 13f
                    }
                )
            }
            return LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(8), dp(2), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(0xFF1E2634.toInt())
                    setStroke(dp(1), if (danger) 0x80FF6B6B.toInt() else 0xFF2E3947.toInt())
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                addView(content)
            }
        }

        fun actionRow(vararg buttons: View): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            buttons.forEach { b ->
                addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                })
            }
        }

        fun toggleRow(label: String, iconRes: Int, checked: Boolean, onChange: (Boolean) -> Unit): View {
            val box = CheckBox(this).apply {
                isChecked = checked
                scaleX = 0.85f
                scaleY = 0.85f
            }
            val labelView = TextView(this).apply {
                this.text = label
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                compoundDrawablePadding = dp(7)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(
                    getColor(R.color.text_secondary)
                )
                getDrawable(iconRes)?.let {
                    it.setBounds(0, 0, dp(14), dp(14))
                    setCompoundDrawablesRelativeWithIntrinsicBounds(it, null, null, null)
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(2), dp(4), dp(2))
                addView(
                    labelView,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(box)
            }
            box.setOnCheckedChangeListener { _, v -> onChange(v) }
            return row
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
                setColor(getColor(R.color.bg_card))
            }
        }

        panel.addView(sectionLabel("快捷操作"))
        panel.addView(actionRow(
            actionButton("全屏画面", R.drawable.ic_q_full) {
                if (!vdOn) toast("请先启动虚拟屏")
                else startActivity(android.content.Intent(this, VdFullscreenActivity::class.java))
            },
            actionButton("防杀设置", R.drawable.ic_q_shield) { showKeepAliveDialog() }
        ))
        // 「关闭游戏声音」框式按钮（在【关闭游戏】上面；状态自描述：静音中显示「恢复游戏声音」）。
        // 开启走确认框（appops 依赖系统接口，部分机型可能无法恢复，先警告再执行）；
        // 关闭是恢复方向，立即执行不弹框。
        var muteLabel: TextView? = null
        fun applyMuteChange(toMute: Boolean) {
            muteEnabled = toMute
            scheduleSave()
            muteLabel?.text = getString(if (toMute) R.string.quick_unmute else R.string.quick_mute)
            lifecycleScope.launch(Dispatchers.IO) {
                // 先落持久化标记再静音（对齐 maameow）：进程被杀后下次启动凭标记自愈
                val ok = if (toMute) {
                    GameAudioMarker.mark(this@MainActivity, MaaConst.GAME_PKG)
                    ShizukuShell.setGameAudioMuted(true)
                } else {
                    GameAudioMarker.restoreIfNeeded(this@MainActivity) || !ShizukuShell.isGameAudioMuted()
                }
                runOnUiThread {
                    toast(
                        when {
                            !ok -> "操作失败（Shizuku 是否在线？）"
                            toMute -> "已单独静音游戏"
                            else -> "已恢复游戏声音"
                        }
                    )
                }
            }
        }
        val muteBtn = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(8), dp(2), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(0xFF1E2634.toInt())
                setStroke(dp(1), 0xFF2E3947.toInt())
            }
            isClickable = true
            isFocusable = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_q_mute)
                    setColorFilter(getColor(R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(7) }
                })
                addView(TextView(this@MainActivity).apply {
                    muteLabel = this
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 13f
                })
            })
            setOnClickListener {
                if (muteEnabled) applyMuteChange(false)
                else showMuteConfirmDialog { applyMuteChange(true) }
            }
        }
        muteLabel?.text = getString(if (muteEnabled) R.string.quick_unmute else R.string.quick_mute)
        panel.addView(actionRow(
            muteBtn,
            actionButton("关闭游戏", R.drawable.ic_q_stop, danger = true) {
                toast("正在关闭游戏…")
                log("快捷操作：关闭游戏")
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { ShizukuShell.execBlocking("am", "force-stop", MaaConst.GAME_PKG) }
                        .onFailure { log("关闭游戏失败: ${it.message}") }
                }
            }
        ))

        panel.addView(sectionLabel("自动设置"))
        panel.addView(toggleRow(getString(R.string.quick_close), R.drawable.ic_q_stop, closeAfterEnabled) { checked ->
            closeAfterEnabled = checked
            scheduleSave()
        })
        panel.addView(toggleRow(getString(R.string.quick_autofmute), R.drawable.ic_q_mute, autoMuteEnabled) { checked ->
            autoMuteEnabled = checked
            scheduleSave()
            // 无立即动作：下次「启动」任务/进虚拟屏把游戏跑起来时自动静音，
            // 游戏还挂在虚拟屏里就保持（退出 MaaWH/关虚拟屏时照常恢复）
        })
        panel.addView(toggleRow(getString(R.string.setting_pip), R.drawable.ic_q_pip, pipEnabled) { checked ->
            binding.switchPip.isChecked = checked   // 复用设置页开关的同一套逻辑（含悬浮窗授权引导）
        })

        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(panel)

        // 定位在【快捷选项】按钮正上方（虚拟屏预览之下），而不是盖住底部
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val dm = resources.displayMetrics
        val margin = dp(12)
        val w = dm.widthPixels - margin * 2
        val loc = IntArray(2)
        binding.btnQuick.getLocationOnScreen(loc)
        val y = (loc[1] - panel.measuredHeight - dp(10)).coerceAtLeast(dp(48))

        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply {
                x = margin
                this.y = y
            }
        }
        dlg.show()
    }

    /**
     * 静音残留自愈：凭持久化标记恢复上次会话没恢复成的静音（队列运行中不动作）。
     * 恢复后若「游戏启动后关闭游戏声音」开着且虚拟屏还活着（游戏仍在跑，App 被杀重启
     * 时虚拟屏挂在 Shizuku 服务上会幸存），立即按开关重新静音——否则冷启动后挂机中的
     * 游戏一直有声，直到下次跑任务。autoMute 从持久化读，避免与 onCreate 主线程加载竞态。
     */
    private fun selfHealGameAudio() {
        if (queueRunner?.running == true) return
        if (GameAudioMarker.restoreIfNeeded(applicationContext)) {
            log("检测到上次会话的游戏静音残留，已恢复游戏声音", LogLevel.INFO)
            val autoMute = runCatching { QueueStore.load(applicationContext)?.autoMute ?: false }.getOrDefault(false)
            if (autoMute && ShizukuShell.isVdAlive()) {
                GameAudioMarker.mark(applicationContext, MaaConst.GAME_PKG)
                if (ShizukuShell.setGameAudioMuted(true)) {
                    log("已按「游戏启动后关闭游戏声音」重新静音（虚拟屏存活，游戏仍在运行）", LogLevel.INFO)
                }
            }
        }
    }

    /** 立即静音/恢复游戏声音走快捷面板的 toggleRow（GameAudioMarker 打标记 + ShizukuShell 执行） */

    // ==================================================================
    // 保活（前台服务）：任务运行中 / 虚拟屏存活期间常驻，避免被系统当缓存进程回收
    // ==================================================================

    private fun keepAlive(text: String) = KeepAliveService.start(this, text).also {
        KeepAliveService.autoMuteWatch = autoMuteEnabled
    }

    private fun stopKeepAlive() {
        KeepAliveService.autoMuteWatch = false
        KeepAliveService.stop(this)
    }

    /** Android 13+ 通知权限：给了前台服务通知才可见（被拒也不影响服务本身与优先级） */
    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        runCatching {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    // ==================================================================
    // 防后台被杀设置（Shizuku 保活）
    // ==================================================================

    private fun ignoringBattery(pkg: String): Boolean = try {
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)
    } catch (e: Throwable) {
        false
    }

    /**
     * 启动时体检：两者没进电池优化白名单就提示一次。
     * 依据：实测本机 `dumpsys activity exit-info` 里 Shizuku 管理器被 ColorOS 反复清理
     * （o-stop(40)/o-kill/Cached(nirvana)），MaaWH 自己也以 importance=400 缓存进程身份被杀。
     */
    private fun checkKeepAliveSetup() {
        val self = ignoringBattery(packageName)
        val sh = ignoringBattery(MaaConst.SHIZUKU_PKG)
        if (self && sh) {
            log("防杀体检：MaaWH 与 Shizuku 均已在电池优化白名单")
        } else {
            val who = listOfNotNull(
                if (self) null else "MaaWH",
                if (sh) null else "Shizuku"
            ).joinToString("、")
            log("防杀体检：${who}未加入电池优化白名单 → 系统回收后台时会连带 Shizuku 掉线。" +
                "到【快捷选项 → 防后台被杀设置】处理")
        }
    }

    private fun showKeepAliveDialog() {
        val self = ignoringBattery(packageName)
        val sh = ignoringBattery(MaaConst.SHIZUKU_PKG)
        // 说明 + 可点条目都放进自绘视图：AlertDialog 的 setMessage 与 setItems 同时用会只显示其一
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        box.addView(TextView(this).apply {
            text = "MaaWH 不会主动关闭 Shizuku。Shizuku 掉线通常是因为系统省电策略回收了后台进程，" +
                "把两个应用都设为「忽略电池优化」可显著降低掉线概率。\n\n" +
                "MaaWH：${if (self) "✓ 已忽略电池优化" else "✗ 仍受电池优化限制（建议开启）"}\n" +
                "Shizuku：${if (sh) "✓ 已忽略电池优化" else "✗ 仍受电池优化限制（建议开启）"}"
            textSize = 13f
            setTextColor(getColorCompat(R.color.text_primary))
            setPadding(0, dp(8), 0, dp(10))
        })
        var dlg: AlertDialog? = null
        val actions = listOf<Pair<String, () -> Unit>>(
            "① 设置 MaaWH 忽略电池优化" to { requestIgnoreBattery(packageName) },
            "② 设置 Shizuku 忽略电池优化" to { requestIgnoreBattery(MaaConst.SHIZUKU_PKG) },
            "③ ${vendorName()}手动设置清单（自启动 / 应用速冻 / 锁定后台 / Watchdog）" to { showColorOsChecklist() },
            "④ 打开 Shizuku 应用详情" to { openAppDetails(MaaConst.SHIZUKU_PKG) }
        )
        for ((label, act) in actions) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(getColorCompat(R.color.text_primary))
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                val lp = LinearLayout.LayoutParams(match(), wrap())
                lp.topMargin = dp(6)
                layoutParams = lp
                isClickable = true
                setOnClickListener {
                    dlg?.dismiss()
                    act()
                }
            })
        }
        dlg = AlertDialog.Builder(this)
            .setTitle(R.string.keepalive_title)
            .setView(box)
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
    }

    /** 拉系统「不优化」确认框；机型/权限不支持时退回电池优化列表页 */
    private fun requestIgnoreBattery(pkg: String) {
        val direct = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$pkg"))
        val ok = runCatching { startActivity(direct) }.isSuccess
        log(if (ok) "已请求忽略电池优化：$pkg" else "直接请求失败，改开电池优化列表：$pkg")
        if (!ok) {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure { toast("请手动到 设置 → 电池 → 电池优化 里把 MaaWH/Shizuku 设为不优化") }
        }
    }

    private fun openAppDetails(pkg: String) = runCatching {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$pkg"))
        )
    }.onFailure { toast("未找到应用：$pkg") }.let { }

    /** ColorOS/realme 专有保活项：App 无法代改，只能引导 */
    /** 设备品牌名（首字母大写），用于按厂商动态生成提示文案 */
    private fun vendorName(): String =
        (android.os.Build.MANUFACTURER ?: "").replaceFirstChar { it.uppercase() }

    /** 按当前设备品牌显示省电设置自查清单（不同厂商入口名称略有差异，按需对照） */
    private fun showColorOsChecklist() {
        val vendor = (android.os.Build.MANUFACTURER ?: "").replaceFirstChar { it.uppercase() }
        val text = buildString {
            append("「$vendor」设备建议手动检查以下项目（缺一项都可能被杀）：\n\n")
            append("1. 设置 → 应用管理 → 自启动管理：MaaWH 与 Shizuku 都打开「自启动」「关联启动」\n")
            append("2. 设置 → 电池 → 应用速冻 / 省电策略：确认两者未被限制\n")
            append("3. 设置 → 电池 → 耗电保护：两者的「允许后台行为」打开\n")
            append("4. 多任务界面长按卡片 → 点锁图标锁定后台\n")
            append("5. 打开 Shizuku → 设置 → 开启 Watchdog（服务被杀后自动重启）\n")
            append("6. 给 MaaWH 通知权限（前台服务保活通知需要它）\n\n")
            append("不同系统版本的入口名称可能略有差异，对照关键词查找即可。做完这些，跑完任务后就不容易连 Shizuku 一起掉。")
        }
        AlertDialog.Builder(this)
            .setTitle("$vendor 保活清单")
            .setMessage(text)
            .setPositiveButton("打开 Shizuku 应用详情") { _, _ -> openAppDetails(MaaConst.SHIZUKU_PKG) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 停止任务：中断当前引擎任务，剩余队列不再执行 */
    private fun stopNow() {
        val r = queueRunner
        if (r == null || !r.running) {
            toast("当前没有运行中的任务")
            log("无运行中任务，无需停止")
            return
        }
        r.requestStop()
        log("已请求停止任务…")
        toast("正在停止任务")
    }

    // ==================================================================
    // M4 虚拟屏：实时预览 + 全屏
    // ==================================================================

    /** 主页预览：仅轻量显示 VdStreamer 的最新帧 */
    private val vdUiTick = object : Runnable {
        override fun run() {
            val f = VdShared.frame
            if (f != null) {
                if (binding.imageShot.tag != System.identityHashCode(f)) {
                    binding.imageShot.setImageBitmap(f)
                    binding.imageShot.tag = System.identityHashCode(f)
                    lastBitmap = f
                }
            }
            vdHandler.postDelayed(this, 150)
        }
    }

    private fun toggleVd() {
        if (isTaskRunning) { toast("任务运行中，请先停止"); return }
        vdOn = !vdOn
        if (vdOn) {
            setRunState("启动虚拟屏…", R.color.accent)
            log("启动虚拟屏…")
            requestNotifPermission()
            keepAlive(getString(R.string.keepalive_vd))
            lifecycleScope.launch(Dispatchers.IO) {
                val r = ShizukuShell.startVirtualGame()
                runCatching { File(filesDir, "m4result.txt").writeText(r) }
                // 静音应用：任一开关开着 → 先落标记再 deny（autoMute 语义 = 游戏启动即静音）；
                // 都关才走 AudioHardening 反制主动放行（闩锁跨会话存活，进虚拟屏就放行游戏音频）
                if (muteEnabled || autoMuteEnabled) {
                    runCatching { GameAudioMarker.mark(this@MainActivity, MaaConst.GAME_PKG) }
                    runCatching { ShizukuShell.setGameAudioMuted(true) }
                    runCatching { ShizukuShell.scheduleGameAudioRestoreGuard() }
                    if (autoMuteEnabled && !muteEnabled) {
                        runOnUiThread { log("已按「游戏启动后关闭游戏声音」静音游戏") }
                    }
                } else {
                    runCatching { ShizukuShell.assertGameAudioAllowed() }
                }
                runOnUiThread {
                    log(r)
                    setRunState("虚拟屏运行中（点预览=游戏内点击）", R.color.ok_green)
                    VdStreamer.start()
                    vdHandler.removeCallbacks(vdUiTick)
                    vdHandler.post(vdUiTick)
                }
            }
        } else {
            VdStreamer.stop()
            vdHandler.removeCallbacks(vdUiTick)
            lifecycleScope.launch(Dispatchers.IO) {
                ShizukuShell.stopVirtual()
                // 虚拟屏停了就不再管控游戏：清掉 appops 静音残留，用户回物理屏打开游戏有声
                // （快捷开关不受影响，下次跑队列仍会按它决定要不要静音）
                runCatching { GameAudioMarker.restoreIfNeeded(this@MainActivity) }
            }
            hideFullscreen()
            // 画面没了，后台悬浮窗一并收起
            FloatingPanel.hide()
            // 虚拟屏没了就不用再占着前台服务（任务在跑的话保留，由 runQueue 收尾时停）
            if (!isTaskRunning) stopKeepAlive()
            setRunState(getString(R.string.run_ready), R.color.text_secondary)
            log("已停止虚拟屏")
        }
    }

    private fun buildVdOverlay() {
        vdOverlay = FrameLayout(this)
        vdOverlay.setBackgroundColor(0xFF000000.toInt())
        vdFullImg = ImageView(this)
        vdFullImg.scaleType = ImageView.ScaleType.FIT_CENTER
        vdOverlay.addView(vdFullImg, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val close = TextView(this)
        close.text = "×"
        close.setTextColor(0xFFFFFFFF.toInt())
        close.textSize = 40f
        close.setPadding(28, 8, 28, 28)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        close.layoutParams = lp
        close.setOnClickListener {
            log("点击了关闭全屏 ×")
            hideFullscreen()
        }
        vdOverlay.addView(close)
    }

    private fun showFullscreen() {
        log("进入全屏（转横屏）")
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val decor = window.decorView as FrameLayout
        if (vdOverlay.parent == null) decor.addView(vdOverlay, MATCH_PARENT, MATCH_PARENT)
        vdOverlay.visibility = View.VISIBLE
        vdOverlay.bringToFront()
        lastBitmap?.let { vdFullImg.setImageBitmap(it) }
    }

    private fun hideFullscreen() {
        if (vdOverlay.isShown || vdOverlay.visibility == View.VISIBLE) {
            vdOverlay.visibility = View.GONE
        }
        if (!isFinishing) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun toggleFullscreen() {
        if (!vdOn) return
        if (vdOverlay.isShown) hideFullscreen() else showFullscreen()
    }

    // ==================================================================
    // 声音（经 Shizuku 进程的 AudioManager，shell 命令设置流音量不生效）
    // ==================================================================

    private fun getMusicVolume(): Int = ShizukuShell.getMusicVolume()

    private fun setMusicVolume(v: Int) = ShizukuShell.setMusicVolume(v)

    // ==================================================================
    // Shizuku 状态
    // ==================================================================

    private fun shizukuRunning() = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    private fun shizukuGranted(): Boolean {
        // binder 未就绪时 checkSelfPermission 会抛 IllegalStateException（冷启动常见），
        // 等 addBinderReceivedListenerSticky 触发后再刷新即可
        if (!shizukuRunning()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    private fun shizukuReady() = shizukuRunning() && shizukuGranted()

    /** 引导弹窗引用（新弹窗出现前关掉旧的，避免叠窗）；同状态只提示一次 */
    private var shizukuDialog: AlertDialog? = null
    private var lastPromptedCase = 0

    private fun isShizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo(MaaConst.SHIZUKU_PKG, 0); true
    } catch (e: Throwable) {
        false
    }

    /**
     * 进入 App 时检测 Shizuku：未安装 / 服务未运行 / 未授权 → 弹窗引导。
     * 点击弹窗按钮可直达 Shizuku（官网 / 打开 Shizuku app / 申请授权）。
     */
    private fun maybePromptShizuku() {
        // 引导展示中不弹 Shizuku 提示（对标 maameow 的 blocksStartupDialogs），引导结束回调里补检
        if (::onboarding.isInitialized && onboarding.isActive) return
        if (shizukuReady()) return
        val case = when {
            !isShizukuInstalled() -> CASE_NOT_INSTALLED
            !shizukuRunning() -> CASE_NOT_RUNNING
            else -> CASE_NOT_GRANTED
        }
        // 同一状态已提示过就不再弹（用户从 Shizuku 启动服务回来后状态变化会重新检测）
        if (case == lastPromptedCase) return
        showShizukuGuide(case)
    }

    private fun showShizukuGuide(case: Int) {
        if (isFinishing || isDestroyed) return
        shizukuDialog?.dismiss()
        val (title, msg, pos) = when (case) {
            CASE_NOT_INSTALLED -> Triple(
                "未安装 Shizuku",
                "MaaWH 依赖 Shizuku 执行自动化操作。请先安装 Shizuku（官网有下载地址与启动教程），启动服务并授权后再使用。",
                "打开官网"
            )
            CASE_NOT_RUNNING -> Triple(
                "Shizuku 未启动",
                "检测到 Shizuku 服务未运行。点击「打开 Shizuku」，在 Shizuku 中启动服务（Android 11+ 推荐用无线调试启动），完成后返回 MaaWH。",
                "打开 Shizuku"
            )
            else -> Triple(
                "需要 Shizuku 授权",
                "Shizuku 服务已运行，但 MaaWH 尚未获得授权。点击「申请授权」，并在弹出的确认框中允许。",
                "申请授权"
            )
        }
        lastPromptedCase = case
        log(when (case) {
            CASE_NOT_INSTALLED -> "检测到未安装 Shizuku，已弹窗引导"
            CASE_NOT_RUNNING -> "检测到 Shizuku 未启动，已弹窗引导"
            else -> "检测到 Shizuku 未授权，已弹窗引导"
        })
        shizukuDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(true)
            .setPositiveButton(pos) { _, _ ->
                when (case) {
                    CASE_NOT_INSTALLED -> runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_GUIDE_URL)))
                    }
                    CASE_NOT_RUNNING -> openShizukuApp()
                    else -> requestShizukuPermission()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openShizukuApp() {
        val i = packageManager.getLaunchIntentForPackage(MaaConst.SHIZUKU_PKG)
        if (i != null) {
            startActivity(i)
        } else {
            toast("未找到 Shizuku 应用，请先安装")
        }
    }


    private fun refreshStatus() {
        val running = shizukuRunning()
        val granted = shizukuGranted()
        binding.tvStatus.text = buildString {
            append("Shizuku 服务：")
            append(if (running) "在线" else "离线")
            if (running) append(if (granted) " ｜ 权限：已授权" else " ｜ 权限：未授权")
            else append("\n请先在手机打开 Shizuku 并启动")
            append("\n设备：${Build.MODEL} ｜ Android ${Build.VERSION.RELEASE}")
        }
        binding.tvStatus.setTextColor(
            if (running) getColorCompat(R.color.ok_green) else getColorCompat(R.color.err_red)
        )
        binding.btnRequest.isEnabled = running && !granted
    }

    private fun requestShizukuPermission() {
        when {
            !shizukuRunning() -> { toast("Shizuku 未运行"); log("请先启动 Shizuku") }
            shizukuGranted() -> toast("已授权")
            else -> Shizuku.requestPermission(REQ_SHIZUKU)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SHIZUKU) {
            log(if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权" else "授权被拒")
            refreshStatus()
        }
    }

    private fun setupNav() {
        binding.bottomNav.setOnItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                R.id.nav_home -> switchTo(binding.panelHome)
                R.id.nav_log -> switchTo(binding.panelLog)
                R.id.nav_settings -> switchTo(binding.panelSettings)
                R.id.nav_gacha -> {
                    switchTo(binding.panelGacha)
                    // 抽卡页首访引导：等切页布局完成再弹，避免与任务/抓取运行冲突
                    vdHandler.postDelayed({
                        if (canShowScenarioGuide(GuideStore.KEY_GACHA)) {
                            onboarding.start(buildGachaGuideSteps(), listOf(GuideStore.KEY_GACHA), homeFirst = false)
                        }
                    }, 450)
                }
            }
            true
        }
    }

    // ==================================================================
    // 抽卡记录（抓取 + 数据面板）
    // ==================================================================

    @Volatile
    private var gachaRunning = false
    private var gachaJob: kotlinx.coroutines.Job? = null

    private fun setupGachaAccounts() {
        // 点账号名 = 管理菜单（改名/删除/新建/切换）；点倒三角 = 直接弹账号切换列表
        binding.accountHeader.setOnClickListener { showGachaAccountMenu() }
        binding.tvGachaAccountName.setOnClickListener { showGachaAccountMenu() }
        binding.btnGachaAccountArrow.setOnClickListener {
            showGachaAccountPicker()
        }
        binding.btnGachaEdit.setOnClickListener { editGachaRecord() }
        refreshGachaAccounts()
    }

    /** 账号管理弹窗（自定义卡片，风格与编辑记录对话框一致）：改名/新建/切换/删除 */
    private fun showGachaAccountMenu() {
        val accName = GachaStore.activeAccountName(applicationContext)
        var dlgRef: androidx.appcompat.app.AlertDialog? = null

        fun menuRow(icon: String, label: String, colorRes: Int, onClick: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(13), dp(18), dp(13))
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { dlgRef?.dismiss(); onClick() }
                addView(TextView(this@MainActivity).apply {
                    text = icon
                    setTextColor(getColor(colorRes))
                    textSize = 14f
                    paint.isFakeBoldText = true
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(12) })
                addView(TextView(this@MainActivity).apply {
                    text = label
                    setTextColor(getColor(colorRes))
                    textSize = 14f
                })
            }

        fun divider() = View(this).apply {
            setBackgroundColor(0xFF2E3947.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            )
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = accName
                setTextColor(getColor(R.color.text_primary))
                textSize = 16f
                paint.isFakeBoldText = true
            })
            addView(TextView(this@MainActivity).apply {
                text = "记录与锚点随账号独立保存"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
                setPadding(0, dp(2), 0, 0)
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(getColor(R.color.bg_card))
            }
            addView(head)
            addView(divider())
            addView(menuRow("＋", "新建", R.color.text_primary) { createGachaAccount() })
            addView(menuRow("✎", "改名", R.color.text_primary) { renameGachaAccount() })
            addView(menuRow("⇄", "切换账号", R.color.text_primary) {
                // 锚点必须用 Activity 窗口的 view：dialog 内部 view 会让 PopupMenu 定位失败
                showGachaAccountPicker()
            })
            addView(menuRow("↺", "恢复账号", R.color.text_primary) {
                showGachaRestoreList()
            })
            addView(divider())
            addView(menuRow("✕", "删除", R.color.err_red) { deleteGachaAccount() })
        }

        val dlg = AlertDialog.Builder(this).create()
        dlg.setView(root)
        dlgRef = dlg
        dlg.show()
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** 账号下拉宽度：账号名左缘 → 倒三角右缘 */
    private fun accountPopupWidth(): Int {
        val nameLoc = IntArray(2)
        binding.tvGachaAccountName.getLocationOnScreen(nameLoc)
        val arrowLoc = IntArray(2)
        binding.btnGachaAccountArrow.getLocationOnScreen(arrowLoc)
        return maxOf(arrowLoc[0] + binding.btnGachaAccountArrow.width - nameLoc[0], dp(160))
    }

    /** 账号切换下拉（两个入口共用）：ListPopupWindow 锚定账号名，宽度=账号名左缘→倒三角右缘 */
    private fun showGachaAccountPicker() {
        val accounts = GachaStore.listAccounts(applicationContext)
        val active = GachaStore.activeAccountId(applicationContext)
        val lpw = androidx.appcompat.widget.ListPopupWindow(this)
        lpw.setAdapter(object : ArrayAdapter<String>(
            this, R.layout.item_account_picker, accounts.map { it.name }
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_account_picker, parent, false)
                v.findViewById<TextView>(R.id.mark).text =
                    if (accounts[position].id == active) "●" else ""
                v.findViewById<TextView>(R.id.name).text = accounts[position].name
                return v
            }
        })
        lpw.setAnchorView(binding.tvGachaAccountName)
        lpw.setWidth(accountPopupWidth())
        lpw.setOnItemClickListener { _, _, pos, _ ->
            val acc = accounts.getOrNull(pos)
            if (acc != null && acc.id != GachaStore.activeAccountId(applicationContext)) {
                GachaStore.setActiveAccount(applicationContext, acc.id)
                log("抽卡账号切换：${acc.name}（记录/锚点随账号独立）", LogLevel.INFO)
                refreshGachaAccounts()
                renderGachaPanel()
            }
            lpw.dismiss()
        }
        lpw.show()
    }

    /** 恢复最近删除的账号（回收站保留 3 份），点选即恢复为新账号并激活 */
    private fun showGachaRestoreList() {
        val trash = GachaStore.listTrashedAccounts(applicationContext)
        if (trash.isEmpty()) {
            toast("最近没有可恢复的删除记录")
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val items = trash.map {
            "${it.name} · ${fmt.format(Date(it.deletedAt))} · ${it.recordCount} 条"
        }
        val lpw = androidx.appcompat.widget.ListPopupWindow(this)
        lpw.setAdapter(ArrayAdapter(this, R.layout.item_spinner_account, items))
        lpw.setAnchorView(binding.tvGachaAccountName)
        lpw.setWidth(accountPopupWidth())
        lpw.setOnItemClickListener { _, _, pos, _ ->
            lpw.dismiss()
            val t = trash.getOrNull(pos) ?: return@setOnItemClickListener
            val acc = GachaStore.restoreAccount(applicationContext, t)
            if (acc == null) {
                toast("恢复失败：备份数据已损坏")
            } else {
                refreshGachaAccounts()
                renderGachaPanel()
                log("已从回收站恢复账号「${acc.name}」（${t.recordCount} 条记录）", LogLevel.INFO)
                toast("已恢复「${acc.name}」")
            }
        }
        lpw.show()
    }

    private fun refreshGachaAccounts() {
        binding.tvGachaAccountName.text = GachaStore.activeAccountName(applicationContext)
    }

    private fun promptGachaAccountName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSingleLine()
            setSelection(initial.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createGachaAccount() {
        if (gachaRunning) { toast("抓取中不能操作账号"); return }
        promptGachaAccountName("新建账号", "账号${GachaStore.listAccounts(applicationContext).size + 1}") { name ->
            val acc = GachaStore.createAccount(applicationContext, name)
            refreshGachaAccounts()
            renderGachaPanel()
            log("新建抽卡账号「${acc.name}」并切换", LogLevel.INFO)
        }
    }

    private fun renameGachaAccount() {
        if (gachaRunning) { toast("抓取中不能操作账号"); return }
        val ctx = applicationContext
        val id = GachaStore.activeAccountId(ctx)
        val cur = GachaStore.listAccounts(ctx).find { it.id == id } ?: return
        promptGachaAccountName("重命名账号", cur.name) { name ->
            if (name.isNotEmpty()) {
                GachaStore.renameAccount(ctx, id, name)
                refreshGachaAccounts()
                renderGachaPanel()
            }
        }
    }

    private fun deleteGachaAccount() {
        if (gachaRunning) { toast("抓取中不能操作账号"); return }
        val ctx = applicationContext
        val id = GachaStore.activeAccountId(ctx)
        val acc = GachaStore.listAccounts(ctx).find { it.id == id } ?: return
        AlertDialog.Builder(this)
            .setTitle("删除账号")
            .setMessage("删除「${acc.name}」及其全部抽卡记录与锚点？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                if (GachaStore.deleteAccount(ctx, id)) {
                    refreshGachaAccounts()
                    renderGachaPanel()
                    log("已删除抽卡账号「${acc.name}」", LogLevel.WRN)
                } else {
                    toast("至少保留一个账号")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 编辑抽卡记录：顶部两个页签【标注UP器者】【添加抽卡记录】。
     * 标注页给限时/限定渠道的每个卡池小类设 UP 器者（统计卡据此算歪/UP平均）；
     * 添加页保留手动补录表单，并新增「最近手动补录」列表，可删除误录的记录。
     */
    private fun editGachaRecord() {
        if (gachaRunning) { toast("抓取中不能编辑记录"); return }
        val ctx = applicationContext
        val pools = try {
            GachaCrawler.Points.load(ctx).pools
        } catch (e: Throwable) {
            listOf("限时渠道", "限定渠道", "招集渠道", "征集渠道")
        }
        val upPools = listOf("限时渠道", "限定渠道")

        // ===== 页签行 =====
        fun tabBtn(text: String) = TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 13f
            paint.isFakeBoldText = true
            setPadding(dp(6), dp(9), dp(6), dp(9))
        }
        val tabUp = tabBtn("标注UP器者")
        val tabAdd = tabBtn("添加记录")
        val tabNames = tabBtn("器者名单")
        fun styleTab(t: TextView, sel: Boolean) {
            t.setTextColor(getColor(if (sel) R.color.text_primary else R.color.text_secondary))
            t.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (sel) 0x334C9AFF else 0x00000000)
            }
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(6), dp(14), dp(2))
            addView(tabUp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
            addView(tabAdd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
            addView(tabNames, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // ===== 面板1：标注UP器者 =====
        val upEditors = ArrayList<Triple<String, String, EditText>>() // (pool, banner原文, 输入框)
        val upBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(10))
                val marks = GachaStore.loadUpMarks(ctx)
                val allNow = GachaStore.loadRecords(ctx)
                for (pool in upPools) {
                    addView(TextView(this@MainActivity).apply {
                        text = pool
                        setTextColor(getColor(R.color.text_primary))
                        textSize = 14f
                        paint.isFakeBoldText = true
                        setPadding(0, dp(10), 0, dp(2))
                    })
                    val banners = LinkedHashSet<String>()
                    for (r in allNow) if (r.pool == pool) banners.add(r.banner)
                    if (banners.isEmpty()) {
                        addView(TextView(this@MainActivity).apply {
                            text = "该池暂无记录，抓取或补录后再来标注"
                            setTextColor(getColor(R.color.text_secondary))
                            textSize = 12f
                        })
                    }
                    for (b in banners) {
                        val label = b.substringAfter('/', b).ifBlank { "未识别" }
                        val cnt = allNow.count { it.pool == pool && it.banner == b }
                        val et = EditText(this@MainActivity).apply {
                            hint = "UP 器者名（留空清除）"
                            setSingleLine()
                            setText(marks[pool]?.get(b) ?: "")
                            textSize = 13f
                            setPadding(dp(10), dp(8), dp(10), dp(8))
                        }
                        upEditors.add(Triple(pool, b, et))
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(4), 0, dp(4))
                            addView(TextView(this@MainActivity).apply {
                                text = "『$label』"
                                setTextColor(getColor(R.color.accent))
                                textSize = 12f
                            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            addView(TextView(this@MainActivity).apply {
                                text = "$cnt 抽"
                                setTextColor(getColor(R.color.text_secondary))
                                textSize = 11f
                            }, LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = dp(8) })
                            addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
                        })
                    }
                }
                addView(TextView(this@MainActivity).apply {
                    text = "标注后顶部统计卡按「出卡数 / 歪」与「UP平均」显示；歪 = 小类标注 UP 以外的特出。"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 11f
                    setPadding(0, dp(10), 0, dp(8))
                })
            }
        val btnUpSave = TextView(this).apply {
            text = "保存标注"
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            paint.isFakeBoldText = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(getColor(R.color.accent))
            }
            setPadding(dp(10), dp(11), dp(10), dp(11))
        }
        upBox.addView(btnUpSave)
        val panelUp = ScrollView(this).apply { addView(upBox) }
        val panelUpWrap = FrameLayout(this).apply {
            addView(panelUp, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        // ===== 面板2：添加抽卡记录 =====
        val nameEdit = EditText(this).apply {
            hint = "如：银香囊"
            setSingleLine()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val bannerEdit = EditText(this).apply {
            hint = "如：至乐如真（可留空）"
            setSingleLine()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val timeEdit = EditText(this).apply {
            hint = "2026-08-01 10:30"
            setSingleLine()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val poolSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, R.layout.item_spinner_account, pools)
        }
        val rarSpin = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, R.layout.item_spinner_account, listOf("特出", "优异", "新生")
            )
        }

        fun row(label: String, view: View): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(
                TextView(this@MainActivity).apply {
                    text = label
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 13f
                },
                LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val btnAdd = TextView(this).apply {
            text = "＋ 添加记录"
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            paint.isFakeBoldText = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(getColor(R.color.accent))
            }
            setPadding(dp(10), dp(11), dp(10), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        // 最近手动补录列表（可删除）
        val manualBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        fun refreshManualList() {
            manualBox.removeAllViews()
            val manuals = GachaStore.loadRecords(ctx).filter { it.manual }
            manualBox.addView(TextView(this@MainActivity).apply {
                text = "最近手动补录（${manuals.size}）"
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                paint.isFakeBoldText = true
                setPadding(0, dp(4), 0, dp(2))
            })
            if (manuals.isEmpty()) {
                manualBox.addView(TextView(this@MainActivity).apply {
                    text = "暂无手动补录的记录"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                })
                return
            }
            for (r in manuals.take(20)) {
                val bannerLabel = r.banner.substringAfter('/', r.banner).ifBlank { "未识别" }
                val line = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            text = r.name + when (r.rarity) {
                                GachaStore.RARITY_TOP -> "  特出"
                                GachaStore.RARITY_MID -> "  优异"
                                else -> "  新生"
                            }
                            setTextColor(
                                getColor(
                                    when (r.rarity) {
                                        GachaStore.RARITY_TOP -> R.color.err_red
                                        GachaStore.RARITY_MID -> R.color.accent
                                        else -> R.color.text_secondary
                                    }
                                )
                            )
                            textSize = 12f
                            paint.isFakeBoldText = true
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = "${r.pool.removeSuffix("渠道")} · $bannerLabel"
                            setTextColor(getColor(R.color.text_secondary))
                            textSize = 10f
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = GachaStore.shortTime(r.ts)
                        setTextColor(getColor(R.color.text_secondary))
                        textSize = 10f
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "  ✕"
                        setTextColor(getColor(R.color.text_secondary))
                        textSize = 14f
                        setPadding(dp(10), dp(4), dp(4), dp(4))
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("删除补录记录")
                                .setMessage("删除「${r.name}」(${GachaStore.shortTime(r.ts)})？")
                                .setPositiveButton("删除") { _, _ ->
                                    if (GachaStore.deleteRecord(ctx, r.uid)) {
                                        refreshManualList()
                                        renderGachaPanel()
                                        log("已删除手动补录记录：[${r.uid}]", LogLevel.WRN)
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    })
                }
                manualBox.addView(line)
            }
            if (manuals.size > 20) {
                manualBox.addView(TextView(this@MainActivity).apply {
                    text = "仅显示最近 20 条"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 10f
                    setPadding(0, dp(2), 0, 0)
                })
            }
        }

        btnAdd.setOnClickListener {
            val name = nameEdit.text.toString().trim()
            val banner = bannerEdit.text.toString().trim()
            val ts = GachaDictionary.parseManualTime(timeEdit.text.toString().trim())
            val rarity = rarSpin.selectedItem?.toString() ?: "新生"
            val pool = poolSpin.selectedItem?.toString() ?: pools[0]
            if (name.isEmpty()) { toast("器者名不能为空"); return@setOnClickListener }
            if (ts == null) { toast("时间格式不对：应如 2026-08-01 10:30"); return@setOnClickListener }
            val rec = GachaStore.addManualRecord(ctx, pool, banner, name, rarity, ts)
            // 批量补录：只清名字、保留时间（同一次十连的时间相同），光标回名字栏
            nameEdit.setText("")
            nameEdit.requestFocus()
            refreshManualList()
            renderGachaPanel()
            log("手动添加记录：[${rec.uid}]", LogLevel.INFO)
            toast("已添加")
        }

        val panelAdd = ScrollView(this).apply {
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(12))
                    addView(row("卡池大类", poolSpin))
                    addView(row("卡池小类", bannerEdit))
                    addView(row("器者名", nameEdit))
                    addView(row("稀有度", rarSpin))
                    addView(row("抽卡时间", timeEdit))
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "时间格式：2026-08-01 10:30（可补录 30 天窗口外的旧记录，不影响锚点）"
                            setTextColor(getColor(R.color.text_secondary))
                            textSize = 11f
                            setPadding(0, dp(6), 0, 0)
                        }
                    )
                    addView(btnAdd)
                    addView(manualBox)
                }
            )
        }
        refreshManualList()

        // ===== 面板3：器者名单（后台预置 + 手动编辑；与抽卡记录解耦，次数按账号统计） =====
        val nameInput = EditText(this).apply {
            hint = "新增器者名"
            setSingleLine()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val btnNameAdd = TextView(this).apply {
            text = "＋ 添加"
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            paint.isFakeBoldText = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(getColor(R.color.accent))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val namesBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val tvNameCount = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            paint.isFakeBoldText = true
        }

        fun saveNameList(list: List<String>) {
            GachaStore.saveNames(ctx, list)
            GachaDictionary.names = list
        }

        fun refreshNameList() {
            namesBox.removeAllViews()
            val counts = HashMap<String, Int>()
            for (r in GachaStore.loadRecords(ctx)) counts[r.name] = (counts[r.name] ?: 0) + 1
            val dict = GachaStore.loadNames(ctx)
            GachaDictionary.names = dict // 打开面板即同步 OCR 纠错字典，adb 直推名单免重启
            // 名单 = 后台预置 + 手动编辑（字典），与抽卡记录解耦；次数 = 当前账号记录计数
            val sorted = dict.sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { it })
            tvNameCount.text = "器者总数：${sorted.size}"
            if (sorted.isEmpty()) {
                namesBox.addView(TextView(this@MainActivity).apply {
                    text = "名单为空：手动添加后显示"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                })
                return
            }
            for (name in sorted) {
                val cnt = counts[name] ?: 0
                val line = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            text = name
                            setTextColor(getColor(R.color.text_primary))
                            textSize = 13f
                            paint.isFakeBoldText = true
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = "  ·$cnt 次"
                            setTextColor(getColor(R.color.text_secondary))
                            textSize = 10f
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "✎"
                        setTextColor(getColor(R.color.text_secondary))
                        textSize = 14f
                        setPadding(dp(8), dp(4), dp(4), dp(4))
                        setOnClickListener {
                            promptGachaAccountName("修改名字（联动记录）", name) { newName ->
                                val trimmed = newName.trim()
                                if (trimmed.isEmpty() || trimmed == name) return@promptGachaAccountName
                                val list = GachaStore.loadNames(ctx).toMutableList()
                                list.remove(name)
                                if (!list.contains(trimmed)) list.add(trimmed)
                                saveNameList(list)
                                val n = GachaStore.renameEverywhere(ctx, name, trimmed)
                                refreshNameList()
                                renderGachaPanel()
                                if (n > 0) toast("已改名，联动更新 $n 条记录")
                            }
                        }
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "✕"
                        setTextColor(getColor(R.color.text_secondary))
                        textSize = 14f
                        setPadding(dp(8), dp(4), dp(4), dp(4))
                        setOnClickListener {
                            val list = GachaStore.loadNames(ctx).toMutableList()
                            list.remove(name)
                            saveNameList(list)
                            refreshNameList()
                            toast("已移出名单（记录不受影响）")
                        }
                    })
                }
                namesBox.addView(line)
            }
        }

        btnNameAdd.setOnClickListener {
            val n = nameInput.text.toString().trim()
            if (n.isEmpty()) { toast("名字不能为空"); return@setOnClickListener }
            val list = GachaStore.loadNames(ctx).toMutableList()
            if (list.contains(n)) { toast("已在名单中"); return@setOnClickListener }
            list.add(n)
            saveNameList(list)
            nameInput.setText("")
            refreshNameList()
            toast("已添加")
        }

        val panelNames = ScrollView(this).apply {
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(12))
                    addView(tvNameCount, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(nameInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(btnNameAdd, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(8) })
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "名单 = 后台预置 + 手动编辑，全部账号共享，不受抽卡记录删减影响；次数为当前账号记录计数。抓取时按名单自动纠正 OCR 错字（距离 ≤2）。✎ 改名会联动当前账号的记录。"
                        setTextColor(getColor(R.color.text_secondary))
                        textSize = 11f
                        setPadding(0, dp(8), 0, 0)
                    })
                    addView(namesBox)
                }
            )
        }
        refreshNameList()

        // ===== 组装：页签 + 三面板切换 =====
        val frame = FrameLayout(this)
        frame.addView(panelUpWrap)
        frame.addView(panelAdd)
        frame.addView(panelNames)
        panelAdd.visibility = View.GONE
        panelNames.visibility = View.GONE

        val panels = listOf(panelUpWrap, panelAdd, panelNames)
        val tabs = listOf(tabUp, tabAdd, tabNames)
        fun switchTab(idx: Int) {
            panels.forEachIndexed { i, p -> p.visibility = if (i == idx) View.VISIBLE else View.GONE }
            tabs.forEachIndexed { i, t -> styleTab(t, i == idx) }
        }
        tabUp.setOnClickListener { switchTab(0) }
        tabAdd.setOnClickListener { switchTab(1) }
        tabNames.setOnClickListener { switchTab(2) }
        switchTab(0)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabRow)
            addView(frame)
        }

        var dlgRef: androidx.appcompat.app.AlertDialog? = null
        btnUpSave.setOnClickListener {
            saveGachaUpMarks(ctx, upEditors)
            toast("已保存UP标注")
            dlgRef?.dismiss()
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("编辑抽卡记录")
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
        dlgRef = dlg
        dlg.show()
    }

    /** 「标注UP器者」保存：收集各小类输入框写回 up_marks.json 并刷新面板 */
    private fun saveGachaUpMarks(
        ctx: android.content.Context,
        editors: List<Triple<String, String, EditText>>
    ) {
        val marks = GachaStore.loadUpMarks(ctx)
        for ((pool, banner, et) in editors) {
            val v = et.text.toString().trim()
            val m = marks.getOrPut(pool) { LinkedHashMap() }
            if (v.isEmpty()) m.remove(banner) else m[banner] = v
        }
        GachaStore.saveUpMarks(ctx, marks)
        renderGachaPanel()
    }

    /** 抓取入口：运行中再点一次 = 停止。与任务队列互斥（两边都注入同一个虚拟屏） */
    private fun stopGachaCrawl() {
        gachaJob?.cancel()
        binding.tvGachaStatus.text = "停止中…"
        if (homeTab == HomeTab.TOOLBOX) binding.btnStartQueue.text = getString(R.string.quick_stop)
    }

    private fun startGachaCrawl() {
        if (gachaRunning) {
            stopGachaCrawl()
            return
        }
        if (isTaskRunning) { toast("任务队列运行中，不能同时抓取"); return }
        if (!ShizukuShell.isVdAlive()) { toast("请先启动虚拟屏，并把游戏打开到「招集记录」页"); return }
        gachaRunning = true
        binding.btnGachaRun.text = getString(R.string.gacha_stop)
        binding.tvGachaStatus.text = "准备…"
        if (homeTab == HomeTab.TOOLBOX) binding.btnStartQueue.text = getString(R.string.quick_stop)
        // 通知/悬浮窗联动：状态行显示识别中，■停止按钮换为停止抓取（结束后恢复默认）
        keepAlive("抽卡记录识别中")
        FloatingPanel.stopCallback = { stopGachaCrawl() }
        FloatingPanel.update("▶ 抽卡记录识别中")
        gachaJob = lifecycleScope.launch {
            var runSummary = ""
            try {
                RunLogStore.begin(applicationContext, "抽卡抓取", -1)
                val ocr = GachaOcrFactory.create(applicationContext) { m -> log(m, LogLevel.INFO) }
                val crawler = GachaCrawler(applicationContext, ocr) { m, lv -> log(m, lv) }
                val report = crawler.crawl { p ->
                    runOnUiThread { binding.tvGachaStatus.text = p }
                    keepAlive("▶ 抽卡识别：$p")
                    FloatingPanel.update("▶ 抽卡识别：$p")
                }
                runSummary = "新增 ${report.added} 条"
                binding.tvGachaStatus.text = "✓ 新增 ${report.added} 条"
            } catch (e: kotlinx.coroutines.CancellationException) {
                runSummary = "已停止"
                binding.tvGachaStatus.text = "◼ 已停止"
                throw e
            } catch (e: Exception) {
                runSummary = "失败：${e.message}"
                log("抽卡抓取失败：${e.message}", LogLevel.ERR)
                binding.tvGachaStatus.text = "✗ ${e.message}"
            } finally {
                RunLogStore.end(runSummary)
                gachaRunning = false
                gachaJob = null
                binding.btnGachaRun.text = getString(R.string.gacha_run)
                if (homeTab == HomeTab.TOOLBOX) {
                    binding.btnStartQueue.text = getString(R.string.btn_start_queue)
                }
                if (ShizukuShell.isVdAlive()) keepAlive(getString(R.string.keepalive_vd)) else stopKeepAlive()
                FloatingPanel.stopCallback = { QueueRunner.requestStopCurrent() }
                FloatingPanel.update(if (runSummary.isEmpty()) "抽卡识别结束" else "抽卡识别：$runSummary")
                renderGachaPanel()
            }
        }
    }

    /** 面板：每池一张卡片（头部统计 + 垫抽 + 特出明细），纯 TextView 无图片资源 */
    private fun renderGachaPanel() {
        val container = binding.llGachaCards
        container.removeAllViews()
        val all = GachaStore.loadRecords(applicationContext)
        val cfg = GachaStore.loadConfig(applicationContext)
        if (!gachaRunning) {
            binding.tvGachaStatus.text =
                if (cfg.lastCrawlMs > 0) "空闲" else getString(R.string.gacha_status_default)
        }
        binding.tvGachaLastCrawl.text = if (cfg.lastCrawlMs > 0)
            "上次抓取：${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(cfg.lastCrawlMs))}"
        else "尚未抓取"
        binding.tvGachaCount.text = "库存 ${all.size} 条"
        binding.tvGachaAccountSub.visibility = View.GONE
        if (all.isEmpty()) {
            container.addView(simpleText("还没有录入过抽卡记录哦", R.color.text_secondary, 12f))
            return
        }
        val pools = try {
            GachaCrawler.Points.load(applicationContext).pools
        } catch (e: Throwable) {
            listOf("限时渠道", "限定渠道", "招集渠道", "征集渠道")
        }
        val upMarks = GachaStore.loadUpMarks(applicationContext)
        container.addView(buildStatRow(pools, all, upMarks))
        for (pool in pools) {
            val rs = all.filter { it.pool == pool }
            if (rs.isNotEmpty()) container.addView(buildPoolCard(pool, rs, upMarks[pool] ?: emptyMap()))
        }
    }

    /** 顶部统计卡行：每池一张（总抽数大数字 + 出卡/歪 + UP平均/六星平均），池多时横滑 */
    private fun buildStatRow(
        pools: List<String>,
        all: List<GachaStore.Record>,
        upMarks: Map<String, MutableMap<String, String>>
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var first = true
        for (pool in pools) {
            val rs = all.filter { it.pool == pool }
            if (rs.isEmpty()) continue
            if (!first) {
                row.addView(View(this).apply {
                    setBackgroundColor(0xFF2E3947.toInt())
                    layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        setMargins(dp(2), dp(10), dp(2), dp(10))
                    }
                })
            }
            row.addView(buildStatCard(pool, rs, upMarks[pool] ?: emptyMap()))
            first = false
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                row,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /** 单池统计卡：标题 / 大数字+抽 / 分隔 / 出卡(歪) + UP平均（或 出卡 + 六星平均） */
    private fun buildStatCard(pool: String, rs: List<GachaStore.Record>, poolUpMarks: Map<String, String>): View {
        val st = GachaStore.upStats(rs, poolUpMarks)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.bg_card))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(8), dp(8), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(106), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(4)
            }
        }
        card.addView(TextView(this).apply {
            text = pool.removeSuffix("渠道")
            setTextColor(getColor(R.color.text_primary))
            textSize = 12f
            paint.isFakeBoldText = true
        })
        // 大数字 + 「抽」
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = st.total.toString()
                setTextColor(getColor(R.color.text_primary))
                textSize = 24f
                paint.isFakeBoldText = true
            })
            addView(TextView(this@MainActivity).apply {
                text = " 抽"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
                setPadding(0, 0, 0, dp(3))
            })
        })
        card.addView(View(this).apply {
            setBackgroundColor(0xFF2E3947.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6); bottomMargin = dp(6)
            }
        })
        // 底部：数字组合「N / M」与标签组合「出卡数 / 歪」各占一个跨列单元格、内容居中——
        // 组合对组合居中（中心重合）；UP平均独立一列。未标注模式为 出卡数 / 六星平均 两列。
        fun buildStatGrid(): android.widget.GridLayout {
            val gl = android.widget.GridLayout(this).apply {
                columnCount = 5
                rowCount = 2
            }
            fun addCell(row: Int, colStart: Int, colSpan: Int, tv: TextView, leftMarginDp: Int = 0) {
                val lp = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(row, android.widget.GridLayout.CENTER),
                    android.widget.GridLayout.spec(colStart, colSpan, android.widget.GridLayout.CENTER)
                ).apply {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    leftMargin = dp(leftMarginDp).toInt()
                }
                gl.addView(tv, lp)
            }
            fun numTv(txt: CharSequence, color: Int, size: Float, bold: Boolean) = TextView(this@MainActivity).apply {
                text = txt
                setTextColor(getColor(color))
                textSize = size
                if (bold) paint.isFakeBoldText = true
            }
            fun labelTv(txt: String) = TextView(this@MainActivity).apply {
                text = txt
                setTextColor(getColor(R.color.text_secondary))
                textSize = 9f
            }
            if (st.marked) {
                // 数字组合：白 N / 灰斜杠 / 红 M（Spannable 分色），组合整体居中于跨列单元格
                val num = SpannableStringBuilder("${st.teCount} / ${st.waiCount}")
                val a = "${st.teCount}".length
                val b = " / ".length
                num.setSpan(
                    android.text.style.ForegroundColorSpan(getColor(R.color.text_primary)),
                    0, a, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                num.setSpan(
                    android.text.style.ForegroundColorSpan(getColor(R.color.text_secondary)),
                    a, a + b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                num.setSpan(
                    android.text.style.ForegroundColorSpan(getColor(R.color.err_red)),
                    a + b, num.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                addCell(0, 0, 3, numTv(num, R.color.text_primary, 15f, true))
                addCell(1, 0, 3, labelTv("出卡数 / 歪"))
                addCell(0, 4, 1, numTv(st.upAvgText, R.color.text_primary, 15f, true), leftMarginDp = 10)
                addCell(1, 4, 1, labelTv("UP平均"), leftMarginDp = 10)
            } else {
                addCell(0, 0, 1, numTv("${st.teCount}", R.color.text_primary, 15f, true))
                addCell(1, 0, 1, labelTv("出卡数"))
                addCell(0, 2, 1, numTv(
                    if (st.teCount == 0) "0"
                    else String.format(Locale.US, "%.1f", st.total.toDouble() / st.teCount),
                    R.color.text_primary, 15f, true
                ), leftMarginDp = 10)
                addCell(1, 2, 1, labelTv("六星平均"), leftMarginDp = 10)
            }
            return gl
        }
        card.addView(buildStatGrid(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(2).toInt()
        })
        return card
    }

    private fun buildPoolCard(pool: String, rs: List<GachaStore.Record>, poolUpMarks: Map<String, String>): View {
        val st = GachaStore.poolStats(rs)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        // 头部：池名 + 总抽数/特出/平均
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cell(pool, R.color.text_primary, 15f, bold = true, weight = 1f))
            addView(cell("总抽数 ${st.total} · 特出 ${st.teCount} · 平均 ${st.avgText}", R.color.text_secondary, 12f))
        })
        // 当前垫抽
        card.addView(cell("当前垫抽：${st.dian} 抽", if (st.dian >= 50) R.color.warn_orange else R.color.text_primary, 13f).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        })
        // 特出明细按小类（招集列）分组成子卡片
        val groups = LinkedHashMap<String, MutableList<GachaStore.Record>>()
        for (r in rs) {
            val b = if (r.banner.isBlank()) "未识别" else r.banner
            groups.getOrPut(b) { mutableListOf() }.add(r)
        }
        val te = GachaStore.teListWithCost(rs)
        for ((banner, list) in groups) {
            val label = banner.substringAfter('/', banner)
            val sub = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF151C29.toInt())
                    cornerRadius = dp(6).toFloat()
                }
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            val upName = poolUpMarks[banner]?.takeIf { it.isNotBlank() }
            sub.addView(cell(
                "『$label』 · ${list.size} 抽" + if (upName != null) " · UP $upName" else "",
                R.color.accent, 13f, bold = true
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            val teInBanner = te.filter { it.first.banner == banner }
            if (teInBanner.isEmpty()) {
                sub.addView(cell("暂无特出", R.color.text_secondary, 12f).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2) }
                })
            } else {
                for ((rec, cost) in teInBanner) {
                    sub.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(3) }
                        addView(cell("特出", R.color.err_red, 12f, bold = true, weight = 0.8f))
                        addView(cell(rec.name, R.color.text_primary, 12f, weight = 1.4f))
                        addView(cell(cost, R.color.warn_orange, 12f, weight = 0.9f))
                        addView(cell(GachaStore.shortTime(rec.ts), R.color.text_secondary, 12f, weight = 1.1f))
                    })
                }
            }
            card.addView(sub)
        }
        return card
    }

    /**
     * 写一行任务日志（对标 maameow：时间 + 级别徽标 + 正文，级别自带颜色）。
     * 可从任意线程调用；渲染合并在 [TaskLogView] 里做。
     */
    /**
     * 公告弹窗（圆底图标标题头 + 滚动正文 + 不再显示勾选 + 确认按钮）。
     * auto=true 为启动时自动弹出：该版本公告若已勾选「不再显示」则跳过。
     */
    private fun showAnnouncement(auto: Boolean = false) {
        val f = File(filesDir, "announcement.txt")
        val content = if (f.isFile) f.readText() else getString(R.string.about_announcement_default)
        val hash = Integer.toHexString(content.hashCode())
        val prefs = getSharedPreferences("maawh_announcement", MODE_PRIVATE)
        if (auto && prefs.getString("dismissedHash", "") == hash) return

        fun divider() = View(this).apply {
            setBackgroundColor(0xFF2E3947.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(12); bottomMargin = dp(4) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(getColor(R.color.bg_card))
            }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "📢"
                gravity = Gravity.CENTER
                textSize = 16f
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x333A7BD5)
                }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })
            addView(TextView(this@MainActivity).apply {
                text = "重要公告"
                setTextColor(getColor(R.color.text_primary))
                textSize = 18f
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(12) }
            })
        })
        root.addView(divider())

        val contentView = TextView(this).apply {
            text = content
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(8))
        }
        val contentScroll = ScrollView(this).apply { addView(contentView) }
        root.addView(
            contentScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val dontShow = CheckBox(this).apply {
            text = "下次公告更新前不再显示"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
        }
        var dlgRef: android.app.Dialog? = null
        root.addView(
            dontShow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        root.addView(TextView(this).apply {
            text = "确认"
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(getColor(R.color.accent))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            isClickable = true
            setOnClickListener {
                prefs.edit()
                    .putString("dismissedHash", if (dontShow.isChecked) hash else "")
                    .apply()
                dlgRef?.dismiss()
            }
        })

        val dlg = android.app.Dialog(this)
        dlgRef = dlg
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setLayout(resources.displayMetrics.widthPixels - dp(28), (resources.displayMetrics.heightPixels * 0.78f).toInt())
        }
        dlg.show()
    }

    /** 主引导：7 步连播（欢迎 → 预览 → 任务队列 → 配置管理 → 新建复制 → 开始 → 完成），
     *  配置管理两步进入时自动切配置管理模式（靶点面板可见），开始步切回队列视图 */
    private fun buildGuideSteps(): List<Onboarding.Step> = listOf(
        Onboarding.Step(0, null, "✨",
            getString(R.string.guide_welcome_title), getString(R.string.guide_welcome_body)),
        Onboarding.Step(0, { binding.imageShot }, "🖥️",
            getString(R.string.guide_preview_title), getString(R.string.guide_preview_body)),
        Onboarding.Step(0, { binding.rvTaskList }, "📋",
            getString(R.string.guide_queue_title), getString(R.string.guide_queue_body)),
        Onboarding.Step(0, { binding.panelConfig }, "⚙️",
            getString(R.string.guide_c_list_title), getString(R.string.guide_c_list_body),
            enter = { setConfigMode(true) }),
        Onboarding.Step(0, { binding.btnNewProfile }, "➕",
            getString(R.string.guide_c_new_title), getString(R.string.guide_c_new_body)),
        Onboarding.Step(0, { binding.panelToolbox }, "🧰",
            getString(R.string.guide_m_toolbox_title), getString(R.string.guide_m_toolbox_body),
            enter = { switchTab(HomeTab.TOOLBOX) }),
        Onboarding.Step(0, { binding.btnStartQueue }, "▶️",
            getString(R.string.guide_start_title), getString(R.string.guide_start_body),
            enter = { switchTab(HomeTab.ONECLICK) }),
        Onboarding.Step(0, null, "🎉",
            getString(R.string.guide_done_title), getString(R.string.guide_done_body)),
    )

    /** 抽卡页首访引导（4 步）：账号 → 抓取 → 编辑 → 数据面板 */
    private fun buildGachaGuideSteps(): List<Onboarding.Step> = listOf(
        Onboarding.Step(1, { binding.tvGachaAccountName }, "👤",
            getString(R.string.guide_g_acc_title), getString(R.string.guide_g_acc_body)),
        Onboarding.Step(1, { binding.btnGachaRun }, "📥",
            getString(R.string.guide_g_run_title), getString(R.string.guide_g_run_body)),
        Onboarding.Step(1, { binding.btnGachaEdit }, "✏️",
            getString(R.string.guide_g_edit_title), getString(R.string.guide_g_edit_body)),
        Onboarding.Step(1, { binding.llGachaCards }, "📊",
            getString(R.string.guide_g_stat_title), getString(R.string.guide_g_stat_body)),
    )

    /** 配置管理首访引导（2 步）：配置列表 → 新建/复制 */
    private fun buildConfigGuideSteps(): List<Onboarding.Step> = listOf(
        Onboarding.Step(0, { binding.panelConfig }, "⚙️",
            getString(R.string.guide_c_list_title), getString(R.string.guide_c_list_body)),
        Onboarding.Step(0, { binding.btnNewProfile }, "➕",
            getString(R.string.guide_c_new_title), getString(R.string.guide_c_new_body)),
    )

    /** 场景引导通用门禁：引导没看过、没在引导中、没有任务/抓取在跑 */
    private fun canShowScenarioGuide(key: String): Boolean =
        !GuideStore.isDone(this, key) && !onboarding.isActive && !isTaskRunning &&
                !gachaRunning && !isFinishing

    /** 重看引导：自定义卡片三选（与账号管理弹窗同风格），配置管理重看时自动切入配置模式 */
    private fun showGuideReplayDialog() {
        var dlgRef: androidx.appcompat.app.AlertDialog? = null

        fun menuRow(icon: String, title: String, desc: String, onClick: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { dlgRef?.dismiss(); onClick() }
                addView(TextView(this@MainActivity).apply {
                    text = icon
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    paint.isFakeBoldText = true
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0x264C9AFF)
                    }
                }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        setTextColor(getColor(R.color.text_primary))
                        textSize = 14f
                        paint.isFakeBoldText = true
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = desc
                        setTextColor(getColor(R.color.text_secondary))
                        textSize = 11f
                        setPadding(0, dp(1), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = "查看新手引导"
                setTextColor(getColor(R.color.text_primary))
                textSize = 16f
                paint.isFakeBoldText = true
            })
            addView(TextView(this@MainActivity).apply {
                text = "选一套开始播放"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
                setPadding(0, dp(2), 0, 0)
            })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(getColor(R.color.bg_card))
            }
            addView(head)
            addView(View(this@MainActivity).apply {
                setBackgroundColor(0xFF2E3947.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                )
            })
            addView(menuRow("⌂", "主界面", "7 步 · 预览 / 队列 / 配置管理 / 开始") {
                onboarding.start(
                    buildGuideSteps(),
                    listOf(GuideStore.KEY_MAIN, GuideStore.KEY_CONFIG),
                )
            })
            addView(menuRow("✦", "抽卡页", "4 步 · 账号 / 抓取 / 编辑 / 数据面板") {
                onboarding.start(buildGachaGuideSteps(), listOf(GuideStore.KEY_GACHA), homeFirst = false)
            })
            addView(menuRow("⚙", "配置管理", "2 步 · 多套配置 / 新建与复制") {
                setConfigMode(true)
                guideShowPage(0)
                onboarding.start(buildConfigGuideSteps(), listOf(GuideStore.KEY_CONFIG), homeFirst = false)
            })
        }

        val dlg = AlertDialog.Builder(this).create()
        dlg.setView(root)
        dlgRef = dlg
        dlg.show()
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** 引导跨页时的页面切换（与底部导航选中项联动；页序 = menu 顺序：主页/抽卡/日志/设置） */
    private fun guideShowPage(page: Int) {
        when (page) {
            1 -> switchTo(binding.panelGacha)
            2 -> switchTo(binding.panelLog)
            3 -> switchTo(binding.panelSettings)
            else -> switchTo(binding.panelHome)
        }
        binding.bottomNav.menu.getItem(page).isChecked = true
    }

    /** 引导开始前把主页归位：队列视图 + 一键长草（配置管理模式/额外队列 tab 下靶点面板不同） */
    private fun guideEnsureHome() {
        setConfigMode(false)
        switchTab(HomeTab.ONECLICK)
        guideShowPage(0)
    }

    private fun switchTo(panel: View) {
        listOf(binding.panelHome, binding.panelLog, binding.panelSettings, binding.panelGacha)
            .forEach { it.visibility = if (it === panel) View.VISIBLE else View.GONE }
        // 日志页打开即定位到最新一条（日志追加在底部）
        if (panel === binding.panelLog) {
            binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ==================================================================
    // 截图/预览
    // ==================================================================

    /** 抓一帧物理屏刷新预览（快捷选项里的「截图」；虚拟屏运行时用它的实时帧） */

    private fun takeScreenshot() {
        if (!shizukuReady()) { log("无法截图：Shizuku 未就绪"); refreshStatus(); return }
        lifecycleScope.launch {
            try {
                val bmp = ShizukuShell.screencap()
                lastBitmap = bmp
                binding.imageShot.setImageBitmap(bmp)
                log("预览刷新 ${bmp.width}x${bmp.height}")
            } catch (e: Exception) {
                log("截图失败：${e.message}")
            }
        }
    }

    private fun handleImageTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                gestureActive = false
            }
            MotionEvent.ACTION_MOVE -> {
                // 虚拟屏实时手势：手指移动时逐点注入 MOVE（>20px 视为滑动开始）
                if (!vdOn) return true
                val bmp = lastBitmap ?: return true
                val dist = Math.hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble())
                if (!gestureActive) {
                    if (dist > 10) {
                        val from = mapToDevice(downX, downY, bmp) ?: return true
                        val fx = from.x.toInt() * 2; val fy = from.y.toInt() * 2
                        gestureActive = true
                        ShizukuShell.touchDown(fx, fy)
                        log("虚拟屏手势开始 ($fx,$fy)")
                    }
                } else {
                    val p = mapToDevice(ev.x, ev.y, bmp) ?: return true
                    ShizukuShell.touchMove(p.x.toInt() * 2, p.y.toInt() * 2)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val bmp = lastBitmap ?: return true
                if (gestureActive) {
                    val to = mapToDevice(ev.x, ev.y, bmp) ?: return true
                    val tx = to.x.toInt() * 2; val ty = to.y.toInt() * 2
                    ShizukuShell.touchUp(tx, ty)
                    gestureActive = false
                    log("虚拟屏手势结束 ($tx,$ty)")
                    return true
                }
                val dist = Math.hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble())
                if (dist > 20) {
                    // 非虚拟屏：抬起时合成一次滑动
                    val from = mapToDevice(downX, downY, bmp) ?: return true
                    val to = mapToDevice(ev.x, ev.y, bmp) ?: return true
                    log("模拟滑动 (${from.x.toInt()},${from.y.toInt()})->(${to.x.toInt()},${to.y.toInt()})")
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            ShizukuShell.swipe(from.x.toInt(), from.y.toInt(), to.x.toInt(), to.y.toInt())
                        }
                    }
                    return true
                }
                val dev = mapToDevice(ev.x, ev.y, bmp) ?: return true
                val x = dev.x.toInt(); val y = dev.y.toInt()
                if (vdOn) {
                    val nx = x * 2; val ny = y * 2
                    log("虚拟屏点击 ($nx, $ny)")
                    lifecycleScope.launch(Dispatchers.IO) {
                        val r = ShizukuShell.injectTapVD(nx, ny)
                        runOnUiThread { log("注入: $r") }
                    }
                    return true
                }
                if (binding.switchTap.isChecked) {
                    log("模拟点击 ($x, $y)")
                    lifecycleScope.launch { runCatching { ShizukuShell.tap(x, y) } }
                } else log("该点坐标 ($x, $y)")
            }
        }
        return true
    }

    private fun mapToDevice(vx: Float, vy: Float, bmp: Bitmap): PointF? {
        val vw = binding.imageShot.width.toFloat()
        val vh = binding.imageShot.height.toFloat()
        if (vw <= 0 || vh <= 0) return null
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val offX = (vw - dw) / 2f
        val offY = (vh - dh) / 2f
        if (vx < offX || vy < offY || vx > offX + dw || vy > offY + dh) return null
        return PointF((vx - offX) / scale, (vy - offY) / scale)
    }

    // ==================================================================
    // 工具
    // ==================================================================


    private fun updateInfoTexts() {
        binding.tvAboutVersion.text = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "0.1.0"
        binding.tvPaths.text = "内部: ${File(filesDir, "taskpacks")}\n外部: ${getExternalFilesDir(null)}/taskpacks"
    }

    /**
     * 写一行任务日志（对标 maameow：时间 + 级别徽标 + 正文，级别自带颜色）。
     * 可从任意线程调用；渲染合并在 [TaskLogView] 里做。
     */


    private fun log(msg: String, level: LogLevel = LogLevel.INFO) {
        RunLogStore.append(level.name, msg)   // 会话日志落盘（历史日志页浏览）
        android.util.Log.i("MaaWH", "${level.name} $msg")
        val time = synchronized(timeFmt) { timeFmt.format(Date()) }
        vdHandler.post { logView?.add(time, level, msg) }
    }

    /** 引擎逐节点日志的展示回调已并入 QueueRunner（统一走 TRACE） */

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun getColorCompat(res: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, res)

    private fun setRunState(text: String, colorRes: Int) {
        binding.tvRunState.text = text
        binding.tvRunState.setTextColor(getColorCompat(colorRes))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun simpleText(text: String, colorRes: Int, sizeSp: Float): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(getColor(colorRes))
            textSize = sizeSp
        }

    private fun cell(
        text: String,
        colorRes: Int,
        sizeSp: Float,
        bold: Boolean = false,
        weight: Float = 0f
    ): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(colorRes))
        textSize = sizeSp
        paint.isFakeBoldText = bold
        layoutParams = if (weight > 0f) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        } else {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val REQ_NOTIF = 1002
        private const val SHIZUKU_GUIDE_URL = "https://shizuku.rikka.app/zh-hans/"
        private const val CASE_NOT_INSTALLED = 1
        private const val CASE_NOT_RUNNING = 2
        private const val CASE_NOT_GRANTED = 3
        /** 全新安装时的第一个配置名（对标 maameow 的「日常」） */
        private const val DEFAULT_PROFILE = "日常"
        /** 任务归属两处：一键长草主队列 / 额外队列（见 homeOf） */
        private const val HOME_MAIN = "main"
        private const val HOME_TOOLS = "tools"
        private const val MATCH_PARENT = -1
    }
}
