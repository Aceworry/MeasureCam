package com.measurecam

import com.measurecam.data.FieldRole
import com.measurecam.data.RecordField
import com.measurecam.data.WatermarkAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 水印落点与记录字段的纯逻辑测试。
 * 这两块都不碰 Android 框架，能直接在 JVM 上跑。
 */
class ModelsTest {

    // ---------------- 九宫格 ----------------

    @Test
    fun anchor_fromKey_known() {
        assertEquals(WatermarkAnchor.TOP_RIGHT, WatermarkAnchor.fromKey("tr"))
        assertEquals(WatermarkAnchor.MIDDLE_CENTER, WatermarkAnchor.fromKey("mc"))
        assertEquals(WatermarkAnchor.BOTTOM_LEFT, WatermarkAnchor.fromKey("bl"))
    }

    @Test
    fun anchor_fromKey_legacyFallback() {
        // v1.2 的记录没有这个键，必须回到左下角，位置不能跳
        assertEquals(WatermarkAnchor.BOTTOM_LEFT, WatermarkAnchor.fromKey(null))
        assertEquals(WatermarkAnchor.BOTTOM_LEFT, WatermarkAnchor.fromKey(""))
        assertEquals(WatermarkAnchor.BOTTOM_LEFT, WatermarkAnchor.fromKey("乱码"))
    }

    @Test
    fun anchor_rowColMapping() {
        assertEquals(0, WatermarkAnchor.TOP_LEFT.row)
        assertEquals(0, WatermarkAnchor.TOP_LEFT.col)
        assertEquals(1, WatermarkAnchor.MIDDLE_CENTER.row)
        assertEquals(1, WatermarkAnchor.MIDDLE_CENTER.col)
        assertEquals(2, WatermarkAnchor.BOTTOM_RIGHT.row)
        assertEquals(2, WatermarkAnchor.BOTTOM_RIGHT.col)
    }

    @Test
    fun anchor_of_roundTrip() {
        WatermarkAnchor.entries.forEach { a ->
            assertEquals(a, WatermarkAnchor.of(a.row, a.col))
        }
    }

    @Test
    fun anchor_of_clampsOutOfRange() {
        assertEquals(WatermarkAnchor.TOP_LEFT, WatermarkAnchor.of(-1, -5))
        assertEquals(WatermarkAnchor.BOTTOM_RIGHT, WatermarkAnchor.of(9, 9))
    }

    @Test
    fun anchor_keysAreUnique() {
        val keys = WatermarkAnchor.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    // ---------------- 记录字段 ----------------

    @Test
    fun recordField_chainageRole() {
        val f = RecordField(label = "测点桩号", unit = "", values = listOf("K0+350"), role = FieldRole.CHAINAGE.name)
        assertTrue(f.isChainage())
    }

    @Test
    fun recordField_plainRole() {
        val f = RecordField(label = "深度", unit = "mm", values = listOf("1000"))
        assertFalse(f.isChainage())
    }

    @Test
    fun recordField_display_multiValue() {
        val f = RecordField(label = "平整度（水平）", unit = "mm", values = listOf("3.1", "3.2"))
        assertEquals("平整度（水平）: 3.1 / 3.2 mm", f.display())
    }

    @Test
    fun recordField_display_singleValue() {
        val f = RecordField(label = "宽度", unit = "mm", values = listOf("452"))
        assertEquals("宽度: 452 mm", f.display())
    }

    @Test
    fun recordField_display_noUnit() {
        val f = RecordField(label = "测点桩号", unit = "", values = listOf("K0+350"))
        assertEquals("测点桩号: K0+350", f.display())
    }

    @Test
    fun recordField_display_empty() {
        val f = RecordField(label = "深度", unit = "mm", values = listOf("", " "))
        assertEquals("", f.display())
    }

    @Test
    fun recordField_display_dropsBlankSlots() {
        // 两个读数只填了一个，不要渲染出 "3.1 / " 这种尾巴
        val f = RecordField(label = "平整度", unit = "mm", values = listOf("3.1", ""))
        assertEquals("平整度: 3.1 mm", f.display())
    }
}
