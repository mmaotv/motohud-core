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
        when (BleConstants.deviceTypeOf(deviceName)) {
            DeviceType.SR -> {
                send(BleConstants.cmdRem(pathRemain), "REM")
                send(BleConstants.cmdNext(stepRemain), "NEXT")
                send(BleConstants.cmdNavi(iconType), "NAVI")
            }

            DeviceType.BYQ -> {
                send(BleConstants.cmdNa(iconType), "NA")
                send(BleConstants.cmdRe(pathRemain), "RE")
                send(BleConstants.cmdNe(stepRemain), "NE")
            }

            DeviceType.UNKNOWN -> {
                send(BleConstants.cmdNa(iconType), "NA")
                send(BleConstants.cmdRe(pathRemain), "RE")
                send(BleConstants.cmdNe(stepRemain), "NE")
                send(BleConstants.cmdRem(pathRemain), "REM")
                send(BleConstants.cmdNext(stepRemain), "NEXT")
                send(BleConstants.cmdNavi(iconType), "NAVI")
            }
        }
    }

    /** 到达终点（BaseNaviActivity.java:368 onArriveDestination） */
    fun forwardFinish(send: (String) -> Unit) = send(BleConstants.CMD_FINISH)

    /** 退出导航（BaseNaviActivity.java:264 onDestroy） */
    fun forwardExit(send: (String) -> Unit) = send(BleConstants.CMD_NO)
}
