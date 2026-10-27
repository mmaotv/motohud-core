package com.motohud.ble.ui.navi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.motohud.ble.R
import com.motohud.ble.data.AppDatabase
import com.motohud.ble.data.AvoidZoneEntity
import com.motohud.ble.data.AvoidZoneStore
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 避让区管理页。
 *
 * ## 这个页面存在的原因
 *
 * 「途经点」只保证**经过**某些点，两点之间怎么走仍由高德选 —— 所以用户
 * 在江两边各放一个点，中间可能被导上禁摩的那座桥。避让区才是把中间段
 * 锁死的那个手段：圈一块"不能进"的区域，算路会整体绕开。
 *
 * ## 为什么不做"自由拖多边形"
 *
 * 手机小屏上拖顶点极难操作，且高德对避让区大小很敏感（超过约 2km 见方
 * 常直接返回空路线）。所以改成「地图上选中心 + 调半径」，交互成本低得多，
 * 对"把一条路/一个商圈框住"这个诉求没有本质差别。
 */
class AvoidZoneActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    private val addLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_avoid_zone)

        val topBar = findViewById<View>(R.id.az_top_bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(8))
            // 底部按钮也要避开导航条，否则手势导航下会被吞掉一半
            findViewById<View>(R.id.az_add).updatePadding()
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            findViewById<View>(R.id.az_add).let { b ->
                (b.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                    if (lp.bottomMargin != bottom + dp(16)) {
                        lp.bottomMargin = bottom + dp(16)
                        b.layoutParams = lp
                    }
                }
            }
            insets
        }

        list = findViewById(R.id.az_list)
        empty = findViewById(R.id.az_empty)
        findViewById<View>(R.id.az_btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.az_add).setOnClickListener {
            addLauncher.launch(Intent(this, AvoidZoneEditActivity::class.java))
        }
        refresh()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun refresh() {
        lifecycleScope.launch {
            val all = runCatching {
                AppDatabase.get(this@AvoidZoneActivity).avoidZoneDao().allOnce()
            }.getOrDefault(emptyList())
            render(all)
        }
    }

    private fun render(zones: List<AvoidZoneEntity>) {
        list.removeAllViews()
        empty.visibility = if (zones.isEmpty()) View.VISIBLE else View.GONE
        zones.forEach { zone ->
            val row = layoutInflater.inflate(R.layout.item_avoid_zone, list, false)
            row.findViewById<TextView>(R.id.az_name).text = zone.name
            val poly = AvoidZoneStore.decode(zone.polygon)
            val (w, h) = AvoidZoneStore.sizeM(poly)
            row.findViewById<TextView>(R.id.az_sub).text = getString(
                R.string.avoid_zone_size_value,
                String.format(Locale.US, "%.0f", w),
                String.format(Locale.US, "%.0f", h)
            ) + " · " + if (zone.enabled) {
                getString(R.string.avoid_zone_on)
            } else {
                getString(R.string.avoid_zone_off)
            }
            val sw = row.findViewById<MaterialSwitch>(R.id.az_switch)
            sw.isChecked = zone.enabled
            sw.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    runCatching {
                        AppDatabase.get(this@AvoidZoneActivity).avoidZoneDao()
                            .update(zone.copy(enabled = checked))
                    }
                }
            }
            row.findViewById<View>(R.id.az_delete).setOnClickListener {
                com.motohud.ble.ui.common.MotoDialog.builder(this)
                    .setTitle(R.string.avoid_zone_delete_title)
                    .setMessage(getString(R.string.avoid_zone_delete_msg, zone.name))
                    .setPositiveButton(R.string.delete) { d, _ ->
                        lifecycleScope.launch {
                            runCatching {
                                AppDatabase.get(this@AvoidZoneActivity).avoidZoneDao().delete(zone)
                            }
                            refresh()
                        }
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            row.setOnClickListener {
                // 点击重新编辑（移动到新位置 / 改半径 / 改名）
                addLauncher.launch(
                    AvoidZoneEditActivity.intent(this, zone.id)
                )
            }
            list.addView(row)
        }
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, AvoidZoneActivity::class.java))
        }
    }
}
