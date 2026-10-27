package com.motohud.ble.navi

import androidx.annotation.RawRes
import com.motohud.ble.R

/**
 * 内置语音包 —— 导航播报的「词库」。
 *
 * ## 为什么不用系统 TTS
 *
 * 语音播报以前依赖 `TextToSpeech`，实测在车机和部分国产 ROM 上根本调不通：
 * 有的机器没有引擎，有的引擎缺中文语音数据，有的只支持 `synthesizeToFile`
 * 不支持 `speak`。用户看到的就是「导航全程哑巴」。
 *
 * 于是把播报改成**构建期合成、运行期拼装**：
 * 「前方 / 300米 / 右转」是三个独立音频片段，按顺序播就是一句完整播报。
 * 全量 47 段合计约 0.5MB，音质是神经网络 TTS 级别，且
 * **不依赖系统任何组件、无 JNI、不挑 ABI**。
 *
 * ## 代价
 *
 * 念不出路名（只说「前方 300 米右转」，不说「右转进入望京路」）。
 * 这是一线车机导航的常见口径，可接受；要读路名就得内置几十 MB 的
 * 离线神经 TTS 模型，与本项目「小包体 + 省流量」的目标冲突。
 *
 * ## 片段命名
 *
 * `vp_` = voice pack；`d*` 距离档、`t*` 全程、`a_*` 动作、其余为固定句。
 * 重新生成见 `tools/gen_voice_pack.py`。
 */
object VoicePack {

    /**
     * 距离档位。
     *
     * 播报只取最近的档，不念精确米数：「前方 300 米」比「前方 273 米」
     * 更符合真人播报习惯，也让片段数量从上百降到十几个。
     */
    @RawRes
    fun distanceClip(meters: Float): Int = when {
        meters <= 75f -> R.raw.vp_d50
        meters <= 150f -> R.raw.vp_d100
        meters <= 250f -> R.raw.vp_d200
        meters <= 400f -> R.raw.vp_d300
        meters <= 650f -> R.raw.vp_d500
        meters <= 1_000f -> R.raw.vp_d800
        meters <= 1_500f -> R.raw.vp_d1k
        meters <= 3_000f -> R.raw.vp_d2k
        meters <= 7_000f -> R.raw.vp_d5k
        else -> R.raw.vp_d10k
    }

    /** 全程播报用的是整句片段（拼"全程约"+"5公里"会把 6.7km 念成 5 公里） */
    @RawRes
    fun totalClip(meters: Float): Int {
        val km = meters / 1000f
        return when {
            km < 1.5f -> R.raw.vp_t1
            km < 2.5f -> R.raw.vp_t2
            km < 3.5f -> R.raw.vp_t3
            km < 4.5f -> R.raw.vp_t4
            km < 5.5f -> R.raw.vp_t5
            km < 6.5f -> R.raw.vp_t6
            km < 7.5f -> R.raw.vp_t7
            km < 8.5f -> R.raw.vp_t8
            km < 9.5f -> R.raw.vp_t9
            km < 12.5f -> R.raw.vp_t10
            km < 17.5f -> R.raw.vp_t15
            km < 25f -> R.raw.vp_t20
            km < 40f -> R.raw.vp_t30
            else -> R.raw.vp_t50
        }
    }

    /**
     * 转向动作片段，入参是车机协议的 `IconType`（与 NA/RE/NE 里的图标号同一套）。
     * 返回 null 表示"这个图标没有对应的可播动作"，调用方直接跳过播报。
     */
    @RawRes
    fun actionClip(iconType: Int): Int? = when (iconType) {
        IconType.LEFT -> R.raw.vp_a_left
        IconType.RIGHT -> R.raw.vp_a_right
        IconType.LEFT_FRONT -> R.raw.vp_a_left_front
        IconType.RIGHT_FRONT -> R.raw.vp_a_right_front
        IconType.LEFT_BACK -> R.raw.vp_a_left_back
        IconType.RIGHT_BACK -> R.raw.vp_a_right_back
        IconType.LEFT_TURN_AROUND, IconType.U_TURN_RIGHT -> R.raw.vp_a_uturn
        IconType.STRAIGHT -> R.raw.vp_a_straight
        IconType.ARRIVED_WAYPOINT -> R.raw.vp_a_waypoint
        IconType.ENTER_ROUNDABOUT, IconType.ENTRY_RING_LEFT -> R.raw.vp_a_roundabout_in
        IconType.OUT_ROUNDABOUT -> R.raw.vp_a_roundabout_out
        IconType.ARRIVED_DESTINATION -> R.raw.vp_a_dest
        IconType.MERGE_LEFT -> R.raw.vp_a_merge_left
        IconType.MERGE_RIGHT -> R.raw.vp_a_merge_right
        else -> null
    }

    /** 「前方 300 米右转」 */
    fun turn(iconType: Int, remainM: Int): List<Int> {
        val action = actionClip(iconType) ?: return emptyList()
        return listOf(R.raw.vp_front, distanceClip(remainM.toFloat()), action)
    }

    /** 「开始导航，全程约 6 公里」 */
    fun start(totalM: Float): List<Int> =
        if (totalM > 0f) listOf(R.raw.vp_start, totalClip(totalM)) else listOf(R.raw.vp_start)

    fun arrived(): List<Int> = listOf(R.raw.vp_arrived, R.raw.vp_end)

    fun rerouted(): List<Int> = listOf(R.raw.vp_reroute)

    fun voiceOn(): List<Int> = listOf(R.raw.vp_voice_on)

    fun voiceOff(): List<Int> = listOf(R.raw.vp_voice_off)

    fun exited(): List<Int> = listOf(R.raw.vp_exited)
}
