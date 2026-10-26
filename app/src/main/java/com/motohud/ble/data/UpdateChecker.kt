package com.motohud.ble.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新检查（对接 GitHub Releases）。
 *
 * 只读公开接口，不需要 token。返回的 APK 下载地址来自 release assets，
 * 与仓库发布产物一一对应。
 */
object UpdateChecker {

    // 0.0.x 核心双功能版走独立仓库发布；
    // 旧仓库 motohud-bridge 是 2.2.x 全功能线的存档，版本体系完全不同，
    // 混用会让更新检查拿到旧大版本的 release 提示"降级"。
    private const val API = "https://api.github.com/repos/mmaotv/motohud-core/releases/latest"
    const val REPO_URL = "https://github.com/mmaotv/motohud-core"

    data class ReleaseInfo(
        /** 形如 v2.2.0 */
        val tag: String,
        val title: String,
        val changelog: String,
        val apkUrl: String?,
        val apkSize: Long,
        val pageUrl: String
    ) {
        /** 去掉前缀 v，便于比较 */
        val version: String get() = tag.removePrefix("v").removePrefix("V").trim()
    }

    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MotoHud-App")
            }
            try {
                if (conn.responseCode != 200) return@runCatching null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val tag = json.optString("tag_name").ifBlank { json.optString("name") }
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url")
                            apkSize = a.optLong("size")
                            break
                        }
                    }
                }
                ReleaseInfo(
                    tag = tag,
                    title = json.optString("name").ifBlank { tag },
                    changelog = json.optString("body"),
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                    pageUrl = json.optString("html_url", REPO_URL)
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** latest 是否比 current 新（按数字段比较，1.10 > 1.9） */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+')
            .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
}
