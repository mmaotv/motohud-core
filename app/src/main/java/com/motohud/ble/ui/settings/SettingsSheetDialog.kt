package com.motohud.ble.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.data.device.DevicePrefs
import com.motohud.ble.databinding.DialogSettingsBinding
import com.motohud.ble.service.NaviForegroundService
import com.motohud.ble.ui.MainActivity
import com.motohud.ble.ui.OnboardingActivity
import com.motohud.ble.ui.about.AboutActivity
import com.motohud.ble.ui.navi.KeySetupDialog
import com.motohud.ble.ui.scan.ScanActivity

/**
 * 设置面板。
 *
 * 为什么从 `setItems` 文本列表改成底部面板：
 * 「自动连接车机 / 投屏车机 / 息屏导航 / 语音播报 / 路况图层」都是**开关**，
 * 文本列表只能表达"点了就跳走"。这些开关以前被迫散落在首页卡片、
 * 导航页对话框里，用户找不到也不知道彼此关系。
 *
 * 归集原则：策略类开关进设置，操作类（连接、开始导航）留在原处。
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        // ---------- 开关组 ----------
        b.swAutoConnect.isChecked = DevicePrefs.autoConnectEnabled(ctx)
        b.swAutoConnect.setOnCheckedChangeListener { _, on ->
            DevicePrefs.setAutoConnect(ctx, on)
        }

        b.swCast.isChecked = NaviPrefs.castEnabled.value
        b.swCast.setOnCheckedChangeListener { _, on -> NaviPrefs.setCastEnabled(on) }

        b.swScreenOff.isChecked = NaviPrefs.screenOffNavi.value
        b.swScreenOff.setOnCheckedChangeListener { _, on ->
            NaviPrefs.setScreenOffNavi(on)
            // 唤醒锁由服务内部按此开关决定，正在导航时要让它重新评估一次
            if (app.naviEngine.state.value.running) NaviForegroundService.start(ctx)
        }

        b.swVoice.isChecked = NaviPrefs.voice.value
        b.swVoice.setOnCheckedChangeListener { _, on -> NaviPrefs.setVoice(on) }

        b.swTraffic.isChecked = NaviPrefs.mapTraffic.value
        b.swTraffic.setOnCheckedChangeListener { _, on -> NaviPrefs.setMapTraffic(on) }

        // ---------- 跳转项 ----------
        b.setConnect.setOnClickListener {
            dismiss()
            ScanActivity.start(ctx)
        }
        b.setOnboarding.setOnClickListener {
            dismiss()
            OnboardingActivity.start(ctx)
        }
        b.setAmapKey.setOnClickListener {
            dismiss()
            KeySetupDialog().show(act.supportFragmentManager, "key_setup")
        }
        b.setPrivacy.setOnClickListener {
            dismiss()
            act.openPrivacy()
        }
        b.setClearCache.setOnClickListener {
            dismiss()
            act.openClearCache()
        }
        b.setAbout.setOnClickListener {
            dismiss()
            startActivity(Intent(ctx, AboutActivity::class.java))
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(): SettingsSheetDialog = SettingsSheetDialog()
    }
}
