package com.motohud.ble.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.ui.theme.AppTheme

/**
 * 新手引导（首次启动展示，参考主流应用引导流程）：
 *
 *   1. 欢迎页：一句话介绍产品定位；
 *   2. 隐私政策：地图/定位/通知的用途说明，同意后方可使用地图能力（合规必须）；
 *   3. 如何连接车机：3 步图解；
 *   4. 如何开始导航：3 步 + Key 说明。
 *
 * 完成或跳过后写入标记，之后仅在"设置 → 重新查看新手引导"中再次出现。
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var btnNext: Button
    private lateinit var btnSkip: Button
    private lateinit var dots: LinearLayout
    private var lastClickMs = 0L

    private val pages = listOf(
        R.layout.page_onboarding_welcome,
        R.layout.page_onboarding_privacy,
        R.layout.page_onboarding_connect,
        R.layout.page_onboarding_navi
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        /*
         * 日 / 夜主题必须在 super 之前定下来。
         * `?attr/mtXxx` 是在布局 inflate 那一刻从主题里取值的，晚一步设置
         * 只能影响之后新建的 View，已经铺好的界面一个字都不会变。
         */
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pager = findViewById(R.id.onboarding_pager)
        btnNext = findViewById(R.id.btn_next)
        btnSkip = findViewById(R.id.btn_skip)
        dots = findViewById(R.id.dots_container)

        pager.adapter = PageAdapter()
        pager.isUserInputEnabled = false
        pager.offscreenPageLimit = 1

        buildDots()
        updateUi()

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateUi()
            }
        })

        btnNext.setOnClickListener {
            // 防抖：避免触摸事件重复投递导致一次点击跳过两页
            val now = System.currentTimeMillis()
            if (now - lastClickMs < 400L) return@setOnClickListener
            lastClickMs = now

            val idx = pager.currentItem
            if (idx == PAGE_PRIVACY) {
                // 同意隐私政策后才能初始化高德 SDK
                AmapManager.markPrivacyShown(this)
                AmapManager.agreePrivacy(this)
            }
            if (idx >= pages.size - 1) {
                finishOnboarding()
            } else {
                pager.currentItem = idx + 1
            }
        }

        btnSkip.setOnClickListener { finishOnboarding() }
    }

    private fun finishOnboarding() {
        prefs(this).edit().putBoolean(KEY_DONE, true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun updateUi() {
        val idx = pager.currentItem
        btnNext.setText(
            when (idx) {
                PAGE_PRIVACY -> R.string.onboarding_agree
                pages.size - 1 -> R.string.onboarding_start
                else -> R.string.onboarding_next
            }
        )
        // 隐私页不允许跳过（必须明确同意或退出），其余页面可跳过
        btnSkip.visibility = if (idx == PAGE_PRIVACY) View.GONE else View.VISIBLE
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).setBackgroundResource(
                if (i == idx) R.drawable.bg_onboarding_dot_active else R.drawable.bg_onboarding_dot
            )
        }
    }

    private fun buildDots() {
        dots.removeAllViews()
        val size = (7 * resources.displayMetrics.density).toInt()
        val gap = (6 * resources.displayMetrics.density).toInt()
        pages.indices.forEach { i ->
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = gap
            dot.layoutParams = lp
            dot.setBackgroundResource(
                if (i == 0) R.drawable.bg_onboarding_dot_active else R.drawable.bg_onboarding_dot
            )
            dots.addView(dot)
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
            return Holder(v)
        }

        override fun getItemViewType(position: Int): Int = pages[position]

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            // 页面内容为静态文案，入场做一次淡入上移动画
            holder.itemView.alpha = 0f
            holder.itemView.translationY = 24f * resources.displayMetrics.density
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280L)
                .start()
        }
    }

    companion object {
        private const val PAGE_PRIVACY = 1
        private const val PREFS = "onboarding"
        private const val KEY_DONE = "done"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** 是否已完成（或跳过）引导 */
        fun isDone(context: Context): Boolean = prefs(context).getBoolean(KEY_DONE, false)

        fun reset(context: Context) {
            prefs(context).edit().putBoolean(KEY_DONE, false).apply()
        }

        fun start(context: Context) {
            context.startActivity(Intent(context, OnboardingActivity::class.java))
        }
    }
}
