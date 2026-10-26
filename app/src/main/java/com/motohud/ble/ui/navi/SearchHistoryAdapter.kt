package com.motohud.ble.ui.navi

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.R
import com.motohud.ble.data.SearchHistory

/**
 * 最近搜索列表。
 *
 * 每条右侧有一个删除按钮：历史里总会有"查一次就再也不去"的地方，
 * 没有单条删除就只能整列表清空，那太粗暴。
 */
class SearchHistoryAdapter(
    private val onPick: (SearchHistory.Item) -> Unit,
    private val onDelete: (SearchHistory.Item) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.VH>() {

    private val data = mutableListOf<SearchHistory.Item>()

    fun submit(list: List<SearchHistory.Item>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_history, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = data[position]
        holder.name.text = item.name
        holder.itemView.setOnClickListener { onPick(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val name: android.widget.TextView = v.findViewById(R.id.tv_history_name)
        val delete: android.view.View = v.findViewById(R.id.iv_history_delete)
    }
}
