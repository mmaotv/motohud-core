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
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
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
 * 转弯提前播报的距离阶梯（米，降序）：1 公里 → 500 → 200 → 50。
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
 * 即 **1100 预警 + 550 确认 + 临门动作**，加进来正好 3 次。以前我们多一档 200，
 * 与 550 只隔两百来米（高速上不到 8 秒），听感是同一句话说两遍。
 *
 *  - **1100 米**：预警，「前方 1 公里右转进入南滨路」；
 *  - **550 米**：确认，最后一次带距离的提醒；
 *  - **临门**：只报动作（「右转」「进入环岛，第 3 个出口」），见
 *    [com.motohud.ble.navi.VoiceGuidance.closeDistOf]。
 *
 * 前两档固定；最后一档出厂值 50 米，实际由 `thresholdsFor` 按车速替换
 * （30/45/60/80），所以这里写的是兜底值。
 */
private val TURN_THRESHOLDS = intArrayOf(1_100, 550, 50)

/** 直行路口的提醒档：200 米预警 + 临门一句「路口直行」，长直路不啰嗦 */
private val STRAIGHT_THRESHOLDS = intArrayOf(200, 50)

/** 简洁模式：砍掉 1100 预警，只留 550 确认 + 临门动作 */
private val BRIEF_THRESHOLDS = intArrayOf(550, 50)

/** 只播转弯模式：只在 200 米处念一句 */
private val TURN_ONLY_THRESHOLDS = intArrayOf(200)

class GuidanceActivity : AppCompatActivity() {

    private lateinit var app: MotoHudApp
    private lateinit var engine: RouteNaviEngine
    private lateinit var ble: BleConnectionManager

    private var mapView: MapView? = null
    private var aMap: AMap? = null

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
    private lateinit var tvBleHint: TextView
    private lateinit var tvLights: TextView
    private lateinit var tvTolls: TextView
    private lateinit var tvStepProgress: TextView
    private lateinit var tvTrafficEstimate: TextView
    private lateinit var planningOverlay: View
    private lateinit var arrivedOverlay: View
    private lateinit var mapActions: View
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
    private var destName: String = "目的地"
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

    /** 当前是否横屏：镜头缩放/俯角/前移都要跟着变，横屏和竖屏必须有区别 */
    private var landscapeNow = false

    /** 上一次已播报过的路段序号：进入新路段时播一次「前方 X 米 左/右转」 */
    private var lastAnnouncedStep = -1

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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guidance)

        app = application as MotoHudApp
        engine = app.naviEngine
        ble = app.bleManager

        // 偏航 → 自动重新规划（开错路时上层负责算新路线）
        engine.onRerouteRequest = { lat, lng -> requestReroute(lat, lng) }

        NaviPrefs.init(this)
        // 坡度感知：设备有气压计才创建，没有就留 null（整套逻辑静默关闭）
        terrainSense = com.motohud.ble.navi.TerrainSense(this)
        bindViews()
        applyWindowInsets()
        // 旋转不重建 Activity，布局形态（横屏右下面板 / 竖屏底部通栏）由这里统一施加
        applyOrientationLayout(
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
        applyScreenPrefs()

        destName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "目的地" }
        destLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        destLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        waypoints = parseWaypoints(intent.getStringExtra(EXTRA_WAYPOINTS))

        tvDestName.text = destName
        tvDestSub.text = CoordConverter.formatLatLng(destLat, destLng)

        ensureLocationPermission()
        setupMap(savedInstanceState)
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
        tvBleHint = findViewById(R.id.tv_guidance_ble_hint)
        tvLights = findViewById(R.id.tv_lights)
        tvTolls = findViewById(R.id.tv_tolls)
        tvStepProgress = findViewById(R.id.tv_step_progress)
        tvTrafficEstimate = findViewById(R.id.tv_traffic_estimate)
        planningOverlay = findViewById(R.id.planning_overlay)
        arrivedOverlay = findViewById(R.id.arrived_overlay)
        mapActions = findViewById(R.id.map_actions)
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
                        "推荐"
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
        prewarmedStep = -1
        featureAnnounced.clear()
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
            startName = start?.let { CoordConverter.formatLatLng(it.first, it.second) } ?: "未知",
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
                    "避拥堵"
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
                    color(ContextCompat.getColor(this@GuidanceActivity, R.color.track_ahead))
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
                    .color(ContextCompat.getColor(this, R.color.track_done))
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
        val roadText = route?.steps?.getOrNull(s.stepIndex)?.road.orEmpty()
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

        // 进入新路段 → 播一次，并重置本段的阈值调度
        if (s.stepIndex != lastAnnouncedStep) {
            lastAnnouncedStep = s.stepIndex
            remindStepIndex = s.stepIndex
            remindMask = 0
            activeThresholds = thresholdsFor(s)
            val spokeAt = announceStepEntered(s)
            // 进入时就已念过的距离，把同一档及更远的档位标记为已播，避免复读/倒着播
            if (spokeAt > 0) markAnnouncedAt(spokeAt)
        }
        // 按距离阈值逐级提前提醒（1 公里/500/200/50），最后一档只报动作
        announceApproaching(s)
        // 沿途道路要素（隧道/桥梁/立交/匝道/天桥）：进入前单独播一次
        announceRoadFeatures(s)
        // 弯道预警（急转弯/连续弯道）：从折线曲率自算，提前 300 米
        announceCurve(s)
        // 坡度提示（长下坡/长上坡）：气压计测量，只能报"当前"
        announceTerrain(s)
        // 喂两个感知器（坡度需要里程增量，行为需要速度/航向）
        feedDriveSenses(s)
        // 趁还在当前路段，把下一段的引导语先合成好（进入下一段直接命中缓存）
        prewarmStep(s.stepIndex + 1)

        if (s.arrived) {
            if (!stopped) {
                stopped = true
                app.naviForwarder.forwardFinish()
                say(buildArrivedText(s), SpeechPriority.INFO, cue = com.motohud.ble.navi.SpeechArbiter.ARRIVED)
                // 导航历史：到达即结束这条记录（轨迹在这里压缩写回）
                finishHistory()
            }
            hideBetterRouteCard()
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
        val steps = route?.steps.orEmpty()
        val next = steps.getOrNull(s.stepIndex + 1)
        val text = next?.instruction.orEmpty().ifBlank { next?.action.orEmpty() }
        if (s.arrived || text.isBlank()) {
            tvTurnNext.visibility = View.GONE
        } else {
            tvTurnNext.visibility = View.VISIBLE
            tvTurnNext.text = getString(R.string.guidance_next_step, text)
        }
    }

    /** 展开区：红绿灯 / 过路费 / 步骤进度 / 路况估算 */
    private fun renderExtraStats(s: NaviEngine.NaviState) {
        val r = route ?: return
        if (!cardExpanded) return
        tvLights.text = r.trafficLights.toString()
        tvTolls.text = if (r.tolls > 0f) String.format(Locale.US, "%.0f 元", r.tolls) else "免费"
        val total = r.steps.size
        tvStepProgress.text = if (total > 0) "${(s.stepIndex + 1).coerceIn(1, total)}/$total" else "--"
        tvTrafficEstimate.text = buildTrafficEstimate(r)
    }

    /**
     * 路况估算。
     *
     * 高德**搜索 SDK 没有实时路况分段**（那是导航 SDK 的能力），所以这里只能用
     * 每步「距离 ÷ 时长」推出的平均速度来估三档，并明确标注"估算"——
     * 不能伪装成实时路况，否则用户按这个决策会出问题。
     */
    private fun buildTrafficEstimate(r: RouteData): String {
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
        val step = route?.steps?.getOrNull(s.stepIndex) ?: return -1
        val r = s.stepRemain.toInt()
        if (r <= 0) return -1
        val icon = IconMapper.of(step.action, step.orientation, step.instruction)
        val isTurn = TurnIcons.isTurn(icon)
        val mode = NaviPrefs.voiceMode.value
        // 只播转弯：只在路口段开口；其余模式长直段也报一次路名，短碎段不抢话
        val should = when {
            mode == NaviPrefs.VOICE_MODE_TURN_ONLY -> isTurn
            isTurn -> true
            r >= 800 -> true
            else -> false
        }
        if (!should) return -1
        // 内置语音包念不出路名，别拼「继续沿烟雨路行驶」——把路名去掉，
        // 直接说「继续直行 500 米」，这段在语音包里有整句，一次命中
        val stepForVoice = if (presetPack) step.copy(road = "") else step
        val text = com.motohud.ble.navi.VoiceGuidance.entry(stepForVoice, icon, r.toFloat())
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
        val spokeBucket = com.motohud.ble.navi.SpeechScript.distance(spokeAt)
        for (i in activeThresholds.indices) {
            val t = activeThresholds[i]
            if (t >= spokeAt || com.motohud.ble.navi.SpeechScript.distance(t) == spokeBucket) {
                remindMask = remindMask or (1 shl i)
            }
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
        // 只在档位数 >= 2 时刷新 —— "只播转弯"模式只有一档 [200]，那是提醒档不是临门档。
        if (activeThresholds.size >= 2) activeThresholds = withCloseDist(activeThresholds, s)
        val rem = s.stepRemain.toInt()
        if (rem <= 0) return
        var chosen = -1
        for (i in activeThresholds.indices) {
            val t = activeThresholds[i]
            if (rem <= t && (remindMask and (1 shl i)) == 0) chosen = i
        }
        if (chosen < 0) return
        for (i in 0..chosen) remindMask = remindMask or (1 shl i)
        // 临门那句是「右转」，和预警句「前方 500 米右转进入X路」在仲裁表里分属两档：
        // 到跟前时哪怕有路况提示在说，也该让临门动作抢进来。
        val cue = if (rem <= com.motohud.ble.navi.VoiceGuidance.closeDistOf(s.speedKmh)) {
            com.motohud.ble.navi.SpeechArbiter.CLOSE_ACTION
        } else {
            com.motohud.ble.navi.SpeechArbiter.APPROACH
        }
        say(stepText(s.stepIndex, rem, s.speedKmh), cue = cue)
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
            Icon.createWithResource(this, R.drawable.ic_turn_arrive),
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
        findViewById<View>(R.id.btn_voice).visibility = vis
        findViewById<View>(R.id.btn_cast).visibility = vis
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
        if (!inPip) mapView?.onResume()
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
        if (!inPip) mapView?.onPause()
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
        // 顶栏第一行横屏只剩语音 / 投屏两个按钮（返回键已独立、目的地卡隐藏），
        // 目的地卡一隐藏它们会掉到面板最左边，看着像孤立的小圆点；
        // 横屏改成靠右对齐，贴着面板右边缘与下方横幅的右端成一条竖线。
        findViewById<LinearLayout>(R.id.top_row).gravity = if (landscape) {
            Gravity.END or Gravity.CENTER_VERTICAL
        } else {
            Gravity.START or Gravity.CENTER_VERTICAL
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
            // 地图操作键：紧挨侧栏左侧放一个「回中」，全览已隐藏
            acts.gravity = Gravity.END or Gravity.BOTTOM
            acts.marginEnd = dp(DP_PANEL_LAND + 12)
            acts.marginStart = 0
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
            engine.stop()
            com.motohud.ble.service.NaviForegroundService.stop(this)
            app.naviForwarder.forwardExit()
            finishHistory()
        }
        app.naviForwarder.detach(TAG_FORWARD)
        runCatching { unregisterReceiver(pipReceiver) }
        betterRouteHandler.removeCallbacks(betterRouteTask)
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
        private const val TAG_FORWARD = "guidance"
        private const val ACTION_PIP_STOP = "com.motohud.ble.action.PIP_STOP"

        /** 路口前自动放大的触发距离 */
        private const val AUTO_ZOOM_RANGE_M = 200

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
