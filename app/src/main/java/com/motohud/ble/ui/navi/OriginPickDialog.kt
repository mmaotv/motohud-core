package com.motohud.ble.ui.navi

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.data.SavedPlaceEntity
import com.motohud.ble.databinding.DialogOriginPickBinding
import com.motohud.ble.ui.common.MotoDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 起点选择面板。
 *
 * 界面上有四条路：当前位置 / 地图选点 / 手动坐标 / 常用地址。
 * 选完通过 [onPicked] 把「名称 + 经纬度」回给路线页，由它重新算路。
 */
class OriginPickDialog : BottomSheetDialogFragment() {

    private var _b: DialogOriginPickBinding? = null
    private val b get() = _b!!

    /** 回到路线页的回调：名称、纬度、经度 */
    var onPicked: ((String, Double, Double) -> Unit)? = null

    /** 「在地图上选点」需要跳另一个页面，交给宿主实现 */
    var onPickOnMap: (() -> Unit)? = null

    /** 当前是否有可用定位（没有就得把"当前位置"那行灰掉） */
    private var currentLocation: Pair<Double, Double>? = null

    /** 面板标题资源 id，默认「选择起点」 */
    private var titleRes: Int = R.string.origin_pick_title

    private val savedAdapter by lazy {
        PlaceListAdapter { place ->
            onPicked?.invoke(place.name, place.latitude, place.longitude)
            dismiss()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = DialogOriginPickBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.originTitle.setText(titleRes)
        // 「拖动地图，把起点对准屏幕中心」——途经点时得说"途经点"，
        // 沿用同一份布局所以副标题也要跟着换
        if (titleRes == R.string.route_waypoint_pick_title) {
            b.originMapSub.setText(R.string.route_waypoint_map_pick_sub)
        }

        // 没有定位时，"当前位置"不可用 —— 不隐藏，只置灰并写清原因
        val loc = currentLocation
        b.originCurrent.isEnabled = loc != null
        b.originCurrent.alpha = if (loc != null) 1f else 0.4f
        b.originCurrentValue.text = if (loc != null) {
            getString(R.string.origin_current_sub)
        } else {
            getString(R.string.navi_locate_failed_short)
        }
        b.originCurrent.setOnClickListener {
            val p = currentLocation ?: return@setOnClickListener
            onPicked?.invoke(getString(R.string.origin_current), p.first, p.second)
            dismiss()
        }

        b.originMap.setOnClickListener {
            onPickOnMap?.invoke()
            dismiss()
        }

        b.originSavedList.layoutManager = LinearLayoutManager(requireContext())
        b.originSavedList.adapter = savedAdapter

        lifecycleScope.launch {
            // 常用地址只需要当前快照，不需要持续观察
            val places = (requireActivity().application as MotoHudApp)
                .savedPlaceDao.observeAll().first()
            if (places.isEmpty()) {
                b.originSavedTitle.visibility = View.GONE
                b.originSavedList.visibility = View.GONE
            } else {
                b.originSavedTitle.visibility = View.VISIBLE
                b.originSavedList.visibility = View.VISIBLE
                savedAdapter.submit(places)
            }
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        /**
         * @param titleRes 面板标题。起点和途经点复用同一个面板，
         *                 标题不对用户会以为自己点错了行。
         */
        fun newInstance(
            currentLocation: Pair<Double, Double>?,
            titleRes: Int = R.string.origin_pick_title
        ): OriginPickDialog = OriginPickDialog().apply {
            this.currentLocation = currentLocation
            this.titleRes = titleRes
        }
    }
}

/** 常用地址列表（起点选择用） */
private class PlaceListAdapter(
    private val onPick: (SavedPlaceEntity) -> Unit
) : RecyclerView.Adapter<PlaceListAdapter.VH>() {

    private var data: List<SavedPlaceEntity> = emptyList()

    fun submit(list: List<SavedPlaceEntity>) {
        data = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_place_pick, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = data[position]
        holder.name.text = p.name
        holder.itemView.setOnClickListener { onPick(p) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tv_place_name)
    }
}
