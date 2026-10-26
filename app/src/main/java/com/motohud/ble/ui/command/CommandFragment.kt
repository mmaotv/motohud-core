package com.motohud.ble.ui.command

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.ble.BleConstants
import com.motohud.ble.databinding.FragmentCommandBinding
import com.motohud.ble.ui.MainActivity
import com.motohud.ble.ui.MainViewModel
import kotlinx.coroutines.launch

/**
 * 指令页 —— 按设备类型（SR/BYQ）发送导航指令（复刻 NormalMotorFragment 指令集），
 * 支持自定义文本与通知转发测试。
 */
class CommandFragment : Fragment(R.layout.fragment_command) {

    private var _binding: FragmentCommandBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels {
        MainViewModel.Factory(requireActivity().application as MotoHudApp)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentCommandBinding.bind(view)

        // 设备类型提示
        vm.connectionState.observe(viewLifecycleOwner) { state ->
            val type = BleConstants.deviceTypeOf(vm.ble.connectedDeviceName)
            binding.tvDeviceType.text = when (type) {
                BleConstants.DeviceType.SR -> "当前设备：SR（使用 REM/NEXT/NAVI 码表）"
                BleConstants.DeviceType.BYQ -> "当前设备：BYQ（使用 NA/RE/NE 码表）"
                else -> "当前设备：未连接或类型未知"
            }
            val enabled = state.name == "CONNECTED"
            listOf(
                binding.btnHello, binding.btnNavi, binding.btnFinish,
                binding.btnSendCustom, binding.btnMsgTest
            ).forEach { it.isEnabled = enabled }
        }

        // 转向图标选择（高德图标类型 0~19）
        val icons = (0..19).map { "图标 $it" }
        binding.spinnerIcon.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, icons
        )

        // 距离滑块
        binding.seekPathRemain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvPathRemain.text = "${progress * 10} m"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.seekStepRemain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvStepRemain.text = "${progress * 10} m"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.btnHello.setOnClickListener { vm.sendHello() }
        binding.btnNavi.setOnClickListener {
            vm.sendNavigation(
                iconType = binding.spinnerIcon.selectedItemPosition,
                pathRemain = binding.seekPathRemain.progress * 10,
                stepRemain = binding.seekStepRemain.progress * 10
            )
        }
        binding.btnFinish.setOnClickListener { vm.sendFinish() }
        binding.btnSendCustom.setOnClickListener {
            val text = binding.etCustomCommand.text?.toString()?.trim()
            if (text.isNullOrEmpty()) {
                Toast.makeText(requireContext(), R.string.command_empty, Toast.LENGTH_SHORT).show()
            } else {
                vm.sendRaw(text)
            }
        }
        binding.btnMsgTest.setOnClickListener {
            vm.sendMsg(BleConstants.TYPE_SR_WECHAT, 1)
        }

        // 断连空态（与顶部状态条一致：未连接时覆盖并引导去连接）
        val ble = (requireActivity().application as MotoHudApp).bleManager
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ble.state.collect { st ->
                    val connected = st == BleConnectionManager.State.CONNECTED
                    with(binding.emptyState) {
                        root.visibility = if (connected) View.GONE else View.VISIBLE
                        emptyMessage.setText(R.string.empty_message_command)
                        emptyAction.visibility = if (connected) View.GONE else View.VISIBLE
                        emptyAction.setOnClickListener {
                            com.motohud.ble.ui.scan.ScanActivity.start(requireContext())
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
