package com.motohud.ble.ui.garage

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.motohud.ble.R
import com.motohud.ble.data.bike.BikeBrand
import com.motohud.ble.data.bike.BikeCatalog
import com.motohud.ble.data.bike.BikePrefs
import com.motohud.ble.databinding.DialogBikePickerBinding
import com.motohud.ble.ui.theme.themeColor
import com.motohud.ble.ui.theme.AppTheme

/**
 * 车型选择弹窗：顶部品牌筹码 + 下方车型列表（带缩略图）。
 *
 * 注意：这里走 onCreateDialog 自建 Dialog，DialogFragment 不会回调 onViewCreated，
 * 所以视图构建全部放在 onCreateDialog 里完成。
 */
class BikePickerDialog : DialogFragment() {

    private lateinit var binding: DialogBikePickerBinding
    private lateinit var adapter: BikeOptionAdapter
    private var filter: BikeBrand? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Dialog 主题不继承 Activity，白天要显式选一份（见 AppTheme.dialogTheme）
        val dialog = Dialog(requireContext(), AppTheme.dialogTheme())
        binding = DialogBikePickerBinding.inflate(layoutInflater)
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_solid)
        val dm = resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        adapter = BikeOptionAdapter(
            scope = lifecycleScope,
            selectedId = BikePrefs.current.value.id
        ) { model ->
            BikePrefs.select(requireContext(), model)
            dismiss()
        }
        binding.rvBikes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBikes.adapter = adapter

        buildChips()
        applyFilter(null)
        return dialog
    }

    private fun buildChips() {
        binding.chipRow.removeAllViews()
        val entries = listOf<Pair<String, BikeBrand?>>(
            getString(R.string.garage_picker_all) to null
        ) + BikeBrand.values().map { it.display to it }

        entries.forEachIndexed { index, (label, brand) ->
            val selected = brand == filter
            val chip = TextView(requireContext()).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setTextColor(
                    requireContext().themeColor(
                        if (selected) R.attr.mtTextOnAccent else R.attr.mtTextSecondary,
                        android.graphics.Color.WHITE
                    )
                )
                setBackgroundResource(
                    if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
                )
                setOnClickListener {
                    filter = brand
                    buildChips()
                    applyFilter(brand)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) marginStart = dp(8) }
            binding.chipRow.addView(chip, lp)
        }
    }

    private fun applyFilter(brand: BikeBrand?) {
        adapter.submit(BikeCatalog.byBrand(brand))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
