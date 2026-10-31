package com.motohud.ble.navi

import android.content.Context
import android.location.LocationManager
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapCarInfo
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviPath
import com.amap.api.navi.model.AMapNaviRouteNotifyData
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.navi.enums.NaviType
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.data.NaviPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 高德**导航 SDK**（`AMapNavi` 实时导航引擎）驱动的导航引擎。
 *
 * ## 与自研引擎（[RouteNaviEngine]）的关系
 *
 * 对外契约完全不变（[NaviEngine.NaviState]），所以手机 UI、BLE 投屏、通知栏
 * 全都不需要认识 SDK。内部则彻底换掉：
 *
 * | 环节 | 自研引擎 | 本类 |
 * |---|---|---|
 * | 路线规划 | 搜索 SDK `RouteSearch` | 导航 SDK `calculateDriveRoute` |
 * | 位置 | 定位 SDK + 自己投影到折线 | SDK `onLocationChange`（已选路吸附） |
 * | 距离/转向 | 自己按累计里程算 | SDK `NaviInfo` |
 * | 偏航重算 | 自己判偏移 + 重新规划 | SDK `onReCalculateRouteForYaw` |
 * | 电子眼/限速 | **没有**（拿不到） | `updateCameraInfo` |
 * | 车道线/路口图 | **没有** | `showLaneInfo` / `showCross` |
 * | 服务区/交通事件 | **没有** | `onServiceAreaUpdate` / `onNaviRouteNotify` |
 *
 * ## 为什么不再自己算"下一转弯"
 *
 * 自研引擎最痛的历史 bug 就是"AMap 把直行引道和转弯拆成相邻两步"，
 * 拿当前段判转弯 → 1 公里/500 米预告全消失、过了路口才报。
 * SDK 的 `NaviInfo.iconType / curStepRetainDistance` **本身就是"即将执行的动作 + 距它多远"**，
 * 不存在这个错位 —— 播报层只要读这两个字段，问题从根上没有了。
 *
 * ## 降级
 *
 * 没配 Key / 未同意隐私 / SDK 初始化失败时，自动委托给 [RouteNaviEngine]，
 * 保证"车机投屏导航"这条主链路任何情况下都不失能。
 */
class AmapNaviEngine(
    private val context: Context
) : NaviEngine {

    private val app = context.applicationContext

    private val _state = MutableStateFlow(NaviEngine.NaviState())
    override val state: StateFlow<NaviEngine.NaviState> = _state.asStateFlow()

    override var onStepChanged: ((String) -> Unit)? = null

    override var onSdkSpeech: ((String) -> Unit)? = null

    /**
     * SDK 自己会重算（偏航/拥堵），这里不再需要上层重规划，
     * 但保留该回调用以兼容既有调用点（GuidanceActivity 会写入，本类不触发）。
     */
    override var onRerouteRequest: ((Double, Double) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** SDK 不可用时的兜底引擎（自研实现，保留不删） */
    private val fallback by lazy {
        RouteNaviEngine(
            context,
            app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        )
    }

    private var usingFallback = false

    private var navi: AMapNavi? = null

    // ---------- 导航上下文 ----------
    private var running = false
    private var destName = ""
    private var destLat = 0.0
    private var destLng = 0.0
    private var waypoints: List<Pair<Double, Double>> = emptyList()
    private var currentPath: AMapNaviPath? = null
    private var lastInfo: NaviInfo? = null
    private var arrived = false

    /** 已播报过的电子眼（key = 类型+坐标），避免同一点反复念 */
    private val spokenCameras = HashSet<String>()

    /** 等算路完成再 startNavi 的待办 */
    private var pendingFrom: Pair<Double, Double>? = null

    /**
     * 模拟导航车速，0 = 真机 GPS 导航。
     *
     * SDK 的 `NaviType.EMULATOR` 不读定位，按这个车速沿路线推演 —— 用来在室内
     * 把「转向图标 / 播报 / 投屏帧」整条链路走一遍，不必实车跑一趟。
     * 由 [startEmulator] 置位、[stop] 清零，[startNaviInternal] 消费。
     */
    private var emulatorSpeed = 0

    /** 上层传入的路线步骤（用于地图画线、弯道/道路要素；转向播报已改用 NaviInfo） */
    private var routeSteps: List<RouteStep> = emptyList()

    /** 当前路线上的限速点（SDK `AMapNaviPath.limitInfos`） */
    private var limitPoints: List<Triple<Double, Double, Int>> = emptyList()

    /**
     * 引导点列表（SDK `AMapNavi.getNaviGuideList()`）。
     *
     * `NaviInfo.curStep` 就是这个列表的下标，每项带 `iconType`（该引导点的动作）
     * 与 `length`（**到该引导点**的距离）。用它算「第几个路口」——
     * 不能用上层传进来的 `route.steps`：那是另一套索引空间（`path.steps`），
     * 下标对不上，会把直行路口念成左转。
     */
    private var guides: List<com.amap.api.navi.model.AMapNaviGuide> = emptyList()

    // ---------------------------------------------------------------- 公开 API

    override fun start(
        destName: String,
        destLat: Double,
        destLng: Double,
        startHint: Pair<Double, Double>?,
        route: RouteData?
    ) {
        // 先停机再启动：重进导航页/重新规划都走这里，不清上个会话会残留 running
        teardownSession()

        this.destName = destName
        this.destLat = destLat
        this.destLng = destLng
        arrived = false
        routeSteps = route?.steps.orEmpty()

        _state.update {
            it.copy(
                running = true,
                arrived = false,
                destName = destName,
                destLatitude = destLat,
                destLongitude = destLng,
                onRoute = route != null,
                sdkDriven = true,
                stepIndex = -1,
                maneuverIndex = -1,
                cross = null,
                lane = null,
                camera = null,
                rerouting = false
            )
        }

        val n = ensureNavi()
        if (n == null) {
            delegateToFallback(destName, destLat, destLng, startHint, route)
            return
        }

        runCatching {
            n.setUseInnerVoice(false)          // 用我们自己的 TTS（离线语音包 + 念路名）
            n.setMultipleRouteNaviMode(false)  // 单选一条，避免多方案同时推进（投屏会跳）
        }

        val from = startHint ?: lastKnownFromState() ?: NaviEngine.DEFAULT_START
        pendingFrom = from

        val routeId = route?.sdkRouteId ?: -1
        val reused = routeId >= 0 && runCatching { n.selectRouteId(routeId) }.getOrDefault(false)
        if (reused) {
            // 选路页已经用导航 SDK 算过这条线，直接开跑，不再算一遍
            pendingFrom = null
            refreshPath(n)
            startNaviInternal(n)
        } else {
            calcRoute(n, from)
        }
    }

    /**
     * 模拟导航：必须在 [start] 之前调用（GuidanceActivity 从 Intent 里读出来就先置位），
     * 这样 [start] 里不管走「复用选路结果」还是「重新算路」，最后都会用
     * `NaviType.EMULATOR` 启动。
     *
     * 若路线已经算好且还没开跑（`start()` 里算路是异步的），这里也能立刻补启动。
     */
    override fun startEmulator(speedKmh: Int) {
        emulatorSpeed = speedKmh.coerceIn(5, 120)
        val n = navi ?: return
        if (pendingFrom == null && currentPath != null) startNaviInternal(n)
    }

    override fun pause() {
        runCatching { navi?.pauseNavi() }
    }

    override fun resume() {
        runCatching { navi?.resumeNavi() }
    }

    override fun switchParallelRoad() {
        runCatching { navi?.switchParallelRoad() }
    }

    override fun overrideIcon(iconType: Int) {
        if (usingFallback) {
            fallback.overrideIcon(iconType)
            return
        }
        _state.update { it.copy(iconType = iconType) }
    }

    override fun setSpeed(kmh: Float) {
        if (usingFallback) {
            fallback.setSpeed(kmh)
            return
        }
        // SDK 导航下速度来自定位，这里只用于「还没定位到」时界面不显示 0
        if (!_state.value.gpsActive) {
            _state.update { it.copy(speedKmh = kmh.coerceIn(NaviEngine.MIN_SPEED_KMH, NaviEngine.MAX_SPEED_KMH)) }
        }
    }

    override fun stop() {
        teardownSession()
        // 模拟导航只对「这一次会话」有效，停了就退出模拟，别让下次真机导航也在推演
        emulatorSpeed = 0
        if (usingFallback) fallback.stop()
        _state.update {
            it.copy(
                running = false,
                rerouting = false,
                cross = null,
                lane = null,
                camera = null,
                serviceArea = null,
                incident = null,
                parallelRoad = 0
            )
        }
    }

    override fun release() {
        runCatching { navi?.removeAMapNaviListener(listener) }
        runCatching { navi?.stopNavi() }
        if (usingFallback) runCatching { fallback.release() }
        scope.cancel()
    }

    // ---------------------------------------------------------------- 内部：SDK 生命周期

    /**
     * 取得 AMapNavi 单例（首次调用时完成隐私合规 + Key + 引擎设置）。
     * 任一步失败返回 null，由调用方降级。
     */
    private fun ensureNavi(): AMapNavi? {
        navi?.let { return it }
        if (!AmapManager.initNavi(app)) {
            usingFallback = true
            return null
        }
        val n = runCatching { AMapNavi.getInstance(app) }.getOrNull()
        if (n == null) {
            usingFallback = true
            return null
        }
        n.addAMapNaviListener(listener)
        runCatching {
            // 导航设置：全部打开 —— 这些正是自研引擎做不到的部分
            n.naviSetting?.apply {
                setTrafficStatusUpdateEnabled(true)   // TMC 路况刷新
                setTrafficInfoUpdateEnabled(true)     // 交通事件
                setMonitorCameraEnabled(true)         // 电子眼播报（限速随之播报）
                setOpenNextRoadInfo(true)             // 下一段路名（banner 的"接下来进入"）
                SetFilterShowCrossMessageStatus(false) // 路口放大图不额外过滤
            }
            // 车型：摩托车导航（排量影响禁摩/限行判定与匝道引导）
            n.setCarInfo(
                AMapCarInfo().apply {
                    val cc = NaviPrefs.motorcycleCc()
                    if (cc > 0) motorcycleCC = cc
                    isRestriction = true   // 参与限行/禁摩判定
                }
            )
            n.setEmulatorNaviSpeed(60)
        }
        navi = n
        usingFallback = false
        return n
    }

    /** 算路（首次进导航，或上层给的路线不是导航 SDK 算出来的） */
    private fun calcRoute(n: AMapNavi, from: Pair<Double, Double>) {
        val strategy = NaviPrefs.naviSdkStrategy().takeIf { it != PathPlanningStrategy.NO_STRATEGY }
            ?: PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT
        val ok = runCatching {
            n.calculateDriveRoute(
                listOf(NaviLatLng(from.first, from.second)),
                listOf(NaviLatLng(destLat, destLng)),
                waypoints.map { NaviLatLng(it.first, it.second) },
                strategy
            )
        }.getOrDefault(false)

        if (!ok) {
            // 算路请求都发不出去（Key 无效/SDK 异常）→ 降级，别让用户卡在"正在规划"
            delegateToFallback(destName, destLat, destLng, from, null)
        }
    }

    private fun startNaviInternal(n: AMapNavi) {
        val speed = emulatorSpeed
        val type = if (speed > 0) NaviType.EMULATOR else NaviType.GPS
        if (speed > 0) runCatching { n.setEmulatorNaviSpeed(speed) }
        val ok = runCatching { n.startNavi(type) }.getOrDefault(false)
        if (!ok) {
            delegateToFallback(destName, destLat, destLng, pendingFrom, null)
        }
    }

    private fun teardownSession() {
        running = false
        pendingFrom = null
        currentPath = null
        guides = emptyList()
        lastInfo = null
        spokenCameras.clear()
        limitPoints = emptyList()
        runCatching { navi?.stopNavi() }
    }

    private fun lastKnownFromState(): Pair<Double, Double>? {
        val s = _state.value
        if (s.latitude == 0.0 && s.longitude == 0.0) return null
        return s.latitude to s.longitude
    }

    /**
     * 交给自研引擎兜底。
     *
     * 这条路只在「没有 Key / 没同意隐私 / SDK 起不来」时走 —— 车机投屏是主功能，
     * 不能因为地图服务不可用就整个失效。
     */
    private fun delegateToFallback(
        name: String,
        lat: Double,
        lng: Double,
        from: Pair<Double, Double>?,
        route: RouteData?
    ) {
        if (!usingFallback) {
            usingFallback = true
            // 把兜底引擎的状态转发到我们的 state，上层只认一个 flow
            scope.launch {
                fallback.state.collect { s -> if (usingFallback) _state.value = s.copy(sdkDriven = false) }
            }
        }
        _state.update { it.copy(sdkDriven = false) }
        fallback.start(name, lat, lng, from, route)
    }

    /** 算路完成 / 重算完成后刷新路线数据（限速点、步骤几何） */
    private fun refreshPath(n: AMapNavi) {
        val p = runCatching { n.naviPath }.getOrNull() ?: return
        currentPath = p
        guides = runCatching { n.naviGuideList.orEmpty() }.getOrDefault(emptyList())
        waypoints = runCatching {
            p.wayPoint.orEmpty().map { it.latitude to it.longitude }
        }.getOrDefault(emptyList())
        limitPoints = runCatching {
            p.limitInfos.orEmpty().map {
                Triple(it.latitude, it.longitude, it.type.toInt())
            }
        }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                onRoute = true,
                pathRemain = if (it.pathRemain > 0) it.pathRemain else p.allLength,
                etaSeconds = if (it.etaSeconds > 0) it.etaSeconds else p.allTime
            )
        }
    }

    // ---------------------------------------------------------------- 内部：状态映射

    /**
     * `NaviInfo` → 状态快照。
     *
     * 这里有个**关键语义**必须记牢：`NaviInfo.iconType` 与 `curStepRetainDistance`
     * 说的是「即将执行的那个动作」以及「距它多远」，不是「当前所在路段」。
     * 高德官方导航界面就是拿这两个字段渲染转向图标与倒计数的。
     */
    private fun applyNaviInfo(info: NaviInfo) {
        val icon = info.iconType
        val remain = info.curStepRetainDistance
        val pathRemain = info.pathRetainDistance
        val nextRoad = info.nextRoadName.orEmpty().trim()
        val curRoad = info.currentRoadName.orEmpty().trim()
        val step = info.curStep
        // SDK 不直接给"已行驶里程"，用 全程 - 剩余 反推（轨迹回放/坡度判定都靠它）
        val total = runCatching { currentPath?.allLength ?: 0 }.getOrDefault(0)
        val traveled = if (total > 0 && pathRemain >= 0) {
            (total - pathRemain).coerceAtLeast(0)
        } else {
            -1
        }

        _state.update { s ->
            s.copy(
                running = true,
                sdkDriven = true,
                iconType = if (icon > 0) icon else s.iconType,
                stepRemain = if (remain >= 0) remain else s.stepRemain,
                pathRemain = if (pathRemain >= 0) pathRemain else s.pathRemain,
                etaSeconds = if (info.pathRetainTime > 0) info.pathRetainTime else s.etaSeconds,
                currentRoadName = curRoad,
                nextRoadName = nextRoad,
                // banner 文字：动作 + 目标路名；SDK 不给整句引导语，自己拼
                stepInstruction = instructionOf(icon, nextRoad, curRoad),
                // 在两个索引上都放同一个值：UI/播报按 maneuverIndex 读，
                // 而它现在与「当前引导步」是同一个概念，不再有当前段/下一段的错位
                stepIndex = if (step >= 0) step else s.stepIndex,
                maneuverIndex = if (step >= 0) step else s.maneuverIndex,
                speedKmh = if (info.currentSpeed > 0) info.currentSpeed.toFloat() else s.speedKmh,
                maneuverOrdinal = ordinalAt(step),
                nextManeuverIcon = nextGuideIcon(step),
                nextManeuverRoad = nextGuideRoad(step),
                remainLights = info.routeRemainLightCount,
                traveled = if (traveled >= 0) traveled else s.traveled,
                rerouting = false
            )
        }
    }

    /**
     * 当前引导点是「一串连续路口」里的第几个。
     *
     * 判定规则：从当前引导点往前走，只要**相邻两个路口的间距 < 150 米**就算同一串
     * （商业街、老城区、辅路出口连成串的场景），同一个串里的序号就是答案。
     * 间距 ≥ 150 米说明中间够骑一段，用户不会把两个路口搞混，序号归 1。
     *
     * 依据：`AMapNaviGuide.length` 是"从上一个引导点到这个引导点"的距离，
     * 因此 `guides[i-1].length` 就是上一个路口到当前路口的间距。
     */
    private fun ordinalAt(step: Int): Int {
        val g = guides
        if (step <= 0 || step >= g.size) return 1
        var ord = 1
        var i = step - 1
        while (i >= 0 && ord < 9) {
            val gap = runCatching { g[i].length }.getOrDefault(Int.MAX_VALUE)
            if (gap >= JUNCTION_CLUSTER_M) break
            ord++
            i--
        }
        return ord
    }

    /**
     * 再下一个引导点的图标（banner 底下那行「然后…」）。
     *
     * 取 `guides[curStep + 1]`：`guides[i]` 描述的是**第 i 段末端的动作**，
     * 所以当前动作之后的下一个动作就在 +1 处 —— 与上层 `route.steps` 的索引无关。
     */
    private fun nextGuideIcon(step: Int): Int =
        runCatching { guides.getOrNull(step + 1)?.iconType ?: 0 }.getOrDefault(0)

    /** 再下一个引导点对应的路名；取不到返回空串 */
    private fun nextGuideRoad(step: Int): String =
        runCatching {
            guides.getOrNull(step + 1)?.name?.trim().orEmpty()
        }.getOrDefault("").takeIf { SpeechScript.isUsableRoad(it) }.orEmpty()

    /**
     * banner 上那行引导文字。
     * 只给「动作」，不带路名 —— 因为路名由 `tvTurnRoad` 单独显示（取 `nextRoadName`）。
     * 之前把"左转，进入望京路"整句塞进来，横屏一行放不下会被截成"左转，进…"，
     * 后面那半句在路名行里又说了一遍。
     */
    private fun instructionOf(icon: Int, nextRoad: String, curRoad: String): String = when {
        icon == IconType.ARRIVED_DESTINATION -> "到达目的地"
        icon == IconType.STRAIGHT ->
            if (curRoad.isNotBlank()) "沿 $curRoad 直行" else "直行"
        else -> SpeechScript.action(icon).ifBlank { if (nextRoad.isNotBlank()) "进入 $nextRoad" else "直行" }
    }

    private fun toCameraAlert(cam: AMapNaviCameraInfo, interval: Boolean, intervalRemain: Int = 0): CameraAlert {
        val type = runCatching { cam.cameraType }.getOrDefault(0)
        val dist = runCatching { cam.cameraDistance }.getOrDefault(0)
            .let { if (it > 0) it else runCatching { cam.distance }.getOrDefault(0) }
        // 区间测速的均速就是限速值；普通测速取 cameraSpeed
        val speed = runCatching {
            if (interval) cam.averageSpeed else cam.cameraSpeed
        }.getOrDefault(0).let { if (it > 0) it else runCatching { cam.reasonableSpeedInRemainDist }.getOrDefault(0) }
        return CameraAlert(type, dist, speed, interval, intervalRemain)
    }

    // ---------------------------------------------------------------- SDK 回调

    /**
     * 只覆盖需要的回调，其余由 [SimpleNaviListener] 给空实现。
     *
     * 注意：`SimpleNaviListener` 的继承链是
     * `MyNaviListener → AMapNaviListener`，所以可以直接 addAMapNaviListener。
     */
    private val listener = object : SimpleNaviListener() {

        override fun onInitNaviSuccess() {
            usingFallback = false
            _state.update { it.copy(sdkDriven = true) }
        }

        override fun onInitNaviFailure() {
            usingFallback = true
            _state.update { it.copy(sdkDriven = false) }
        }

        override fun onStartNavi(naviType: Int) {
            running = true
            _state.update { it.copy(running = true, sdkDriven = true, arrived = false) }
        }

        override fun onStopNavi() {
            running = false
            _state.update { it.copy(running = false) }
        }

        /**
         * 位置：SDK 已做过选路吸附，`roadBearing` 是道路方向（比 GPS 航向稳）。
         *
         * **速度不在这里取**：实测（模拟导航 `setEmulatorNaviSpeed(45)`，日志
         * `loc.speed=45.0 infoSpeed=45`）`AMapNaviLocation.speed` 与
         * `NaviInfo.currentSpeed` **同为 km/h**，早期按"米/秒"再乘 3.6 会把 45 显示成 162。
         * 统一由 [applyNaviInfo] 读官方车速，本回调不再写 speedKmh。
         */
        override fun onLocationChange(location: AMapNaviLocation?) {
            val loc = location ?: return
            val coord = loc.coord ?: return
            _state.update { s ->
                s.copy(
                    latitude = coord.latitude,
                    longitude = coord.longitude,
                    bearing = if (loc.roadBearing > 0.1f) loc.roadBearing else loc.bearing,
                    accuracy = loc.accuracy,
                    gpsActive = true,
                    locType = loc.locationType,
                    fromSatellite = loc.locationType == 1
                )
            }
        }

        override fun onNaviInfoUpdate(naviInfo: NaviInfo?) {
            naviInfo ?: return
            lastInfo = naviInfo
            applyNaviInfo(naviInfo)
        }

        /**
         * 官方播报文本。
         *
         * SDK 有两个重载：`(String)` 与 `(int, String)`（后者带播报类型）。
         * 只取无类型那个 —— 两个都转发会导致同一句念两遍。
         */
        override fun onGetNavigationText(text: String?) {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return
            // 转向类文本**不转发**：由 GuidanceActivity 的叙事层统一说 ——
            // 它能带「第 2 个路口」和「请走左侧车道」，并按档位严格去重；
            // 官方文本再念一遍，用户听到的就是同一声"左转"反复出现。
            // 非转向信息（事故、施工、注意避让…）仍然放行。
            if (TURN_TEXT.containsMatchIn(t)) return
            onSdkSpeech?.invoke(t)
        }

        override fun onGetNavigationText(type: Int, text: String?) {
            // 与上面的无类型重载重复，丢弃
        }

        override fun onCalculateRouteSuccess(routeIds: IntArray?) {
            handleRouteSuccess(routeIds?.firstOrNull())
        }

        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
            handleRouteSuccess(result?.routeid?.firstOrNull())
        }

        override fun onCalculateRouteFailure(errorCode: Int) {
            handleRouteFailure(errorCode)
        }

        override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
            handleRouteFailure(result?.errorCode ?: -1)
        }

        /** 电子眼：测速/闯红灯/违章/公交道/应急道… 高德把限速一并发下来 */
        override fun updateCameraInfo(cameras: Array<AMapNaviCameraInfo>?) {
            val list = cameras.orEmpty()
            if (list.isEmpty()) {
                _state.update { it.copy(camera = null) }
                return
            }
            val nearest = list
                .map { toCameraAlert(it, interval = false) }
                .filter { CameraKind.worthSpeaking(it.type) && it.distanceM > 0 }
                .minByOrNull { it.distanceM }
            _state.update {
                it.copy(
                    camera = nearest,
                    // 电子眼过去之后要把限速清掉，否则界面上会一直挂着上一个路段的限速
                    limitSpeedKmh = nearest?.speedKmh?.takeIf { s -> s > 0 } ?: 0
                )
            }
        }

        /** 区间测速：起点/终点 + 剩余里程 */
        override fun updateIntervalCameraInfo(
            start: AMapNaviCameraInfo?,
            end: AMapNaviCameraInfo?,
            remainDistance: Int
        ) {
            val alert = start?.let { toCameraAlert(it, interval = true, intervalRemain = remainDistance) }
                ?: end?.let { toCameraAlert(it, interval = true, intervalRemain = remainDistance) }
                ?: return
            _state.update { it.copy(camera = alert) }
        }

        /**
         * 车道线：能走哪几条、每条是什么转向。
         *
         * ## 编码（从 SDK 内部 `SinkGuideObserver.parseLaneInfoByte` 反推）
         *
         * - `laneBackgrounds[i]` = 第 i 条车道动作码，**数组以 15 结尾**，数到即停；
         * - `laneFronts[i]` = 15 表示该车道走不到下一段，其余表示可走；
         * - `laneInfos[i].laneTypeIdArray` 是 2 位十六进制：高 4 位动作、低 4 位可用性。
         *
         * 三者的关系是冗余的，优先用原始字节（最直接、不受 `laneCount` 为 0 的影响，
         * 因为 SDK 构造 `AMapLaneInfo` 时只 setLaneTypeId，`laneCount` 一直是 0）。
         */
        override fun showLaneInfo(laneInfos: Array<AMapLaneInfo>?, laneBackgrounds: ByteArray?, laneFronts: ByteArray?) {
            val guide = decodeLane(laneInfos, laneBackgrounds, laneFronts) ?: return
            _state.update { it.copy(lane = guide) }
        }

        override fun showLaneInfo(laneInfo: AMapLaneInfo?) {
            val one = laneInfo ?: return
            val guide = decodeLane(arrayOf(one), null, null) ?: return
            _state.update { it.copy(lane = guide) }
        }

        override fun hideLaneInfo() {
            _state.update { it.copy(lane = null) }
        }

        /** 路口放大图：SDK 直接给位图 */
        override fun showCross(cross: AMapNaviCross?) {
            val bitmap = runCatching { cross?.bitmap }.getOrNull() ?: return
            _state.update { it.copy(cross = CrossImage(bitmap)) }
        }

        override fun hideCross() {
            _state.update { it.copy(cross = null) }
        }

        /** 3D 模式的放大图：SDK 只给原始字节，要自己解码成位图 */
        override fun showModeCross(cross: com.amap.api.navi.model.AMapModelCross?) {
            val bytes = runCatching { cross?.picBuf1 }.getOrNull() ?: return
            if (bytes.isEmpty()) return
            val bitmap = runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull() ?: return
            _state.update { it.copy(cross = CrossImage(bitmap)) }
        }

        override fun hideModeCross() {
            _state.update { it.copy(cross = null) }
        }

        /** 服务区（高速上才有；提示"前方X公里服务区"） */
        override fun onServiceAreaUpdate(areas: Array<AMapServiceAreaInfo>?) {
            val near = areas.orEmpty()
                .mapNotNull { a ->
                    val name = runCatching { a.name }.getOrNull().orEmpty().trim()
                    if (name.isEmpty()) null
                    else ServiceAreaAlert(name, runCatching { a.remainDist }.getOrDefault(0))
                }
                .minByOrNull { if (it.distanceM > 0) it.distanceM else Int.MAX_VALUE }
            _state.update { it.copy(serviceArea = near) }
        }

        /** 偏航重算：SDK 自己算，我们只把状态标出来（界面可以提示"已重新规划"） */
        override fun onReCalculateRouteForYaw() {
            _state.update { it.copy(rerouting = true) }
        }

        override fun onReCalculateRouteForTrafficJam() {
            _state.update { it.copy(rerouting = true) }
        }

        override fun onArriveDestination() {
            arrived = true
            running = false
            _state.update {
                it.copy(
                    running = false,
                    arrived = true,
                    pathRemain = 0,
                    stepRemain = 0,
                    iconType = IconType.ARRIVED_DESTINATION,
                    stepInstruction = "到达目的地",
                    etaSeconds = 0,
                    cross = null,
                    lane = null,
                    camera = null,
                    serviceArea = null
                )
            }
        }

        override fun onEndEmulatorNavi() {
            running = false
            _state.update { it.copy(running = false) }
        }

        /** GPS 信号弱：界面提示 + 播报"卫星信号弱"由上层决定 */
        override fun onGpsSignalWeak(isWeak: Boolean) {
            _state.update { it.copy(gpsAccuracyStatus = if (isWeak) 0 else 1) }
        }

        override fun onGpsOpenStatus(enabled: Boolean) {
            _state.update { it.copy(gpsActive = enabled) }
        }

        /** 交通设施（收费站/服务区/隧道等），取最近一个有名字的做提示 */
        override fun OnUpdateTrafficFacility(facilities: Array<AMapNaviTrafficFacilityInfo>?) {
            // 单独回调的版本信息更细，数组版本只在需要时汇总
        }

        override fun OnUpdateTrafficFacility(info: AMapNaviTrafficFacilityInfo?) {
            val f = info ?: return
            val speed = runCatching { f.limitSpeed }.getOrDefault(0)
            if (speed > 0) _state.update { it.copy(limitSpeedKmh = speed) }
        }

        /** 路线通知：封路/管制/施工等，SDK 给出原因与距离 */
        override fun onNaviRouteNotify(data: AMapNaviRouteNotifyData?) {
            val d = data ?: return
            if (!runCatching { d.isSuccess }.getOrDefault(true)) return
            val text = runCatching { d.reason }.getOrNull().orEmpty().trim()
                .ifBlank { runCatching { d.subTitle }.getOrNull().orEmpty().trim() }
                .ifBlank { runCatching { d.roadName }.getOrNull().orEmpty().trim() }
            if (text.isEmpty()) return
            _state.update { it.copy(incident = RouteIncident(text, runCatching { d.notifyType }.getOrDefault(0))) }
        }

        /** 平行路（主辅路）：SDK 会提示可切换，这里记状态供 UI 显示入口 */
        override fun notifyParallelRoad(type: Int) {
            // type>0 表示有平行路可切；UI 层用「切主辅路」长按手势主动调用 switchParallelRoad
            _state.update { it.copy(parallelRoad = if (type > 0) type else 0) }
        }

        /**
         * TMC 路况刷新：把「前方剩余路段的拥堵里程」汇总出来。
         *
         * 自研引擎是从搜索 SDK 的 DriveStep.getTMCs() 逐段累加的，
         * 导航 SDK 这边路线自带 `trafficStatuses`（每个 link 一个状态 + 长度），更准。
         */
        override fun onTrafficStatusUpdate() {
            val p = currentPath ?: return
            val congested = runCatching {
                p.trafficStatuses.orEmpty()
                    .filter { it.status >= 3 }      // 3=拥堵 4=严重拥堵
                    .sumOf { it.length.toDouble() }
            }.getOrDefault(0.0).toFloat()
            _state.update { it.copy(congestionAheadM = congested) }
        }

        /** 提示音：进入路口/超速等的提示音。我们有自己的语音链路，不叠 SDK 音效 */
        override fun onPlayRing(type: Int) {
        }
    }

    /**
     * 车道线解码，编码规则见 [listener] 里 `showLaneInfo` 的注释。
     *
     * 返回 null 表示"这帧没有可用车道信息"，调用方保持原样（不要清空 ——
     * SDK 在过完路口后会自己发 `hideLaneInfo`，中途清空会让箭头闪一下）。
     */
    private fun decodeLane(
        infos: Array<AMapLaneInfo>?,
        backgrounds: ByteArray?,
        fronts: ByteArray?
    ): LaneGuide? {
        val bg = backgrounds
        // 车道数：以 15 为终止符，数到就停；个别版本不补结尾，退回数组长度
        var count = bg?.indexOfFirst { (it.toInt() and 0xFF) == LaneAction.RESERVED } ?: -1
        if (count < 0) count = bg?.size ?: infos?.size ?: 0
        if (count <= 0) return null

        val actions = IntArray(count)
        val usable = BooleanArray(count)
        for (i in 0 until count) {
            val fromBytes = bg?.getOrNull(i)?.toInt()?.and(0xFF)
            val fromHex = infos?.getOrNull(i)?.let { hexActionOf(it) }
            actions[i] = fromBytes ?: fromHex ?: LaneAction.AHEAD

            val fromFront = fronts?.getOrNull(i)?.toInt()?.and(0xFF)
            val fromInfo = infos?.getOrNull(i)?.let { runCatching { it.isRecommended }.getOrDefault(true) }
            usable[i] = when {
                fromFront != null -> fromFront != LaneAction.RESERVED
                fromInfo != null -> fromInfo
                else -> true
            }
        }
        return LaneGuide(count, actions, usable)
    }

    /** `AMapLaneInfo.getLaneTypeIdArray()` = 2 位十六进制，"高位=动作码" */
    private fun hexActionOf(info: AMapLaneInfo): Int? =
        runCatching { info.laneTypeIdArray?.firstOrNull()?.digitToIntOrNull(16) }.getOrNull()

    private fun handleRouteSuccess(firstRouteId: Int?) {
        val n = navi ?: return
        if (firstRouteId != null && firstRouteId >= 0 && pendingFrom != null) {
            // 首次算路：默认选第一条，然后开跑
            runCatching { n.selectRouteId(firstRouteId) }
        }
        refreshPath(n)
        val wasPending = pendingFrom != null
        pendingFrom = null
        if (wasPending && !running) {
            startNaviInternal(n)
        } else {
            _state.update { it.copy(rerouting = false) }
        }
    }

    private fun handleRouteFailure(errorCode: Int) {
        pendingFrom = null
        _state.update { it.copy(rerouting = false) }
        // 算路失败（Key 无效 / 网络不可用）→ 退回自研引擎，导航仍可用
        if (!running) {
            delegateToFallback(destName, destLat, destLng, null, null)
        }
    }

    // ---------------------------------------------------------------- 只读查询

    /** 当前路线在 SDK 里的 routeId（调试/日志用） */
    fun currentSdkRouteId(): Int = runCatching { currentPath?.pathid?.toInt() ?: -1 }.getOrDefault(-1)

    /** 最近一帧 SDK 转向信息（排障用） */
    fun lastNaviInfo(): NaviInfo? = lastInfo

    /** 当前路线步骤（地图画线/弯道检测用） */
    fun steps(): List<RouteStep> = routeSteps

    /** 当前路线的限速点（lat, lng, type） */
    fun limitInfos(): List<Triple<Double, Double, Int>> = limitPoints

    private companion object {
        /**
         * 相邻路口间距小于该值 → 视为「同一串连续路口」，播报里要带序号。
         *
         * 取 150 米：按 30km/h 算是 18 秒，够骑手把"刚才那个口子"和"下一个口子"
         * 分清楚；再远就是两件独立的事了，报"第 2 个路口"反而让人以为要连着拐。
         */
        const val JUNCTION_CLUSTER_M = 150

        /**
         * 官方播报文本里属于「转向指令」的那一类，形如：
         *  - `600米后 左转`
         *  - `前方300米右转，进入望京路`
         *  - `靠左` / `进入环岛`
         *
         * 这类话由我们自己的叙事层统一说（带路口序号、带车道指引、每档只说一次），
         * 官方文本若也转发一遍，同一件事会被念两遍 —— 用户听到的就是
         * "六七百米处反复播报左转"。
         */
        val TURN_TEXT = Regex(
            "(\\d+\\s*米后|前方\\s*\\d+|现在|立即)?\\s*" +
                "(左转|右转|直行|掉头|靠左|靠右|进入环岛|驶出环岛|进入[^，。]{1,12})"
        )
    }
}
