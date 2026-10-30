package com.motohud.ble.amap

import com.amap.api.maps.AMap
import com.motohud.ble.data.NaviPrefs
import java.util.concurrent.atomic.AtomicInteger

/**
 * 高德 V11 新增能力的统一落地点。
 *
 * 升级到 11.3.100 后 SDK 多了一批接口，但没有必要散落在各个页面里各写一遍 ——
 * 一是容易漏，二是以后换版本要满仓库改。这里按"用得上/用不上"筛过一遍：
 *
 * **采用**
 *  - [navigationBuildingLayer]：把 3D 建筑压到路线下方。横屏导航时建筑体块会
 *    盖住路线和转向标，这是骑行时最容易"看不清该走哪"的来源之一。
 *  - [watchTiles]：瓦片加载回调。地图转圈时能分清是网络断还是引擎卡，
 *    连续失败给一次提示，不反复弹。
 *  - [applyNaviMode]：`setUseForNavi` + `setRoadArrowEnable`，导航场景渲染。
 *
 * **不采用**（逐个看过，对两轮车导航没有实际收益）
 *  - `isOpenCollisionDetection`（标注碰撞检测）：曾在这里包过一层 [collisionSafe]，
 *    但全项目没有任何地方调它 —— 本项目的搜索结果只落"起点/终点"两个图钉，
 *    不存在互相压盖的标注。留着就是死代码，已删。
 *  - `addPoiFilter`：屏蔽底图 POI 属于产品取舍（屏蔽后用户也看不到周边加油站），
 *    不做默认开启
 *  - `setReducePoint`：折线抽稀，可能让弯道被拉直，显示与真实道路错位，需实测后定
 *  - 语言/logo/比例尺/视觉搜索/GLTF/粒子/热力图/公交：与场景无关
 *
 * **待做**（SDK 有能力，但要单独一轮）
 *  - **离线地图 `OfflineMapManager`**：以前这里写「与场景无关」，是判断错了 ——
 *    骑行进山/进隧道没有信号，地图会是一片空白，这正是最需要离线的场景。
 *    前置条件：高德控制台要给该 Key 勾选「离线地图」服务权限，然后做下载/更新/
 *    删除的管理界面（SDK 自带的 `OfflineMapActivity` 是浅色 UI，和本项目不搭）。
 *  - `getMapScreenShot`：骑行轨迹分享图（现在历史详情页只能看，导不出图）。
 */
object AmapMapConfig {

    /**
     * 按用户设置应用地图外观（每个地图页 onCreate 后调用一次）。
     *
     * 以前这几个值在各页面里写死：一律夜间配色、建筑全开、文字全开。
     * 骑手场景差异很大 —— 白天跑长途嫌深色看不清、夜里嫌亮；
     * 中低端机开 3D 建筑会掉帧；跑熟路的嫌 POI 字太密。
     */
    fun applyDisplay(aMap: AMap) {
        runCatching { aMap.mapType = mapType(aMap) }
        runCatching { aMap.showBuildings(NaviPrefs.map3DBuilding.value) }
        runCatching { aMap.showMapText(NaviPrefs.mapText.value) }
    }

    /** 当前设置下该用的 mapType（地图加载完成需要强制刷新时用） */
    fun mapType(): Int =
        if (resolveDark(null)) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL

    /**
     * 同上，但带地图实例 —— 自动档需要**地图当前中心**的经纬度算日出日落。
     *
     * 为什么不取 GPS 定位：地图中心就是用户眼前那块地，即便暂时没有定位
     * 也能算对；而定位在隧道/地库里本来就不可用，那正是最需要深色的时候。
     */
    fun mapType(aMap: AMap?): Int =
        if (resolveDark(aMap)) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL

    /**
     * 解析「现在是不是该用深色」。
     *
     * 自动档取地图中心的经纬度算日出日落；拿不到中心就退回纯时间（6:30~18:00 当白天），
     * 见 [DayNight]。任何异常都判为白天 —— 深色地图在白天的代价只是对比度差一点，
     * 而浅色地图在夜里会直接晃眼睛，两害相权。
     */
    fun resolveDark(aMap: AMap?): Boolean = runCatching {
        when (NaviPrefs.mapTheme.value) {
            NaviPrefs.THEME_DAY -> false
            NaviPrefs.THEME_NIGHT -> true
            else -> {
                val target = runCatching { aMap?.cameraPosition?.target }.getOrNull()
                if (target != null && target.latitude != 0.0) {
                    DayNight.isNight(target.latitude, target.longitude)
                } else {
                    DayNight.isNight(DEFAULT_LAT, DEFAULT_LNG)
                }
            }
        }
    }.getOrDefault(false)

    /** 没有地图中心时的兜底坐标：国内中部，日出日落时刻取到中位数 */
    private const val DEFAULT_LAT = 30.6
    private const val DEFAULT_LNG = 104.1

    /**
     * 导航页专用渲染开关。
     *
     * 以前这里注释写「setUseForNavi 行为未公开，可能改交互，风险大于收益」——
     * 实际担心的是它可能禁掉旋转/倾斜手势，而导航页**本来就**把这两个手势关了
     * （见 GuidanceActivity 的 uiSettings），所以那个风险并不存在。
     *
     * 开着的作用：底图按导航场景渲染 —— 弱化与驾驶无关的 POI、突出道路走向与
     * 路名层级，正是骑行时要看的东西。
     *
     * 配套开 `setRoadArrowEnable`：道路上画出通行方向箭头，单行道/匝道方向
     * 一眼可辨，比在路口看标牌反应快得多。
     *
     * 两个都是可选接口（不同机型/渲染后端可能直接 no-op），失败静默。
     */
    fun applyNaviMode(aMap: AMap) {
        runCatching { aMap.setUseForNavi(true) }
        runCatching { aMap.setRoadArrowEnable(true) }
    }

    /**
     * 3D 建筑渲染层级。
     *
     * 默认建筑参与深度测试，横屏低俯角时会把路线压在下面。给一个较小的 zIndex
     * 让它沉到覆盖物之下；取 -1 而不是 0，避免和未设置的覆盖物同层。
     */
    fun navigationBuildingLayer(aMap: AMap) {
        runCatching { aMap.setMapBuildingZIndex(-1) }
    }

    // ---------- 瓦片加载监控 ----------

    private val tileFailStreak = AtomicInteger(0)
    private val tileOkCount = AtomicInteger(0)
    private val tileFailCount = AtomicInteger(0)

    /** 最近一次瓦片请求耗时（毫秒），-1 表示还没有结果 */
    @Volatile
    var lastTileCostMs: Long = -1
        private set

    /** 连续失败达到该次数才认为"网络真的不行了"，单次失败太常见（快速拖动、切层） */
    private const val FAIL_HINT_AT = 8

    /**
     * 挂上瓦片加载回调。
     *
     * @param onPersistentFailure 连续 [FAIL_HINT_AT] 次加载失败时回调一次，
     *        之后必须等到有成功加载才会再次触发（避免一路上反复弹）。
     */
    fun watchTiles(aMap: AMap, onPersistentFailure: (() -> Unit)? = null) {
        runCatching {
            aMap.setTileLoadCallback { success, costMs, _ ->
                lastTileCostMs = costMs
                if (success) {
                    tileOkCount.incrementAndGet()
                    tileFailStreak.set(0)
                } else {
                    tileFailCount.incrementAndGet()
                    if (tileFailStreak.incrementAndGet() == FAIL_HINT_AT) {
                        onPersistentFailure?.invoke()
                    }
                }
            }
        }
    }

    /** 诊断用的瓦片统计：成功/失败次数与最近耗时 */
    fun tileStats(): String =
        "瓦片 成功=${tileOkCount.get()} 失败=${tileFailCount.get()} 最近耗时=${lastTileCostMs}ms"
}
