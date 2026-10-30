package com.motohud.ble.ui.navi

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amap.api.maps.offlinemap.OfflineMapCity
import com.amap.api.maps.offlinemap.OfflineMapManager
import com.amap.api.maps.offlinemap.OfflineMapStatus
import com.motohud.ble.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 离线地图管理（高德 `OfflineMapManager`）。
 *
 * 为什么需要它：骑行进山、进隧道、跑省道，最缺信号的地方恰恰最需要地图 ——
 * 在线地图这时候是一片空白。离线包下载到本地后，底图和路网不再依赖网络。
 *
 * **前置条件（重要）**：离线地图**不在**免费基础服务列表里（地图/定位/导航/
 * 室内地图/室内定位/猎鹰才是免费项），需向高德单独申请开通，否则
 * `getOfflineMapProvinceList()` 永远返回空。这不是代码问题，页面会明确写出来，
 * 免得以为是 Bug。
 *
 * 状态判断不依赖 `OfflineMapCity.getState()` 的枚举值（文档从来没写全过），
 * 改用「集合归属」：在 `getDownloadOfflineMapCityList()` 里 = 已下载，
 * 在 `getDownloadingCityList()` 里 = 下载中。state 只用来区分「有更新」。
 */
class OfflineMapActivity : AppCompatActivity(), OfflineMapManager.OfflineMapDownloadListener {

    private lateinit var tvSummary: TextView
    private lateinit var tvHint: TextView
    private lateinit var list: RecyclerView
    private lateinit var loading: View
    private lateinit var adapter: RowAdapter

    private var manager: OfflineMapManager? = null
    private var rows: List<Row> = emptyList()
    private val expandedProvinces = HashSet<String>()

    /** 已下载城市名集合（SDK 的 getDownloadOfflineMapCityList 是唯一可靠来源） */
    private val downloaded = HashSet<String>()

    /** 下载中：城市名 → 完成百分比 */
    private val downloading = HashMap<String, Int>()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var retryJob: Job? = null

    /** 错误提示节流，避免 19 个城市连续报错时刷屏 19 条 toast */
    private var lastErrToast = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_map)

        val topBar = findViewById<View>(R.id.offline_top_bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(8), left = bars.left + dp(12), right = bars.right + dp(16))
            insets
        }
        // 底部提示也要避开手势条，否则最后一行字压在系统栏下面
        val footer = findViewById<View>(R.id.offline_footer)
        ViewCompat.setOnApplyWindowInsetsListener(footer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom + dp(14))
            insets
        }

        tvSummary = findViewById(R.id.offline_summary)
        tvHint = findViewById(R.id.offline_hint)
        loading = findViewById(R.id.offline_loading)
        list = findViewById(R.id.offline_list)
        adapter = RowAdapter(::onRowClick, ::onActionClick)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.offline_btn_back).setOnClickListener { finish() }

        runCatching { manager = OfflineMapManager(this, this) }
            .onFailure { showHint(getString(R.string.offline_map_hint_init_fail, it.message ?: "")) }

        if (manager == null) {
            loading.visibility = View.GONE
        } else {
            // 构造后 SDK 异步拉取离线包清单，第一次调用往往是空的；
            // 没有公开的"加载完成"回调，只能短间隔轮询几次
            pollUntilReady(attempt = 0)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 轮询等待离线包清单就绪。
     *
     * 第 5 次（约 5 秒）还空，就认定是当前 Key 未开通离线地图服务 —— 这时候只报"暂无数据"
     * 等于把用户丢在原地，必须给出可执行的下一步（说明需向高德单独申请开通离线地图服务）。
     */
    private fun pollUntilReady(attempt: Int) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(if (attempt == 0) 600L else 1_200L)
            val provs = runCatching { manager?.offlineMapProvinceList }.getOrNull().orEmpty()
            if (provs.isEmpty() && attempt < 5) {
                pollUntilReady(attempt + 1)
                return@launch
            }
            loading.visibility = View.GONE
            if (provs.isEmpty()) {
                showHint(getString(R.string.offline_map_hint_no_service))
            } else {
                tvHint.visibility = View.GONE
                reload()
            }
        }
    }

    private fun showHint(text: String) {
        tvHint.text = text
        tvHint.visibility = View.VISIBLE
    }

    // ---------- 数据装配 ----------

    private fun reload() {
        val m = manager ?: return
        downloaded.clear()
        downloading.clear()
        runCatching {
            m.downloadOfflineMapCityList?.forEach { c -> downloaded.add(c.city) }
            // 注意：SDK 的这个 getter 是全小写 `getcompleteCode()`，
            // Kotlin 侧推不出 `completeCode` 属性，只能按方法名调
            m.downloadingCityList?.forEach { c ->
                downloading[c.city] = runCatching { c.getcompleteCode() }.getOrDefault(0)
            }
        }

        val out = ArrayList<Row>(64)
        runCatching {
            m.offlineMapProvinceList.orEmpty().forEach { p ->
                val name = p.provinceName.orEmpty()
                val cities = p.cityList.orEmpty().filter { it.city.isNullOrBlank().not() }
                if (cities.isEmpty()) return@forEach

                val doneCount = cities.count { downloaded.contains(it.city) }
                val totalBytes = cities.sumOf { runCatching { it.size }.getOrDefault(0L) }
                val expanded = expandedProvinces.contains(name)
                val downloadingNow = cities.any { downloading.containsKey(it.city) }

                out.add(
                    Row(
                        province = name,
                        city = null,
                        title = name,
                        sub = getString(
                            R.string.offline_map_province_sub,
                            doneCount, cities.size, mb(totalBytes)
                        ),
                        action = when {
                            doneCount == cities.size -> ""
                            downloadingNow -> getString(R.string.offline_action_pause)
                            else -> getString(R.string.offline_action_province)
                        },
                        arrowVisible = true,
                        arrowDown = expanded,
                        indent = false,
                        progress = -1
                    )
                )
                if (!expanded) return@forEach
                cities.forEach { c -> out.add(cityRow(name, c)) }
            }
        }

        rows = out
        adapter.submit(rows)
        updateSummary()
    }

    private fun cityRow(province: String, c: OfflineMapCity): Row {
        val city = c.city.orEmpty()
        val percent = downloading[city]
        val isDone = downloaded.contains(city)
        // 已下载且 state 标记为可更新；state 枚举没文档，只在值为 6 时认为有更新
        val hasUpdate = isDone && runCatching { c.state } .getOrDefault(0) == 6

        val (sub, action, progress) = when {
            percent != null -> Triple(
                getString(R.string.offline_map_downloading, percent),
                getString(R.string.offline_action_pause),
                percent
            )
            hasUpdate -> Triple(
                getString(R.string.offline_map_size, mb(runCatching { c.size }.getOrDefault(0L))),
                getString(R.string.offline_action_update),
                -1
            )
            isDone -> Triple(
                getString(R.string.offline_map_size, mb(runCatching { c.size }.getOrDefault(0L))),
                getString(R.string.offline_action_delete),
                -1
            )
            else -> Triple(
                getString(R.string.offline_map_size, mb(runCatching { c.size }.getOrDefault(0L))),
                getString(R.string.offline_action_download),
                -1
            )
        }
        return Row(
            province = province,
            city = city,
            title = city,
            sub = sub,
            action = action,
            arrowVisible = false,
            arrowDown = false,
            indent = true,
            progress = progress
        )
    }

    private fun updateSummary() {
        val count = downloaded.size
        if (count == 0) {
            tvSummary.visibility = View.GONE
            return
        }
        var bytes = 0L
        runCatching {
            manager?.downloadOfflineMapCityList?.forEach { bytes += runCatching { it.size }.getOrDefault(0L) }
        }
        tvSummary.text = getString(R.string.offline_map_summary, count, mb(bytes))
        tvSummary.visibility = View.VISIBLE
    }

    private fun mb(bytes: Long): String = when {
        bytes <= 0 -> "--"
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    // ---------- 交互 ----------

    private fun onRowClick(row: Row) {
        if (row.city != null) return // 城市行点空白处不做任何事，避免误触
        if (expandedProvinces.contains(row.province)) {
            expandedProvinces.remove(row.province)
        } else {
            expandedProvinces.add(row.province)
        }
        reload()
    }

    private fun onActionClick(row: Row) {
        val m = manager ?: return
        val action = row.action
        if (action.isBlank()) return

        val province = row.province
        val city = row.city
        val result = runCatching {
            if (city == null) {
                if (action == getString(R.string.offline_action_pause)) {
                    // 省行显示「暂停」时：暂停该省所有正在下载的城市
                    m.offlineMapProvinceList.orEmpty()
                        .firstOrNull { it.provinceName == province }
                        ?.cityList.orEmpty()
                        .forEach { c -> if (downloading.containsKey(c.city)) m.pauseByName(c.city) }
                } else {
                    // 省行：整包下载（SDK 会自己拆成该省所有城市），自动展开看进度
                    expandedProvinces.add(province)
                    m.downloadByProvinceName(province)
                }
            } else {
                when (action) {
                    getString(R.string.offline_action_update) -> m.updateOfflineCityByName(city)
                    getString(R.string.offline_action_delete) -> m.remove(city)
                    getString(R.string.offline_action_pause) -> m.pauseByName(city)
                    else -> {
                        expandedProvinces.add(province)
                        m.downloadByCityName(city)
                    }
                }
            }
        }
        result.onFailure { toast(getString(R.string.offline_map_action_fail, it.message ?: "")) }
        // SDK 的回调是异步的，先给一个即时反馈，回调到达后再刷新真实状态
        list.postDelayed({ reload() }, 400)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- OfflineMapManager 回调 ----------

    override fun onDownload(status: Int, completeCode: Int, des: String?) {
        // 第一个参数是 OfflineMapStatus；异常类（网络/服务/存储）必须显式提示，
        // 否则用户只会看到 0% 不动、毫无反馈。
        when (status) {
            OfflineMapStatus.ERROR,
            OfflineMapStatus.EXCEPTION_AMAP,
            OfflineMapStatus.EXCEPTION_NETWORK_LOADING,
            OfflineMapStatus.EXCEPTION_SDCARD,
            OfflineMapStatus.START_DOWNLOAD_FAILED -> {
                val msg = when (status) {
                    OfflineMapStatus.EXCEPTION_NETWORK_LOADING -> getString(R.string.offline_map_err_network)
                    OfflineMapStatus.EXCEPTION_SDCARD -> getString(R.string.offline_map_err_sdcard)
                    OfflineMapStatus.EXCEPTION_AMAP -> getString(R.string.offline_map_err_service)
                    OfflineMapStatus.START_DOWNLOAD_FAILED -> getString(R.string.offline_map_err_start)
                    else -> getString(R.string.offline_map_err_unknown, status)
                }
                val now = System.currentTimeMillis()
                if (now - lastErrToast > 3000) {
                    lastErrToast = now
                    runOnUiThread { toast(msg) }
                }
                Log.w("OfflineMap", "onDownload 失败 status=$status des=$des -> $msg")
            }
        }
        // 回调来自 SDK 内部线程，刷新必须回主线程
        runOnUiThread { reload() }
    }

    override fun onCheckUpdate(update: Boolean, des: String?) {
        runOnUiThread { reload() }
    }

    override fun onRemove(success: Boolean, cityName: String?, msg: String?) {
        runOnUiThread {
            reload()
            if (!success) toast(getString(R.string.offline_map_action_fail, msg.orEmpty()))
        }
    }

    // ---------- 生命周期 ----------

    override fun onDestroy() {
        retryJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        runCatching { manager?.stop() }
        manager = null
        super.onDestroy()
    }

    // ---------- 列表 ----------

    private data class Row(
        val province: String,
        val city: String?,
        val title: String,
        val sub: String,
        val action: String,
        val arrowVisible: Boolean,
        val arrowDown: Boolean,
        val indent: Boolean,
        val progress: Int
    )

    private class RowAdapter(
        private val onRow: (Row) -> Unit,
        private val onAction: (Row) -> Unit
    ) : RecyclerView.Adapter<RowAdapter.VH>() {

        private var data: List<Row> = emptyList()

        fun submit(list: List<Row>) {
            data = list
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val arrow: ImageView = view.findViewById(R.id.off_arrow)
            val name: TextView = view.findViewById(R.id.off_name)
            val sub: TextView = view.findViewById(R.id.off_sub)
            val action: TextView = view.findViewById(R.id.off_action)
            val progress: ProgressBar = view.findViewById(R.id.off_progress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_offline_row, parent, false))

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = data[position]
            holder.name.text = row.title
            holder.sub.text = row.sub

            holder.arrow.visibility = if (row.arrowVisible) View.VISIBLE else View.INVISIBLE
            // 省行的折叠标记复用返回箭头这一张图：收起时转 180°（左→右，表示"点开"），
            // 展开时转 270°（指向下，表示"已展开"）。省一套图标资源。
            holder.arrow.rotation = if (row.arrowDown) 270f else 180f

            holder.name.setPaddingRelative(
                // 城市缩进到与省份**文字**左边缘对齐：箭头 16dp + 间距 6dp + 再让 18dp
                if (row.indent) (holder.name.resources.displayMetrics.density * 40).toInt() else 0,
                0, 0, 0
            )

            holder.action.text = row.action
            holder.action.visibility = if (row.action.isBlank()) View.INVISIBLE else View.VISIBLE
            holder.action.setOnClickListener { onAction(row) }
            holder.itemView.setOnClickListener { onRow(row) }

            if (row.progress in 0..100) {
                holder.progress.visibility = View.VISIBLE
                holder.progress.progress = row.progress
            } else {
                holder.progress.visibility = View.GONE
            }
        }
    }

    companion object {
        fun start(ctx: android.content.Context) {
            ctx.startActivity(android.content.Intent(ctx, OfflineMapActivity::class.java))
        }
    }
}
