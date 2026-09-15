package com.maawh.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
import android.widget.ImageView
import android.widget.LinearLayout
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

    // 小工具队列：adb 入口触发的测试任务，独立于一键长草主队列（不互相清空）
    private val toolsTasks = mutableListOf<TaskItem>()
    private val toolsEnabled = mutableListOf<Boolean>()
    private lateinit var toolsAdapter: TaskQueueAdapter

    /** 当前任务包清单（interface.json）；加载失败为 null，此时队列为空并提示 */
    private var manifest: TaskPack.Manifest? = null

    private var running = false
    @Volatile
    private var muteEnabled = false
    @Volatile
    private var closeAfterEnabled = false
    private var vdOn = false
    @Volatile
    private var stopRequested = false
    private val vdHandler = Handler(Looper.getMainLooper())
    private lateinit var vdOverlay: FrameLayout
    private lateinit var vdFullImg: ImageView

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
            }
        }

        Shizuku.addBinderReceivedListenerSticky { runOnUiThread { refreshStatus(); maybePromptShizuku() } }

        binding.btnRequest.setOnClickListener { requestShizukuPermission() }
        binding.btnRefresh.setOnClickListener { refreshStatus() }
        binding.imageShot.setOnTouchListener { _, ev -> handleImageTouch(ev) }
        binding.btnStartQueue.setOnClickListener { startQueue() }
        binding.btnQuick.setOnClickListener { showQuickMenu() }
        // tab 切换：一键长草 / 小工具
        binding.btnTabOneClick.setOnClickListener { switchTab(HomeTab.ONECLICK) }
        binding.btnTabTools.setOnClickListener { switchTab(HomeTab.TOOLS) }
        // 「编辑配置」：一键长草里切到配置管理（对标 maameow 的编辑配置/完成）
        binding.btnEditConfig.setOnClickListener { setConfigMode(!configMode) }
        binding.btnNewProfile.setOnClickListener { createProfile() }
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
        vdHandler.postDelayed({ maybePromptShizuku() }, 1500)

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
     * group 含 "tools" 的任务进「小工具」tab（如查找器者），其余进「一键长草」主队列。
     * 清单缺失时回退到最小可用清单（仅「启动」），避免界面空白无法操作。
     */
    private fun loadManifestIntoQueue() {
        val dir = resolveBundleDir()
        val m = if (dir != null) TaskPack.load(dir) else null
        manifest = m
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
                val item = TaskItem(
                    pack = m.name,
                    name = t.name,
                    entry = t.entry,
                    label = t.label,
                    options = t.options,
                    selection = TaskPack.Selection().also { sel ->
                        // 用 option 默认值初始化选择
                        t.options.forEach { key ->
                            val o = m.option(key) ?: return@forEach
                            when (o.type) {
                                "input" -> o.inputs.forEach { sel.inputOf[it.name] = it.default }
                                else -> sel.caseOf[key] = o.defaultCase
                            }
                        }
                    }
                ).also { it.summary = summarize(it) }
                // 小工具任务（group 含 tools）：只进工具队列，不进一键长草
                if (t.group.contains("tools")) addToolTask(item) else addTask(item)
            }
            val toolCount = toolsTasks.size
            if (toolCount > 0) log("小工具任务 $toolCount 个（在「小工具」tab）")
        }
        // 载入存档：配置列表 + 各配置内容（下面 restore 要用），并决定当前生效的配置
        val saved = QueueStore.load(this)
        val usable = saved != null && (m == null || saved.pack == m.name)
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
        closeAfterEnabled = saved.closeAfter
        switchTab(if (saved.tab == "tools") HomeTab.TOOLS else HomeTab.ONECLICK)
        // 旧存档里的 tab="config"（配置曾是与两个 tab 平级的分区）→ 落到一键长草的队列视图。
        // 这里**不能**再重置配置模式：清单加载比窗口可点慢，重置会把用户启动瞬间点的「编辑配置」撤销掉
        log("已载入配置「$activeProfile」（队列 ${tasks.size} 项 · 小工具 ${toolsTasks.size} 项）")
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

    /** 小工具队列：清单声明的工具任务取原对象（带 option），adb 直达条目按存档的 entry 重建 */
    private fun restoreToolsQueue(saved: List<QueueStore.SavedTask>) {
        val newTasks = ArrayList<TaskItem>()
        val newEnabled = ArrayList<Boolean>()
        val seen = HashSet<String>()
        for (s in saved) {
            if (!seen.add(s.name)) continue
            val idx = toolsTasks.indexOfFirst { it.name == s.name }
            val item = if (idx >= 0) toolsTasks[idx]
                       else TaskItem(manifest?.name ?: "whmx", s.name, s.entry)
            if (idx >= 0) applySavedSelection(item, s)
            item.summary = summarize(item)
            newTasks.add(item)
            newEnabled.add(s.enabled)
        }
        toolsTasks.forEachIndexed { i, item ->
            if (seen.contains(item.name)) return@forEachIndexed
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
        val def = m.tasks.firstOrNull { it.name == item.name } ?: return
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
                tab = if (homeTab == HomeTab.TOOLS) "tools" else "oneclick",
                mute = muteEnabled,
                closeAfter = closeAfterEnabled
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
        // 工具入口：只进小工具队列并切到小工具 tab，绝不改动一键长草主队列
        val toolItem = TaskItem(pack, entry, entry)
        addToolTask(toolItem)
        switchTab(HomeTab.TOOLS)
        log("工具入口: [$entry] 已加入小工具队列")
        if (vdFirst) {
            log("vd=1：先建虚拟屏并投游戏，再跑 [$entry]")
            vdOn = true
            lifecycleScope.launch(Dispatchers.IO) {
                val r = ShizukuShell.startVirtualGame()
                runOnUiThread { log(r) }
                runOnUiThread {
                    VdStreamer.start()
                    vdHandler.removeCallbacks(vdUiTick)
                    vdHandler.post(vdUiTick)
                    setRunState("游戏已进虚拟屏，即将执行任务", R.color.ok_green)
                }
            }
        }
        // 只跑本次入口这一个任务，不重跑小工具队列里的历史条目
        queuedStart?.let { binding.btnStartQueue.removeCallbacks(it) }
        queuedStart = Runnable {
            when {
                running -> {}
                !shizukuRunning() -> {
                    log("✗ Shizuku 未运行，任务未执行：请启动 Shizuku 后重新触发")
                    refreshStatus()
                }
                !shizukuReady() -> {
                    // 卸载重装后授权会失效：给出明确提示并主动发起授权请求
                    log("✗ Shizuku 未授权，任务未执行：请在弹出的授权框中允许，再重新触发任务")
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
        // 虚拟屏是服务端权威状态(app 重启/切后台会丢内存标志)，回前台自动同步 UI
        syncVdUi()
    }

    override fun onPause() {
        super.onPause()
        // 切后台可能被 LMKD 直接回收，编辑状态在这里立即落盘，不等 400ms 防抖
        saveNow()
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
                    if (!running) stopKeepAlive()
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

        // 小工具队列：同一适配器，仅绑到小工具 tab 的列表
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
    // 小工具队列（adb 入口触发的测试任务）
    // ==================================================================

    /** 加入小工具队列（同入口去重后移到最新位，保留最近 12 条，超出丢弃最旧） */
    private fun addToolTask(item: TaskItem) {
        val dup = toolsTasks.indexOfFirst { it.pack == item.pack && it.entry == item.entry }
        if (dup >= 0) {
            toolsTasks.removeAt(dup)
            toolsEnabled.removeAt(dup)
        }
        toolsTasks.add(item)
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
        binding.tvToolsEditTitle.text = "编辑: ${item.label}"
        binding.tvToolsEditHint.text = "该入口为 adb 直达测试任务，无额外参数"
        binding.layoutOptionsTools.removeAllViews()
        log("选中工具任务: ${item.label}")
    }

    // ==================================================================
    // 参数编辑（按清单 option 动态渲染）
    // ==================================================================

    private var editingIndex = -1

    /** 待落盘的上次编辑（scheduleSave 的延迟任务） */
    private var savePending: Runnable? = null

    /** 主页里的两个分区 */
    private enum class HomeTab { ONECLICK, TOOLS }

    private var homeTab = HomeTab.ONECLICK

    /** 一键长草里的「配置管理」子视图是否展开（对标 maameow 的编辑配置/完成） */
    private var configMode = false

    /** 一键长草 / 小工具 分区切换 */
    private fun switchTab(tab: HomeTab) {
        homeTab = tab
        binding.panelOneClick.visibility = if (tab == HomeTab.ONECLICK) View.VISIBLE else View.GONE
        binding.panelTools.visibility = if (tab == HomeTab.TOOLS) View.VISIBLE else View.GONE
        binding.btnTabOneClick.isChecked = tab == HomeTab.ONECLICK
        binding.btnTabTools.isChecked = tab == HomeTab.TOOLS
        scheduleSave()
    }

    /** 一键长草：队列视图 ⇄ 配置管理 */
    private fun setConfigMode(on: Boolean) {
        configMode = on
        binding.panelQueue.visibility = if (on) View.GONE else View.VISIBLE
        binding.panelConfig.visibility = if (on) View.VISIBLE else View.GONE
        binding.tvQueueHint.text = if (on) "当前生效：$activeProfile" else getString(R.string.hint_queue)
        binding.btnEditConfig.text = getString(if (on) R.string.config_done else R.string.config_edit)
        if (on) refreshProfilePanel()
    }

    /** 刷新某任务的列表摘要显示 */
    private fun refreshRow(item: TaskItem) {
        val idx = tasks.indexOf(item)
        if (idx >= 0) adapter.notifyItemChanged(idx)
    }

    /** 单击任务行：右侧按 interface.json 的 option 动态渲染编辑面板 */
    private fun selectTask(i: Int) {
        if (i < 0 || i >= tasks.size) return
        editingIndex = i
        val item = tasks[i]
        binding.tvEditTitle.text = "编辑: ${item.label}"
        val m = manifest
        val container = binding.layoutOptionsMain
        container.removeAllViews()

        val def = m?.tasks?.firstOrNull { it.name == item.name }
        if (m == null || def == null || def.options.isEmpty()) {
            binding.tvEditHint.text = "该任务无可配置参数"
            return
        }
        binding.tvEditHint.text = "参数来自任务包清单 interface.json"

        for (key in def.options) {
            val o = m.option(key) ?: continue
            when (o.type) {
                "input" -> renderInputOption(container, item, o)
                "switch" -> renderSwitchOption(container, item, o)
                else -> renderSelectOption(container, item, o)
            }
        }
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
                text = "${o.label} · ${inp.label}"
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

    // ==================================================================
    // 运行
    // ==================================================================

    private fun startQueue() {
        // 运行中再点本按钮 = 停止任务（按钮文字已切换为「停止任务」）
        if (running) {
            stopNow()
            return
        }
        // 按当前 tab 决定跑哪个队列：小工具 tab 跑工具队列，其余（含配置 tab）跑生效配置的主队列
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
            log("无法运行：Shizuku ${if (!shizukuRunning()) "未运行" else "未授权"}")
            refreshStatus()
            return
        }
        log("运行配置「$activeProfile」：${plan.joinToString(" → ") { it.label }}")
        runQueue(plan)
    }

    private fun runQueue(planTasks: List<TaskItem>) {
        running = true
        stopRequested = false
        MaaBridge.clearStop()
        binding.btnStartQueue.text = getString(R.string.quick_stop)
        setRunState("任务运行中…", R.color.accent)

        // Android 16 FUSE 下，adb 以 shell 身份推入外部目录的文件 App 自身无权读取，
        // MaaFramework 原生层 stat 失败会抛未捕获异常直接 abort 整个进程；
        // 故优先用内部 taskpacks（adb 可经 run-as 铺内），不存在时回退外部目录
        val whmxDir = resolveBundleDir()
        if (whmxDir == null) {
            log("任务包缺失：files/taskpacks/whmx")
            toast("未找到任务包 whmx，详见日志")
            running = false
            binding.btnStartQueue.text = getString(R.string.btn_start_queue)
            return
        }
        val logDir = File(filesDir, "maa_logs")
        val prevVolume = if (muteEnabled) getMusicVolume() else -1

        // 保活：任务期间挂前台服务（通知权限顺手要一下，通知可见性影响系统对待方式）
        requestNotifPermission()
        keepAlive(getString(R.string.keepalive_running))

        lifecycleScope.launch(Dispatchers.IO) {
            // M4-④a：以服务端权威状态同步虚拟屏路由(截图/点击目标屏)
            ShizukuShell.syncVdMode()
            if (muteEnabled) setMusicVolume(0)
            var ok = true
            for (item in planTasks) {
                if (stopRequested) {
                    runOnUiThread { log("任务已停止：剩余队列不再执行") }
                    break
                }
                runOnUiThread {
                    setRunState("执行中: ${item.label}", R.color.accent)
                    keepAlive("任务运行中：${item.label}")
                }
                log("==== 任务 ${item.label} 开始 ====")
                val r = try {
                    if (item.entry == "启动" || item.name == "启动") {
                        // 「启动」= 先进虚拟屏把游戏投进去，再用引擎收口到主页
                        runOnUiThread {
                            vdOn = true
                        }
                        val vd = ShizukuShell.startVirtualGame()
                        runOnUiThread { log(vd) }
                        runOnUiThread {
                            VdStreamer.start()
                            vdHandler.removeCallbacks(vdUiTick)
                            vdHandler.post(vdUiTick)
                            setRunState("游戏已进虚拟屏，收口中…", R.color.accent)
                        }
                        if (ShizukuShell.syncVdMode()) {
                            val rr = MaaBridge.runTask(whmxDir, item.entry, logDir, "{}") { msg ->
                                runOnUiThread { log(msg) }
                            }
                            runOnUiThread {
                                setRunState(
                                    if (rr) "游戏已进入主页(虚拟屏)" else "✗ 到主页失败",
                                    if (rr) R.color.ok_green else R.color.err_red
                                )
                            }
                            rr
                        } else {
                            runOnUiThread { log("虚拟屏未就绪，跳过收口") }
                            true
                        }
                    } else if (item.entry == "关闭游戏" || item.name == "关闭游戏") {
                        // 关闭游戏：强杀游戏进程（放在一键长草末尾，跑完即退出游戏）
                        runOnUiThread { log("关闭游戏：force-stop ${GAME_PKG}") }
                        runCatching { ShizukuShell.execBlocking("am", "force-stop", GAME_PKG) }
                        true
                    } else {
                        // 其余任务：按清单 option 生成 pipeline_override 后交给引擎
                        val override = manifest?.let { m ->
                            m.tasks.firstOrNull { it.name == item.name }
                                ?.let { TaskPack.buildOverride(m, it, item.selection) }
                        } ?: "{}"
                        log("pipeline_override: $override")
                        MaaBridge.runTask(whmxDir, item.entry, logDir, override) { msg ->
                            runOnUiThread { log(msg) }
                        }
                    }
                } catch (e: Throwable) {
                    runOnUiThread { log("任务异常: $e") }
                    false
                }
                runOnUiThread { log(
                    when {
                        stopRequested -> "已停止任务 ${item.label}"
                        r -> "✓ ${item.label} 完成"
                        else -> "✗ ${item.label} 失败"
                    }
                ) }
                if (!r) {
                    ok = false
                    break
                }
            }
            // 任务结束：勾选「游戏启动后关闭游戏声音」时，
            // 若游戏仍在运行（虚拟屏未退出）则保持静音效果，不主动恢复声音；
            // 仅当游戏已退出（如本队列末尾勾选了关闭游戏）才恢复音量。
            // 这里的调用必须兜异常：Shizuku 若在任务期间被系统回收，execBlocking 会抛异常，
            // 而本协程没有外层 catch —— 未捕获异常会直接把 App 打崩（虚拟屏也随之没）。
            if (closeAfterEnabled) {
                runCatching { ShizukuShell.execBlocking("am", "force-stop", GAME_PKG) }
            }
            if (muteEnabled && prevVolume > 0) {
                val gameAlive = try {
                    ShizukuShell.execBlocking("pidof", GAME_PKG)
                        .toString(Charsets.UTF_8).trim().isNotEmpty()
                } catch (e: Throwable) {
                    false
                }
                if (!gameAlive) setMusicVolume(prevVolume)
            }
            // 收尾体检：Shizuku 掉了要说清"不是 MaaWH 关的"并给出保活办法，
            // 否则用户只会看到下次打开时必须重新启用 Shizuku
            if (!shizukuRunning()) {
                runOnUiThread {
                    log("⚠ Shizuku 已离线（MaaWH 全程只对游戏包名下命令，不会关闭 Shizuku；" +
                        "通常是 ColorOS 回收了后台进程）。请到【快捷选项 → 防后台被杀设置】" +
                        "把 MaaWH 与 Shizuku 加入电池优化白名单，并在 Shizuku 里开启 Watchdog。")
                }
            }

            runOnUiThread {
                running = false
                binding.btnStartQueue.isEnabled = true
                binding.btnStartQueue.text = getString(R.string.btn_start_queue)
                // 队列收工：虚拟屏还活着就继续保活（游戏还在屏上，App 一死屏就没了），否则停服务
                if (vdOn) keepAlive(getString(R.string.keepalive_vd)) else stopKeepAlive()
                when {
                    stopRequested -> {
                        setRunState("✓ 任务已停止", R.color.ok_green)
                        toast("任务已停止")
                    }
                    ok -> {
                        setRunState("✓ 全部任务完成", R.color.ok_green)
                        toast("全部任务完成")
                    }
                    else -> {
                        setRunState("✗ 有任务失败，见日志", R.color.err_red)
                        toast("任务失败")
                    }
                }
            }
        }
    }

    private fun showQuickMenu() {
        val popup = androidx.appcompat.widget.PopupMenu(this, binding.btnQuick)
        popup.menu.add(0, 1, 0, getString(R.string.quick_mute))
        popup.menu.add(0, 4, 0, getString(R.string.quick_autofmute)).apply {
            isCheckable = true
            isChecked = muteEnabled
        }
        popup.menu.add(0, 2, 0, getString(R.string.quick_close)).apply {
            isCheckable = true
            isChecked = closeAfterEnabled
        }
        popup.menu.add(0, 3, 0, getString(R.string.quick_closegame))
        // 虚拟屏相关：原顶部三个按钮收进这里（预览占满上方，操作按需展开）
        popup.menu.add(0, 6, 0, getString(if (vdOn) R.string.vd_stop else R.string.vd_run)).apply {
            isCheckable = true
            isChecked = vdOn
        }
        popup.menu.add(0, 7, 0, getString(R.string.vd_full))
        popup.menu.add(0, 8, 0, getString(R.string.quick_shot))
        popup.menu.add(0, 9, 0, getString(R.string.keepalive_title))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleMute()
                2 -> {
                    closeAfterEnabled = !closeAfterEnabled
                    scheduleSave()
                }
                3 -> {
                    toast("正在关闭游戏…")
                    log("快捷操作：关闭游戏")
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            ShizukuShell.execBlocking("am", "force-stop", GAME_PKG)
                        } catch (e: Throwable) {
                            log("关闭游戏失败: ${e.message}")
                        }
                    }
                }
                4 -> {
                    muteEnabled = !muteEnabled
                    scheduleSave()
                }
                6 -> toggleVd()
                7 -> {
                    if (!vdOn) toast("请先在快捷选项里启动虚拟屏")
                    else startActivity(android.content.Intent(this, VdFullscreenActivity::class.java))
                }
                8 -> takeScreenshot()
                9 -> showKeepAliveDialog()
            }
            true
        }
        popup.show()
    }

    private var savedMuteVolume = -1

    /** 立即静音/恢复游戏声音（点击切换，立即生效；音量操作放 IO 线程防 UI 卡顿） */
    private fun toggleMute() {
        lifecycleScope.launch(Dispatchers.IO) {
            muteEnabled = !muteEnabled
            if (muteEnabled) {
                savedMuteVolume = getMusicVolume()
                setMusicVolume(0)
                runOnUiThread { log("已静音游戏声音"); toast("游戏声音已关闭") }
            } else {
                if (savedMuteVolume >= 0) setMusicVolume(savedMuteVolume)
                runOnUiThread { log("已恢复游戏声音"); toast("游戏声音已恢复") }
            }
        }
    }

    // ==================================================================
    // 保活（前台服务）：任务运行中 / 虚拟屏存活期间常驻，避免被系统当缓存进程回收
    // ==================================================================

    private fun keepAlive(text: String) = KeepAliveService.start(this, text)

    private fun stopKeepAlive() = KeepAliveService.stop(this)

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
        val sh = ignoringBattery(SHIZUKU_PKG)
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
        val sh = ignoringBattery(SHIZUKU_PKG)
        // 说明 + 可点条目都放进自绘视图：AlertDialog 的 setMessage 与 setItems 同时用会只显示其一
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        box.addView(TextView(this).apply {
            text = "MaaWH 不会关闭 Shizuku（所有命令只针对游戏包名）；Shizuku 掉线是系统回收后台进程所致" +
                "（本机 ColorOS 实测有 o-stop / nirvana 清理记录）。\n\n" +
                "当前：MaaWH 电池优化 = ${if (self) "已关闭 ✓" else "未关闭"}；" +
                "Shizuku 电池优化 = ${if (sh) "已关闭 ✓" else "未关闭"}"
            textSize = 13f
            setTextColor(getColorCompat(R.color.text_primary))
            setPadding(0, dp(8), 0, dp(10))
        })
        var dlg: AlertDialog? = null
        val actions = listOf<Pair<String, () -> Unit>>(
            "① 让 MaaWH 不受电池优化限制" to { requestIgnoreBattery(packageName) },
            "② 让 Shizuku 不受电池优化限制" to { requestIgnoreBattery(SHIZUKU_PKG) },
            "③ ColorOS 手动设置清单（自启动 / 应用速冻 / 锁定后台 / Watchdog）" to { showColorOsChecklist() },
            "④ 打开 Shizuku 应用详情" to { openAppDetails(SHIZUKU_PKG) }
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
    private fun showColorOsChecklist() {
        val text = buildString {
            append("ColorOS / realme 需手动开的项（缺一项都可能被杀）\n\n")
            append("1. 设置 → 应用管理 → 自启动管理：MaaWH 与 Shizuku 都打开「自启动」「关联启动」\n")
            append("2. 设置 → 电池 → 应用速冻：确认两者未被勾选\n")
            append("3. 设置 → 电池 → 耗电保护：两者的「允许后台行为」打开\n")
            append("4. 多任务界面长按卡片 → 点锁图标锁定后台\n")
            append("5. 打开 Shizuku → 设置 → 开启 Watchdog（服务被杀后自动重启）\n")
            append("6. 给 MaaWH 通知权限（前台服务保活通知需要它）\n\n")
            append("做完这些，跑完任务后就不容易连 Shizuku 一起掉。")
        }
        AlertDialog.Builder(this)
            .setTitle("ColorOS 保活清单")
            .setMessage(text)
            .setPositiveButton("打开 Shizuku 应用详情") { _, _ -> openAppDetails(SHIZUKU_PKG) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 停止任务：中断当前引擎任务，剩余队列不再执行 */
    private fun stopNow() {
        if (!running) {
            toast("当前没有运行中的任务")
            log("无运行中任务，无需停止")
            return
        }
        stopRequested = true
        MaaBridge.requestStop()
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
        if (running) { toast("任务运行中，请先停止"); return }
        vdOn = !vdOn
        if (vdOn) {
            setRunState("启动虚拟屏…", R.color.accent)
            log("启动虚拟屏…")
            requestNotifPermission()
            keepAlive(getString(R.string.keepalive_vd))
            lifecycleScope.launch(Dispatchers.IO) {
                val r = ShizukuShell.startVirtualGame()
                runCatching { File(filesDir, "m4result.txt").writeText(r) }
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
            lifecycleScope.launch(Dispatchers.IO) { ShizukuShell.stopVirtual() }
            hideFullscreen()
            // 虚拟屏没了就不用再占着前台服务（任务在跑的话保留，由 runQueue 收尾时停）
            if (!running) stopKeepAlive()
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
        packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
    } catch (e: Throwable) {
        false
    }

    /**
     * 进入 App 时检测 Shizuku：未安装 / 服务未运行 / 未授权 → 弹窗引导。
     * 点击弹窗按钮可直达 Shizuku（官网 / 打开 Shizuku app / 申请授权）。
     */
    private fun maybePromptShizuku() {
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
        val i = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
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
            }
            true
        }
    }

    private fun switchTo(panel: View) {
        listOf(binding.panelHome, binding.panelLog, binding.panelSettings)
            .forEach { it.visibility = if (it === panel) View.VISIBLE else View.GONE }
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
        binding.tvAbout.text = getString(R.string.about_text)
        binding.tvPaths.text = "内部: ${File(filesDir, "taskpacks")}\n外部: ${getExternalFilesDir(null)}/taskpacks"
    }

    private fun log(msg: String) {
        android.util.Log.i("MaaWH", msg)
        val line = "${timeFmt.format(Date())} $msg"
        val text = binding.tvLog.text.toString()
        binding.tvLog.text = if (text.length > 8000) text.takeLast(6000) + "\n" + line
        else text + (if (text.isEmpty()) "" else "\n") + line
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

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

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val REQ_NOTIF = 1002
        /** Shizuku 管理器包名（引导用户去启动服务用） */
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private const val SHIZUKU_GUIDE_URL = "https://shizuku.rikka.app/zh-hans/"
        private const val CASE_NOT_INSTALLED = 1
        private const val CASE_NOT_RUNNING = 2
        private const val CASE_NOT_GRANTED = 3
        /** 《物华弥新》游戏包名 */
        private const val GAME_PKG = "com.cipaishe.wuhua.bilibili"
        /** 全新安装时的第一个配置名（对标 maameow 的「日常」） */
        private const val DEFAULT_PROFILE = "日常"
        private const val MATCH_PARENT = -1
    }
}
