package com.motohud.ble.ui.garage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.bike.BikeModel
import com.motohud.ble.data.bike.BikePhotoStore
import com.motohud.ble.data.bike.BikePrefs
import com.motohud.ble.data.bike.BikeSpec
import com.motohud.ble.data.device.DevicePrefs
import com.motohud.ble.databinding.FragmentGarageBinding
import com.motohud.ble.ui.HostActivity
import com.motohud.ble.ui.MainActivity
import com.motohud.ble.ui.MainViewModel
import com.motohud.ble.ui.common.MotoDialog
import com.motohud.ble.ui.scan.ScanActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 爱车页 —— 首页。
 *
 *  - 上半屏：车型大图 + 场景光晕 + 品牌/车型名（参考九号、小牛等车辆 App 的首页构图）
 *  - 中部：油量 / 电压 / 水温 / 总里程 四个关键数据 + 功能宫格
 *  - 下部：车辆参数与亮点标签，可「换车」切换车型
 *
 * 原来分散在多个页签的信息在这里聚合：仪表、体检、指令、导航、检索、连接都能一键直达。
 */
class GarageFragment : Fragment(R.layout.fragment_garage) {

    private var _binding: FragmentGarageBinding? = null
    private val binding get() = _binding!!

    private val vm: MainViewModel by activityViewModels {
        MainViewModel.Factory(requireActivity().application as MotoHudApp)
    }

    private val app get() = requireActivity().application as MotoHudApp
    private var currentBike: BikeModel? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGarageBinding.bind(view)

        binding.btnChangeBike.setOnClickListener { showPicker() }
        binding.hero.setOnClickListener { showPhotoMenu() }

        // 首页只保留这两个工具入口（原「功能」板块两个大方块 → Hero 右上角图标）
        binding.ivSearch.setOnClickListener {
            // 标题留空：检索页自身有标题，避免顶部重复
            HostActivity.start(requireContext(), "", HostActivity.PAGE_SEARCH)
        }
        binding.ivSettings.setOnClickListener {
            (requireActivity() as? MainActivity)?.showSettings()
        }

        observeBike()
        observeConnection()
        observeTelemetry()
        setupDeviceCard()
    }

    // ---------- 车机连接卡 ----------
    // 连接页已从底部页签降级为二级页，这里是它的主入口。
    // 一个用户只有一台车机，所以卡片只记一台，换车机走「更换车机」。

    private fun setupDeviceCard() {
        binding.cardDeviceAction.setOnClickListener { onDeviceAction() }
        binding.cardChangeDevice.setOnClickListener {
            ScanActivity.start(requireContext())
        }
        // 未连过车机时卡片本身也可点（等同于「连接车机」）
        binding.cardDevice.setOnClickListener {
            val st = app.bleManager.state.value
            if (st != BleConnectionManager.State.CONNECTED && !DevicePrefs.hasSaved(requireContext())) {
                ScanActivity.start(requireContext())
            }
        }
        binding.cardDeviceName.setOnClickListener { onDeviceAction() }

        renderDeviceCard(app.bleManager.state.value)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    DevicePrefs.saved.collect { renderDeviceCard(app.bleManager.state.value) }
                }
            }
        }

        showAutoConnectHintOnce()
    }

    /** 三态渲染：已连接 / 有存档未连上 / 从未连过 */
    private fun renderDeviceCard(state: BleConnectionManager.State) {
        val saved = DevicePrefs.saved.value
        val connecting =
            state == BleConnectionManager.State.CONNECTING || state == BleConnectionManager.State.RECONNECTING
        val connected = state == BleConnectionManager.State.CONNECTED

        binding.cardDot.setBackgroundResource(
            when {
                connected -> R.drawable.dot_green
                connecting -> R.drawable.dot_orange
                else -> R.drawable.dot_gray
            }
        )

        when {
            connected -> {
                val name = app.bleManager.connectedDeviceName
                    ?: saved?.second?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.banner_device_fallback)
                binding.cardDeviceName.text = name
                binding.cardDeviceAction.setText(R.string.device_card_disconnect)
                val ts = DevicePrefs.lastConnectedAt.value
                binding.cardDeviceSub.text = if (ts > 0L) {
                    getString(R.string.device_card_last_seen, relativeTime(ts))
                } else {
                    getString(R.string.device_card_connected_hint)
                }
            }
            connecting -> {
                binding.cardDeviceName.text =
                    saved?.second?.takeIf { it.isNotBlank() } ?: getString(R.string.banner_device_fallback)
                binding.cardDeviceAction.setText(R.string.device_card_connecting)
                binding.cardDeviceSub.setText(R.string.device_card_hint)
            }
            saved != null -> {
                binding.cardDeviceName.text =
                    saved.second.takeIf { it.isNotBlank() } ?: getString(R.string.banner_device_fallback)
                binding.cardDeviceAction.setText(R.string.auto_connect_retry)
                binding.cardDeviceSub.setText(R.string.device_card_hint)
            }
            else -> {
                binding.cardDeviceName.setText(R.string.device_card_not_connected)
                binding.cardDeviceAction.setText(R.string.device_card_connect)
                binding.cardDeviceSub.setText(R.string.device_card_hint_new)
            }
        }
    }

    /** 按钮语义随状态变化：断开 / 重连 / 去连接页 */
    private fun onDeviceAction() {
        val ble = app.bleManager
        val ctx = requireContext()
        when (ble.state.value) {
            BleConnectionManager.State.CONNECTED -> {
                MotoDialog.builder(ctx)
                    .setTitle(R.string.device_card_disconnect)
                    .setMessage(R.string.device_card_disconnect_msg)
                    .setPositiveButton(R.string.device_card_disconnect) { _, _ ->
                        ble.disconnect()
                        Toast.makeText(ctx, R.string.device_card_disconnected, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            BleConnectionManager.State.CONNECTING,
            BleConnectionManager.State.RECONNECTING -> Unit
            else -> {
                val saved = DevicePrefs.saved.value
                if (saved == null) {
                    ScanActivity.start(ctx)
                    return
                }
                // 有存档：就地重连，失败再引导去连接页（不打断用户）
                // 不弹"正在连接"：卡片按钮已经变成「连接中…」，说了两遍
                ble.connectSavedOrScan(saved.first, saved.second) { ok ->
                    if (!ok) {
                        _binding ?: return@connectSavedOrScan
                        Toast.makeText(ctx, R.string.auto_connect_failed, Toast.LENGTH_LONG).show()
                        if (isAdded) ScanActivity.start(requireContext())
                    }
                }
            }
        }
    }

    /** 首次连上后提示一次「以后打开自动连接」，只提示一次 */
    private fun showAutoConnectHintOnce() {
        val ctx = requireContext()
        if (!DevicePrefs.hasSaved(ctx)) return
        if (DevicePrefs.hintShown(ctx)) return
        if (app.bleManager.state.value != BleConnectionManager.State.CONNECTED) return
        DevicePrefs.markHintShown(ctx)
        MotoDialog.builder(ctx)
            .setTitle(R.string.auto_hint_title)
            .setMessage(R.string.auto_hint_message)
            .setPositiveButton(R.string.auto_hint_ok, null)
            .show()
    }

    private fun relativeTime(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000L -> getString(R.string.device_card_just_now)
            diff < 3_600_000L -> getString(R.string.device_card_min_ago, diff / 60_000L)
            diff < 86_400_000L -> getString(R.string.device_card_hour_ago, diff / 3_600_000L)
            else -> getString(R.string.device_card_day_ago, diff / 86_400_000L)
        }
    }

    // ---------- 车型 ----------

    private fun observeBike() {
        renderBike(BikePrefs.current.value)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                BikePrefs.current.collect { renderBike(it) }
            }
        }
    }

    private fun renderBike(bike: BikeModel) {
        currentBike = bike
        binding.tvBrand.text = bike.brand.display
        binding.tvBrand.setTextColor(bike.accent.toInt())
        binding.tvModel.text = bike.name
        binding.tvSubtitle.text = bike.subtitle
        loadBikeImage(bike)

        // 亮点标签
        binding.highlightRow.removeAllViews()
        bike.highlights.forEachIndexed { index, text ->
            val tv = TextView(requireContext(), null, 0, R.style.Garage_Highlight).apply {
                this.text = text
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(26)
            ).apply { if (index > 0) marginStart = dp(6) }
            binding.highlightRow.addView(tv, lp)
        }

        renderSpecs(bike.specs)
    }

    private fun renderSpecs(specs: List<BikeSpec>) {
        binding.specContainer.removeAllViews()
        specs.forEach { spec ->
            val row = LinearLayout(requireContext(), null, 0, R.style.Garage_Spec_Row)
            val label = TextView(requireContext(), null, 0, R.style.Garage_Spec_Label).apply {
                text = spec.label
            }
            val value = TextView(requireContext(), null, 0, R.style.Garage_Spec_Value).apply {
                text = spec.value
            }
            // style 里的 layout_* 不会被解析，布局参数必须显式给
            row.addView(
                label,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                value,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            binding.specContainer.addView(row)
        }
    }

    private fun showPicker() {
        BikePickerDialog().show(childFragmentManager, "bike_picker")
    }

    // ---------- 自定义车照 ----------

    /**
     * 相册选图回调：拿到 URI 后做降采样 + 居中裁切再落盘，
     * 显示侧配合 CENTER_CROP，任何比例的原图都不会被拉伸。
     */
    private val pickBikePhoto =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val bike = currentBike ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = BikePhotoStore.save(requireContext(), bike.id, uri)
                if (ok) {
                    Toast.makeText(
                        requireContext(), R.string.garage_photo_saved, Toast.LENGTH_SHORT
                    ).show()
                    renderBike(BikePrefs.current.value)
                } else {
                    Toast.makeText(
                        requireContext(), R.string.garage_photo_failed, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    /** 点击车型大图：选车照 / 恢复默认 / 换车（换车按钮仍单独保留） */
    private fun showPhotoMenu() {
        val bike = currentBike ?: return
        val hasCustom = BikePhotoStore.hasPhoto(requireContext(), bike.id)
        val items = buildList {
            add(getString(R.string.garage_photo_pick))
            if (hasCustom) add(getString(R.string.garage_photo_reset))
            add(getString(R.string.garage_photo_change_bike))
        }
        MotoDialog.builder(requireContext())
            .setTitle(R.string.garage_photo_menu_title)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> pickBikePhoto.launch("image/*")
                    1 -> if (hasCustom) resetBikePhoto() else showPicker()
                    else -> showPicker()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resetBikePhoto() {
        val bike = currentBike ?: return
        BikePhotoStore.delete(requireContext(), bike.id)
        Toast.makeText(requireContext(), R.string.garage_photo_reset_done, Toast.LENGTH_SHORT).show()
        renderBike(BikePrefs.current.value)
    }

    /**
     * 优先显示用户自定义车照，否则回退到内置车型图。
     * - 自定义车照：CENTER_CROP 铺满整个 Hero 框（padding 归零），任何比例都不留边缘空白。
     * - 内置车型图：保留上下留白 + FIT_CENTER，配合光晕/底部渐隐构图居中显示。
     */
    private fun loadBikeImage(bike: BikeModel) {
        val iv = binding.ivBike
        val density = resources.displayMetrics.density
        viewLifecycleOwner.lifecycleScope.launch {
            val w = iv.width.takeIf { it > 0 } ?: iv.rootView.width
            val bmp = BikePhotoStore.load(requireContext(), bike.id, w)
            _binding ?: return@launch
            if (bmp != null) {
                iv.setPadding(0, 0, 0, 0)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageBitmap(bmp)
            } else {
                iv.setPadding(0, (6 * density).toInt(), 0, (34 * density).toInt())
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                val asset = withContext(Dispatchers.IO) { decodeAsset(bike.assetPath, 2) }
                _binding ?: return@launch
                if (asset != null) iv.setImageBitmap(asset)
            }
        }
    }

    private fun decodeAsset(path: String, sample: Int): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        requireContext().assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    // ---------- 连接状态 ----------

    private fun observeConnection() {
        val ble = app.bleManager
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ble.state.collect { st ->
                    renderStatus(st)
                    renderDeviceCard(st)
                    if (st == BleConnectionManager.State.CONNECTED) {
                        tryAutoMatch(ble.connectedDeviceName)
                        showAutoConnectHintOnce()
                    }
                }
            }
        }
        renderStatus(ble.state.value)
    }

    private fun renderStatus(state: BleConnectionManager.State) {
        val connected = state == BleConnectionManager.State.CONNECTED
        binding.statusDot.setBackgroundResource(
            when (state) {
                BleConnectionManager.State.CONNECTED -> R.drawable.dot_green
                BleConnectionManager.State.CONNECTING,
                BleConnectionManager.State.RECONNECTING -> R.drawable.dot_orange
                else -> R.drawable.dot_gray
            }
        )
        binding.tvStatus.setText(
            if (connected) R.string.garage_status_connected else R.string.status_disconnected
        )
    }

    /** 首次连接车机时按设备名猜一次车型 */
    private fun tryAutoMatch(deviceName: String?) {
        val matched = BikePrefs.autoMatchOnce(requireContext(), deviceName) ?: return
        Toast.makeText(
            requireContext(),
            getString(R.string.garage_auto_matched, matched.name),
            Toast.LENGTH_LONG
        ).show()
    }

    // ---------- 实时数据 ----------

    /**
     * 关键数据块。
     *
     * 车机侧没有遥测上行帧（实测确认），这三格在真机上永远是 "--"，
     * 所以整块默认 GONE：**只有真的拿到数据才显示**。
     * 这样首页不会用一排空框占掉一整屏，接上 OBD 后又会自动长出来。
     */
    private fun observeTelemetry() {
        vm.telemetry.observe(viewLifecycleOwner) { t ->
            val hasAny = t.oil != null || t.battery != null || t.totalMileage != null
            binding.metricRow.visibility = if (hasAny) View.VISIBLE else View.GONE
            if (!hasAny) return@observe
            binding.tvOil.text = t.oil?.let { "$it%" } ?: getString(R.string.garage_value_empty)
            binding.tvBattery.text = t.battery?.let { String.format(Locale.US, "%.1fV", it) }
                ?: getString(R.string.garage_value_empty)
            binding.tvMileage.text = t.totalMileage?.let { "$it" }
                ?: getString(R.string.garage_value_empty)
        }
    }

    // ---------- 工具 ----------

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
