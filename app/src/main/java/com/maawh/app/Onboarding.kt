package com.maawh.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * 新手引导完成标记（独立于任务配置存档 QueueStore：引导是 App 级状态，跟任务包无关）。
 * 存「看完时的引导版本」，以后步骤改版把 GUIDE_VERSION +1 就能重新弹一次。
 */
object GuideStore {
    private const val PREF = "maawh_guide"
    private const val KEY_VERSION = "done_version"

    /** 步骤内容或顺序改版时 +1，老用户会再看到一次引导 */
    const val GUIDE_VERSION = 1

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isDone(ctx: Context) = prefs(ctx).getInt(KEY_VERSION, 0) >= GUIDE_VERSION

    fun markDone(ctx: Context) {
        try {
            prefs(ctx).edit().putInt(KEY_VERSION, GUIDE_VERSION).apply()
        } catch (_: Throwable) {
        }
    }
}

/**
 * 新手引导（对标 MAA-Meow 的聚光灯引导，View 体系实现，不引新依赖）：
 * 全屏半透明遮罩在目标控件处挖一个圆角洞 + 描边高亮，讲解卡片贴着洞摆放
 * （优先洞下方 → 上方 → 左右 → 都放不下则贴空间更大一侧允许压住洞），无靶点时卡片居中。
 * 跨页步骤自动切页；遮罩吞掉全部触摸（洞内也不透），只有卡片按钮可点；返回键 = 上一步/退出。
 * 首次启动自动弹出（GuideStore），设置页「查看新手引导」可重看。
 */
class Onboarding(
    private val activity: ComponentActivity,
    private val steps: List<Step>,
    /** 引导要求切到某页（0 主页 / 1 日志 / 2 设置），随步骤自动切 */
    private val showPage: (Int) -> Unit,
    /** 开始前把主页归位到 一键长草 + 队列视图（配置管理模式下靶点面板不存在） */
    private val ensureHome: () -> Unit,
    /** 引导结束（看完或跳过）回调 */
    private val onFinished: () -> Unit,
) {

    /** 一步引导；target 惰性求值（切页后才布局），null 或视图不可见时不挖洞、卡片居中 */
    data class Step(
        val page: Int,
        val target: (() -> View?)?,
        val emoji: String,
        val title: String,
        val body: String,
    )

    /** 洞与遮罩透明度上限，比普通弹窗深才衬得出聚光灯 */
    private val scrimMax = 0.72f

    private val density = activity.resources.displayMetrics.density
    private var stepIndex = 0
    private var active = false
    private lateinit var root: FrameLayout
    private lateinit var spotlight: SpotlightView
    private var card: MaterialCardView? = null
    private var backCallback: androidx.activity.OnBackPressedCallback? = null

    /** 布局变化（切页/旋转/面板收展）时把洞 snap 到靶点当前位置；translation 不触发 layout，无递归 */
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { snapToTarget() }

    val isActive: Boolean get() = active

    fun start() {
        if (active) return
        ensureHome()
        stepIndex = 0
        active = true
        buildOverlay()
        backCallback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stepIndex > 0) showStep(stepIndex - 1) else finish()
            }
        }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }
        showStep(0)
    }

    /** 结束（看完最后一步 / 点跳过 / 第一步按返回），标记已完成 */
    fun finish() {
        if (!active) return
        active = false
        GuideStore.markDone(activity)
        (root.parent as? ViewGroup)?.removeView(root)
        root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        backCallback?.isEnabled = false
        backCallback = null
        onFinished()
    }

    private fun buildOverlay() {
        root = FrameLayout(activity)
        spotlight = SpotlightView()
        // 遮罩吞掉一切触摸（洞内也不透，只有卡片可点）
        spotlight.isClickable = true
        root.addView(spotlight, FrameLayout.LayoutParams(-1, -1))
        activity.addContentView(root, FrameLayout.LayoutParams(-1, -1))
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    private fun showStep(i: Int) {
        stepIndex = i
        val step = steps[i]
        showPage(step.page)
        card?.let { root.removeView(it) }
        card = null
        spotlight.cancelAnim()
        // 等切页后的下一帧布局完成再取靶点位置（layout traversal 带同步屏障，先于普通消息执行）
        root.post { place(step) }
    }

    private fun place(step: Step) {
        if (!active) return
        val target = step.target?.invoke()
        val hole = if (target != null && target.isShown && target.width > 0) {
            rectInOverlay(target, dp(8))
        } else null
        showCard(step, hole)
        spotlight.animateTo(hole, 300)
    }

    /** 靶点相对引导层的矩形（同窗口坐标系，减去引导层自身偏移），pad 外扩一圈 */
    private fun rectInOverlay(v: View, pad: Float): RectF {
        val loc = IntArray(2).also(v::getLocationInWindow)
        val rloc = IntArray(2).also(root::getLocationInWindow)
        val x = (loc[0] - rloc[0]).toFloat()
        val y = (loc[1] - rloc[1]).toFloat()
        return RectF(x - pad, y - pad, x + v.width + pad, y + v.height + pad)
    }

    private fun showCard(step: Step, hole: RectF?) {
        val c = buildCard(step)
        card = c
        val lp = FrameLayout.LayoutParams(cardWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(c, lp)
        c.measure(
            View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(
                (root.height - dp(32)).toInt().coerceAtLeast(0),
                View.MeasureSpec.AT_MOST
            ),
        )
        positionCard(c, hole)
    }

    private fun positionCard(c: View, hole: RectF?) {
        val (x, y) = resolvePos(hole, c.measuredWidth, c.measuredHeight)
        c.translationX = x
        c.translationY = y
    }

    /**
     * 卡片相对洞的摆放（移植 MAA-Meow OnboardingPlacement 的策略）：
     * 优先洞下方，其次上方；上下都放不下试左右；仍不够则贴空间更大的一侧、允许压住洞；无洞居中。
     */
    private fun resolvePos(hole: RectF?, cardW: Int, cardH: Int): Pair<Float, Float> {
        val mL = dp(16)
        val aL = mL
        val aT = mL
        val aR = root.width - mL
        val aB = root.height - mL
        if (hole == null) {
            return (aL + ((aR - aL - cardW) / 2f).coerceAtLeast(0f)) to
                    (aT + ((aB - aT - cardH) / 2f).coerceAtLeast(0f))
        }
        val gap = dp(12)
        val cx = (hole.centerX() - cardW / 2f).coerceIn(aL, (aR - cardW).coerceAtLeast(aL))
        val below = aB - hole.bottom
        val above = hole.top - aT
        val needV = cardH + gap
        if (below >= needV) return cx to hole.bottom + gap
        if (above >= needV) return cx to hole.top - gap - cardH
        val cy = (hole.centerY() - cardH / 2f).coerceIn(aT, (aB - cardH).coerceAtLeast(aT))
        val needH = cardW + gap
        if (aR - hole.right >= needH) return hole.right + gap to cy
        if (hole.left - aL >= needH) return hole.left - gap - cardW to cy
        val y = if (below >= above) aB - cardH else aT
        return cx to y.coerceIn(aT, (aB - cardH).coerceAtLeast(aT))
    }

    private fun cardWidth(): Int = minOf(dp(400), root.width - dp(32)).toInt().coerceAtLeast(120)

    private fun buildCard(step: Step): MaterialCardView {
        val ctx = activity
        val card = MaterialCardView(ctx).apply {
            radius = dp(16)
            setCardBackgroundColor(Color.parseColor("#1B2230"))
            cardElevation = dp(8)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18).toInt(), dp(14).toInt(), dp(18).toInt(), dp(10).toInt())
        }

        // 头部：emoji 图标 + 标题 / 步数
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(ctx).apply { text = step.emoji; textSize = 24f },
            LinearLayout.LayoutParams(-2, -2)
        )
        val titles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(ctx).apply {
                    text = step.title
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
            addView(
                TextView(ctx).apply {
                    text = ctx.getString(R.string.guide_counter, stepIndex + 1, steps.size)
                    textSize = 11f
                    setTextColor(0xFF8E9AAB.toInt())
                }
            )
        }
        head.addView(titles, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10).toInt() })
        col.addView(head)

        // 正文
        col.addView(
            TextView(ctx).apply {
                text = step.body
                textSize = 13f
                setTextColor(0xFFC6CEDB.toInt())
                setLineSpacing(dp(2), 1f)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10).toInt() }
        )

        // 底部按钮：跳过（最后一步没有）| 弹性 | 上一步 下一步/完成
        val isLast = stepIndex == steps.lastIndex
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (!isLast) btnRow.addView(flatBtn(ctx.getString(R.string.guide_skip)) { finish() })
        btnRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        if (stepIndex > 0) {
            btnRow.addView(flatBtn(ctx.getString(R.string.guide_prev)) { showStep(stepIndex - 1) })
        }
        btnRow.addView(
            mainBtn(ctx.getString(if (isLast) R.string.guide_done else R.string.guide_next)) {
                if (isLast) finish() else showStep(stepIndex + 1)
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4).toInt() }
        )
        col.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8).toInt() })

        card.addView(col)
        return card
    }

    private fun flatBtn(text: String, onClick: () -> Unit) = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF8E9AAB.toInt())
        val p = dp(10).toInt()
        setPadding(p, dp(6).toInt(), p, dp(6).toInt())
        setOnClickListener { onClick() }
    }

    private fun mainBtn(text: String, onClick: () -> Unit) = MaterialButton(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        insetTop = 0
        insetBottom = 0
        setPadding(dp(16).toInt(), 0, dp(16).toInt(), 0)
        setOnClickListener { onClick() }
    }

    private fun snapToTarget() {
        if (!active) return
        val step = steps.getOrNull(stepIndex) ?: return
        val target = step.target?.invoke() ?: return
        if (!target.isShown || target.width <= 0) return
        val hole = rectInOverlay(target, dp(8))
        if (spotlight.setHoleIfChanged(hole)) {
            card?.let { positionCard(it, hole) }
        }
    }

    private fun dp(v: Float): Float = v * density
    private fun dp(v: Int): Float = v * density

    private fun lerp(a: RectF, b: RectF, f: Float) = RectF(
        a.left + (b.left - a.left) * f,
        a.top + (b.top - a.top) * f,
        a.right + (b.right - a.right) * f,
        a.bottom + (b.bottom - a.bottom) * f,
    )

    private fun RectF.inflateBy(d: Float) = RectF(left - d, top - d, right + d, bottom + d)

    /**
     * 聚光灯遮罩：saveLayer 离屏画半透明底，再用 CLEAR 挖洞（洞随动画渐现）+ 主题色描边。
     * 硬件加速下 saveLayer 内的 xfermode 可用，无需软件层。
     */
    private inner class SpotlightView : View(activity) {
        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            color = 0xFF4C9AFF.toInt()
            alpha = 230
        }
        private var anim: ValueAnimator? = null

        var hole: RectF? = null
            private set
        var scrim: Float = 0f
            private set

        /** 洞从「大一圈」收拢进目标（首次）或平滑移动到新目标（换步）；无靶点时洞消失、遮罩保持 */
        fun animateTo(target: RectF?, duration: Long) {
            anim?.cancel()
            val to = target?.let { RectF(it) }
            val from = hole?.let { RectF(it) } ?: to?.inflateBy(dp(24))
            val fromScrim = scrim
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    hole = if (from != null && to != null) lerp(from, to, f) else to
                    scrim = fromScrim + (scrimMax - fromScrim) * f
                    invalidate()
                }
            }.also { it.start() }
        }

        fun cancelAnim() {
            anim?.cancel()
        }

        /** 布局变化时直接对齐（不动画）；返回是否真的动了，避免卡片重复摆位 */
        fun setHoleIfChanged(newHole: RectF): Boolean {
            if (hole != null && hole == newHole) return false
            hole = RectF(newHole)
            invalidate()
            return true
        }

        override fun onDraw(c: Canvas) {
            val sc = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            c.drawColor(Color.argb((scrim * 255).toInt(), 0, 0, 0))
            hole?.let {
                val r = dp(16)
                c.drawRoundRect(it, r, r, clearPaint)
                c.drawRoundRect(it, r, r, strokePaint)
            }
            c.restoreToCount(sc)
        }
    }
}
