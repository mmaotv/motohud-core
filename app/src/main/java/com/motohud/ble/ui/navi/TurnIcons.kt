package com.motohud.ble.ui.navi

import com.motohud.ble.R
import com.motohud.ble.navi.IconMapper
import com.motohud.ble.navi.IconType

/**
 * 高德 IconType → 转向图标资源。
 *
 * 原先这个映射写在 HudActivity 里，HUD 页删除后抽出来单独放，
 * 导航页、通知栏（RemoteViews 里用不到的位图走同名 drawable）与历史详情页共用。
 */
object TurnIcons {

    fun res(iconType: Int): Int = when (iconType) {
        IconType.LEFT -> R.drawable.ic_turn_left
        IconType.RIGHT -> R.drawable.ic_turn_right
        IconType.LEFT_FRONT -> R.drawable.ic_turn_slight_left
        IconType.RIGHT_FRONT -> R.drawable.ic_turn_slight_right
        IconType.LEFT_BACK -> R.drawable.ic_turn_slight_left
        IconType.RIGHT_BACK -> R.drawable.ic_turn_slight_right
        IconType.LEFT_TURN_AROUND, IconType.U_TURN_RIGHT -> R.drawable.ic_turn_uturn
        IconType.ENTER_ROUNDABOUT, IconType.ENTRY_RING_LEFT -> R.drawable.ic_turn_roundabout
        IconType.ARRIVED_DESTINATION, IconType.ARRIVED_WAYPOINT -> R.drawable.ic_turn_arrive
        else -> R.drawable.ic_turn_straight
    }

    /** 是否属于"需要提前看清路口"的转弯类图标（路口前自动放大用）。
     *  唯一实现下沉到 [IconMapper.isTurn]，此处仅转发，避免两处各写一份走样。 */
    fun isTurn(iconType: Int): Boolean = IconMapper.isTurn(iconType)
}
