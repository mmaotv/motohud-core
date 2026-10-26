package com.motohud.ble.amap

/**
 * 高德 WebAPI 请求的**进程内结果缓存**。
 *
 * ## 为什么需要
 *
 * 移动流量主要花在这三处（由大到小）：
 *  1. **算路（RouteSearch）** —— 一次返回 3 条方案，每条几千个坐标点 + 步骤文本，
 *     单次几十 KB；来回进退路线选择页、或在同一地点反复点"导航"，会一模一样地重算；
 *  2. **POI 搜索（PoiSearch）** —— 回车搜索一次也是几十 KB，同一个词重搜毫无必要；
 *  3. **关键词联想（Inputtips）** —— 单次小，但输入过程中打得最勤，
 *     退格再输入、输入后清空再重输都会重复打。
 *
 * 这些都是**纯读且短时间内不变**的数据，缓存是性价比最高的省流量手段：
 * 命中就直接不产生请求，用户察觉不到差别。
 *
 * ## 边界
 *
 * - 只缓存**成功**结果，错误码不进缓存（否则一次网络抖动会被记住十分钟）；
 * - 有 TTL + LRU 上限，进程死了自然失效，不会无限膨胀；
 * - **起点做了粗略取整**：实时定位每次都会抖几十米，如果按原精度当 key，
 *   缓存永远不会命中。取到 ~100m 网格后，"站在原地反复算"能命中，
 *   而真正意义上的位置变化又会自然换 key。目的地保持原精度 —— POI 坐标是固定的。
 */
object AmapCache {

    private const val MAX_ENTRIES = 32

    /** 算路结果：10 分钟内同一段路复用（路况会变，太久不新鲜） */
    const val TTL_ROUTE_MS = 10 * 60_000L

    /** POI 搜索：30 分钟（地点数据是静态的） */
    const val TTL_POI_MS = 30 * 60_000L

    /** 联想词：30 分钟 */
    const val TTL_TIPS_MS = 30 * 60_000L

    private class Entry(val at: Long, val value: Any)

    // accessOrder = true → LinkedHashMap 自己就是 LRU
    private val map = LinkedHashMap<String, Entry>(MAX_ENTRIES + 1, 0.75f, true)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, ttlMs: Long): T? = synchronized(this) {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() - e.at > ttlMs) {
            map.remove(key)
            return null
        }
        e.value as? T
    }

    fun put(key: String, value: Any): Unit = synchronized(this) {
        while (map.size >= MAX_ENTRIES) {
            val oldest = map.entries.firstOrNull() ?: break
            map.remove(oldest.key)
        }
        map[key] = Entry(System.currentTimeMillis(), value)
    }

    fun clear(): Unit = synchronized(this) { map.clear() }

    // ---------- key 构造 ----------

    /** 坐标按 ~100m 网格取整，让"站着不动"能命中缓存 */
    private fun grid(v: Double): Long = Math.round(v * 1000.0)

    /** 目的地按 ~10m 取整：POI 坐标本身是固定的，不必再粗 */
    private fun precise(v: Double): Long = Math.round(v * 100_000.0)

    /** 算路缓存键 */
    fun routeKey(
        fromLat: Double, fromLng: Double,
        toLat: Double, toLng: Double,
        strategy: Int,
        limit: Int
    ): String = "route|${grid(fromLat)},${grid(fromLng)}|${precise(toLat)},${precise(toLng)}|$strategy|$limit"

    /** POI 搜索缓存键 */
    fun poiKey(
        keyword: String, city: String,
        lat: Double?, lng: Double?,
        pageNum: Int, pageSize: Int
    ): String {
        val loc = if (lat != null && lng != null) "${grid(lat)},${grid(lng)}" else "-"
        return "poi|${keyword.trim()}|$city|$loc|$pageNum|$pageSize"
    }

    /** 联想词缓存键 */
    fun tipsKey(keyword: String, city: String, lat: Double?, lng: Double?): String {
        val loc = if (lat != null && lng != null) "${grid(lat)},${grid(lng)}" else "-"
        return "tips|${keyword.trim()}|$city|$loc"
    }

    /**
     * 当前缓存占用量，供设置页显示"已缓存 N 条请求"。
     * 分母是上限，超过就会按 LRU 淘汰最久没用的一条。
     */
    fun stat(): Pair<Int, Int> = synchronized(this) { map.size to MAX_ENTRIES }
}
