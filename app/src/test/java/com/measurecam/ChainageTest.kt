package com.measurecam

import com.measurecam.data.FieldDef
import com.measurecam.data.FieldType
import com.measurecam.util.Chainage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桩号解析/格式化/推移 与 多值字段格式化的单元测试。
 *
 * 这两块都是纯逻辑，放在这里跑比装到手机上试快得多；
 * 桩号算错会直接污染整套记录，所以值得逐条钉住。
 */
class ChainageTest {

    // ---------- 解析 ----------

    @Test
    fun parse_standardFormat() {
        assertEquals(350.0, Chainage.parse("K0+350")!!, 0.001)
        assertEquals(1050.0, Chainage.parse("K1+050")!!, 0.001)
        assertEquals(12350.0, Chainage.parse("K12+350")!!, 0.001)
    }

    @Test
    fun parse_toleratesMessyInput() {
        assertEquals(350.0, Chainage.parse("k0+350")!!, 0.001)
        assertEquals(350.0, Chainage.parse("  K0 + 350 ")!!, 0.001)
        assertEquals(350.5, Chainage.parse("K0+350.5")!!, 0.001)
        assertEquals(12300.0, Chainage.parse("12+300")!!, 0.001)
        // 光填数字按米理解（现场常见：前缀已经打好了只补数字）
        assertEquals(350.0, Chainage.parse("350")!!, 0.001)
        assertEquals(8.0, Chainage.parse("8")!!, 0.001)
    }

    @Test
    fun parse_rejectsGarbage() {
        assertNull(Chainage.parse(""))
        assertNull(Chainage.parse("abc"))
        assertNull(Chainage.parse("K0+abcd"))
    }

    // ---------- 格式化 ----------

    @Test
    fun format_padsToThreeDigits() {
        assertEquals("K0+000", Chainage.format(0.0))
        assertEquals("K0+350", Chainage.format(350.0))
        assertEquals("K1+050", Chainage.format(1050.0))
        assertEquals("K12+350", Chainage.format(12350.0))
    }

    @Test
    fun format_keepsDecimalWhenNotWhole() {
        assertTrue(Chainage.format(350.5).startsWith("K0+350"))
    }

    @Test
    fun format_parseRoundTrip() {
        val samples = listOf(0.0, 8.0, 350.0, 999.0, 1000.0, 1050.0, 12350.0)
        samples.forEach { m ->
            assertEquals(m, Chainage.parse(Chainage.format(m))!!, 0.001)
        }
    }

    // ---------- 推移 ----------

    @Test
    fun advance_roundsToWholeMeter() {
        assertEquals(362.0, Chainage.advance(350.0, 12.4), 0.001)
        assertEquals(370.0, Chainage.advance(350.0, 20.0), 0.001)
    }

    @Test
    fun advance_ignoresZeroOrInvalidMove() {
        assertEquals(350.0, Chainage.advance(350.0, 0.0), 0.001)
        assertEquals(350.0, Chainage.advance(350.0, -3.0), 0.001)
        assertEquals(350.0, Chainage.advance(350.0, Double.NaN), 0.001)
    }

    // ---------- 位移 ----------

    @Test
    fun distance_samePointIsZero() {
        assertEquals(0.0, Chainage.distanceMeters(30.5, 114.3, 30.5, 114.3), 0.001)
    }

    @Test
    fun distance_oneDegreeLatitude() {
        // 1 度纬度约 111.19 km
        val d = Chainage.distanceMeters(30.0, 114.3, 31.0, 114.3)
        assertEquals(111195.0, d, 60.0)
    }

    @Test
    fun distance_twelveMetersNorth() {
        // 0.000108 度纬度 ≈ 12 米，正是工地上两个相邻测点的量级
        val d = Chainage.distanceMeters(30.500000, 114.300000, 30.500108, 114.300000)
        assertEquals(12.0, d, 0.15)
    }

    @Test
    fun distance_eastWestShrinksWithLatitude() {
        // 同经度差在高纬度对应的地面距离更短
        val low = Chainage.distanceMeters(0.0, 114.0, 0.0, 114.001)
        val high = Chainage.distanceMeters(60.0, 114.0, 60.0, 114.001)
        assertTrue(high < low)
    }

    // ---------- 多值字段 ----------

    @Test
    fun fieldDef_multiValueFormat() {
        val f = FieldDef(label = "平整度（水平）", unit = "mm", valueCount = 2)
        assertEquals("平整度（水平）: 3.1 / 3.2 mm", f.format("3.1/3.2"))
        assertEquals(listOf("3.1", "3.2"), f.splitValue("3.1/3.2"))
        assertEquals("3.1/3.2", f.joinValues(listOf("3.1", "3.2")))
    }

    @Test
    fun fieldDef_multiValueHandlesOnlyOneFilled() {
        val f = FieldDef(label = "平整度（垂直）", unit = "mm", valueCount = 2)
        assertEquals("平整度（垂直）: 2.9 mm", f.format("2.9"))
        assertEquals(listOf("2.9", ""), f.splitValue("2.9"))
    }

    @Test
    fun fieldDef_singleValueFormat() {
        val f = FieldDef(label = "深度", unit = "mm", type = FieldType.INTEGER)
        assertEquals("深度: 1000 mm", f.format("1000"))
        assertEquals("", f.format("  "))
        assertEquals("测点桩号: K0+350", FieldDef(label = "测点桩号").format("K0+350"))
    }
}
