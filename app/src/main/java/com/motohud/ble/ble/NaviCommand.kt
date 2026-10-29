package com.motohud.ble.ble

import com.motohud.ble.ble.BleConstants.DeviceType

/**
 * 导航 BLE 指令转发器。
 *
 * 复刻原 App BaseNaviActivity.writeData / NormalMotorFragment.java:1795-1827 的码表选择逻辑：
 *  - SR  设备：REM :<全程剩余>::   NEXT :<下一转弯剩余>::   NAVI :<图标>::
 *  - BYQ 设备：NA  :<图标>:       RE  :<全程剩余>:        NE  :<下一转弯剩余>:
 *  - 未知设备：两套都发（原 App 对 byq 大小写兼容的扩展）
 * 到达终点发 FINISH :::，退出导航发 NO（BaseNaviActivity.java:264,368）。
 */
object NaviCommand {

    /**
     * 发送一帧导航信息（onNaviInfoUpdate 等价）。
     *
     * [send] 的第二个参数是**指令类型键**（NA/RE/NE/REM/NEXT/NAVI）。
     * 投屏链路按这个键做「同类只留最新一帧」的覆盖入队，
     * 防止 BLE 写入慢时队列无限积压、车机越显示越旧。
     */
    fun forwardInfo(
        send: (String, String) -> Unit,
        deviceName: String?,
        iconType: Int,
        pathRemain: Int,
        stepRemain: Int
    ) {
        forwardPartial(send, deviceName, iconType, pathRemain, stepRemain)
    }

    /**
     * 只发**确实拿到**的字段（第三方导航源用）。
     *
     * 三个参数都可空：null = 上游没读到，这一项就不发，车机保留上一次显示。
     * 这条规则是硬约束 —— 通知文本只解析出「剩余 12.6 公里」时，
     * 绝不能顺手把 12.6km 也填进 NEXT，那会把目的地距离伪装成下一路口距离。
     */
    fun forwardPartial(
        send: (String, String) -> Unit,
        deviceName: String?,
        iconType: Int?,
        pathRemain: Int?,
        stepRemain: Int?
    ) {
        val type = BleConstants.deviceTypeOf(deviceName)
        // 图标**原样透传**，不做任何裁剪。依据原厂 BaseNaviActivity.onNaviInfoUpdate:380-388：
        // 官方实现直接 `writeData("NAVI :" + iconType + "::")`，只做 <10 补零（见 cmdNavi 的 pad2），
        // 没有任何范围判断。这里曾加过 clamp（越界落直行），会把「靠左/靠右」显示成「直行」，
        // 对自家导航是退化，已回退。
        when (type) {
            DeviceType.SR -> {
                pathRemain?.let { send(BleConstants.cmdRem(it), "REM") }
                stepRemain?.let { send(BleConstants.cmdNext(it), "NEXT") }
                iconType?.let { send(BleConstants.cmdNavi(it), "NAVI") }
            }

            DeviceType.BYQ -> {
                iconType?.let { send(BleConstants.cmdNa(it), "NA") }
                pathRemain?.let { send(BleConstants.cmdRe(it), "RE") }
                stepRemain?.let { send(BleConstants.cmdNe(it), "NE") }
            }

            DeviceType.UNKNOWN -> {
                iconType?.let { send(BleConstants.cmdNa(it), "NA") }
                pathRemain?.let { send(BleConstants.cmdRe(it), "RE") }
                stepRemain?.let { send(BleConstants.cmdNe(it), "NE") }
                pathRemain?.let { send(BleConstants.cmdRem(it), "REM") }
                stepRemain?.let { send(BleConstants.cmdNext(it), "NEXT") }
                iconType?.let { send(BleConstants.cmdNavi(it), "NAVI") }
            }
        }
    }

    /** 到达终点（BaseNaviActivity.java:368 onArriveDestination） */
    fun forwardFinish(send: (String) -> Unit) = send(BleConstants.CMD_FINISH)

    /** 退出导航（BaseNaviActivity.java:264 onDestroy） */
    fun forwardExit(send: (String) -> Unit) = send(BleConstants.CMD_NO)
}
