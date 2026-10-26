package com.motohud.ble.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索历史（本地持久化）。
 *
 * ## 存什么
 *
 * 存的是**用户真正查过的地方**，不是输入的字符。
 * 所以只在点选搜索结果（进而发起路线规划）时写入，输入过程中不写 ——
 * 否则"打了一半的词"和"打错的词"会把历史塞满。
 *
 * 每条带经纬度，点历史可以**直接进路线页**，不用再发一次搜索请求
 * （顺带也省了一次联网）。
 *
 * ## 上限
 *
 * 存储留 20 条，界面展示最近 5 条。留多一点是因为删旧记录没必要，
 * 而展示多了会把搜索框下面的空间撑满，反而挡住联想结果。
 */
object SearchHistory {

    private const val PREFS = "search_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_STORE = 20

    /** 界面展示条数 */
    const val DISPLAY_LIMIT = 5

    data class Item(
        val name: String,
        val lat: Double,
        val lng: Double,
        val ts: Long
    )

    private var prefs: SharedPreferences? = null

    private fun sp(ctx: Context): SharedPreferences {
        val p = prefs ?: ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        return p
    }

    /** 最近在前 */
    fun list(ctx: Context): List<Item> {
        val raw = sp(ctx).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                if (name.isBlank()) return@mapNotNull null
                Item(
                    name = name,
                    lat = o.optDouble("lat"),
                    lng = o.optDouble("lng"),
                    ts = o.optLong("ts")
                )
            }.sortedByDescending { it.ts }
        }.getOrDefault(emptyList())
    }

    /** 界面展示用：最近 [DISPLAY_LIMIT] 条 */
    fun recent(ctx: Context): List<Item> = list(ctx).take(DISPLAY_LIMIT)

    /** 写入一条。同名（同地点）会顶到最前面，不重复占位 */
    fun add(ctx: Context, name: String, lat: Double, lng: Double) {
        if (name.isBlank()) return
        val merged = list(ctx).filterNot { it.name == name }.toMutableList()
        merged.add(0, Item(name, lat, lng, System.currentTimeMillis()))
        save(ctx, merged.take(MAX_STORE))
    }

    fun remove(ctx: Context, name: String) {
        save(ctx, list(ctx).filterNot { it.name == name })
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(KEY_ITEMS).apply()
    }

    private fun save(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().apply {
                    put("name", it.name)
                    put("lat", it.lat)
                    put("lng", it.lng)
                    put("ts", it.ts)
                }
            )
        }
        sp(ctx).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
