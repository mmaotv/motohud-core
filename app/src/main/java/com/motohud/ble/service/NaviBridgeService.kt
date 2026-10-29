package com.motohud.ble.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.BleDiagnostic
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.navi.bridge.AmapBroadcast
import com.motohud.ble.navi.bridge.BaiduBroadcast
import com.motohud.ble.navi.bridge.BridgeNaviState
import com.motohud.ble.navi.bridge.NaviBridgeHub
import com.motohud.ble.ui.MainActivity
import com.motohud.ble.ui.navi.TurnIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 第三方导航桥接服务。
 *
 * 手机继续用高德 / 百度导航，本服务把它们的状态转成车机协议发出去，
 * **不重新算路、不画地图、不碰导航逻辑**。
 *
 * ## 为什么是前台服务
 *
 * 高德 / 百度发的是**隐式广播**。Android 8.0 起静态注册的 `<receiver>` 收不到这类广播，
 * 只能 `registerReceiver()` 动态注册；而动态注册的接收器跟着进程走，
 * 进程被回收就断了。前台服务把进程钉住，骑行时锁屏也不会掉线。
 *
 * ## 数据流
 *
 * ```
 * 高德 AUTONAVI_STANDARD_BROADCAST_SEND ─┐
 * 百度 com.baidu.map.auto.NOTIFY.*      ─┼→ NaviBridgeHub → 本服务 → NaviForwarder.forwardRaw
 * 通知文本（广播静默时兜底）             ─┘                          → NaviCommand → BLE
 * ```
 *
 * 本服务只负责**广播通道**；通知通道由 [NotificationForwardService] 投递到同一个 Hub。
 */
class NaviBridgeService : Service() {

    private val app get() = application as MotoHudApp

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rx: BroadcastReceiver? = null
    private var job: Job? = null

    /** 是否已向 NaviForwarder 注册为数据源（走它既有的"投到车机"总开关与连接判断） */
    private var attached = false

    /** 到达 / 退出只发一次，否则每秒一帧会把车机刷成反复提示 */
    private var finishSent = false
    private var exitSent = false

    /** 节流用的上一条已发送状态 */
    private var lastIcon: Int? = null
    private var lastStep: Int? = null
    private var lastPath: Int? = null
    private var lastBucket = -1
    private var lastPushAt = 0L

    /** 前台通知去重，避免每帧都重建通知 */
    private var lastNotiKey = ""

    /** 是否正在为本 App 自带导航让位 */
    private var yielding = false

    /** 原始广播只记前几条的 extras key 集合，够定位问题又不刷屏 */
    private val seenKinds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        NaviBridgeHub.enabled = true
        NaviBridgeHub.reset()

        // 注册成 NaviForwarder 的一个数据源：它内部已经处理了
        // 「投到车机开关」与「BLE 是否连接」，这里不再重复判断
        app.naviForwarder.attach(TAG_ATTACH)
        attached = true

        createChannel()
        showForeground(getString(R.string.bridge_fg_title), getString(R.string.bridge_fg_waiting))
        registerReceivers()
        observe()
        BleDiagnostic.log("导航桥接：服务启动，等待高德/百度广播")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 不做 START_STICKY：用户关掉开关后不该被系统重新拉起来
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        rx?.let { runCatching { unregisterReceiver(it) } }
        rx = null
        job?.cancel()

        // 停止转发前先清车机屏幕，免得残留在最后一帧
        runCatching { app.naviForwarder.forwardExit() }
        if (attached) {
            app.naviForwarder.detach(TAG_ATTACH)
            attached = false
        }
        NaviBridgeHub.enabled = false
        NaviBridgeHub.reset()

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        BleDiagnostic.log("导航桥接：服务停止")
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 广播
    // ------------------------------------------------------------------

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(AmapBroadcast.ACTION_SEND)
            addAction(BaiduBroadcast.ACTION_INDUCED)
            addAction(BaiduBroadcast.ACTION_STATE)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val i = intent ?: return
                try {
                    val state = when (i.action) {
                        AmapBroadcast.ACTION_SEND -> AmapBroadcast.parse(i)
                        else -> BaiduBroadcast.parse(i)
                    } ?: return
                    logRawOnce(i)
                    Log.d(
                        TAG,
                        "解析 ${i.action} → ${state.source.label}" +
                            " 图标=${state.iconType} 下一路口=${state.stepRemainM}" +
                            " 剩余=${state.pathRemainM}" +
                            " run=${state.running} arrived=${state.arrived}"
                    )
                    NaviBridgeHub.offerBroadcast(state)
                } catch (e: Exception) {
                    Log.w(TAG, "广播解析失败: ${i.action}", e)
                    BleDiagnostic.log("导航桥接：广播解析异常 ${e.message}")
                }
            }
        }
        // EXPORTED：高德 / 百度是别的应用，NOT_EXPORTED 会直接收不到
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        rx = receiver
    }

    /**
     * 每种广播只把 extras 的 key 集合记一次。
     *
     * 真机适配时最有用的就是这个：能一眼看出这台机器上的高德到底发了哪些字段、
     * 百度用的是直接 extra 还是 method+value。
     */
    private fun logRawOnce(intent: Intent) {
        val kind = "${intent.action}#${intent.extras?.get(AmapBroadcast.KEY_TYPE) ?: ""}"
        if (!seenKinds.add(kind) || seenKinds.size > MAX_KIND_LOGS) return
        val keys = intent.extras?.keySet()?.sorted()?.joinToString(",").orEmpty()
        BleDiagnostic.log("导航桥接：收到 $kind extras=[$keys]")
    }

    // ------------------------------------------------------------------
    // 状态 → 车机
    // ------------------------------------------------------------------

    private fun observe() {
        job = scope.launch {
            NaviBridgeHub.state.collect { s -> if (s != null) onState(s) }
        }
        scope.launch {
            app.bleManager.state.collect { st ->
                if (st == BleConnectionManager.State.CONNECTED) {
                    BleDiagnostic.log("导航桥接：车机已连接")
                    // 重连后清掉节流记录，让第一帧一定发出去
                    lastIcon = null
                    lastStep = null
                    lastPath = null
                    lastBucket = -1
                    lastPushAt = 0L
                }
            }
        }
    }

    private fun onState(s: BridgeNaviState) {
        // 1) 到达目的地
        if (s.arrived) {
            if (!finishSent) {
                finishSent = true
                exitSent = false
                app.naviForwarder.forwardFinish()
                BleDiagnostic.log("导航桥接[${s.source.label}]：到达目的地 → FINISH")
                showForeground(
                    getString(R.string.bridge_fg_title),
                    getString(R.string.bridge_fg_arrived)
                )
            }
            return
        }

        // 2) 导航结束：清车机显示，但不重置 Hub（广播通道还活着）
        if (!s.running) {
            if (!exitSent) {
                exitSent = true
                finishSent = false
                app.naviForwarder.forwardExit()
                BleDiagnostic.log("导航桥接[${s.source.label}]：导航结束 → NO")
                showForeground(
                    getString(R.string.bridge_fg_title),
                    getString(R.string.bridge_fg_waiting)
                )
            }
            return
        }

        if (!s.hasPayload) return
        // 新一轮导航开始，重置一次性标记
        finishSent = false
        exitSent = false
        push(s)
    }

    private fun push(s: BridgeNaviState) {
        /*
         * 本 App 自带的导航正在跑就退让。
         *
         * 两条链路最终都写同一个 NaviForwarder，同时开会变成「本 App 一帧、
         * 高德一帧」交替下发，车机上距离和图标来回跳。用户既然在本 App 里
         * 开了导航，就用本 App 的；关掉后自动恢复第三方转发。
         */
        val ownNavi = app.naviEngine.state.value.running
        if (ownNavi != yielding) {
            yielding = ownNavi
            BleDiagnostic.log(
                if (ownNavi) "导航桥接：本 App 导航进行中，第三方转发暂停"
                else "导航桥接：本 App 导航结束，恢复第三方转发"
            )
        }
        if (ownNavi) return

        val now = SystemClock.elapsedRealtime()
        val iconChanged = s.iconType != lastIcon
        val same = !iconChanged && s.stepRemainM == lastStep && s.pathRemainM == lastPath
        // 完全没变就别重复处理（直行路段高德也是 1Hz 推送）
        if (same && now - lastPushAt < IDLE_REPEAT_MS) return
        // 图标没变时限制最小间隔，防止距离跳动把写队列刷满
        if (!iconChanged && lastPushAt > 0 && now - lastPushAt < MIN_GAP_MS) return

        val connected = app.bleManager.state.value == BleConnectionManager.State.CONNECTED
        val castOn = com.motohud.ble.data.NaviPrefs.castEnabled.value

        lastIcon = s.iconType
        lastStep = s.stepRemainM
        lastPath = s.pathRemainM
        lastPushAt = now

        // 关键变化才落盘：图标变了、或下一路口距离跨过 100m 档。
        // 未连车机 / 总开关关着也照样记 —— 现场判断"是没收到数据还是没地方发"全靠这一行。
        val bucket = (s.stepRemainM ?: -1) / LOG_BUCKET_M
        if (iconChanged || bucket != lastBucket) {
            lastBucket = bucket
            val tail = when {
                !connected -> "（车机未连接，未下发）"
                !castOn -> "（「投到车机」已关，未下发）"
                else -> ""
            }
            BleDiagnostic.log(
                "导航桥接[${s.source.label}] 图标=${s.iconType ?: "-"}" +
                    " 下一路口=${s.stepRemainM?.let { "${it}m" } ?: "-"}" +
                    " 剩余=${s.pathRemainM?.let { "${it}m" } ?: "-"}" +
                    if (s.roadName.isBlank()) tail else " 路名=${s.roadName}$tail"
            )
        }

        // 前台通知始终跟着解析结果走，这样"数据进来了但车机没连"一眼能看出来
        refreshNotification(s)

        if (!connected) {
            Log.d(
                TAG,
                "未连接车机，跳过下发（图标=${s.iconType}" +
                    " 下一路口=${s.stepRemainM} 剩余=${s.pathRemainM}）"
            )
            return
        }
        if (!castOn) return

        app.naviForwarder.forwardRaw(s.iconType, s.pathRemainM, s.stepRemainM)
    }

    // ------------------------------------------------------------------
    // 前台通知
    // ------------------------------------------------------------------

    private fun refreshNotification(s: BridgeNaviState) {
        val step = s.stepRemainM?.let { getString(R.string.bridge_fg_step, CoordConverter.formatDistance(it)) }
        val path = s.pathRemainM?.let { getString(R.string.bridge_fg_path, CoordConverter.formatDistance(it)) }
        val detail = listOfNotNull(step, path).joinToString("  ")
            .ifBlank { getString(R.string.bridge_fg_no_data) }
        val text = getString(R.string.bridge_fg_active, s.source.label, detail)
        val key = "$text|${s.iconType}"
        if (key == lastNotiKey) return
        lastNotiKey = key
        val iconRes = s.iconType?.let { TurnIcons.res(it) } ?: R.drawable.ic_nav_navigation
        showForeground(getString(R.string.bridge_fg_title), text, iconRes)
    }

    private fun showForeground(title: String, text: String, smallIcon: Int = R.drawable.ic_nav_navigation) {
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val noti: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_navigation)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()

        val ok = if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                ServiceCompat.startForeground(
                    this, NOTI_ID, noti,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }.isSuccess
        } else {
            runCatching { startForeground(NOTI_ID, noti) }.isSuccess
        }
        if (!ok) runCatching { startForeground(NOTI_ID, noti) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bridge_fg_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    companion object {
        private const val TAG = "NaviBridge"
        private const val CHANNEL_ID = "navi_bridge"
        private const val NOTI_ID = 2102
        private const val TAG_ATTACH = "navi_bridge_source"

        /** 图标未变时两帧之间的最小间隔 */
        private const val MIN_GAP_MS = 800L

        /** 状态完全没变时的最长重复发送间隔 */
        private const val IDLE_REPEAT_MS = 5_000L

        /** 日志分档：下一路口距离每跨 100m 记一行 */
        private const val LOG_BUCKET_M = 100

        private const val MAX_KIND_LOGS = 8

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, NaviBridgeService::class.java))
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, NaviBridgeService::class.java)) }
        }
    }
}
