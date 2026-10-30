package com.motohud.ble.navi

/**
 * 沿途道路要素的播报文案。
 *
 * ## 两条链路，两套文案
 *
 * - **内置语音包**（[com.motohud.ble.navi.tts.PresetPackSpeaker]，158 条预录 mp3）：
 *   念不出任意桥名、隧道名，只能播**固定句式**。这些句子已经预合成进
 *   `assets/voicepack`，见 `tools/gen_voice_pack.py` 的 `ROAD_FEATURES`，
 *   两边必须逐字一致，否则命中不了。
 * - **离线神经语音**（sherpa）：现场合成，可以念完整名称：
 *   「前方 300 米进入向阳隧道，请开灯」。
 *
 * ## 为什么这些提示值得播
 *
 * 摩托车的实际场景差异很大：
 *  - 隧道：进洞瞬间有明暗适应，必须提前开灯，且洞内常有积水；
 *  - 桥 / 跨江大桥：桥上横风大，大车经过会有气流，不能随意变道；
 *  - 立交：车道多、方向多，走错要绕很远；
 *  - 匝道 / 岔路：骑行最常见的走错就是岔口选错一条；
 *  - 人行天桥：桥下常有行人横穿。
 *
 * ## 触发距离为什么各不相同
 *
 * 按"需要多长时间做出反应"倒推，而不是所有要素同一个值：
 *  - 隧道 400 米：要开灯、减速、摘墨镜，反应时间最长；
 *  - 桥 500 米：桥上不能变道，得提前选好车道；
 *  - 立交 400 米：看清指示牌需要时间；
 *  - 匝道 300 米：看到就该开始并线；
 *  - 天桥 200 米：只是提醒注意行人，不需要提前太多。
 */
object RoadFeatureNarrator {

    /** 各要素的触发距离（距该路段起点的剩余米数） */
    fun triggerDist(type: RoadFeatureType): Int = when (type) {
        RoadFeatureType.TUNNEL -> 400
        RoadFeatureType.BRIDGE -> 500
        RoadFeatureType.INTERCHANGE -> 400
        RoadFeatureType.RAMP -> 300
        RoadFeatureType.OVERPASS -> 200
        // 环岛已经有专门的转向播报（「进入环岛，走第 3 个出口」），不再重复
        RoadFeatureType.ROUNDABOUT -> 0
    }

    /**
     * 是否值得为这个要素开口。
     *
     * 环岛交还给转向播报；其余类型都要播。
     */
    fun worthAnnouncing(type: RoadFeatureType): Boolean = triggerDist(type) > 0

    /**
     * 生成播报文本。
     *
     * @param feature 识别出的要素
     * @param remainM 距该路段起点还有多远（决定"sherpa 链路"里的距离词）
     * @param presetPack true 表示内置语音包，必须用固定句式
     */
    fun text(feature: RoadFeature, remainM: Int, presetPack: Boolean): String {
        val type = feature.type
        if (presetPack) return fixedText(type)
        val d = SpeechScript.distance(remainM)
        val name = feature.displayName
        // 天桥不念名字：人行天桥的名字对骑手没有意义，念了只是噪音
        return when (type) {
            RoadFeatureType.TUNNEL ->
                if (d.isEmpty()) "进入${name}，请开灯" else "${d}进入${name}，请开灯"
            RoadFeatureType.BRIDGE ->
                if (d.isEmpty()) "上${name}，注意横风" else "${d}上${name}，注意横风"
            RoadFeatureType.INTERCHANGE ->
                if (d.isEmpty()) "进入${name}，注意车道" else "${d}进入${name}，注意车道"
            RoadFeatureType.RAMP ->
                if (d.isEmpty()) "前方进入匝道" else "${d}进入匝道"
            RoadFeatureType.OVERPASS -> "前方人行天桥，注意行人"
            RoadFeatureType.ROUNDABOUT -> ""
        }
    }

    /** 内置语音包用的固定句式，与 gen_voice_pack.py 的 ROAD_FEATURES 逐字一致 */
    fun fixedText(type: RoadFeatureType): String = when (type) {
        RoadFeatureType.TUNNEL -> "前方进入隧道，请开灯"
        RoadFeatureType.BRIDGE -> "前方上桥，注意横风"
        RoadFeatureType.INTERCHANGE -> "前方进入立交，注意车道"
        RoadFeatureType.RAMP -> "前方进入匝道"
        RoadFeatureType.OVERPASS -> "前方人行天桥，注意行人"
        RoadFeatureType.ROUNDABOUT -> ""
    }

    /** 预合成清单：固定句式全部预合成一次，整程复用 */
    fun prewarmTexts(): List<String> = RoadFeatureType.values()
        .map { fixedText(it) }
        .filter { it.isNotEmpty() }
}
