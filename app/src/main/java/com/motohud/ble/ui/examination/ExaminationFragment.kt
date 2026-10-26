package com.motohud.ble.ui.examination

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.FaultRepository
import com.motohud.ble.databinding.FragmentExaminationBinding
import com.motohud.ble.ui.MainActivity
import com.motohud.ble.ui.MainViewModel
import kotlinx.coroutines.launch

/**
 * 体检页 —— CMD_A 开关位域 + CMD_E/F 故障码（复刻原 App 车辆体检：
 * ABS/OBD/电瓶/胎压/燃油/机油压力/保养/主控）。
 */
class ExaminationFragment : Fragment(R.layout.fragment_examination) {

    private var _binding: FragmentExaminationBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels {
        MainViewModel.Factory(requireActivity().application as MotoHudApp)
    }
    private lateinit var faultAdapter: FaultAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentExaminationBinding.bind(view)

        faultAdapter = FaultAdapter()
        binding.faultList.layoutManager = LinearLayoutManager(requireContext())
        binding.faultList.adapter = faultAdapter

        vm.examination.observe(viewLifecycleOwner) { exam ->
            // 开关位
            if (exam.switches.isEmpty()) {
                binding.switchContainer.text = getString(com.motohud.ble.R.string.exam_no_data)
            } else {
                binding.switchContainer.text = exam.switches.joinToString("\n") { s ->
                    "● ${s.name}：${if (s.on) "开" else "关"}"
                }
            }
            // 故障码
            val faults = listOfNotNull(exam.ecuFault, exam.obdFault)
            faultAdapter.submit(faults)
            binding.faultEmpty.visibility = if (faults.isEmpty()) View.VISIBLE else View.GONE
        }

        // 断连空态
        val ble = (requireActivity().application as MotoHudApp).bleManager
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ble.state.collect { st ->
                    val connected = st == BleConnectionManager.State.CONNECTED
                    with(binding.emptyState) {
                        root.visibility = if (connected) View.GONE else View.VISIBLE
                        emptyMessage.setText(R.string.empty_message_exam)
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
