package com.maawh.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import java.util.Locale
import kotlin.math.abs

/**
 * 垂直循环滚轮选择器（对标 maameow 定时编辑的「选择时间」滚轮，View 体系不引库）。
 *
 * 只负责一列数字：拖动/fling、松手 snap 到最近刻度、中心行最亮向上下渐隐（alpha 平方衰减）；
 * 中心高亮胶囊由父容器垫一个圆角 View 实现本 View 不画（见 MainActivity.showWheelTimePicker）。
 * 刻度循环：小时 0..23、分钟 0..59 都可无限向两个方向滚（与系统转盘习惯一致）。
 */
class WheelView(context: Context) : View(context) {

    /** 刻度总数：显示 0..count-1（小时传 24、分钟传 60） */
    var count = 24

    /** 最近一次落定的选中值（snap 落定后更新；实时取值用 [currentValue]） */
    var value = 0
        private set

    /** 选中值变化回调（snap 落定时发出；「确定」按钮直接读 [currentValue] 不依赖它） */
    var onValueChanged: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val itemH = density * ITEM_H_DP
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = density * TEXT_DP
        color = 0xFFFFFFFF.toInt()
    }
    private val minFlingPx = density * 300f

    /** 连续滚动值（可越出 0..count-1，绘制与取值时按 count 取模） */
    private var valueF = 0f
    private var lastY = 0f
    private var dragging = false
    private var flinging = false
    private var flingLast = 0f
    private var tracker: VelocityTracker? = null
    private var snapAnim: ValueAnimator? = null
    private val scroller = OverScroller(context)

    /** 设置选中值（重置滚动状态），不触发回调 */
    fun setValue(v: Int) {
        snapAnim?.cancel()
        scroller.forceFinished(true)
        flinging = false
        value = ((v.toLong() % count) + count).toInt() % count
        valueF = value.toFloat()
        invalidate()
    }

    /** 实时选中值：snap 动画没跑完（用户直接点「确定」）也取的是目标刻度 */
    fun currentValue(): Int =
        (((Math.round(valueF).toLong() % count) + count) % count).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val half = height / 2f
        val base = Math.round(valueF).toFloat()
        for (k in -4..4) {
            val idx = base + k
            val y = cy + (idx - valueF) * itemH
            if (y < -itemH || y > height + itemH) continue
            val shown = ((idx.toLong() % count) + count) % count
            // 中心最亮，向上下按距离平方渐隐（截图同款：相邻行中灰、边缘更暗）
            val t = (abs(y - cy) / half).coerceIn(0f, 1f)
            paint.alpha = ((1f - t * t * 0.9f) * 255f).toInt().coerceIn(14, 255)
            canvas.drawText(
                String.format(Locale.US, "%02d", shown),
                width / 2f,
                y - (paint.ascent() + paint.descent()) / 2f,
                paint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                snapAnim?.cancel()
                scroller.forceFinished(true)
                flinging = false
                dragging = true
                lastY = event.y
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(event)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                // 上滑（y 减小）→ 数字增大，与系统转盘方向一致
                valueF += (lastY - event.y) / itemH
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(event)
                dragging = false
                val vy = tracker?.let {
                    it.computeCurrentVelocity(1000)
                    it.yVelocity
                } ?: 0f
                tracker?.recycle()
                tracker = null
                if (abs(vy) > minFlingPx) {
                    flinging = true
                    flingLast = 0f
                    scroller.fling(0, 0, 0, -vy.toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
                    postInvalidateOnAnimation()
                } else {
                    snapToNearest()
                }
            }
        }
        return true
    }

    override fun computeScroll() {
        if (flinging && scroller.computeScrollOffset()) {
            valueF += (scroller.currY - flingLast) / itemH
            flingLast = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        } else if (flinging) {
            flinging = false
            snapToNearest()
        }
    }

    /** 滚到最近刻度（decelerate 短动画），落定后结算选中值 */
    private fun snapToNearest() {
        val target = Math.round(valueF).toFloat()
        if (target == valueF) {
            settle()
            return
        }
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofFloat(valueF, target).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                valueF = a.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = settle()
            })
            start()
        }
    }

    private fun settle() {
        valueF = Math.round(valueF).toFloat()
        val nv = currentValue()
        if (nv != value) {
            value = nv
            onValueChanged?.invoke(nv)
        }
        invalidate()
    }

    companion object {
        private const val ITEM_H_DP = 52f
        private const val TEXT_DP = 23f
    }
}
