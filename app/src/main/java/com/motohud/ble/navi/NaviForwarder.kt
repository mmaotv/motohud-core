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
            // 走带 navKey 的覆盖入队：同类指令只保留最新一帧，杜绝队列积压导致车机显示滞后
            send = { text, key -> ble.sendNavCommand(text, key) },
            deviceName = ble.connectedDeviceName,
            iconType = state.iconType,
            pathRemain = state.pathRemain,
            stepRemain = state.stepRemain
        )
    }

    /**
     * 转发**外部导航源**（高德 / 百度 APP 的广播或通知）的一帧。
     *
     * 与 [forward] 共用同一条 BLE 出口，只是三个字段都可空：
     * 上游没读到的项传 null，就不发 —— 车机保留上一次显示，
     * 不会出现"目的地距离被填进下一路口"这种串字段。
     *
     * 数据源需要先 [attach]（服务启动时挂一个 tag），这样"投到车机"总开关
     * 和连接状态判断都复用现有逻辑，不必另写一套。
     */
    fun forwardRaw(iconType: Int?, pathRemain: Int?, stepRemain: Int?) {
        if (!canSend()) return
        if (iconType == null && pathRemain == null && stepRemain == null) return
        NaviCommand.forwardPartial(
            send = { text, key -> ble.sendNavCommand(text, key) },
            deviceName = ble.connectedDeviceName,
            iconType = iconType,
            pathRemain = pathRemain,
            stepRemain = stepRemain
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
