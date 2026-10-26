package com.motohud.ble.data.device

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 车机存档（跨会话持久化）。
 *
 * 为什么需要：用户一般只有一台车机，每次打开 App 都要手动扫描 + 点选是最大的体验缺口。
 * 这里只记住**一台**主用车机（MAC + 广播名），配合
 * [com.motohud.ble.ble.BleConnectionManager.connectSavedOrScan] 实现"打开即连"。
 *
 * 写入时机：只在**真正连上之后**写（`Event.ConnectionState(CONNECTED)`），
 * 不在发起连接时写——否则用户点错设备也会被记成"我的车"。
 */
object DevicePrefs {

    private const val PREFS = "device_prefs"
    private const val KEY_ADDRESS = "last_address"
    private const val KEY_NAME = "last_name"
    private const val KEY_AUTO = "auto_connect"
    private const val KEY_LAST_TS = "last_connected_at"
    private const val KEY_HINT_SHOWN = "auto_hint_shown"
    private const val KEY_LAST_TRY = "last_auto_try_at"

    private lateinit var prefs: SharedPreferences

    /** 存档的车机（address to name），null = 从未连过 */
    private val _saved = MutableStateFlow<Pair<String, String>?>(null)
    val saved: StateFlow<Pair<String, String>?> = _saved.asStateFlow()

    /** 自动连接开关（默认开） */
    private val _auto = MutableStateFlow(true)
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    /** 上次成功连接时间戳，0 = 从未连过 */
    private val _lastConnectedAt = MutableStateFlow(0L)
    val lastConnectedAt: StateFlow<Long> = _lastConnectedAt.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val addr = prefs.getString(KEY_ADDRESS, null)
        _saved.value = if (addr.isNullOrBlank()) null else addr to prefs.getString(KEY_NAME, null).orEmpty()
        _auto.value = prefs.getBoolean(KEY_AUTO, true)
        _lastConnectedAt.value = prefs.getLong(KEY_LAST_TS, 0L)
    }

    /** 连接成功后落盘 */
    fun save(ctx: Context, address: String, name: String?) {
        init(ctx)
        if (address.isBlank()) return
        val safeName = name.orEmpty()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, safeName)
            .putLong(KEY_LAST_TS, now)
            .apply()
        _saved.value = address to safeName
        _lastConnectedAt.value = now
    }

    fun last(ctx: Context): Pair<String, String>? {
        init(ctx)
        return _saved.value
    }

    fun hasSaved(ctx: Context): Boolean = last(ctx) != null

    fun autoConnectEnabled(ctx: Context): Boolean {
        init(ctx)
        return _auto.value
    }

    fun setAutoConnect(ctx: Context, on: Boolean) {
        init(ctx)
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
        _auto.value = on
    }

    /** 忘记此车机：清空存档（下次打开回到"未连接"引导态） */
    fun forget(ctx: Context) {
        init(ctx)
        prefs.edit()
            .remove(KEY_ADDRESS)
            .remove(KEY_NAME)
            .remove(KEY_LAST_TS)
            .apply()
        _saved.value = null
        _lastConnectedAt.value = 0L
    }

    /** 首次自动连接说明弹窗是否已展示过 */
    fun hintShown(ctx: Context): Boolean {
        init(ctx)
        return prefs.getBoolean(KEY_HINT_SHOWN, false)
    }

    fun markHintShown(ctx: Context) {
        init(ctx)
        prefs.edit().putBoolean(KEY_HINT_SHOWN, true).apply()
    }

    /** 上次自动连接尝试时间（回前台 60s 节流用） */
    fun lastAutoTry(ctx: Context): Long {
        init(ctx)
        return prefs.getLong(KEY_LAST_TRY, 0L)
    }

    fun markAutoTry(ctx: Context) {
        init(ctx)
        prefs.edit().putLong(KEY_LAST_TRY, System.currentTimeMillis()).apply()
    }
}
