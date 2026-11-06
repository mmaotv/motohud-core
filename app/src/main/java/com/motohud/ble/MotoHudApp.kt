package com.motohud.ble

import android.app.Application
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.AppDatabase
import com.motohud.ble.data.BleDiagnostic
import com.motohud.ble.data.CrashHandler
import com.motohud.ble.data.FaultRepository
import com.motohud.ble.data.SearchEngine
import com.motohud.ble.data.bike.BikePrefs
import com.motohud.ble.navi.AmapNaviEngine
import com.motohud.ble.navi.NaviForwarder
import com.motohud.ble.obd.ObdClient
import com.motohud.ble.obd.ObdPrefs

class MotoHudApp : Application() {

    val db by lazy { AppDatabase.get(this) }
    val searchEngine by lazy { SearchEngine(db.searchHistoryDao()) }
    val savedPlaceDao by lazy { db.savedPlaceDao() }
    val naviHistoryDao by lazy { db.naviHistoryDao() }
    val naviTrackPointDao by lazy { db.naviTrackPointDao() }
    val bleManager by lazy { BleConnectionManager(this) }
    val faultRepository get() = FaultRepository

    /**
     * OBD 诊断头客户端（ELM327 over BLE）。
     *
     * 和 [bleManager] 是两条独立的 BLE 链路：车机那条给仪表/投屏，这条给
     * 转速/水温/电压/故障码 —— 车机上行帧恒为 0，这些数据只能从 OBD 拿。
     * 不自动连接（[ObdPrefs.enabled] 默认关），由用户先连一次车机再按需开，
     * 免得一开机就抢 BLE 带宽、也免得没插诊断头时反复扫描耗电。
     */
    val obdClient by lazy { ObdClient(this) }

    /**
     * 全局单例导航引擎（手机导航页与车机投屏 HUD 共享同一实例）。
     *
     * 已从自研 [RouteNaviEngine] 切换到**高德导航 SDK** 驱动的 [AmapNaviEngine]：
     * 路线规划/实时引导/电子眼/限速/车道线/路口放大图全部走 SDK 原生能力。
     * 没配 Key 或未同意隐私时，[AmapNaviEngine] 内部自动降级回自研引擎，
     * 保证车机投屏导航在任何情况下都不失能。
     */
    val naviEngine: AmapNaviEngine by lazy { AmapNaviEngine(this) }

    /** BLE 导航指令转发器（保证同一时刻只有一个界面在发指令） */
    val naviForwarder: NaviForwarder by lazy { NaviForwarder(bleManager) }

    override fun onCreate() {
        super.onCreate()
        // 崩溃捕获必须第一件事装上：后面任何一行初始化都可能炸，
        // 晚装一步就漏掉最该拿到的那类启动崩溃。
        CrashHandler.install(this) { crashExtras() }
        // 诊断日志落盘（filesDir/ble_diagnostic/diag.log），供现场导出
        BleDiagnostic.attach(this)
        // 恢复上次选择的车型
        BikePrefs.init(this)
        // 车机存档（上次连接的设备，用于自动连接）
        com.motohud.ble.data.device.DevicePrefs.init(this)
        // OBD 诊断头存档（与车机存档分开存，见 ObdPrefs 注释）
        ObdPrefs.init(this)
        // 导航偏好（路线偏好 / 语音开关 / 息屏导航）
        com.motohud.ble.data.NaviPrefs.init(this)
        // 坐标纠偏走高德官方 CoordinateConverter，需要 Context 先喂一次
        com.motohud.ble.amap.CoordFix.init(this)

        // 上次开着「第三方导航转发」就把它拉回来。
        // 该开关已在设置面板收起（实测高德手机版不发导航广播，见审计报告第三阶段），
        // 所以这里也一并停用 —— 否则偏好里残留 true 时服务会被悄悄拉起来，用户又关不掉。
        // 恢复方式：把设置面板的开关贴回来，同时取消下面这行注释。
        // if (com.motohud.ble.data.NaviPrefs.bridgeEnabled.value) {
        //     com.motohud.ble.service.NaviBridgeService.start(this)
        // }

        // 自愈：引导流程已完成说明用户同意过隐私政策，但高德侧的同意标记是另一套存储，
        // 重装、清数据或跨版本升级后可能缺失。不同步的话地图与搜索会一直不可用，
        // 界面上还会莫名出现"去配置地图"的提示。
        if (com.motohud.ble.ui.OnboardingActivity.isDone(this) && !AmapManager.isPrivacyAgreed(this)) {
            AmapManager.agreePrivacy(this)
        }

        // 已同意隐私政策时预热高德 SDK（未同意则等引导页授权后再初始化）
        if (AmapManager.isPrivacyAgreed(this) && AmapManager.hasKey(this)) {
            AmapManager.initMap(this)
            AmapManager.initSearch(this)
            AmapManager.initLocation(this)
            // 导航 SDK 必须在隐私合规之后、AMapNavi.getInstance 之前完成 Key 下发
            AmapManager.initNavi(this)
        }

        // 离线语音模型装了就先在后台加载好（约 3 秒）。
        // 拖到进导航再加载的话，头几句只能退回预录语音包，听感上"路名时有时无"。
        if (com.motohud.ble.navi.tts.TtsModelStore.isInstalled(this)) {
            com.motohud.ble.navi.tts.SherpaSpeaker.warmUp(this)
            // 语音库没生成全就在后台补：库是空的话第一次导航的前几句都要现合成，
            // 用户只会觉得"反应慢"。生成期间遇到实时播报会自动让位，不会挡住导航。
            if (com.motohud.ble.navi.tts.SherpaSpeaker.libraryMissing(this) > 0) {
                com.motohud.ble.navi.tts.SherpaSpeaker.buildVoiceLibrary(this) { _, _ -> }
            }
        }
    }

    /**
     * 崩溃报告里的「附加状态」。
     *
     * 只写能反映「崩的时候 App 在干什么」的几项，别塞一堆无关字段 ——
     * 真正定位问题时，看的是导航有没有在跑、连的是哪台车机，
     * 而不是把整个偏好表打出来。
     *
     * 每一项都独立 runCatching：崩溃现场本身可能就是某个单例构造失败，
     * 这里再去摸它只会二次崩，把原始异常盖掉。
     */
    private fun crashExtras(): String = buildString {
        runCatching {
            appendLine("BLE: ${BleDiagnostic.state.value} / 设备=${BleDiagnostic.deviceName.value ?: "无"}")
            appendLine("BLE: 通知=${BleDiagnostic.notifyReady.value} HELLO=${BleDiagnostic.helloSent.value} 帧数=${BleDiagnostic.frameCount.value}")
        }.onFailure { appendLine("BLE: (读取失败 ${it.javaClass.simpleName})") }

        runCatching {
            val s = naviEngine.state.value
            appendLine(
                "导航: running=${s.running} arrived=${s.arrived} " +
                    "step=${s.stepIndex} stepRemain=${s.stepRemain}m pathRemain=${s.pathRemain}m " +
                    "speed=${s.speedKmh.toInt()}km/h gps=${s.gpsActive}"
            )
        }.onFailure { appendLine("导航: (读取失败 ${it.javaClass.simpleName})") }

        runCatching {
            appendLine(
                "偏好: 语音=${com.motohud.ble.data.NaviPrefs.voice.value} " +
                    "投屏=${com.motohud.ble.data.NaviPrefs.castEnabled.value} " +
                    "息屏=${com.motohud.ble.data.NaviPrefs.screenOffNavi.value}"
            )
        }.onFailure { appendLine("偏好: (读取失败 ${it.javaClass.simpleName})") }
    }
}
