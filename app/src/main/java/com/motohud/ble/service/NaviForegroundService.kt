package com.motohud.ble.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.navi.NaviEngine
import com.motohud.ble.ui.navi.GuidanceActivity
import com.motohud.ble.ui.navi.TurnIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 导航前台服务。
 *
 * 两件事：
 *  1. **保命**：导航引擎靠 Handler 每秒 tick，屏幕一关系统很快进入 Doze，
 *     主线程定时器会被冻结 —— 表现就是"熄屏后车机上的剩余距离不动了、也不播报了"。
 *     前台服务 + PARTIAL_WAKE_LOCK 让进程息屏期间仍有 CPU 时间。
 *  2. **通知栏导航条**：骑行时手机在兜里/熄屏，这是最高频的"瞥一眼"场景。
 *     通知内容是转向图标 + 「右转 · 300m」+「剩余 12.6km」，
 *     锁屏可见、常驻、不发声，带「结束导航」按钮。
 *
 * 刷新策略：**只在图标变化或剩余距离跨 500m 档位时**更新通知。
 * 按 1Hz 刷通知既耗电又会把通知栏刷成噪音。
 */
class NaviForegroundService : android.app.Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 上一次通知的状态，用来判断要不要刷新 */
    private var lastIcon = Int.MIN_VALUE
    private var lastBucket = Int.MIN_VALUE
    private var lastArrived = false

    private val engine: NaviEngine
        get() = (application as MotoHudApp).naviEngine

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground()
        updateWakeLock()
        observeEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 通知栏「结束导航」：停引擎 + 给车机发 NO（复刻原 App 退出写 "NO"）
            runCatching {
                engine.stop()
                (application as MotoHudApp).naviForwarder.forwardExit()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        // 每次启动都刷新一次（页面可能换了目的地，也可能刚切换「息屏导航」）
        startAsForeground()
        updateWakeLock()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** 订阅导航状态，按节流规则刷新通知 */
    private fun observeEngine() {
        if (job != null) return
        job = scope.launch {
            engine.state.collect { s -> maybeRefresh(s) }
        }
    }

    private fun maybeRefresh(s: NaviEngine.NaviState) {
        val bucket = if (s.running) (s.pathRemain / REFRESH_BUCKET_M) else -1
        val changed = s.iconType != lastIcon || bucket != lastBucket || s.arrived != lastArrived
        if (!changed) return
        lastIcon = s.iconType
        lastBucket = bucket
        lastArrived = s.arrived
        startAsForeground(s)
    }

    private fun startAsForeground(state: NaviEngine.NaviState? = null) {
        val notification = buildNotification(state ?: engine.state.value)
        // 定位权限被拒时带 LOCATION 类型会抛 SecurityException ——
        // 宁可不带类型（只保留通知条），也不能让整个导航挂掉
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTI_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            }.isSuccess
        } else {
            runCatching { startForeground(NOTI_ID, notification) }.isSuccess
        }
        if (!ok) runCatching { startForeground(NOTI_ID, notification) }
    }

    private fun buildNotification(s: NaviEngine.NaviState): Notification {
        val open = Intent(this, GuidanceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, NaviForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // RemoteViews：图标 + 「右转 · 300m」+「剩余 12.6km」
        val views = RemoteViews(packageName, R.layout.notif_navi)
        val hasNavi = s.running || s.arrived
        if (hasNavi) {
            views.setImageViewResource(R.id.notif_icon, TurnIcons.res(s.iconType))
            val action = s.stepInstruction.ifBlank { getString(R.string.hud_step_remain_label) }
            views.setTextViewText(
                R.id.notif_title,
                "${shortAction(action)} · ${CoordConverter.formatDistance(s.stepRemain)}"
            )
            views.setTextViewText(
                R.id.notif_sub,
                getString(
                    R.string.navi_fg_remain,
                    CoordConverter.formatDistance(s.pathRemain),
                    CoordConverter.formatEta(s.etaSeconds)
                )
            )
        } else {
            views.setImageViewResource(R.id.notif_icon, R.drawable.ic_nav_navigation)
            views.setTextViewText(R.id.notif_title, getString(R.string.navi_fg_title))
            views.setTextViewText(R.id.notif_sub, getString(R.string.navi_fg_text))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_navigation)
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentTitle(getString(R.string.navi_fg_title))
            .setContentText(getString(R.string.navi_fg_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(pi)
            .addAction(0, getString(R.string.guidance_stop), stopPi)
            .build()
    }

    /** 引导语通常很长（"沿民安大道行驶 500 米后右转"），通知只有一行，取动作短语 */
    private fun shortAction(instruction: String): String {
        val keys = listOf("左转", "右转", "掉头", "直行", "靠左", "靠右", "进入环岛", "驶出环岛", "到达")
        keys.firstOrNull { instruction.contains(it) }?.let { return it }
        return if (instruction.length > 8) instruction.take(8) else instruction
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.navi_fg_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * 唤醒锁只在「息屏导航」开启时持有。
     *
     * 通知栏导航条本身是**每次导航都要有**的（息屏/兜里瞥一眼方向），
     * 但为了让通知存在就长期抓 PARTIAL_WAKE_LOCK 是浪费电 ——
     * 屏幕亮着的时候主线程不会被冻结，根本不需要它。
     */
    private fun updateWakeLock() {
        if (NaviPrefs.screenOffNavi.value) acquireWakeLock() else releaseWakeLock()
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motohud:navi").apply {
            setReferenceCounted(false)
            runCatching { acquire(3 * 60 * 60 * 1000L) } // 最长 3 小时，防止异常泄漏
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "navi_foreground"
        private const val NOTI_ID = 2101
        const val ACTION_STOP = "com.motohud.ble.action.NAVI_STOP"

        /** 剩余距离每跨过 500m 才刷新一次通知 */
        private const val REFRESH_BUCKET_M = 500

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, NaviForegroundService::class.java)
                )
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, NaviForegroundService::class.java)) }
        }
    }
}
