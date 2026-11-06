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
import com.motohud.ble.obd.ObdClient
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

    private var bleConnected = false
    private val ble get() = (requireActivity().application as MotoHudApp).bleManager
    private val obd get() = (requireActivity().application as MotoHudApp).obdClient

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
            // 故障码：车机侧 CMD_E/F 的索引码 + OBD 实读码（03/07/0A）合并显示
            val faults = listOfNotNull(exam.ecuFault, exam.obdFault) + exam.dtcs
            faultAdapter.submit(faults)
            binding.faultEmpty.visibility = if (faults.isEmpty()) View.VISIBLE else View.GONE
        }

        // 断连空态：车机、OBD 两条链路任意一条活着就不该挡页面 ——
        // 只插诊断头、不开车机时，故障码照样要能读（这是 OBD 模块的主要用途）。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ble.state.collect { st ->
                    bleConnected = st == BleConnectionManager.State.CONNECTED
                    applyEmptyState()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                obd.phase.collect { p ->
                    val ready = p == ObdClient.Phase.READY
                    binding.examObdRead.visibility = if (ready) View.VISIBLE else View.GONE
                    val name = obd.connectedName.value.orEmpty()
                    binding.examObdState.text = if (ready) {
                        getString(R.string.exam_obd_ready, name.ifBlank { "OBD" })
                    } else {
                        getString(R.string.exam_obd_off)
                    }
                    applyEmptyState()
                }
            }
        }
        binding.examObdRead.setOnClickListener { vm.readObdDtcs() }
    }

    private fun applyEmptyState() {
        val obdReady = obd.phase.value == ObdClient.Phase.READY
        with(binding.emptyState) {
            root.visibility = if (bleConnected || obdReady) View.GONE else View.VISIBLE
            emptyMessage.setText(R.string.empty_message_exam)
            emptyAction.visibility = if (bleConnected) View.GONE else View.VISIBLE
            emptyAction.setOnClickListener {
                com.motohud.ble.ui.scan.ScanActivity.start(requireContext())
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
