package com.maawh.app

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.io.File

/**
 * ============ libMaaFramework C API 的 JNA 绑定（最小子集） ============
 * 对应头文件：mfw-android/include/MaaFramework（目录）
 * 仅声明本工程用到的函数；句柄统一为不透明指针（Pointer）。
 */
interface MaaLibrary : Library {

    // ---------- global ----------
    fun MaaGlobalSetOption(key: Int, value: String, valSize: Long): Byte

    // ---------- buffers ----------
    fun MaaImageBufferCreate(): Pointer
    fun MaaImageBufferDestroy(buffer: Pointer)
    fun MaaImageBufferSetEncoded(buffer: Pointer, data: Pointer, size: Long): Byte

    fun MaaStringBufferCreate(): Pointer
    fun MaaStringBufferDestroy(buffer: Pointer)
    fun MaaStringBufferSet(buffer: Pointer, str: String): Byte

    // ---------- custom controller ----------
    fun MaaCustomControllerCreate(callbacks: MaaCustomControllerCallbacks, controllerArg: Pointer?): Pointer
    fun MaaControllerDestroy(ctrl: Pointer)
    fun MaaControllerSetOption(ctrl: Pointer, key: Int, value: Pointer, valSize: Long): Byte
    fun MaaControllerPostConnection(ctrl: Pointer): Long
    fun MaaControllerWait(ctrl: Pointer, id: Long): Int
    fun MaaControllerConnected(ctrl: Pointer): Byte

    // ---------- resource ----------
    fun MaaResourceCreate(): Pointer
    fun MaaResourceDestroy(res: Pointer)
    fun MaaResourcePostBundle(res: Pointer, path: String): Long
    fun MaaResourceWait(res: Pointer, id: Long): Int
    fun MaaResourceLoaded(res: Pointer): Byte
    fun MaaResourceGetNodeList(res: Pointer, buffer: Pointer): Byte

    // ---------- string buffers ----------
    fun MaaStringBufferGet(buffer: Pointer): Pointer
    fun MaaStringListBufferCreate(): Pointer
    fun MaaStringListBufferDestroy(buffer: Pointer)
    fun MaaStringListBufferSize(buffer: Pointer): Long
    fun MaaStringListBufferAt(buffer: Pointer, index: Long): Pointer

    // ---------- tasker ----------
    fun MaaTaskerCreate(): Pointer
    fun MaaTaskerDestroy(tasker: Pointer)
    fun MaaTaskerBindResource(tasker: Pointer, res: Pointer): Byte
    fun MaaTaskerBindController(tasker: Pointer, ctrl: Pointer): Byte
    fun MaaTaskerInited(tasker: Pointer): Byte
    fun MaaTaskerPostTask(tasker: Pointer, entry: String, pipelineOverride: String?): Long
    fun MaaTaskerWait(tasker: Pointer, id: Long): Int
    fun MaaTaskerRunning(tasker: Pointer): Byte
    fun MaaTaskerPostStop(tasker: Pointer): Long
    fun MaaTaskerGetRecognitionDetail(tasker: Pointer, taskId: Long, buffer: Pointer): Byte

    // ---------- tasker 实时事件（MaaTasker.h：MaaSinkId MaaTaskerAddSink(tasker, sink, trans_arg)）----------
    fun MaaTaskerAddSink(tasker: Pointer, sink: MaaEventSink, transArg: Pointer?): Long
}

/**
 * 引擎事件回调（对应 MaaDef.h 的 MaaEventCallback）。
 * 在【引擎线程】被调；message 是事件名（MaaMsg.h，如 "Node.PipelineNode.Starting"），
 * detailsJson 是事件明细 json（节点名 / task_id / reco_id 等）。
 */
fun interface MaaEventSink : Callback {
    fun invoke(handle: Pointer?, message: String?, detailsJson: String?, transArg: Pointer?)
}

/** 引擎实时事件（常用字段已从 details 解出；解析失败置 null） */
data class MaaEvent(
    val msg: String,          // 事件名，如 Tasker.Task.Failed / Node.PipelineNode.Starting
    val nodeName: String?,    // details.name（Node.* 事件的节点名）
    val entry: String?,       // details.entry（Tasker.Task.* 事件的入口名）
    val taskId: Long?,        // details.task_id
    val details: String       // 原始 json
)

// ============ CustomController 回调函数指针表（结构体字段顺序必须与头文件一致） ============

class MaaCustomControllerCallbacks : Structure() {
    fun interface ConnectCb : Callback { fun invoke(transArg: Pointer?): Byte }
    fun interface ConnectedCb : Callback { fun invoke(transArg: Pointer?): Byte }
    fun interface RequestUuidCb : Callback { fun invoke(transArg: Pointer?, buffer: Pointer?): Byte }
    fun interface GetFeaturesCb : Callback { fun invoke(transArg: Pointer?): Long }
    fun interface StartAppCb : Callback { fun invoke(intent: String?, transArg: Pointer?): Byte }
    fun interface StopAppCb : Callback { fun invoke(intent: String?, transArg: Pointer?): Byte }
    fun interface ScreencapCb : Callback { fun invoke(transArg: Pointer?, buffer: Pointer?): Byte }
    fun interface ClickCb : Callback { fun invoke(x: Int, y: Int, transArg: Pointer?): Byte }
    fun interface SwipeCb : Callback {
        fun invoke(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int, transArg: Pointer?): Byte
    }
    fun interface TouchDownCb : Callback { fun invoke(contact: Int, x: Int, y: Int, pressure: Int, transArg: Pointer?): Byte }
    fun interface TouchMoveCb : Callback { fun invoke(contact: Int, x: Int, y: Int, pressure: Int, transArg: Pointer?): Byte }
    fun interface TouchUpCb : Callback { fun invoke(contact: Int, transArg: Pointer?): Byte }
    fun interface ClickKeyCb : Callback { fun invoke(keycode: Int, transArg: Pointer?): Byte }
    fun interface InputTextCb : Callback { fun invoke(text: String?, transArg: Pointer?): Byte }
    fun interface KeyDownCb : Callback { fun invoke(keycode: Int, transArg: Pointer?): Byte }
    fun interface KeyUpCb : Callback { fun invoke(keycode: Int, transArg: Pointer?): Byte }
    fun interface ScrollCb : Callback { fun invoke(dx: Int, dy: Int, transArg: Pointer?): Byte }
    fun interface RelativeMoveCb : Callback { fun invoke(dx: Int, dy: Int, transArg: Pointer?): Byte }
    fun interface ShellCb : Callback {
        fun invoke(cmd: String?, timeout: Long, transArg: Pointer?, buffer: Pointer?): Byte
    }
    fun interface InactiveCb : Callback { fun invoke(transArg: Pointer?): Byte }
    fun interface GetInfoCb : Callback { fun invoke(transArg: Pointer?, buffer: Pointer?): Byte }

    @JvmField var connect: ConnectCb? = null
    @JvmField var connected: ConnectedCb? = null
    @JvmField var requestUuid: RequestUuidCb? = null
    @JvmField var getFeatures: GetFeaturesCb? = null
    @JvmField var startApp: StartAppCb? = null
    @JvmField var stopApp: StopAppCb? = null
    @JvmField var screencap: ScreencapCb? = null
    @JvmField var click: ClickCb? = null
    @JvmField var swipe: SwipeCb? = null
    @JvmField var touchDown: TouchDownCb? = null
    @JvmField var touchMove: TouchMoveCb? = null
    @JvmField var touchUp: TouchUpCb? = null
    @JvmField var clickKey: ClickKeyCb? = null
    @JvmField var inputText: InputTextCb? = null
    @JvmField var keyDown: KeyDownCb? = null
    @JvmField var keyUp: KeyUpCb? = null
    @JvmField var scroll: ScrollCb? = null
    @JvmField var relativeMove: RelativeMoveCb? = null
    @JvmField var shell: ShellCb? = null
    @JvmField var inactive: InactiveCb? = null
    @JvmField var getInfo: GetInfoCb? = null

    override fun getFieldOrder(): List<String> = listOf(
        "connect", "connected", "requestUuid", "getFeatures",
        "startApp", "stopApp", "screencap", "click", "swipe",
        "touchDown", "touchMove", "touchUp", "clickKey", "inputText",
        "keyDown", "keyUp", "scroll", "relativeMove", "shell",
        "inactive", "getInfo"
    )
}

/**
 * MaaFramework 引擎桥接：加载 .so、用自定义控制器把截图/点击接到 ShizukuShell，
 * 并提供一个「自检」demo —— 在 MaaWH 界面用 TemplateMatch 找到按钮并点击。
 */
object MaaBridge {

    private val loadLock = Any()
    private var libsLoaded = false

    /** 截图 PNG 内存保持引用，防止 SetEncoded 后 Memory 被回收 */
    private val memPool = java.util.Collections.synchronizedList(mutableListOf<Memory>())

    /**
     * 控制器回调表强引用：引擎异步线程（ActionRunner）在控制器销毁后仍可能调用
     * inactive/screencap 等回调，若回调表是 run() 的局部变量，GC 会在引擎仍持有
     * 函数指针时回收 JNA trampoline → SIGBUS（pc=0x25f）/scudo abort（release 实测，
     * debuggable 的 GC 时序会掩盖）。保留到下一次 run() 覆盖，窗口内对象永远可达。
     */
    @Volatile
    private var liveControllerCallbacks: MaaCustomControllerCallbacks? = null

    @Volatile
    private var stopFlag = false

    @Volatile
    private var activeTasker: Pointer? = null

    /** 串行化「停止注入」与「引擎销毁」，避免 PostStop 与 Destroy 并发触发 FORTIFY mutex 崩溃 */
    private val stopLock = Any()

    // ============ 引擎实时事件（第 2 步：通知回调，替代 500ms 日志轮询的地基） ============
    //
    // 引擎每个节点/动作/任务的 Starting/Succeeded/Failed 都经 MaaTaskerAddSink 实时推过来
    // （此前只能 500ms 轮询 tail maafw.log 再正则解析）。两个消费方向：
    //  - QueueRunner 订阅：状态行实时显示当前节点、控制器动作失败即时上报；
    //  - 后续悬浮面板 / 通知进度 / 任务历史都吃这条事件流。
    // EngineLog 日志轮询保留：识别失败/超时的归因细节（模板/阈值/ROI 提示）仍靠日志文本。

    /** 事件名常量（MaaMsg.h 子集，只列本项目消费的） */
    object MaaMsg {
        const val TASK_STARTING = "Tasker.Task.Starting"
        const val NODE_STARTING = "Node.PipelineNode.Starting"
        const val CTRL_FAILED = "Controller.Action.Failed"
    }

    /** 监听器：在【引擎线程】被调，必须快（只做转发/落队列，重活切自己的线程） */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(MaaEvent) -> Unit>()

    fun addEngineEventListener(l: (MaaEvent) -> Unit) { listeners.add(l) }
    fun removeEngineEventListener(l: (MaaEvent) -> Unit) { listeners.remove(l) }

    /**
     * Sink 单例：必须与 MaaBridge 同生命周期持有——JNA Callback 的 trampoline 若在
     * 引擎仍持引用时被回收会直接崩；挂在 object 单例上则跨 runTask 复用、永不回收。
     */
    private val eventSink = MaaEventSink { _, msg, details, _ ->
        val m = msg ?: return@MaaEventSink
        var node: String? = null
        var entry: String? = null
        var tid: Long? = null
        try {
            val jo = org.json.JSONObject(details ?: "{}")
            node = jo.optString("name").takeIf { it.isNotEmpty() }
            entry = jo.optString("entry").takeIf { it.isNotEmpty() }
            tid = if (jo.has("task_id")) jo.getLong("task_id") else null
        } catch (ignored: Throwable) {
        }
        val ev = MaaEvent(m, node, entry, tid, details ?: "")
        for (l in listeners) {
            // 单个监听器抛异常不能打死引擎回调线程（那会拖垮整个任务）
            try { l(ev) } catch (e: Throwable) {
                android.util.Log.w("MaaWH", "engine event listener error", e)
            }
        }
    }

    /** 请求立即停止运行中的引擎任务（线程安全，可从任意线程调用） */
    fun requestStop() {
        stopFlag = true
        synchronized(stopLock) {
            activeTasker?.let { t ->
                runCatching { if (::native.isInitialized) native.MaaTaskerPostStop(t) }
            }
        }
        // 不做长等待：引擎释放由 runTask 的停止路径处理（销毁前缓冲），避免阻塞 UI
    }

    /** 开始新一轮队列前调用，清除历史停止标志 */
    fun clearStop() {
        stopFlag = false
    }

    private fun shouldStop(): Boolean = stopFlag

    /**
     * 停止后等引擎 worker 真正退出再销毁。
     * PostStop 内部会 clear 任务队列（AsyncRunner::compl_id_ 直接顶到最新），MaaTaskerWait
     * 因此【提前返回】，但 worker 线程还在节点里跑（wait_freezes 不响应停止标记，只按自身
     * timeout 结束）；此时销毁 Tasker 会让 worker 写已释放的 RuntimeCache →
     * FORTIFY "pthread_mutex_lock called on a destroyed mutex" → 整个进程 abort 闪退。
     * 所以必须轮询 MaaTaskerRunning 到 0（worker 已脱离任务）后再销毁。
     */
    private fun waitEngineIdle(lib: MaaLibrary, tasker: Pointer, onLog: (String) -> Unit) {
        var waited = 0
        while (lib.MaaTaskerRunning(tasker).toInt() != 0 && waited < 60000) {
            try { Thread.sleep(100) } catch (ignored: InterruptedException) {}
            waited += 100
        }
        if (waited >= 60000) {
            onLog("警告：引擎 60s 仍未退出，按超时继续销毁（小概率崩溃）")
        }
        // worker 脱离任务后再留一点收尾缓冲（JNA 回调 trampoline 收尾）
        try { Thread.sleep(500) } catch (ignored: InterruptedException) {}
    }

    private lateinit var native: MaaLibrary

    fun isReady(): Boolean = libsLoaded

    private fun ensureNative(): MaaLibrary {
        if (!libsLoaded) {
            synchronized(loadLock) {
                if (!libsLoaded) {
                    val names = listOf(
                        "c++_shared",
                        "opencv_world4",
                        "onnxruntime",
                        "fastdeploy_ppocr",
                        "MaaUtils",
                        "MaaFramework",
                        "MaaCustomControlUnit"
                    )
                    names.forEach { n ->
                        try {
                            System.loadLibrary(n)
                        } catch (e: UnsatisfiedLinkError) {
                            if (n == "MaaFramework") throw e
                        }
                    }
                    libsLoaded = true
                }
            }
        }
        if (!::native.isInitialized) {
            native = Native.load("MaaFramework", MaaLibrary::class.java)
        }
        return native
    }

    /**
     * 列出任务包内所有可执行节点（加载资源后由引擎返回）。
     * 失败返回 null。
     */
    fun listNodeNames(bundleDir: File): List<String>? {
        val lib = ensureNative()
        var res: Pointer? = null
        return try {
            res = lib.MaaResourceCreate()
            val resource = checkNotNull(res) { "MaaResourceCreate 失败" }
            val id = lib.MaaResourcePostBundle(resource, bundleDir.absolutePath)
            if (lib.MaaResourceWait(resource, id) != MaaConst.STATUS_SUCCEEDED) return null
            val list = lib.MaaStringListBufferCreate()
            try {
                if (lib.MaaResourceGetNodeList(resource, list).toInt() == 0) return null
                val size = lib.MaaStringListBufferSize(list)
                val names = ArrayList<String>(size.toInt())
                for (i in 0L until size) {
                    val item = lib.MaaStringListBufferAt(list, i)
                    val ptr = lib.MaaStringBufferGet(item)
                    ptr?.let { names.add(it.getString(0, "UTF-8")) }
                }
                names
            } finally {
                lib.MaaStringListBufferDestroy(list)
            }
        } catch (e: Throwable) {
            null
        } finally {
            res?.let { runCatching { lib.MaaResourceDestroy(it) } }
        }
    }

    // ============ Resource 常驻复用（第 3 步：队列提速） ============
    //
    // 原先每次 runTask 都 Create → PostBundle → Wait 整包重载（whmx 全部 JSON+模板图），
    // 队列 N 个任务就重载 N 遍。现在 Resource 常驻，靠【目录快照】检测任务包变化：
    // 编辑器直推 vf_*.json / adb 推模板后，下一次 runTask 会自动重载——
    // "推完即生效"这条 Stdio 契约不能破。

    private var cachedRes: Pointer? = null

    /** 加载时的目录快照指纹（lastModified+size 哈希）；-1 = 无缓存 */
    private var cachedResStamp: Long = -1L

    private val resLock = Any()

    /** 递归快照：文件相对路径、mtime、大小一起揉进指纹。whmx 约百个文件，毫秒级 */
    private fun dirStamp(dir: File): Long {
        var h = 1469598103934665603L   // FNV offset basis，随便一个稳定初值
        dir.walkTopDown().forEach { f ->
            h = h xor (f.relativeTo(dir).path + "|" + f.lastModified() + "|" + f.length()).hashCode().toLong()
            h *= 1099511628211L
        }
        return h
    }

    /**
     * 取可用的 Resource：无缓存 → 加载；目录指纹变了 → 销毁重建；
     * 没变 → 直接复用。返回 null = 加载失败（日志已说明）。
     */
    private fun ensureResource(lib: MaaLibrary, bundleDir: File, onLog: (String) -> Unit): Pointer? {
        synchronized(resLock) {
            val stamp = dirStamp(bundleDir)
            val cur = cachedRes
            if (cur != null && stamp == cachedResStamp) {
                onLog("任务包未变化，复用已加载资源")
                return cur
            }
            if (cur != null) {
                onLog("检测到任务包内容变化，重新加载资源")
                runCatching { lib.MaaResourceDestroy(cur) }
                cachedRes = null
                cachedResStamp = -1L
            }
            val res = lib.MaaResourceCreate() ?: run {
                onLog("MaaResourceCreate 失败")
                return null
            }
            val resId = lib.MaaResourcePostBundle(res, bundleDir.absolutePath)
            val resStatus = lib.MaaResourceWait(res, resId)
            onLog("资源加载: status=$resStatus ${statusText(resStatus)}")
            if (resStatus != MaaConst.STATUS_SUCCEEDED) {
                runCatching { lib.MaaResourceDestroy(res) }
                return null
            }
            cachedRes = res
            cachedResStamp = stamp
            return res
        }
    }

    /**
     * 运行任务包中的指定入口任务。
     * 需在后台线程调用（会阻塞）。
     */
    fun runTask(
        bundleDir: File,
        entry: String,
        logDir: File,
        pipelineOverride: String?,
        onLog: (String) -> Unit
    ): Boolean {
        val lib = ensureNative()
        var ctrl: Pointer? = null
        var tasker: Pointer? = null
        var callbacks: MaaCustomControllerCallbacks? = null

        fun destroyAll() {
            synchronized(stopLock) {
                tasker?.let { runCatching { lib.MaaTaskerDestroy(it) } }
                ctrl?.let { runCatching { lib.MaaControllerDestroy(it) } }
                tasker = null; ctrl = null
                // Resource 不在这里销毁：它由 ensureResource 常驻复用（第 3 步），
                // 队列跑 N 个任务不再重复加载整包资源；变更检测负责重载
            }
        }

        try {
            if (shouldStop()) {
                onLog("任务已收到停止请求，本次不执行")
                return false
            }
            if (!ShizukuShell.pingReady()) {
                onLog("Shizuku 未就绪，无法运行引擎")
                return false
            }

            // 打开引擎日志，便于诊断
            logDir.mkdirs()
            runCatching {
                lib.MaaGlobalSetOption(MaaConst.OPT_GLOBAL_LOG_DIR, logDir.absolutePath, logDir.absolutePath.length.toLong())
            }
            onLog("引擎日志目录: ${logDir.absolutePath}")

            // 1) 资源：常驻复用，whmx 目录内容变化时才重新加载（保住"直推 vf 即生效"）
            val resource = ensureResource(lib, bundleDir, onLog) ?: return false

            // 2) 控制器：自定义控制器 -> Shizuku
            callbacks = buildCallbacks(onLog)
            liveControllerCallbacks = callbacks
            ctrl = lib.MaaCustomControllerCreate(callbacks, null)
            val controller = checkNotNull(ctrl) { "MaaCustomControllerCreate 失败（可能缺少 libMaaCustomControlUnit.so）" }

            // 关闭引擎的截图缩放/旋转归一化，按原始分辨率识别（模板按原分辨率制作）
            // 引擎校验 val_size == sizeof(bool) == 1，传 4 字节会被拒（invalid value size）
            val rawValue = Memory(1)
            rawValue.setByte(0, 1)
            lib.MaaControllerSetOption(controller, MaaConst.OPT_CTRL_SCREENSHOT_USE_RAW_SIZE /* ScreenshotUseRawSize */, rawValue, 1)

            val connId = lib.MaaControllerPostConnection(controller)
            val connStatus = lib.MaaControllerWait(controller, connId)
            onLog("控制器连接: status=$connStatus ${statusText(connStatus)}")
            if (connStatus != MaaConst.STATUS_SUCCEEDED || lib.MaaControllerConnected(controller).toInt() == 0) {
                onLog("控制器连接失败")
                return false
            }

            // 3) Tasker
            tasker = lib.MaaTaskerCreate()
            val taskerHandle = checkNotNull(tasker) { "MaaTaskerCreate 失败" }
            lib.MaaTaskerBindResource(taskerHandle, resource)
            lib.MaaTaskerBindController(taskerHandle, controller)
            // 实时事件 Sink（节点/动作/任务的 Starting/Succeeded/Failed 都推给 listeners）
            lib.MaaTaskerAddSink(taskerHandle, eventSink, null)
            onLog("Tasker inited=${lib.MaaTaskerInited(taskerHandle).toInt()}")

            // 4) 跑任务（pipeline_override 传空 JSON，引擎不允许 NULL 覆盖串）
            activeTasker = taskerHandle
            val taskId = lib.MaaTaskerPostTask(taskerHandle, entry, pipelineOverride ?: "{}")
            val taskStatus = lib.MaaTaskerWait(taskerHandle, taskId)
            activeTasker = null
            onLog("任务结束: [$entry] status=$taskStatus ${statusText(taskStatus)}")
            // 无论成败都先等引擎真正空闲再销毁：Wait 返回时控制器动作线程可能还在
            // 收尾（失败/超时路径同样如此），不等就 destroy 是 use-after-free
            //（9-17 I11 两次 SIGSEGV/SIGBUS 均崩在 get_info_from_controller）。
            // 停止路径同理：PostStop 让 Wait 提前返回但 worker 还在跑
            waitEngineIdle(lib, taskerHandle, onLog)
            return taskStatus == MaaConst.STATUS_SUCCEEDED
        } catch (e: Throwable) {
            onLog("引擎异常: $e")
            tasker?.let { t ->
                runCatching { lib.MaaTaskerPostStop(t) }
                // 异常路径同样先等 worker 退出再走 finally 的销毁
                waitEngineIdle(lib, t, onLog)
            }
            return false
        } finally {
            activeTasker = null
            destroyAll()
            memPool.clear()
        }
    }

    private fun statusText(s: Int): String = when (s) {
        MaaConst.STATUS_SUCCEEDED -> "成功"
        MaaConst.STATUS_FAILED -> "失败"
        MaaConst.STATUS_PENDING -> "等待中"
        MaaConst.STATUS_RUNNING -> "运行中"
        else -> "($s)"
    }

    // ============ 回调实现 ============

    private fun buildCallbacks(onLog: (String) -> Unit): MaaCustomControllerCallbacks {
        val cb = MaaCustomControllerCallbacks()
        val lib = native

        cb.connect = MaaCustomControllerCallbacks.ConnectCb { _ -> 1 }
        cb.connected = MaaCustomControllerCallbacks.ConnectedCb { _ -> 1 }

        cb.startApp = MaaCustomControllerCallbacks.StartAppCb { intent, _ ->
            try {
                val i = intent ?: ""
                val act: String =
                    if (i.contains("/")) {
                        ShizukuShell.execBlocking("am", "start", "-n", i)
                        "am start -n $i"
                    } else {
                        // 游戏进程已在运行则跳过 monkey：避免「启动已把游戏送进虚拟屏后，
                        // 刷冬谷币又整包冷启动一遍、重弹公告」。只有进程不在时才拉起来。
                        val running = try {
                            val out = ShizukuShell.execBlocking("pidof", i)
                            out.toString(Charsets.UTF_8).trim().isNotEmpty()
                        } catch (e: Throwable) {
                            false
                        }
                        if (running) {
                            "skip(already running)"
                        } else {
                            ShizukuShell.execBlocking("monkey", "-p", i, "1")
                            "monkey -p $i 1"
                        }
                    }
                onLog("启动App: $i -> $act")
                1
            } catch (e: Throwable) {
                onLog("启动App失败: ${e.message}")
                0
            }
        }

        cb.stopApp = MaaCustomControllerCallbacks.StopAppCb { intent, _ ->
            try {
                ShizukuShell.execBlocking("am", "force-stop", intent ?: "")
                1
            } catch (e: Throwable) {
                0
            }
        }

        cb.requestUuid = MaaCustomControllerCallbacks.RequestUuidCb { _, buffer ->
            try {
                lib.MaaStringBufferSet(buffer!!, "maawh-shizuku-android")
            } catch (e: Throwable) {
                0
            }
        }

        cb.getFeatures = MaaCustomControllerCallbacks.GetFeaturesCb { _ -> 0L }

        cb.screencap = MaaCustomControllerCallbacks.ScreencapCb { _, buffer ->
            try {
                // M4-④a：虚拟屏模式下直接取 ImageReader 帧(JPEG)。
                // 注意：VD 帧为空时【不要】回退主屏 screencap —— 主屏(竖屏/异分辨率)帧会改变引擎
                // 对截图分辨率/旋转的判定，导致后续固定坐标映射错乱(Actuator failed to get target rect)。
                // 点击后游戏切换界面的瞬间画面会短暂取不到（实测约 2 秒）：空帧重试窗口
                // 加宽到 2.5 秒，在 App 内部吸收这类瞬态；真正的故障（虚拟屏没了）仍会报错。
                val img: ByteArray =
                    if (ShizukuShell.vdMode) {
                        var f = ByteArray(0)
                        val deadline = System.currentTimeMillis() + 2500
                        while (System.currentTimeMillis() < deadline) {
                            f = ShizukuShell.grabVirtualFrame()
                            if (f.isNotEmpty()) break
                            Thread.sleep(120)
                        }
                        if (f.isEmpty()) {
                            onLog("截图回调: VD 帧为空（已重试约 2.5 秒），本轮截图失败")
                            return@ScreencapCb 0
                        }
                        f
                    } else {
                        ShizukuShell.execBlocking("screencap", "-p")
                    }
                val mem = Memory(img.size.toLong() + 1)
                mem.write(0, img, 0, img.size)
                memPool.add(mem)
                lib.MaaImageBufferSetEncoded(buffer!!, mem, img.size.toLong())
            } catch (e: Throwable) {
                onLog("截图回调失败: $e")
                0
            }
        }

        cb.click = MaaCustomControllerCallbacks.ClickCb { x, y, _ ->
            try {
                val r = ShizukuShell.clickOnTarget(x, y)
                onLog("引擎点击 ($x, $y) => $r")
                1
            } catch (e: Throwable) {
                onLog("点击回调失败: $e")
                0
            }
        }

        cb.swipe = MaaCustomControllerCallbacks.SwipeCb { x1, y1, x2, y2, dur, _ ->
            try {
                val r = ShizukuShell.swipeOnTarget(x1, y1, x2, y2, dur)
                onLog("引擎滑动 ($x1,$y1)->($x2,$y2) => $r")
                1
            } catch (e: Throwable) {
                onLog("滑动回调失败: $e")
                0
            }
        }

        cb.inactive = MaaCustomControllerCallbacks.InactiveCb { _ -> 1 }

        cb.getInfo = MaaCustomControllerCallbacks.GetInfoCb { _, buffer ->
            try {
                lib.MaaStringBufferSet(buffer!!, "{\"platform\":\"android\",\"impl\":\"maawh-shizuku\"}")
            } catch (e: Throwable) {
                0
            }
        }

        return cb
    }
}
