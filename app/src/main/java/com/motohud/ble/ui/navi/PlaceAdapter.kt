package com.motohud.ble.ui.navi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.R
import com.motohud.ble.data.SavedPlaceEntity
import com.motohud.ble.navi.CoordConverter

/**
 * 常用地址卡片适配器。
 * 点击卡片 → 一键开始投屏导航；编辑/删除按钮走回调。
 */
class PlaceAdapter(
    private val onClick: (SavedPlaceEntity) -> Unit,
    private val onEdit: (SavedPlaceEntity) -> Unit,
    private val onDelete: (SavedPlaceEntity) -> Unit
) : ListAdapter<SavedPlaceEntity, PlaceAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_place, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val place = getItem(position)
        holder.name.text = place.name
        // 普通用户无需看到经纬度，地点卡上不再展示坐标（有备注时看备注）
        holder.coord.visibility = View.GONE
        holder.icon.setImageResource(
            when (place.type) {
                SavedPlaceEntity.TYPE_HOME -> R.drawable.ic_place_home
                SavedPlaceEntity.TYPE_WORK -> R.drawable.ic_place_work
                else -> R.drawable.ic_place_other
            }
        )
        holder.remark.text = place.remark
        holder.remark.visibility = if (place.remark.isBlank()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick(place) }
        holder.btnEdit.setOnClickListener { onEdit(place) }
        holder.btnDelete.setOnClickListener { onDelete(place) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_place_icon)
        val name: TextView = view.findViewById(R.id.tv_place_name)
        val coord: TextView = view.findViewById(R.id.tv_place_coord)
        val remark: TextView = view.findViewById(R.id.tv_place_remark)
        val btnEdit: ImageButton = view.findViewById(R.id.btn_place_edit)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_place_delete)
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SavedPlaceEntity>() {
            override fun areItemsTheSame(a: SavedPlaceEntity, b: SavedPlaceEntity) = a.id == b.id
            override fun areContentsTheSame(a: SavedPlaceEntity, b: SavedPlaceEntity) = a == b
        }
    }
}
