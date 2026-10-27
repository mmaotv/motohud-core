package com.motohud.ble.data

import android.content.Context
import android.content.SharedPreferences
import com.amap.api.services.route.RouteSearch
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

    private const val PREFS = "navi_prefs"
    private const val KEY_MOTORCYCLE = "motorcycle_mode"
    private const val KEY_AVOID_HIGHWAY = "avoid_highway"
    private const val KEY_VOICE = "voice_on"
    private const val KEY_VOICE_MODE = "voice_mode"
    private const val KEY_PHONE_MODE = "phone_mode"   // 旧键：仅用于迁移读取
    private const val KEY_CAST = "cast_enabled"
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

    /** 离线语音音色（AISHELL-3 是多说话人模型，值就是 speakerId） */
    private const val KEY_TTS_SPEAKER = "tts_speaker"

    /** 离线语音语速倍率 */
    private const val KEY_TTS_SPEED = "tts_speed"

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
     * 离线语音音色：AISHELL-3 是 174 说话人模型，这里存 speakerId。
     *
     * 挑的是一组听感差别明显的编号（见 [TtsVoice.ALL]），
     * 用户挨个试听选一个，不用自己输数字。
     */
    private val _ttsSpeaker = MutableStateFlow(0)
    val ttsSpeaker: StateFlow<Int> = _ttsSpeaker.asStateFlow()

    /**
     * 离线语音语速倍率（0.7~1.4）。
     *
     * 8kHz 的合成音本身偏闷，语速稍快一点听感会更清楚 ——
     * 而且市区骑行时本来就希望播报短一些。
     */
    private val _ttsSpeed = MutableStateFlow(1.0f)
    val ttsSpeed: StateFlow<Float> = _ttsSpeed.asStateFlow()

    /**
     * 是否把导航信息投到车机（BLE 转发开关）。
     *
     * 语义变更说明：「投屏」以前是一个**页面**（横屏 HUD），现在只是一个**开关**——
     * 开了就每 tick 往车机发 NA/RE/NE（或 REM/NEXT/NAVI），手机这边可以息屏。
     * 旧键 `phone_mode` 会在 [init] 里读一次做迁移，老用户的偏好不会丢。
     */
    private val _castEnabled = MutableStateFlow(true)
    val castEnabled: StateFlow<Boolean> = _castEnabled.asStateFlow()

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
        _ttsSpeaker.value = prefs.getInt(KEY_TTS_SPEAKER, 0)
        _ttsSpeed.value = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
        // 「投屏」从页面改成开关：默认开（连了车机就是要投），忽略已废弃的 phone_mode
        _castEnabled.value = prefs.getBoolean(KEY_CAST, true)
        _screenOffNavi.value = prefs.getBoolean(KEY_SCREEN_OFF, true)
        _mapTraffic.value = prefs.getBoolean(KEY_MAP_TRAFFIC, false)
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

    fun setVoiceMode(mode: Int) {
        _voiceMode.value = mode
        prefs.edit().putInt(KEY_VOICE_MODE, mode).apply()
    }

    fun setTtsSpeaker(id: Int) {
        _ttsSpeaker.value = id
        prefs.edit().putInt(KEY_TTS_SPEAKER, id).apply()
    }

    fun setTtsSpeed(speed: Float) {
        _ttsSpeed.value = speed
        prefs.edit().putFloat(KEY_TTS_SPEED, speed).apply()
    }

    fun setCastEnabled(on: Boolean) {
        _castEnabled.value = on
        prefs.edit().putBoolean(KEY_CAST, on).apply()
    }

    fun setScreenOffNavi(on: Boolean) {
        _screenOffNavi.value = on
        prefs.edit().putBoolean(KEY_SCREEN_OFF, on).apply()
    }

    fun setMapTraffic(on: Boolean) {
        _mapTraffic.value = on
        prefs.edit().putBoolean(KEY_MAP_TRAFFIC, on).apply()
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
