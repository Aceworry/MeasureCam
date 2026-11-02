package com.measurecam.util

/** 界面固定竖向，只有照片跟随物理朝向；10° 缓冲避免在斜握时反复切换。 */
object CaptureOrientation {
    fun rotationFor(degrees: Int, current: Int): Int {
        if (degrees !in 0..359) return current
        val previous = (360 - current * 90) % 360
        val distance = kotlin.math.abs(degrees - previous).let { minOf(it, 360 - it) }
        if (distance < 55) return current
        val quadrant = ((degrees + 45) / 90) % 4
        // Surface 的 rotation 与 OrientationEventListener 的角度方向相反。
        return (4 - quadrant) % 4
    }
}
