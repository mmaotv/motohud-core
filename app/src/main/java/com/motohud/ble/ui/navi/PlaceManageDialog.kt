package com.motohud.ble.ui.navi

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.ui.common.MotoDialog
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.data.SavedPlaceEntity
import kotlinx.coroutines.launch
import com.motohud.ble.ui.theme.themeColor

/**
 * 常用地址管理对话框（列表：点击导航 / 编辑 / 删除）。
 *
 * 闪退根因：原先在 [onCreateDialog] 里用 `viewLifecycleOwner`。
 * DialogFragment 此时还没有自己的 View（getView() == null），访问
 * `viewLifecycleOwner` 会直接抛 IllegalStateException —— 点「管理地址」必崩。
 * DialogFragment 的对话框内容不依赖 fragment view，所以观察数据要用
 * fragment 自己的 [lifecycleScope]，不能再用 viewLifecycleOwner。
 */
class PlaceManageDialog : DialogFragment() {

    /** 点击地址 → 打开路线选择 */
    var onNavi: ((SavedPlaceEntity) -> Unit)? = null
    var onEdit: ((SavedPlaceEntity) -> Unit)? = null
    var onDelete: ((SavedPlaceEntity) -> Unit)? = null

    private val vm: NavigationViewModel by activityViewModels {
        NavigationViewModelFactory(requireActivity().application as MotoHudApp)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        val rv = RecyclerView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutManager = LinearLayoutManager(ctx)
            clipToPadding = false
        }
        val adapter = PlaceAdapter(
            onClick = { place ->
                dismissSafely()
                onNavi?.invoke(place)
            },
            onEdit = { place ->
                dismissSafely()
                onEdit?.invoke(place)
            },
            onDelete = { place -> onDelete?.invoke(place) }
        )
        rv.adapter = adapter

        // 空状态：没有常用地址时给一句提示，避免弹窗一片空白看着像加载失败
        val emptyView = android.widget.TextView(ctx).apply {
            text = ctx.getString(R.string.navi_places_none)
            gravity = android.view.Gravity.CENTER
            setTextColor(ctx.themeColor(R.attr.mtTextTertiary, android.graphics.Color.WHITE))
            textSize = 13f
            val pad = (28 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = android.view.View.GONE
        }

        // 用 fragment 生命周期观察（此时 viewLifecycleOwner 尚不可用）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.places.collect { list ->
                    adapter.submitList(list)
                    emptyView.visibility =
                        if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }

        val container = FrameLayout(ctx).apply {
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(rv)
            addView(emptyView)
        }
        // 限制最大高度，避免条目多时把对话框撑出屏幕
        val maxH = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        container.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rv.viewTreeObserver.addOnGlobalLayoutListener {
            if (rv.height > maxH) {
                rv.layoutParams = rv.layoutParams.apply { height = maxH }
            }
        }

        return MotoDialog.builder(ctx)
            .setTitle(R.string.navi_manage_places)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    private fun dismissSafely() {
        runCatching { dismissAllowingStateLoss() }
    }
}
