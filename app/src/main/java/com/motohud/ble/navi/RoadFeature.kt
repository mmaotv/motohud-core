package com.motohud.ble.navi

/**
 * 沿途道路要素识别：隧道、桥梁、天桥、立交、匝道、环岛、路口。
 *
 * ## 为什么要自己从文本里认
 *
 * 高德的**免费搜索 SDK（RouteSearch）不提供结构化的道路要素**：`DriveStep`
 * 只有 instruction / action / assistantAction / road / orientation / tollRoad 六个
 * 文本或基础字段，没有"道路类型"。带 `getRoadType()`、隧道桥梁标识的是**导航 SDK
 * （AMapNavi）**，需要商务授权，本项目用不了（见 `两轮车SDK评估.md`）。
 *
 * 所以只能从文本里认。实测数据（重庆主城两条路线、121 条 step）里，要素信息
 * 出现在三个稳定的位置：
 *
 *  1. **`assistantAction`** —— 高德把"不影响转向但要说一声"的要素放这里：
 *     `进入隧道` / `进入匝道` / `进入左岔路` / `进入右岔路` / `进入中间岔道` /
 *     `沿主路行驶` / `到达目的地`。
 *     **这个字段以前根本没被解析过**（`AmapRepo.toRouteStep` 漏了），
 *     导致隧道提示一条都没播过 —— 这是最主要的缺失原因。
 *  2. **`instruction` 的「途径」列举** ——
 *     `沿石板坡立交途径重庆长江大桥、江南大道向南行驶1.8千米右转`，
 *     「途径」后面那串就是沿途经过的道路和桥梁，用「、」分隔。
 *  3. **`road`** —— 有时直接就是要素本身：
 *     `road=向阳隧道` / `road=大石坎立交桥` / `road=五里店立交` / `road=黄花园立交`。
 *
 * ## 环岛的坑
 *
 * 高德返回 `action=进入环岛` 与 `action=**离开**环岛`，
 * 而 `IconMapper` 只认「驶出环岛」——「离开环岛」匹配不上，落到 instruction 兜底时
 * 又因为含「环岛」被判成 **ENTER_ROUNDABOUT**，于是"驶出环岛"被当成"进入环岛"，
 * 播报会说反。这是本次一并修掉的 bug。
 */
enum class RoadFeatureType {
    /** 隧道：进洞前要开灯、注意明暗适应 */
    TUNNEL,
    /** 桥梁（含立交桥、高架桥、跨江大桥）：上桥注意横风、不能随意变道 */
    BRIDGE,
    /** 人行天桥：注意行人 */
    OVERPASS,
    /** 立交枢纽：方向多、容易走错 */
    INTERCHANGE,
    /** 匝道 / 岔路：走错岔口是骑行最常见的错误 */
    RAMP,
    /** 环岛 */
    ROUNDABOUT
}

/** 一个识别出来的道路要素 */
data class RoadFeature(
    val type: RoadFeatureType,
    /** 要素名称，如「向阳隧道」「重庆长江大桥」；可能为空（只知道类型） */
    val name: String = "",
    /** 是否来自 assistantAction（高德明确说了"进入隧道"，可信度最高） */
    val explicit: Boolean = false
) {
    /** 播报里称呼它的名字；没名字时退回类型名 */
    val displayName: String
        get() = name.ifEmpty {
            when (type) {
                RoadFeatureType.TUNNEL -> "隧道"
                RoadFeatureType.BRIDGE -> "桥梁"
                RoadFeatureType.OVERPASS -> "人行天桥"
                RoadFeatureType.INTERCHANGE -> "立交"
                RoadFeatureType.RAMP -> "匝道"
                RoadFeatureType.ROUNDABOUT -> "环岛"
            }
        }
}

object RoadFeatureDetector {

    /** 一个 step 里最多返回的要素数（防止一句播报塞进五个桥名） */
    private const val MAX_PER_STEP = 2

    /**
     * 识别该路段沿途的要素，按「明确程度」排序（assistantAction > road > 途径列举）。
     */
    fun detect(step: RouteStep): List<RoadFeature> {
        val out = LinkedHashMap<String, RoadFeature>()

        fun put(f: RoadFeature) {
            if (out.size >= MAX_PER_STEP) return
            val key = "${f.type}|${f.displayName}"
            if (!out.containsKey(key)) out[key] = f
        }

        // 1) assistantAction：高德明确声明的要素，最可信
        classify(step.assistantAction)?.let { put(it.copy(name = "", explicit = true)) }

        // 2) road 本身就是要素名（向阳隧道 / 大石坎立交桥）
        classify(step.road)?.let { put(it.copy(name = step.road.trim())) }

        // 3) instruction 里「途径 A、B」列举
        passThroughNames(step.instruction).forEach { n ->
            classify(n)?.let { put(it.copy(name = n)) }
        }

        return out.values.toList()
    }

    /**
     * 把一段路名/动作名归类成要素类型；不是要素返回 null。
     *
     * 判定顺序有讲究：**先认「天桥」再认「桥」**，否则「人行天桥」会被当成桥梁，
     * 播成"前方经人行天桥，注意横风"——横风跟人行天桥没关系。
     */
    fun classify(text: String): RoadFeature? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return when {
            t.contains("隧道") -> RoadFeature(RoadFeatureType.TUNNEL)
            t.contains("天桥") -> RoadFeature(RoadFeatureType.OVERPASS)
            t.contains("匝道") || t.contains("岔路") || t.contains("岔道") ->
                RoadFeature(RoadFeatureType.RAMP)
            t.contains("环岛") -> RoadFeature(RoadFeatureType.ROUNDABOUT)
            t.contains("立交") -> RoadFeature(RoadFeatureType.INTERCHANGE)
            t.contains("桥") -> RoadFeature(RoadFeatureType.BRIDGE)
            else -> null
        }
    }

    /**
     * 抽出 instruction 里「途径」后面的这一串名字。
     *
     * 形如：`沿石板坡立交途径重庆长江大桥、江南大道向南行驶1.8千米右转`
     * 只取「途径」到「向<方向>行驶」之间的部分，避免把方向词和距离当路名。
     */
    fun passThroughNames(instruction: String): List<String> {
        val m = PASS_THROUGH.find(instruction) ?: return emptyList()
        val raw = m.groupValues.getOrNull(1).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('、', '，', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 14 }
    }

    /** 「途径 A、B、C」→ A、B、C（到「向<方位>」或句末为止） */
    private val PASS_THROUGH = Regex("途径([^向]{2,40}?)(?=向[东南西北]|行驶|$)")
}
