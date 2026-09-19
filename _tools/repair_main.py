# -*- coding: utf-8 -*-
# 修复 MainActivity：把误删的函数从 git HEAD 还原（含今天的修改），并把公告块挪回独立函数
import subprocess

DAM = r"app\src\main\java\com\maawh\app\MainActivity.kt"
dam = open(DAM, encoding="utf-8").read()

head = subprocess.run(
    ["git", "show", "HEAD:app/src/main/java/com/maawh/app/MainActivity.kt"],
    capture_output=True
).stdout.decode("utf-8")


def extract(src, start_marker, end_marker):
    a = src.index(start_marker)
    b = src.index(end_marker, a)
    return src[a:b]


# 旧函数（HEAD 原文）
guide_block = extract(head, "    private fun buildGuideSteps", "    private fun takeScreenshot()")
preview_block = extract(head, "    private fun takeScreenshot()", "    private fun updateInfoTexts()")
tools_block = extract(head, "    private fun updateInfoTexts()", "    private fun log(msg: String")

# HEAD 旧函数打补丁（今天的修改）
guide_block = guide_block.replace(
    "Onboarding.Step(1, { binding.scrollLog }", "Onboarding.Step(2, { binding.scrollLog }")
guide_block = guide_block.replace(
    "Onboarding.Step(2, { binding.cardStatus }", "Onboarding.Step(3, { binding.cardStatus }")
guide_block = guide_block.replace(
    "            1 -> switchTo(binding.panelLog)", "            2 -> switchTo(binding.panelLog)")
guide_block = guide_block.replace(
    "            2 -> switchTo(binding.panelSettings)", "            3 -> switchTo(binding.panelSettings)")
guide_block = guide_block.replace(
    "页序 = menu 顺序）", "页序 = menu 顺序：主页/抽卡/日志/设置）")
guide_block = guide_block.replace(
    "        listOf(binding.panelHome, binding.panelLog, binding.panelSettings)",
    "        listOf(binding.panelHome, binding.panelLog, binding.panelSettings, binding.panelGacha)")
tools_block = tools_block.replace(
    "binding.tvAbout.text = getString(R.string.about_text)",
    'binding.tvAbout.text = getString(R.string.about_text)\n'
    '        binding.tvAboutVersion.text = runCatching {\n'
    '            packageManager.getPackageInfo(packageName, 0).versionName\n'
    '        }.getOrNull() ?: "0.1.0"')

# ---------- 2) buildPoolCard 尾部（误删部分还原） ----------
pool_tail = """        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        // 头部：池名 + 总抽数/特出/平均
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cell(pool, R.color.text_primary, 15f, bold = true, weight = 1f))
            addView(cell("总抽数 ${st.total} · 特出 ${st.teCount} · 平均 ${st.avgText}", R.color.text_secondary, 12f))
        })
        // 当前垫抽
        card.addView(cell("当前垫抽：${st.dian} 抽", if (st.dian >= 50) R.color.warn_orange else R.color.text_primary, 13f).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        })
        // 特出明细按小类（招集列）分组成子卡片
        val groups = LinkedHashMap<String, MutableList<GachaStore.Record>>()
        for (r in rs) {
            val b = if (r.banner.isBlank()) "未识别" else r.banner
            groups.getOrPut(b) { mutableListOf() }.add(r)
        }
        val te = GachaStore.teListWithCost(rs)
        for ((banner, list) in groups) {
            val label = banner.substringAfter('/', banner)
            val sub = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF151C29.toInt())
                    cornerRadius = dp(6).toFloat()
                }
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            sub.addView(cell("『$label』 · ${list.size} 抽", R.color.accent, 13f, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            val teInBanner = te.filter { it.first.banner == banner }
            if (teInBanner.isEmpty()) {
                sub.addView(cell("暂无特出", R.color.text_secondary, 12f).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2) }
                })
            } else {
                for ((rec, cost) in teInBanner) {
                    sub.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(3) }
                        addView(cell("特出", R.color.err_red, 12f, bold = true, weight = 0.8f))
                        addView(cell(rec.name, R.color.text_primary, 12f, weight = 1.4f))
                        addView(cell(cost, R.color.warn_orange, 12f, weight = 0.9f))
                        addView(cell(GachaStore.shortTime(rec.ts), R.color.text_secondary, 12f, weight = 1.1f))
                    })
                }
            }
            card.addView(sub)
        }
        return card
    }
"""

# ---------- 3) buildPoolCard 里的公告块换成它的尾部 ----------
start = dam.index('        // 根布局：头部/勾选/按钮固定，仅正文区滚动')
end_marker = '        dlg.show()\n    }'
end = dam.index(end_marker, start) + len(end_marker)
dam = dam[:start] + pool_tail.rstrip("\n") + "\n    }" + dam[end + len("    }"):]

# ---------- 4) showAnnouncement（独立函数，含自带声明） ----------
show_ann = '''    /**
     * 公告弹窗（圆底图标标题头 + 滚动正文 + 不再显示勾选 + 确认按钮）。
     * auto=true 为启动时自动弹出：该版本公告若已勾选「不再显示」则跳过。
     */
    private fun showAnnouncement(auto: Boolean = false) {
        val f = File(filesDir, "announcement.txt")
        val content = if (f.isFile) f.readText() else getString(R.string.about_announcement_default)
        val hash = Integer.toHexString(content.hashCode())
        val prefs = getSharedPreferences("maawh_announcement", MODE_PRIVATE)
        if (auto && prefs.getString("dismissedHash", "") == hash) return

        fun divider() = View(this).apply {
            setBackgroundColor(0xFF2E3947.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(12); bottomMargin = dp(4) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(getColor(R.color.bg_card))
            }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "📢"
                gravity = Gravity.CENTER
                textSize = 16f
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x333A7BD5)
                }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })
            addView(TextView(this@MainActivity).apply {
                text = "重要公告"
                setTextColor(getColor(R.color.text_primary))
                textSize = 18f
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(12) }
            })
        })
        root.addView(divider())

        val contentView = TextView(this).apply {
            text = content
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(8))
        }
        val contentScroll = ScrollView(this).apply { addView(contentView) }
        root.addView(
            contentScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val dontShow = CheckBox(this).apply {
            text = "下次公告更新前不再显示"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
        }
        var dlgRef: android.app.Dialog? = null
        root.addView(
            dontShow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        root.addView(TextView(this).apply {
            text = "确认"
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(getColor(R.color.accent))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            isClickable = true
            setOnClickListener {
                prefs.edit()
                    .putString("dismissedHash", if (dontShow.isChecked) hash else "")
                    .apply()
                dlgRef?.dismiss()
            }
        })

        val dlg = android.app.Dialog(this)
        dlgRef = dlg
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setLayout(resources.displayMetrics.widthPixels - dp(28), (resources.displayMetrics.heightPixels * 0.78f).toInt())
        }
        dlg.show()
    }

'''

insert_at = dam.index("    private fun log(msg: String")
dam = dam[:insert_at] + show_ann + guide_block + "\n" + preview_block + "\n" + tools_block + "\n\n" + dam[insert_at:]
open(DAM, "w", encoding="utf-8").write(dam)
print("spliced ok")
