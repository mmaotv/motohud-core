package com.motohud.ble.obd

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OBD 适配器存档。
 *
 * 和车机存档（[com.motohud.ble.data.device.DevicePrefs]）分开存：
 * 用户可能换车机但不换诊断头，反过来也一样，混在一份 key 里会互相覆盖。
 *
 * 只在**真正连上并初始化成功**之后写，避免点错设备被记成"我的诊断头"。
 */
object ObdPrefs {

    private const val PREFS = "obd_prefs"
    private const val KEY_ADDRESS = "last_address"
    private const val KEY_NAME = "last_name"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_TS = "last_connected_at"

    private lateinit var prefs: SharedPreferences

    /** 总开关：关掉后不自动连接，也不在后台轮询（省电） */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _saved = MutableStateFlow<Pair<String, String>?>(null)
    val saved: StateFlow<Pair<String, String>?> = _saved.asStateFlow()

    private val _lastConnectedAt = MutableStateFlow(0L)
    val lastConnectedAt: StateFlow<Long> = _lastConnectedAt.asStateFlow()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val addr = prefs.getString(KEY_ADDRESS, null)
        _saved.value = if (addr.isNullOrBlank()) null else addr to prefs.getString(KEY_NAME, null).orEmpty()
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        _lastConnectedAt.value = prefs.getLong(KEY_LAST_TS, 0L)
    }

    fun save(ctx: Context, address: String, name: String?) {
        init(ctx)
        if (address.isBlank()) return
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, name.orEmpty())
            .putLong(KEY_LAST_TS, now)
            .apply()
        _saved.value = address to name.orEmpty()
        _lastConnectedAt.value = now
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        init(ctx)
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    fun forget(ctx: Context) {
        init(ctx)
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).remove(KEY_LAST_TS).apply()
        _saved.value = null
        _lastConnectedAt.value = 0L
    }
}
