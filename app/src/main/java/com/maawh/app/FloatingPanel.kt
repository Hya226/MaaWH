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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 悬浮进度条（对标 MAA-Meow 的悬浮面板，View 版不引库）。
 *
 * 触发：任务运行中把 App 切到后台 → MainActivity.onStop 自动弹出；回前台自动收起。
 * 形态：一颗可拖动的圆角横条 `[状态文字] [■停] [×关]`，浮在游戏/其他应用上方：
 *   - 点横条主体（位移小于判定阈值的抬起）= 回 MaaWH；
 *   - [■] = 停止当前队列（与通知栏停止按钮同一入口 requestStopCurrent）；
 *   - [×] = 手动收起，本轮队列内不再自动弹出（队列结束自动复位）。
 *
 * 权限：SYSTEM_ALERT_WINDOW（「显示在其他应用上层」）。无权限时静默跳过，
 * 由 MainActivity 在切后台时提示一次引导。
 */
object FloatingPanel {

    private const val TOUCH_SLOP = 12   // px，位移小于它算点击而非拖动

    private val mainHandler = Handler(Looper.getMainLooper())

    private var appCtx: Context? = null
    private var added = false
    private var rootView: LinearLayout? = null
    private var statusView: TextView? = null
    private var pendingText: String? = null

    /** 用户点过 [×] 后置位：本轮队列内不再自动弹出；队列结束复位 */
    @Volatile
    private var dismissedForQueue = false

    fun isPermissionGranted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** App 切后台且任务运行中：显示悬浮条（无权限 / 用户已手动关闭 → 不弹） */
    fun showForQueue(ctx: Context) {
        if (dismissedForQueue) return
        if (!isPermissionGranted(ctx)) return
        appCtx = ctx.applicationContext
        mainHandler.post { attach() }
    }

    /** 回前台 / 队列结束：收起（不计入手动关闭） */
    fun hide() {
        mainHandler.post { detach() }
    }

    /** 更新状态文字（任意线程可调；未显示时记住，attach 后立即可见） */
    fun update(text: String) {
        pendingText = text
        mainHandler.post { statusView?.text = text }
    }

    /** 队列结束：收起并复位手动关闭标志（下一轮队列恢复自动弹出） */
    fun onQueueFinished() {
        dismissedForQueue = false
        hide()
    }

    // ------------------------------------------------------------------

    private fun attach() {
        val ctx = appCtx ?: return
        if (added) {
            statusView?.text = pendingText
            return
        }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        fun chip(label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
            setOnClickListener { onClick() }
        }

        val status = TextView(ctx).apply {
            text = pendingText ?: "MaaWH 运行中"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8))
        }
        statusView = status

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 18).toFloat()
                setColor(0xCC202124.toInt())
            }
            setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
        }
        box.addView(status)
        box.addView(chip("■") {
            QueueRunner.requestStopCurrent()
            Toast.makeText(ctx, "已请求停止任务", Toast.LENGTH_SHORT).show()
        })
        box.addView(chip("×") {
            dismissedForQueue = true
            detach()
        })
        rootView = box

        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            // NOT_FOCUSABLE：不抢焦点（背后 App 的返回键正常）；NOT_TOUCH_MODAL：条外触摸穿透
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = dp(ctx, 24)
            y = dp(ctx, 120)
        }

        // 拖动 + 点击判定：MOVE 跟手移动，抬起时位移小于阈值视为点击 → 回 MaaWH
        var downRawX = 0f; var downRawY = 0f
        var startLpX = 0; var startLpY = 0
        var moved = false
        box.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startLpX = lp.x; startLpY = lp.y
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (moved || Math.abs(dx) > TOUCH_SLOP || Math.abs(dy) > TOUCH_SLOP) {
                        moved = true
                        lp.x = startLpX + dx
                        lp.y = startLpY + dy
                        runCatching { wm.updateViewLayout(box, lp) }
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
            wm.addView(box, lp)
            added = true
        } catch (e: Throwable) {
            added = false
        }
    }

    private fun detach() {
        val ctx = appCtx ?: return
        val view = rootView ?: return
        if (!added) return
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (ignored: Throwable) {
        }
        added = false
        rootView = null
        statusView = null
    }

    private fun backToApp(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
