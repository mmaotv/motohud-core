package com.motohud.ble.data

import android.content.Context
import android.content.SharedPreferences
import com.amap.api.services.route.RouteSearch
import com.motohud.ble.navi.tts.PresetVoice
import com.motohud.ble.navi.tts.TtsStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 导航偏好（跨会话持久化）。
 *
 * ## 关于「摩托车专用导航」与「路线偏好」
 *
 * 高德确实有摩托车路径规划，但两条路都走不通：
 *  1. 导航 SDK（AMapNavi）的 `PathPlanningStrategy.MOTOR_MULTIPLE_*` 需要单独申请
 *     商务授权，未授权时算路直接失败；
 *  2. 本项目用的免费搜索 SDK `RouteSearch` **没有任何摩托车策略**。
 *
 * 所以这里改成「路线偏好」来表达摩托车需求：禁摩城市主要限制快速路/高架/隧道，
 * 用「不走高速 + 躲避拥堵」的组合策略就能规避掉大部分硬禁区。用户也可以按所在
 * 城市自己勾选（少收费、高速优先、时间优先等），比单一的"摩托车模式"更实用。
 *
 * ## 多选如何落到单一 strategy
 *
 * 高德驾车算路的 strategy 是一个 int，不是位掩码，不能真的"同时勾三个"。
 * 但 SDK 提供了覆盖各种组合的 MULTI_CHOICE 常量（会一次返回多个方案），
 * 所以这里按"约束强弱"把多选合并成最接近的那个组合策略，见 [strategy]。
 * 优先级：不走高速 > 少收费 > 躲避拥堵 > 高速优先 > 时间优先 > 路况优先。
 */
object NaviPrefs {

    /** 播报详细度：详细 / 简洁 / 只播转弯 */
    const val VOICE_MODE_DETAIL = 0
    const val VOICE_MODE_BRIEF = 1
    const val VOICE_MODE_TURN_ONLY = 2

    /** 地图配色：自动（按日出日落）/ 日间 / 夜间 */
    const val THEME_AUTO = 0
    const val THEME_DAY = 1
    const val THEME_NIGHT = 2

    /** 导航页亮度：跟随系统 / 较暗 / 正常 */
    const val BRIGHT_FOLLOW = 0
    const val BRIGHT_DIM = 1
    const val BRIGHT_NORMAL = 2

    /** 播报音量档位（模型原始输出偏小，必须放大才听得清；默认 2.0×） */
    val VOLUME_STEPS = floatArrayOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
    const val DEFAULT_TTS_VOLUME = 2.0f

    private const val PREFS = "navi_prefs"
    private const val KEY_MOTORCYCLE = "motorcycle_mode"
    private const val KEY_AVOID_HIGHWAY = "avoid_highway"
    private const val KEY_VOICE = "voice_on"
    private const val KEY_VOICE_MODE = "voice_mode"
    private const val KEY_PHONE_MODE = "phone_mode"   // 旧键：仅用于迁移读取
    private const val KEY_CAST = "cast_enabled"
    private const val KEY_BRIDGE = "navi_bridge_enabled"
    private const val KEY_SCREEN_OFF = "screen_off_navi"
    private const val KEY_MAP_TRAFFIC = "map_traffic_layer"

    private const val KEY_SMART = "route_smart"
    private const val KEY_MAP_CAM_LAT = "map_cam_lat"
    private const val KEY_MAP_CAM_LNG = "map_cam_lng"
    private const val KEY_MAP_CAM_ZOOM = "map_cam_zoom"
    private const val KEY_LAST_DEST_NAME = "last_dest_name"
    private const val KEY_LAST_DEST_LAT = "last_dest_lat"
    private const val KEY_LAST_DEST_LNG = "last_dest_lng"
    private const val KEY_AVOID_JAM = "route_avoid_jam"
    private const val KEY_AVOID_TOLL = "route_avoid_toll"
    private const val KEY_HIGHWAY_FIRST = "route_highway_first"
    private const val KEY_TIME_FIRST = "route_time_first"
    private const val KEY_TRAFFIC_FIRST = "route_traffic_first"

    /**
     * 离线语音**风格**（"御姐音" / "调皮活泼" / "小女孩" …）的标识。
     *
     * 存的是风格 id 而不是裸 speakerId：[TtsStyle] 一个风格 = 说话人 + 语速 + 音调，
     * 单独存编号用户根本选不出来（"音色 3"是谁？）。
     */
    private const val KEY_TTS_STYLE = "tts_style"

    /** 离线语音语速倍率 */
    private const val KEY_TTS_SPEED = "tts_speed"

    /** 播报音量倍率（引擎原始输出偏小，需要放大才听得清） */
    private const val KEY_TTS_VOLUME = "tts_volume"

    /** 内置语音包说明是否弹过（只提示一次） */
    private const val KEY_PACK_HINT = "voice_pack_hint_shown"

    /**
     * 内置语音包音色。
     *
     * 与 [KEY_TTS_STYLE] 是两回事：那个是给离线神经 TTS 的"风格"（换说话人/语速/音调，
     * 不需要额外资源）；这个选的是**预录 mp3 套装**，换一个就换一整套录音。
     * 模型没装时只有这个有意义，装了模型时只有那个有意义。
     */
    private const val KEY_PRESET_VOICE = "preset_voice"

    // ---------- 屏幕与地图 ----------
    // 这几项以前都是写死在各个地图页里的（统一夜间、建筑全开、文字全开）。
    // 骑手场景差异很大：白天跑长途的嫌深色地图看不清，夜里跑的嫌亮；
    // 中低端机开 3D 建筑 + 全量文字会掉帧，跑熟路的又嫌字太密。
    //
    // 深色地图原本是个布尔开关（`map_dark`），现在改成三态 `map_theme`：
    // 手动开关的问题是**跑一趟从白天到天黑得中途去设置里点一下**，
    // 而骑手双手都在车把上。自动档按当地日出日落切换，见 [com.motohud.ble.amap.DayNight]。
    private const val KEY_MAP_THEME = "map_theme"
    private const val KEY_MAP_DARK_LEGACY = "map_dark"
    private const val KEY_MAP_3D_BUILDING = "map_3d_building"
    private const val KEY_MAP_TEXT = "map_text"
    private const val KEY_ROUTE_REDUCE = "route_reduce_point"

    /** 导航页屏幕常亮 / 亮度。以前是写死无条件常亮，夜骑没法调暗，兜里放着又没法关 */
    private const val KEY_SCREEN_KEEP_ON = "screen_keep_on"
    private const val KEY_SCREEN_BRIGHT = "screen_brightness"

    // ---------- 沿途提示 ----------
    // 四类提示分别开关。为什么要拆开而不是一个"沿途提示"总闸：
    //  - 隧道/桥梁/立交（道路要素）几乎人人要，尤其隧道开灯；
    //  - 弯道提示在山路是救命功能，在平原城市基本不触发；
    //  - 坡度提示依赖气压计，且只有山区才有意义；
    //  - 驾驶行为提示（急刹/急加速）默认关，见 DriveBehaviorMonitor 的说明。
    private const val KEY_CUE_ROAD_FEATURE = "cue_road_feature"
    private const val KEY_CUE_CURVE = "cue_curve"
    private const val KEY_CUE_TERRAIN = "cue_terrain"
    private const val KEY_CUE_HARSH = "cue_harsh_drive"

    // ---------- 算路细项 ----------
    /** 避开轮渡：对应 DriveRouteQuery.setUseFerry(false)，摩托车上轮渡多半是绕路 */
    private const val KEY_AVOID_FERRY = "route_avoid_ferry"
    /** 车型：0=普通汽车 1=纯电 2=插混。影响限行判定与"能耗/加油"类算路 */
    private const val KEY_CAR_TYPE = "route_car_type"

    private lateinit var prefs: SharedPreferences

    /** 骑行模式（保留：影响默认偏好与界面文案，不再单独做算路分支） */
    private val _motorcycle = MutableStateFlow(true)
    val motorcycle: StateFlow<Boolean> = _motorcycle.asStateFlow()

    private val _avoidHighway = MutableStateFlow(true)
    val avoidHighway: StateFlow<Boolean> = _avoidHighway.asStateFlow()

    private val _voice = MutableStateFlow(true)
    val voice: StateFlow<Boolean> = _voice.asStateFlow()

    /**
     * 播报详细度（[VOICE_MODE_DETAIL] / [VOICE_MODE_BRIEF] / [VOICE_MODE_TURN_ONLY]）。
     *
     * 语音包是片段拼装的，播报量完全可控，所以给三档：
     *  - 详细：开始导航 + 每次进入路段 + 500m 预告 + 200m 提醒 + 到达
     *  - 简洁：开始导航 + 进入路段时若已在 600m 内 + 200m 提醒 + 到达
     *  - 只播转弯：仅在 200m 内提醒转弯 + 到达（跑熟路的人只要这一档）
     */
    private val _voiceMode = MutableStateFlow(VOICE_MODE_DETAIL)
    val voiceMode: StateFlow<Int> = _voiceMode.asStateFlow()

    /**
     * 当前播报风格 id（见 [TtsStyle]）。
     *
     * 一个风格同时决定三件事：说话人、语速、音调。
     * 三者打包成"御姐音/调皮/小女孩"这种有名字的选项，用户听一遍就能记住，
     * 比让人在 174 个编号里盲选友好得多。
     */
    private val _ttsStyle = MutableStateFlow(TtsStyle.DEFAULT.id)
    val ttsStyle: StateFlow<String> = _ttsStyle.asStateFlow()

    /**
     * 内置语音包音色（见 [PresetVoice]）。
     *
     * 只在**没装离线模型**时生效：那时播的是预录 mp3，音色是录音时定死的，
     * 想换只能换一整套包。装模型后走 [SherpaSpeaker]，那个由 [ttsStyle] 决定。
     */
    private val _presetVoice = MutableStateFlow(PresetVoice.DEFAULT.id)
    val presetVoice: StateFlow<String> = _presetVoice.asStateFlow()

    /**
     * 离线语音语速倍率（0.7~1.4）。
     *
     * 8kHz 的合成音本身偏闷，语速稍快一点听感会更清楚 ——
     * 而且市区骑行时本来就希望播报短一些。
     */
    private val _ttsSpeed = MutableStateFlow(1.0f)
    val ttsSpeed: StateFlow<Float> = _ttsSpeed.asStateFlow()

    /**
     * 播报音量倍率。
     *
     * 离线模型原始输出振幅偏小（只有 ±0.3 左右），摩托车上风噪又大，
     * 不放大基本听不清。默认 2.0×，用户还能按自己的耳机/蓝牙再调。
     */
    private val _ttsVolume = MutableStateFlow(DEFAULT_TTS_VOLUME)
    val ttsVolume: StateFlow<Float> = _ttsVolume.asStateFlow()

    /**
     * 是否把导航信息投到车机（BLE 转发开关）。
     *
     * 语义变更说明：「投屏」以前是一个**页面**（横屏 HUD），现在只是一个**开关**——
     * 开了就每 tick 往车机发 NA/RE/NE（或 REM/NEXT/NAVI），手机这边可以息屏。
     * 旧键 `phone_mode` 会在 [init] 里读一次做迁移，老用户的偏好不会丢。
     */
    private val _castEnabled = MutableStateFlow(true)
    val castEnabled: StateFlow<Boolean> = _castEnabled.asStateFlow()

    /**
     * 第三方导航桥接（读取高德/百度导航状态并转发到车机）。
     *
     * **默认关闭**：这是新增能力，且需要额外的前台常驻服务，
     * 不能让老用户升级后莫名多一个常驻通知。只在设置里手动打开才生效。
     *
     * 注意它是「数据源」层面的开关，真正发不发还要过 [castEnabled] 这道总闸。
     */
    private val _bridgeEnabled = MutableStateFlow(false)
    val bridgeEnabled: StateFlow<Boolean> = _bridgeEnabled.asStateFlow()

    /** 息屏继续导航（关闭屏幕后仍保持定位、播报与车机转发） */
    private val _screenOffNavi = MutableStateFlow(true)
    val screenOffNavi: StateFlow<Boolean> = _screenOffNavi.asStateFlow()

    /**
     * 地图实时路况图层。
     *
     * **默认关闭**：路况瓦片大约每 30s 刷新一次，是地图页最大的流量来源之一
     * （一路骑下来几 MB 级别）。需要避堵时再开，配合「躲避拥堵」偏好已经够用。
     */
    private val _mapTraffic = MutableStateFlow(false)
    val mapTraffic: StateFlow<Boolean> = _mapTraffic.asStateFlow()

    // ---------- 屏幕与地图 ----------

    /**
     * 地图配色。见 [THEME_AUTO] / [THEME_DAY] / [THEME_NIGHT]。
     * 默认自动 —— 手动档等于要求骑手中途停车去点一下设置。
     */
    private val _mapTheme = MutableStateFlow(THEME_AUTO)
    val mapTheme: StateFlow<Int> = _mapTheme.asStateFlow()

    /** 导航页屏幕常亮。关掉后按系统超时熄屏，靠语音听路，长途省电 */
    private val _screenKeepOn = MutableStateFlow(true)
    val screenKeepOn: StateFlow<Boolean> = _screenKeepOn.asStateFlow()

    /**
     * 导航页屏幕亮度（只在导航页生效，退出即恢复系统亮度）。
     * 取值见 [BRIGHT_FOLLOW] / [BRIGHT_DIM] / [BRIGHT_NORMAL]。
     */
    private val _screenBright = MutableStateFlow(BRIGHT_FOLLOW)
    val screenBright: StateFlow<Int> = _screenBright.asStateFlow()

    /** 3D 建筑体块。关掉能明显减轻渲染负担（中低端机掉帧的主因之一） */
    private val _map3DBuilding = MutableStateFlow(true)
    val map3DBuilding: StateFlow<Boolean> = _map3DBuilding.asStateFlow()

    /** 底图文字（路名、POI 名称）。关掉地图只剩路网，转向时更干净 */
    private val _mapText = MutableStateFlow(true)
    val mapText: StateFlow<Boolean> = _mapText.asStateFlow()

    /** 路线折线抽稀。关掉按原始点绘制，弯道贴合最准但更吃性能 */
    private val _routeReduce = MutableStateFlow(true)
    val routeReduce: StateFlow<Boolean> = _routeReduce.asStateFlow()

    // ---------- 沿途提示 ----------

    /** 道路要素提示（隧道 / 桥梁 / 天桥 / 立交 / 匝道） */
    private val _cueRoadFeature = MutableStateFlow(true)
    val cueRoadFeature: StateFlow<Boolean> = _cueRoadFeature.asStateFlow()

    /** 弯道提示（急转弯 / 连续弯道） */
    private val _cueCurve = MutableStateFlow(true)
    val cueCurve: StateFlow<Boolean> = _cueCurve.asStateFlow()

    /** 坡度提示（长下坡 / 长上坡）。设备没有气压计时自动静默 */
    private val _cueTerrain = MutableStateFlow(true)
    val cueTerrain: StateFlow<Boolean> = _cueTerrain.asStateFlow()

    /** 驾驶行为提示（急刹 / 急加速 / 急转弯）。**默认关**，理由见 DriveBehaviorMonitor */
    private val _cueHarsh = MutableStateFlow(false)
    val cueHarsh: StateFlow<Boolean> = _cueHarsh.asStateFlow()

    // ---------- 算路细项 ----------

    /** 避开轮渡 */
    private val _avoidFerry = MutableStateFlow(false)
    val avoidFerry: StateFlow<Boolean> = _avoidFerry.asStateFlow()

    /** 车型（RouteSearch.DRIVING_NORMAL_CAR / DRIVING_PURE_ELECTRIC_VEHICLE / DRIVING_PLUGIN_HYBRID_CAR） */
    private val _carType = MutableStateFlow(RouteSearch.DRIVING_NORMAL_CAR)
    val carType: StateFlow<Int> = _carType.asStateFlow()

    // ---------- 路线偏好 ----------

    /** 高德智能推荐（与其他选项互斥：勾上它就忽略其余选项） */
    private val _smart = MutableStateFlow(true)
    val smart: StateFlow<Boolean> = _smart.asStateFlow()

    private val _avoidJam = MutableStateFlow(true)
    val avoidJam: StateFlow<Boolean> = _avoidJam.asStateFlow()

    private val _avoidToll = MutableStateFlow(false)
    val avoidToll: StateFlow<Boolean> = _avoidToll.asStateFlow()

    private val _highwayFirst = MutableStateFlow(false)
    val highwayFirst: StateFlow<Boolean> = _highwayFirst.asStateFlow()

    private val _timeFirst = MutableStateFlow(false)
    val timeFirst: StateFlow<Boolean> = _timeFirst.asStateFlow()

    private val _trafficFirst = MutableStateFlow(true)
    val trafficFirst: StateFlow<Boolean> = _trafficFirst.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _motorcycle.value = prefs.getBoolean(KEY_MOTORCYCLE, true)
        _avoidHighway.value = prefs.getBoolean(KEY_AVOID_HIGHWAY, true)
        _voice.value = prefs.getBoolean(KEY_VOICE, true)
        _voiceMode.value = prefs.getInt(KEY_VOICE_MODE, VOICE_MODE_DETAIL)
        _ttsStyle.value = prefs.getString(KEY_TTS_STYLE, TtsStyle.DEFAULT.id)
            ?: TtsStyle.DEFAULT.id
        _presetVoice.value = prefs.getString(KEY_PRESET_VOICE, PresetVoice.DEFAULT.id)
            ?: PresetVoice.DEFAULT.id
        _ttsSpeed.value = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
        _ttsVolume.value = prefs.getFloat(KEY_TTS_VOLUME, DEFAULT_TTS_VOLUME)
        // 「投屏」从页面改成开关：默认开（连了车机就是要投），忽略已废弃的 phone_mode
        _castEnabled.value = prefs.getBoolean(KEY_CAST, true)
        _bridgeEnabled.value = prefs.getBoolean(KEY_BRIDGE, false)
        _screenOffNavi.value = prefs.getBoolean(KEY_SCREEN_OFF, true)
        _mapTraffic.value = prefs.getBoolean(KEY_MAP_TRAFFIC, false)
        _mapTheme.value = readMapTheme(prefs)
        _map3DBuilding.value = prefs.getBoolean(KEY_MAP_3D_BUILDING, true)
        _mapText.value = prefs.getBoolean(KEY_MAP_TEXT, true)
        _routeReduce.value = prefs.getBoolean(KEY_ROUTE_REDUCE, true)
        _screenKeepOn.value = prefs.getBoolean(KEY_SCREEN_KEEP_ON, true)
        _screenBright.value = prefs.getInt(KEY_SCREEN_BRIGHT, BRIGHT_FOLLOW)
        _cueRoadFeature.value = prefs.getBoolean(KEY_CUE_ROAD_FEATURE, true)
        _cueCurve.value = prefs.getBoolean(KEY_CUE_CURVE, true)
        _cueTerrain.value = prefs.getBoolean(KEY_CUE_TERRAIN, true)
        _cueHarsh.value = prefs.getBoolean(KEY_CUE_HARSH, false)
        _avoidFerry.value = prefs.getBoolean(KEY_AVOID_FERRY, false)
        _carType.value = prefs.getInt(KEY_CAR_TYPE, RouteSearch.DRIVING_NORMAL_CAR)
        _smart.value = prefs.getBoolean(KEY_SMART, true)
        _avoidJam.value = prefs.getBoolean(KEY_AVOID_JAM, true)
        _avoidToll.value = prefs.getBoolean(KEY_AVOID_TOLL, false)
        _highwayFirst.value = prefs.getBoolean(KEY_HIGHWAY_FIRST, false)
        _timeFirst.value = prefs.getBoolean(KEY_TIME_FIRST, false)
        _trafficFirst.value = prefs.getBoolean(KEY_TRAFFIC_FIRST, true)
    }

    fun setMotorcycle(on: Boolean) {
        _motorcycle.value = on
        prefs.edit().putBoolean(KEY_MOTORCYCLE, on).apply()
    }

    fun setAvoidHighway(on: Boolean) {
        _avoidHighway.value = on
        prefs.edit().putBoolean(KEY_AVOID_HIGHWAY, on).apply()
    }

    fun setVoice(on: Boolean) {
        _voice.value = on
        prefs.edit().putBoolean(KEY_VOICE, on).apply()
    }

    /**
     * 内置语音包的说明是否已经提示过。
     *
     * 没装模型时导航会自动用内置语音包（不念路名）。这件事必须告诉用户一次，
     * 否则他会以为"路名怎么不见了"；但每次进导航都弹就是骚扰 —— 所以只弹一次。
     */
    fun packHintShown(): Boolean =
        ::prefs.isInitialized && prefs.getBoolean(KEY_PACK_HINT, false)

    fun markPackHintShown() {
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_PACK_HINT, true).apply()
    }

    fun setVoiceMode(mode: Int) {
        _voiceMode.value = mode
        prefs.edit().putInt(KEY_VOICE_MODE, mode).apply()
    }

    fun setTtsVolume(v: Float) {
        _ttsVolume.value = v
        prefs.edit().putFloat(KEY_TTS_VOLUME, v).apply()
    }

    fun setTtsStyle(id: String) {
        _ttsStyle.value = id
        prefs.edit().putString(KEY_TTS_STYLE, id).apply()
    }

    fun setPresetVoice(id: String) {
        _presetVoice.value = id
        prefs.edit().putString(KEY_PRESET_VOICE, id).apply()
    }

    fun setTtsSpeed(speed: Float) {
        _ttsSpeed.value = speed
        prefs.edit().putFloat(KEY_TTS_SPEED, speed).apply()
    }

    fun setCastEnabled(on: Boolean) {
        _castEnabled.value = on
        prefs.edit().putBoolean(KEY_CAST, on).apply()
    }

    fun setBridgeEnabled(on: Boolean) {
        _bridgeEnabled.value = on
        prefs.edit().putBoolean(KEY_BRIDGE, on).apply()
    }

    fun setScreenOffNavi(on: Boolean) {
        _screenOffNavi.value = on
        prefs.edit().putBoolean(KEY_SCREEN_OFF, on).apply()
    }

    fun setMapTraffic(on: Boolean) {
        _mapTraffic.value = on
        prefs.edit().putBoolean(KEY_MAP_TRAFFIC, on).apply()
    }

    fun setMapTheme(theme: Int) {
        val v = theme.coerceIn(THEME_AUTO, THEME_NIGHT)
        _mapTheme.value = v
        prefs.edit().putInt(KEY_MAP_THEME, v).apply()
    }

    /**
     * 迁移旧版布尔开关 `map_dark`。
     *
     * 旧版只有一个「深色地图」开关，默认 true。直接映射成夜间会让老用户
     * 永远用不上自动切换（他们要的其实正是「天黑自动变深」），所以：
     *   · 旧值 true（开着深色）→ 自动。白天变浅、夜里变深，比原来只有深色更好；
     *   · 旧值 false（特意关掉过）→ 日间。这是**主动选择**，必须尊重。
     */
    private fun readMapTheme(p: android.content.SharedPreferences): Int {
        if (p.contains(KEY_MAP_THEME)) {
            return p.getInt(KEY_MAP_THEME, THEME_AUTO).coerceIn(THEME_AUTO, THEME_NIGHT)
        }
        if (!p.contains(KEY_MAP_DARK_LEGACY)) return THEME_AUTO
        val legacyDark = p.getBoolean(KEY_MAP_DARK_LEGACY, true)
        val migrated = if (legacyDark) THEME_AUTO else THEME_DAY
        // 立刻落盘并清掉旧键：否则每次启动都重新迁移一遍，用户改了又被旧值盖回去
        p.edit().putInt(KEY_MAP_THEME, migrated).remove(KEY_MAP_DARK_LEGACY).apply()
        return migrated
    }

    fun setScreenKeepOn(on: Boolean) {
        _screenKeepOn.value = on
        prefs.edit().putBoolean(KEY_SCREEN_KEEP_ON, on).apply()
    }

    fun setScreenBright(level: Int) {
        val v = level.coerceIn(BRIGHT_FOLLOW, BRIGHT_NORMAL)
        _screenBright.value = v
        prefs.edit().putInt(KEY_SCREEN_BRIGHT, v).apply()
    }

    /**
     * 屏幕亮度档位 → `WindowManager.LayoutParams.screenBrightness` 的取值。
     * [BRIGHT_FOLLOW] 用 `BRIGHTNESS_OVERRIDE_NONE`（-1）表示交还系统。
     */
    fun screenBrightnessValue(): Float = when (_screenBright.value) {
        BRIGHT_DIM -> 0.40f
        BRIGHT_NORMAL -> 0.90f
        else -> android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }

    fun setMap3DBuilding(on: Boolean) {
        _map3DBuilding.value = on
        prefs.edit().putBoolean(KEY_MAP_3D_BUILDING, on).apply()
    }

    fun setMapText(on: Boolean) {
        _mapText.value = on
        prefs.edit().putBoolean(KEY_MAP_TEXT, on).apply()
    }

    fun setRouteReduce(on: Boolean) {
        _routeReduce.value = on
        prefs.edit().putBoolean(KEY_ROUTE_REDUCE, on).apply()
    }

    fun setCueRoadFeature(on: Boolean) {
        _cueRoadFeature.value = on
        prefs.edit().putBoolean(KEY_CUE_ROAD_FEATURE, on).apply()
    }

    fun setCueCurve(on: Boolean) {
        _cueCurve.value = on
        prefs.edit().putBoolean(KEY_CUE_CURVE, on).apply()
    }

    fun setCueTerrain(on: Boolean) {
        _cueTerrain.value = on
        prefs.edit().putBoolean(KEY_CUE_TERRAIN, on).apply()
    }

    fun setCueHarsh(on: Boolean) {
        _cueHarsh.value = on
        prefs.edit().putBoolean(KEY_CUE_HARSH, on).apply()
    }

    fun setAvoidFerry(on: Boolean) {
        _avoidFerry.value = on
        prefs.edit().putBoolean(KEY_AVOID_FERRY, on).apply()
    }

    fun setCarType(type: Int) {
        _carType.value = type
        prefs.edit().putInt(KEY_CAR_TYPE, type).apply()
    }

    fun setSmart(on: Boolean) {
        _smart.value = on
        prefs.edit().putBoolean(KEY_SMART, on).apply()
    }

    fun setAvoidJam(on: Boolean) {
        _avoidJam.value = on
        prefs.edit().putBoolean(KEY_AVOID_JAM, on).apply()
    }

    fun setAvoidToll(on: Boolean) {
        _avoidToll.value = on
        prefs.edit().putBoolean(KEY_AVOID_TOLL, on).apply()
    }

    fun setHighwayFirst(on: Boolean) {
        _highwayFirst.value = on
        prefs.edit().putBoolean(KEY_HIGHWAY_FIRST, on).apply()
    }

    fun setTimeFirst(on: Boolean) {
        _timeFirst.value = on
        prefs.edit().putBoolean(KEY_TIME_FIRST, on).apply()
    }

    fun setTrafficFirst(on: Boolean) {
        _trafficFirst.value = on
        prefs.edit().putBoolean(KEY_TRAFFIC_FIRST, on).apply()
    }

    /**
     * 当前偏好对应的高德驾车 strategy。
     *
     * MULTI_CHOICE 系列会一次返回多套方案，界面因此可以"先选路线再导航"。
     *
     * ## 修复：「智能推荐」不再吞掉「不走高速」
     *
     * 历史 bug：这里原本是 `if (_smart.value) return 最快最短路`，而 `_smart`
     * 默认 `true` —— 于是**默认状态下「不走高速」完全无效**，算出来就是含
     * 内环快速路的最快路线。对摩托车来说这不是"偏好"，是"能不能上路"的问题。
     *
     * 现在把「不走高速」视为**硬约束**：只要它开着，无论「智能推荐」是否勾选，
     * 都走 NO_HIGHWAY 系列策略（这些策略本身也是 MULTI_CHOICE，仍会返回
     * 2~3 套方案，不会损失"先选路线"的体验）。
     */
    fun strategy(): Int {
        val noHigh = _avoidHighway.value
        if (_smart.value && !noHigh) {
            return RouteSearch.DRIVING_MULTI_STRATEGY_FASTEST_SHORTEST_AVOID_CONGESTION
        }
        val noToll = _avoidToll.value
        val noJam = _avoidJam.value
        val highFirst = _highwayFirst.value
        val timeFirst = _timeFirst.value
        val trafficFirst = _trafficFirst.value

        return when {
            // 不走高速的各种组合（摩托车/禁摩城市的主要诉求）
            noHigh && noToll && noJam ->
                RouteSearch.DRIVING_MULTI_CHOICE_AVOID_CONGESTION_NO_HIGHWAY_SAVE_MONEY
            noHigh && noToll -> RouteSearch.DRIVING_MULTI_CHOICE_SAVE_MONEY_NO_HIGHWAY
            noHigh && noJam -> RouteSearch.DRIVING_MULTI_CHOICE_AVOID_CONGESTION_NO_HIGHWAY
            noHigh -> RouteSearch.DRIVING_MULTI_CHOICE_NO_HIGHWAY
            // 少收费
            noToll && noJam -> RouteSearch.DRIVING_MULTI_CHOICE_AVOID_CONGESTION_SAVE_MONEY
            noToll -> RouteSearch.DRIVING_MULTI_CHOICE_SAVE_MONEY
            // 躲避拥堵
            noJam && highFirst -> RouteSearch.DRIVING_MULTI_CHOICE_HIGHWAY_AVOID_CONGESTION
            noJam && (trafficFirst || timeFirst) -> RouteSearch.DRIVING_MULTI_CHOICE_AVOID_CONGESTION
            noJam -> RouteSearch.DRIVING_MULTI_CHOICE_AVOID_CONGESTION
            // 高速优先
            highFirst -> RouteSearch.DRIVING_MULTI_CHOICE_HIGHWAY
            // 时间 / 路况
            timeFirst -> RouteSearch.DRIVING_MULTI_STRATEGY_FASTEST_SHORTEST
            trafficFirst -> RouteSearch.DRIVING_MULTI_CHOICE_AVOID_CONGESTION
            else -> RouteSearch.DRIVING_MULTI_STRATEGY_FASTEST_SAVE_MONEY_SHORTEST
        }
    }

    /**
     * 算路时要下发的「排除道路类型」参数（高德 `DriveRouteQuery.setExclude`）。
     *
     * 和 [strategy] 里的 NO_HIGHWAY 是**双保险**：策略名里的 no_highway 是否
     * 真的覆盖「城市快速路」（如重庆内环快速路）取决于高德内部的路网分级，
     * 而 `DRIVING_EXCLUDE_MOTORWAY` 是显式按道路等级排除，更硬。
     * 两者叠加不会冲突，所以「不走高速」开着时一并下发。
     */
    fun excludeMotorway(): Boolean = _avoidHighway.value

    // ---------- 地图视野 / 上次目的地 ----------
    // 地图页每次进来都弹回当前位置很烦：用户常看的是固定一片区域（家附近、常跑的路线）。
    // 记住上次的中心与缩放，下次进来原地恢复；想看自己在哪，点一下定位按钮即可。
    // 上次导航的目的地也留一个图钉，复访时点一下就再去一次，比翻导航历史快。

    data class CameraMemo(val lat: Double, val lng: Double, val zoom: Float)

    data class DestMemo(val name: String, val lat: Double, val lng: Double)

    /** 没有记录（首次使用）时返回 null */
    fun mapCamera(): CameraMemo? {
        if (!::prefs.isInitialized) return null
        val lat = prefs.getFloat(KEY_MAP_CAM_LAT, Float.NaN)
        val lng = prefs.getFloat(KEY_MAP_CAM_LNG, Float.NaN)
        val zoom = prefs.getFloat(KEY_MAP_CAM_ZOOM, Float.NaN)
        if (lat.isNaN() || lng.isNaN() || zoom.isNaN()) return null
        return CameraMemo(lat.toDouble(), lng.toDouble(), zoom)
    }

    fun setMapCamera(lat: Double, lng: Double, zoom: Float) {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putFloat(KEY_MAP_CAM_LAT, lat.toFloat())
            .putFloat(KEY_MAP_CAM_LNG, lng.toFloat())
            .putFloat(KEY_MAP_CAM_ZOOM, zoom)
            .apply()
    }

    fun lastDest(): DestMemo? {
        if (!::prefs.isInitialized) return null
        val name = prefs.getString(KEY_LAST_DEST_NAME, null) ?: return null
        val lat = prefs.getFloat(KEY_LAST_DEST_LAT, Float.NaN)
        val lng = prefs.getFloat(KEY_LAST_DEST_LNG, Float.NaN)
        if (name.isBlank() || lat.isNaN() || lng.isNaN()) return null
        return DestMemo(name, lat.toDouble(), lng.toDouble())
    }

    fun setLastDest(name: String, lat: Double, lng: Double) {
        if (!::prefs.isInitialized) return
        if (name.isBlank() || lat == 0.0 || lng == 0.0) return
        prefs.edit()
            .putString(KEY_LAST_DEST_NAME, name)
            .putFloat(KEY_LAST_DEST_LAT, lat.toFloat())
            .putFloat(KEY_LAST_DEST_LNG, lng.toFloat())
            .apply()
    }

    /**
     * 当前偏好的简短摘要，用于界面徽章。
     *
     * 「不走高速」是硬约束，**即使「智能推荐」开着也要显示**——
     * 否则用户在界面上看不到它，会以为设置没生效（上一版就是这个观感）。
     */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (_smart.value) parts += "智能推荐"
        if (_avoidHighway.value) parts += "不走高速"
        if (!_smart.value) {
            if (_avoidToll.value) parts += "少收费"
            if (_avoidJam.value) parts += "避拥堵"
            if (_highwayFirst.value) parts += "高速优先"
            if (_timeFirst.value) parts += "时间优先"
            if (_trafficFirst.value && !_avoidJam.value) parts += "路况优先"
        }
        return if (parts.isEmpty()) "智能推荐" else parts.joinToString("·")
    }
}
