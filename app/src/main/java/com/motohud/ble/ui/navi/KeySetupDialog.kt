package com.motohud.ble.ui.navi

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.motohud.ble.ui.common.MotoDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.motohud.ble.R
import com.motohud.ble.amap.AmapErrorCode
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.amap.AmapRepo
import com.motohud.ble.amap.TipsResult
import kotlinx.coroutines.launch
import java.security.MessageDigest
import com.motohud.ble.ui.theme.themeColor

/**
 * 高德地图 Key 配置对话框。
 *
 * 高德 Android Key 与「包名 + 签名 SHA1」绑定，服务端会校验二者，
 * 因此不能沿用任何他人（或旧版应用）的 Key —— 必须用自己的签名重新申请。
 * 这里把包名与 SHA1 直接算出来、可一键复制，并给出控制台入口，申请过程只剩两步。
 */
class KeySetupDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        root.addView(TextView(ctx).apply {
            text = currentStatusText()
            textSize = 12f
            setTextColor(requireContext().themeColor(R.attr.mtTextSecondary, android.graphics.Color.WHITE))
        })

        root.addView(TextView(ctx).apply {
            text = getString(R.string.amap_apply_tip)
            textSize = 12f
            setTextColor(requireContext().themeColor(R.attr.mtTextTertiary, android.graphics.Color.WHITE))
            setPadding(0, dp(10), 0, dp(8))
        })

        val pkg = ctx.packageName
        val sha1 = signatureSha1(ctx)
        val info = TextView(ctx).apply {
            text = "${getString(R.string.amap_pkg_label)}：$pkg\n" +
                "${getString(R.string.amap_sha1_label)}：$sha1"
            textSize = 12f
            setTextColor(requireContext().themeColor(R.attr.mtTextPrimary, android.graphics.Color.WHITE))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_metric)
        }
        root.addView(info)

        val et = TextInputEditText(ctx).apply {
            hint = getString(R.string.amap_key_input_hint)
            setText(AmapManager.apiKey(ctx))
            setSingleLine()
        }

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        actions.addView(TextView(ctx).apply {
            text = getString(R.string.amap_copy_info)
            textSize = 12f
            setTextColor(requireContext().themeColor(R.attr.mtAccent, android.graphics.Color.WHITE))
            setPadding(0, dp(6), dp(16), dp(6))
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("amap", "包名：$pkg\nSHA1：$sha1"))
                Toast.makeText(ctx, R.string.amap_info_copied, Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(TextView(ctx).apply {
            text = getString(R.string.amap_test)
            textSize = 12f
            setTextColor(requireContext().themeColor(R.attr.mtAccent, android.graphics.Color.WHITE))
            setPadding(dp(8), dp(6), dp(16), dp(6))
            setOnClickListener {
                val key = et.text?.toString()?.trim().orEmpty()
                if (key.isNotEmpty()) AmapManager.setApiKey(ctx, key)
                AmapManager.initSearch(ctx)
                info.text = getString(R.string.amap_testing)
                val act = activity ?: return@setOnClickListener
                act.lifecycleScope.launch {
                    val r = runCatching { AmapRepo(ctx).searchTips("天安门", "北京") }.getOrNull()
                    info.text = when (r) {
                        is TipsResult.Ok -> getString(R.string.amap_test_ok, r.items.size)
                        is TipsResult.Err -> buildString {
                            append(getString(R.string.amap_test_fail, r.code))
                            append("\n")
                            append(AmapErrorCode.reason(r.code))
                            append("\n")
                            append(AmapErrorCode.advice(r.code))
                        }
                        else -> getString(R.string.amap_test_network)
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(TextView(ctx).apply {
            text = getString(R.string.amap_open_console)
            textSize = 12f
            setTextColor(requireContext().themeColor(R.attr.mtAccent, android.graphics.Color.WHITE))
            setPadding(dp(8), dp(6), 0, dp(6))
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://console.amap.com/dev/key/app"))
                    )
                }
            }
        })
        root.addView(actions)

        root.addView(et)

        return MotoDialog.builder(ctx)
            .setTitle(R.string.amap_key_title)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.clear) { _, _ ->
                AmapManager.clearApiKey(ctx)
                Toast.makeText(ctx, R.string.amap_key_cleared, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val key = et.text?.toString()?.trim().orEmpty()
                if (key.isEmpty()) {
                    Toast.makeText(ctx, R.string.amap_key_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AmapManager.setApiKey(ctx, key)
                // 预热各 SDK
                AmapManager.initMap(ctx)
                AmapManager.initSearch(ctx)
                AmapManager.initLocation(ctx)
                Toast.makeText(ctx, R.string.amap_key_applied, Toast.LENGTH_SHORT).show()
                // 刷新导航页的降级状态
                (activity as? com.motohud.ble.ui.MainActivity)?.refreshNaviKeyState()
            }
            .create()
    }

    private fun currentStatusText(): String {
        val ctx = requireContext()
        val key = AmapManager.apiKey(ctx)
        return when {
            key.isEmpty() -> getString(R.string.amap_key_none)
            AmapManager.isKeyFromBuild(ctx) -> getString(R.string.amap_key_from_build)
            else -> getString(R.string.amap_key_current, maskKey(key))
        }
    }

    private fun maskKey(key: String): String =
        if (key.length <= 8) key else key.take(6) + "****" + key.takeLast(4)

    /** 当前签名证书的 SHA1（大写冒号分隔），用于在高德控制台登记 */
    @Suppress("DEPRECATION")
    private fun signatureSha1(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
        val sig = info.signatures?.firstOrNull()?.toByteArray() ?: return@runCatching null
        val md = MessageDigest.getInstance("SHA-1")
        md.digest(sig).joinToString(":") { String.format("%02X", it) }
    }.getOrNull() ?: getString(R.string.amap_sha1_unavailable)
}
