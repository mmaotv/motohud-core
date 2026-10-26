package com.motohud.ble.ui.dashboard

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.databinding.FragmentDashboardBinding
import com.motohud.ble.ui.MainActivity
import com.motohud.ble.ui.MainViewModel
import kotlinx.coroutines.launch

/**
 * 仪表页 —— 展示 CMD_B/C/D 实时数据（复刻 RidingFragment.displayData 的单位与格式化）。
 */
class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels {
        MainViewModel.Factory(requireActivity().application as MotoHudApp)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDashboardBinding.bind(view)

        vm.telemetry.observe(viewLifecycleOwner) { t ->
            // 速度（原 App show_speed = %1$dkm/h）
            binding.tvSpeed.text = t.speed?.let { getString(R.string.unit_speed, it) } ?: "--"
            // 转速（原 App str_turn_speed_unit）
            binding.tvRpm.text = t.rpm?.let { getString(R.string.unit_rpm, it) } ?: "--"
            // 瞬时油耗（原 App：fffe 或空显示 --.-）
            binding.tvFuel.text = t.fuel?.let { getString(R.string.unit_fuel, it) } ?: "--.-"
            // 水温（原 App：0 显示 <45）
            binding.tvWaterTemp.text = when (t.waterTemp) {
                null -> "--"
                0 -> getString(R.string.water_temp_low)
                else -> getString(R.string.unit_temp, t.waterTemp)
            }
            // 环境温度
            binding.tvRoomTemp.text = t.roomTemp?.let { "${it}℃" } ?: "--"
            // 油量
            binding.tvOil.text = t.oil?.let { getString(R.string.unit_percent, it) } ?: "--"
            // 电瓶电压
            binding.tvBattery.text = t.battery?.let { getString(R.string.unit_battery, it) } ?: "--"
            // 里程
            binding.tvMileage.text = t.totalMileage?.toString() ?: "0"
            binding.tvMaxSpeed.text = t.maxSpeed?.toString() ?: "--"
            binding.tvAvgSpeed.text = t.avgSpeed?.toString() ?: "--"
        }

        // 断连空态：未连接时覆盖内容并引导去连接
        val ble = (requireActivity().application as MotoHudApp).bleManager
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ble.state.collect { st ->
                    val connected = st == BleConnectionManager.State.CONNECTED
                    with(binding.emptyState) {
                        root.visibility = if (connected) View.GONE else View.VISIBLE
                        emptyMessage.setText(R.string.empty_message_dashboard)
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
