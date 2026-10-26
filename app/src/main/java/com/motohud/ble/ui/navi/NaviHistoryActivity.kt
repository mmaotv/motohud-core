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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.data.NaviHistory
import com.motohud.ble.data.NaviHistoryEntity
import com.motohud.ble.ui.common.MotoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 导航历史。
 *
 * 展示每次导航的：目的地、出发地、起止时间、用时、距离，
 * 以及**该次导航结束时车机的状态**（设备名 / 电压 / 水温 / 油量 / 是否投屏）。
 *
 * 数据只存在本机 Room，不上传、不共享，只有用户自己能看。
 */
class NaviHistoryActivity : AppCompatActivity() {

    private lateinit var app: MotoHudApp
    private lateinit var adapter: NaviHistoryAdapter
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var summary: TextView

    /** 时间范围筛选；null = 全部 */
    private var rangeMs: Long? = null

    /** 全部记录（用于统计条） */
    private var all: List<NaviHistoryEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navi_history)
        app = application as MotoHudApp

        val topBar = findViewById<View>(R.id.nh_top_bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(8))
            insets
        }

        list = findViewById(R.id.nh_list)
        empty = findViewById(R.id.nh_empty)
        summary = findViewById(R.id.nh_summary)

        adapter = NaviHistoryAdapter(
            onClick = { item -> NaviHistoryDetailActivity.start(this, item.id) },
            onLongClick = { item -> confirmDelete(item) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.nh_btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.nh_btn_clear).setOnClickListener { confirmClear() }

        buildRangeChips()

        // 回页面时刷新（可能刚结束一次导航）
        onResume()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 时间范围筛选 ----------

    private fun buildRangeChips() {
        val row = findViewById<LinearLayout>(R.id.nh_range_row)
        row.removeAllViews()

        data class Range(val labelRes: Int, val ms: Long?)
        val ranges = listOf(
            Range(R.string.navi_history_range_today, startOfToday()),
            Range(R.string.navi_history_range_7d, TimeUnit.DAYS.toMillis(7)),
            Range(R.string.navi_history_range_30d, TimeUnit.DAYS.toMillis(30)),
            Range(R.string.navi_history_range_all, null)
        )

        ranges.forEachIndexed { index, r ->
            val tv = TextView(this).apply {
                text = getString(r.labelRes)
                textSize = 13f
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        this@NaviHistoryActivity,
                        if (index == 0) R.color.accent else R.color.text_secondary
                    )
                )
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundResource(R.drawable.bg_chip)
                setOnClickListener {
                    rangeMs = r.ms
                    highlightChips(row, index)
                    load()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) lp.marginStart = dp(8)
            row.addView(tv, lp)
        }
    }

    private fun highlightChips(row: LinearLayout, selected: Int) {
        for (i in 0 until row.childCount) {
            val tv = row.getChildAt(i) as? TextView ?: continue
            tv.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this,
                    if (i == selected) R.color.accent else R.color.text_secondary
                )
            )
        }
    }

    /** 今天的零点时间戳 */
    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------- 数据 ----------

    private fun load() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val dao = app.naviHistoryDao
                val all = dao.listAll()
                val since = rangeMs
                if (since == null) all else all.filter { it.finishedAt >= since }
            }
            all = rows
            adapter.submit(rows)
            val emptyNow = rows.isEmpty()
            list.visibility = if (emptyNow) View.GONE else View.VISIBLE
            empty.visibility = if (emptyNow) View.VISIBLE else View.GONE
            summary.text = buildSummary(rows)
        }
    }

    private fun buildSummary(rows: List<NaviHistoryEntity>): String {
        if (rows.isEmpty()) return ""
        val totalMs = rows.sumOf { it.durationMs }
        val avgMs = totalMs / rows.size
        return getString(
            R.string.navi_history_summary,
            rows.size,
            NaviHistoryAdapter.formatDuration(this, totalMs),
            NaviHistoryAdapter.formatDuration(this, avgMs)
        )
    }

    // ---------- 删除 ----------

    private fun confirmDelete(item: NaviHistoryEntity) {
        MotoDialog.builder(this)
            .setTitle(R.string.navi_history_entry)
            .setMessage(item.destName + "\n" + NaviHistoryAdapter.formatDuration(this, item.durationMs))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                NaviHistory.delete(app, item.id)
                load()
            }
            .show()
    }

    private fun confirmClear() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { app.naviHistoryDao.count() }
            if (count <= 0) return@launch
            MotoDialog.builder(this@NaviHistoryActivity)
                .setTitle(R.string.navi_history_clear_confirm_title)
                .setMessage(getString(R.string.navi_history_clear_confirm, count))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    NaviHistory.clear(app)
                    load()
                }
                .show()
        }
    }

    companion object {
        /** 统一入口 */
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, NaviHistoryActivity::class.java))
        }
    }
}
