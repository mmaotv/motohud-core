package com.motohud.ble.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import com.motohud.ble.MotoHudApp
import com.motohud.ble.ble.BleConstants
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.navi.bridge.NaviBridgeHub
import com.motohud.ble.navi.bridge.NotifyTextParser

/**
 * 通知/来电转发服务 —— 复刻原 App TWNotifyService（264 行）。
 *
 * 证据：decompiled/.../controller/ble/TWNotifyService.java
 *  - 监听应用通知与来电状态
 *  - SR 设备码表：WECHAT/LINE/SMS/CALL（count 恒 1）
 *  - BYQ 设备码表：WA/IG/LI/TE/WC/SM/MC（count=number+1，移除时 0）
 *  - 仅当已连接目标设备时转发，经 MSG :<类型>:<计数>: 指令上行
 */
class NotificationForwardService : NotificationListenerService() {

    private lateinit var app: MotoHudApp
    private var callListener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        app = application as MotoHudApp
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val tm = getSystemService(TelephonyManager::class.java)
        callListener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)
                forwardCall(state)
            }
        }
        tm?.listen(callListener, PhoneStateListener.LISTEN_CALL_STATE)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            // 导航通知桥接：只有「导航桥接」开关打开、且广播通道静默时才真正生效，
            // 判断都在 Hub 内部做，这里只负责把能解析出来的字段投递过去。
            // 高德/百度不在 WATCHED_PACKAGES 里，不会和下面的通知计数转发冲突。
            if (NaviBridgeHub.enabled) {
                val navi = NotifyTextParser.parse(sbn)
                if (navi != null) {
                    NaviBridgeHub.offerNotification(navi)
                    return
                }
            }
            val pkg = sbn.packageName
            val count = (sbn.notification.number + 1).coerceAtLeast(1)
            val type = mapType(pkg)
            if (type != null) forward(type, count)
        } catch (e: Exception) {
            Log.w(TAG, "onNotificationPosted failed", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val type = mapType(sbn.packageName)
        if (type != null) forward(type, 0)
    }

    /** 来电/挂断（TWNotifyService.java:227-247） */
    private fun forwardCall(state: Int) {
        val ringing = state == TelephonyManager.CALL_STATE_RINGING
        val type = if (isSrDevice()) BleConstants.TYPE_SR_CALL else BleConstants.TYPE_MC
        forward(type, if (ringing) 1 else 0)
    }

    private fun mapType(pkg: String): String? {
        val isSr = isSrDevice()
        val watched = BleConstants.WATCHED_PACKAGES.containsKey(pkg) ||
            pkg.contains("facebook")
        if (!watched) return null
        return when {
            pkg.contains("com.tencent.mm") -> if (isSr) BleConstants.TYPE_SR_WECHAT else BleConstants.TYPE_WC
            pkg.contains("jp.naver.line.android") -> if (isSr) BleConstants.TYPE_SR_LINE else BleConstants.TYPE_LI
            pkg.contains("android.messaging") || pkg.contains("android.mms") ->
                if (isSr) BleConstants.TYPE_TYPE_SR_SMS else BleConstants.TYPE_SM
            pkg.contains("com.whatsapp") -> BleConstants.TYPE_WA
            pkg.contains("com.instagram.android") -> BleConstants.TYPE_IG
            pkg.contains("org.telegram.messenger") -> BleConstants.TYPE_TE
            pkg.contains("facebook") -> null // FB 在原实现中保留默认码，此处跳过
            else -> null
        }
    }

    private fun forward(type: String, count: Int) {
        if (app.bleManager.state.value != BleConnectionManager.State.CONNECTED) return
        val name = app.bleManager.connectedDeviceName
        // 原 App 仅对 SR 开头设备强制转发；这里对已连接目标设备均转发（码表已按类型区分）
        if (BleConstants.deviceTypeOf(name) == BleConstants.DeviceType.UNKNOWN) return
        Log.i(TAG, "forward: MSG :$type:$count:")
        app.bleManager.sendCommand(BleConstants.cmdMsg(type, count))
    }

    private fun isSrDevice(): Boolean =
        BleConstants.deviceTypeOf(app.bleManager.connectedDeviceName) == BleConstants.DeviceType.SR

    override fun onDestroy() {
        callListener?.let {
            getSystemService(TelephonyManager::class.java)?.listen(it, PhoneStateListener.LISTEN_NONE)
        }
        callListener = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotifyForward"
    }
}
