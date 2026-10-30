package com.motohud.ble.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播报距离分档。
 *
 * 分档表是整条播报链路的地基：档位既决定"说多少"，也决定"什么时候说"。
 * 改一个数字，`GuidanceActivity` 的阈值阶梯、`prewarmTexts` 的预合成清单、
 * 内置语音包的 168 条录音全都要跟着对上 —— 所以这里把边界钉死。
 */
class SpeechScriptTest {

    @Test
    fun `零和负数不出距离词`() {
        assertEquals("", SpeechScript.distance(0))
        assertEquals("", SpeechScript.distance(-1))
        assertEquals("", SpeechScript.span(0))
    }

    @Test
    fun `档位边界`() {
        assertEquals("前方 50 米", SpeechScript.distance(1))
        assertEquals("前方 50 米", SpeechScript.distance(75))
        assertEquals("前方 100 米", SpeechScript.distance(76))
        assertEquals("前方 100 米", SpeechScript.distance(150))
        assertEquals("前方 200 米", SpeechScript.distance(151))
        assertEquals("前方 300 米", SpeechScript.distance(251))
        assertEquals("前方 500 米", SpeechScript.distance(401))
        assertEquals("前方 800 米", SpeechScript.distance(651))
        assertEquals("前方 1 公里", SpeechScript.distance(901))
        assertEquals("前方 2 公里", SpeechScript.distance(1501))
        assertEquals("前方 5 公里", SpeechScript.distance(3001))
        assertEquals("前方 10 公里", SpeechScript.distance(7001))
        assertEquals("前方 10 公里", SpeechScript.distance(50_000))
    }

    /**
     * `distance` 与 `span` 必须**完全同档**。
     *
     * 两者是同一个距离的两种句式（「前方 300 米右转」vs「沿 X 路行驶 300 米」）。
     * 一旦档位不一致，合成缓存会成倍膨胀，预合成也会漏 —— 表现是某些距离的
     * 播报要现场合成，慢半拍。
     */
    @Test
    fun `两个距离词表的档位完全一致`() {
        for (m in 1..20_000) {
            val d = SpeechScript.distance(m)
            val s = SpeechScript.span(m)
            assertNotEquals("$m 米不应为空", "", d)
            assertEquals("$m 米：distance 多了「前方」前缀", "前方 $s", d)
        }
    }

    @Test
    fun `动作词与服务端图标码对应`() {
        assertEquals("左转", SpeechScript.action(IconType.LEFT))
        assertEquals("右转", SpeechScript.action(IconType.RIGHT))
        assertEquals("进入环岛", SpeechScript.action(IconType.ENTER_ROUNDABOUT))
        assertEquals("驶出环岛", SpeechScript.action(IconType.OUT_ROUNDABOUT))
        assertEquals("掉头", SpeechScript.action(IconType.LEFT_TURN_AROUND))
        assertEquals("直行", SpeechScript.action(IconType.STRAIGHT))
    }

    /** 内置语音包念不出任意数字，所以每个档位都必须能在包里找到对应片段 */
    @Test
    fun `所有档位都在语音包清单里`() {
        val pack = packPhrases()
        for (m in listOf(50, 100, 200, 300, 500, 800, 1000, 2000, 5000, 10_000)) {
            val d = SpeechScript.distance(m)
            assertTrue("语音包缺少「$d」", pack.contains(d))
        }
    }

    /**
     * 从 assets 的 index.txt 读清单 —— 测试跑在 JVM 上，assets 是普通文件，
     * 直接按路径读比反射 Resources 可靠。
     */
    private fun packPhrases(): Set<String> {
        val f = java.io.File("src/main/assets/voicepack/index.txt")
        if (!f.exists()) return emptySet()
        return f.readLines(Charsets.UTF_8)
            .mapNotNull { it.substringBefore('\t').takeIf { t -> t.isNotBlank() } }
            .toSet()
    }
}
