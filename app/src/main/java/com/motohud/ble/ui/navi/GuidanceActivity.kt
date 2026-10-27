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
import com.amap.api.maps.model.PolylineOptions
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.amap.AmapRepo
import com.motohud.ble.amap.RoutePlanResult
import com.motohud.ble.amap.accuracyLevel
import com.motohud.ble.amap.formatAccuracy
import com.motohud.ble.amap.locationTypeLabel
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.data.NaviTrack
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.navi.IconType
import com.motohud.ble.navi.NaviEngine
import com.motohud.ble.navi.RouteData
import com.motohud.ble.navi.RouteHandoff
import com.motohud.ble.navi.RouteNaviEngine
import com.motohud.ble.navi.SpeechPriority
import com.motohud.ble.navi.VoicePack
import com.motohud.ble.navi.VoicePackPlayer
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
     * 现在默认实现是内置语音包（[com.motohud.ble.navi.ClipSpeaker]），
     * 离线合成引擎就绪后由 [com.motohud.ble.navi.SpeechRouter] 自动接管。
     */
    private var voice: com.motohud.ble.navi.Speaker? = null
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

    /** 转向提前预告防重放：bit0=500m 档已播、bit1=200m 档已播 */
    private var remindStepIndex = -1
    private var remindFlags = 0

    /** 上一次已播报过的路段序号：进入新路段时播一次「前方 X 米 左/右转」 */
    private var lastAnnouncedStep = -1

    /**
     * 预合成去重：记录已经交给引擎预热的「路段序号 + 文本」。
     * 只认文本不认序号，是因为重新算路后序号会重排，同一序号可能换了路名。
     */
    private var prewarmedStep = -1
    private var prewarmedText = ""

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
        bindViews()
        applyWindowInsets()
        // 旋转不重建 Activity，布局形态（横屏右下面板 / 竖屏底部通栏）由这里统一施加
        applyOrientationLayout(
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        destName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "目的地" }
        destLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        destLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)

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
        btnPip.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.VISIBLE else View.GONE

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
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + baseTop)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomInfo) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // right 也吃 inset：横屏时它是右下角定宽面板，要避开侧边手势条/挖孔
            v.updatePadding(bottom = bars.bottom + baseBottom, right = bars.right + baseEnd)
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
            say("", if (on) VoicePack.voiceOn() else VoicePack.voiceOff(), SpeechPriority.INFO)
        }
        // 长按选播报档位（详细/简洁/只播转弯）：单击是最高频的开/关，不该被设置项挤掉
        btnVoice.setOnLongClickListener {
            showVoiceModeDialog()
            true
        }
        tvModeBadge.setOnClickListener { showRoutePrefsDialog() }
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
                say("", VoicePack.turn(IconType.RIGHT, 300))
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
        remindFlags = 0
        lastAnnouncedStep = -1
        naviStartMs = System.currentTimeMillis()
        // 开始播报：骑行中不看屏幕也能确认导航已启动、大概多远
        if (NaviPrefs.voiceMode.value != NaviPrefs.VOICE_MODE_TURN_ONLY) {
            say("", VoicePack.start(r?.distanceM ?: 0f), SpeechPriority.INFO)
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
            CoordConverter.wgs84ToGCJ(best.latitude, best.longitude)
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
        engine.start(destName, destLat, destLng, start, r)
    }

    /** 从指定起点重新规划并应用（联网） */
    private fun planAndReplace(start: Pair<Double, Double>) {
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
                        .planDriveRoute(start.first, start.second, destLat, destLng, NaviPrefs.strategy(), NaviPrefs.summary())
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
        hideBetterRouteCard()
        planAndReplace(lat to lng)
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
                    mapType = AMap.MAP_TYPE_NIGHT
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
                    // 首次布局偶尔只出灰底不出瓦片：底图加载完成后重设一次类型强制刷新
                    setOnMapLoadedListener {
                        mapView?.postDelayed({
                            runCatching { mapType = AMap.MAP_TYPE_NIGHT }
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
                PolylineOptions()
                    .addAll(points)
                    .width(18f)
                    .color(ContextCompat.getColor(this, R.color.track_ahead))
                    .setDottedLine(false)
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
        // 路口前（<200m 且要转弯）自动放大到 18.2，过路口回 17.2 —— 免去手动缩放
        val zoom = if (zoomBoost) 18.2f else 17.2f
        try {
            val target = LatLng(s.latitude, s.longitude)
            if (s.bearing.isFinite() && s.speedKmh > 8f) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        com.amap.api.maps.model.CameraPosition.builder()
                            .target(target)
                            .zoom(zoom)
                            .bearing(s.bearing)
                            .tilt(45f)
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
            }
        }
        lifecycleScope.launch {
            NaviPrefs.castEnabled.collect {
                tvBleHint.visibility =
                    if (ble.state.value == BleConnectionManager.State.CONNECTED && it) View.GONE
                    else View.VISIBLE
                tvBleHint.text = if (it) getString(R.string.guidance_ble_hint)
                else getString(R.string.guidance_cast_off_hint)
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

        // 信号弱：只在导航中真的弱时提示，且只弹一次
        val weak = s.running && (!s.gpsActive || accuracyLevel(s.accuracy) == 1)
        tvSignalWarn.visibility = if (weak && !inPip) View.VISIBLE else View.GONE
        if (weak && !weakGpsToastShown && s.accuracy > 40f) {
            weakGpsToastShown = true
            Toast.makeText(
                this,
                getString(R.string.navi_gps_weak_message, s.accuracy.roundToInt()),
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

        // 进入新路段 → 播一次（老实现靠引擎抛整句引导语，语音包念不了路名，改由这里触发）
        if (s.stepIndex != lastAnnouncedStep) {
            lastAnnouncedStep = s.stepIndex
            remindStepIndex = s.stepIndex
            remindFlags = 0
            val spoke = announceStepEntered(s)
            // 刚进入路段那句已经带了距离，同一档位不要再复读一遍（否则两句话会互相打断）
            if (spoke) {
                when {
                    s.stepRemain <= 250 -> remindFlags = remindFlags or 2
                    s.stepRemain <= 500 -> remindFlags = remindFlags or 1
                }
            }
        }
        // 临近路口的短提醒（长路段场景下，进入路段时那句早被忘了）
        announceTurnReminders(s)
        // 趁还在当前路段，把下一段的引导语先合成好（进入下一段直接命中缓存）
        prewarmStep(s.stepIndex + 1)

        if (s.arrived) {
            if (!stopped) {
                stopped = true
                app.naviForwarder.forwardFinish()
                say("", VoicePack.arrived(), SpeechPriority.INFO)
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
     * 转向提醒（原来叫"提前预告"）。
     *
     * 改名是因为播报源变了：以前播的是高德给的整句引导语（含路名），
     * 现在播的是语音包片段「前方 + 300米 + 右转」，所以这里只需要
     * **图标类型**和**距路口的距离**，不再依赖文本。
     *
     * 三档详细度：
     *  - 详细：500m 档（仅 >=800m 的长路段）+ 200m 档
     *  - 简洁：只留 200m 档
     *  - 只播转弯：只留 200m 档（与简洁的差别在于进入路段时不播）
     */
    private fun announceTurnReminders(s: NaviEngine.NaviState) {
        if (!s.running || s.arrived || s.stepIndex < 0) return
        if (!TurnIcons.isTurn(s.iconType)) return
        val mode = NaviPrefs.voiceMode.value
        val step = route?.steps?.getOrNull(s.stepIndex)
        if (mode == NaviPrefs.VOICE_MODE_DETAIL &&
            remindFlags and 1 == 0 &&
            s.stepRemain in 201..500 &&
            (step?.distanceM ?: 0f) >= 800f
        ) {
            remindFlags = remindFlags or 1
            // text 给离线合成用（含路名），clips 给语音包兜底
            say(step?.instruction.orEmpty(), VoicePack.turn(s.iconType, s.stepRemain))
        }
        if (remindFlags and 2 == 0 && s.stepRemain in 1..200) {
            remindFlags = remindFlags or 2
            say(step?.instruction.orEmpty(), VoicePack.turn(s.iconType, s.stepRemain))
        }
    }

    /**
     * 进入新路段时的播报。
     *
     * 以前由引擎的 onStepChanged 抛高德原文来播；语音包念不了路名，
     * 改成自己按「转向图标 + 本段剩余」拼一句话，所以改在渲染里按 stepIndex 变化触发。
     */
    private fun announceStepEntered(s: NaviEngine.NaviState): Boolean {
        if (!s.running || s.arrived) return false
        if (s.stepIndex < 0 || !TurnIcons.isTurn(s.iconType)) return false
        return when (NaviPrefs.voiceMode.value) {
            NaviPrefs.VOICE_MODE_DETAIL -> {
                // 不抢播：让「开始导航，全程 6 公里」先说完，别把话切成半句
                say(
                    currentInstruction(s.stepIndex),
                    VoicePack.turn(s.iconType, s.stepRemain),
                    interrupt = false
                )
                true
            }
            // 简洁：只在"马上要转"时才在进入路段那一刻多说一句，避免长路段上一句话反复播
            NaviPrefs.VOICE_MODE_BRIEF -> if (s.stepRemain <= 600f) {
                say(
                    currentInstruction(s.stepIndex),
                    VoicePack.turn(s.iconType, s.stepRemain),
                    interrupt = false
                )
                true
            } else false
            else -> false
        }
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
        findViewById<View>(R.id.btn_pip).visibility = vis
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

    // ---------- 语音播报（内置语音包） ----------
    //
    // 历史教训：这里原来是系统 TextToSpeech，两个致命问题都表现为"导航全程哑巴"：
    // ① initTts 回调异步，就绪前的播报被丢弃；② 引擎缺中文数据时 ttsReady 永远 false。
    // 更根本的是——车机上根本没有可用的 TTS 引擎，怎么修都是哑巴。
    // 现在换成构建期合成的语音包（VoicePack + VoicePackPlayer）：
    // 片段拼装、完全离线、不依赖系统任何组件。见 VoicePack 的类注释。

    private fun initVoice() {
        voice = com.motohud.ble.navi.SpeechRouter.create(this)
            .also { it.setMuted(!NaviPrefs.voice.value) }
    }

    /**
     * 播一句导航语音（主线程调用）。
     *
     * @param text 含路名的完整文本，只有支持合成的引擎（离线 TTS）会用它；
     *             语音包实现会忽略它、只播 [clips]。
     * @param clips 预录片段，任何引擎都有的兜底。
     * @param interrupt false = 接在后面播（进入路段的播报不该把「开始导航」切成半句）。
     */
    private fun say(
        text: String,
        clips: List<Int>,
        priority: com.motohud.ble.navi.SpeechPriority =
            com.motohud.ble.navi.SpeechPriority.TURN,
        interrupt: Boolean = true
    ) {
        if (!NaviPrefs.voice.value) return
        if (text.isBlank() && clips.isEmpty()) return
        android.util.Log.i("GuidanceVoice", "播报：${text.ifBlank { "（仅片段 ${clips.size} 个）" }}")
        voice?.speak(text, clips, priority, interrupt)
    }

    /** 当前路段的完整引导语（含路名），给离线合成用；取不到就返回空串 */
    private fun currentInstruction(stepIndex: Int): String =
        route?.steps?.getOrNull(stepIndex)?.instruction.orEmpty()

    /**
     * 预合成某一段的引导语（主线程调用，内部转后台）。
     *
     * 离线引擎单句合成要 0.5~1.5s，正好卡在「该播了」的那一刻才合成必然滞后。
     * 所以一边在当前段骑行，一边就把下一段先合成进缓存；进入下一段直接出声音。
     * 同一个（序号 + 文本）只提交一次，渲染循环每帧调用也无副作用。
     */
    private fun prewarmStep(stepIndex: Int) {
        if (stepIndex < 0) return
        if (!NaviPrefs.voice.value) return
        val text = currentInstruction(stepIndex)
        if (text.isBlank()) return
        if (stepIndex == prewarmedStep && text == prewarmedText) return
        prewarmedStep = stepIndex
        prewarmedText = text
        voice?.prewarm(text)
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
        // 设置面板里可能刚改过语音开关，回前台时同步按钮状态
        updateVoiceButton()
        // 导航可能是在通知栏 / 画中画里被"结束导航"停掉的：那边只停了引擎，
        // 本页的 stopped 不会跟着变，回到这里还会显示"导航中"，用户以为没停掉。
        if (!stopped && !engine.state.value.running && started) {
            stopNavi(sendFinish = false)
            finish()
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
     * 所以横屏把信息卡挪到右下角、顶部渐变收窄、地图操作键挪到左下——
     * 地图拿回高度方向的全部空间，这也是车载导航横屏的通行布局。
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
        val dm = resources.displayMetrics
        fun dp(v: Int): Int = (v * dm.density).toInt()

        // 顶部渐变：横屏收窄，否则盖掉大半张图
        topScrim.layoutParams = topScrim.layoutParams.apply {
            height = if (landscape) dp(128) else dp(220)
        }

        // 横屏屏高只有 ~360dp：转向横幅与信号提示收到右侧面板左侧，
        // 否则它们全宽铺开后会和右下角信息面板在空间上打架（任一长高就叠在一起）。
        val sideZone = dp(352)
        (turnBanner.layoutParams as ViewGroup.MarginLayoutParams).apply {
            rightMargin = if (landscape) sideZone else 0
            turnBanner.layoutParams = this
        }
        (tvSignalWarn.layoutParams as ViewGroup.MarginLayoutParams).apply {
            rightMargin = if (landscape) sideZone else 0
            tvSignalWarn.layoutParams = this
        }
        // 横屏太矮，红绿灯/过路费/路况估算不是骑行时需要的阅读项，折叠手柄隐藏，
        // 避免展开后面板长高吃掉大半个屏；竖屏照常。
        findViewById<View>(R.id.btn_expand).visibility = if (landscape) View.GONE else View.VISIBLE

        val card = bottomInfo.layoutParams as FrameLayout.LayoutParams
        val acts = mapActions.layoutParams as FrameLayout.LayoutParams
        if (landscape) {
            // 信息卡：右下角定宽面板，高度方向全让给地图
            card.gravity = Gravity.END or Gravity.BOTTOM
            card.width = dp(340)
            // 地图操作键：挪到左下角，避开右侧面板
            acts.gravity = Gravity.START or Gravity.BOTTOM
            acts.marginStart = dp(14)
            acts.marginEnd = 0
            acts.bottomMargin = dp(46)
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

        // 横屏空间窄，转向引导语收成单行，避免双行把横幅顶高与面板相撞
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
        private const val TAG_FORWARD = "guidance"
        private const val ACTION_PIP_STOP = "com.motohud.ble.action.PIP_STOP"

        /** 路口前自动放大的触发距离 */
        private const val AUTO_ZOOM_RANGE_M = 200

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
