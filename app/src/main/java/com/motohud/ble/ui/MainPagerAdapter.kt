package com.motohud.ble.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.motohud.ble.ui.garage.GarageFragment
import com.motohud.ble.ui.navi.NavigationFragment

/**
 * 主界面 2 个页签：爱车 / 导航。
 *
 * 原先是 4 个（爱车 / 导航 / 车况 / 连接）。实测确认 BYQ 车机只提供投屏导航，
 * 没有任何上行遥测，车况相关页面（仪表 / 体检 / 指令台）永远等不到数据；
 * 连接页则降级为二级页 [com.motohud.ble.ui.scan.ScanActivity]。
 * 这些 Fragment 类本身都保留着，需要时可直接挂回分页器。
 */
class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = MainActivity.TAB_COUNT
    override fun createFragment(position: Int): Fragment = when (position) {
        MainActivity.TAB_NAVIGATION -> NavigationFragment()
        else -> GarageFragment()
    }
}
