package com.motohud.ble.ui.navi

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.motohud.ble.ui.common.MotoDialog
import com.motohud.ble.R
import com.motohud.ble.data.NaviPrefs

/**
 * 路线偏好对话框。
 *
 * 「高德智能推荐」与其余六个约束是互斥关系：勾上智能推荐就把其余项禁用，
 * 用户想自己控制就取消智能推荐再勾。
 *
 * 其余六项可以多选 —— 高德的 strategy 是单个 int，不能真的位运算叠加，
 * 多选由 [NaviPrefs.strategy] 合并成最接近的组合策略（组合项越多，约束越强）。
 * 这些策略用的是 `DRIVING_MULTI_CHOICE_*`，会一次返回多套路线方案，
 * 正好支撑"先选路线再导航"。
 */
class RoutePrefsDialog : DialogFragment() {

    /** 偏好变更回调（重新算路） */
    var onApplied: (() -> Unit)? = null

    private var bound = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val v = layoutInflater.inflate(R.layout.dialog_route_prefs, null)
        val cbSmart = v.findViewById<MaterialCheckBox>(R.id.cb_smart)
        val cbHighwayFirst = v.findViewById<MaterialCheckBox>(R.id.cb_highway_first)
        val cbAvoidHighway = v.findViewById<MaterialCheckBox>(R.id.cb_avoid_highway)
        val cbAvoidJam = v.findViewById<MaterialCheckBox>(R.id.cb_avoid_jam)
        val cbAvoidToll = v.findViewById<MaterialCheckBox>(R.id.cb_avoid_toll)
        val cbTrafficFirst = v.findViewById<MaterialCheckBox>(R.id.cb_traffic_first)
        val cbTimeFirst = v.findViewById<MaterialCheckBox>(R.id.cb_time_first)

        val others = listOf(
            cbHighwayFirst, cbAvoidHighway, cbAvoidJam, cbAvoidToll, cbTrafficFirst, cbTimeFirst
        )

        val tvMultiDesc = v.findViewById<android.widget.TextView>(R.id.tv_multi_desc)

        fun renderEnable(smart: Boolean) {
            others.forEach { cb ->
                cb.isEnabled = !smart
                cb.isClickable = !smart
                cb.alpha = if (smart) 0.38f else 1f
            }
            // 说明文字一起淡掉，否则"置灰"只有一行字变暗，整体看着还是可点的
            tvMultiDesc.alpha = if (smart) 0.38f else 1f
        }

        cbSmart.isChecked = NaviPrefs.smart.value
        cbHighwayFirst.isChecked = NaviPrefs.highwayFirst.value
        cbAvoidHighway.isChecked = NaviPrefs.avoidHighway.value
        cbAvoidJam.isChecked = NaviPrefs.avoidJam.value
        cbAvoidToll.isChecked = NaviPrefs.avoidToll.value
        cbTrafficFirst.isChecked = NaviPrefs.trafficFirst.value
        cbTimeFirst.isChecked = NaviPrefs.timeFirst.value
        renderEnable(NaviPrefs.smart.value)

        cbSmart.setOnCheckedChangeListener { _, checked -> renderEnable(checked) }

        // 高速优先 / 不走高速 天然互斥，勾一个自动取消另一个
        cbHighwayFirst.setOnCheckedChangeListener { _, checked ->
            if (checked && cbAvoidHighway.isChecked) cbAvoidHighway.isChecked = false
        }
        cbAvoidHighway.setOnCheckedChangeListener { _, checked ->
            if (checked && cbHighwayFirst.isChecked) cbHighwayFirst.isChecked = false
        }

        return MotoDialog.builder(ctx)
            .setTitle(R.string.route_prefs)
            .setView(v)
            .setNegativeButton(R.string.cancel, null)
            // 这里点下去只是**保存偏好**，不跳任何页面。
            // 原来借用 R.string.setting（「去设置」）是历史误用：按钮写着"去设置"，
            // 实际什么都不跳，用户点完会以为没生效。改用「保存」。
            .setPositiveButton(R.string.save) { _, _ ->
                NaviPrefs.setSmart(cbSmart.isChecked)
                if (!cbSmart.isChecked) {
                    NaviPrefs.setHighwayFirst(cbHighwayFirst.isChecked)
                    NaviPrefs.setAvoidHighway(cbAvoidHighway.isChecked)
                    NaviPrefs.setAvoidJam(cbAvoidJam.isChecked)
                    NaviPrefs.setAvoidToll(cbAvoidToll.isChecked)
                    NaviPrefs.setTrafficFirst(cbTrafficFirst.isChecked)
                    NaviPrefs.setTimeFirst(cbTimeFirst.isChecked)
                }
                bound = true
                onApplied?.invoke()
            }
            .create()
    }

    /** 对话框内的勾选颜色跟随主题（Material3 默认已用 colorPrimary，这里补一下深色可读性） */
    override fun onStart() {
        super.onStart()
        val accent = ContextCompat.getColor(requireContext(), R.color.accent)
        val idle = ContextCompat.getColor(requireContext(), R.color.text_tertiary)
        val disabled = ContextCompat.getColor(requireContext(), R.color.fill_strong)
        // 注意顺序：禁用态必须排在最前，否则 state_checked 会先命中，
        // 出现"已禁用但方框还是橙色勾"的假象。
        val states = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(disabled, accent, idle)
        )
        (dialog?.findViewById<android.view.View>(android.R.id.content) as? android.view.ViewGroup)
            ?.let { root -> tintCheckBoxes(root, states) }
    }

    private fun tintCheckBoxes(v: android.view.View, colors: ColorStateList) {
        if (v is MaterialCheckBox) {
            v.buttonTintList = colors
            return
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) tintCheckBoxes(v.getChildAt(i), colors)
        }
    }
}
