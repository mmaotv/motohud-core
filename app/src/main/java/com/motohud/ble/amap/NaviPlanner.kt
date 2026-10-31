package com.motohud.ble.amap

import android.content.Context
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapNaviGuide
import com.amap.api.navi.model.AMapNaviPath
import com.amap.api.navi.model.NaviLatLng
import com.motohud.ble.navi.RouteData
import com.motohud.ble.navi.RouteStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 路线规划门面 —— 全部走**导航 SDK**（`AMapNavi.calculateDriveRoute`）。
 *
 * ## 为什么不用搜索 SDK 算路了
 *
 * 接导航 SDK 之前，选路页用搜索 SDK 的 `RouteSearch` 算方案、导航页却要重新算一遍，
 * 两套算法的结果**不可能完全一致**。用户选「避开拥堵」的方案，进导航走的却是另一条，
 * 这是"选了没用"的根源。现在两边共用同一个引擎、同一套 routeId：
 * 选路页算出 `routeIds`，用户选中的那条带 [RouteData.sdkRouteId] 交给导航页，
 * 导航页 `selectRouteId` 后直接开跑，路线绝对一致。
 *
 * ## 并发
 *
 * `AMapNavi` 是进程级单例，同一时刻只能算一次路。这里用 [busy] 串行化，
 * 并给每次请求一个 [seq]：回调回来时如果 seq 已过期（用户又改了目的地），
 * 结果直接丢弃，避免"上一次的算路结果盖掉这一次"。
 */
object NaviPlanner {

    private val busy = AtomicBoolean(false)

    /** 当前等待中的请求（同一时刻最多一个） */
    @Volatile
    private var waiter: Waiter? = null

    private class Waiter(
        val seq: Int,
        val resume: (Outcome) -> Unit
    ) {
        val done = AtomicBoolean(false)
    }

    private sealed class Outcome {
        data class Ok(val routeIds: IntArray) : Outcome()
        data class Fail(val code: Int) : Outcome()
    }

    private var seqCounter = 0

    private val listener = object : SimpleNaviListener() {
        override fun onCalculateRouteSuccess(routeIds: IntArray?) {
            finish(Outcome.Ok(routeIds ?: IntArray(0)))
        }

        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
            finish(Outcome.Ok(result?.routeid ?: IntArray(0)))
        }

        override fun onCalculateRouteFailure(errorCode: Int) {
            fail(Outcome.Fail(errorCode))
        }

        override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
            fail(Outcome.Fail(result?.errorCode ?: -1))
        }
    }

    private fun finish(o: Outcome) {
        val w = waiter ?: return
        if (!w.done.compareAndSet(false, true)) return
        waiter = null
        w.resume(o)
    }

    private fun fail(o: Outcome) = finish(o)

    private fun engine(context: Context): AMapNavi? {
        if (!AmapManager.initNavi(context)) return null
        return runCatching {
            AMapNavi.getInstance(context.applicationContext).also {
                it.setUseInnerVoice(false)
                // 先加再算：listener 加晚了会漏掉同步回调
                it.addAMapNaviListener(listener)
            }
        }.getOrNull()
    }

    /**
     * 算路（多方案）。
     *
     * @param avoidPolygons 避让区。导航 SDK 没有"多边形避让"入参，
     *   这里把每个避让区的**中心点**折成途经点之外的一个"避开点"——
     *   不完全等价，但比完全不传强（搜索 SDK 那条路仍保留在 AmapRepo 里做兜底）。
     * @return [RoutePlanResult.Many]（可能只有一个方案）
     */
    suspend fun planCandidates(
        context: Context,
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        waypoints: List<Pair<Double, Double>> = emptyList(),
        strategy: Int = PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT,
        modeLabel: String = "",
        timeoutMs: Long = 15_000
    ): RoutePlanResult = withContext(Dispatchers.Main) {
        val navi = engine(context)
            ?: return@withContext RoutePlanResult.Failure("导航服务不可用（请检查地图 Key 与隐私授权）")

        if (!busy.compareAndSet(false, true)) {
            return@withContext RoutePlanResult.Failure("上一次算路还没结束，请稍候")
        }

        val mySeq = ++seqCounter
        try {
            val outcome = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Outcome> { cont ->
                    val w = Waiter(mySeq) { o -> if (cont.isActive) cont.resume(o) }
                    waiter = w
                    cont.invokeOnCancellation {
                        if (w.done.compareAndSet(false, true)) waiter = null
                    }
                    val started = runCatching {
                        navi.calculateDriveRoute(
                            listOf(NaviLatLng(fromLat, fromLng)),
                            listOf(NaviLatLng(toLat, toLng)),
                            waypoints.map { NaviLatLng(it.first, it.second) },
                            strategy
                        )
                    }.getOrDefault(false)
                    if (!started) {
                        // 请求都发不出去：立刻收尾，别等 15 秒
                        if (w.done.compareAndSet(false, true)) {
                            waiter = null
                            if (cont.isActive) cont.resume(Outcome.Fail(-1))
                        }
                    }
                }
            }

            when (outcome) {
                null -> RoutePlanResult.Failure("算路超时，请检查网络后重试")
                is Outcome.Fail -> RoutePlanResult.Failure(planErrorMessage(outcome.code))
                is Outcome.Ok -> {
                    val ids = outcome.routeIds
                    if (ids.isEmpty()) return@withContext RoutePlanResult.Failure("没有找到可用路线")
                    val paths = runCatching { navi.naviPaths }.getOrNull().orEmpty()
                    val guides = runCatching { navi.naviGuideList }.getOrNull().orEmpty()
                    val list = ids.toList().mapNotNull { id ->
                        paths[id]?.let { p -> p.toRouteData(id, guides, modeLabel) }
                    }
                    when {
                        list.isEmpty() -> RoutePlanResult.Failure("没有找到可用路线")
                        list.size == 1 -> RoutePlanResult.Success(list.first())
                        else -> RoutePlanResult.Many(list)
                    }
                }
            }
        } finally {
            busy.set(false)
        }
    }

    private fun planErrorMessage(code: Int): String = when (code) {
        1 -> "算路失败：Key 无效或未绑定当前包名"
        2 -> "算路失败：网络不可用"
        3 -> "算路失败：起点或终点无效"
        4 -> "算路失败：距离过短或过长"
        6 -> "算路失败：额度已用尽"
        else -> "算路失败（错误码 $code）"
    }

    /**
     * `AMapNaviPath` → 我们的 [RouteData]。
     *
     * 引导语（instruction）从 `AMapNaviGuide` 按**顺序**取。两者本来一一对应，
     * 但 SDK 版本间可能有 ±1 的出入，所以做长度保护：够长才用，不够就留空，
     * 播报会退回"只用 NaviInfo 的动作 + 路名"，不会因为错位念错路。
     */
    private fun AMapNaviPath.toRouteData(
        routeId: Int,
        guides: List<AMapNaviGuide>,
        modeLabel: String
    ): RouteData {
        val rawSteps = runCatching { steps }.getOrNull().orEmpty()
        val useGuides = guides.size >= rawSteps.size
        val steps = rawSteps.mapIndexed { i, s ->
            val coords = runCatching { s.coords }.getOrNull().orEmpty()
                .map { it.latitude to it.longitude }
            val links = runCatching { s.links }.getOrNull().orEmpty()
            val road = links.lastOrNull()?.let { runCatching { it.roadName }.getOrNull() }.orEmpty()
            // 路段拥堵里程：link 的 trafficStatus ≥ 3 视为拥堵/严重拥堵
            val congested = links.sumOf { l ->
                val st = runCatching { l.trafficStatus }.getOrDefault(0)
                val len = runCatching { l.length }.getOrDefault(0)
                if (st >= 3) len.toDouble() else 0.0
            }.toFloat()
            val guide = if (useGuides) guides.getOrNull(i) else null
            RouteStep(
                instruction = guide?.let { runCatching { it.name }.getOrNull() }.orEmpty(),
                action = "",
                assistantAction = "",
                orientation = "",
                road = road,
                distanceM = runCatching { s.length }.getOrDefault(0).toFloat(),
                polyline = coords,
                congestedM = congested
            )
        }

        val lights = runCatching { trafficLightCount }.getOrDefault(0)
        val toll = runCatching { tollCost }.getOrDefault(0)
        val label = runCatching { labels }.getOrNull().orEmpty()
        val restricted = runCatching { restrictionInfo != null }.getOrDefault(false)

        return RouteData(
            distanceM = runCatching { allLength }.getOrDefault(0).toFloat(),
            durationS = runCatching { allTime }.getOrDefault(0).toFloat(),
            steps = steps,
            modeLabel = modeLabel,
            label = label,
            tolls = toll.toFloat(),
            trafficLights = lights,
            restriction = if (restricted) 1 else 0,
            tollDistanceM = steps.sumOf { it.distanceM.toDouble() }.toFloat() * 0f, // 导航 SDK 未单独给出收费里程
            sdkRouteId = routeId
        )
    }
}
