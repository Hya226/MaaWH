package com.maawh.app

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.ScrollView
import android.widget.TextView

/**
 * 任务日志视图（对标 maameow 的任务日志）。
 *
 * 每行 = 时间 + 级别徽标 + 正文，级别决定颜色：
 *   TRACE 灰（引擎逐节点细节）· INFO 蓝（流程/环境信息）· SUCCESS 绿 · WRN 橙 · ERR 红
 * 渲染按固定节奏合并（引擎节点日志很密，逐条 setText 会 O(n²) 卡顿），
 * 并保留最近 [MAX_LINES] 行。
 */
enum class LogLevel { TRACE, INFO, SUCCESS, WRN, ERR }

class TaskLogView(
    private val tv: TextView,
    private val scroll: ScrollView,
    private val colorOf: (LogLevel) -> Int
) {

    private data class Entry(val time: String, val level: LogLevel, val msg: String)

    private val buf = ArrayDeque<Entry>()
    private var renderPosted = false

    fun add(time: String, level: LogLevel, msg: String) {
        buf.addLast(Entry(time, level, msg))
        while (buf.size > MAX_LINES) buf.removeFirst()
        scheduleRender()
    }

    fun clear() {
        buf.clear()
        render()
    }

    /** 合并 200ms 内的连续写入，避免密集日志把 UI 拖死 */
    private fun scheduleRender() {
        if (renderPosted) return
        renderPosted = true
        tv.postDelayed({ renderPosted = false; render() }, 200)
    }

    private fun render() {
        val sb = SpannableStringBuilder()
        for (e in buf) {
            val lineStart = sb.length
            sb.append(e.time).append(' ')
            // 级别徽标：固定宽度便于竖向对齐（参照 maameow 的 INFO/SUCCESS 标签）
            val badgeStart = sb.length
            sb.append(e.level.name.padEnd(7))
            sb.setSpan(
                ForegroundColorSpan(colorOf(e.level)), badgeStart, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append(' ')
            val bodyStart = sb.length
            sb.append(e.msg).append('\n')
            sb.setSpan(
                ForegroundColorSpan(colorOf(e.level)), bodyStart, bodyStart + e.msg.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            // 时间用弱色（整行兜底，徽标/正文再覆盖中间段）
            sb.setSpan(
                ForegroundColorSpan(colorOf(LogLevel.TRACE)), lineStart, badgeStart,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tv.setText(sb)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val MAX_LINES = 400
    }
}
