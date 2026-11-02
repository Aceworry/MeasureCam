package com.measurecam

import com.measurecam.data.CaptureRecord
import com.measurecam.data.SOURCE_CAMERA
import com.measurecam.data.SOURCE_IMPORT
import com.measurecam.util.ImageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片导入相关的纯逻辑测试。
 *
 * 解码降采样这块是纯算术，不用碰 BitmapFactory；记录来源的判定也只是字段比较。
 * 真正吃 Android 框架的部分（ExifInterface、Matrix、Canvas）留给实机验证。
 */
class ImportTest {

    // ---------------- 解码降采样 ----------------

    @Test
    fun sampleSize_cameraPhoto_keptFull() {
        // 12MP 相机原图 4000×3000，没超上限，保持原分辨率
        assertEquals(1, ImageLoader.sampleSize(4000, 3000, 4096))
    }

    @Test
    fun sampleSize_midSizePhoto_halved() {
        // 5000×4000 正是旧算法的漏洞：5000/2=2500 < 4096 就放过了，
        // 结果按原样解出 80MB。现在按"降完还超不超"判断，必须降到 2。
        assertEquals(2, ImageLoader.sampleSize(5000, 4000, 4096))
    }

    @Test
    fun sampleSize_highResPhoto_quartered() {
        // 9000/2=4500 仍然超过 4096，得再降一级
        assertEquals(4, ImageLoader.sampleSize(9000, 7000, 4096))
    }

    @Test
    fun sampleSize_lowerLimit_appliesToLongEdge() {
        assertEquals(2, ImageLoader.sampleSize(4000, 3000, 2048))
    }

    @Test
    fun sampleSize_smallPicture_untouched() {
        assertEquals(1, ImageLoader.sampleSize(1080, 1920, 4096))
        assertEquals(1, ImageLoader.sampleSize(800, 600, 4096))
    }

    @Test
    fun sampleSize_invalidInput_fallsBackToOne() {
        assertEquals(1, ImageLoader.sampleSize(0, 0, 4096))
        assertEquals(1, ImageLoader.sampleSize(100, 100, 0))
    }

    @Test
    fun sampleSize_absurdLargeImage_terminates() {
        // 极端大图要能收敛，返回 0 会让 BitmapFactory 直接抛异常
        val s = ImageLoader.sampleSize(100000, 80000, 4096)
        assertTrue(s > 0)
        assertTrue(100000 / s <= 4096)
    }

    // ---------------- 记录来源 ----------------

    @Test
    fun record_defaultSource_isCamera() {
        val r = CaptureRecord()
        assertEquals(SOURCE_CAMERA, r.source)
        assertFalse(r.imported())
    }

    @Test
    fun record_importedWithOriginal_isEditable() {
        val r = CaptureRecord(
            source = SOURCE_IMPORT,
            photoName = "MC_20261029_101500_000.jpg",
            originalName = "MC_20261029_101500_000.jpg"
        )
        assertTrue(r.imported())
        // 留了基底就能改数值、加字段
        assertTrue(r.editable())
    }

    @Test
    fun record_importedWithoutOriginal_notEditable() {
        // 磁盘满导致基底没存下来时，只能看和删
        val r = CaptureRecord(source = SOURCE_IMPORT, photoName = "MC_x.jpg")
        assertTrue(r.imported())
        assertFalse(r.editable())
    }
}
