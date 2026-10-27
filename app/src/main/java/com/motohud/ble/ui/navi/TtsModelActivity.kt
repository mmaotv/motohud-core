package com.motohud.ble.ui.navi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.motohud.ble.R
import com.motohud.ble.data.AppCache
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.navi.SpeechPriority
import com.motohud.ble.navi.tts.SherpaSpeaker
import com.motohud.ble.navi.tts.TtsModelStore
import com.motohud.ble.navi.tts.TtsStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 离线语音引擎管理页。
 *
 * ## 这一页要回答用户三个问题
 *
 * 1. 装没装？占多少空间？（状态卡）
 * 2. 装了之后能好到什么程度？（试听 —— 直接念一句带路名的播报）
 * 3. 不想要了怎么删？（删除按钮，顺手把空间还回去）
 *
 * ## 为什么模型不做进 APK
 *
 * 见 [TtsModelStore] 注释：29MB 的 onnx 会让所有用户为可能用不到的功能付流量，
 * 而且换模型必须重新发版。按需下载则两全。
 */
class TtsModelActivity : AppCompatActivity() {

    private lateinit var state: TextView
    private lateinit var stateSub: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnDownload: View
    private lateinit var btnDelete: View
    private lateinit var btnTest: View
    private lateinit var tuneCard: View
    private lateinit var voiceBox: android.widget.LinearLayout
    private lateinit var speedBar: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var libCard: View
    private lateinit var libCount: TextView
    private lateinit var libProgress: ProgressBar
    private lateinit var btnLib: TextView

    /** 语音库是否正在生成（生成完之前禁止重复点击） */
    private var libBuilding = false

    /** 试听用的播报器（页面销毁时释放，避免常驻一个 30MB 的模型实例） */
    private var testSpeaker: SherpaSpeaker? = null

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_model)

        val topBar = findViewById<View>(R.id.tts_top_bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(8))
            findViewById<View>(R.id.tts_actions)
                .updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom + dp(16))
            insets
        }

        state = findViewById(R.id.tts_state)
        stateSub = findViewById(R.id.tts_state_sub)
        progress = findViewById(R.id.tts_progress)
        progressText = findViewById(R.id.tts_progress_text)
        btnDownload = findViewById(R.id.tts_btn_download)
        btnDelete = findViewById(R.id.tts_btn_delete)
        btnTest = findViewById(R.id.tts_btn_test)
        tuneCard = findViewById(R.id.tts_tune_card)
        voiceBox = findViewById(R.id.tts_voice_box)
        speedBar = findViewById(R.id.tts_speed)
        speedValue = findViewById(R.id.tts_speed_value)
        volumeBar = findViewById(R.id.tts_volume)
        volumeValue = findViewById(R.id.tts_volume_value)
        libCard = findViewById(R.id.tts_lib_card)
        libCount = findViewById(R.id.tts_lib_count)
        libProgress = findViewById(R.id.tts_lib_progress)
        btnLib = findViewById(R.id.tts_btn_lib)

        findViewById<View>(R.id.tts_btn_back).setOnClickListener { finish() }
        btnDownload.setOnClickListener { download() }
        btnDelete.setOnClickListener { confirmDelete() }
        btnTest.setOnClickListener { test() }
        btnLib.setOnClickListener { buildLibrary() }
        setupSpeed()
        setupVolume()

        render()
    }

    /** 语速滑块：0..7 → 0.7×..1.4×，每档 0.1 */
    private fun setupSpeed() {
        speedBar.progress = speedToProgress(NaviPrefs.ttsSpeed.value)
        renderSpeedValue()
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                NaviPrefs.setTtsSpeed(progressToSpeed(p))
                renderSpeedValue()
            }

            // 松手才试听：拖动过程中不停合成会卡，也吵
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = test()
        })
        renderSpeedValue()
    }

    private fun speedToProgress(speed: Float): Int =
        ((speed.coerceIn(0.7f, 1.4f) - 0.7f) * 10f + 0.5f).toInt()

    private fun progressToSpeed(p: Int): Float = 0.7f + p * 0.1f

    /** 音量滑块：0..4 → 1.0×..3.0×，每档 0.5 */
    private fun setupVolume() {
        val steps = NaviPrefs.VOLUME_STEPS
        volumeBar.progress = steps.indexOfFirst { it >= NaviPrefs.ttsVolume.value }
            .takeIf { it >= 0 } ?: steps.indexOfFirst { it >= NaviPrefs.DEFAULT_TTS_VOLUME }
        renderVolumeValue()
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                NaviPrefs.setTtsVolume(steps[p.coerceIn(steps.indices)])
                renderVolumeValue()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = test()
        })
        renderVolumeValue()
    }

    private fun renderVolumeValue() {
        val steps = NaviPrefs.VOLUME_STEPS
        val v = steps[volumeBar.progress.coerceIn(steps.indices)]
        volumeValue.text = getString(
            R.string.tts_volume_value,
            String.format(Locale.US, "%.1f", v)
        )
    }

    private fun renderSpeedValue() {
        speedValue.text = getString(
            R.string.tts_speed_value,
            String.format(Locale.US, "%.1f", progressToSpeed(speedBar.progress))
        )
    }

    /** 风格 chips：点哪个就切到哪个并立刻试听，省得用户来回点两下 */
    private fun renderVoices() {
        voiceBox.removeAllViews()
        val current = NaviPrefs.ttsStyle.value
        TtsStyle.ALL.forEach { style ->
            val chip = TextView(this).apply {
                text = style.name
                setTextColor(
                    ContextCompat.getColor(
                        this@TtsModelActivity,
                        if (style.id == current) R.color.text_primary else R.color.text_secondary
                    )
                )
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = ContextCompat.getDrawable(
                    this@TtsModelActivity,
                    if (style.id == current) R.drawable.bg_chip_selected else R.drawable.bg_chip
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    // 换风格 = 换音色 + 语速 + 音调，缓存/语音库都按风格分开存，不会串味
                    NaviPrefs.setTtsStyle(style.id)
                    renderVoices()
                    renderLibCount()
                    test()
                }
            }
            voiceBox.addView(chip)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val installed = TtsModelStore.isInstalled(this)
        val bytes = TtsModelStore.installedBytes(this)
        if (installed) {
            state.setText(R.string.tts_state_installed)
            stateSub.text = getString(
                R.string.tts_state_installed_sub,
                AppCache.human(bytes)
            )
        } else {
            state.setText(R.string.tts_state_missing)
            stateSub.setText(R.string.tts_state_missing_sub)
        }
        btnDownload.visibility = if (installed) View.GONE else View.VISIBLE
        btnDelete.visibility = if (installed) View.VISIBLE else View.GONE
        btnTest.visibility = if (installed) View.VISIBLE else View.GONE
        tuneCard.visibility = if (installed) View.VISIBLE else View.GONE
        libCard.visibility = if (installed) View.VISIBLE else View.GONE
        if (installed) {
            renderVoices()
            renderLibCount()
        }
    }

    private fun renderLibCount() {
        val n = SherpaSpeaker.libraryCount(this)
        val size = AppCache.human(SherpaSpeaker.libraryBytes(this))
        libCount.text = getString(R.string.tts_lib_count, n, size)
    }

    /**
     * 生成离线语音库。
     *
     * 150 条约 2~3 分钟，所以给进度条而不是转圈；中途退出也无所谓，
     * 已生成的都落盘了，下次进来接着补（[SherpaSpeaker.buildVoiceLibrary] 会跳过已有的）。
     */
    private fun buildLibrary() {
        if (libBuilding) return
        libBuilding = true
        btnLib.isEnabled = false
        btnLib.alpha = 0.45f
        libProgress.visibility = View.VISIBLE
        libProgress.progress = 0
        SherpaSpeaker.buildVoiceLibrary(this) { done, total ->
            if (isFinishing || isDestroyed) return@buildVoiceLibrary
            libProgress.progress = if (total > 0) done * 100 / total else 100
            libCount.text = getString(R.string.tts_lib_building, done, total)
            if (done >= total) {
                libBuilding = false
                btnLib.isEnabled = true
                btnLib.alpha = 1f
                libProgress.visibility = View.GONE
                renderLibCount()
                Toast.makeText(
                    this@TtsModelActivity,
                    getString(R.string.tts_lib_done, SherpaSpeaker.libraryCount(this@TtsModelActivity)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun download() {
        if (busy) return
        busy = true
        btnDownload.isEnabled = false
        btnDownload.alpha = 0.45f
        progress.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progress.progress = 0
        progressText.setText(R.string.tts_connecting)
        state.setText(R.string.tts_state_downloading)

        lifecycleScope.launch {
            val result = TtsModelStore.install(this@TtsModelActivity) { done, total ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                    progress.progress = pct
                    progressText.text = if (total > 0) {
                        String.format(
                            Locale.US, "%d%%  %s / %s",
                            pct, AppCache.human(done), AppCache.human(total)
                        )
                    } else {
                        AppCache.human(done)
                    }
                    if (pct >= 100) progressText.setText(R.string.tts_unzipping)
                }
            }
            busy = false
            progress.visibility = View.GONE
            progressText.visibility = View.GONE
            btnDownload.isEnabled = true
            btnDownload.alpha = 1f
            result.onSuccess {
                Toast.makeText(
                    this@TtsModelActivity, R.string.tts_download_ok, Toast.LENGTH_SHORT
                ).show()
                render()
                // 模型一就绪就顺手把语音库生成了：不生成的话第一次导航的头几句
                // 都要现合成，用户会以为"这软件反应慢"
                buildLibrary()
            }.onFailure { e ->
                Toast.makeText(
                    this@TtsModelActivity,
                    getString(R.string.tts_download_fail, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
                render()
            }
        }
    }

    private fun confirmDelete() {
        com.motohud.ble.ui.common.MotoDialog.builder(this)
            .setTitle(R.string.tts_delete)
            .setMessage(R.string.tts_delete_msg)
            .setPositiveButton(R.string.delete) { d, _ ->
                testSpeaker?.release()
                testSpeaker = null
                TtsModelStore.delete(this)
                render()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 试听。
     *
     * 用一个**带路名的真实导航文案**，这样用户能直接听出差别 ——
     * 语音包只能说"前方 300 米右转"，这里能念出"右转进入黄桷坪正街"。
     * （黄桷坪是故意选的：它正好验证多音字替换有没有生效。）
     */
    private fun test() {
        if (busy) return
        busy = true
        btnTest.isEnabled = false
        Toast.makeText(this, R.string.tts_test_preparing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val speaker = testSpeaker ?: SherpaSpeaker(this@TtsModelActivity).also {
                testSpeaker = it
            }
            // 模型加载在后台线程，等它就绪再播，否则会掉到语音包兜底上（听起来没变化）
            val ready = speaker.awaitReady(12_000)
            busy = false
            btnTest.isEnabled = true
            if (!ready) {
                Toast.makeText(
                    this@TtsModelActivity, R.string.tts_test_fail, Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            speaker.speak(
                getString(R.string.tts_test_text),
                SpeechPriority.INFO
            )
        }
    }

    override fun onDestroy() {
        testSpeaker?.release()
        testSpeaker = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, TtsModelActivity::class.java))
        }
    }
}
