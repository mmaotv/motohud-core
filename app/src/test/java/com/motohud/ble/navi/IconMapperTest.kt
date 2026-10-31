package com.motohud.ble.navi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 动作文本 → 车机图标码的映射。
 *
 * 车机认的是原厂高德 IconType 数值，映射错了车机就显示错误的转向箭头，
 * 而手机上播报是对的 —— 用户会以为"车机坏了"。
 * 「离开环岛」这条以前就漏过：被判成进入环岛，图标和播报全反。
 */
class IconMapperTest {

    @Test
    fun `基本动作`() {
        assertEquals(IconType.LEFT, IconMapper.of("左转", "", ""))
        assertEquals(IconType.RIGHT, IconMapper.of("右转", "", ""))
        assertEquals(IconType.LEFT_FRONT, IconMapper.of("向左前方行驶", "", ""))
        assertEquals(IconType.RIGHT_FRONT, IconMapper.of("向右前方行驶", "", ""))
        assertEquals(IconType.LEFT_BACK, IconMapper.of("向左后方行驶", "", ""))
        assertEquals(IconType.RIGHT_BACK, IconMapper.of("向右后方行驶", "", ""))
        assertEquals(IconType.LEFT_TURN_AROUND, IconMapper.of("掉头", "", ""))
    }

    /** 「离开环岛」必须排在「环岛」前面判，否则永远命中不到 */
    @Test
    fun `环岛进出不能混淆`() {
        assertEquals(IconType.ENTER_ROUNDABOUT, IconMapper.of("进入环岛", "", ""))
        assertEquals(IconType.OUT_ROUNDABOUT, IconMapper.of("驶出环岛", "", ""))
        assertEquals(IconType.OUT_ROUNDABOUT, IconMapper.of("离开环岛", "", ""))
        assertEquals(IconType.OUT_ROUNDABOUT, IconMapper.of("离开环岛路口", "", ""))
    }

    @Test
    fun `靠左右是并线而不是转向`() {
        assertEquals(IconType.MERGE_LEFT, IconMapper.of("靠左", "", ""))
        assertEquals(IconType.MERGE_RIGHT, IconMapper.of("靠右", "", ""))
    }

    @Test
    fun `没有任何动作信息时落到直行`() {
        assertEquals(IconType.STRAIGHT, IconMapper.of("", "", "沿江南大道向东南行驶"))
        assertEquals(IconType.STRAIGHT, IconMapper.of(null, null, null))
    }

    /** action 为空时要从 instruction 文本里兜回来，否则整条链路都掉成直行 */
    @Test
    fun `动作字段缺失时从引导语里识别`() {
        assertEquals(IconType.RIGHT, IconMapper.of("", "", "沿望京路向前行驶500米右转"))
        assertEquals(IconType.LEFT, IconMapper.of(null, "", "沿人民路行驶200米后左转"))
    }

    /**
     * 「即将转弯」的步骤下标。
     *
     * 回归用例：AMap 把直行引道与转弯拆成相邻两步。正在走直行引道时，
     * 转弯在下一步 —— 以前错取当前段(直行)当转弯，导致整段直行引道没有
     * 1 公里/500 米提前预告，只剩到路口才报。
     */
    @Test
    fun `直行引道要指向下一段的转弯`() {
        // 当前段是直行 → 即将转弯在下一段
        assertEquals(1, IconMapper.maneuverIndexOf(0, IconType.STRAIGHT, 3))
        // 当前段本身就是转弯（短转弯段）→ 就是它自己
        assertEquals(0, IconMapper.maneuverIndexOf(0, IconType.RIGHT, 3))
        // 最后一段没有下一段 → 退回自己
        assertEquals(2, IconMapper.maneuverIndexOf(2, IconType.STRAIGHT, 3))
        // 无路线 / 空路线 → -1
        assertEquals(-1, IconMapper.maneuverIndexOf(-1, IconType.STRAIGHT, 3))
        assertEquals(-1, IconMapper.maneuverIndexOf(0, IconType.STRAIGHT, 0))
    }
}
