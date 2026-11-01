package com.motohud.ble.ui.common

import android.content.Context
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.motohud.ble.R
import com.motohud.ble.ui.theme.themeColor

/**
 * 全应用统一的弹窗外观。
 *
 * 为什么不能只靠 themes.xml：
 * Material 的 AlertController 在 show 之前会无条件调用
 * `window.setBackgroundDrawable(insetDrawable(mBackground))`，
 * 其中 mBackground 是它自己 new 的 MaterialShapeDrawable（填充色取 colorSurface，
 * 且**不读 colorOutline**）。所以主题里能改的只有填充色和圆角，改不出描边。
 * 想要「不透明底 + 1dp 描边 + 圆角」这一套，只能在 builder 上挂一个自建背景，
 * 这份代码就是那个唯一出口。
 *
 * 用法：
 * ```
 * val dlg = MotoDialog.builder(ctx).setTitle(...).create()
 * ```
 * 或者对已有的 builder 调 `MotoDialog.apply(builder)`。
 */
object MotoDialog {

    /** 圆角半径，单位 dp。与 ShapeAppearance.Moto.Dialog 保持一致。 */
    private const val CORNER_DP = 22f

    /** 描边宽度，单位 dp。 */
    private const val STROKE_DP = 1f

    fun builder(context: Context): MaterialAlertDialogBuilder =
        apply(MaterialAlertDialogBuilder(context))

    /** 给已有 builder 挂上统一背景，返回同一个 builder 便于链式调用。 */
    fun apply(builder: MaterialAlertDialogBuilder): MaterialAlertDialogBuilder {
        builder.setBackground(background(builder.context))
        return builder
    }

    /** 圆角 + 不透明填充 + 细描边。 */
    fun background(context: Context): MaterialShapeDrawable {
        val density = context.resources.displayMetrics.density
        val model = ShapeAppearanceModel.builder()
            .setAllCornerSizes(CORNER_DP * density)
            .build()
        /*
         * 颜色走主题属性而不是 @color：弹窗要跟着页面的日/夜主题变。
         * 早先写死 @color/dialog_surface，白天页面上仍然弹出深色板 ——
         * 而且 builder.setBackground 会无条件覆盖主题里的 colorSurface，
         * 所以只改主题不够，必须在这里取 attr。
         */
        return MaterialShapeDrawable(model).apply {
            fillColor = ColorStateList.valueOf(
                context.themeColor(
                    R.attr.mtDialogSurface,
                    ContextCompat.getColor(context, R.color.dialog_surface)
                )
            )
            strokeColor = ColorStateList.valueOf(
                context.themeColor(
                    R.attr.mtDialogStroke,
                    ContextCompat.getColor(context, R.color.dialog_stroke)
                )
            )
            strokeWidth = STROKE_DP * density
        }
    }
}
