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
            }
        }

        Shizuku.addBinderReceivedListenerSticky { runOnUiThread { refreshStatus(); maybePromptShizuku() } }

        binding.btnRequest.setOnClickListener { requestShizukuPermission() }
        binding.btnRefresh.setOnClickListener { refreshStatus() }
        binding.btnScreenshot.setOnClickListener { takeScreenshot() }
        binding.imageShot.setOnTouchListener { _, ev -> handleImageTouch(ev) }
        binding.btnStartQueue.setOnClickListener { startQueue() }
        binding.btnQuick.setOnClickListener { showQuickMenu() }
        binding.btnVdRun.setOnClickListener { toggleVd() }
        binding.btnVdFull.setOnClickListener {
            if (!vdOn) toast("请先启动虚拟屏")
            else startActivity(android.content.Intent(this, VdFullscreenActivity::class.java))
        }
        // tab 切换：一键长草 / 小工具
        binding.btnTabOneClick.setOnClickListener { switchTab(oneClick = true) }
        binding.btnTabTools.setOnClickListener { switchTab(oneClick = false) }
        buildVdOverlay()

        setupQueue()
        loadManifestIntoQueue()

        setupNav()

        refreshStatus()
        updateInfoTexts()
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
        adapter.notifyDataSetChanged()
        toolsAdapter.notifyDataSetChanged()
        if (tasks.isNotEmpty()) selectTask(0)
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
        switchTab(oneClick = false)
        log("工具入口: [$entry] 已加入小工具队列")
        if (vdFirst) {
            log("vd=1：先建虚拟屏并投游戏，再跑 [$entry]")
            vdOn = true
            binding.btnVdRun.text = "停止虚拟屏"
            binding.btnVdFull.isEnabled = true
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
            if (!running && shizukuReady()) runQueue(listOf(toolItem))
        }
        binding.btnStartQueue.postDelayed(queuedStart!!, 1800)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 虚拟屏是服务端权威状态(app 重启/切后台会丢内存标志)，回前台自动同步 UI
        syncVdUi()
    }

    /** 按服务端虚拟屏状态同步 UI：避免 vdOn 标志丢失导致预览黑屏/点击不注入 */
    private fun syncVdUi() {
        lifecycleScope.launch(Dispatchers.IO) {
            val alive = try { ShizukuShell.isVdAlive() } catch (e: Throwable) { false }
            runOnUiThread {
                if (alive && !vdOn) {
                    vdOn = true
                    binding.btnVdRun.text = getString(R.string.vd_stop)
                    binding.btnVdFull.isEnabled = true
                    VdStreamer.start()
                    vdHandler.removeCallbacks(vdUiTick)
                    vdHandler.post(vdUiTick)
                    log("虚拟屏状态已同步")
                } else if (!alive && vdOn) {
                    vdOn = false
                    binding.btnVdRun.text = getString(R.string.vd_run)
                    binding.btnVdFull.isEnabled = false
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
            { i, checked -> if (i < enabled.size) enabled[i] = checked },
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
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        })
        helper.attachToRecyclerView(binding.rvTaskList)

        // 小工具队列：同一适配器，仅绑到小工具 tab 的列表
        toolsAdapter = TaskQueueAdapter(
            toolsTasks,
            toolsEnabled,
            { i, checked -> if (i < toolsEnabled.size) toolsEnabled[i] = checked },
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
    }

    private fun deleteToolTask(i: Int) {
        if (i < 0 || i >= toolsTasks.size) return
        val removed = toolsTasks.removeAt(i)
        if (i < toolsEnabled.size) toolsEnabled.removeAt(i)
        toolsAdapter.notifyItemRemoved(i)
        log("已从工具队列删除: ${removed.label}")
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

    /** 一键长草 / 小工具 分区切换 */
    private fun switchTab(oneClick: Boolean) {
        binding.panelOneClick.visibility = if (oneClick) View.VISIBLE else View.GONE
        binding.panelTools.visibility = if (oneClick) View.GONE else View.VISIBLE
        binding.btnTabOneClick.isChecked = oneClick
        binding.btnTabTools.isChecked = !oneClick
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
        // 按当前 tab 决定跑哪个队列：小工具 tab 跑工具队列，否则跑一键长草主队列
        val onTools = binding.panelTools.visibility == View.VISIBLE
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
                runOnUiThread { setRunState("执行中: ${item.label}", R.color.accent) }
                log("==== 任务 ${item.label} 开始 ====")
                val r = try {
                    if (item.entry == "启动" || item.name == "启动") {
                        // 「启动」= 先进虚拟屏把游戏投进去，再用引擎收口到主页
                        runOnUiThread {
                            vdOn = true
                            binding.btnVdRun.text = "停止虚拟屏"
                            binding.btnVdFull.isEnabled = true
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
            // 仅当游戏已退出（如本队列末尾勾选了关闭游戏）才恢复音量
            if (closeAfterEnabled) ShizukuShell.execBlocking("am", "force-stop", GAME_PKG)
            if (muteEnabled && prevVolume > 0) {
                val gameAlive = try {
                    ShizukuShell.execBlocking("pidof", GAME_PKG)
                        .toString(Charsets.UTF_8).trim().isNotEmpty()
                } catch (e: Throwable) {
                    false
                }
                if (!gameAlive) setMusicVolume(prevVolume)
            }

            runOnUiThread {
                running = false
                binding.btnStartQueue.isEnabled = true
                binding.btnStartQueue.text = getString(R.string.btn_start_queue)
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
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleMute()
                2 -> closeAfterEnabled = !closeAfterEnabled
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
                4 -> muteEnabled = !muteEnabled
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
            binding.btnVdRun.text = "停止虚拟屏"
            binding.btnVdFull.isEnabled = true
            setRunState("启动虚拟屏…", R.color.accent)
            log("启动虚拟屏…")
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
            binding.btnVdRun.text = getString(R.string.vd_run)
            binding.btnVdFull.isEnabled = false
            VdStreamer.stop()
            vdHandler.removeCallbacks(vdUiTick)
            lifecycleScope.launch(Dispatchers.IO) { ShizukuShell.stopVirtual() }
            hideFullscreen()
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

    private fun takeScreenshot() {
        if (!shizukuReady()) { log("无法截图：Shizuku 未就绪"); refreshStatus(); return }
        binding.btnScreenshot.isEnabled = false
        lifecycleScope.launch {
            try {
                val bmp = ShizukuShell.screencap()
                lastBitmap = bmp
                binding.imageShot.setImageBitmap(bmp)
                log("预览刷新 ${bmp.width}x${bmp.height}")
            } catch (e: Exception) {
                log("截图失败：${e.message}")
            } finally {
                binding.btnScreenshot.isEnabled = true
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
        /** Shizuku 管理器包名（引导用户去启动服务用） */
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private const val SHIZUKU_GUIDE_URL = "https://shizuku.rikka.app/zh-hans/"
        private const val CASE_NOT_INSTALLED = 1
        private const val CASE_NOT_RUNNING = 2
        private const val CASE_NOT_GRANTED = 3
        /** 《物华弥新》游戏包名 */
        private const val GAME_PKG = "com.cipaishe.wuhua.bilibili"
        private const val MATCH_PARENT = -1
    }
}
