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
import android.view.ScaleGestureDetector
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
 * `[▶ 任务进度] [■停] [×关]`。整窗可拖动、**画面支持双指捏合缩放**（0.4×~2.5×，会话内记忆）：
 *   - [■] = 停止当前队列（与通知栏停止按钮同一入口 requestStopCurrent）；
 *   - [×] = 手动收起，直到下次回前台才恢复自动弹出（避免"关了又弹"）；
 *   - 点画面区域 = 回 MaaWH（位移小于判定阈值的抬起才算点击，拖动/缩放不算）。
 *
 * 权限：SYSTEM_ALERT_WINDOW（「显示在其他应用上层」）。无权限时静默跳过，
 * 由 MainActivity 在切后台时提示一次引导。
 */
object FloatingPanel {

    private const val TOUCH_SLOP = 12          // px，位移小于它算点击而非拖动
    private const val FRAME_W = 432            // 画面基准宽（屏宽 40%，1080 屏）
    private const val FRAME_H = FRAME_W * 9 / 16   // 虚拟屏 16:9 → 243
    private const val FRAME_TICK_MS = 200L
    private const val MIN_SCALE = 0.4f
    private const val MAX_SCALE = 2.5f

    private val mainHandler = Handler(Looper.getMainLooper())

    private var appCtx: Context? = null
    private var added = false
    private var rootView: LinearLayout? = null
    private var frameView: ImageView? = null
    private var frameLp: LinearLayout.LayoutParams? = null
    private var statusView: TextView? = null
    private var pendingText: String? = null
    private var lp: WindowManager.LayoutParams? = null

    /** 画面缩放（会话内记忆：关窗再弹保持上次大小） */
    private var scale = 1.0f

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

    // ------------------------------------------------------------------

    private val frameTick = object : Runnable {
        override fun run() {
            frameView?.let { v ->
                VdShared.frame?.let { v.setImageBitmap(it) }
            }
            mainHandler.postDelayed(this, FRAME_TICK_MS)
        }
    }

    private fun attach() {
        val ctx = appCtx ?: return
        if (added) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = ctx.resources.displayMetrics.density

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
        frameLp = LinearLayout.LayoutParams(frameSize(), frameSize() * 9 / 16).apply {
            gravity = Gravity.CENTER
        }

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
            frame,
            frameLp!!.apply { gravity = Gravity.CENTER }
        )
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(status)
        row.addView(chip("■") {
            QueueRunner.requestStopCurrent()
            Toast.makeText(ctx, "已请求停止任务", Toast.LENGTH_SHORT).show()
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

        // 缩放：双指捏合，实时改画面 LayoutParams（窗 WRAP_CONTENT 自动跟）
        val detector = ScaleGestureDetector(
            ctx,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    scale = (scale * d.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                    applyScale()
                    return true
                }
            }
        )

        // 拖动 + 点击判定：MOVE 跟手移动，抬起时位移小于阈值视为点击 → 回 MaaWH；
        // 双指期间交给缩放，不算拖动/点击
        var downRawX = 0f; var downRawY = 0f
        var startLpX = 0; var startLpY = 0
        var moved = false
        box.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startLpX = params.x; startLpY = params.y
                    moved = false
                    false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 第二指落下：进入缩放，本次手势不再触发点击/拖动
                    moved = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount > 1) return@setOnTouchListener true
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

    /** 当前缩放下的画面宽（高按 16:9 推） */
    private fun frameSize(): Int = (FRAME_W * scale).toInt()

    private fun applyScale() {
        val fl = frameLp ?: return
        fl.width = frameSize()
        fl.height = frameSize() * 9 / 16
        rootView?.requestLayout()
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
