package com.motohud.ble.ui.navi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.R
import com.motohud.ble.amap.TipItem

/**
 * POI 搜索结果适配器（高德输入提示）。
 * 点击 → 由 NavigationFragment 在主页弹出地点卡。
 *
 * [originProvider] 提供当前定位，用来把"距离"补成"方位 + 距离"：
 * 只知道 3.2 公里，用户还得自己判断在哪个方向、顺不顺路；
 * "东南 3.2 公里"才是完整回答。没定位时退化为只有距离。
 */
class TipAdapter(
    private val originProvider: () -> Pair<Double, Double>?,
    private val onClick: (TipItem) -> Unit
) : ListAdapter<TipItem, TipAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tip, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tip = getItem(position)
        holder.name.text = tip.name
        // 副标题：**距离必须排在最前**。
        // 列表按距离排序，地址又长又容易被 ellipsize 吃掉，
        // 距离放末尾等于排序白做了（实测"君亭酒店"的 7.6km 直接被截掉）。
        val parts = mutableListOf<String>()
        tip.distanceM?.let { m ->
            val dir = originProvider()?.let {
                com.motohud.ble.navi.CoordConverter.direction8(
                    it.first, it.second, tip.latitude, tip.longitude
                )
            }
            parts += if (dir != null) "$dir ${formatDistance(m)}" else formatDistance(m)
        }
        if (tip.district.isNotBlank()) parts += tip.district
        if (tip.address.isNotBlank()) parts += tip.address
        holder.address.text = parts.joinToString(" · ")
            .ifEmpty { String.format(java.util.Locale.US, "%.4f, %.4f", tip.latitude, tip.longitude) }
        holder.itemView.setOnClickListener { onClick(tip) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_tip_name)
        val address: TextView = view.findViewById(R.id.tv_tip_address)
    }

    /** 距离格式化：1km 内给米，10km 内给一位小数，再远只给整数（与导航页「公里」说法统一） */
    private fun formatDistance(m: Int): String = when {
        m < 1000 -> "${m}米"
        m < 10_000 -> String.format(java.util.Locale.US, "%.1f公里", m / 1000.0)
        else -> "${m / 1000}公里"
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TipItem>() {
            override fun areItemsTheSame(a: TipItem, b: TipItem) =
                a.name == b.name && a.latitude == b.latitude && a.longitude == b.longitude

            override fun areContentsTheSame(a: TipItem, b: TipItem) = a == b
        }
    }
}
