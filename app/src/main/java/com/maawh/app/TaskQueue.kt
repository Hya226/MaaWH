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

/** 任务列表：勾选=参与运行；右侧 ✕ 删除；单击行=编辑参数；长按行名字/空白=拖动排序 */
class TaskQueueAdapter(
    private val items: MutableList<TaskItem>,
    private val enabled: List<Boolean>,
    private val onToggle: (Int, Boolean) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onSelect: (Int) -> Unit,
    private val onStartDrag: (VH) -> Unit = {}
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
        // 回调里必须用 bindingAdapterPosition：notifyItemRemoved/Moved 不会让未复用的行
        // 重新 bind，闭包里的 bind position 是旧值，删错任务/勾错行
        fun curPos(): Int = holder.bindingAdapterPosition
        // 先按数据写入勾选状态（摘掉监听避免 setChecked 误触发回调），
        // 否则 RecyclerView 复用 ViewHolder 时勾选显示与 enabled 数据脱节
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = enabled.getOrElse(position) { false }
        holder.check.setOnCheckedChangeListener { _, checked ->
            val pos = curPos()
            if (pos != RecyclerView.NO_POSITION) onToggle(pos, checked)
        }
        holder.del.setOnClickListener {
            val pos = curPos()
            if (pos != RecyclerView.NO_POSITION) onDelete(pos)
        }
        holder.itemView.setOnClickListener {
            val pos = curPos()
            if (pos != RecyclerView.NO_POSITION) onSelect(pos)
        }
        // 长按排序挂在行根上。✕/勾选框是 clickable child，长按不会冒泡到这里，长按它们
        // 不触发拖动（配合 MainActivity 关掉 ItemTouchHelper 的全局长按拖动——否则长按
        // ✕ 400ms 就进了拖动态，点击被 ACTION_CANCEL 吞掉，表现为「点删除没反应」）
        holder.itemView.setOnLongClickListener {
            if (curPos() != RecyclerView.NO_POSITION) onStartDrag(holder)
            true
        }
    }

    override fun getItemCount() = items.size

    fun onMove(from: Int, to: Int) {
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
    }
}
