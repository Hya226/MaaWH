package com.maawh.app

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * 微型流式布局：子 View 一行放不下就换行。给「定时编辑」的星期勾选行用——
 * 7 个 CheckBox 用固定单行 LinearLayout，窄屏/页面缩放下会超出对话框宽度
 * （反馈截图实锤：「日」的文字被裁到只剩个框），换行兜底后任何宽度都完整可见。
 */
class FlowLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val hGap = dp(6)  // 项与项横向间距
    private val vGap = dp(6)  // 行与行纵向间距

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var lineH = 0
        var totalH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            measureChild(c, widthMeasureSpec, heightMeasureSpec)
            val w = c.measuredWidth
            if (x > 0 && x + hGap + w > maxW) {  // 本行放不下 → 换行
                x = 0
                totalH += lineH + vGap
                lineH = 0
            }
            if (x > 0) x += hGap
            x += w
            lineH = maxOf(lineH, c.measuredHeight)
        }
        totalH += lineH
        setMeasuredDimension(
            resolveSize(maxW + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(totalH + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val w = c.measuredWidth
            if (x > paddingLeft && x + hGap + w > maxW) {  // 与 onMeasure 同一换行判据
                x = paddingLeft
                y += lineH + vGap
                lineH = 0
            }
            if (x > paddingLeft) x += hGap
            c.layout(x, y, x + w, y + c.measuredHeight)
            x += w
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}
