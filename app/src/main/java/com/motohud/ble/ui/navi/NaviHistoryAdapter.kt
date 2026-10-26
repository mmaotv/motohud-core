package com.motohud.ble.ui.navi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.R
import com.motohud.ble.data.NaviHistoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导航历史列表项。
 */
class NaviHistoryAdapter(
    private val onClick: (NaviHistoryEntity) -> Unit,
    private val onLongClick: (NaviHistoryEntity) -> Unit
) : RecyclerView.Adapter<NaviHistoryAdapter.VH>() {

    private var data: List<NaviHistoryEntity> = emptyList()

    fun submit(list: List<NaviHistoryEntity>) {
        data = list
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: TrackThumbView = view.findViewById(R.id.nh_thumb)
        val dest: TextView = view.findViewById(R.id.nh_dest)
        val duration: TextView = view.findViewById(R.id.nh_duration)
        val from: TextView = view.findViewById(R.id.nh_from)
        val time: TextView = view.findViewById(R.id.nh_time)
        val distance: TextView = view.findViewById(R.id.nh_distance)
        val device: TextView = view.findViewById(R.id.nh_device)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_navi_history, parent, false))

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = data[position]
        val ctx = holder.itemView.context

        holder.dest.text = item.destName
        holder.duration.text = formatDuration(ctx, item.durationMs)
        holder.from.text = ctx.getString(
            R.string.navi_history_from
        ) + " " + item.startName

        // 时间：今天显示"HH:mm"，更早的带上日期
        val now = System.currentTimeMillis()
        val sameDay = isSameDay(item.finishedAt, now)
        holder.time.text = if (sameDay) {
            TIME_FMT.format(Date(item.finishedAt))
        } else {
            DATE_TIME_FMT.format(Date(item.finishedAt))
        }

        holder.distance.text = if (item.distanceM > 0) {
            ctx.getString(
                R.string.navi_history_km,
                String.format(Locale.US, "%.1f", item.distanceM / 1000.0)
            )
        } else {
            ""
        }

        // 车机情况
        val name = item.deviceName
        if (name.isNullOrBlank()) {
            holder.device.visibility = View.VISIBLE
            holder.device.text = ctx.getString(R.string.navi_history_device_none)
        } else {
            holder.device.visibility = View.VISIBLE
            val battery = item.battery?.let { String.format(Locale.US, "%.1f V", it) } ?: "--"
            val water = item.waterTemp?.let { if (it == 0) "<45℃" else "${it}℃" } ?: "--"
            val oil = item.oil?.let { "$it%" } ?: "--"
            val type = item.deviceType?.let { "（$it）" } ?: ""
            holder.device.text = ctx.getString(
                R.string.navi_history_device, "$name$type", battery, water, oil
            )
        }

        // 轨迹缩略图：列表里最省的"看起来像高德"手段（Canvas 画折线，不加载地图）
        holder.thumb.setTrack(item.trackPolyline)

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance()
        val cb = java.util.Calendar.getInstance()
        ca.timeInMillis = a
        cb.timeInMillis = b
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
            ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
    }

    companion object {
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val DATE_TIME_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        /** 把毫秒格式化成「1 小时 23 分」/「23 分 05 秒」/「45 秒」 */
        fun formatDuration(ctx: android.content.Context, ms: Long): String {
            if (ms <= 0) return "--"
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> ctx.getString(R.string.navi_history_hour, h, m)
                m > 0 -> ctx.getString(R.string.navi_history_min, m, s)
                else -> ctx.getString(R.string.navi_history_sec, s)
            }
        }
    }
}
