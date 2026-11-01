package com.motohud.ble.ui.navi

import com.motohud.ble.R
import com.motohud.ble.navi.IconMapper
import com.motohud.ble.navi.IconType

/**
 * 高德 IconType → 转向图标资源。
 *
 * **图标全部用高德官方的，不再自绘。**
 *
 * 官方图标藏在导航 SDK 的资源包里（`assets/autonavi_Resource1_1_0.png`，
 * 那个文件其实是个 zip：`res/drawable-*-v4/` + `resources.arsc`）。
 * 其中 `amap_navi_lbs_sou*` 就是高德导航卡上那套「白色粗箭头 + 灰环」的转向图，
 * 114×114 (hdpi)，正好对应 76dp，拿来当 88dp 的转向图标基本不糊。
 *
 * 映射不是拍脑袋定的，直接照抄 SDK 的 `NextTurnTipView.defaultIconTypes`
 * （长度 67 的 int[]，下标就是 IconType，值是对应的 drawable 资源 id）：
 *
 * ```
 * iconType  0/1/9 → sou9    2 → sou2(左转)   3 → sou3(右转)
 *           4 → sou4(左前)  5 → sou5(右前)   6 → sou6(左后)
 *           7 → sou7(右后)  8 → sou8(掉头)   10 → sou10(途经点)
 *           11 → sou11(进环岛) 12 → sou12(出环岛) 13 → sou13 14 → sou14(收费站)
 *           15 → sou15(终点旗) 19 → sou19(右掉头)  20 → sou20
 * ```
 *
 * 也就是说 **iconType 本身就是图标编号**（2..20 直通），SDK 自己也是这么查的。
 * 旧实现把 6/7（左后/右后）也画成"斜前"，把 11/12 都画成同一个环岛，
 * 这就是"左前方右前方看着乱"的直接原因。
 *
 * 只有两个号官方表里没有，按语义挂最接近的官方图（仍不自绘）：
 *  - 65 靠左 / 66 靠右（搜索 SDK 的并线）：用 sou4 / sou5（左前 / 右前）
 *  - 21 环岛左侧进入：用 sou25（环岛 + 左向出口箭头）
 */
object TurnIcons {

    fun res(iconType: Int): Int = when (iconType) {
        IconType.LEFT -> R.drawable.amap_navi_lbs_sou2
        IconType.RIGHT -> R.drawable.amap_navi_lbs_sou3
        IconType.LEFT_FRONT -> R.drawable.amap_navi_lbs_sou4
        IconType.RIGHT_FRONT -> R.drawable.amap_navi_lbs_sou5
        IconType.LEFT_BACK -> R.drawable.amap_navi_lbs_sou6
        IconType.RIGHT_BACK -> R.drawable.amap_navi_lbs_sou7
        IconType.LEFT_TURN_AROUND -> R.drawable.amap_navi_lbs_sou8
        IconType.STRAIGHT -> R.drawable.amap_navi_lbs_sou9
        IconType.ARRIVED_WAYPOINT -> R.drawable.amap_navi_lbs_sou10
        IconType.ENTER_ROUNDABOUT -> R.drawable.amap_navi_lbs_sou11
        IconType.OUT_ROUNDABOUT -> R.drawable.amap_navi_lbs_sou12
        IconType.ARRIVED_DESTINATION -> R.drawable.amap_navi_lbs_sou15
        IconType.U_TURN_RIGHT -> R.drawable.amap_navi_lbs_sou19
        IconType.ENTRY_RING_LEFT -> R.drawable.amap_navi_lbs_sou25
        // 并线：搜索 SDK 给的 65/66，官方图标表里没有对应的号
        IconType.MERGE_LEFT -> R.drawable.amap_navi_lbs_sou4
        IconType.MERGE_RIGHT -> R.drawable.amap_navi_lbs_sou5
        else -> R.drawable.amap_navi_lbs_sou9
    }

    /** 是否属于"需要提前看清路口"的转弯类图标（路口前自动放大用）。
     *  唯一实现下沉到 [IconMapper.isTurn]，此处仅转发，避免两处各写一份走样。 */
    fun isTurn(iconType: Int): Boolean = IconMapper.isTurn(iconType)
}
