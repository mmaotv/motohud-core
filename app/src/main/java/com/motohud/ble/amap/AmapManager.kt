package com.motohud.ble.amap

import android.content.Context
import android.content.SharedPreferences
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.motohud.ble.BuildConfig

/**
 * 高德 SDK 统一管理器：API Key + 隐私合规。
 *
 * 设计要点（规避法律风险 + 灵活配置）：
 *  1. Key 来源优先级：应用内设置（用户粘贴）> 构建注入（local.properties / BuildConfig）；
 *  2. 高德 SDK 自 2022 起要求先完成隐私合规（updatePrivacyShow / updatePrivacyAgree），
 *     未同意前不初始化任何 SDK，本 App 在首次启动引导页中取得用户授权；
 *  3. 三个 SDK（地图 / 搜索 / 定位）均支持运行时 setApiKey，无需重启即可切换 Key；
 *  4. Key 绑定应用包名（com.motohud.ble）与签名，用户需在高德开放平台自行申请。
 */
object AmapManager {

    private const val PREFS = "amap_settings"
    private const val KEY_API = "api_key"
    private const val KEY_PRIVACY = "privacy_agreed"
    private const val KEY_PRIVACY_EVER_SHOWN = "privacy_ever_shown"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Key ----------

    /** 当前生效的 Key：应用内设置优先，其次构建注入；空串 = 未配置 */
    fun apiKey(context: Context): String {
        val local = prefs(context).getString(KEY_API, "").orEmpty().trim()
        if (local.isNotEmpty()) return local
        return BuildConfig.AMAP_API_KEY.trim()
    }

    fun hasKey(context: Context): Boolean = apiKey(context).isNotEmpty()

    /** 用户在应用内粘贴 Key（高德开放平台申请，绑定包名 com.motohud.ble） */
    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
        applyKey(context)
    }

    /** 清除应用内 Key，回退到构建注入值 */
    fun clearApiKey(context: Context) {
        prefs(context).edit().remove(KEY_API).apply()
        applyKey(context)
    }

    fun isKeyFromBuild(context: Context): Boolean =
        prefs(context).getString(KEY_API, "").orEmpty().isBlank() && BuildConfig.AMAP_API_KEY.isNotBlank()

    // ---------- 隐私合规 ----------

    fun isPrivacyAgreed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRIVACY, false)

    fun markPrivacyShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_PRIVACY_EVER_SHOWN, true).apply()
    }

    fun hasEverShownPrivacy(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRIVACY_EVER_SHOWN, false)

    /** 用户同意隐私政策（引导页调用），之后方可初始化 SDK */
    fun agreePrivacy(context: Context) {
        prefs(context).edit().putBoolean(KEY_PRIVACY, true).apply()
        applyPrivacy(context)
    }

    /** 撤回同意（设置页） */
    fun revokePrivacy(context: Context) {
        prefs(context).edit().putBoolean(KEY_PRIVACY, false).apply()
    }

    // ---------- SDK 初始化 ----------

    /**
     * 地图 SDK 初始化（创建 MapView 前调用）。
     * 未同意隐私或未配置 Key 时返回 false，调用方应降级展示。
     */
    fun initMap(context: Context): Boolean {
        if (!isPrivacyAgreed(context)) return false
        val key = apiKey(context)
        if (key.isEmpty()) return false
        runCatching {
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            MapsInitializer.setApiKey(key)
            // HTTPDNS：把瓦片/接口域名解析交给高德自己的 DNS 服务。
            // 骑在路上切基站、进隧道、连公共 Wi-Fi 时，运营商 DNS 经常解析慢或
            // 被劫持到广告页，表现为"地图一直转圈但网络正常"。V11 才有这个开关。
            MapsInitializer.setHttpDnsEnabled(true)
        }
        return true
    }

    /** 搜索 SDK 初始化（POI 提示 / 路线规划前调用） */
    fun initSearch(context: Context): Boolean {
        if (!isPrivacyAgreed(context)) return false
        val key = apiKey(context)
        if (key.isEmpty()) return false
        runCatching {
            ServiceSettings.updatePrivacyShow(context, true, true)
            ServiceSettings.updatePrivacyAgree(context, true)
            ServiceSettings.getInstance().setApiKey(key)
            // 注意：搜索侧的 HTTPDNS 默认**不开**。
            // 9.8.1 提供了 ServiceSettings.setHttpDnsEnabled，但实测开启后
            // Inputtips 搜索会间歇性抛 "http或socket连接失败 - ConnectionException"
            // （同一台机器、同一网络下，连续 5 次搜索失败 3 次）。
            // 地图侧的地图 HTTPDNS 没有这个问题，所以只有搜索不启。
        }
        return true
    }

    /** 定位 SDK 初始化（地图蓝点内部使用；独立定位前调用） */
    fun initLocation(context: Context): Boolean {
        if (!isPrivacyAgreed(context)) return false
        val key = apiKey(context)
        if (key.isEmpty()) return false
        runCatching {
            AMapLocationClient.updatePrivacyShow(context, true, true)
            AMapLocationClient.updatePrivacyAgree(context, true)
            AMapLocationClient.setApiKey(key)
        }
        return true
    }

    /** Key 变更后同步到各 SDK */
    private fun applyKey(context: Context) {
        if (!isPrivacyAgreed(context)) return
        val key = apiKey(context)
        if (key.isEmpty()) return
        runCatching {
            MapsInitializer.setApiKey(key)
            MapsInitializer.setHttpDnsEnabled(true)
            ServiceSettings.getInstance().setApiKey(key)
            AMapLocationClient.setApiKey(key)
        }
    }

    private fun applyPrivacy(context: Context) {
        runCatching {
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            ServiceSettings.updatePrivacyShow(context, true, true)
            ServiceSettings.updatePrivacyAgree(context, true)
            AMapLocationClient.updatePrivacyShow(context, true, true)
            AMapLocationClient.updatePrivacyAgree(context, true)
        }
    }
}
