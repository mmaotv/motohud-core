package com.motohud.ble.data.bike

/**
 * 车型库。
 *
 * 覆盖国内市场常见的比亚乔集团车型：
 *  - Aprilia（阿普利亚）：SR Max 250 / SR Max 300 / GPR 150 / GPR 250
 *  - Vespa（威斯帕）：Primavera·Sprint 150 / GTS 300
 *  - Piaggio（比亚乔）：Beverly 300 / Medley 150
 *
 * 参数为公开资料整理，可能因年款、地区版本不同而存在差异，页面上已标注「仅供参考」。
 * 新增车型只需在 [ALL] 中追加一条，并把同名图片放进 assets/bikes。
 */
object BikeCatalog {

    private fun spec(label: String, value: String) = BikeSpec(label, value)

    val SR_MAX_250 = BikeModel(
        id = "sr_max_250",
        brand = BikeBrand.APRILIA,
        name = "SR Max 250",
        subtitle = "运动型大踏板 · 244cc",
        image = "sr_max.webp",
        accent = 0xFFFF3B30,
        highlights = listOf("TFT 全彩仪表", "无钥匙启动", "双通道 ABS"),
        specs = listOf(
            spec("发动机", "单缸水冷 SOHC 4 气门"),
            spec("排量", "244 cc"),
            spec("最大功率", "19.0 kW / 8500 rpm"),
            spec("最大扭矩", "22.5 N·m / 7000 rpm"),
            spec("整备质量", "188 kg"),
            spec("座高", "815 mm"),
            spec("油箱容积", "15 L"),
            spec("轮胎", "前 120/70-15 · 后 140/70-14")
        ),
        prefixes = listOf("SR")
    )

    val SR_MAX_300 = BikeModel(
        id = "sr_max_300",
        brand = BikeBrand.APRILIA,
        name = "SR Max 300",
        subtitle = "运动型大踏板 · 278cc",
        image = "sr_max.webp",
        accent = 0xFFFF3B30,
        highlights = listOf("TFT 全彩仪表", "ASR 牵引力控制", "双通道 ABS"),
        specs = listOf(
            spec("发动机", "单缸水冷 SOHC 4 气门"),
            spec("排量", "278 cc"),
            spec("最大功率", "16.5 kW / 7250 rpm"),
            spec("最大扭矩", "23.5 N·m / 6250 rpm"),
            spec("整备质量", "199 kg"),
            spec("座高", "815 mm"),
            spec("油箱容积", "15.5 L"),
            spec("轮胎", "前 120/70-15 · 后 140/70-14")
        ),
        prefixes = listOf("SR")
    )

    val GPR_150 = BikeModel(
        id = "gpr_150",
        brand = BikeBrand.APRILIA,
        name = "GPR 150",
        subtitle = "入门级仿赛 · 149cc",
        image = "gpr.webp",
        accent = 0xFFFF453A,
        highlights = listOf("双翼梁车架", "倒置前叉", "赛道化坐姿"),
        specs = listOf(
            spec("发动机", "单缸水冷 DOHC 4 气门"),
            spec("排量", "149.2 cc"),
            spec("最大功率", "13.6 kW / 9750 rpm"),
            spec("最大扭矩", "14.1 N·m / 7500 rpm"),
            spec("整备质量", "143 kg"),
            spec("座高", "820 mm"),
            spec("油箱容积", "14 L"),
            spec("轮胎", "前 100/80-17 · 后 130/70-17")
        ),
        prefixes = listOf("GPR")
    )

    val GPR_250 = BikeModel(
        id = "gpr_250",
        brand = BikeBrand.APRILIA,
        name = "GPR 250",
        subtitle = "轻量级仿赛 · 249cc",
        image = "gpr.webp",
        accent = 0xFFFF453A,
        highlights = listOf("铝合金后摇臂", "滑动离合", "LED 双透镜大灯"),
        specs = listOf(
            spec("发动机", "单缸水冷 DOHC 4 气门"),
            spec("排量", "249 cc"),
            spec("最大功率", "19.5 kW / 9000 rpm"),
            spec("最大扭矩", "21.5 N·m / 7000 rpm"),
            spec("整备质量", "152 kg"),
            spec("座高", "820 mm"),
            spec("油箱容积", "14 L"),
            spec("轮胎", "前 110/70-17 · 后 140/70-17")
        ),
        prefixes = listOf("GPR")
    )

    val VESPA_150 = BikeModel(
        id = "vespa_150",
        brand = BikeBrand.VESPA,
        name = "Primavera 150",
        subtitle = "复古踏板 · 155cc",
        image = "vespa.webp",
        accent = 0xFF64B5F6,
        highlights = listOf("钢制承载式车身", "i-get 水冷引擎", "无钥匙启动"),
        specs = listOf(
            spec("发动机", "单缸水冷 3 气门 i-get"),
            spec("排量", "155 cc"),
            spec("最大功率", "9.5 kW / 7750 rpm"),
            spec("最大扭矩", "12.8 N·m / 6500 rpm"),
            spec("整备质量", "118 kg"),
            spec("座高", "790 mm"),
            spec("油箱容积", "8 L"),
            spec("轮胎", "前 110/70-11 · 后 120/70-11")
        ),
        prefixes = listOf("VESPA", "PRIMAVERA", "SPRINT")
    )

    val VESPA_300 = BikeModel(
        id = "vespa_300",
        brand = BikeBrand.VESPA,
        name = "GTS 300",
        subtitle = "复古踏板 · 278cc",
        image = "vespa.webp",
        accent = 0xFF64B5F6,
        highlights = listOf("钢制承载式车身", "ASR 牵引力控制", "ABS 防抱死"),
        specs = listOf(
            spec("发动机", "单缸水冷 4 气门"),
            spec("排量", "278 cc"),
            spec("最大功率", "17.5 kW / 8250 rpm"),
            spec("最大扭矩", "26.0 N·m / 5250 rpm"),
            spec("整备质量", "158 kg"),
            spec("座高", "790 mm"),
            spec("油箱容积", "8.5 L"),
            spec("轮胎", "前 120/70-12 · 后 130/70-12")
        ),
        prefixes = listOf("VESPA", "GTS")
    )

    val BEVERLY_300 = BikeModel(
        id = "beverly_300",
        brand = BikeBrand.PIAGGIO,
        name = "Beverly 300",
        subtitle = "大轮踏板 · 278cc",
        image = "beverly.webp",
        accent = 0xFF9E9E9E,
        highlights = listOf("16 英寸大轮", "ASR + ABS", "大容量座桶"),
        specs = listOf(
            spec("发动机", "单缸水冷 4 气门"),
            spec("排量", "278 cc"),
            spec("最大功率", "19.0 kW / 8000 rpm"),
            spec("最大扭矩", "25.0 N·m / 6250 rpm"),
            spec("整备质量", "183 kg"),
            spec("座高", "800 mm"),
            spec("油箱容积", "12 L"),
            spec("轮胎", "前 110/70-16 · 后 140/70-14")
        ),
        prefixes = listOf("BEVERLY", "BYQ")
    )

    val MEDLEY_150 = BikeModel(
        id = "medley_150",
        brand = BikeBrand.PIAGGIO,
        name = "Medley 150",
        subtitle = "大轮踏板 · 155cc",
        image = "beverly.webp",
        accent = 0xFF9E9E9E,
        highlights = listOf("16 英寸大轮", "启停系统", "低油耗通勤"),
        specs = listOf(
            spec("发动机", "单缸水冷 4 气门 i-get"),
            spec("排量", "155 cc"),
            spec("最大功率", "12.1 kW / 8750 rpm"),
            spec("最大扭矩", "14.5 N·m / 6500 rpm"),
            spec("整备质量", "148 kg"),
            spec("座高", "799 mm"),
            spec("油箱容积", "7 L"),
            spec("轮胎", "前 100/80-16 · 后 110/80-16")
        ),
        prefixes = listOf("MEDLEY")
    )

    val ALL: List<BikeModel> = listOf(
        SR_MAX_250, SR_MAX_300, GPR_150, GPR_250,
        VESPA_150, VESPA_300, BEVERLY_300, MEDLEY_150
    )

    val DEFAULT: BikeModel = SR_MAX_300

    fun byId(id: String?): BikeModel = ALL.firstOrNull { it.id == id } ?: DEFAULT

    fun byBrand(brand: BikeBrand?): List<BikeModel> =
        if (brand == null) ALL else ALL.filter { it.brand == brand }

    /**
     * 按车机蓝牙名猜测车型：先匹配具体前缀，再退回品牌默认车型。
     * 猜错也没关系，用户可以在爱车页手动切换。
     */
    fun guessByDevice(deviceName: String?): BikeModel? {
        if (deviceName.isNullOrBlank()) return null
        ALL.firstOrNull { it.matchesDevice(deviceName) }?.let { return it }
        // BYQ 前缀是比亚乔车机的通用标识，归到大踏板系列
        return if (deviceName.startsWith("BYQ", ignoreCase = true)) BEVERLY_300 else null
    }
}
