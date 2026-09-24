package com.maawh.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 虚拟屏悬浮窗（对标 MAA-Meow 的后台悬浮面板，View 版不引库）。
 *
 * 触发：App 切到后台（虚拟屏活着或任务运行中）→ MainActivity.onStop 自动弹出；回前台自动收起。
 * 形态：上半是虚拟屏实时画面（复用 VdStreamer 已拉取的帧，200ms 刷新），下方一行
 * `[▶ 任务进度] [■停] [×关]`。整窗可拖动：
 *   - [■] = 停止当前队列（与通知栏停止按钮同一入口 requestStopCurrent）；
 *   - [×] = 手动收起，直到下次回前台才恢复自动弹出（避免"关了又弹"）；
 *   - 点画面区域 = 回 MaaWH（位移小于判定阈值的抬起才算点击，拖动不算）。
 *
 * 权限：SYSTEM_ALERT_WINDOW（「显示在其他应用上层」）。无权限时静默跳过，
 * 由 MainActivity 在切后台时提示一次引导。
 */
object FloatingPanel {

    private const val TOUCH_SLOP = 12          // px，位移小于它算点击而非拖动
    private const val FRAME_W = 432            // 画面宽（屏宽 40%，1080 屏 · 100% 缩放时）
    private const val FRAME_H = FRAME_W * 9 / 16   // 虚拟屏 16:9 → 243
    private const val FRAME_TICK_MS = 200L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var appCtx: Context? = null
    private var added = false
    private var rootView: LinearLayout? = null
    private var frameView: ImageView? = null
    private var surfaceView: VdSurfaceView? = null
    private var statusView: TextView? = null
    private var pendingText: String? = null
    private var lp: WindowManager.LayoutParams? = null

    /** 用户点过 [×] 后置位：本次后台会话内不再自动弹出；回前台（hide）复位 */
    @Volatile
    private var dismissed = false

    fun isPermissionGranted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** App 切后台：显示虚拟屏悬浮窗（无权限 / 用户已手动关闭 → 不弹） */
    fun showForBackground(ctx: Context) {
        if (dismissed) return
        if (!isPermissionGranted(ctx)) return
        appCtx = ctx.applicationContext
        mainHandler.post { attach() }
    }

    /** 回前台 / 虚拟屏关闭：收起（并复位手动关闭标志） */
    fun hide() {
        dismissed = false
        mainHandler.post { detach() }
    }

    /** 更新状态行文字（任意线程可调；未显示时记住，attach 后立即可见） */
    fun update(text: String) {
        pendingText = text
        mainHandler.post { statusView?.text = text }
    }

    /**
     * 悬浮窗「■停止」的行为（默认 = 停止当前任务队列）。
     * 抽卡识别运行时由 MainActivity 换成停止抓取，结束后恢复默认。
     */
    @Volatile
    var stopCallback: (() -> Unit)? = null

    // ------------------------------------------------------------------

    private val frameTick = object : Runnable {
        override fun run() {
            // SurfaceView 常驻可见（可见性死锁坑见 MainActivity.vdUiTick）：直渲画不透明帧
            // 自然盖住底图；降级时 Surface 透明，位图路径照常可见
            if (!VdPreview.nativeActive) {
                frameView?.let { v ->
                    VdShared.frame?.let { v.setImageBitmap(it) }
                }
            }
            mainHandler.postDelayed(this, FRAME_TICK_MS)
        }
    }

    private fun attach() {
        val ctx = appCtx ?: return
        if (added) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = ctx.resources.displayMetrics.density
        // 画面尺寸跟随「页面缩放」：DisplayScale 只改 densityDpi，这里的 px 常量不会自己缩，
        // 不跟着缩会出现「窗口字变小、虚拟屏画面还是那么大」
        val scale = DisplayScale.percent(ctx) / 100f
        val frameW = (FRAME_W * scale).toInt()
        val frameH = (FRAME_H * scale).toInt()

        fun chip(label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(density, 10), dp(density, 4), dp(density, 10), dp(density, 4))
            setOnClickListener { onClick() }
        }

        val frame = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            VdShared.frame?.let { setImageBitmap(it) }
        }
        frameView = frame
        // 双路径画面：GPU 直渲 Surface 盖在位图 ImageView 上（降级时 Surface 隐藏）
        val frameBox = android.widget.FrameLayout(ctx)
        frameBox.addView(frame, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        val sv = VdSurfaceView(ctx)
        surfaceView = sv
        frameBox.addView(sv, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))

        val status = TextView(ctx).apply {
            text = pendingText ?: "MaaWH"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(density, 10), 0, dp(density, 2), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusView = status

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(density, 10).toFloat()
                setColor(0xE6202124.toInt())
            }
            setPadding(dp(density, 4), dp(density, 4), dp(density, 4), dp(density, 2))
        }
        box.addView(
            frameBox,
            LinearLayout.LayoutParams(frameW, frameH).apply { gravity = Gravity.CENTER }
        )
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(status)
        row.addView(chip("■") {
            val cb = stopCallback
            if (cb != null) {
                cb()
                Toast.makeText(ctx, "已请求停止", Toast.LENGTH_SHORT).show()
            } else {
                QueueRunner.requestStopCurrent()
                Toast.makeText(ctx, "已请求停止任务", Toast.LENGTH_SHORT).show()
            }
        })
        row.addView(chip("×") {
            dismissed = true
            detach()
        })
        box.addView(row)
        rootView = box

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            // NOT_FOCUSABLE：不抢焦点（背后 App 的返回键正常）；NOT_TOUCH_MODAL：窗外触摸穿透
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = dp(density, 24)
            y = dp(density, 120)
        }
        lp = params

        // 拖动 + 点击判定：MOVE 跟手移动，抬起时位移小于阈值视为点击 → 回 MaaWH
        var downRawX = 0f; var downRawY = 0f
        var startLpX = 0; var startLpY = 0
        var moved = false
        box.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startLpX = params.x; startLpY = params.y
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (moved || Math.abs(dx) > TOUCH_SLOP || Math.abs(dy) > TOUCH_SLOP) {
                        moved = true
                        params.x = startLpX + dx
                        params.y = startLpY + dy
                        runCatching { wm.updateViewLayout(box, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) backToApp(ctx)
                    moved
                }
                else -> false
            }
        }

        try {
            wm.addView(box, params)
            added = true
            mainHandler.removeCallbacks(frameTick)
            mainHandler.post(frameTick)
        } catch (e: Throwable) {
            added = false
        }
    }

    private fun detach() {
        val ctx = appCtx ?: return
        val view = rootView ?: return
        if (!added) return
        mainHandler.removeCallbacks(frameTick)
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (ignored: Throwable) {
        }
        added = false
        rootView = null
        frameView = null
        surfaceView = null
        statusView = null
        lp = null
    }

    private fun backToApp(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun dp(density: Float, v: Int): Int = (v * density).toInt()
}
