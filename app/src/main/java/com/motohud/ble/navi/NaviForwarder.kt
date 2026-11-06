package com.motohud.ble.navi

import android.os.SystemClock
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

    /**
     * 上一帧真正写出去的三个投屏字段（去重用）。
     *
     * 为什么要去重：[AmapNaviEngine.applyNaviInfo] 每收到一次 `onNaviInfoUpdate`
     * 就全量 update 一次 state，而高德这个回调实测约 10Hz，路径距离却是一秒才变
     * 一次。中间那些帧三字段完全相同，照样会走完 state → 界面 → BLE 整条链路，
     * 实车日志里能看到同一组 `NA/RE/NE` 在一秒内被连写 6~7 遍。
     */
    private var lastIcon = Int.MIN_VALUE
    private var lastPathRemain = Int.MIN_VALUE
    private var lastStepRemain = Int.MIN_VALUE
    private var lastSentAt = 0L

    /** 第三方导航源（NaviBridgeService.forwardRaw）独立的去重基准，两条链路互不压制 */
    private var lastRawIcon: Int? = null
    private var lastRawPath: Int? = null
    private var lastRawStep: Int? = null
    private var lastRawSentAt = 0L

    fun attach(tag: String) {
        attached.add(tag)
    }

    fun detach(tag: String) {
        attached.remove(tag)
    }

    /**
     * 忘记上一帧的去重基准。
     *
     * 每次开始导航调一次：新路线起步的第一帧可能与上一趟结束时数值相同，
     * 不清掉就会被当成重复帧吞掉，车机停在上一次的画面上。
     */
    fun reset() {
        lastIcon = Int.MIN_VALUE
        lastPathRemain = Int.MIN_VALUE
        lastStepRemain = Int.MIN_VALUE
        lastSentAt = 0L
        lastRawIcon = null
        lastRawPath = null
        lastRawStep = null
        lastRawSentAt = 0L
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
        val now = SystemClock.elapsedRealtime()
        // 转弯动作变了必须立刻发：车机图标早半秒出现，比任何节流都重要
        if (state.iconType == lastIcon) {
            if (now - lastSentAt < MIN_FRAME_INTERVAL_MS) return
            if (state.pathRemain == lastPathRemain && state.stepRemain == lastStepRemain) return
        }
        lastIcon = state.iconType
        lastPathRemain = state.pathRemain
        lastStepRemain = state.stepRemain
        lastSentAt = now
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
        // 外部广播/通知可能以 10Hz 连发同一帧，之前这条链路没节流，
        // 自带导航那条修好了它照样能把 BLE 写队列灌满。
        val now = SystemClock.elapsedRealtime()
        if (iconType == lastRawIcon) {
            if (now - lastRawSentAt < MIN_FRAME_INTERVAL_MS) return
            if (pathRemain == lastRawPath && stepRemain == lastRawStep) return
        }
        lastRawIcon = iconType
        lastRawPath = pathRemain
        lastRawStep = stepRemain
        lastRawSentAt = now
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

    private companion object {
        /**
         * 两帧之间的最小间隔。
         *
         * 400ms ≈ 2.5 帧/秒：车机上「剩余 342 米」跳到「333 米」本来就是每秒一次，
         * 再快也看不出区别；而 SDK 的 10Hz 回调下把间隔放开成 0，同一内容会被
         * 连写六七遍，把 BLE 写队列占满（转向/电子眼那些真正要抢先的帧就排不上了）。
         */
        const val MIN_FRAME_INTERVAL_MS = 400L
    }
}
