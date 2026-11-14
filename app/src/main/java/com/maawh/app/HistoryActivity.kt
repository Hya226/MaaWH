package com.maawh.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** 历史日志浏览器：每次队列/抓卡运行的日志文件列表（files/logs/），可查看/删除/分享/清理 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView

    /** 界面整体缩放（与主界面同一份设置，见 DisplayScale） */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(DisplayScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_app))
        }

        // 顶栏：← 历史日志 [分享] [清理30天前]
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        bar.addView(topText("←", R.color.text_primary, 20f) { finish() })
        bar.addView(topText("历史日志", R.color.text_primary, 18f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(topText("分享", R.color.accent, 14f) { shareLatest() })
        bar.addView(topText("清理30天前", R.color.accent, 14f) { purgeOld() })
        root.addView(bar)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(10))
        }
        root.addView(
            ScrollView(this).apply {
                addView(listContainer)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )
        emptyHint = TextView(this).apply {
            text = "还没有历史日志。跑一次队列或抽卡后会在这里留下记录。"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(dp(10), dp(20), dp(10), 0)
        }
        listContainer.addView(emptyHint)

        setContentView(root)
        refresh()
    }

    /**
     * 禁用关闭过渡动画：系统默认关闭动画的几百 ms 窗口期内，底层 MainActivity 的
     * 第一次触摸被系统吞掉，返回后手快点【历史】要「点两次才能点开」（真机复现实锤）。
     * 进入动画已由 showHistory() 的 ActivityOptions(0,0) 禁用。
     */
    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun refresh() {
        listContainer.removeAllViews()
        listContainer.addView(emptyHint)
        renderArchiveEvents()
        val files = RunLogStore.list(this)
        emptyHint.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        for (f in files) listContainer.addView(buildRow(f))
    }

    /**
     * 存档持久化事件（写盘失败/解析失败，跨会话）。日志页是内存环形缓冲重启即清，
     * 「第二天发现配置/定时丢了」时这里就是唯一的现场记录。有记录才显示。
     */
    private fun renderArchiveEvents() {
        val errors = HistoryStore.load(this)
            .filter { it.type == HistoryStore.TYPE_SAVE_ERROR }
            .take(10)
        if (errors.isEmpty()) return
        listContainer.addView(TextView(this).apply {
            text = "存档事件"
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(dp(2), dp(10), dp(2), dp(2))
        })
        for (e in errors) {
            listContainer.addView(TextView(this).apply {
                text = e.line()
                setTextColor(getColor(R.color.err_red))
                textSize = 12f
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })
        }
    }

    private fun buildRow(f: File): View {
        val sizeKb = (f.length() / 1024).coerceAtLeast(1)
        val header = runCatching { f.useLines { it.firstOrNull() ?: "" } }.getOrDefault("")
            .removePrefix("=== ").removeSuffix(" ===")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { showLogContent(f) }
        }
        // 第一行：时间 + 🗑
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(this@HistoryActivity).apply {
                    text = RunLogStore.displayTime(f)
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 15f
                    paint.isFakeBoldText = true
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            addView(
                TextView(this@HistoryActivity).apply {
                    text = "🗑"
                    textSize = 15f
                    setPadding(dp(8), 0, dp(4), 0)
                    setOnClickListener {
                        runCatching { RunLogStore.delete(f) }
                        refresh()
                    }
                }
            )
        })
        // 第二行：首行元信息（标签 · 时间 · N 个任务）+ 大小
        row.addView(
            TextView(this).apply {
                text = "$header ｜ ${sizeKb} KB"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        )
        return row
    }

    private fun showLogContent(f: File) {
        val content = runCatching { f.readText() }.getOrDefault("(读取失败)")
        val view = ScrollView(this).apply {
            addView(
                TextView(this@HistoryActivity).apply {
                    text = content
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
            )
        }
        AlertDialog.Builder(this)
            .setTitle(RunLogStore.displayTime(f))
            .setView(view)
            .setPositiveButton("分享") { _, _ -> shareFile(f) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun shareLatest() {
        val f = RunLogStore.list(this).firstOrNull() ?: run { toast("没有可分享的日志"); return }
        shareFile(f)
    }

    private fun shareFile(f: File) {
        val text = runCatching { f.readText() }.getOrDefault("")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "MaaWH 日志 ${f.name}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "分享日志"))
    }

    private fun purgeOld() {
        val n = RunLogStore.purgeOlderThan30Days(this)
        refresh()
        android.widget.Toast.makeText(this, "已清理 $n 个日志", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun topText(
        text: String,
        colorRes: Int,
        sizeSp: Float,
        bold: Boolean = false,
        onClick: (() -> Unit)? = null
    ): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(colorRes))
        textSize = sizeSp
        paint.isFakeBoldText = bold
        setPadding(dp(8), dp(4), dp(8), dp(4))
        if (onClick != null) {
            background = getDrawable(R.drawable.bg_card)
            setOnClickListener { onClick() }
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
