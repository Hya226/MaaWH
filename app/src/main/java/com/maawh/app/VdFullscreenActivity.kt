package com.maawh.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.maawh.app.databinding.ActivityVdFullBinding

/** 虚拟屏全屏页：横屏，画面按中心缩小到约90%并居中，右上角 × 退出 */
class VdFullscreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVdFullBinding
    private val handler = Handler(Looper.getMainLooper())
    private var appliedW = 0
    private var appliedH = 0
    private var appliedAspect = 0f

    private val zoom = 0.9f

    /** 界面整体缩放（与主界面同一份设置，见 DisplayScale） */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(DisplayScale.wrap(newBase))
    }

    private val tick = object : Runnable {
        override fun run() {
            VdShared.frame?.let { fitFrame(it) }
            handler.postDelayed(this, 200)
        }
    }

    private fun fitFrame(bmp: android.graphics.Bitmap) {
        binding.root.post {
            val rw = binding.root.width
            val rh = binding.root.height
            if (rw <= 0 || rh <= 0) return@post
            val aspect = bmp.width.toFloat() / bmp.height.toFloat()
            // 尺寸/比例变化时才重算布局
            if (appliedW != rw || appliedH != rh || kotlin.math.abs(appliedAspect - aspect) > 0.01f) {
                appliedW = rw
                appliedH = rh
                appliedAspect = aspect
                var h = rh * zoom
                var w = h * aspect
                if (w > rw) {
                    w = rw.toFloat()
                    h = w / aspect
                }
                val lp = android.widget.FrameLayout.LayoutParams(w.toInt(), h.toInt(),
                    android.view.Gravity.CENTER)
                binding.vdImg.layoutParams = lp
            }
            // 新帧每次都更新画面
            binding.vdImg.setImageBitmap(bmp)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.attributes.layoutInDisplayCutoutMode =
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        binding = ActivityVdFullBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.vdImg).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        binding.vdClose.setOnClickListener { finish() }
        binding.vdImg.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    fullDownX = ev.x; fullDownY = ev.y
                    fullGestureActive = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val (vw, vh) = vdDims()
                    val dist = kotlin.math.hypot(
                        (ev.x - fullDownX).toDouble(), (ev.y - fullDownY).toDouble())
                    if (!fullGestureActive) {
                        if (dist > 10) {
                            fullGestureActive = true
                            val sx = (fullDownX / v.width * vw).toInt().coerceIn(0, vw - 1)
                            val sy = (fullDownY / v.height * vh).toInt().coerceIn(0, vh - 1)
                            ShizukuShell.touchDown(sx, sy)
                            android.util.Log.i("MaaWH", "全屏手势开始 ($sx,$sy)")
                        }
                    } else {
                        val mx = (ev.x / v.width * vw).toInt().coerceIn(0, vw - 1)
                        val my = (ev.y / v.height * vh).toInt().coerceIn(0, vh - 1)
                        ShizukuShell.touchMove(mx, my)
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val (vw, vh) = vdDims()
                    if (fullGestureActive) {
                        val ex = (ev.x / v.width * vw).toInt().coerceIn(0, vw - 1)
                        val ey = (ev.y / v.height * vh).toInt().coerceIn(0, vh - 1)
                        ShizukuShell.touchUp(ex, ey)
                        fullGestureActive = false
                        android.util.Log.i("MaaWH", "全屏手势结束 ($ex,$ey)")
                    } else {
                        // 点击：只发虚拟屏 tap
                        val nx = (ev.x / v.width * vw).toInt().coerceIn(0, vw - 1)
                        val ny = (ev.y / v.height * vh).toInt().coerceIn(0, vh - 1)
                        Thread {
                            val r = ShizukuShell.injectTapVD(nx, ny)
                            android.util.Log.i("MaaWH", "全屏注入 ($nx,$ny): $r")
                        }.start()
                    }
                }
            }
            true
        }
        handler.post(tick)
    }

    /** 全屏页坐标基准：展示帧为 VD 帧的 1/2（×2 还原 VD 原生坐标） */
    private fun vdDims(): Pair<Int, Int> {
        val bmp = VdShared.frame
        return Pair(
            if (bmp != null) (bmp.width * 2) else MaaConst.VD_W,
            if (bmp != null) (bmp.height * 2) else MaaConst.VD_H
        )
    }

    private var fullDownX = 0f
    private var fullDownY = 0f
    private var fullGestureActive = false

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
    }
}
