package com.motohud.ble.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.motohud.ble.R
import com.motohud.ble.databinding.ActivityHostBinding
import com.motohud.ble.ui.search.SearchFragment
import com.motohud.ble.ui.theme.AppTheme

/**
 * 二级页面容器。
 *
 * 主界面只保留 6 个高频页签，低频功能（如本地检索）通过这里打开，
 * 避免底部导航越堆越多。
 */
class HostActivity : AppCompatActivity() {

    companion object {
        const val PAGE_SEARCH = "search"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PAGE = "page"

        fun start(context: Context, title: String, page: String) {
            context.startActivity(
                Intent(context, HostActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_PAGE, page)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        /*
         * 日 / 夜主题必须在 super 之前定下来。
         * `?attr/mtXxx` 是在布局 inflate 那一刻从主题里取值的，晚一步设置
         * 只能影响之后新建的 View，已经铺好的界面一个字都不会变。
         */
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        val binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.btnBack.setOnClickListener { finish() }

        if (savedInstanceState == null) {
            val page: Fragment? = when (intent.getStringExtra(EXTRA_PAGE)) {
                PAGE_SEARCH -> SearchFragment()
                else -> null
            }
            page?.let {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container, it)
                    .commit()
            }
        }
    }
}
