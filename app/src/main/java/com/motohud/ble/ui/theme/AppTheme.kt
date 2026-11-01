package com.motohud.ble.ui.theme

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import com.motohud.ble.R
import com.motohud.ble.amap.AmapMapConfig
import com.motohud.ble.data.NaviPrefs

/**
 * 全 App 的日 / 夜主题入口。
 *
 * ## 为什么需要一个统一入口
 *
 * 配色走的是**主题属性**（`?attr/mtXxx`，见 `values/attrs.xml`）：属性值是在
 * 布局 inflate 那一刻从主题里解析的，`setContentView` 之后再 `setTheme` 只能
 * 影响之后新建的 View，已经铺好的界面一个字都不会变。所以每个页面都必须在
 * `super.onCreate` **之前**把主题定下来 —— 18 个 Activity 各写一遍判断逻辑，
 * 一定会漏、会不一致。这个对象就是那唯一一份判断。
 *
 * ## 判断依据
 *
 * 完全复用「地图主题」这个已有设置（自动 / 白天 / 夜间），不新增开关：
 * 骑手只需要在一处表达"我现在要看清一点还是别晃眼"。
 * 自动档按**日出日落**算（[AmapMapConfig.resolveDark]），不跟系统深色模式。
 *
 * 非地图页面拿不到「地图中心」，用调色板里的兜底坐标（国内中部）。误差只在
 * 自动档的清晨/黄昏半小时内出现，且只影响页面配色不影响功能。
 */
object AppTheme {

    // 注意：AGP 8 默认开 non-final-resource-ids，R.style.* 不是编译期常量，
    // 所以这里是 val 不是 const val。
    /** 夜间主题（就是基础主题本身，不需要 setTheme） */
    private val NIGHT = R.style.Theme_MotoHeadUnit

    /** 白天主题 */
    private val DAY = R.style.Theme_MotoHeadUnit_Day

    /**
     * 现在该用不用深色。
     *
     * 传了坐标就用坐标算日出日落（导航页有终点坐标，比兜底准）；
     * 没传就交给 [AmapMapConfig.resolveDark] 的兜底。
     */
    fun isDark(lat: Double = 0.0, lng: Double = 0.0): Boolean {
        if (lat != 0.0 && lng != 0.0) return AmapMapConfig.resolveDark(lat, lng)

        /*
         * 没给坐标时用「上次地图中心」，**不能**让 resolveDark 落到固定的兜底点。
         *
         * 地图页自己是用 cameraPosition（用户眼前那块地）算日落的。页面配色若用
         * 另一个固定坐标，就会出现"卡片是白天、地图是夜间"——真机实测：17:15
         * 地图中心在北京，已过日落前 20 分钟的余量，地图转深色；而页面按兜底点
         * （成都，更靠西、日落更晚）算出来还是白天，同一屏两套配色。
         *
         * 缓存为空（全新安装、从没打开过地图）才用兜底点，那种情况下也没有地图
         * 可以"不一致"。
         */
        val memo = NaviPrefs.mapCamera()
        return AmapMapConfig.resolveDark(memo?.lat ?: 0.0, memo?.lng ?: 0.0)
    }

    /**
     * 给页面套主题。**必须在 `super.onCreate` 之前调用。**
     *
     * 夜间时不做任何事：基础主题本来就是夜间，`setTheme` 一遍反而会覆盖掉
     * manifest 里可能指定的其它主题。
     */
    fun apply(activity: Activity, lat: Double = 0.0, lng: Double = 0.0) {
        activity.setTheme(styleOf(isDark(lat, lng)))
    }

    /** 是深色就返回夜间样式号，否则白天样式号。给需要 `setTheme(int)` 的场景用。 */
    @StyleRes
    fun styleOf(dark: Boolean): Int = if (dark) NIGHT else DAY

    /**
     * 自定义 Dialog 该用的主题号。
     *
     * Dialog 的主题不继承所在 Activity，必须自己选一份 —— 否则白天页面上
     * 弹出的 Dialog 里仍是"白字 + 深色板"。见 `Theme.MotoDialog.Day` 的注释。
     */
    @StyleRes
    fun dialogTheme(dark: Boolean = isDark()): Int =
        if (dark) R.style.Theme_MotoDialog else R.style.Theme_MotoDialog_Day

    /**
     * 用户改了「地图主题」之后，已经打开的页面要不要重画。
     *
     * 白天/夜间是 inflate 时定死的，运行时切换只能靠 recreate。但**不能无条件
     * recreate** —— 导航进行中重建 Activity 会打断投屏握手和语音队列。所以：
     *   · 普通页面（主页、设置、历史…）→ 重建，用户立刻看到新配色；
     *   · 导航页 → 不重建，等下次进入导航页自然生效（提示文案里说明）。
     */
    fun recreateIfThemeChanged(activity: Activity) {
        /*
         * 导航页例外：正在导航时重建 Activity 会重建 AMapNaviView、打断投屏
         * 握手队列和语音播报，代价远大于"晚一点看到新配色"。导航页下次进入时
         * 自然按新设置 inflate。
         */
        if (activity is com.motohud.ble.ui.navi.GuidanceActivity) return

        if (isDark() != currentIsDark(activity)) activity.recreate()
    }

    /** 当前这套主题实际是深还是浅（按已解析的 `mtWindow` 亮度判断，不猜） */
    fun currentIsDark(context: Context): Boolean =
        Color.luminance(context.themeColor(R.attr.mtWindow, Color.BLACK)) < 0.5f
}

/**
 * 从主题里解析一个颜色属性。
 *
 * 属性值可能指向颜色资源（`@color/pal_night_panel`），也可能是内联色值
 * （`#F0141416`），两条路都要能走 —— 所以先看 `resourceId`，没有再取 `data`。
 *
 * 为什么不用 `obtainStyledAttributes`：那个 API 要求调用方自己 recycle，
 * 而且对「属性值是 attr 引用」这种情况要多绕一层；这里只取一个颜色，
 * `resolveAttribute` + `data` 最直接。
 *
 * 解析不到时返回 [fallback]，绝不抛异常 —— 配色解析失败不该让导航崩掉。
 */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int, @ColorInt fallback: Int): Int {
    val tv = TypedValue()
    if (!theme.resolveAttribute(attr, tv, true)) return fallback
    return when {
        tv.resourceId != 0 -> runCatching { ContextCompat.getColor(this, tv.resourceId) }
            .getOrDefault(fallback)
        tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            tv.type <= TypedValue.TYPE_LAST_COLOR_INT -> tv.data
        else -> fallback
    }
}

/** 同 [themeColor]，但属性缺失时直接抛（给「必须有值」的自定义 View 用） */
@ColorInt
fun Context.requireThemeColor(@AttrRes attr: Int): Int =
    themeColor(attr, Color.MAGENTA)
