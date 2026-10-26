package com.motohud.ble.navi

import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.ble.NaviCommand
import com.motohud.ble.data.NaviPrefs

/**
 * BLE 导航指令转发器。
 *
 * 导航页（GuidanceActivity）onStart 时 attach、onStop 时 detach（息屏/画中画例外），
 * 只有至少有一个界面附着时才向车机发指令——避免息屏或切页时重复发送。
 *
 * 另外还受「投到车机」开关（[NaviPrefs.castEnabled]）约束：
 * 开关关掉时车机不会收到任何导航指令，手机导航照常工作。
 */
class NaviForwarder(private val ble: BleConnectionManager) {

    private val attached = LinkedHashSet<String>()

    fun attach(tag: String) {
        attached.add(tag)
    }

    fun detach(tag: String) {
        attached.remove(tag)
    }

    val isForwarding: Boolean
        get() = attached.isNotEmpty()

    /** 投屏开关打开且已连接车机时，才会真正写 BLE */
    private fun canSend(): Boolean {
        if (attached.isEmpty()) return false
        if (!NaviPrefs.castEnabled.value) return false
        return ble.state.value == BleConnectionManager.State.CONNECTED
    }

    /** 每 tick 转发一帧（复刻原 App onNaviInfoUpdate 每次都写） */
    fun forward(state: NaviEngine.NaviState) {
        if (!canSend()) return
        if (!state.running && !state.arrived) return
        NaviCommand.forwardInfo(
            send = { text -> ble.sendCommand(text) },
            deviceName = ble.connectedDeviceName,
            iconType = state.iconType,
            pathRemain = state.pathRemain,
            stepRemain = state.stepRemain
        )
    }

    /** 到达终点：FINISH ::: */
    fun forwardFinish() {
        if (ble.state.value != BleConnectionManager.State.CONNECTED) return
        if (!NaviPrefs.castEnabled.value) return
        NaviCommand.forwardFinish { text -> ble.sendCommand(text) }
    }

    /** 退出导航：NO */
    fun forwardExit() {
        if (ble.state.value != BleConnectionManager.State.CONNECTED) return
        if (!NaviPrefs.castEnabled.value) return
        NaviCommand.forwardExit { text -> ble.sendCommand(text) }
    }
}
