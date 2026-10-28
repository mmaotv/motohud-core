package com.motohud.ble.ui.navi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.amap.AmapRepo
import com.motohud.ble.amap.TipItem
import com.motohud.ble.data.NaviPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 地点搜索选点页（起点 / 途经点共用）。
 *
 * 为什么单开一个页而不是复用导航主页的搜索：
 *  - 主页搜索是"边看地图边搜"，结果落在主页的地点卡上，流程是「搜 → 看 → 导航」；
 *  - 这里是「选一个点交给路线页」，结果直接回传坐标，流程是「搜 → 选 → 回路线页」。
 *    两个场景的收尾动作完全不同，硬塞在一起反而乱。
 *
 * 地理中心：优先用调用方传入的起点（让途经点搜索围绕路线起点），否则当前定位，
 * 再否则地图最后视野。所有搜索都以该中心画地理围栏 —— 复用 [AmapRepo.searchPoi] 的
 * `center` 参数，结果自然收敛到附近，不会再出现"跑到北京"。
 */
class PlaceSearchActivity : AppCompatActivity() {

    private lateinit var et: EditText
    private lateinit var clear: ImageView
    private lateinit var state: TextView
    private lateinit var list: androidx.recyclerview.widget.RecyclerView

    private val repo by lazy { AmapRepo(applicationContext) }
    private val scope get() = lifecycleScope
    private var searchJob: Job? = null
    private var debounce: Job? = null

    /** 搜索地理中心：[lat, lng] */
    private var center: Pair<Double, Double>? = null

    private lateinit var adapter: TipAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 高德隐私合规必须早于任何搜索 SDK 调用
        val ready = AmapManager.hasKey(this) && AmapManager.isPrivacyAgreed(this)
        setContentView(R.layout.activity_place_search)

        et = findViewById(R.id.ps_et)
        clear = findViewById(R.id.ps_clear)
        state = findViewById(R.id.ps_state)
        list = findViewById(R.id.ps_list)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.place_search_title) }
        // 用标题占位（Activity 标题在 head-unit 主题下不显示，这里用状态行提示搜索范围）

        // 初始中心：调用方给的起点 > 地图视野 > 暂无（之后会尝试定位补）
        val inLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val inLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        center = if (inLat != 0.0 && inLng != 0.0) inLat to inLng
        else NaviPrefs.mapCamera()?.let { it.lat to it.lng }

        // 没拿到传入中心时，异步补一次实时定位，让搜索围绕"我"展开
        if (center == null) {
            scope.launch {
                runCatching {
                    com.motohud.ble.amap.AmapLocator(applicationContext).locateOnce(6000)
                }.getOrNull()?.let { fix ->
                    if (center == null) center = fix.lat to fix.lng
                }
            }
        }

        adapter = TipAdapter(
            originProvider = { center },
            onClick = { tip -> pick(tip) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.ps_back).setOnClickListener { finish() }

        clear.setOnClickListener { et.setText("") }

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                clear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                searchJob?.cancel()
                debounce?.cancel()
                if (text.isBlank()) {
                    adapter.submitList(emptyList())
                    state.visibility = View.GONE
                    return
                }
                // 输入中：450ms 防抖 + Inputtips 实时联想（带地理偏置）
                debounce = scope.launch {
                    delay(450)
                    runSearch(text.trim(), live = true)
                }
            }
        })

        et.setOnEditorActionListener { _, actionId, event ->
            val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isSearch) {
                val t = et.text?.toString().orEmpty().trim()
                if (t.isNotBlank()) {
                    searchJob?.cancel()
                    debounce?.cancel()
                    runSearch(t, live = false)
                }
                true
            } else false
        }

        et.requestFocus()
    }

    /**
     * 发起一次搜索。
     * @param live true=输入中联想（Inputtips，轻量）；false=回车结果（PoiSearch，带地理围栏）
     */
    private fun runSearch(keyword: String, live: Boolean) {
        if (!AmapManager.initSearch(this)) {
            showState(getString(R.string.route_select_no_map))
            return
        }
        state.visibility = View.VISIBLE
        state.text = getString(R.string.place_search_loading)
        searchJob = scope.launch {
            val c = center
            val result = if (live) {
                repo.searchTips(keyword, "", c, c)
            } else {
                // 回车用 PoiSearch 并画 30km 地理围栏，结果锁定在中心附近
                repo.searchPoi(keyword, "", c, c, 30_000)
            }
            when (result) {
                is com.motohud.ble.amap.TipsResult.Ok -> {
                    val items = result.items
                    adapter.submitList(items)
                    if (items.isEmpty()) {
                        showState(getString(R.string.place_search_empty))
                    } else {
                        state.visibility = View.GONE
                    }
                }
                is com.motohud.ble.amap.TipsResult.Err -> {
                    adapter.submitList(emptyList())
                    showState(getString(R.string.navi_tips_error))
                }
                null -> showState(getString(R.string.navi_tips_error))
            }
        }
    }

    private fun showState(text: String) {
        state.visibility = View.VISIBLE
        state.text = text
    }

    /** 选中一个地点 → 回传名称与坐标，结束本页 */
    private fun pick(tip: TipItem) {
        if (tip.latitude == 0.0 && tip.longitude == 0.0) return
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_NAME, tip.name)
                putExtra(EXTRA_LAT, tip.latitude)
                putExtra(EXTRA_LNG, tip.longitude)
            }
        )
        finish()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        debounce?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_NAME = "name"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"

        /** 拉起本页；[centerLat]/[centerLng] 为可选搜索中心（如路线起点） */
        fun intent(
            ctx: Context,
            title: String? = null,
            centerLat: Double = 0.0,
            centerLng: Double = 0.0
        ): Intent = Intent(ctx, PlaceSearchActivity::class.java).apply {
            title?.let { putExtra(EXTRA_TITLE, it) }
            putExtra(EXTRA_LAT, centerLat)
            putExtra(EXTRA_LNG, centerLng)
        }
    }
}
