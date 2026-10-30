package com.motohud.ble.navi

import kotlin.math.abs

/**
 * 本地轨迹轻量纠偏（猎鹰的本地替代）。
 *
 * 背景：用户希望轨迹回放/分享「更贴路更平滑」。猎鹰能做到（云端路网绑路），
 * 但它要上传高德云端 + 额外 so + 离线失效，代价偏高。本类用纯几何手段
 * 在本地把轨迹「收拾干净」：
 *
 *  - **去重**：剔除与前一点几乎重合（<0.5m）的冗余点；
 *  - **平滑**：距离加权移动平均，抹掉 GPS 毛刺锯齿，让线更顺。
 *
 * 明确边界：**本地没有路网数据库，做不到猎鹰那种「吸附到真实道路」**。
 * 真正的贴路需要路网（在线抓路/猎鹰），或把当时的导航路线 polyline 存下来做投影。
 * 本类只负责让已有轨迹点更干净，不夸大成「贴路」。
 *
 * 接入点：回放 [com.motohud.ble.ui.navi.NaviHistoryDetailActivity.drawTrack] 与分享
 * （复用同一地图画面）在 decode 后调用 [smooth]；源头漂移剔除在
 * [com.motohud.ble.data.NaviTrack.finish] 里做。
 */
object TrackSmooth {

    /** 相邻点小于此距离（米）视为重复，剔除 */
    private const val DUP_M = 0.5

    /**
     * 几何平滑：去重 + 距离加权移动平均。
     *
     * @param window 单侧窗口半径（默认 2 → 中心 ±2 共 5 点参与）。窗口越大越顺，
     *   但同时越会抹平真实拐弯，所以保守取 2。
     * @return 平滑后的点序列（与输入同坐标系，GCJ-02）
     */
    fun smooth(
        points: List<Pair<Double, Double>>,
        window: Int = 2
    ): List<Pair<Double, Double>> {
        if (points.size <= 2) return points

        // 1) 去重：丢掉与前一点几乎重合的点（DP ε=5m 已过滤大部分，这里兜底）
        val dedup = ArrayList<Pair<Double, Double>>(points.size)
        for (p in points) {
            val last = dedup.lastOrNull()
            if (last == null ||
                Geo.distanceM(last.first, last.second, p.first, p.second) > DUP_M
            ) {
                dedup.add(p)
            }
        }
        if (dedup.size <= 2) return dedup

        // 2) 距离加权移动平均：离中心越近权重越高，尽量保留拐弯形态
        val out = ArrayList<Pair<Double, Double>>(dedup.size)
        for (i in dedup.indices) {
            val lo = (i - window).coerceAtLeast(0)
            val hi = (i + window).coerceAtMost(dedup.size - 1)
            var sLat = 0.0
            var sLng = 0.0
            var wsum = 0.0
            for (j in lo..hi) {
                val w = (window + 1 - abs(j - i).toDouble()).coerceAtLeast(1.0)
                sLat += dedup[j].first * w
                sLng += dedup[j].second * w
                wsum += w
            }
            out.add(sLat / wsum to sLng / wsum)
        }
        return out
    }
}
