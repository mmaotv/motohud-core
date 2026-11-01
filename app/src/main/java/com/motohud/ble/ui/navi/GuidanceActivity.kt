package com.motohud.ble.ui.navi

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.location.AMapLocation
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.navi.AMapNaviView
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.amap.AmapMapConfig
import com.motohud.ble.amap.AmapRepo
import com.motohud.ble.amap.RoutePlanResult
import com.motohud.ble.amap.RouteQueryOptions
import com.motohud.ble.amap.accuracyLevel
import com.motohud.ble.amap.formatAccuracy
import com.motohud.ble.amap.locationTypeLabel
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.data.NaviTrack
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.navi.Geo
import com.motohud.ble.navi.IconMapper
import com.motohud.ble.navi.IconType
import com.motohud.ble.navi.NaviEngine
import com.motohud.ble.navi.RouteData
import com.motohud.ble.navi.RouteHandoff
import com.motohud.ble.navi.RouteNaviEngine
import com.motohud.ble.navi.RouteStep
import com.motohud.ble.navi.SpeechPriority
import com.motohud.ble.navi.SpeechScript
import com.motohud.ble.navi.tts.TtsModelStore
import com.motohud.ble.ui.common.MotoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.motohud.ble.ui.theme.themeColor

/**
 * 手机导航页（专业导航软件体验）。
 *
 * ## 数据流
 *   POI/收藏地址 → 路线规划（摩托车/驾车策略）→ [RouteNaviEngine] 每秒推演
 *     → 手机界面（转向横幅 / 下一步预告 / 已走变灰 / 剩余 / ETA / 地图跟随）
 *     → [com.motohud.ble.navi.NaviForwarder] → BLE → 车机箭头屏
 *
 * ## 四种显示形态（并列，互不替代）
 *  - **本页地图**：手机在支架、屏亮、要看路时的主形态；
 *  - **投到车机**：一个开关（[NaviPrefs.castEnabled]），开了就往车机发指令，手机可息屏；
 *  - **通知栏导航条**：息屏 / 兜里也能瞥一眼方向（[com.motohud.ble.service.NaviForegroundService]）；
 *  - **画中画小窗**：切到微信、音乐时的浮窗（系统 PiP，零权限）。
 *
 * 原来的横屏 HUD 页已删除：它画的三项（转向图标 / 本段剩余 / 全程剩余）与车机 100% 重复，
 * 多出来的 ETA/速度撑不起一个独立页面。
 */

/**
 * 转弯提前播报的距离阶梯（米，降序）：1 公里 → 300 → 临门。
 *
 * 骑行时最需要的是「快到路口了」的紧迫感，而不是到达后才知道该转。
 * 档位不能堆：以前是 1km/500/300/200/100/50 六档，300 与 200、100 与 50
 * 之间只差一两秒，实际听感是"一直有人在说话"，反而盖住了真正该听的那句。
 *
 * 现在照高德播报调度表（`libamaptbt.so` 的 `mixfork0`）收敛成三档：
 * ```
 * "frontLimit": {"default": 1100}       // 路口前 1100 米开始提醒
 * "recoverDist": {"default": 200, "lastPlay": 550}   // 最后一次播报在 550 米
 * "times": {"default": 2}               // 同一路口只播 2 次
 * ```
 * 我们取 1000 / 300 两档 + 临门一句，档距 700 米，比官方 550 那一档更晚、更贴路口。
 *
 *  - **1000 米**：预警，「前方 1 公里，第 2 个路口右转进入南滨路」；
 *  - **300 米**：确认，最后一次带距离的提醒（顺带把车道指引并进同一句）；
 *  - **临门**：只报动作（「右转」「进入环岛，第 3 个出口」），见
 *    [com.motohud.ble.navi.VoiceGuidance.closeDistOf]。
 *
 * 前两档固定；最后一档出厂值 50 米，实际由 `thresholdsFor` 按车速替换
 * （30/45/60/80），所以这里写的是兜底值。
 */
private val TURN_THRESHOLDS = intArrayOf(1_000, 300, 50)

/** 直行路口的提醒档：只提醒一次「路口直行」，长直路不啰嗦 */
private val STRAIGHT_THRESHOLDS = intArrayOf(300)

/** 简洁模式：砍掉 1 公里预警，只留 300 确认 + 临门动作 */
private val BRIEF_THRESHOLDS = intArrayOf(300, 50)

/** 只播转弯模式：只在 300 米处念一句 */
private val TURN_ONLY_THRESHOLDS = intArrayOf(300)

class GuidanceActivity : AppCompatActivity() {

    private lateinit var app: MotoHudApp
    /**
     * 导航引擎。类型用接口 [NaviEngine]，具体实现由 [MotoHudApp.naviEngine] 决定：
     * 现在是高德导航 SDK（[com.motohud.ble.navi.AmapNaviEngine]），
     * SDK 不可用时它内部会降级到自研引擎 —— 页面本身不关心是哪种。
     */
    private lateinit var engine: NaviEngine
    private lateinit var ble: BleConnectionManager

    private var mapView: MapView? = null
    private var aMap: AMap? = null

    /**
     * 影子导航视图：8dp×8dp、alpha=0、被地图盖住，只为让 SDK 生成路口放大图。
     * 见 [setupShadowNaviView] 的说明。
     */
    private var shadowNaviView: AMapNaviView? = null

    /** 全程路线（未走部分）与已走路段，两条线叠出"走到哪了"的效果 */
    private var aheadLine: Polyline? = null
    private var doneLine: Polyline? = null
    private var destMarker: Marker? = null
    private var meMarker: Marker? = null

    /** 路线展平后的点与累计距离，用于把折线按 traveled 切两段 */
    private var routePoints: List<LatLng> = emptyList()
    private var routeCum: DoubleArray = DoubleArray(0)
    private var lastProgressM = -1

    private lateinit var topBar: View
    private lateinit var bottomInfo: View
    private lateinit var bottomExtra: View
    private lateinit var tvDestName: TextView
    private lateinit var tvDestSub: TextView
    private lateinit var tvModeBadge: TextView
    private lateinit var btnVoice: ImageView
    private lateinit var turnBanner: View
    private lateinit var ivTurnIcon: ImageView
    private lateinit var tvTurnInstruction: TextView
    private lateinit var tvTurnRoad: TextView
    private lateinit var tvTurnNext: TextView
    private lateinit var tvTurnDistance: TextView
    private lateinit var tvEta: TextView
    private lateinit var tvRemain: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvGpsMode: TextView
    private lateinit var tvGpsQuality: TextView
    private lateinit var statGps: View
    private lateinit var tvSignalWarn: View

    // ---- 导航 SDK 新增能力对应的控件 ----
    /** 车道线（showLaneInfo）：自绘箭头，见 [LaneGuideView] */
    private lateinit var laneGuideView: LaneGuideView
    /** 电子眼提示整行（含限速徽章），显隐要整行控制 */
    private lateinit var llCameraWarn: View
    /** 电子眼 / 限速提示（updateCameraInfo） */
    private lateinit var tvCameraWarn: TextView
    /** 限速徽章（红圈数字）；0 = 无已知限速，隐藏 */
    private lateinit var tvLimitBadge: TextView
    /** 路口放大图（showCross） */
    private lateinit var ivCrossImage: ImageView

    /** 路口大图整卡：标题行 + 图 + ETA 行（见布局注释，不是光一块图） */
    private lateinit var crossPanel: View
    private lateinit var ivCrossTurn: ImageView
    private lateinit var tvCrossDistance: TextView
    private lateinit var tvCrossTitle: TextView
    private lateinit var tvCrossEta: TextView
    private lateinit var tvCrossRemain: TextView
    private lateinit var tvCrossSpeed: TextView

    /** 最近一张路口放大图 + 它最后一次出现的时刻（用于最短展示时长，见 renderSdkAlerts） */
    private var lastCross: com.motohud.ble.navi.CrossImage? = null
    private var crossShownAt = 0L
    /** 侧栏是否正被路口大图独占（见 setCrossMode） */
    private var crossMode = false

    /**
     * 矢量放大图渲染器（`AMapModelCross` 给的是矢量数据，不是图片）。
     * 懒建：要等主地图 `aMap` 就绪。
     */
    private var vectorCrossRenderer: com.motohud.ble.navi.VectorCrossRenderer? = null
    /** 已经送去渲染的矢量图 seq，防止每帧重复提交 */
    private var vectorRenderedSeq = -1

    /** 路口大图卡的原始上内边距 + 状态栏高度（运行时算，见 onCreate 末尾） */
    private var crossPanelBaseTop = 0
    private var statusBarPx = 0
    /** SDK 提示条：服务区 / 交通事件 / 可切主辅路（三者共用一行，按优先级取一条） */
    private lateinit var tvNaviNote: TextView
    private lateinit var tvBleHint: TextView
    private lateinit var tvLights: TextView
    private lateinit var tvTolls: TextView
    private lateinit var tvStepProgress: TextView
    private lateinit var tvTrafficEstimate: TextView
    private lateinit var planningOverlay: View
    private lateinit var arrivedOverlay: View
    private lateinit var mapActions: View
    /** 语音 / 投屏 / 定位三枚圆钮的容器：横屏竖排一列挂左上角，竖屏横排贴右上角 */
    private lateinit var quickActions: LinearLayout
    private lateinit var btnRecenter: View
    private lateinit var topScrim: View
    private lateinit var btnPip: View
    private lateinit var tvArriveSub: TextView
    private lateinit var tvArriveDistance: TextView
    private lateinit var tvArriveDuration: TextView
    private lateinit var tvArriveAvg: TextView
    private lateinit var betterRouteCard: View
    private lateinit var tvBetterRoute: TextView

    /**
     * 语音播报：走 [com.motohud.ble.navi.Speaker] 接口。
     *
     * 不再直接用系统 `TextToSpeech` —— 车机和部分国产 ROM 上它根本调不通
     * （无引擎 / 缺中文数据 / 只支持 synthesizeToFile），表现就是"导航全程哑巴"。
     * 现在有两种实现，[com.motohud.ble.navi.SpeechRouter] 按"模型装没装"二选一：
     *  - [com.motohud.ble.navi.tts.SherpaSpeaker]：能念路名；
     *  - [com.motohud.ble.navi.PresetPackSpeaker]：APK 内置语音包，不念路名。
     */
    private var voice: com.motohud.ble.navi.Speaker? = null

    /**
     * 当前是不是「内置语音包」在播（没装模型）。
     *
     * 内置包只能念固定短句，**念不出路名和任意数字**，所以要换一套文案：
     *  - 进段确认说「继续直行 500 米」而不是「继续沿烟雨路行驶 500 米」；
     *  - 开场白只说「开始导航」，不带全程距离/ETA（"10.1 公里"这种数字念不了，
     *    硬拼会变成"一公里"）。
     */
    private var presetPack = false
    private var route: RouteData? = null
    private var destName: String = ""
    private var destLat = 0.0
    private var destLng = 0.0
    private var lastCameraMs = 0L

    /** 周期性检查更优/更畅通路线：每隔一段时间重新算路并对比当前路线 */
    private val betterRouteHandler = Handler(Looper.getMainLooper())
    private var betterRouteDismissed = false
    private var pendingBetterRoute: RouteData? = null
    private var pendingBetterStart: Pair<Double, Double>? = null
    private val betterRouteTask = object : Runnable {
        override fun run() {
            val s = engine.state.value
            if (!betterRouteDismissed && started && !stopped && s.running &&
                !s.arrived && s.gpsActive
            ) {
                checkBetterRoute()
            }
            betterRouteHandler.postDelayed(this, BETTER_ROUTE_INTERVAL_MS)
        }
    }

    /** 上一次相机跟随的目标点（用于拦截异常跳跃） */
    private var lastFollowLat = 0.0
    private var lastFollowLng = 0.0
    private var stopped = false

    /**
     * 提前播报调度：每个路段按距离阈值逐级提醒，位图记录本段已播过的档位。
     *
     * 以前只有「进入路段」和 500m/200m 两个固定点，路口临近时几乎没有提醒；
     * 现在改成 1 公里/500/200/50 四档，越接近路口越具体（最后一档只说动作）。
     * 这套「多远一档」的模型参考自开源 OSRM Text Instructions（BSD-2）与
     * Valhalla 的语音提示：`armDistances`/`announcementsDistancesBelow500m` 等。
     */
    private var remindStepIndex = -1
    private var remindMask = 0

    /** 当前路段实际生效的阈值集合（随 voiceMode / 是否转弯而定） */
    private var activeThresholds: IntArray = TURN_THRESHOLDS

    /**
     * 已经播报过的「路口 + 动作 + 档位」，整程只说一次。
     *
     * `remindMask` 是按路段序号重置的：路口附近 GPS 抖动、SDK 的 `curStep` 在两个
     * 值之间跳，掩码就被清掉，同一句话重播 —— 这是"反复提前播报"的直接原因。
     * 掩码管"本段哪几档说过"，这个集合管"整程哪几档说过"，两者叠加才不重不漏。
     */
    private val spokenTiers = HashSet<String>()

    /**
     * 已经说过**完整句**的路口（`"序号|动作"`）。
     *
     * 作用是保证每个路口的第一句一定带距离和序号 —— 否则起手就在临门距离内时，
     * 用户只会听到孤零零一声"左转"（真机日志里连续两次"左转"就是这么来的）。
     */
    private val spokenIntersections = HashSet<String>()

    /** 上一次转向播报的时刻，用于 [APPROACH_MIN_GAP_MS] 这个最小间隔闸门 */
    private var lastApproachMs = 0L

    /** 当前是否横屏：镜头缩放/俯角/前移都要跟着变，横屏和竖屏必须有区别 */
    private var landscapeNow = false

    /**
     * 本页用的是哪套配色（由 onCreate 在 inflate 之前定下，见那里的注释）。
     * 地图的 mapType 另由 [AmapMapConfig.applyDisplay] 按同一个
     * `AmapMapConfig.resolveDark` 决定，两边同源，不会出现"卡片白天、地图夜间"。
     */
    private var darkTheme = true

    /** 上一次已播报过的路段序号：进入新路段时播一次「前方 X 米 左/右转」 */
    private var lastAnnouncedStep = -1

    /**
     * 电子眼播报去重：`"点位流水号|距离档"` 的集合。
     *
     * 以前按「类型 + 限速」+ 30 秒冷却去重，问题有两个：
     *  1. 一条路上连着两个同型号测速头，第二个会被 30 秒冷却整个吞掉 → 漏报；
     *  2. 同一个探头只能念一次，800 米那句听完，到 300 米就没有确认了。
     * 现在按引擎给的点位流水号（同一点位全程不变）+ **档位值**（200/500…）记，
     * 同一个点位最多按 [com.motohud.ble.navi.CameraKind.tiersOf] 给的档数念，
     * 不同点位互不影响。
     */
    private val cameraSpoken = HashSet<String>()

    /** 上一条电子眼播报的时刻，用于 [CAMERA_MIN_GAP_MS] 跨串间隔 */
    private var lastCameraSpeakMs = 0L

    /** 上一条电子眼播报的**分组**（[com.motohud.ble.navi.CameraKind.group]） */
    private var lastCameraGroup = -1

    /** 上一次播报同组电子眼的时刻，用于 [CAMERA_SAME_GROUP_MS] */
    private var lastCameraGroupMs = 0L

    /**
     * 内置语音包实例（装模型时为 null）。
     *
     * 只为一件事存在：**念之前先问它念不念得全**。内置包是预录片段，
     * 句子里混进没录过的字（限速数字、新类型名）会被贪心匹配悄悄跳过，
     * 听感上就是"漏字"。宁可退回固定句式，也别念半句。
     */
    private var presetSpeaker: com.motohud.ble.navi.PresetPackSpeaker? = null

    /** 上一条电子眼提示的色标分组，避免每帧都 setTextColor（会触发重绘） */
    private var lastCameraTone = -1

    /** 已经念过的交通事件（按标题去重）：同一条封路信息不该每帧重复播 */
    private val incidentSpoken = HashSet<String>()

    /** 已经念过的服务区（按名称去重） */
    private val serviceAreaSpoken = HashSet<String>()

    /**
     * 预合成去重：记录已经交给引擎预热的路段序号。
     *
     * 重新算路时 [resetRoute] 会把它清回 -1 —— 新路线的同一个序号通常是另一条路，
     * 不清的话下一段永远不会被预热。
     */
    private var prewarmedStep = -1

    /** 本次导航是否已调用过 engine.start（屏蔽引擎里上一次导航的残留状态） */
    private var started = false

    /** 导航历史：本次导航对应的记录 id，停止/到达时用它补完用时与车况 */
    private var historyId: Long? = null

    /** 已结束但还要能"查看轨迹"的记录 id */
    private var finishedHistoryId: Long? = null

    /** 本次导航开始时刻（到达卡里算用时/均速） */
    private var naviStartMs = 0L

    /** 用户手动拖动地图后暂停自动跟随，点「回中」恢复 */
    private var userPanned = false

    /** 路口前自动放大：stepRemain < 200m 且是转弯类图标 */
    private var zoomBoost = false

    /** 底部卡是否展开（默认收起，只显示 ETA/剩余/速度） */
    private var cardExpanded = false

    /** 是否处于画中画小窗（此时不能 detach 转发） */
    private var inPip = false

    /** 是否已提示过定位精度差（避免反复弹） */
    private var weakGpsToastShown = false

    /**
     * 当前路线的途经点（本次导航全部有效途经点，含中途追加的）。
     *
     * 为什么要在导航页也存一份：以前途经点只活在选路线页的算路参数里，
     * 导航页拿到的是算好的 [RouteData]，再想重算就只剩起终点 ——
     * 表现就是"中途加一个点，之前设的途经点全没了"。
     */
    private var waypoints: MutableList<Pair<Double, Double>> = mutableListOf()

    /** 是否已提示过地图瓦片加载失败（一次导航只提一次） */
    private var tileFailHintShown = false

    /** 已经播过要素提示的路段下标（同一段不重复播；导航开始时清空） */
    private val featureAnnounced = HashSet<Int>()

    /**
     * 已经播过的弯道（key = `步骤下标|弯道在本段的位置`）。
     *
     * 必须带上步骤下标：不同路段都会从 0 米开始，只用位置会互相顶掉。
     */
    private val curveAnnounced = HashSet<String>()

    /**
     * 驾驶行为感知（急刹 / 急加速 / 急转弯）。
     *
     * 默认不播报（见 DriveBehaviorMonitor 的说明），但一直计数 ——
     * 骑行结束后的回顾需要这些数据，而且它是"这次骑得猛不猛"的客观依据。
     */
    private val behaviorMonitor = com.motohud.ble.navi.DriveBehaviorMonitor()

    /** 每段路线的弯道提示缓存（步骤下标 → 本段弯道列表），避免每秒重算几何 */
    private val curveCache = HashMap<Int, List<com.motohud.ble.navi.RoadShape.CurveHint>>()

    /**
     * 坡度感知（气压计）。设备没有气压计时 [terrainSense.isAvailable] 为 false，
     * 整套逻辑静默关闭。
     */
    private var terrainSense: com.motohud.ble.navi.TerrainSense? = null

    /** 上一次坡度提示的时间：长下坡可能持续几公里，不能一直念 */
    private var terrainSpokenAt = 0L

    /** 上一次送进坡度模块的已行驶里程，用于算帧间前进距离 */
    private var lastFeedTraveled = -1

    /** 上一次喂驾驶行为模块的时间戳 */
    private var lastBehaviorFeedMs = 0L

    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 无定位时引擎自动走推算模式 */ }

    /** 画中画里的「结束导航」按钮广播 */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_STOP) {
                stopNavi(sendFinish = true)
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        /*
         * ===================== 日 / 夜主题 =====================
         * 必须在 super.onCreate / setContentView **之前**定下来。
         *
         * 导航页的配色全部写成 `?attr/nvXxx`（见 values/attrs.xml），这类引用
         * 是在**布局解析那一刻**从主题里取值的；setContentView 之后再 setTheme
         * 只会影响之后新建的 View，已经 inflate 的 TextView 颜色一个字都不会变。
         *
         * 自动档要算日出日落，而这时地图还没建、拿不到 cameraPosition，
         * 所以用**终点经纬度**顶替 —— 一次导航的起终点必然在同一座城市，
         * 日出日落差不出几分钟。拿不到终点时 resolveDark 自己退回时间判断。
         */
        NaviPrefs.init(this)
        darkTheme = com.motohud.ble.ui.theme.AppTheme.isDark(
            intent.getDoubleExtra(EXTRA_LAT, 0.0),
            intent.getDoubleExtra(EXTRA_LNG, 0.0)
        )
        setTheme(com.motohud.ble.ui.theme.AppTheme.styleOf(darkTheme))

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guidance)

        app = application as MotoHudApp
        engine = app.naviEngine
        ble = app.bleManager

        // 偏航 → 自动重新规划（开错路时上层负责算新路线）
        engine.onRerouteRequest = { lat, lng -> requestReroute(lat, lng) }

        /*
         * SDK 模式的播报：直接用高德官方文本（`onGetNavigationText`）。
         *
         * 为什么不再走自研的三档阈值：官方文本的**触发时机**由 SDK 按实时车速、
         * 路口类型、剩余距离综合判断（高速上会提前到 2 公里，市区路口 50 米才报），
         * 固定的 1100/550/临门 三档做不到这一点。两套同时跑只会互相打断。
         *
         * 但发声仍然是本 App 的 TTS（引擎里 `setUseInnerVoice(false)`），
         * 所以离线语音包念路名、语速音量、播报仲裁全都保留。
         */
        engine.onSdkSpeech = { text ->
            if (!stopped) say(text, cue = com.motohud.ble.navi.SpeechArbiter.TRAFFIC)
        }

        NaviPrefs.init(this)
        // 坡度感知：设备有气压计才创建，没有就留 null（整套逻辑静默关闭）
        terrainSense = com.motohud.ble.navi.TerrainSense(this)
        bindViews()
        applyThemeExtras()
        applyWindowInsets()
        // 旋转不重建 Activity，布局形态（横屏右下面板 / 竖屏底部通栏）由这里统一施加
        applyOrientationLayout(
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
        applyScreenPrefs()

        destName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { getString(R.string.dest_default) }
        destLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        destLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        waypoints = parseWaypoints(intent.getStringExtra(EXTRA_WAYPOINTS))

        tvDestName.text = destName
        tvDestSub.text = CoordConverter.formatLatLng(destLat, destLng)

        ensureLocationPermission()
        setupMap(savedInstanceState)
        setupShadowNaviView(savedInstanceState)
        initVoice()
        bindActions()
        updateModeBadge()
        updateVoiceButton()
        observeState()

        // 画中画的「结束导航」按钮：动态注册（不需要 exported 广播）
        ContextCompat.registerReceiver(
            this, pipReceiver, IntentFilter(ACTION_PIP_STOP),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 低版本没有画中画。注意还要判横屏：applyOrientationLayout 已经把横屏的
        // 画中画入口收掉了，这里不能再无脑改回 VISIBLE（否则横屏侧栏会多一个按钮，
        // 与"左上角/侧栏只留必要控件"的设计冲突）。
        btnPip.visibility = if (!landscapeNow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            View.VISIBLE
        } else {
            View.GONE
        }

        /*
         * 模拟导航：必须在 planAndStart() 之前置位。
         * 导航 SDK 的算路是异步的，置位后由引擎在 startNavi 那一刻选 NaviType.EMULATOR。
         */
        if (intent.getBooleanExtra(EXTRA_EMULATOR, false)) {
            engine.startEmulator(
                intent.getIntExtra(EXTRA_SPEED, EMULATOR_SPEED_KMH).coerceIn(5, 200)
            )
        }

        planAndStart()

        // 周期性检查更优/更畅通路线（偏航重算由引擎回调触发，这里负责"路况变化换线"）
        betterRouteHandler.postDelayed(betterRouteTask, BETTER_ROUTE_INTERVAL_MS)
    }

    private fun bindViews() {
        topBar = findViewById(R.id.top_bar)
        bottomInfo = findViewById(R.id.bottom_info)
        bottomExtra = findViewById(R.id.bottom_extra)
        tvDestName = findViewById(R.id.tv_dest_name)
        tvDestSub = findViewById(R.id.tv_dest_sub)
        tvModeBadge = findViewById(R.id.tv_mode_badge)
        btnVoice = findViewById(R.id.btn_voice)
        turnBanner = findViewById(R.id.turn_banner)
        ivTurnIcon = findViewById(R.id.iv_turn_icon)
        tvTurnInstruction = findViewById(R.id.tv_turn_instruction)
        tvTurnRoad = findViewById(R.id.tv_turn_road)
        tvTurnNext = findViewById(R.id.tv_turn_next)
        tvTurnDistance = findViewById(R.id.tv_turn_distance)
        tvEta = findViewById(R.id.tv_eta)
        tvRemain = findViewById(R.id.tv_remain)
        tvSpeed = findViewById(R.id.tv_speed_live)
        tvGpsMode = findViewById(R.id.tv_gps_mode)
        tvGpsQuality = findViewById(R.id.tv_gps_quality)
        statGps = findViewById(R.id.stat_gps)
        tvSignalWarn = findViewById(R.id.tv_signal_warn)
        laneGuideView = findViewById(R.id.ll_lane_guide)
        llCameraWarn = findViewById(R.id.ll_camera_warn)
        tvCameraWarn = findViewById(R.id.tv_camera_warn)
        tvLimitBadge = findViewById(R.id.tv_limit_badge)
        ivCrossImage = findViewById(R.id.iv_cross_image)
        crossPanel = findViewById(R.id.ll_cross_panel)
        ivCrossTurn = findViewById(R.id.iv_cross_turn)
        tvCrossDistance = findViewById(R.id.tv_cross_distance)
        tvCrossTitle = findViewById(R.id.tv_cross_title)
        tvCrossEta = findViewById(R.id.tv_cross_eta)
        tvCrossRemain = findViewById(R.id.tv_cross_remain)
        tvCrossSpeed = findViewById(R.id.tv_cross_speed)
        tvNaviNote = findViewById(R.id.tv_navi_note)
        tvBleHint = findViewById(R.id.tv_guidance_ble_hint)
        tvLights = findViewById(R.id.tv_lights)
        tvTolls = findViewById(R.id.tv_tolls)
        tvStepProgress = findViewById(R.id.tv_step_progress)
        tvTrafficEstimate = findViewById(R.id.tv_traffic_estimate)
        planningOverlay = findViewById(R.id.planning_overlay)
        arrivedOverlay = findViewById(R.id.arrived_overlay)
        mapActions = findViewById(R.id.map_actions)
        quickActions = findViewById(R.id.quick_actions)
        btnRecenter = findViewById(R.id.btn_recenter)
        topScrim = findViewById(R.id.top_scrim)
        btnPip = findViewById(R.id.btn_pip)
        tvArriveSub = findViewById(R.id.tv_arrive_sub)
        tvArriveDistance = findViewById(R.id.tv_arrive_distance)
        tvArriveDuration = findViewById(R.id.tv_arrive_duration)
        tvArriveAvg = findViewById(R.id.tv_arrive_avg)
        betterRouteCard = findViewById(R.id.better_route_card)
        tvBetterRoute = findViewById(R.id.tv_better_route)
    }

    /**
     * 主题定了之后还要"手工补"的几处。
     *
     * 布局里能用 `?attr/nvXxx` 表达的都已经自动生效了，这里只处理那些
     * **没法写成资源**的东西：
     *
     * 1. **转向图标的 padding** —— 白天要给官方白箭头垫一块蓝底（`bg_turn_badge`），
     *    不内缩的话箭头会顶到蓝底边缘，看着像贴纸。夜间没有底衬，padding 必须是 0，
     *    否则 88dp 的箭头白白缩小一圈，横屏侧栏本来就小。
     * 2. **状态栏图标深浅** —— 白天顶部 scrim 是白的，白色状态栏图标直接消失。
     *    主题里的 `android:windowLightStatusBar` 只管到系统认定的主题，用
     *    WindowInsetsControllerCompat 再兜一层更稳（部分 OEM 只认后者）。
     */
    /**
     * 解析一个主题属性成实际颜色。
     *
     * 属性值可能指向颜色资源（`@color/pal_day_danger`），也可能是内联色值，
     * 两条路都要能走 —— 所以先看 `resourceId`，没有再取 `data`。
     */
    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        if (!theme.resolveAttribute(attr, tv, true)) return Color.WHITE
        return when {
            tv.resourceId != 0 -> ContextCompat.getColor(this, tv.resourceId)
            tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
                tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT -> tv.data
            else -> Color.WHITE
        }
    }

    private fun applyThemeExtras() {
        val dense = resources.displayMetrics.density
        // 内缩量按"底衬还剩多少可呼吸的边"来定，不是按图标的百分比：
        // 88dp 底衬收 5dp、44dp 收 3dp，箭头才撑得起来。收太多（试过 8dp）
        // 箭头会缩成一个小白点，远看只剩一块蓝。
        val padBig = if (darkTheme) 0 else (5 * dense).roundToInt()
        val padSmall = if (darkTheme) 0 else (3 * dense).roundToInt()
        ivTurnIcon.setPadding(padBig, padBig, padBig, padBig)
        ivCrossTurn.setPadding(padSmall, padSmall, padSmall, padSmall)

        /*
         * 3. **悬浮圆钮的投影** —— 夜间深色按钮压在地图上，靠颜色就分得清；
         *    白天是白按钮压在白/浅灰地图上，只靠 1dp 描边几乎看不出边界
         *    （真机截图里那几个圆钮像是"印"在地图上的）。给一点 elevation
         *    让系统画一圈柔和阴影，比加粗描边自然得多。
         */
        val lift = if (darkTheme) 0f else 3 * dense
        listOf(R.id.btn_back, R.id.btn_voice, R.id.btn_cast, R.id.btn_overview,
            R.id.btn_recenter, R.id.btn_pip).forEach { id ->
            findViewById<View>(id)?.elevation = lift
        }

        applySystemBarAppearance()
    }

    /**
     * 状态栏 / 导航栏图标的深浅。
     *
     * **必须在每次旋转后再设一次**：本页 `configChanges` 带了 orientation，
     * 转屏不重建 Activity，但系统会重算一次 Window 的 decor insets，
     * 白天的"深色图标"标记会被丢回默认（白图标）。
     * 真机复现：白天横屏图标是深灰，转竖屏后变纯白，正好压在浅色地图上
     * —— 时钟基本看不见。
     */
    private fun applySystemBarAppearance() {
        runCatching {
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            ctl.isAppearanceLightStatusBars = !darkTheme
            ctl.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    /**
     * 按 WindowInsets 给顶栏/底栏留出状态栏与导航栏高度。
     * 这比写死 44dp 可靠：刘海屏、挖孔屏、手势导航条的数值都不一样。
     */
    private fun applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val baseTop = (4 * resources.displayMetrics.density).roundToInt()
        val baseBottom = (12 * resources.displayMetrics.density).roundToInt()
        val baseEnd = (18 * resources.displayMetrics.density).roundToInt()
        /** 顶栏 / 返回键的左侧内边距，与 XML 里的 12dp 对齐 */
        val baseStart = (12 * resources.displayMetrics.density).roundToInt()
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 横屏侧栏贴右边：右侧系统栏（三键导航横屏常驻右侧）要一并让开
            v.updatePadding(top = bars.top + baseTop, right = bars.right + baseStart)
            insets
        }
        // 返回键独立悬浮在左上角，位置得自己算：顶栏那套 padding 已经带不动它了
        val btnBack = findViewById<View>(R.id.btn_back)
        ViewCompat.setOnApplyWindowInsetsListener(btnBack) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + baseTop
                marginStart = bars.left + baseStart
                v.layoutParams = this
            }
            insets
        }
        // 语音 / 投屏 / 定位悬浮容器：竖屏横排贴右上角（让开右侧系统栏/手势条）；
        // 横屏**竖排一列挂在左上角返回键下面**（marginStart 与返回键对齐，
        // topMargin 让过键高 44dp + 8dp 间距）。
        ViewCompat.setOnApplyWindowInsetsListener(quickActions) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val belowBack = ((44 + 8) * resources.displayMetrics.density).toInt()
            (v.layoutParams as FrameLayout.LayoutParams).apply {
                if (gravity and Gravity.END == Gravity.END) {
                    topMargin = bars.top + baseTop
                    marginStart = 0
                    marginEnd = bars.right + baseStart
                } else {
                    topMargin = bars.top + baseTop + belowBack
                    marginStart = bars.left + baseStart
                    marginEnd = 0
                }
                v.layoutParams = this
            }
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomInfo) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (landscapeNow) {
                // 横屏侧栏在右侧：按右边系统栏留白，避开手势条/挖孔；左侧留少量内边距
                v.updatePadding(
                    left = baseEnd,
                    right = bars.right + baseEnd,
                    top = baseTop,
                    bottom = bars.bottom + baseBottom
                )
            } else {
                // 竖屏底部通栏：右边吃 inset，避开侧边手势条
                v.updatePadding(bottom = bars.bottom + baseBottom, right = bars.right + baseEnd)
            }
            insets
        }
        // 路口大图整卡是满高的深色栏，不做处理会把状态栏的时间/电量压在卡片上。
        // **不能用 inset listener**：这个 View 初始是 GONE，GONE 的 View 不接收
        // insets 分发，等它变 VISIBLE 时也不会补发一次（真机截图里电池图标就印在卡上）。
        // 所以直接量状态栏高度，在 setCrossMode 打开时写进 padding。
        crossPanelBaseTop = (10 * resources.displayMetrics.density).toInt()
        statusBarPx = runCatching {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
    }

    private fun bindActions() {
        findViewById<View>(R.id.btn_back).setOnClickListener { confirmExit() }
        // 顶栏投屏钮 = 「投到车机」开关（已不再是跳页面）
        findViewById<View>(R.id.btn_cast).setOnClickListener { showCastDialog() }
        findViewById<View>(R.id.btn_cast_navi).setOnClickListener { showCastDialog() }
        findViewById<View>(R.id.btn_stop_navi).setOnClickListener { confirmExit() }
        findViewById<View>(R.id.btn_finish_navi).setOnClickListener {
            stopNavi(sendFinish = true)
            finish()
        }
        btnPip.setOnClickListener { enterPip() }
        findViewById<View>(R.id.btn_expand).setOnClickListener { toggleCard() }
        findViewById<View>(R.id.btn_view_track).setOnClickListener {
            val id = finishedHistoryId
            if (id == null || id <= 0) {
                Toast.makeText(this, R.string.guidance_no_track, Toast.LENGTH_SHORT).show()
            } else {
                NaviHistoryDetailActivity.start(this, id)
            }
        }
        findViewById<View>(R.id.btn_navi_again).setOnClickListener { naviAgain() }

        findViewById<View>(R.id.btn_better_adopt).setOnClickListener {
            val cand = pendingBetterRoute
            val start = pendingBetterStart
            if (cand != null && start != null) {
                applyRoute(cand, start)
                Toast.makeText(this, R.string.guidance_better_route_adopted, Toast.LENGTH_SHORT).show()
                // 卡片是给用户看的，声音是给"没在看屏幕"的人听的：两者都要有
                say("已为您切换到更快路线", SpeechPriority.INFO, cue = com.motohud.ble.navi.SpeechArbiter.BETTER_ROUTE)
            }
            hideBetterRouteCard()
        }
        findViewById<View>(R.id.btn_better_dismiss).setOnClickListener {
            // 本次导航不再提示，避免打扰
            betterRouteDismissed = true
            hideBetterRouteCard()
        }

        btnVoice.setOnClickListener {
            val on = !NaviPrefs.voice.value
            NaviPrefs.setVoice(on)
            updateVoiceButton()
            voice?.setMuted(!on)
            // 开着车看不见屏幕，用一声确认代替「开了吗？」的犹豫
            say(if (on) "语音播报已开启" else "语音播报已关闭", SpeechPriority.INFO, cue = com.motohud.ble.navi.SpeechArbiter.UI_CONFIRM)
        }
        // 长按选播报档位（详细/简洁/只播转弯）：单击是最高频的开/关，不该被设置项挤掉
        btnVoice.setOnLongClickListener {
            showVoiceModeDialog()
            true
        }
        tvModeBadge.setOnClickListener { showNaviOptions() }

        /*
         * 长按转向横幅 = 切主辅路（导航 SDK `switchParallelRoad`）。
         * 只在 SDK 报了「有平行路」时生效（`notifyParallelRoad(type>0)`），
         * 否则按了也没反应，用 Toast 说明而不是静默失败。
         * 骑行最怕的就是上了主路却该走辅路，绕回来要多跑好几公里。
         */
        turnBanner.setOnLongClickListener {
            if (engine.state.value.parallelRoad > 0) {
                engine.switchParallelRoad()
                Toast.makeText(this, R.string.navi_parallel_switched, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.navi_parallel_unavailable, Toast.LENGTH_SHORT).show()
            }
            true
        }
        findViewById<View>(R.id.btn_overview).setOnClickListener {
            route?.let { drawRoute(it, fitAll = true) }
        }
        findViewById<View>(R.id.btn_recenter).setOnClickListener {
            userPanned = false
            lastCameraMs = 0L
            followOnMap(engine.state.value, force = true)
        }
    }

    private fun updateVoiceButton() {
        btnVoice.setImageResource(
            if (NaviPrefs.voice.value) R.drawable.ic_volume_on else R.drawable.ic_volume_off
        )
        btnVoice.alpha = if (NaviPrefs.voice.value) 1f else 0.55f
        // 档位不是「详细」时角标提示一下，否则用户不知道长按改过什么
        btnVoice.contentDescription = getString(
            when (NaviPrefs.voiceMode.value) {
                NaviPrefs.VOICE_MODE_BRIEF -> R.string.navi_voice_mode_brief
                NaviPrefs.VOICE_MODE_TURN_ONLY -> R.string.navi_voice_mode_turn_only
                else -> R.string.navi_voice
            }
        )
    }

    /** 播报档位选择 */
    private fun showVoiceModeDialog() {
        val modes = arrayOf(
            getString(R.string.navi_voice_mode_detail),
            getString(R.string.navi_voice_mode_brief),
            getString(R.string.navi_voice_mode_turn_only)
        )
        com.motohud.ble.ui.common.MotoDialog.builder(this)
            .setTitle(R.string.navi_voice_mode_title)
            .setSingleChoiceItems(modes, NaviPrefs.voiceMode.value) { d, which ->
                NaviPrefs.setVoiceMode(which)
                updateVoiceButton()
                // 立刻用一声实测让用户听到档位效果，不用等下一个路口
                say("前方 300 米右转，请按语音提示行驶", SpeechPriority.INFO, cue = com.motohud.ble.navi.SpeechArbiter.UI_CONFIRM)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateModeBadge() {
        tvModeBadge.text = NaviPrefs.summary()
    }

    // ---------- 路线规划 + 启动 ----------

    private fun planAndStart() {
        // 从「选择路线」页交接过来的方案：直接用，不再重复算路
        val handed = RouteHandoff.take(destName, destLat, destLng)
        if (handed != null) {
            route = handed
            drawRoute(handed, fitAll = true)
            tvDestSub.text = routeSummary(handed)
            begin(currentStart(), handed)
            return
        }

        val start = currentStart()
        if (!AmapManager.hasKey(this) || !AmapManager.isPrivacyAgreed(this)) {
            Toast.makeText(this, R.string.guidance_demo_mode, Toast.LENGTH_LONG).show()
            begin(start, null)
            return
        }
        planningOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            AmapManager.initSearch(this@GuidanceActivity)
            val from = start ?: NaviEngine.DEFAULT_START
            val strategy = NaviPrefs.strategy()
            val summary = NaviPrefs.summary()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AmapRepo(this@GuidanceActivity)
                        .planDriveRoute(from.first, from.second, destLat, destLng, strategy, summary)
                }
            }
            planningOverlay.visibility = View.GONE
            val r = result.getOrNull()
            when {
                r is RoutePlanResult.Success -> {
                    route = r.route
                    drawRoute(r.route, fitAll = true)
                    tvDestSub.text = routeSummary(r.route)
                    begin(start, r.route)
                }
                r is RoutePlanResult.Failure -> {
                    Toast.makeText(
                        this@GuidanceActivity,
                        getString(R.string.guidance_plan_failed, r.message),
                        Toast.LENGTH_LONG
                    ).show()
                    // 当前偏好算不出时退回默认策略再试一次，避免直接掉到离线引导
                    fallbackToDefault(from)
                }
                else -> begin(start, null)
            }
        }
    }

    private fun routeSummary(r: RouteData): String = buildString {
        append(CoordConverter.formatDistance(r.distanceM.toInt()))
        append(" · ")
        append(CoordConverter.formatEta(r.durationS.toInt()))
        val note = r.label.ifBlank { r.modeLabel }
        if (note.isNotBlank()) {
            append(" · ")
            append(note)
        }
    }

    /** 当前偏好的策略算不出路线时，用高德默认策略再试一次 */
    private fun fallbackToDefault(from: Pair<Double, Double>) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AmapRepo(this@GuidanceActivity).planDriveRoute(
                        from.first, from.second, destLat, destLng,
                        com.amap.api.services.route.RouteSearch.DRIVING_MULTI_STRATEGY_FASTEST_SHORTEST,
                        getString(R.string.route_badge_recommended)
                    )
                }
            }
            val r = result.getOrNull()
            if (r is RoutePlanResult.Success) {
                route = r.route
                drawRoute(r.route, fitAll = true)
                tvDestSub.text = routeSummary(r.route)
                begin(from, r.route)
            } else {
                begin(from, null)
            }
        }
    }

    private fun begin(start: Pair<Double, Double>?, r: RouteData?) {
        turnBanner.visibility = View.VISIBLE
        arrivedOverlay.visibility = View.GONE
        engine.onStepChanged = null   // 播报改由 render 按 stepIndex 驱动，见 announceStepEntered
        // 引擎为全局共享实例，上一次导航的 arrived=true 仍留在 StateFlow 里，
        // 必须先置位再 start，避免首帧按旧状态误判「已到达」。
        started = true
        stopped = false
        remindStepIndex = -1
        remindMask = 0
        activeThresholds = TURN_THRESHOLDS
        lastAnnouncedStep = -1
        spokenTiers.clear()
        spokenIntersections.clear()
        lastApproachMs = 0L
        prewarmedStep = -1
        featureAnnounced.clear()
        cameraSpoken.clear()
        lastCameraSpeakMs = 0L
        lastCross = null
        crossShownAt = 0L
        incidentSpoken.clear()
        serviceAreaSpoken.clear()
        curveAnnounced.clear()
        curveCache.clear()
        // 新一次导航必须重置感知器：上一次的累计里程/加减速历史留着会立刻误报
        behaviorMonitor.reset()
        lastFeedTraveled = -1
        lastBehaviorFeedMs = 0L
        terrainSpokenAt = 0L
        terrainSense?.let { if (it.isAvailable()) it.start() }
        naviStartMs = System.currentTimeMillis()
        // 开始播报：骑行中不看屏幕也能确认导航已启动、大概多远、预计多久
        if (NaviPrefs.voiceMode.value != NaviPrefs.VOICE_MODE_TURN_ONLY) {
            say(buildStartText(r), SpeechPriority.INFO, cue = com.motohud.ble.navi.SpeechArbiter.START)
        }
        engine.start(destName, destLat, destLng, start, r)
        // 前两段引导语提前预合成：起步后第一句就已经是「前方 xx 右转」，
        // 等渲染循环走到那一步再合成必然慢半拍
        prewarmStep(0)
        prewarmStep(1)
        // 前台服务：**每次导航都启动**，它承载通知栏导航条（息屏/兜里瞥一眼方向）；
        // 唤醒锁只由「息屏导航」开关决定，见 NaviForegroundService.updateWakeLock
        com.motohud.ble.service.NaviForegroundService.start(this)
        // 导航历史 + 轨迹：先把「开始」记下来，结束时压缩写回
        historyId = com.motohud.ble.data.NaviHistory.begin(
            app = app,
            destName = destName,
            destLat = destLat,
            destLng = destLng,
            startName = start?.let { CoordConverter.formatLatLng(it.first, it.second) } ?: getString(R.string.common_unknown),
            startLat = start?.first ?: 0.0,
            startLng = start?.second ?: 0.0,
            distanceM = r?.distanceM?.toDouble() ?: 0.0
        )
        historyId?.let { NaviTrack.begin(app, it) }
        if (start == null) {
            Toast.makeText(this, R.string.guidance_no_location, Toast.LENGTH_LONG).show()
        }
        // 限行提醒：高德 getRestriction() 一直在给，以前整条链路上没人读。
        // 只在进入导航那一刻提示一次 —— 行进中再弹会盖住转向横幅。
        if ((r?.restriction ?: 0) != 0) {
            Toast.makeText(this, R.string.settings_route_restricted, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 起点：优先用导航引擎的实时位置（导航中重新算路必须从这里出发，
     * 否则会用系统缓存里的旧位置，算出几百公里的离谱路线）；
     * 其次用系统最后已知位置（WGS84→GCJ02，超过 5 分钟视为过期）。
     */
    @Suppress("MissingPermission")
    private fun currentStart(): Pair<Double, Double>? {
        val st = engine.state.value
        // 只有「本次导航已经开跑」时才用引擎位置，否则可能是上一次导航残留
        if (started && st.running && st.latitude != 0.0 && st.longitude != 0.0) {
            return st.latitude to st.longitude
        }
        // 其次用「选择路线」页算路时用的起点，保证路线与起点一致
        val fromLat = intent.getDoubleExtra(EXTRA_FROM_LAT, 0.0)
        val fromLng = intent.getDoubleExtra(EXTRA_FROM_LNG, 0.0)
        if (fromLat != 0.0 && fromLng != 0.0) return fromLat to fromLng

        val granted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            val best = listOfNotNull(
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ).maxByOrNull { it.time }
            if (best == null) return null
            val fresh = System.currentTimeMillis() - best.time < 5 * 60_000L
            if (!fresh) return null
            com.motohud.ble.amap.CoordFix.gpsToGcj(best.latitude, best.longitude)
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 路线偏好 ----------

    private fun showRoutePrefsDialog() {
        val d = RoutePrefsDialog()
        d.onApplied = {
            updateModeBadge()
            replan()
        }
        d.show(supportFragmentManager, "route_prefs")
    }

    /** 按当前偏好重新算路（不退出页面） */
    private fun replan() {
        planningOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            AmapManager.initSearch(this@GuidanceActivity)
            val from = currentStart() ?: NaviEngine.DEFAULT_START
            val strategy = NaviPrefs.strategy()
            val summary = NaviPrefs.summary()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AmapRepo(this@GuidanceActivity)
                        .planDriveRoute(from.first, from.second, destLat, destLng, strategy, summary)
                }
            }
            planningOverlay.visibility = View.GONE
            val r = result.getOrNull()
            if (r is RoutePlanResult.Success) {
                route = r.route
                drawRoute(r.route, fitAll = true)
                tvDestSub.text = routeSummary(r.route)
                started = true
                engine.start(destName, destLat, destLng, from, r.route)
                userPanned = false
                lastCameraMs = 0L
            } else if (r is RoutePlanResult.Failure) {
                Toast.makeText(
                    this@GuidanceActivity,
                    getString(R.string.guidance_plan_failed, r.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------- 偏航重算 / 更优换线 ----------

    /**
     * 应用一条新路线（绘制 + 让引擎从当前位置接管），复用同一引擎实例与历史记录，
     * 不新建导航、不重置计时。偏航重算与主动换线共用。
     */
    private fun applyRoute(r: RouteData, start: Pair<Double, Double>) {
        route = r
        drawRoute(r, fitAll = false)
        tvDestSub.text = routeSummary(r)
        started = true
        stopped = false
        // 换了路线：路段序号全部重排，预合成去重必须复位，否则新路线的下一段
        // 永远不会被预热（表现就是重新算路后又只剩语音包在响）
        prewarmedStep = -1
        // 沿途提示的"已播/已算"标记同样按段号记，路线一换就全部对不上：
        // 不清的话，新路线第 3 段会被当成"这条路的第 3 段已经播过"而整段沉默，
        // 弯道缓存更糟——拿旧路线的弯道位置去比新路线的里程，提示会出现在完全不相干的地方。
        featureAnnounced.clear()
        curveAnnounced.clear()
        curveCache.clear()
        engine.start(destName, destLat, destLng, start, r)
    }

    /** 从指定起点重新规划并应用（联网） */
    private fun planAndReplace(start: Pair<Double, Double>) {
        planAndReplace(start, buildOptions())
    }

    /**
     * 当前生效的算路参数。
     *
     * 重算路线（偏航 / 更优换线 / 中途加途经点）时必须**原样带上**这些约束，
     * 否则用户设了「不走高速」「避开轮渡」「途经点」，一次重算就全被丢掉 ——
     * 表现是"重新规划后又把我导上高速了"。
     */
    private fun buildOptions(): RouteQueryOptions = RouteQueryOptions(
        waypoints = waypoints.toList(),
        excludeMotorway = NaviPrefs.excludeMotorway(),
        avoidFerry = NaviPrefs.avoidFerry.value,
        carType = NaviPrefs.carType.value
    )

    /** 从指定起点重新规划并应用（联网，带算路约束） */
    private fun planAndReplace(start: Pair<Double, Double>, options: RouteQueryOptions) {
        if (!AmapManager.hasKey(this) || !AmapManager.isPrivacyAgreed(this)) {
            Toast.makeText(this, R.string.guidance_reroute_no_key, Toast.LENGTH_SHORT).show()
            return
        }
        planningOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            AmapManager.initSearch(this@GuidanceActivity)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AmapRepo(this@GuidanceActivity)
                        .planDriveRoute(
                            start.first,
                            start.second,
                            destLat,
                            destLng,
                            NaviPrefs.strategy(),
                            NaviPrefs.summary(),
                            options
                        )
                }
            }
            planningOverlay.visibility = View.GONE
            val r = result.getOrNull()
            when (r) {
                is RoutePlanResult.Success -> applyRoute(r.route, start)
                is RoutePlanResult.Failure ->
                    Toast.makeText(this@GuidanceActivity, R.string.guidance_reroute_failed, Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(this@GuidanceActivity, R.string.guidance_reroute_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 引擎偏航回调：已持续偏离路线，提示并重新规划 */
    private fun requestReroute(lat: Double, lng: Double) {
        Toast.makeText(this, R.string.guidance_rerouting, Toast.LENGTH_SHORT).show()
        // 偏航是骑行中最需要马上知道的事，但眼睛在看路 —— 必须说出来
        say("您已偏离路线，正在重新规划", SpeechPriority.SAFETY, cue = com.motohud.ble.navi.SpeechArbiter.REROUTE)
        hideBetterRouteCard()
        planAndReplace(lat to lng)
    }

    // ---------- 导航中调整路线（加途经点） ----------

    /**
     * 顶栏模式角标点开的「导航选项」。
     *
     * 为什么挂在角标上而不是新增一颗常驻按钮：导航页已经很满（横屏侧栏只有 300dp），
     * 再加一颗「+途经」会挤掉转向横幅。角标本身就是"这条路线是怎么算的"，
     * 点它出来"改这条路线"的操作，心智模型是对的。
     */
    private fun showNaviOptions() {
        val items = arrayOf(
            getString(R.string.navi_opt_add_waypoint),
            getString(R.string.settings_route_prefs_title)
        )
        MotoDialog.builder(this)
            .setTitle(R.string.navi_opt_title)
            .setItems(items) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> pickWaypoint()
                    else -> showRoutePrefsDialog()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 途经点已满就别再拉了，免得用户选完才被告知 */
    private fun pickWaypoint() {
        if (waypoints.size >= RouteQueryOptions.MAX_WAYPOINTS) {
            Toast.makeText(
                this,
                getString(R.string.route_waypoint_limit, RouteQueryOptions.MAX_WAYPOINTS),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        // 搜索中心给当前位置：骑行中途加的点几乎都在附近（加油站、厕所、吃饭的地方）
        val s = engine.state.value
        val centerLat = if (s.latitude != 0.0) s.latitude else destLat
        val centerLng = if (s.longitude != 0.0) s.longitude else destLng
        addWaypointLauncher.launch(
            PlaceSearchActivity.intent(
                this,
                title = getString(R.string.navi_opt_add_waypoint),
                centerLat = centerLat,
                centerLng = centerLng
            )
        )
    }

    /** 选完途经点：把它追加到现有途经点末尾，然后整串重新算路 */
    private val addWaypointLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val lat = r.data?.getDoubleExtra(PlaceSearchActivity.EXTRA_LAT, 0.0) ?: 0.0
        val lng = r.data?.getDoubleExtra(PlaceSearchActivity.EXTRA_LNG, 0.0) ?: 0.0
        if (lat == 0.0 && lng == 0.0) return@registerForActivityResult
        val name = r.data?.getStringExtra(PlaceSearchActivity.EXTRA_NAME)
            .orEmpty().ifBlank { getString(R.string.navi_opt_waypoint_fallback) }

        waypoints.add(lat to lng)
        Toast.makeText(this, getString(R.string.navi_opt_waypoint_added, name), Toast.LENGTH_SHORT).show()
        // 说一句：加完必然重算，骑手看不到遮罩在转，得知道发生了什么
        say(getString(R.string.navi_opt_waypoint_voice), SpeechPriority.INFO, interrupt = false)

        val s = engine.state.value
        val start = if (s.latitude != 0.0) s.latitude to s.longitude
        else {
            val fLat = intent.getDoubleExtra(EXTRA_FROM_LAT, 0.0)
            val fLng = intent.getDoubleExtra(EXTRA_FROM_LNG, 0.0)
            if (fLat != 0.0 || fLng != 0.0) fLat to fLng else return@registerForActivityResult
        }
        planAndReplace(start)
    }

    /** 解析 `EXTRA_WAYPOINTS`（`lat,lng;lat,lng` 形式），非法项直接跳过 */
    private fun parseWaypoints(raw: String?): MutableList<Pair<Double, Double>> {
        if (raw.isNullOrBlank()) return mutableListOf()
        return raw.split(";").mapNotNull { seg ->
            val parts = seg.split(",")
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lng = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            if (lat == 0.0 && lng == 0.0) null else lat to lng
        }.toMutableList()
    }

    /** 单次联网算路，失败返回 null */
    private suspend fun planOnce(
        start: Pair<Double, Double>,
        strategy: Int,
        summary: String
    ): RouteData? {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                AmapRepo(this@GuidanceActivity)
                    .planDriveRoute(start.first, start.second, destLat, destLng, strategy, summary)
            }.getOrNull()
        }
        return (r as? RoutePlanResult.Success)?.route
    }

    /** 路线拥堵里程（TMC 状态≥3 的分段累加） */
    private fun sumCongestion(r: RouteData): Int =
        r.steps.sumOf { it.congestedM.toDouble() }.toInt()

    /** 每隔一段时间检查：更省时，或前方明显拥堵且"避拥堵"方案更畅通 */
    private fun checkBetterRoute() {
        val s = engine.state.value
        val start = s.latitude to s.longitude
        lifecycleScope.launch {
            AmapManager.initSearch(this@GuidanceActivity)
            val cur = planOnce(start, NaviPrefs.strategy(), NaviPrefs.summary())
            // 前方拥堵达到阈值，再算一条"避拥堵"方案做对比
            val avoid = if (s.congestionAheadM >= CONGESTION_TRIGGER_M) {
                planOnce(
                    start,
                    com.amap.api.services.route.RouteSearch.DRIVING_MULTI_STRATEGY_FASTEST_SHORTEST_AVOID_CONGESTION,
                    getString(R.string.route_badge_avoid_congestion)
                )
            } else null
            val candidate = avoid ?: cur ?: return@launch
            val curEta = route?.durationS?.toInt() ?: s.etaSeconds
            val newEta = candidate.durationS.toInt()
            val saveSecs = curEta - newEta
            val curCong = route?.let { sumCongestion(it) } ?: s.congestionAheadM.toInt()
            val newCong = sumCongestion(candidate)
            val saveCong = curCong - newCong
            // 更省时：快 ≥5 分钟；或更畅通：少堵 ≥1 公里且耗时不劣化太多
            val adopt = saveSecs >= BETTER_ROUTE_SAVE_SEC ||
                (avoid != null && saveCong >= BETTER_ROUTE_SAVE_CONG_M && newEta <= curEta + 300)
            if (adopt) {
                runOnUiThread { showBetterRouteCard(candidate, start, saveSecs, saveCong, avoid != null) }
            }
        }
    }

    private fun showBetterRouteCard(
        candidate: RouteData,
        start: Pair<Double, Double>,
        saveSecs: Int,
        saveCong: Int,
        isAvoid: Boolean
    ) {
        pendingBetterRoute = candidate
        pendingBetterStart = start
        val msg = if (isAvoid && saveCong >= BETTER_ROUTE_SAVE_CONG_M) {
            getString(R.string.guidance_better_route_cong, saveCong / 1000)
        } else {
            getString(R.string.guidance_better_route_time, saveSecs / 60)
        }
        tvBetterRoute.text = msg
        betterRouteCard.visibility = View.VISIBLE
        // 卡片在顶栏，骑行中未必看得见；说一声才不会错过这次换线机会
        say("前方路况变化，发现更快路线", SpeechPriority.INFO, cue = com.motohud.ble.navi.SpeechArbiter.BETTER_ROUTE)
    }

    private fun hideBetterRouteCard() {
        betterRouteCard.visibility = View.GONE
        pendingBetterRoute = null
        pendingBetterStart = null
    }

    // ---------- 地图 ----------

    /**
     * 挂一个"影子"导航视图，**只为点亮 SDK 的路口放大图能力**。
     *
     * 高德的 `AMapNaviCross` 是**网络下发的图片数据**
     * （构造器 `AMapNaviCross(int picFormat, byte[] head, byte[] body)`）。
     * SDK 只在挂着 `AMapNaviView` 时才去请求这份数据、才会回调 `showCross()`；
     * 只用裸 `AMapNavi` 时一次都不回调，`AMapNavi` 侧也没有任何替代开关
     * （javap 全量核对，只有与本需求无关的 `setIsNaviTravelView`）。
     *
     * 所以"整程看不到环岛/匝道/立交的大图"根因不是去重写错，而是**根本没有图**。
     *
     * 真机验证过：挂上它之后 `showCross()` 开始有回调（机场路一趟出 2 张）。
     * 它自己绘制的结果完全不参与界面（放在 root 最底层、alpha=0、不吃触摸），
     * 图仍然由我们画在 `ll_cross_panel`。
     */
    private fun setupShadowNaviView(savedInstanceState: Bundle?) {
        val v = findViewById<AMapNaviView?>(R.id.shadow_navi_view) ?: return
        if (!AmapManager.initMap(this)) return
        runCatching {
            // 影子视图只用来点亮能力，绝不能吃触摸事件
            v.isClickable = false
            v.isFocusable = false
            v.isFocusableInTouchMode = false
            v.setOnTouchListener { _, _ -> false }
            // ViewOptions 必须在 onCreate 之前配好，之后再改只有部分项生效
            v.viewOptions?.apply {
                setRealCrossDisplayShow(true)   // 实景路口放大图
                setModeCrossDisplayShow(true)   // 3D 模型路口图（环岛/立交多用这个）
                setEyrieCrossDisplay(true)      // 鹰眼放大图（高速匝道/分岔）
                setLaneInfoShow(false)          // 车道自己画，别在 8dp 里再画一份
                setAutoChangeZoom(false)        // 不抢缩放，免得和主地图行为不一致
                // 放大图必须给明确的显示区域。不设时 View 内部算出来可能是 0 尺寸，
                // native 侧就会认为“没有地方画”而不请求图片 —— 试验：先给 3/4 屏。
                val w = resources.displayMetrics.widthPixels
                val h = resources.displayMetrics.heightPixels
                val rect = android.graphics.Rect(w / 4, h / 4, w, h)
                setCrossLocation(rect, rect)
                setEyrieCrossLocation(rect, rect)
            }
            v.onCreate(savedInstanceState)
            v.onResume()
            // AMapNaviView.onCreate 会把 AMapNavi 与 View 绑起来，可能顺手把
            // 内置语音重新打开 —— 那样会和我们的 TTS 叠音。这里压回去。
            AmapManager.initNavi(this)
            runCatching { com.amap.api.navi.AMapNavi.getInstance(this)?.setUseInnerVoice(false) }
            // 同理，过滤开关也重设一次，保证放大图不被 SDK 的默认策略吃掉
            runCatching {
                com.amap.api.navi.AMapNavi.getInstance(this)?.naviSetting
                    ?.SetFilterShowCrossMessageStatus(false)
            }
            // 尺寸必须在导航 SDK 起来**之后**设，否则 native manager 还没 init，
            // 设进去的尺寸直接丢，等于没设。
            applyCrossImageSize()
            enableExtraCrossSwitches()
            shadowNaviView = v
            android.util.Log.i(TAG_CROSS, "shadow AMapNaviView ready")
        }.onFailure {
            android.util.Log.w(TAG_CROSS, "shadow AMapNaviView setup failed: $it")
        }
    }

    /**
     * 告诉 native 侧「放大图有多大」。
     *
     * `AMapNaviCoreManager.setCrossImageSize(w, h)` 是 `public static`，但整个 SDK 里
     * **没有任何一处调用它**（已 grep 全 jar 确认），公开文档也没有。它是给调用方自己
     * 通知 native 绘制尺寸用的：native 拿不到尺寸就按 0 处理，判定「没地方画」，
     * 于是放大图事件根本不产生 —— 光挂 `AMapNaviView`、开 `setRealCrossDisplayShow`
     * 都还是出不来。
     *
     * 真机验证：加上这一句之后，机场路一趟开始连续回调 `showCross()`（seq=1、seq=2）。
     * 尺寸按屏幕比例给即可，SDK 会自己决定位图分辨率（实测下发 500x320）并等比缩放。
     */
    private fun applyCrossImageSize() {
        runCatching {
            // 优先用根视图的实际宽高：旋转不重建 Activity，`resources.displayMetrics`
            // 不一定会立刻跟着变，而 root 一定是新方向下的真实尺寸。
            val root = findViewById<View>(R.id.guidance_root)
            val dm = resources.displayMetrics
            val sw = root?.width?.takeIf { it > 0 } ?: dm.widthPixels
            val sh = root?.height?.takeIf { it > 0 } ?: dm.heightPixels
            // 保持原比例（屏幕 42% x 78%）。实测放大图**数量少**跟这个尺寸无关
            // —— 真因是矢量图通路从来没接（见 VectorCrossRenderer），改这里只是白担风险。
            val w = (sw * 0.42f).toInt().coerceAtLeast(240)
            val h = (sh * 0.78f).toInt().coerceAtLeast(240)
            com.autonavi.amap.navicore.AMapNaviCoreManager.setCrossImageSize(w, h)
            android.util.Log.i(TAG_CROSS, "setCrossImageSize($w, $h) from ${sw}x$sh")
        }.onFailure {
            android.util.Log.w(TAG_CROSS, "setCrossImageSize failed: $it")
        }
    }

    /**
     * 放大图**数据面**的开关 —— 这是"高德 App 每个大路口都有、我们偶尔有"的真因。
     *
     *  - `AMapNaviCoreManager.setRequestGridCross(boolean)`：让引擎请求/生成放大图数据。
     *    SDK 内部唯一的调用点是 `com.amap.api.navi.core.h#h(boolean)`，而 `h(boolean)`
     *    没有任何调用者 —— 默认路径下这个开关一直停在初值，所以
     *    **`showModeCross`（矢量图）一次都不会回调**。
     *    实测：同一段机场高速，不开时整趟 0 次 `showModeCross`；开了之后 100 秒内 3 次。
     *  - `AMapNaviCoreManager.setOpenNextRoadInfo(boolean)`：提前准备下一路口的信息。
     *
     * `setRequestGridCross` 是实例方法而 SDK 没暴露单例 getter，只能反射 new 一个壳；
     * 它的 native 实现与 static 的 `setCrossImageSize` 共用同一把 static `mLock`，
     * 说明状态在 native 侧是全局的，壳对象用完即弃没有副作用。
     */
    private fun enableExtraCrossSwitches() {
        runCatching {
            val cls = Class.forName("com.autonavi.amap.navicore.AMapNaviCoreManager")
            val mgr = cls.getDeclaredConstructor().newInstance()
            cls.getMethod("setRequestGridCross", Boolean::class.javaPrimitiveType)
                .invoke(mgr, true)
            cls.getMethod("setOpenNextRoadInfo", Boolean::class.javaPrimitiveType)
                .invoke(null, true)
            android.util.Log.i(TAG_CROSS, "extra cross switches on")
        }.onFailure {
            android.util.Log.w(TAG_CROSS, "extra cross switches failed: $it")
        }
    }

    /**
     * 把矢量放大图渲染成位图（异步）。
     *
     * 出图宽度取大图的显示宽度 ×2，够右栏卡片用；渲染在 GL 线程完成，这里切回主线程回灌。
     * 失败（bitmap == null）就什么都不做 —— 宁可少一张图，也不要顶一张空白卡。
     */
    private fun renderVectorCross(cross: com.motohud.ble.navi.CrossImage) {
        val bytes = cross.vector ?: return
        val renderer = vectorCrossRenderer ?: run {
            // 用**影子 AMapNaviView 内部的地图**：放大图的矢量渲染资源（`assets/cross`、
            // 3D 路口贴图）是随 AMapNaviView 的地图引擎一起初始化的，挂到我们自己的
            // 主地图上渲染会直接拿不到资源（实测出图回调 code=10103、bitmap=null）。
            val map = shadowNaviView?.map ?: aMap ?: return
            com.motohud.ble.navi.VectorCrossRenderer(this, map).also { vectorCrossRenderer = it }
        }
        // 出图宽度按卡片实际宽度（不再 ×2）：rect 是给 GL 取 viewport 用的，
        // 超出地图尺寸会被判失败（之前给 1200x744 而地图只有 1612x720，一直 code=10103）。
        val targetW = minOf(ivCrossImage.width.takeIf { it > 0 } ?: 600, 600)
        renderer.render(bytes, cross.seq, targetW) { bitmap, seq ->
            if (bitmap == null) return@render
            runOnUiThread {
                (engine as? com.motohud.ble.navi.AmapNaviEngine)?.acceptVectorCross(bitmap, seq)
            }
        }
    }

    private fun setupMap(savedInstanceState: Bundle?) {
        if (!AmapManager.initMap(this)) return
        mapView = findViewById<MapView?>(R.id.guidance_map).also { mv ->
            mv ?: return
            runCatching {
                mv.onCreate(savedInstanceState)
                aMap = mv.map?.apply {
                    // 深色/浅色、3D 建筑、地图文字：全部走用户设置（默认与旧行为一致）
                    com.motohud.ble.amap.AmapMapConfig.applyDisplay(this)
                    uiSettings.isZoomControlsEnabled = false
                    uiSettings.isMyLocationButtonEnabled = false
                    uiSettings.isRotateGesturesEnabled = false
                    uiSettings.isTiltGesturesEnabled = false
                    // 关掉 SDK 自带定位：位置统一由导航引擎提供，避免两套定位打架
                    runCatching { isMyLocationEnabled = false }
                    // 路况瓦片跟着设置走：开着爽但要付费（流量），默认关
                    runCatching { setTrafficEnabled(NaviPrefs.mapTraffic.value) }
                    // 用户拖动地图 → 暂停自动跟随（专业导航软件的通行做法）
                    setOnMapTouchListener { userPanned = true }
                    // V11：把 3D 建筑压到覆盖物下方。横屏低俯角时建筑体块会盖住
                    // 路线和转向标，是骑行中"看不清该走哪"的主要来源之一。
                    com.motohud.ble.amap.AmapMapConfig.navigationBuildingLayer(this)
                    // 导航场景渲染：弱化无关 POI、道路画方向箭头（见 AmapMapConfig）
                    com.motohud.ble.amap.AmapMapConfig.applyNaviMode(this)
                    // V11：瓦片加载回调。进隧道/信号差时地图会停住不动，
                    // 这里能确认是网络断了还是引擎卡了，并提示一次（不反复弹）。
                    com.motohud.ble.amap.AmapMapConfig.watchTiles(this) {
                        if (!tileFailHintShown && !isFinishing) {
                            tileFailHintShown = true
                            Toast.makeText(
                                this@GuidanceActivity,
                                getString(R.string.navi_map_tile_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    // 首次布局偶尔只出灰底不出瓦片：底图加载完成后重设一次类型强制刷新。
                    // 带上 this 是因为自动配色要按地图中心算日出日落（见 AmapMapConfig）。
                    setOnMapLoadedListener {
                        mapView?.postDelayed({
                            runCatching {
                                mapType = com.motohud.ble.amap.AmapMapConfig.mapType(this)
                            }
                        }, 350L)
                    }
                }
            }
        }
    }

    /** 画出规划路线；fitAll=true 时缩放到全线 */
    private fun drawRoute(r: RouteData, fitAll: Boolean = true) {
        val map = aMap ?: return
        val points = ArrayList<LatLng>(1024)
        val cum = ArrayList<Double>(1024)
        var acc = 0.0
        var prev: LatLng? = null
        r.steps.forEach { step ->
            step.polyline.forEach { (lat, lng) ->
                val p = LatLng(lat, lng)
                if (prev != null && prev!!.latitude == lat && prev!!.longitude == lng) return@forEach
                prev?.let {
                    acc += com.motohud.ble.navi.LocationFilter.distanceM(
                        it.latitude, it.longitude, lat, lng
                    ).toDouble()
                }
                points.add(p)
                cum.add(acc)
                prev = p
            }
        }
        if (points.size < 2) return
        routePoints = points
        routeCum = cum.toDoubleArray()
        lastProgressM = -1
        safeMap {
            aheadLine?.remove()
            doneLine?.remove()
            // 底层：全程路线（主色，粗）；上层：已走路段（灰，细）——一眼看出走到哪
            aheadLine = map.addPolyline(
                PolylineOptions().apply {
                    addAll(points)
                    width(18f)
                    color(themeColor(R.attr.mtTrackAhead, android.graphics.Color.WHITE))
                    setDottedLine(false)
                    // V11 新增：折线抽稀。一条长途路线动辄几千个点，全量提交给渲染引擎
                    // 在中低端机上会拖慢每帧绘制；抽稀后顶点数大幅下降。
                    // 误差由引擎按像素级别控制，肉眼看不出与道路的贴合差异；
                    // 真要按原始点绘制可以在设置里关掉。
                    if (com.motohud.ble.data.NaviPrefs.routeReduce.value) setReducePoint(true)
                }
            )
            doneLine = map.addPolyline(
                PolylineOptions()
                    .add(points.first(), points.first())
                    .width(11f)
                    .color(themeColor(R.attr.mtTrackDone, android.graphics.Color.WHITE))
                    .setDottedLine(false)
                    .zIndex(2f)
            )
            destMarker?.remove()
            destMarker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(destLat, destLng))
                    .title(destName)
                    .draggable(false)
            )
            if (!fitAll) return@safeMap
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(it) }
            builder.include(LatLng(destLat, destLng))
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(builder.build(), 160), 700, null
            )
        }
    }

    /** 按已行驶距离把折线切成「已走（灰）」与「未走（主色）」两段 */
    private fun renderProgress(traveled: Int) {
        if (routePoints.size < 2 || routeCum.isEmpty()) return
        // 位移不足 20m 不重画：1Hz 每帧重设上千个点会拖慢地图渲染
        if (lastProgressM >= 0 && kotlin.math.abs(traveled - lastProgressM) < 20) return
        lastProgressM = traveled
        val idx = splitIndexFor(traveled.toDouble())
        val done = routePoints.subList(0, (idx + 1).coerceIn(1, routePoints.size))
        val ahead = routePoints.subList(idx.coerceIn(0, routePoints.size - 1), routePoints.size)
        safeMap {
            doneLine?.points = ArrayList(done)
            aheadLine?.points = ArrayList(ahead)
        }
    }

    /** 累计距离 → 折线下标 */
    private fun splitIndexFor(dist: Double): Int {
        if (routeCum.isEmpty()) return 0
        var lo = 0
        var hi = routeCum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (routeCum[mid] < dist) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 当前位置点（与导航数据同源，替代 SDK 自带蓝点） */
    private fun updateMeMarker(s: NaviEngine.NaviState) {
        val map = aMap ?: return
        if (s.latitude == 0.0 && s.longitude == 0.0) return
        try {
            val pos = LatLng(s.latitude, s.longitude)
            val m = meMarker
            if (m == null) {
                meMarker = map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .anchor(0.5f, 0.5f)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        .setFlat(true)
                        .zIndex(9f)
                )
            } else {
                m.position = pos
            }
            // 信号差时点变淡，提示用户（不用额外文案打扰）
            meMarker?.alpha = when {
                !s.gpsActive -> 0.45f
                accuracyLevel(s.accuracy) >= 2 -> 1f
                else -> 0.8f
            }
        } catch (_: Exception) {
        }
    }

    /** 地图跟随当前位置（节流 1.5s；用户拖动后停止跟随） */
    private fun followOnMap(s: NaviEngine.NaviState, force: Boolean = false) {
        val map = aMap ?: return
        if (s.latitude == 0.0 && s.longitude == 0.0) return
        if (userPanned && !force) return
        // 兜底：单帧位移超过 5km 时不跟随，避免定位错点时地图"飞走"变白屏
        if (!force && lastFollowLat != 0.0 &&
            com.motohud.ble.navi.LocationFilter.distanceM(
                lastFollowLat, lastFollowLng, s.latitude, s.longitude
            ) > 5000f
        ) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCameraMs < 1500L) return
        lastCameraMs = now
        lastFollowLat = s.latitude
        lastFollowLng = s.longitude
        // 路口前（<200m 且要转弯）自动放大，过路口回退 —— 免去手动缩放。
        // **横屏基准更高**：横屏就是为"看清前方路口"而存在的，跟竖屏给同样的
        // 缩放等于浪费掉多出来的横向空间，看起来和竖屏没区别。
        val zoom = when {
            landscapeNow && zoomBoost -> 19.8f
            landscapeNow -> 18.8f
            zoomBoost -> 18.2f
            else -> 17.2f
        }
        // 横屏俯角更大：3D 透视把前方道路拉长，同样高度能看到更远的路口
        val tilt = if (landscapeNow) 58f else 45f
        try {
            // 横屏把镜头中心**前移**一段：否则车标永远在屏幕正中，
            // 前方路口刚冒头就被右侧信息条挡住，等于白放大。
            val target = if (landscapeNow && s.bearing.isFinite() && s.speedKmh > 8f) {
                ahead(s.latitude, s.longitude, s.bearing, CAMERA_AHEAD_M)
            } else {
                LatLng(s.latitude, s.longitude)
            }
            if (s.bearing.isFinite() && s.speedKmh > 8f) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        com.amap.api.maps.model.CameraPosition.builder()
                            .target(target)
                            .zoom(zoom)
                            .bearing(s.bearing)
                            .tilt(tilt)
                            .build()
                    ), 800, null
                )
            } else {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(target, zoom), 800, null
                )
            }
        } catch (_: Exception) {
        }
    }

    /** 把镜头中心沿行进方向前移 [meters] 米，让车标落在屏幕下方、前方路况看得更远 */
    private fun ahead(lat: Double, lng: Double, bearingDeg: Float, meters: Double): LatLng {
        val rad = Math.toRadians(bearingDeg.toDouble())
        val dLat = meters * Math.cos(rad) / Geo.M_PER_DEG_LAT
        val dLng = meters * Math.sin(rad) / Geo.mPerDegLng(lat)
        return LatLng(lat + dLat, lng + dLng)
    }

    // ---------- 状态渲染 ----------

    private fun observeState() {
        lifecycleScope.launch {
            engine.state.collectLatest { s -> render(s) }
        }
        /*
         * 车机投屏转发：每 tick 一帧（复刻原 App onNaviInfoUpdate 每次都写）。
         *
         * 这一段在归档版里写在 HudActivity（横屏投屏 HUD 页）的 render 里。
         * 做成核心双功能版删掉 HUD 页时，转发调用没有跟着搬到本页 ——
         * 于是车机只收到握手 HELLO 和退出时的 NO，中间**一帧导航数据都没有**，
         * 车机屏幕上自然什么都不显示。
         *
         * 单独起一条收集器而不是塞进 render()：
         *  - render() 里有 UI 早退（!started / 未在导航中），转发不该被界面状态左右；
         *  - 息屏导航 / 画中画时界面已不可见，但车机必须继续收到数据。
         */
        lifecycleScope.launch {
            engine.state.collect { s ->
                if (started && !stopped) app.naviForwarder.forward(s)
            }
        }
        lifecycleScope.launch {
            ble.state.collectLatest { st ->
                val connected = st == BleConnectionManager.State.CONNECTED
                tvBleHint.visibility =
                    if (connected && NaviPrefs.castEnabled.value) View.GONE else View.VISIBLE
                tvBleHint.text = if (!connected) {
                    getString(R.string.guidance_ble_hint)
                } else {
                    getString(R.string.guidance_cast_off_hint)
                }
                // 横屏极度精简：侧栏不留连接提示，避免顶高一整行
                if (landscapeNow) tvBleHint.visibility = View.GONE
            }
        }
        lifecycleScope.launch {
            NaviPrefs.castEnabled.collect {
                tvBleHint.visibility =
                    if (ble.state.value == BleConnectionManager.State.CONNECTED && it) View.GONE
                    else View.VISIBLE
                tvBleHint.text = if (it) getString(R.string.guidance_ble_hint)
                else getString(R.string.guidance_cast_off_hint)
                // 横屏极度精简：侧栏不留连接提示
                if (landscapeNow) tvBleHint.visibility = View.GONE
            }
        }
    }

    private fun render(s: NaviEngine.NaviState) {
        // 路线规划尚未落地时不消费引擎里的历史状态（可能属于上一次导航）
        if (!started) return
        if (!s.running && !s.arrived) return

        updateMeMarker(s)

        // 转向横幅
        tvTurnInstruction.text = s.stepInstruction.ifBlank {
            if (s.arrived) getString(R.string.hud_arrived) else getString(R.string.hud_step_remain_label)
        }
        // 路名也取「即将转弯」那一段（目标路），与图标/引导语/距离同一口径；
        // 取当前路段会出现「右转进入Y路」却配着"X路"的错位。
        // SDK 模式下直接用 NaviInfo.nextRoadName（官方数据，比从引导语里抠准）。
        val roadText = (s.nextRoadName.takeIf { it.isNotBlank() }
            ?: s.currentRoadName.takeIf { it.isNotBlank() }
            ?: route?.steps?.getOrNull(s.maneuverIndex)?.road.orEmpty())
            // 「无名道路」「当前道路」是 SDK 的占位值，印在卡上等于没说；
            // 空出来时下面那行会显示「剩余里程」，信息量反而更大
            .takeUnless { SpeechScript.isPlaceholderRoad(it) }
            .orEmpty()
        tvTurnRoad.text = roadText.ifBlank {
            if (s.onRoute) getString(R.string.hud_step_remain_label) else getString(R.string.guidance_demo_short)
        }
        ivTurnIcon.setImageResource(TurnIcons.res(s.iconType))
        tvTurnDistance.text = CoordConverter.formatDistance(s.stepRemain)
        renderNextStep(s)

        // 底部信息
        tvRemain.text = CoordConverter.formatDistance(s.pathRemain)
        tvSpeed.text = String.format(Locale.US, "%.0f", s.speedKmh)
        tvEta.text = if (s.arrived) clock.format(Date()) else
            clock.format(Date(System.currentTimeMillis() + s.etaSeconds * 1000L))
        renderExtraStats(s)

        // 定位质量
        tvGpsMode.text = when {
            !s.gpsActive -> getString(R.string.guidance_simulated)
            s.locType != 0 -> locationTypeLabel(s.locType)
            else -> "GPS"
        }
        tvGpsQuality.text = if (s.gpsActive && s.accuracy > 0f) formatAccuracy(s.accuracy)
        else getString(R.string.guidance_gps)

        // 信号弱：优先信高德给的信号质量（综合卫星数/信噪比/速度一致性），
        // 只有拿不到（系统定位兜底）时才退回精度阈值。
        // 高德判 "差" 时哪怕 accuracy 显示 ±5m 也算弱 —— 那是基站定位伪装出来的精度。
        val weak = s.running && when {
            !s.gpsActive -> true
            s.gpsAccuracyStatus == AMapLocation.GPS_ACCURACY_BAD -> true
            s.gpsAccuracyStatus == AMapLocation.GPS_ACCURACY_GOOD -> false
            else -> accuracyLevel(s.accuracy) == 1
        }
        tvSignalWarn.visibility = if (weak && !inPip) View.VISIBLE else View.GONE
        if (weak && !weakGpsToastShown) {
            weakGpsToastShown = true
            Toast.makeText(
                this,
                if (s.accuracy > 0f) getString(R.string.navi_gps_weak_message, s.accuracy.roundToInt())
                else getString(R.string.navi_gps_weak_message, 0),
                Toast.LENGTH_LONG
            ).show()
        }

        // 路口前自动放大状态切换：置零节流，让下一帧立刻动相机
        val wantBoost = !s.arrived && s.stepRemain in 1..AUTO_ZOOM_RANGE_M && TurnIcons.isTurn(s.iconType)
        if (wantBoost != zoomBoost) {
            zoomBoost = wantBoost
            lastCameraMs = 0L
        }

        // 轨迹采样（10m / 5s 门槛，见 NaviTrack）
        if (s.running && !s.arrived) {
            historyId?.let { id ->
                NaviTrack.feed(id, s.latitude, s.longitude, s.speedKmh, System.currentTimeMillis())
            }
        }

        renderProgress(s.traveled)

        // 进入新路段 → 重置本段的阈值调度
        if (s.stepIndex != lastAnnouncedStep) {
            lastAnnouncedStep = s.stepIndex
            remindStepIndex = s.stepIndex
            remindMask = 0
            activeThresholds = thresholdsFor(s)
            // 进段确认在两种引擎下都要说：刚拐完弯是"我在不在对的路"最需要确认的时刻。
            // SDK 模式没有 DriveStep，用 currentRoadName/stepRemain 合成等价的一句。
            val spokeAt = announceStepEntered(s)
            // 进入时就已念过的距离，把同一档及更远的档位标记为已播，避免复读/倒着播
            if (spokeAt > 0) markAnnouncedAt(spokeAt)
        }
        // 播报统一走本页的叙事层。SDK 的转向文本已经在引擎侧被过滤掉
        // （只放行事故/施工这类非转向信息），所以这里不再按 sdkDriven 分叉。
        announceApproaching(s)
        // 预合成只对兜底引擎有意义：它按 route.steps[maneuverIndex] 取句子，
        // 而 SDK 模式下 maneuverIndex 是引导点下标，两套索引不是一回事，算出来的句子用不上。
        if (!s.sdkDriven) prewarmStep(s.maneuverIndex)
        // 电子眼 / 区间测速：SDK 的官方播报文本里不含这块，必须自己念
        announceCamera(s)
        // 服务区 / 沿途交通事件：同样不在官方播报里，各念一次
        announceSdkNotes(s)
        // 沿途道路要素（隧道/桥梁/立交/匝道/天桥）：进入前单独播一次
        announceRoadFeatures(s)
        // 弯道预警（急转弯/连续弯道）：从折线曲率自算，提前 300 米
        announceCurve(s)
        // 坡度提示（长下坡/长上坡）：气压计测量，只能报"当前"
        announceTerrain(s)
        // 喂两个感知器（坡度需要里程增量，行为需要速度/航向）
        feedDriveSenses(s)
        // SDK 新增能力的界面（车道线 / 电子眼 / 路口放大图 / 重新规划提示）
        renderSdkAlerts(s)

        if (s.arrived) {
            if (!stopped) {
                stopped = true
                app.naviForwarder.forwardFinish()
                say(buildArrivedText(s), SpeechPriority.INFO, cue = com.motohud.ble.navi.SpeechArbiter.ARRIVED)
                // 导航历史：到达即结束这条记录（轨迹在这里压缩写回）
                finishHistory()
            }
            hideBetterRouteCard()
            setCrossMode(false)
            showArrivalCard(s)
            turnBanner.visibility = View.GONE
        } else {
            arrivedOverlay.visibility = View.GONE
            turnBanner.visibility = View.VISIBLE
            followOnMap(s)
        }
    }

    /** 下一步预告：`steps[stepIndex + 1]` 的数据一直都在，以前只是没显示 */
    private fun renderNextStep(s: NaviEngine.NaviState) {
        val text = if (s.sdkDriven) {
            // SDK 模式下不能用 route.steps[maneuverIndex + 1]：那是另一套索引空间，
            // 指到哪一步全看运气。改用引擎从引导点列表取出的 guides[curStep + 1]。
            val act = SpeechScript.action(s.nextManeuverIcon)
            if (act.isBlank()) "" else {
                val road = s.nextManeuverRoad
                if (SpeechScript.isUsableRoad(road)) "$act，进入$road" else act
            }
        } else {
            val steps = route?.steps.orEmpty()
            // banner 现在显示的是"即将转弯"（maneuverIndex），"下一个"要显示它之后那一步，
            // 否则 banner 与"下一个"都指着同一个动作。
            val next = if (s.maneuverIndex >= 0) steps.getOrNull(s.maneuverIndex + 1) else null
            next?.instruction.orEmpty().ifBlank { next?.action.orEmpty() }
        }
        if (s.arrived || text.isBlank()) {
            tvTurnNext.visibility = View.GONE
        } else {
            tvTurnNext.visibility = View.VISIBLE
            tvTurnNext.text = getString(R.string.guidance_next_step, text)
        }
    }

    /** 展开区：红绿灯 / 过路费 / 步骤进度 / 路况 */
    private fun renderExtraStats(s: NaviEngine.NaviState) {
        val r = route ?: return
        if (!cardExpanded) return
        // 导航中优先用 SDK 的「剩余红绿灯」：路线算路时给的是全程总数，
        // 跑了一半还挂着 4 个容易误判，`routeRemainLightCount` 才是当下的数
        tvLights.text = if (s.sdkDriven && s.remainLights >= 0) {
            s.remainLights.toString()
        } else {
            r.trafficLights.toString()
        }
        tvTolls.text = if (r.tolls > 0f) getString(R.string.route_fare, r.tolls) else getString(R.string.common_free)
        val total = r.steps.size
        tvStepProgress.text = if (total > 0) "${(s.stepIndex + 1).coerceIn(1, total)}/$total" else "--"
        tvTrafficEstimate.text = buildTrafficEstimate(r, s)
    }

    /**
     * 路况。
     *
     * 导航 SDK 下 `congestionAheadM` 是**实测的实时拥堵里程**（`onTrafficStatusUpdate`
     * 汇总路线上 status≥3 的 link 长度），有就直接说，不再拿平均车速去猜；
     * 只有走兜底引擎时才退回「距离÷时长」的三档估算，并明确标注"估算"。
     */
    private fun buildTrafficEstimate(r: RouteData, s: NaviEngine.NaviState): String {
        if (s.sdkDriven) {
            return if (s.congestionAheadM >= 100f) {
                getString(R.string.navi_congestion_ahead, CoordConverter.formatDistance(s.congestionAheadM.toInt()))
            } else {
                getString(R.string.guidance_traffic_smooth)
            }
        }
        if (r.durationS <= 1f || r.distanceM <= 1f) return ""
        val kmh = r.distanceM / 1000f / (r.durationS / 3600f)
        val level = when {
            kmh >= 35f -> getString(R.string.guidance_traffic_smooth)
            kmh >= 22f -> getString(R.string.guidance_traffic_slow)
            else -> getString(R.string.guidance_traffic_jam)
        }
        return getString(R.string.guidance_traffic_estimate, level, kmh.roundToInt())
    }

    // ---------- 到达 ----------

    private fun showArrivalCard(s: NaviEngine.NaviState) {
        arrivedOverlay.visibility = View.VISIBLE
        tvArriveSub.text = getString(R.string.guidance_arrive_sub, destName)
        val traveledM = s.traveled.coerceAtLeast(0)
        tvArriveDistance.text = CoordConverter.formatDistance(traveledM)
        val durationMs = (System.currentTimeMillis() - naviStartMs).coerceAtLeast(0L)
        tvArriveDuration.text = formatDuration(durationMs)
        tvArriveAvg.text = if (durationMs > 0) {
            val kmh = traveledM / 1000.0 / (durationMs / 3_600_000.0)
            String.format(Locale.US, "%.0f km/h", kmh)
        } else "--"
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) "${h}h${m}m" else "${m}min"
    }

    /**
     * 当前路段生效的播报阈值集合（降序）。
     *
     * 转弯用 1 公里/500/200/临门 四档，最后一档只报动作；
     * 直行只在真有路口（需换路）时用 200 一档，避免长直路反复念「直行」。
     * 详细度选择器仍然有效：简洁砍到 500/临门，只播转弯砍到 200。
     *
     * **最后一档的距离按进段车速算**（[com.motohud.ble.navi.VoiceGuidance.closeDistOf]）：
     * 高速上要早报，市区慢行晚报。进段时定一次就够 —— 这一段的车速基调
     * 在进入时已经确定，中途抖一下没必要重排播报阶梯。
     */
    private fun thresholdsFor(s: NaviEngine.NaviState): IntArray {
        val isTurn = TurnIcons.isTurn(s.iconType)
        val mode = NaviPrefs.voiceMode.value
        return when {
            !isTurn -> when (mode) {
                NaviPrefs.VOICE_MODE_TURN_ONLY -> IntArray(0)
                // 简洁模式直行只提醒一次，别把长直路念成复读机
                NaviPrefs.VOICE_MODE_BRIEF -> intArrayOf(200)
                else -> withCloseDist(STRAIGHT_THRESHOLDS, s)
            }
            mode == NaviPrefs.VOICE_MODE_BRIEF -> withCloseDist(BRIEF_THRESHOLDS, s)
            // 只播转弯模式只有一档「提醒一次」，没有临门档，不动
            mode == NaviPrefs.VOICE_MODE_TURN_ONLY -> TURN_ONLY_THRESHOLDS
            else -> withCloseDist(TURN_THRESHOLDS, s)
        }
    }

    /**
     * 把阶梯的**最后一档**换成按车速算出的临门距离，其余档位保持不变。
     *
     * 换值而不是加一档，是为了不改变 [remindMask] 的位布局 —— 位索引和档位一一对应，
     * 长度变了会让"已播"状态整体错位，导致重复播报。
     */
    private fun withCloseDist(base: IntArray, s: NaviEngine.NaviState): IntArray {
        if (base.isEmpty()) return base
        val d = com.motohud.ble.navi.VoiceGuidance.closeDistOf(s.speedKmh)
        return IntArray(base.size) { i -> if (i == base.lastIndex) d else base[i] }
    }

    /**
     * 进入新路段时的播报，返回**实际播报时的剩余距离**（没播返回 -1）。
     *
     * 说的是「过渡后确认」（Valhalla 的 `verbal_post_transition_instruction`）：
     *  - 长段：继续沿 X 路行驶 2 公里 —— 告诉你现在不用再动方向；
     *  - 短段：继续沿 X 路行驶 300 米，然后右转进入 Y 路 —— 一次交代两件事。
     *
     * 以前这里念的是「前方 2 公里右转进入 Y 路」，即提前把下一个路口说了，
     * 但**没说你现在这条对不对**；拐完弯最需要确认的恰恰是这一句。
     *
     * 长直路段（≥800m）也播一句，否则一路上只听见"直行"没有路名，同样会走错。
     */
    private fun announceStepEntered(s: NaviEngine.NaviState): Int {
        if (!s.running || s.arrived || s.stepIndex < 0) return -1
        val r = s.stepRemain.toInt()
        if (r <= 0) return -1
        val isTurn = IconMapper.isTurn(s.iconType)
        val mode = NaviPrefs.voiceMode.value
        // 只播转弯：只在路口段开口；其余模式长直段也报一次路名，短碎段不抢话
        val should = when {
            mode == NaviPrefs.VOICE_MODE_TURN_ONLY -> isTurn
            isTurn -> true
            r >= 800 -> true
            else -> false
        }
        if (!should) return -1
        val text = if (s.sdkDriven) {
            // 导航 SDK 没有 DriveStep，但 `currentRoadName` + `stepRemain` 就是同一份信息：
            // 合成一个临时 RouteStep 交给叙事层，句式与兜底引擎逐字一致（内置语音包才命中）
            val road = if (presetPack) "" else s.currentRoadName
            val step = RouteStep(
                instruction = if (s.currentRoadName.isNotBlank()) "继续沿${s.currentRoadName}行驶" else "直行",
                action = if (isTurn) SpeechScript.action(s.iconType) else "直行",
                orientation = "",
                road = road,
                distanceM = r.toFloat(),
                polyline = emptyList()
            )
            com.motohud.ble.navi.VoiceGuidance.entry(step, s.iconType, r.toFloat())
        } else {
            val step = route?.steps?.getOrNull(s.stepIndex) ?: return -1
            // 内置语音包念不出路名，别拼「继续沿烟雨路行驶」——把路名去掉，
            // 直接说「继续直行 500 米」，这段在语音包里有整句，一次命中
            val stepForVoice = if (presetPack) step.copy(road = "") else step
            com.motohud.ble.navi.VoiceGuidance.entry(stepForVoice, s.iconType, r.toFloat())
        }
        if (text.isBlank()) return -1
        say(
            text,
            interrupt = false,
            cue = com.motohud.ble.navi.SpeechArbiter.STEP_ENTRY
        )
        return r
    }

    /**
     * 进入路段时已念过的距离 → 把「同档位及更远」的阈值标记为已播。
     *
     * 两个条件任一命中就标记：
     *  - 阈值 >= 刚念的距离（已经在更近处了，不该再报「1 公里」这种更远的档）；
     *  - 阈值念出来的**字面**和刚念的一致（如刚念「前方 500 米」，500 档就别再念一遍）。
     */
    private fun markAnnouncedAt(spokeAt: Int) {
        // 只抑制"比刚念的距离更远"的档位（那些档已经在你身后，再念就是倒着播）。
        //
        // 曾经还加了一条"距离词相同就抑制"，本意是避免进段确认后立刻重复念同一句；
        // 但进段确认说的是「继续沿 X 路行驶 1.2 公里」，预告说的是「前方 1 公里右转」——
        // 两者距离词同为"1 公里"、内容却完全不同（后者才给出转弯信息）。按距离词抑制
        // 会把 1 公里档整档吃掉，配合之前直行引道被当直行的 bug，正好造成"到路口才报"。
        for (i in activeThresholds.indices) {
            if (activeThresholds[i] >= spokeAt) remindMask = remindMask or (1 shl i)
        }
    }

    /**
     * 按距离阶梯提前播报。
     *
     * 每 tick 找到「已跨过但还没播」的**最紧急**一档（阈值最小、且剩余距离 <= 该阈值），
     * 播一次并把它及更远的档位标记为已播；这样一次 GPS 跳变也只会说最相关的一句，
     * 不会把 1 公里/500/200 连着念出来。若跳变时已经进了临门距离内
     * （按车速 30~80 米，见 `VoiceGuidance.closeDistOf`），
     * 说的就是纯动作句（「左转」/「进入环岛，第 2 个出口」）。
     */
    /**
     * 沿途道路要素提示：隧道、桥梁、立交、匝道、人行天桥。
     *
     * 与转向播报是**两条独立通道**：转向说的是"这个路口怎么走"，
     * 要素说的是"接下来这段路本身有什么要注意的"。所以它不看阈值阶梯，
     * 只看"离下一条含要素的路段还有多远"。
     *
     * 每段最多播一条，取触发距离最远的那个要素（桥 500 > 隧道/立交 400 > 匝道 300）。
     */
    private fun announceRoadFeatures(s: NaviEngine.NaviState) {
        if (!s.running || s.arrived || s.stepIndex < 0) return
        if (!NaviPrefs.voice.value) return
        val steps = route?.steps ?: return
        val nextIdx = s.stepIndex + 1
        val next = steps.getOrNull(nextIdx) ?: return
        if (nextIdx in featureAnnounced) return
        val pick = com.motohud.ble.navi.RoadFeatureDetector.detect(next)
            .filter { com.motohud.ble.navi.RoadFeatureNarrator.worthAnnouncing(it.type) }
            .maxByOrNull { com.motohud.ble.navi.RoadFeatureNarrator.triggerDist(it.type) }
            ?: return
        // s.stepRemain 是"距下一段起点还有多远"，正好是离这个要素的距离
        val remain = s.stepRemain
        if (remain <= 0 || remain > com.motohud.ble.navi.RoadFeatureNarrator.triggerDist(pick.type)) return
        featureAnnounced.add(nextIdx)
        val text = com.motohud.ble.navi.RoadFeatureNarrator.text(pick, remain, presetPack)
        if (text.isNotEmpty()) {
            say(
                text,
                SpeechPriority.INFO,
                interrupt = false,
                cue = com.motohud.ble.navi.SpeechArbiter.ROAD_FEATURE
            )
        }
    }

    /**
     * 弯道预警：急转弯 / 连续弯道。
     *
     * ## 为什么这个能做而坡度不能
     *
     * 高德 `DriveStep` 没有任何道路形态字段，但 `polyline` 是真实几何 ——
     * 相邻三点的转向角就是曲率，纯本地可算、零请求、零流量（见 [RoadShape]）。
     *
     * ## 位置换算
     *
     * `RoadShape.scan()` 给出的是"弯道在本段折线的 X 米处"，而引擎的 `s.stepRemain`
     * 正好也是沿这段折线累计出来的（`stepEndDist` 由同一串点累加）。
     * 所以 `已走 = 折线总长 - stepRemain` 是**同一口径**的减法，不需要另建进度模型。
     */
    private fun announceCurve(s: NaviEngine.NaviState) {
        if (!s.running || s.arrived || s.stepIndex < 0) return
        if (!NaviPrefs.voice.value || !NaviPrefs.cueCurve.value) return
        val steps = route?.steps ?: return

        // 只看当前段是不够的：城市路口的引导段常常只有一两百米，
        // 出弯就在下一段里，等真的进段了只剩几十米，预警等于没有。
        // 所以把「当前段」与「下一段」拼起来看，下一段要加上当前段剩余里程做偏移。
        val scan = ArrayList<Pair<Int, Float>>(2)
        scan.add(s.stepIndex to 0f)
        if (s.stepIndex + 1 < steps.size) scan.add(s.stepIndex + 1 to s.stepRemain.toFloat())

        for ((stepIdx, offsetM) in scan) {
            val step = steps.getOrNull(stepIdx) ?: continue
            if (step.polyline.size < 3) continue
            val hints = curveCache.getOrPut(stepIdx) {
                com.motohud.ble.navi.RoadShape.scan(step.polyline)
            }
            if (hints.isEmpty()) continue
            // 本段已走多远 = 折线总长 - stepRemain（同一串点累加，口径一致）
            val totalLen = com.motohud.ble.navi.RoadShape.lengthOf(step.polyline)
            val walked = if (stepIdx == s.stepIndex) {
                (totalLen - s.stepRemain).coerceAtLeast(0f)
            } else {
                0f
            }
            for ((i, h) in hints.withIndex()) {
                val key = "$stepIdx|$i"
                if (key in curveAnnounced) continue
                val ahead = offsetM + h.atM - walked
                if (ahead <= 0f) {
                    // 已经骑过去了（进段太晚 / GPS 跳变），标记掉免得下次又算一遍
                    curveAnnounced.add(key)
                    continue
                }
                if (ahead > com.motohud.ble.navi.CurveNarrator.TRIGGER_M) continue
                curveAnnounced.add(key)
                val text = if (presetPack) {
                    com.motohud.ble.navi.CurveNarrator.fixedText(h)
                } else {
                    com.motohud.ble.navi.CurveNarrator.text(h, ahead.toInt())
                }
                say(
                    text,
                    SpeechPriority.INFO,
                    interrupt = false,
                    cue = com.motohud.ble.navi.SpeechArbiter.CURVE
                )
                // 一帧只播一个弯：连续弯道本来就密，连着念两句会糊成一团
                return
            }
        }
    }

    /**
     * 坡度提示：长下坡 / 长上坡。
     *
     * 只能测"已经走过的路"，给不出"前方"（原因见 [com.motohud.ble.navi.TerrainSense]）。
     * 所以文案是「连续下坡，注意控制车速」而不是「前方下坡」—— 不撒谎。
     *
     * 播报节流 90 秒：一段长下坡可能延续好几公里，20 秒窗口每秒都满足条件，
     * 不节流会变成复读机。
     */
    private fun announceTerrain(s: NaviEngine.NaviState) {
        if (!s.running || s.arrived) return
        if (!NaviPrefs.voice.value || !NaviPrefs.cueTerrain.value) return
        val sense = terrainSense ?: return
        if (!sense.isAvailable()) return
        val v = sense.evaluate() ?: return
        val now = System.currentTimeMillis()
        if (now - terrainSpokenAt < TERRAIN_COOLDOWN_MS) return
        terrainSpokenAt = now
        val text = if (presetPack) {
            com.motohud.ble.navi.TerrainNarrator.fixedText(v.downhill)
        } else {
            com.motohud.ble.navi.TerrainNarrator.text(v)
        }
        say(
            text,
            SpeechPriority.INFO,
            interrupt = false,
            cue = com.motohud.ble.navi.SpeechArbiter.TERRAIN
        )
    }

    /**
     * 把导航状态喂给两个"感知器"：坡度（气压计）与驾驶行为（GPS 速度/航向）。
     *
     * 放在渲染循环里而不是引擎里，是因为这两件事**只影响播报**，
     * 不该让 NaviEngine 承担 UI 关注点（引擎还要给车机投屏用，必须保持轻）。
     */
    private fun feedDriveSenses(s: NaviEngine.NaviState) {
        // ---- 坡度：用路线上的**已行驶里程**增量，而不是 GPS 位移（后者会正负抵消不掉抖动）
        if (s.running && !s.arrived) {
            if (lastFeedTraveled >= 0 && s.traveled > lastFeedTraveled) {
                terrainSense?.addDistance((s.traveled - lastFeedTraveled).toFloat())
            }
            lastFeedTraveled = s.traveled
        }

        // ---- 驾驶行为 ----
        // 只采信真实卫星定位：推算模式下 speedKmh 是模拟值，算出来全是假的加减速。
        val now = System.currentTimeMillis()
        if (!s.running || s.arrived || !s.gpsActive) return
        if (now - lastBehaviorFeedMs < BEHAVIOR_FEED_MS) return
        lastBehaviorFeedMs = now
        val ev = behaviorMonitor.onFix(now, s.speedKmh / 3.6f, s.bearing, s.accuracy) ?: return
        if (!NaviPrefs.cueHarsh.value || !NaviPrefs.voice.value) return
        say(
            com.motohud.ble.navi.DriveBehaviorNarrator.fixedText(ev.kind),
            SpeechPriority.INFO,
            interrupt = false,
            cue = com.motohud.ble.navi.SpeechArbiter.HARSH_DRIVE
        )
    }

    private fun announceApproaching(s: NaviEngine.NaviState) {
        if (!s.running || s.arrived || s.stepIndex < 0) return
        if (activeThresholds.isEmpty()) return
        if (s.stepIndex != remindStepIndex) return
        // 临门档距离按**实时车速**刷新。
        // 进段时算的那一档只反映进入那一刻的车速：高速驶入、到路口前减速停车时，
        // 固定的 80 米会让「右转」那句永远不触发（人已经停在路口，车机还静着）。
        // 只在档位数 >= 2 时刷新 —— "只播转弯"模式只有一档 [300]，那是提醒档不是临门档。
        if (activeThresholds.size >= 2) activeThresholds = withCloseDist(activeThresholds, s)
        val rem = s.stepRemain.toInt()
        if (rem <= 0) return
        var chosen = -1
        for (i in activeThresholds.indices) {
            val t = activeThresholds[i]
            if (rem <= t && (remindMask and (1 shl i)) == 0) chosen = i
        }
        if (chosen < 0) return
        // 两道闸门，缺一不可：
        //  ① 掩码（本段这一档说过没）—— 路口附近步骤序号抖动会把掩码清零；
        //  ② 硬去重（整程这个路口这一档说过没）+ 最小间隔（防止序号来回跳时
        //     key 变了又念一遍）。
        // 用户听到的"六七百米处反复播报左转"，就是只有 ① 没有 ② 的结果。
        val now = System.currentTimeMillis()
        if (now - lastApproachMs < APPROACH_MIN_GAP_MS) return
        for (i in 0..chosen) remindMask = remindMask or (1 shl i)

        val closeDist = com.motohud.ble.navi.VoiceGuidance.closeDistOf(s.speedKmh)
        val isClose = rem <= closeDist

        // ---- 去重键 ----
        // 以前用 `stepIndex|iconType|档位`，两个字段都是会抖的：
        //  · `curStep` 在路口附近两个值之间跳；
        //  · `iconType` 在 SDK 还没填充时是 0（直行），下一帧才变左转。
        // 键一变，"已经说过"就失效 —— 真机日志里连续两次「左转」就是这么来的。
        // 换成**沿路线的绝对里程**（已走 + 到该路口还剩），这个数在接近路口的
        // 整个过程中是恒定的，与索引抖动、图标抖动都无关。按 50 米分桶消掉 GPS 噪声。
        val absM = (s.traveled + rem).takeIf { s.traveled > 0 && it > 0 }?.toInt()
        val place = if (absM != null) "A${absM / 50}" else "S${s.stepIndex}"
        val key = "$place|$chosen"
        if (!spokenTiers.add(key)) return
        if (spokenTiers.size > 200) spokenTiers.clear()

        // ---- 这个路口有没有说过一句完整的话？----
        // 用户抱怨"转向播报不清楚"，根因就在这里：起手就在临门距离内（或中途跳档）时，
        // 第一句直接是"左转"两个字，没有距离、没有序号、没有目标路 ——
        // 等于什么都没说。所以每个路口的**第一句必须是完整句**，
        // 之后才允许用"左转"这种短句收尾。
        val firstForThisTurn = spokenIntersections.add(place)
        if (spokenIntersections.size > 200) spokenIntersections.clear()

        val cue = if (isClose && !firstForThisTurn) {
            com.motohud.ble.navi.SpeechArbiter.CLOSE_ACTION
        } else {
            com.motohud.ble.navi.SpeechArbiter.APPROACH
        }
        // 完整句：距离 + 第几个路口 + 动作 + 目标路 + 车道
        val text = if (isClose && !firstForThisTurn) {
            closeText(s)
        } else {
            maneuverText(s, rem, withLane = rem <= LANE_HINT_M || isClose)
        }
        if (text.isBlank()) return
        lastApproachMs = now
        say(text, cue = cue)
    }

    /**
     * 某一档的完整播报句：**距离 + 第几个路口 + 动作 + 目标路名（+ 车道指引）**。
     *
     * 为什么必须自己拼、不念 SDK 的文本：
     *  - SDK 的转向文本只给「600米后 左转」，没有路口序号，也说不出"请走左侧车道"；
     *  - 它按自己的节奏反复下发，我们无法控制哪一档该说、哪一档不该说；
     *  - 一旦它给的图标与文本不同步，就会出现"直行路口被念成左转"。
     * 现在这一段只读 `NaviInfo` 的结构化字段（`iconType` / `curStepRetainDistance` /
     * `nextRoadName` / `maneuverOrdinal`），三者同源同一帧，不可能对不上。
     *
     * 句式对齐高德：官方模板就是 `{meter}米后 {action}`（见 `libAMapSDK_NAVI` 里的
     * `{meter}米后 {action}` / `左转，进入`），我们只是把「第几个路口」补回去 —
     * 那是高德原文里有、而被我们上一版丢掉的字段。
     */
    private fun maneuverText(s: NaviEngine.NaviState, remainM: Int, withLane: Boolean): String {
        val act = SpeechScript.action(s.iconType)
        if (act.isEmpty()) return ""
        val d = SpeechScript.distance(remainM)
        if (d.isEmpty()) return ""
        val sb = StringBuilder(d)
        /*
         * 路口序号：**只有从第 2 个路口起才说**，第 1 个路口一律不说。
         *
         * 高德的规则就是这么定的 —— 序号的作用是「在连成串的路口里点明是哪一个」。
         * 眼前只有这一个口子时说「第 1 个路口左转」纯属噪音，用户还得先解码"第一个"
         * 是什么意思；而且很容易和"第一条路/左侧车道"混淆。
         * 只有当同一段路上连着好几个路口、我们要在其中一个拐进去时，
         * 「第 2 个路口右转」才真的在消除歧义。
         *
         * 环岛也排除：环岛的序号在"第几个出口"，说"第 1 个路口进入环岛"是病句。
         */
        val ord = s.maneuverOrdinal
        if (ord in 2..9 && s.maneuverCount >= 2 && !SpeechScript.isRoundabout(s.iconType)) {
            sb.append("，第 ").append(ord).append(" 个路口")
        }
        if (s.iconType == IconType.STRAIGHT) {
            sb.append("直行")
            // 内置语音包念不出任意路名，别拼进去 —— 整句匹配不到会被拆成碎片
            val road = if (presetPack) "" else s.nextRoadName
            if (SpeechScript.isUsableRoad(road) && road != s.currentRoadName) {
                sb.append("进入").append(road)
            }
        } else {
            sb.append(act)
            val road = if (presetPack) "" else s.nextRoadName
            if (SpeechScript.isUsableRoad(road)) sb.append("进入").append(road)
        }
        if (withLane) laneHint(s)?.let { sb.append("，").append(it) }
        return sb.toString()
    }

    /** 临门一句：只说动作（环岛带上出口序号），不带距离/路名/车道 */
    private fun closeText(s: NaviEngine.NaviState): String {
        // 借叙事层同一套规则，保证「进入环岛，第 3 个出口」这类特例只有一份实现
        val icon = s.iconType
        if (icon == IconType.STRAIGHT) return getString(R.string.voice_straight_cross)
        return SpeechScript.actionOnly(icon, "")
    }

    /**
     * 车道级指引：「请走左侧车道」。
     *
     * 高德的做法是把车道建议**并进转向句**（`建议走左侧第二车道，排队较短`），
     * 而不是单独再说一句 —— 两句之间隔一次合成，路口早就过去了。
     * 所以这里只返回半句，由 [maneuverText] 拼接。
     *
     * 位置判定用**推荐车道在整排车道里的相对位置**：最左/最右/中间，
     * 比数"第几条"更符合骑手抬头看一眼的实际感知（横屏侧栏只有 300dp）。
     */
    private fun laneHint(s: NaviEngine.NaviState): String? {
        val lane = s.lane ?: return null
        if (lane.count < 2) return null
        // `usable` = 这条车道能不能通往下一段（SDK `frontLane` / `isRecommended`）。
        // 连续几条都可走时取它们的**中心**：只说"左侧车道"就够了，
        // 报"第 2 和第 3 条"既啰嗦，横屏侧栏也放不下。
        var first = -1
        var last = -1
        for (i in 0 until lane.count) {
            if (lane.isUsable(i)) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return null
        // 整排车道都能走 → 没有"该走哪条"的问题，别多嘴
        if (first == 0 && last == lane.count - 1) return null
        val center = (first + last) / 2
        return when {
            center * 2 < lane.count - 1 -> getString(R.string.lane_hint_left)
            center * 2 > lane.count - 1 -> getString(R.string.lane_hint_right)
            else -> getString(R.string.lane_hint_middle)
        }
    }

    /**
     * 电子眼 / 区间测速播报（导航 SDK 能力）。
     *
     * ## 为什么不再"每个点位都念"
     *
     * 真机实测（观音桥 1.3km）：95 秒里念了 15 条，14 条是「前方 100 米有违章拍照，请注意车速」，
     * 平均每 6.3 秒一句、正好卡在最小间隔上。用户听到的就是复读机。
     * 三个原因，逐个治：
     *  1. **通用违章**（`BREAK_RULE`/`SURVEILLANCE`）本身没有可执行的动作，
     *     只上屏不进语音（见 [com.motohud.ble.navi.CameraKind.group]）；
     *  2. **一串探头**（引擎已按 300 米并串）只说一次，不再逐点念；
     *  3. **距离档按类型给**（测速 500→200、闯红灯 200、占道 100），
     *     不再让每个点位都从最远档开始念一遍。
     */
    private fun announceCamera(s: NaviEngine.NaviState) {
        val cam = s.camera ?: return
        if (!s.running || s.arrived) return
        if (cam.distanceM <= 0 || cam.distanceM > CAMERA_SPEAK_M) return
        if (!com.motohud.ble.navi.CameraKind.worthSpeaking(cam.type)) return
        val cue = pickCameraTier(cam)
        if (cue.tier <= 0) return
        // 同一个点位、同一档只说一次：远档给反应时间，近档做确认
        if (!cameraSpoken.add(cam.speechKey(cue.tier, cue.exact))) return
        if (cameraSpoken.size > 96) cameraSpoken.clear()
        // 跨串间隔：两串探头挨得太近时，第二串只上屏不念。
        // 15 秒是"念一句(~3s) + 留 12 秒清净"，比原来的 6 秒节拍器低一半噪音。
        //
        // **细化类型不受这条限制**：它们由官方逐条播报、整条路线也就三五条，
        // 而且每条都是新情报（压线/逆行…）。实测第一版把「三百米内,有多个压线拍照」
        // 拦在了一条闯红灯之后 7 秒的空档里，等于这条路线唯一一次压线提示被吞掉了。
        val now = System.currentTimeMillis()
        val fine = com.motohud.ble.navi.CameraKind.isFine(cam.type)
        val group = com.motohud.ble.navi.CameraKind.group(cam.type)
        if (!fine) {
            if (now - lastCameraSpeakMs < CAMERA_MIN_GAP_MS) return
            // 同一组 60 秒内只说一次。实测最典型的噪音就是这个：
            // 一条商业街上连着四个路口，每组都是「闯红灯拍照」，逐组念四遍
            // 等于把同一句建议重复四次。换个组（比如突然来测速）才值得再开口。
            if (group == lastCameraGroup && now - lastCameraGroupMs < CAMERA_SAME_GROUP_MS) return
        }
        lastCameraSpeakMs = now
        lastCameraGroup = group
        lastCameraGroupMs = now
        say(cameraSpeech(cam, cue.tier, cue.exact), cue = com.motohud.ble.navi.SpeechArbiter.CAMERA)
    }

    /** 命中的播报档位 */
    private data class CameraCue(val tier: Int, val exact: Boolean)

    /**
     * 选播报档位。
     *
     * 规则：由远到近找第一个「正好跨过」的档 —— 距离 ≤ 档位值，且没有离得太远
     * （≥ 档位值的 60%）。这条下界是必须的：点位首次回调常常已经在 100 米内，
     * 没下界就会把 90 米说成「200 米」。
     *
     * 都不满足但已经很近了（`exact`）= 点位"冒出来"得太晚（高德下发窗口本身很近），
     * 这时按**实际距离**兜底念一句，别为了凑档位撒谎。
     */
    private fun pickCameraTier(cam: com.motohud.ble.navi.CameraAlert): CameraCue {
        val tiers = com.motohud.ble.navi.CameraKind.tiersOf(cam.type)
        if (tiers.isEmpty()) return CameraCue(0, false)
        val d = cam.distanceM
        for (t in tiers) {
            if (d <= t && d * 10 >= t * 6) return CameraCue(t, false)
        }
        val last = tiers.last()
        return if (d * 10 <= last * 6) CameraCue(last, true) else CameraCue(0, false)
    }

    /**
     * 电子眼的**播报**文本（跟界面文案分开）。
     *
     * 界面可以写"限速 60"给人看，但内置语音包是预录片段，念不出任意数字
     * —— 只能念「前方 200 米有测速摄像头，请注意车速」这种固定句式。
     * 装了离线模型（或语音包里有对应片段）时用带限速的完整句。
     *
     * @param tier 命中的档位值（用于距离词，保证说出口的距离是准的）
     * @param exact true = 点位出现太晚，按 `cam.distanceM` 的实际档位念
     */
    private fun cameraSpeech(
        cam: com.motohud.ble.navi.CameraAlert,
        tier: Int,
        exact: Boolean
    ): String {
        // 用 span(档位值) 而不是 span(当前距离)：档位值就是 200/500 这种整数，
        // 念出来与屏幕上的数字一致，也不会出现 187 米被念成「200 米」的错位感。
        val d = com.motohud.ble.navi.SpeechScript
            .span(if (exact) cam.distanceM else tier)
        val name = getString(com.motohud.ble.navi.CameraKind.labelRes(cam.type))

        if (cam.interval) {
            // 区间测速：剩余里程是个变化的值，只有念得全的链路才说
            val rich = getString(
                R.string.camera_ahead_interval, d, cam.speedKmh,
                com.motohud.ble.navi.SpeechScript.span(cam.intervalRemainM)
            )
            return if (canSpeakAll(rich)) rich else getString(R.string.camera_ahead_interval_preset, d)
        }
        if (cam.speedKmh > 0) {
            val rich = getString(R.string.camera_ahead_limit, d, name, cam.speedKmh)
            if (canSpeakAll(rich)) return rich
        }
        // 兜底固定句：类型名 + 「有」，句式与界面完全一致
        return getString(R.string.camera_ahead_slow, d, name)
    }

    /**
     * 这句话能不能**完整**念出来（内置包会跳过没录过的字，那就等于漏字）。
     * 装了离线模型 → 永远能念，直接放行。
     */
    private fun canSpeakAll(text: String): Boolean = presetSpeaker?.canSpeak(text) ?: true

    /**
     * 服务区 / 沿途交通事件播报（两者都由导航 SDK 独有地提供）。
     *
     * 各念一次就够：交通事件常在几秒内反复回调同一份数据，
     * 服务区更是几十公里只出现一次，重复念只会吵。
     */
    private fun announceSdkNotes(s: NaviEngine.NaviState) {
        if (!s.running || s.arrived) return

        val incident = s.incident
        if (incident != null) {
            val title = incident.title.trim()
            if (title.isNotEmpty() && incidentSpoken.add(title)) {
                // 内置包念不了自由文本（"XX路施工封闭"），换成固定提示；模型链路读原文
                val text = if (presetPack) getString(R.string.navi_incident_generic) else title
                say(text, cue = com.motohud.ble.navi.SpeechArbiter.TRAFFIC)
            }
        }

        val area = s.serviceArea
        if (area != null && area.distanceM in 1..SERVICE_AREA_SPEAK_M) {
            if (serviceAreaSpoken.add(area.name)) {
                // 服务区名是自由文本（"白鹤服务区"），内置包念不出，只念固定半句
                val text = if (presetPack) {
                    getString(R.string.navi_service_area_call_preset)
                } else {
                    getString(R.string.navi_service_area_call, area.name)
                }
                say(text, cue = com.motohud.ble.navi.SpeechArbiter.ROAD_FEATURE)
            }
        }
    }

    /** 电子眼的完整文案（播报与界面共用，保证听到的和看到的是同一句） */
    private fun cameraText(cam: com.motohud.ble.navi.CameraAlert): String {
        // 注意用 span() 而不是 distance()：后者自带「前方」前缀，
        // 再拼上模板里的「前方」会变成"前方前方 200 米"。
        val d = com.motohud.ble.navi.SpeechScript.span(cam.distanceM)
        val name = getString(com.motohud.ble.navi.CameraKind.labelRes(cam.type))
        return when {
            cam.interval ->
                getString(
                    R.string.camera_ahead_interval,
                    d,
                    cam.speedKmh,
                    com.motohud.ble.navi.SpeechScript.span(cam.intervalRemainM)
                )
            cam.speedKmh > 0 -> getString(R.string.camera_ahead_limit, d, name, cam.speedKmh)
            else -> getString(R.string.camera_ahead, d, name)
        }
    }

    /**
     * 限速徽章（红圈数字）。
     *
     * 只在数值变化时写控件 —— 这个函数每帧都会被调，TextView 反复 setText
     * 会触发重新测量，横屏小屏上能看出抖动。
     */
    private fun bindLimitBadge(limit: Int) {
        if (limit > 0) {
            val text = limit.toString()
            if (tvLimitBadge.text?.toString() != text) tvLimitBadge.text = text
            if (tvLimitBadge.visibility != View.VISIBLE) tvLimitBadge.visibility = View.VISIBLE
        } else if (tvLimitBadge.visibility != View.GONE) {
            tvLimitBadge.visibility = View.GONE
        }
    }

    /**
     * 电子眼文字色标（按分类分组，不是按类型）。
     *
     * 只在分组变化时写一次 —— 这个函数每帧都跑，`setTextColor` 会触发
     * `invalidate`，横屏小屏上能看出整行文字在闪。
     */
    private fun bindCameraTone(type: Int) {
        val tone = com.motohud.ble.navi.CameraKind.group(type)
        if (tone == lastCameraTone) return
        lastCameraTone = tone
        tvCameraWarn.setTextColor(themeColor(com.motohud.ble.navi.CameraKind.colorAttr(type)))
    }

    /**
     * 导航 SDK 新增能力的界面渲染：车道线 / 电子眼限速 / 路口放大图 / 重新规划提示。
     *
     * 这四样在接入导航 SDK 之前**一项都做不到**（搜索 SDK 不给这些数据），
     * 所以单独收在一个函数里，方便一眼看出"这些是 SDK 带来的"。
     */
    private fun renderSdkAlerts(s: NaviEngine.NaviState) {
        // ---- 车道线：几条车道、该走哪条（官方箭头位图，可走的亮、不可走的暗）----
        val lane = s.lane
        if (lane != null && lane.count > 0) {
            // 配色跟地图主题同源：白天 = 交通标志那样蓝底白箭头，夜间 = 深色底。
            // 每秒一次求值不心疼（就是日期的三角函数），换来"切主题立刻生效"。
            laneGuideView.setGuide(lane)
            // 无障碍：把"左/直/右"逐车道念出来，读屏和车机文字兜底都靠它
            laneGuideView.contentDescription = (0 until lane.count)
                .map { getString(lane.describeRes(it)) }
                .joinToString("，")
            if (laneGuideView.visibility != View.VISIBLE) laneGuideView.visibility = View.VISIBLE
        } else if (laneGuideView.visibility != View.GONE) {
            laneGuideView.setGuide(null)
            laneGuideView.visibility = View.GONE
        }

        // ---- 电子眼 / 限速 ----
        val cam = s.camera
        val showLimit = s.limitSpeedKmh > 0 && s.running
        when {
            cam != null && cam.distanceM in 1..CAMERA_SHOW_M -> {
                llCameraWarn.visibility = View.VISIBLE
                tvCameraWarn.text = if (cam.interval) {
                    getString(
                        R.string.camera_interval_remain,
                        com.motohud.ble.navi.SpeechScript.span(cam.intervalRemainM)
                    )
                } else {
                    // 一串探头并成一句（"违章拍照（3 处）"）：屏幕上是唯一的"数量"出口，
                    // 语音只按串念一次，不说 N —— "3 处"在内置语音包里没有片段
                    cameraText(cam) + when {
                        cam.clusterCount == com.motohud.ble.navi.CameraAlert.MULTI ->
                            getString(R.string.camera_cluster_multi)
                        cam.clusterCount > 1 ->
                            getString(R.string.camera_cluster_suffix, cam.clusterCount)
                        else -> ""
                    }
                }
                // 色标：红=测速 / 橙=闯红灯 / 黄=占道 / 灰=通用违章（只上屏不念的那类）
                bindCameraTone(cam.type)
                // 徽章优先用该电子眼的限速，没有就退回路段限速
                val limit = cam.speedKmh.takeIf { it > 0 } ?: s.limitSpeedKmh
                bindLimitBadge(limit)
            }
            // 没有电子眼但路段有限速：只显示限速，别空着
            showLimit -> {
                llCameraWarn.visibility = View.VISIBLE
                tvCameraWarn.text = getString(R.string.navi_limit_only, s.limitSpeedKmh)
                bindCameraTone(com.motohud.ble.navi.CameraKind.SPEED)
                bindLimitBadge(s.limitSpeedKmh)
            }
            else -> {
                if (llCameraWarn.visibility != View.GONE) llCameraWarn.visibility = View.GONE
            }
        }

        // ---- 路口放大图（画中画里不显示，小窗放不下）----
        // 三条显示规则：
        // ① SDK 说"要显示"（s.cross 非空）就一直显示 —— 高德真机复杂路口会挂 4~6 秒，
        //    固定 2.5 秒会把长图硬砍掉；
        // ② SDK `hideCross()` 之后仍挂 [CROSS_MIN_SHOW_MS] 再撤 —— show/hide 是成对但
        //    不等时的，简单路口可能只闪一帧就 hide，骑手抬头时图已经没了；
        // ③ 但无论如何不超过 [CROSS_MAX_SHOW_MS] —— 收不到 hide 时不能把右侧整栏永久占死。
        //    注意 crossShownAt 每收到一次新回调就刷新，所以只要 SDK 还在持续推图，
        //    这个上限就不会误伤；它只对"推了一次就再没消息"的僵尸状态生效。
        //
        // 判"有没有图"只看 bitmap 非空，**不看 `isRecycled`**：引擎侧已经复制了一份
        // 自己的位图（见 AmapNaviEngine.ownBitmap），这里再去看回收标志只会误判。
        val cross = s.cross
        val nowMs = System.currentTimeMillis()
        // 矢量放大图：`AMapModelCross.picBuf1` 是二进制矢量数据，解码成图片一定失败，
        // 要先交给 GL 画出来（见 VectorCrossRenderer）。渲染完由引擎按同一个 seq 回灌。
        if (cross != null && cross.needsRender && cross.seq != vectorRenderedSeq) {
            vectorRenderedSeq = cross.seq
            renderVectorCross(cross)
        }
        if (cross?.bitmap != null) {
            lastCross = cross
            crossShownAt = nowMs
        }
        val crossBitmap = lastCross?.bitmap
        // 两层窗口：SDK 还在推图时最多挂 8 秒（防卡死），推完补挂 4 秒（保证看得见）
        val crossActive = cross?.bitmap != null
        val cap = if (crossActive) CROSS_MAX_SHOW_MS else CROSS_MIN_SHOW_MS
        val showCross = !inPip && crossBitmap != null && nowMs - crossShownAt < cap
        setCrossMode(showCross)
        if (showCross) {
            /*
             * 按 [CrossImage.seq] 判重，**不能按 bitmap 引用判重**，有两层原因：
             *
             * ① 引擎侧：`_state` 是 MutableStateFlow，只在新旧值 `!=` 时才真的发出来。
             *    `CrossImage` 是 data class，同实例 bitmap 的两次 `copy(cross=...)` **相等**
             *    → 第二条及以后的放大图被 StateFlow 直接吞掉，界面根本收不到。
             * ② 界面侧：SDK 很可能复用同一个 Bitmap 实例重写像素，引用相等、内容已变，
             *    用 `tag !== crossBitmap` 判重会把后续所有图判成"没变化"而不刷新。
             *
             * 两处都靠 seq 解决：每次新回调 seq+1，值必然不等，UI 必然刷新。
             */
            val seq = lastCross?.seq ?: 0
            if (ivCrossImage.tag != seq) {
                ivCrossImage.setImageBitmap(crossBitmap)
                ivCrossImage.tag = seq
                android.util.Log.i(TAG_CROSS, "cross 显示 seq=$seq")
            }
            bindCrossHeader(s)
        } else if (ivCrossImage.tag != null) {
            ivCrossImage.setImageDrawable(null)
            ivCrossImage.tag = null
            android.util.Log.i(TAG_CROSS, "cross 撤下")
        }

        /*
         * 一行提示，三种来源按优先级取一个：
         * ① 交通事件（封路/施工/管制）—— 影响是否还能走这条线，最要紧；
         * ② 服务区 —— 高速/长途才出现；
         * ③ 可切主辅路 —— 走错会绕远，骑行最常踩的坑。
         * 同时显示会顶掉地图可视面积，路口前尤其忌讳。
         */
        val note = when {
            s.incident != null -> getString(R.string.navi_incident, s.incident.title)
            s.serviceArea != null && s.serviceArea.distanceM > 0 ->
                getString(
                    R.string.navi_service_area_ahead,
                    s.serviceArea.name,
                    CoordConverter.formatDistance(s.serviceArea.distanceM)
                )
            s.parallelRoad > 0 -> getString(R.string.navi_parallel_road)
            else -> null
        }
        if (note != null) {
            tvNaviNote.text = note
            tvNaviNote.visibility = View.VISIBLE
        } else if (tvNaviNote.visibility != View.GONE) {
            tvNaviNote.visibility = View.GONE
        }
    }

    /**
     * 路口大图「独占侧栏」模式开关。
     *
     * 开：把右栏整个让给大图 —— 转向卡、车道箭头、电子眼、ETA 全部 INVISIBLE（不是 GONE，
     *     这样恢复时不用重算布局，也不会因为尺寸跳变让地图重新居中）。
     * 关：原样恢复。返回键在左上角、独立于侧栏，所以大图期间仍然能退出导航。
     *
     * 为什么要独占：骑行时这块图只活两三秒，叠在侧栏上方会让字和图糊成一片；
     * 而它的信息量（这个路口怎么过）本来就覆盖了侧栏那几行字。
     */
    private fun setCrossMode(on: Boolean) {
        if (crossMode == on) return
        crossMode = on
        val v = if (on) View.INVISIBLE else View.VISIBLE
        topBar.visibility = v
        bottomInfo.visibility = v
        mapActions.visibility = v
        // 底部按钮行横屏本来就 GONE，恢复时不能一律给 VISIBLE，否则会冒出一整行红按钮
        findViewById<View>(R.id.bottom_buttons).visibility = when {
            on -> View.INVISIBLE
            landscapeNow -> View.GONE
            else -> View.VISIBLE
        }
        ivCrossImage.visibility = if (on) View.VISIBLE else View.GONE
        crossPanel.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            // 顶部让开状态栏（见 onCreate 末尾的说明）
            val d = resources.displayMetrics.density
            crossPanel.setPadding(
                (12 * d).toInt(),
                crossPanelBaseTop + statusBarPx,
                (12 * d).toInt(),
                (10 * d).toInt()
            )
        }
    }

    /**
     * 路口大图卡的头尾信息。
     *
     * 只放一张图时骑手会犯迷糊：「这张图是几米外的那个口子？」「我还有多远？」
     * 所以头部给「箭头 + 距离 + 动作」，底部留「预计到达 / 剩余距离 / 当前速度」——
     * 这三项在大图期间**唯一不重复**的信息（转向卡里的那些确实被图替代了）。
     */
    private fun bindCrossHeader(s: NaviEngine.NaviState) {
        ivCrossTurn.setImageResource(TurnIcons.res(s.iconType))
        tvCrossDistance.text = CoordConverter.formatDistance(s.stepRemain)
        tvCrossTitle.text = s.stepInstruction.ifBlank { getString(R.string.hud_turn_icon) }
        tvCrossEta.text = clock.format(Date(System.currentTimeMillis() + s.etaSeconds * 1000L))
        tvCrossRemain.text = CoordConverter.formatDistance(s.pathRemain)
        tvCrossSpeed.text = String.format(Locale.US, "%.0f", s.speedKmh)
    }

    private fun naviAgain() {
        val loc = engine.state.value
        RouteSelectActivity.start(
            this, destName, destLat, destLng,
            fromLat = if (loc.latitude != 0.0) loc.latitude else 0.0,
            fromLng = if (loc.longitude != 0.0) loc.longitude else 0.0
        )
        finish()
    }

    // ---------- 交互 ----------

    /** 底部卡展开/收起 */
    private fun toggleCard() {
        cardExpanded = !cardExpanded
        bottomExtra.visibility = if (cardExpanded) View.VISIBLE else View.GONE
        if (cardExpanded) renderExtraStats(engine.state.value)
    }

    /**
     * 「投到车机」开关 + 息屏导航开关。
     *
     * 原来这里是"显示方式"二选一，选投屏会跳到横屏 HUD 页；
     * HUD 的内容与车机 100% 重复，已删除。投屏本质是一个开关，
     * 与"手机显示什么"完全无关（BLE 转发由 NaviForwarder 统一负责）。
     */
    private fun showCastDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_display_mode, null)
        val swCast = v.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.sw_cast)
        val swScreenOff =
            v.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.sw_screen_off)
        val hint = v.findViewById<View>(R.id.tv_cast_hint)

        swCast.isChecked = NaviPrefs.castEnabled.value
        swScreenOff.isChecked = NaviPrefs.screenOffNavi.value
        hint.visibility =
            if (ble.state.value == BleConnectionManager.State.CONNECTED) View.GONE else View.VISIBLE

        MotoDialog.builder(this)
            .setTitle(R.string.navi_cast_title)
            .setView(v)
            .setNegativeButton(R.string.close, null)
            .show()

        swCast.setOnCheckedChangeListener { _, checked ->
            NaviPrefs.setCastEnabled(checked)
            // 开关本身就是反馈，不再补 Toast
        }
        swScreenOff.setOnCheckedChangeListener { _, checked ->
            NaviPrefs.setScreenOffNavi(checked)
            // 服务一直在跑（通知条要留着），这里只是让它重新评估唤醒锁
            if (!stopped) com.motohud.ble.service.NaviForegroundService.start(this)
        }
    }

    // ---------- 画中画 ----------

    /**
     * 进入画中画小窗。
     *
     * 用系统 PiP 而不是自绘悬浮窗：零额外权限、系统托管、不会被 ROM 拦；
     * 自绘 `SYSTEM_ALERT_WINDOW` 要引导授权、处理点击穿透，得不偿失。
     * 只在 Android 8+ 提供入口（minSdk 24），低版本隐藏按钮，用通知栏导航条兜底。
     */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val stopIntent = PendingIntent.getBroadcast(
            this,
            100,
            Intent(ACTION_PIP_STOP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val action = RemoteAction(
            // 画中画"结束导航"的按钮图标：用官方"终点旗"（sou15），不再自绘
            Icon.createWithResource(this, R.drawable.amap_navi_lbs_sou15),
            getString(R.string.guidance_stop),
            getString(R.string.guidance_stop),
            stopIntent
        )
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(action))
            .build()
        runCatching { enterPictureInPictureMode(params) }
            .onFailure {
                Toast.makeText(this, R.string.navi_pip_failed, Toast.LENGTH_SHORT).show()
            }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        val vis = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_back).visibility = vis
        // 语音 / 投屏已收进同一个悬浮容器，整箱隐藏即可（不必逐个设）
        findViewById<View>(R.id.quick_actions).visibility = vis
        findViewById<View>(R.id.btn_pip).visibility =
            if (isInPictureInPictureMode || landscapeNow) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_overview).visibility = vis
        findViewById<View>(R.id.btn_recenter).visibility = vis
        betterRouteCard.visibility = View.GONE
        tvSignalWarn.visibility = View.GONE
        // 小窗里只留转向横幅 + 距离：地图在 16:9 里看不清还费电
        bottomInfo.visibility = vis
        mapActions.visibility = vis
    }

    private fun confirmExit() {
        MotoDialog.builder(this)
            .setTitle(R.string.guidance_exit_title)
            .setMessage(R.string.guidance_exit_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.guidance_stop) { _, _ ->
                stopNavi(sendFinish = false)
                finish()
            }
            .show()
    }

    /** 停止导航并向车机发 NO（复刻原 App 退出写 "NO"） */
    private fun stopNavi(sendFinish: Boolean) {
        if (stopped) return
        stopped = true
        engine.onStepChanged = null
        engine.onSdkSpeech = null
        engine.onRerouteRequest = null
        betterRouteHandler.removeCallbacks(betterRouteTask)
        hideBetterRouteCard()
        engine.stop()
        // 停导航就停气压计：传感器回调一直在跑会白白耗电，而且离开导航后
        // 再判定坡度也没有任何用处（没人听）。
        terrainSense?.stop()
        com.motohud.ble.service.NaviForegroundService.stop(this)
        if (sendFinish) {
            app.naviForwarder.forwardFinish()
        } else {
            app.naviForwarder.forwardExit()
        }
        turnBanner.visibility = View.GONE
        // 导航历史 + 轨迹收尾
        finishHistory()
    }

    /** 结束这条历史记录，并把轨迹压缩写回 */
    private fun finishHistory() {
        val id = historyId ?: return
        finishedHistoryId = id
        com.motohud.ble.data.NaviHistory.finish(app, id, NaviPrefs.castEnabled.value)
        NaviTrack.finish(app, id)
        historyId = null
    }

    // ---------- 语音播报 ----------
    //
    // 历史教训：这里原来是系统 TextToSpeech，两个致命问题都表现为"导航全程哑巴"：
    // ① initTts 回调异步，就绪前的播报被丢弃；② 引擎缺中文数据时 ttsReady 永远 false。
    // 更根本的是——车机上根本没有可用的 TTS 引擎，怎么修都是哑巴。
    //
    // 现在两条路（由 SpeechRouter 选，互斥不会叠音）：
    //  - 装了离线 VITS 模型 → SherpaSpeaker，能念任意文本（含路名）；
    //  - 没装 → PresetPackSpeaker，播 APK 内置的 150+ 条预合成短句，
    //    方向/距离/第几个路口/出口都有，只是不含路名。

    private fun initVoice() {
        voice = com.motohud.ble.navi.SpeechRouter.create(this)
            .also { it.setMuted(!NaviPrefs.voice.value) }
        presetPack = voice is com.motohud.ble.navi.PresetPackSpeaker
        presetSpeaker = voice as? com.motohud.ble.navi.PresetPackSpeaker
        // 用内置语音包时"路名不念"必须讲一次，否则用户以为功能坏了；
        // 但每次进导航都弹就是骚扰，所以只提示一次。
        if (NaviPrefs.voice.value && !TtsModelStore.isInstalled(this) &&
            !NaviPrefs.packHintShown()
        ) {
            NaviPrefs.markPackHintShown()
            Toast.makeText(this, R.string.guidance_voice_no_model, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 播一句导航语音（主线程调用）。
     *
     * @param text 含路名的完整文本（距离+动作+路名），交给离线 TTS 整句念出来。
     * @param interrupt false = 接在后面播（进入路段的播报不该把「开始导航」切成半句）。
     * @param cue 播报类别，决定和别的播报撞车时谁让谁（见 [SpeechArbiter]）。
     */
    private fun say(
        text: String,
        priority: com.motohud.ble.navi.SpeechPriority =
            com.motohud.ble.navi.SpeechPriority.TURN,
        interrupt: Boolean = true,
        cue: com.motohud.ble.navi.SpeechArbiter.Cue =
            com.motohud.ble.navi.SpeechArbiter.cueOf(priority)
    ) {
        if (!NaviPrefs.voice.value) return
        if (text.isBlank()) return
        // 仲裁：正在说更重要的那句（偏航重算/到达）时，这条直接丢掉，
        // 不要把人正在听的关键指令切成半句——高德也是这么做的
        val verdict = com.motohud.ble.navi.SpeechArbiter.submit(
            cue, text, System.currentTimeMillis()
        )
        if (verdict == com.motohud.ble.navi.SpeechArbiter.Verdict.DROP) {
            android.util.Log.i("GuidanceVoice", "让位给重要播报，丢弃：$text")
            return
        }
        android.util.Log.i("GuidanceVoice", "播报：$text")
        voice?.speak(text, priority, interrupt)
    }

    /**
     * 开始导航的开场白：把"全程多远、预计多久"一次说清，
     * 骑车时不用看屏幕就知道这趟大概多长。
     */
    private fun buildStartText(r: RouteData?): String {
        if (r == null) return "开始导航"
        // 内置语音包念不出"10.1 公里"这种任意数字，硬拼会被切成"一公里"，
        // 所以只说一句「开始导航」——全程/ETA 屏幕上一直有
        if (presetPack) return "开始导航"
        val dist = CoordConverter.formatDistance(r.distanceM.toInt())
        // formatEta 给的是「16 分钟」「1 小时 20 分钟」这种带空格的展示文本，
        // 直接拼「到达」会多出一个空格（TTS 断句变怪），所以只在需要时补空格。
        val eta = CoordConverter.formatEta(r.durationS.toInt()).trim()
        return "开始导航，全程约 $dist，预计 ${eta}到达"
    }

    /**
     * 到达目的地：报一下这趟走了多远、花了多久（与到达卡片一致）。
     */
    private fun buildArrivedText(s: NaviEngine.NaviState): String {
        // 内置包念不了"全程约 10.1 公里，用时 32min"，只说到达
        if (presetPack) return "已到达目的地"
        val traveled = s.traveled.coerceAtLeast(0)
        val dist = CoordConverter.formatDistance(traveled)
        val durMs = (System.currentTimeMillis() - naviStartMs).coerceAtLeast(0L)
        val dur = formatDuration(durMs)
        return "已到达目的地，${destName}。本次导航全程约 $dist，用时 $dur"
    }

    /**
     * 某一段在**距路口 remainM 米**时该念的完整文本（临门指令）。
     *
     * 由 [com.motohud.ble.navi.VoiceGuidance.pre] 组合：
     * 距离 + 「第 N 个路口」+ 动作 + 目标路名（+ 环岛第 N 个出口）；
     * 下一段很短时再补「，然后左转进入 Y 路」。
     *
     * 为什么不直接念高德的 `instruction`：
     *  - 长路段那整句写的是「沿XX路行驶 500 米后右转」，而我们是在距路口
     *    200 米时播报，念「行驶 500 米后」是错的；
     *  - 简单路口高德只给「右转」两个字，连路名都没有 —— 合成出来比语音包
     *    的「前方 300 米右转」还短，这就是"合成引擎听着不完整"的根因。
     *
     * @param remainM 距该段结束还有多远；预合成时传整段长度
     */
    private fun stepText(stepIndex: Int, remainM: Int, speedKmh: Float): String {
        val steps = route?.steps ?: return ""
        val closeDist = com.motohud.ble.navi.VoiceGuidance.closeDistOf(speedKmh)
        // 内置语音包是 153 条预录 mp3、按整句匹配，念不出任意路名。
        // 连续路口压缩会把下一段的路名拼进来 → 整句库里没有 → 只能拆片段、
        // 拆出来缺路名反而更怪。所以走语音包时不压缩，保持单句。
        if (presetPack) {
            val step = steps.getOrNull(stepIndex) ?: return ""
            val icon = IconMapper.of(step.action, step.orientation, step.instruction)
            return com.motohud.ble.navi.VoiceGuidance.pre(
                step, icon, remainM, null, closeDist = closeDist
            )
        }
        return com.motohud.ble.navi.VoiceGuidance.pre(
            steps, stepIndex, remainM,
            // 与 thresholdsFor 用同一个函数算，保证"哪一档会被当成临门档"和这里判断一致
            closeDist = closeDist
        )
    }

    /**
     * 预合成某一段要说的话（主线程调用，内部转后台）。
     *
     * 离线引擎单句合成要 0.5~1.5s，正好卡在「该播了」的那一刻才合成必然滞后。
     * 所以一边在当前段骑行，一边就把下一段**所有可能要说的版本**先合成进缓存
     * （进段确认一句 + 各阈值上的临门指令，带/不带"然后"两种形态）。
     * 同一个序号只提交一次，渲染循环每帧调用也无副作用。
     */
    private fun prewarmStep(stepIndex: Int) {
        if (stepIndex < 0) return
        if (!NaviPrefs.voice.value) return
        // 内置包已经全在 assets 里，没有"合成"这一步，不必白算文本
        if (presetPack) return
        val steps = route?.steps ?: return
        if (stepIndex == prewarmedStep) return
        // 道路要素的固定句（「前方进入隧道，请开灯」等）也走同一条合成链路，
        // 否则 sherpa 模式下第一次遇到隧道要现场合成 0.5~1.5 秒，洞里才出声。
        if (prewarmedStep < 0) {
            com.motohud.ble.navi.RoadFeatureNarrator.prewarmTexts().forEach { voice?.prewarm(it) }
            // 弯道与坡度的固定句同理：山路上一句弯道预警只有 2~3 秒的提前量，
            // 现场合成绝对来不及。
            com.motohud.ble.navi.CurveNarrator.prewarmTexts().forEach { voice?.prewarm(it) }
            com.motohud.ble.navi.TerrainNarrator.prewarmTexts().forEach { voice?.prewarm(it) }
            com.motohud.ble.navi.DriveBehaviorNarrator.prewarmTexts().forEach { voice?.prewarm(it) }
        }
        prewarmedStep = stepIndex
        com.motohud.ble.navi.VoiceGuidance.prewarmTexts(steps, stepIndex)
            .forEach { voice?.prewarm(it) }
    }

    // ---------- 权限 ----------

    private fun ensureLocationPermission() {
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) locationPermLauncher.launch(missing.toTypedArray())
    }

    // ---------- 生命周期 ----------

    override fun onStart() {
        super.onStart()
        app.naviForwarder.attach(TAG_FORWARD)
    }

    override fun onStop() {
        super.onStop()
        // 息屏导航 / 画中画：此时 Activity 会走到 onStop，但导航还在继续，
        // 不能解除转发，否则车机上的数据就停住了。
        // 只有真正结束导航 / 退出页面才 detach。
        if (inPip) return
        if (stopped || isFinishing) app.naviForwarder.detach(TAG_FORWARD)
    }

    override fun onResume() {
        super.onResume()
        if (!inPip) {
            mapView?.onResume()
            shadowNaviView?.onResume()
        }
        // 画中画切换会把窗口属性重置，亮度与常亮要重新施加一次
        applyScreenPrefs()
        // 设置面板里可能刚改过语音开关，回前台时同步按钮状态
        updateVoiceButton()
        // 导航可能是在通知栏 / 画中画里被"结束导航"停掉的：那边只停了引擎，
        // 本页的 stopped 不会跟着变，回到这里还会显示"导航中"，用户以为没停掉。
        if (!stopped && !engine.state.value.running && started) {
            stopNavi(sendFinish = false)
            finish()
        }
    }

    /**
     * 屏幕常亮与亮度。
     *
     * 原来这里是 `window.addFlags(FLAG_KEEP_SCREEN_ON)` 一行，无条件常亮。
     * 两个场景因此没法处理：
     *  - **夜骑**：全亮屏幕直射眼睛，只能退出去改系统亮度，改完整个手机都变暗了；
     *  - **长途放兜里**：语音听路时屏幕没必要亮着，全程常亮是纯耗电。
     *
     * 亮度用**窗口属性**（`screenBrightness`）而不是改系统设置：
     * 窗口属性随 Activity 销毁自动失效，退出导航即恢复系统亮度，
     * 不会留下"用户不知道手机为什么变暗了"的后遗症。
     */
    private fun applyScreenPrefs() {
        if (NaviPrefs.screenKeepOn.value) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        runCatching {
            val lp = window.attributes
            lp.screenBrightness = NaviPrefs.screenBrightnessValue()
            window.attributes = lp
        }
    }

    override fun onPause() {
        if (!inPip) {
            mapView?.onPause()
            shadowNaviView?.onPause()
        }
        super.onPause()
    }

    /**
     * 横竖屏切换（Manifest 的 configChanges 含 orientation，Activity 不重建）。
     *
     * 横屏屏矮（约 360dp），竖屏那套「底部通栏信息卡」会把地图挤得只剩一条缝，
     * 所以横屏把顶栏和底部信息卡收成**右侧一条 300dp 侧栏**、顶部渐变收窄、
     * 地图操作键「回中」贴在侧栏左侧，地图拿回左侧与高度方向的全部空间；
     * 返回键则从顶栏里独立出来，固定在左上角 —— 横屏唯一的退出入口
     * （底部「停止导航」是同一个动作，横屏已隐藏）。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape && ::bottomExtra.isInitialized && bottomExtra.visibility == View.VISIBLE) {
            cardExpanded = false
            bottomExtra.visibility = View.GONE
        }
        applyOrientationLayout(landscape)
        lastCameraMs = 0L
    }

    private fun applyOrientationLayout(landscape: Boolean) {
        landscapeNow = landscape
        val dm = resources.displayMetrics
        fun dp(v: Int): Int = (v * dm.density).toInt()

        // 顶部渐变：横屏收窄，否则盖掉大半张图
        topScrim.layoutParams = topScrim.layoutParams.apply {
            height = if (landscape) dp(96) else dp(220)
        }

        // ---- 顶栏 + 信息卡：横屏统一收成「右侧一条侧栏」，地图拿回左侧全部空间 ----
        // 竖屏恢复顶部通栏 + 底部通栏。横屏就是为「看清前方路口」而生的，
        // 不该再像竖屏那样把控件摊在两边、把地图挤成一条缝。
        // 侧栏在右、返回键独立留在左上角：驾驶时右手拇指够得着侧栏按钮，
        // 左上角只承担「退出」这一个动作，不会被误触到别的控件。
        (topBar.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (landscape) dp(DP_PANEL_LAND) else ViewGroup.LayoutParams.MATCH_PARENT
            gravity = if (landscape) Gravity.TOP or Gravity.END else Gravity.TOP
            topBar.layoutParams = this
        }
        // 顶栏第一行横屏只剩「画中画」（且已 GONE），目的地卡也隐藏 ——
        // 整行塌陷，转向横幅因此顶到栏顶，把原来被两个 44dp 圆钮占掉的 52dp 全数拿回来。
        findViewById<LinearLayout>(R.id.top_row).gravity = if (landscape) {
            Gravity.END or Gravity.CENTER_VERTICAL
        } else {
            Gravity.START or Gravity.CENTER_VERTICAL
        }
        // 语音 / 投屏 / 定位三枚圆钮：
        //   竖屏 —— 横排贴右上角（与原位置一致），定位留在右下角与全览一列；
        //   横屏 —— **竖排一列挂在左上角**、返回键正下方（用户要求：左右三个钮改竖排，
        //           定位也从右下角挪过来，整列 [返回 / 语音 / 投屏 / 定位]）。
        // 竖屏不复刻原位置的话目的地卡会被压住，所以 portrait 时给 dest_card 补
        // 一个 rightMargin = 8 + 44 + 6 + 44 = 102dp 把圆钮那块让出来。
        applyQuickActions(landscape)
        (quickActions.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (landscape) Gravity.TOP or Gravity.START else Gravity.TOP or Gravity.END
            if (landscape) {
                // 与返回键左对齐、挂在它下方（topMargin 由 insets listener 补 52dp）
                marginStart = dp(12)
                marginEnd = 0
            } else {
                marginStart = 0
                marginEnd = dp(12)
            }
            quickActions.layoutParams = this
        }
        // 旋转不重建 Activity，insets 不会自动重发；gravity 换了必须让它重新走一遍
        // listener，否则横屏切竖屏后 marginEnd/marginStart 还是上一方向的值。
        quickActions.requestApplyInsets()
        (findViewById<View>(R.id.dest_card).layoutParams as ViewGroup.MarginLayoutParams).apply {
            // 竖屏：给右侧悬浮的语音/投屏让位（102dp = 两个 44dp 圆钮 + 间距 + 8dp 空隙）
            marginEnd = if (landscape) 0 else dp(8 + 44 + 6 + 44)
            findViewById<View>(R.id.dest_card).layoutParams = this
        }

        // 目的地卡横屏整卡收掉：横屏空间只有 300dp 宽，
        // 骑行者本来就知道去哪，这块让给转向横幅更值（返回键已独立不占行内位置）
        findViewById<View>(R.id.dest_card).visibility =
            if (landscape) View.GONE else View.VISIBLE
        // 副标题（全程 X 公里·预计 Y）与模式角标在横屏让位：不是骑行中要盯的东西
        tvDestSub.visibility = if (landscape) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tv_mode_badge).visibility =
            if (landscape) View.GONE else View.VISIBLE

        // 转向横幅跟顶栏一起收窄，不再额外加右边距（顶栏本身只有面板宽）
        (turnBanner.layoutParams as ViewGroup.MarginLayoutParams).apply {
            rightMargin = 0
            turnBanner.layoutParams = this
        }
        // 路口放大图：横屏**整栏盖住右侧侧栏**（与侧栏同宽、满高），
        // 竖屏贴底通栏盖住 ETA/按钮那一段。大图撤掉后侧栏由 setCrossMode 恢复。
        crossPanel.layoutParams = (crossPanel.layoutParams as FrameLayout.LayoutParams).apply {
            if (landscape) {
                width = dp(DP_PANEL_LAND)
                height = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.TOP or Gravity.END
                marginStart = 0
                topMargin = 0
                bottomMargin = 0
            } else {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = dp(260)
                gravity = Gravity.BOTTOM
                marginStart = 0
                topMargin = 0
                bottomMargin = 0
            }
        }
        // 车道行横屏压到 44dp：侧栏竖向只有 360dp，转向卡（大图标）+ 车道行
        // + 电子眼行要一起塞下，56dp 会把电子眼那行顶到下方信息卡底下（真机已复现重叠）。
        laneGuideView.layoutParams = laneGuideView.layoutParams.apply {
            height = dp(if (landscape) 44 else 56)
        }
        // 横屏把转向图标收到 72dp、上下内边距收到 10dp：仍然是"一个大箭头"的视觉重心，
        // 但给车道行/电子眼行腾出整整 30dp —— 360dp 高的屏上这 30dp 就是"会不会重叠"。
        val turnIconSize = dp(if (landscape) 72 else 88)
        ivTurnIcon.layoutParams = ivTurnIcon.layoutParams.apply {
            width = turnIconSize
            height = turnIconSize
        }
        turnBanner.setPadding(dp(12), dp(if (landscape) 10 else 12), dp(14), dp(if (landscape) 10 else 12))
        (tvSignalWarn.layoutParams as ViewGroup.MarginLayoutParams).apply {
            rightMargin = 0
            tvSignalWarn.layoutParams = this
        }
        // 横屏屏高只有 360dp，"下一转弯"是锦上添花，留它会把横幅顶高一大截
        tvTurnNext.visibility = if (landscape) View.GONE else View.VISIBLE

        // ---- 横屏极度精简：只留导航相关 ----
        // 隐藏：画中画入口（小窗场景另走通知条）、底部投屏钮（顶栏已有同功能钮）、
        //       GPS 质量列（诊断项非导航必看）、全览（横屏只留「回中」）。
        btnPip.visibility = if (landscape) View.GONE else
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_cast_navi).visibility =
            if (landscape) View.GONE else View.VISIBLE
        statGps.visibility = if (landscape) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_overview).visibility =
            if (landscape) View.GONE else View.VISIBLE

        // 折叠手柄 / 路况估算：横屏本就隐藏
        findViewById<View>(R.id.btn_expand).visibility = if (landscape) View.GONE else View.VISIBLE
        tvTrafficEstimate.visibility = if (landscape) View.GONE else View.VISIBLE
        // 车机连接提示在横屏收掉，避免把侧栏顶高一整行；竖屏照常由连接状态控制
        if (landscape) tvBleHint.visibility = View.GONE
        // 「停止导航」按钮 = 左上角返回键（同一个 confirmExit），横屏是重复入口，整行收掉；
        // 竖屏保留，底部通栏那个红按钮是最显眼的退出方式。
        // 连带隐藏整行：横屏投屏大按钮本来就 GONE，留着这行只剩 12dp 空白。
        findViewById<View>(R.id.bottom_buttons).visibility =
            if (landscape) View.GONE else View.VISIBLE

        val card = bottomInfo.layoutParams as FrameLayout.LayoutParams
        val acts = mapActions.layoutParams as FrameLayout.LayoutParams
        if (landscape) {
            // 信息卡：右侧竖直条，与顶栏同宽对齐成一条侧栏；地图拿回左侧全部空间
            card.gravity = Gravity.END or Gravity.BOTTOM
            card.width = dp(DP_PANEL_LAND)
            // 地图操作键横屏已搬到左上角竖列（见 applyQuickActions）：这个容器里
            // 只剩全览（横屏 GONE），留着不显示，位置给个不碍事的左下角
            acts.gravity = Gravity.START or Gravity.BOTTOM
            acts.marginStart = dp(12)
            acts.marginEnd = 0
            acts.bottomMargin = dp(16)
        } else {
            // 竖屏：恢复底部通栏 + 右侧悬浮操作键
            card.gravity = Gravity.BOTTOM
            card.width = ViewGroup.LayoutParams.MATCH_PARENT
            acts.gravity = Gravity.END or Gravity.BOTTOM
            acts.marginStart = 0
            acts.marginEnd = dp(14)
            acts.bottomMargin = dp(212)
        }
        bottomInfo.layoutParams = card
        mapActions.layoutParams = acts

        // 横屏空间窄，转向引导语收成单行，避免双行把横幅顶高与侧栏相撞
        tvTurnInstruction.maxLines = if (landscape) 1 else 2

        // 放大图的绘制尺寸跟屏幕方向绑定，旋转后必须重算一次。
        // 不重算的后果：竖屏启动、转横屏后 native 仍按竖屏缓冲区出图（实测 302x1257），
        // 图会被压成一条竖条，看起来像"放大图又坏了"。
        applyCrossImageSize()

        /*
         * 状态栏图标深浅必须**放在最后，而且要推迟一帧**。
         *
         * 上面这一堆 layoutParams 改动 + `requestApplyInsets()` 会让系统重算
         * 一次 decor insets，重算时把 appearance 标记刷回主题默认值（基础主题是
         * 深色 → 白图标）。放在开头设等于白设：真机复现是白天横屏深灰图标，
         * 转竖屏立刻变纯白、压在浅色地图上，时钟基本看不见。
         * post 到下一帧，等这轮重算结束再写。
         */
        window.decorView.post { applySystemBarAppearance() }
    }

    /**
     * 语音 / 投屏 / 定位三枚圆钮的排列与归属。
     *
     * 横屏：`quick_actions` 转成**竖排**，「定位」从 `map_actions` 搬进来 ——
     * 左上角从「返回」往下就是一整列 [返回 / 语音 / 投屏 / 定位]，右侧侧栏再少
     * 一处悬浮控件，地图左侧边缘只占 56dp。
     *
     * 竖屏：完全恢复原样 —— 语音 / 投屏横排贴右上角，定位和全览一列挂右下角。
     *
     * 为什么搬 View 而不是复制一个定位按钮：定位键带状态（跟随/自由视角高亮、
     * 点击回调、画中画隐藏），复制一份会出现"点了没反应"或两份状态不同步。
     */
    private fun applyQuickActions(landscape: Boolean) {
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val gap = px(6)
        fun lp44() = LinearLayout.LayoutParams(px(44), px(44))

        quickActions.orientation =
            if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        // 定位键换容器：横屏 → 左上竖列；竖屏 → 右下 map_actions
        val target: ViewGroup = if (landscape) quickActions else (mapActions as ViewGroup)
        if ((btnRecenter.parent as? ViewGroup) !== target) {
            (btnRecenter.parent as? ViewGroup)?.removeView(btnRecenter)
            target.addView(btnRecenter)
        }

        // 竖排看 topMargin、横排看 marginStart：语音永远第一个（顶/左），投屏第二个
        fun place(v: View, order: Int) {
            val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: lp44()
            lp.width = px(44)
            lp.height = px(44)
            if (landscape) {
                lp.topMargin = if (order == 0) 0 else gap
                lp.marginStart = 0
            } else {
                lp.marginStart = if (order == 0) 0 else gap
                lp.topMargin = 0
            }
            v.layoutParams = lp
        }
        place(findViewById(R.id.btn_voice), 0)
        place(findViewById(R.id.btn_cast), 1)

        if (landscape) {
            place(btnRecenter, 2)
        } else {
            // 竖屏放回 map_actions：与全览同宽、10dp 间距（与 XML 原值一致）
            val lp = (btnRecenter.layoutParams as? LinearLayout.LayoutParams) ?: lp44()
            lp.width = px(44)
            lp.height = px(44)
            lp.marginStart = 0
            lp.topMargin = px(10)
            btnRecenter.layoutParams = lp
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroy() {
        // 用户未主动停止（直接返回键/进程回收）时，同样向车机发 NO 退出
        if (!stopped) {
            stopped = true
            engine.onStepChanged = null
            engine.onSdkSpeech = null
            engine.stop()
            com.motohud.ble.service.NaviForegroundService.stop(this)
            app.naviForwarder.forwardExit()
            finishHistory()
        }
        app.naviForwarder.detach(TAG_FORWARD)
        runCatching { unregisterReceiver(pipReceiver) }
        betterRouteHandler.removeCallbacks(betterRouteTask)
        // 必须在 mapView.onDestroy 之前：CrossOverlay 挂在 aMap 上，地图先销毁会抛
        runCatching { vectorCrossRenderer?.release() }
        vectorCrossRenderer = null
        runCatching { shadowNaviView?.onDestroy() }
        shadowNaviView = null
        mapView?.onDestroy()
        mapView = null
        aMap = null
        runCatching { voice?.release() }
        voice = null
        // 气压计监听必须注销：传感器回调持有 Activity 引用，不注销会漏内存
        terrainSense?.stop()
        terrainSense = null
        super.onDestroy()
    }

    private inline fun safeMap(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_FROM_LAT = "extra_from_lat"
        const val EXTRA_FROM_LNG = "extra_from_lng"

        /** 途经点，`lat,lng;lat,lng`。中途追加途经点时要拿它一起重算 */
        const val EXTRA_WAYPOINTS = "extra_waypoints"

        /**
         * 模拟导航（`NaviType.EMULATOR`）：不读 GPS，SDK 按固定车速沿路线推演。
         * 用于室内把「转向图标 / 播报 / 投屏帧」整条链路走一遍。
         */
        const val EXTRA_EMULATOR = "extra_emulator"

        /** 模拟导航车速（km/h），市区路口的播报节奏接近真实骑行 */
        const val EMULATOR_SPEED_KMH = 45

        /**
         * 覆盖模拟车速，单位 km/h。测高速路线（立交放大图 / 区间测速 / 出口预告）时传 100 左右，
         * 否则 20+ km 的机场高速按 45 km/h 要跑半小时，而且距离递减不符合真实高速。
         */
        const val EXTRA_SPEED = "extra_speed"
        private const val TAG_FORWARD = "guidance"
        private const val ACTION_PIP_STOP = "com.motohud.ble.action.PIP_STOP"

        /** 路口前自动放大的触发距离 */
        private const val AUTO_ZOOM_RANGE_M = 200

        /** 电子眼进入该距离内才播报（再远念了没意义，还盖住转向提示） */
        private const val CAMERA_SPEAK_M = 800

        /** 电子眼进入该距离内才显示在界面上 */
        private const val CAMERA_SHOW_M = 1200

        /**
         * 两条电子眼播报之间的最小间隔（跨串）。
         *
         * 原来是 6 秒 —— 实测下来它根本不起"降噪"作用，反而成了节拍器：
         * 观音桥 1.3km 上 95 秒念了 15 条，平均 6.3 秒一句，说明每句一结束
         * 下一句立刻接上。15 秒 = 一句(~3s) + 12 秒清净，实测能把一条街上的
         * 连排探头压成 1~2 句。串内本来就不重复念（引擎并串 + 档位去重），
         * 这个间隔只负责拦"两串挨得太近"的情况。
         */
        private const val CAMERA_MIN_GAP_MS = 15_000L

        /**
         * 同一**分组**电子眼的再次播报间隔。
         *
         * 60 秒 ≈ 45km/h 跑 750 米。相邻两个路口都是「闯红灯拍照」时，
         * 建议是同一句（别闯灯），重复念没有任何新信息；但换了组
         * （突然出现测速/压线）就该开口 —— 那是另一件事。
         */
        private const val CAMERA_SAME_GROUP_MS = 60_000L

        /**
         * 路口放大图最短展示时间。
         *
         * SDK 的 show/hide 不等时，简单路口可能只闪一帧 —— 骑手抬头时图已经没了，
         * 感觉"根本没有路口图"。4 秒是"来得及扫一眼、又不至于把地图挡太久"的折中：
         * 按 30km/h 只走 33 米，不影响判断。
         */
        private const val CROSS_MIN_SHOW_MS = 4_000L

        /**
         * 路口放大图最长展示时间（兜底）。
         *
         * 正常情况下 SDK 会调 `hideCross()`，横屏时整栏被大图独占到路口过完为止。
         * 但真机上有两种异常会让我们永远收不到 hide：
         * ① SDK 只推一次 show、路口过完不补 hide（模拟导航里偶现）；
         * ② 引擎在回调线程抛异常，后续回调链断了。
         * 没有这个上限，右侧整栏（转向箭头 + 车道 + 电子眼）会被一张过期的路口图
         * 永久盖住 —— 比"看不到大图"严重得多。8 秒按 40km/h 约 90 米，
         * 正常路口早已通过，留足看的时间又不会赖着不走。
         */
        private const val CROSS_MAX_SHOW_MS = 8_000L

        /** 路口大图相关日志（影子导航视图的初始化结果） */
        private const val TAG_CROSS = "NaviCross"

        /**
         * 距路口该距离内把车道指引并进转向句（「…左转，请走左侧车道」）。
         *
         * 500 米：按 30km/h 约 60 秒，够听清也够变道；再远说"走哪条车道"没有行动意义。
         */
        private const val LANE_HINT_M = 500

        /**
         * 两次转向播报的最小间隔。
         *
         * 4 秒 ≈ 一句「前方 300 米，第 1 个路口右转进入四新路」的念完时间。
         * 比这更密就会叠音（前一句还没念完后一句就抢进来），听起来就是"反复播报"。
         */
        private const val APPROACH_MIN_GAP_MS = 4_000L

        /** 服务区在 3 公里内才播报（再早念了没有行动价值） */
        private const val SERVICE_AREA_SPEAK_M = 3000

        /**
         * 坡度提示的最小间隔（90 秒）。
         *
         * 一段长下坡可能延续几公里，20 秒判定窗口内每秒都满足条件；
         * 不节流就会变成"连续下坡、连续下坡、连续下坡"。
         */
        private const val TERRAIN_COOLDOWN_MS = 90_000L

        /** 驾驶行为采信频率：GPS 一般 1Hz，取 500ms 保证不漏帧又不重复喂 */
        private const val BEHAVIOR_FEED_MS = 500L

        /**
         * 横屏左侧侧栏宽度（dp）。
         * 806dp 宽的屏上占 300dp —— 约 1/3，其余（右侧）全部留给地图与真实导航位置。
         */
        private const val DP_PANEL_LAND = 300

        /** 横屏镜头前移距离（米）：把车标压到屏幕下方，前方路口才看得全 */
        private const val CAMERA_AHEAD_M = 30.0

        /** 更优路线检查间隔（4 分钟，兼顾体验与流量节省） */
        private const val BETTER_ROUTE_INTERVAL_MS = 4 * 60_000L
        /** 比当前路线快多少秒才提示换线 */
        private const val BETTER_ROUTE_SAVE_SEC = 300
        /** 比当前路线少堵多少米才提示换线（绕开前方拥堵场景） */
        private const val BETTER_ROUTE_SAVE_CONG_M = 1000
        /** 前方拥堵达到该米数才额外尝试"避拥堵"方案 */
        private const val CONGESTION_TRIGGER_M = 800
    }
}
