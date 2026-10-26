package com.motohud.ble.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.AppCache
import com.motohud.ble.data.device.DevicePrefs
import com.motohud.ble.service.NotificationForwardService
import com.motohud.ble.ui.common.MotoDialog
import com.motohud.ble.ui.navi.KeySetupDialog
import com.motohud.ble.ui.scan.ScanActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 主界面：**爱车 / 导航** 两个页签 + 顶部连接状态条。
 *
 * 为什么只剩两个页签：
 * 实测确认 BYQ 车机只提供投屏导航通道，没有任何上行遥测数据，
 * 仪表 / 体检 / 指令台在本硬件上属于"永远等不到数据"的功能，
 * 因此入口全部收起（代码保留在 [com.motohud.ble.ui.dashboard] 等包里，
 * 归档分支 archive/full-2.2.7 随时可恢复），只留真正可用的两个功能。
 *
 * 连接状态条：连接不再是独立页签，而是状态条 + 爱车页连接卡；
 * 未连接时点状态条 → 连接页（二级页）；已连接的设备由
 * [DevicePrefs] 记住，冷启动 / 回前台自动连。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var vm: MainViewModel
    private lateinit var pager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var banner: View
    private lateinit var bannerDot: ImageView
    private lateinit var bannerText: TextView
    private lateinit var bannerAction: TextView

    private val app get() = application as MotoHudApp

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 权限刚拿到 → 补一次自动连接（用户可能一开 App 就点了"允许"）
        if (it.values.any { g -> g }) maybeAutoConnect(force = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首次启动：先走新手引导（含隐私政策同意），完成后再进入主界面
        if (savedInstanceState == null && !OnboardingActivity.isDone(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        vm = ViewModelProvider(this, MainViewModel.Factory(app))[MainViewModel::class.java]

        banner = findViewById(R.id.banner_connection)
        bannerDot = findViewById(R.id.banner_dot)
        bannerText = findViewById(R.id.banner_text)
        bannerAction = findViewById(R.id.banner_action)

        pager = findViewById(R.id.view_pager)
        pager.adapter = MainPagerAdapter(this)
        pager.isUserInputEnabled = false
        pager.offscreenPageLimit = 1
        pager.setPageTransformer { page, position ->
            // 纯淡入淡出 + 轻微缩放（不横向位移，避免相邻页从边缘露出）
            val p = abs(position).coerceIn(0f, 1f)
            page.alpha = 1f - p
            val scale = 0.94f + (1f - p) * 0.06f
            page.scaleX = scale
            page.scaleY = scale
        }

        bottomNav = findViewById(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            pager.currentItem = when (item.itemId) {
                R.id.nav_garage -> TAB_GARAGE
                R.id.nav_navigation -> TAB_NAVIGATION
                else -> TAB_GARAGE
            }
            true
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNav.selectedItemId = when (position) {
                    TAB_NAVIGATION -> R.id.nav_navigation
                    else -> R.id.nav_garage
                }
                onTabShown(position)
            }
        })

        applyEdgeToEdge()
        setupConnectionBanner()
        requestBlePermissions()
        // 冷启动自动连接：延迟 300ms 让首帧 UI 先画出来，用户不会看到"白屏等连接"
        banner.postDelayed({ maybeAutoConnect() }, 300L)

        vm.toast.observe(this) { msg ->
            msg?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                vm.consumeToast()
            }
        }
    }

    // ---------- 自动连接 ----------

    /**
     * 自动连接（幂等）。触发点：冷启动、回到前台（60s 节流）、掉线重连由 BLE 层负责。
     *
     * 触发条件全部满足才动手：有存档 + 开关开 + 未手动断开 + 蓝牙开 + 权限齐 + 当前未连接。
     * 失败静默——车不在旁边是常态，弹窗只会打扰。
     */
    private fun maybeAutoConnect(force: Boolean = false) {
        val ble = app.bleManager
        if (!DevicePrefs.autoConnectEnabled(this)) return
        if (ble.manualDisconnect) return
        when (ble.state.value) {
            BleConnectionManager.State.CONNECTED,
            BleConnectionManager.State.CONNECTING,
            BleConnectionManager.State.RECONNECTING -> return
            else -> Unit
        }
        val saved = DevicePrefs.last(this) ?: return
        if (!blePermissionsGranted()) return
        if (!force) {
            val since = System.currentTimeMillis() - DevicePrefs.lastAutoTry(this)
            if (since < AUTO_RETRY_GAP_MS) return
        }
        DevicePrefs.markAutoTry(this)
        // 连上不再弹 Toast：顶部状态条本身就会变成「已连接」，弹窗只是噪音
        ble.connectSavedOrScan(saved.first, saved.second)
    }

    private fun blePermissionsGranted(): Boolean {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        return perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 顶部连接状态条：连接状态 → 文案 / 圆点 / 动作 */
    private fun setupConnectionBanner() {
        val ble = app.bleManager
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ble.state.collect { st -> renderBanner(st, ble.connectedDeviceName) }
            }
        }
        banner.setOnClickListener { onBannerTap() }
        banner.setOnLongClickListener {
            showSettings()
            true
        }
        // 未连接时整条状态条就是那个"去连接"按钮：
        // 以前文字写「未连接车机」右边再放一颗「去连接」，同一件事两个入口，
        // 而且两处的点击行为还不一样，用户不知道该点哪个。
        banner.setOnClickListener { onBannerTap() }
        bannerAction.setOnClickListener { onBannerTap() }
    }

    /**
     * 点击状态条：
     *  - 已连接 → 设置
     *  - 有存档但未连上 → 先就地重试一次自动连接（最快路径），再退到连接页
     *  - 从未连过 → 直接进连接页
     */
    private fun onBannerTap() {
        val ble = app.bleManager
        if (ble.state.value == BleConnectionManager.State.CONNECTED) {
            showSettings()
            return
        }
        val saved = DevicePrefs.last(this)
        if (saved != null && blePermissionsGranted()) {
            DevicePrefs.markAutoTry(this)
            ble.connectSavedOrScan(saved.first, saved.second) { ok ->
                if (!ok) runOnUiThread { ScanActivity.start(this) }
            }
        } else {
            ScanActivity.start(this)
        }
    }

    private fun renderBanner(state: BleConnectionManager.State, deviceName: String?) {
        banner.visibility = View.VISIBLE
        when (state) {
            BleConnectionManager.State.CONNECTED -> {
                bannerDot.setImageResource(R.drawable.dot_green)
                bannerText.text = getString(R.string.banner_connected, deviceName ?: "车机")
                bannerAction.setText(R.string.settings_title)
                bannerAction.visibility = View.VISIBLE
            }
            BleConnectionManager.State.CONNECTING -> {
                bannerDot.setImageResource(R.drawable.dot_orange)
                bannerText.setText(R.string.status_connecting)
                bannerAction.visibility = View.GONE
            }
            BleConnectionManager.State.RECONNECTING -> {
                bannerDot.setImageResource(R.drawable.dot_orange)
                bannerText.setText(R.string.status_reconnecting)
                bannerAction.visibility = View.GONE
            }
            BleConnectionManager.State.DISCONNECTED -> {
                bannerDot.setImageResource(R.drawable.dot_gray)
                // 文案本身带动作提示（› 表示可点），右侧不再单独放「去连接」按钮
                bannerText.setText(
                    if (DevicePrefs.hasSaved(this)) R.string.banner_disconnected_saved_tap
                    else R.string.banner_disconnected_tap
                )
                bannerAction.visibility = View.GONE
            }
        }
    }

    /**
     * 边到边适配。
     *
     * targetSdk 35+ 在 Android 15 上强制 edge-to-edge，内容会画到状态栏下面，
     * 表现就是"顶部界面被状态栏压住 / 时间电量叠在标题上"。
     * 这里主动关掉系统自动避让，再自己按 insets 给状态条与底部导航留白，
     * 这样刘海屏、挖孔屏、手势条的数值都能自适应。
     */
    private fun applyEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val density = resources.displayMetrics.density
        val baseTop = (6 * density).toInt()
        val baseBottom = (6 * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(banner) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + baseTop)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom + baseBottom)
            insets
        }
    }

    /** 切页（供各页空态"去连接"与状态条复用） */
    fun goToTab(index: Int) {
        if (!::pager.isInitialized) return
        pager.setCurrentItem(index.coerceIn(0, TAB_COUNT - 1), true)
    }

    /** 打开连接页（爱车页连接卡、各页空态共用） */
    fun openConnectPage() {
        ScanActivity.start(this)
    }

    /**
     * 页面可见时通知对应页做一次刷新。
     * 导航页最需要：切过去时定位可能已经过期，必须主动重新取一次，
     * 否则会一直显示很久以前的位置。
     */
    private fun onTabShown(position: Int) {
        if (position != TAB_NAVIGATION) return
        supportFragmentManager.fragments.forEach { f ->
            (f as? com.motohud.ble.ui.navi.NavigationFragment)?.onTabShown()
        }
    }

    /** 高德 Key 变更后通知导航页重建地图（页面可能已创建但处于降级态） */
    fun refreshNaviKeyState() {
        supportFragmentManager.fragments.forEach { f ->
            if (f is com.motohud.ble.ui.navi.NavigationFragment) f.onAmapKeyChanged()
        }
    }

    override fun onStart() {
        super.onStart()
        // 回到前台：60s 内不重复尝试，避免频繁开关 App 把电池打光
        maybeAutoConnect()
    }

    // ---------- 设置 ----------

    /**
     * 设置入口：顶部状态条 / 爱车页右上角齿轮共用。
     *
     * 从 `setItems` 文本列表换成底部面板，因为「自动连接 / 投屏 / 息屏 /
     * 语音 / 路况」都是开关型设置，文本列表放不下。
     */
    fun showSettings() {
        com.motohud.ble.ui.settings.SettingsSheetDialog.newInstance()
            .show(supportFragmentManager, "settings")
    }

    fun openPrivacy() = showPrivacy()

    fun openClearCache() = showClearCache()

    private fun showPrivacy() {
        MotoDialog.builder(this)
            .setTitle(R.string.onboarding_privacy_title)
            .setMessage(R.string.onboarding_privacy_body)
            .setPositiveButton(R.string.setting) { _, _ -> /* 已同意，无需操作 */ }
            .show()
    }

    /**
     * 清除缓存：先异步算出当前大小展示给用户，确认后清空 cacheDir / externalCacheDir。
     * 只清标准缓存目录（地图瓦片、搜索结果等），filesDir 里的用户数据不动。
     */
    private fun showClearCache() {
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { AppCache.size(this@MainActivity) }
            val msg = if (size > 0) getString(R.string.cache_dialog_msg, AppCache.human(size))
            else getString(R.string.cache_empty)
            MotoDialog.builder(this@MainActivity)
                .setTitle(R.string.cache_dialog_title)
                .setMessage(msg)
                .setPositiveButton(R.string.cache_clear_btn) { _, _ ->
                    lifecycleScope.launch {
                        val freed = withContext(Dispatchers.IO) {
                            AppCache.clear(this@MainActivity)
                        }
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.cache_cleared, AppCache.human(freed)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** BLE 权限（复刻原 App：定位权限 + Android 12+ 蓝牙权限） */
    private fun requestBlePermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    /** 引导用户授予通知访问权限（复刻 BaseActivity.openNotifyController 引导） */
    fun promptNotificationAccess() {
        MotoDialog.builder(this)
            .setTitle(R.string.notify_title)
            .setMessage(R.string.notify_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.setting) { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setCancelable(false)
            .show()
    }

    fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(packageName)
    }

    fun startForwardService() {
        ContextCompat.startForegroundService(this, Intent(this, NotificationForwardService::class.java))
    }

    override fun onDestroy() {
        // 首启跳转引导时会提前 finish（ViewModel 尚未初始化）
        if (::vm.isInitialized) vm.ble.stopScan()
        super.onDestroy()
    }

    companion object {
        const val TAB_GARAGE = 0
        const val TAB_NAVIGATION = 1
        const val TAB_COUNT = 2

        /** 回前台自动连接的节流间隔 */
        private const val AUTO_RETRY_GAP_MS = 60_000L
    }
}
