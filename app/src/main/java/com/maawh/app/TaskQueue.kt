package com.maawh.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 队列中的一个任务。
 * 任务来自任务包清单（interface.json）的 task[]，参数选择存在 selection 里，
 * 运行时由 TaskPack.buildOverride 转成引擎的 pipeline_override。
 */
data class TaskItem(
    val pack: String,
    val name: String,                       // 任务清单里的 name（唯一标识）
    val entry: String,                      // pipeline 入口节点名
    val label: String = name,               // 界面显示名
    val options: List<String> = emptyList(),// 该任务可配置的 option key 列表
    val selection: TaskPack.Selection = TaskPack.Selection()
) {
    /** 列表里显示的参数摘要，如「次数 3」「办公室物资购买 是」 */
    var summary: String = ""

    val display: String
        get() = if (summary.isBlank()) label else "$label · $summary"
}

/** 任务列表：勾选=参与运行；右侧 ✕ 删除；单击行=编辑参数 */
class TaskQueueAdapter(
    private val items: MutableList<TaskItem>,
    private val enabled: List<Boolean>,
    private val onToggle: (Int, Boolean) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<TaskQueueAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.itemCheck)
        val name: TextView = v.findViewById(R.id.itemName)
        val count: TextView = v.findViewById(R.id.itemCount)
        val del: TextView = v.findViewById(R.id.itemDel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tasklist, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.label
        holder.count.text = item.summary
        // 先按数据写入勾选状态（摘掉监听避免 setChecked 误触发回调），
        // 否则 RecyclerView 复用 ViewHolder 时勾选显示与 enabled 数据脱节
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = enabled.getOrElse(position) { false }
        holder.check.setOnCheckedChangeListener { _, checked -> onToggle(position, checked) }
        holder.del.setOnClickListener { onDelete(position) }
        holder.itemView.setOnClickListener { onSelect(position) }
    }

    override fun getItemCount() = items.size

    fun onMove(from: Int, to: Int) {
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
    }
}
