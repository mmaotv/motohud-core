package com.motohud.ble.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.data.CrashHandler
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.data.device.DevicePrefs
import com.motohud.ble.databinding.DialogSettingsBinding
import com.motohud.ble.service.NaviBridgeService
import com.motohud.ble.service.NaviForegroundService
import com.motohud.ble.ui.MainActivity
import com.motohud.ble.ui.OnboardingActivity
import com.motohud.ble.ui.about.AboutActivity
import com.motohud.ble.ui.common.MotoDialog
import com.motohud.ble.ui.navi.RoutePrefsDialog
import com.motohud.ble.ui.scan.ScanActivity

/**
 * 设置面板 —— 全应用「可调项」的唯一入口。
 *
 * ## 为什么从 setItems 文本列表变成底部面板
 *
 * 「自动连接 / 投屏 / 息屏导航 / 语音播报 / 路况图层」都是**开关**，
 * 文本列表只能表达"点了就跳走"。这些开关以前被迫散落在首页卡片、
 * 导航页对话框里，用户找不到也不知道彼此关系。
 *
 * ## 归集原则（2026-10-29 重做分类）
 *
 * 按**用户想干什么**分组，不按代码模块分组：
 *  - 连接与投屏：车机连不连、投不投；
 *  - 语音播报：说什么、说多细、哪些别说；
 *  - 地图显示：地图长什么样、吃多少性能；
 *  - 路线与导航：怎么算路、避开什么；
 *  - 数据与系统：Key、隐私、缓存、关于。
 *
 * 本次把三处**原本摸不到**的设置搬了进来：
 *  1. 「播报详细度」原来只藏在导航页语音按钮的长按菜单里 —— 不进导航就改不了；
 *  2. 「路线偏好」原来只在选路线/导航页的模式角标上，首页进来无处可点；
 *  3. 沿途提示（隧道桥梁/弯道/坡度/驾驶行为）全部新增，此前根本没有开关。
 *
 * 操作类动作（连接车机、开始导航）仍留在原处 —— 它们不是"设置"。
 */
class SettingsSheetDialog : BottomSheetDialogFragment() {

    private var _b: DialogSettingsBinding? = null
    private val b get() = _b!!

    private val app get() = requireActivity().application as MotoHudApp
    private val act get() = requireActivity() as MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = DialogSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    /**
     * 面板顶到全屏高度并允许内部滚动。
     *
     * 布局根是 NestedScrollView，但 BottomSheet 默认按「半屏 peek」展开，
     * 不显式置为 EXPANDED 的话用户要自己往上拖才能看到下面的分组，
     * 而大多数人根本不知道能拖。
     */
    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        // ---------- 连接与投屏 ----------
        b.swAutoConnect.isChecked = DevicePrefs.autoConnectEnabled(ctx)
        b.swAutoConnect.setOnCheckedChangeListener { _, on ->
            DevicePrefs.setAutoConnect(ctx, on)
        }

        b.swCast.isChecked = NaviPrefs.castEnabled.value
        b.swCast.setOnCheckedChangeListener { _, on -> NaviPrefs.setCastEnabled(on) }

        // 第三方导航转发（高德/百度 APP → 车机）的开关已收起，见 NaviBridgeService 注释。
        // NaviBridgeService / NaviPrefs.bridgeEnabled 保留未删，接入车机版地图时可直接贴回。

        b.swScreenOff.isChecked = NaviPrefs.screenOffNavi.value
        b.swScreenOff.setOnCheckedChangeListener { _, on ->
            NaviPrefs.setScreenOffNavi(on)
            // 唤醒锁由服务内部按此开关决定，正在导航时要让它重新评估一次
            if (app.naviEngine.state.value.running) NaviForegroundService.start(ctx)
        }

        // ---------- 语音播报 ----------
        b.swVoice.isChecked = NaviPrefs.voice.value
        b.swVoice.setOnCheckedChangeListener { _, on -> NaviPrefs.setVoice(on) }

        b.setVoiceMode.setOnClickListener { pickVoiceMode() }
        renderVoiceMode()

        b.setPresetVoice.setOnClickListener { pickPresetVoice() }
        renderPresetVoice()

        b.swCueRoad.isChecked = NaviPrefs.cueRoadFeature.value
        b.swCueRoad.setOnCheckedChangeListener { _, on -> NaviPrefs.setCueRoadFeature(on) }

        b.swCueCurve.isChecked = NaviPrefs.cueCurve.value
        b.swCueCurve.setOnCheckedChangeListener { _, on -> NaviPrefs.setCueCurve(on) }

        b.swCueTerrain.isChecked = NaviPrefs.cueTerrain.value
        b.swCueTerrain.setOnCheckedChangeListener { _, on ->
            NaviPrefs.setCueTerrain(on)
            // 打开时顺便告诉用户设备到底支不支持：没有气压计却开着开关，
            // 会让人以为"功能坏了"，而实际是硬件没有这个传感器。
            if (on) warnIfNoBarometer()
        }

        b.swCueHarsh.isChecked = NaviPrefs.cueHarsh.value
        b.swCueHarsh.setOnCheckedChangeListener { _, on -> NaviPrefs.setCueHarsh(on) }

        // ---------- 屏幕与地图 ----------
        // 这几项对下一次进地图生效（地图页在 onCreate 时读一次设置）。
        // 不在这里重建已经打开的地图页：那样会让导航中的路线闪烁重绘。
        b.swTraffic.isChecked = NaviPrefs.mapTraffic.value
        b.swTraffic.setOnCheckedChangeListener { _, on -> NaviPrefs.setMapTraffic(on) }

        b.setMapTheme.setOnClickListener { pickMapTheme() }
        renderMapTheme()

        b.setOfflineMap.setOnClickListener {
            dismiss()
            com.motohud.ble.ui.navi.OfflineMapActivity.start(requireContext())
        }

        b.swMap3d.isChecked = NaviPrefs.map3DBuilding.value
        b.swMap3d.setOnCheckedChangeListener { _, on -> NaviPrefs.setMap3DBuilding(on) }

        b.swMapText.isChecked = NaviPrefs.mapText.value
        b.swMapText.setOnCheckedChangeListener { _, on -> NaviPrefs.setMapText(on) }

        b.swRouteReduce.isChecked = NaviPrefs.routeReduce.value
        b.swRouteReduce.setOnCheckedChangeListener { _, on -> NaviPrefs.setRouteReduce(on) }

        b.swScreenKeepOn.isChecked = NaviPrefs.screenKeepOn.value
        b.swScreenKeepOn.setOnCheckedChangeListener { _, on -> NaviPrefs.setScreenKeepOn(on) }

        b.setScreenBright.setOnClickListener { pickScreenBright() }
        renderScreenBright()

        // ---------- 路线与导航 ----------
        b.setRoutePrefs.setOnClickListener {
            // 偏好改完要重算路，但设置面板里没有"当前路线"的概念，
            // 所以只存偏好、不触发重算；下次算路自然生效。
            val d = RoutePrefsDialog()
            d.show(act.supportFragmentManager, "route_prefs")
            dismiss()
        }

        b.swAvoidFerry.isChecked = NaviPrefs.avoidFerry.value
        b.swAvoidFerry.setOnCheckedChangeListener { _, on -> NaviPrefs.setAvoidFerry(on) }

        b.setAvoidZone.setOnClickListener {
            dismiss()
            com.motohud.ble.ui.navi.AvoidZoneActivity.start(ctx)
        }
        b.setAmapKey.setOnClickListener {
            dismiss()
            com.motohud.ble.ui.navi.KeySetupDialog().show(act.supportFragmentManager, "key_setup")
        }
        b.setPrivacy.setOnClickListener {
            dismiss()
            act.openPrivacy()
        }

        // ---------- 数据与系统 ----------
        b.setCrashLog.setOnClickListener {
            dismiss()
            showCrashLog()
        }
        b.setClearCache.setOnClickListener {
            dismiss()
            act.openClearCache()
        }
        b.setOnboarding.setOnClickListener {
            dismiss()
            OnboardingActivity.start(ctx)
        }
        b.setAbout.setOnClickListener {
            dismiss()
            startActivity(Intent(ctx, AboutActivity::class.java))
        }

        b.setVoiceEngine.setOnClickListener {
            dismiss()
            com.motohud.ble.ui.navi.TtsModelActivity.start(ctx)
        }

        renderVoiceEngineState()
        renderCrashState()
    }

    // ---------- 内置语音音色 ----------

    /**
     * 内置语音包音色（预录 mp3 套装）。
     *
     * 只在没装离线模型时起作用 —— 装了模型走的是现场合成，音色由 TtsModelActivity
     * 里的「风格」决定。这里如实提示一句，别让用户以为换了没反应。
     */
    private fun pickPresetVoice() {
        val ctx = requireContext()
        val voices = com.motohud.ble.navi.tts.PresetVoice.ALL
        val labels = voices.map { it.name }.toTypedArray()
        val current = voices.indexOfFirst { it.id == NaviPrefs.presetVoice.value }.coerceAtLeast(0)
        val modelInstalled = com.motohud.ble.navi.tts.TtsModelStore.isInstalled(ctx)
        MotoDialog.builder(ctx)
            .setTitle(R.string.settings_preset_voice_title)
            .setMessage(
                if (modelInstalled) getString(R.string.tts_preset_voice_model_wins) else null
            )
            .setSingleChoiceItems(labels, current) { d, which ->
                NaviPrefs.setPresetVoice(voices[which].id)
                renderPresetVoice()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderPresetVoice() {
        b.tvPresetVoiceValue.text = com.motohud.ble.navi.tts.PresetVoice
            .of(NaviPrefs.presetVoice.value).name
    }

    // ---------- 地图配色 ----------

    /**
     * 三档：自动 / 日间 / 夜间。
     *
     * 原来是「深色地图」布尔开关 —— 手动档意味着跑一趟从白天到天黑，
     * 得中途停车进设置点一下。自动档按当地日出日落切换，不用管。
     */
    private fun pickMapTheme() {
        val modes = intArrayOf(NaviPrefs.THEME_AUTO, NaviPrefs.THEME_DAY, NaviPrefs.THEME_NIGHT)
        val labels = arrayOf(
            getString(R.string.settings_map_theme_auto),
            getString(R.string.settings_map_theme_day),
            getString(R.string.settings_map_theme_night)
        )
        val checked = modes.indexOf(NaviPrefs.mapTheme.value).coerceAtLeast(0)
        MotoDialog.builder(requireContext())
            .setTitle(R.string.settings_map_theme_title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                NaviPrefs.setMapTheme(modes[which])
                renderMapTheme()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderMapTheme() {
        b.tvMapThemeValue.text = when (NaviPrefs.mapTheme.value) {
            NaviPrefs.THEME_DAY -> getString(R.string.settings_map_theme_day)
            NaviPrefs.THEME_NIGHT -> getString(R.string.settings_map_theme_night)
            else -> getString(R.string.settings_map_theme_auto)
        }
    }

    // ---------- 导航亮度 ----------

    private fun pickScreenBright() {
        val modes = intArrayOf(
            NaviPrefs.BRIGHT_FOLLOW,
            NaviPrefs.BRIGHT_DIM,
            NaviPrefs.BRIGHT_NORMAL
        )
        val labels = arrayOf(
            getString(R.string.settings_bright_follow),
            getString(R.string.settings_bright_dim),
            getString(R.string.settings_bright_normal)
        )
        val checked = modes.indexOf(NaviPrefs.screenBright.value).coerceAtLeast(0)
        MotoDialog.builder(requireContext())
            .setTitle(R.string.settings_screen_bright_title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                NaviPrefs.setScreenBright(modes[which])
                renderScreenBright()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderScreenBright() {
        b.tvScreenBrightValue.text = when (NaviPrefs.screenBright.value) {
            NaviPrefs.BRIGHT_DIM -> getString(R.string.settings_bright_dim)
            NaviPrefs.BRIGHT_NORMAL -> getString(R.string.settings_bright_normal)
            else -> getString(R.string.settings_bright_follow)
        }
    }

    // ---------- 崩溃记录 ----------

    private fun renderCrashState() {
        val n = CrashHandler.list(requireContext()).size
        b.tvCrashState.text = if (n <= 0) getString(R.string.settings_crash_state_none)
        else getString(R.string.settings_crash_state, n)
    }

    /**
     * 崩溃记录：有记录就展示最近一次的摘要 + 导出/清空，没有就说清楚"没有"。
     *
     * 不单独做一个页面 —— 用户真正需要的是「把报告发出去」，
     * 而不是在手机上读一屏堆栈。
     */
    private fun showCrashLog() {
        val ctx = requireContext()
        val files = CrashHandler.list(ctx)
        val summary = CrashHandler.latestSummary(ctx)
        if (files.isEmpty() || summary == null) {
            MotoDialog.builder(ctx)
                .setTitle(R.string.crash_dialog_title)
                .setMessage(R.string.crash_dialog_none)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        MotoDialog.builder(ctx)
            .setTitle(R.string.crash_dialog_title)
            .setMessage(getString(R.string.crash_dialog_msg, summary, files.size))
            .setPositiveButton(R.string.crash_export) { _, _ -> exportCrash() }
            .setNeutralButton(R.string.crash_clear) { _, _ ->
                CrashHandler.clear(ctx)
                renderCrashState()
                Toast.makeText(ctx, R.string.crash_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 与蓝牙诊断导出走同一条路：写进 external-files/diagnostic/ 再交给系统分享面板 */
    private fun exportCrash() {
        val ctx = requireContext()
        val file = runCatching { CrashHandler.export(ctx) }.getOrNull()
        if (file == null) {
            Toast.makeText(ctx, R.string.crash_export_fail, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.crash_export))) }
            .onFailure {
                Toast.makeText(ctx, R.string.crash_export_fail, Toast.LENGTH_SHORT).show()
            }
    }

    /** 播报详细度三档：详细 / 简洁 / 只播转弯 */
    private fun pickVoiceMode() {
        val modes = intArrayOf(
            NaviPrefs.VOICE_MODE_DETAIL,
            NaviPrefs.VOICE_MODE_BRIEF,
            NaviPrefs.VOICE_MODE_TURN_ONLY
        )
        val labels = arrayOf(
            getString(R.string.navi_voice_mode_detail),
            getString(R.string.navi_voice_mode_brief),
            getString(R.string.navi_voice_mode_turn_only)
        )
        val checked = modes.indexOf(NaviPrefs.voiceMode.value).coerceAtLeast(0)
        MotoDialog.builder(requireContext())
            .setTitle(R.string.navi_voice_mode_title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                NaviPrefs.setVoiceMode(modes[which])
                renderVoiceMode()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderVoiceMode() {
        b.tvVoiceModeValue.text = getString(
            R.string.settings_voice_mode_value,
            when (NaviPrefs.voiceMode.value) {
                NaviPrefs.VOICE_MODE_BRIEF -> getString(R.string.settings_voice_mode_short_brief)
                NaviPrefs.VOICE_MODE_TURN_ONLY -> getString(R.string.settings_voice_mode_short_turn)
                else -> getString(R.string.settings_voice_mode_short_detail)
            }
        )
    }

    /**
     * 没有气压计时提醒一次。
     *
     * 坡度是唯一依赖**硬件传感器**的播报能力。设备没有 `TYPE_PRESSURE` 时
     * 整套逻辑静默关闭 —— 这是对的（不该报假坡度），但用户看着开关是开着的、
     * 却永远等不到提示，只会归因为"这功能没用"。
     */
    private fun warnIfNoBarometer() {
        val sense = com.motohud.ble.navi.TerrainSense(requireContext())
        if (sense.isAvailable()) return
        Toast.makeText(requireContext(), R.string.settings_cue_terrain_none, Toast.LENGTH_LONG).show()
    }

    /** 语音引擎装没装、占多少空间 —— 在设置列表里直接显示，省得点进去才知道 */
    private fun renderVoiceEngineState() {
        val installed = com.motohud.ble.navi.tts.TtsModelStore.isInstalled(requireContext())
        b.setVoiceEngineState.text = if (installed) {
            getString(
                R.string.tts_engine_state_on,
                com.motohud.ble.data.AppCache.human(
                    com.motohud.ble.navi.tts.TtsModelStore.installedBytes(requireContext())
                )
            )
        } else {
            getString(R.string.tts_engine_state_off)
        }
    }

    /**
     * 第三方导航转发开关的接线（已收起，保留备查）。
     *
     * 原实现开启时做三件事：总闸没开先提示 → 起前台服务 → 引导通知权限。
     * 收起原因与恢复方式见 `dialog_settings.xml` 内的注释。
     */
    @Suppress("unused")
    private fun setBridge(ctx: Context, on: Boolean) {
        NaviPrefs.setBridgeEnabled(on)
        if (!on) {
            NaviBridgeService.stop(ctx)
            return
        }
        if (!NaviPrefs.castEnabled.value) {
            Toast.makeText(ctx, R.string.bridge_cast_off, Toast.LENGTH_LONG).show()
        }
        NaviBridgeService.start(ctx)
        Toast.makeText(ctx, R.string.bridge_started, Toast.LENGTH_SHORT).show()
        if (!act.isNotificationAccessGranted()) promptBridgeNotifyAccess()
    }

    @Suppress("unused")
    private fun promptBridgeNotifyAccess() {
        MotoDialog.builder(requireContext())
            .setTitle(R.string.bridge_need_notify_title)
            .setMessage(R.string.bridge_need_notify_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.setting) { _, _ ->
                dismiss()
                act.promptNotificationAccess()
            }
            .show()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(): SettingsSheetDialog = SettingsSheetDialog()
    }
}
