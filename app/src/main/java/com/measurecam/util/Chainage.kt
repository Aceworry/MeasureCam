package com.measurecam.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 桩号（链程）的解析、格式化与按位移推进。
 *
 * 工程桩号的写法是「千米 + 米」：K0+350 表示从起点起 350 米，K12+050 表示 12050 米。
 * 现场手填时格式五花八门，这里尽量宽容：
 *   K0+350 / k0+350 / K0+350.5 / 0+350 / 350 / 350.5
 * 全部按「米」解出来。
 */
object Chainage {

    private val PATTERN = Regex("^K?\\s*(\\d{1,4})\\s*(?:\\+\\s*(\\d{1,3}(?:\\.\\d+)?))?$", RegexOption.IGNORE_CASE)

    /** 解析成「从起点算起的米数」，解析不了返回 null */
    fun parse(text: String?): Double? {
        val raw = text?.trim()?.replace("＋", "+")?.replace("　", "") ?: return null
        if (raw.isEmpty()) return null

        if ('+' !in raw) {
            val metres = raw.removePrefix("K").removePrefix("k").trim()
            if (!metres.matches(Regex("\\d{1,7}(?:\\.\\d+)?"))) return null
            return metres.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        val m = PATTERN.find(raw) ?: return null
        val major = m.groupValues[1].toDoubleOrNull() ?: return null

        val minorText = m.groupValues[2]

        val minor = minorText.toDoubleOrNull() ?: return null
        return major * 1000.0 + minor
    }

    /** 取整后的推荐桩号也必须满足上限，按显示精度向下取边界。 */
    fun limitedAdvance(last: Double, move: Double, limit: Int): Double {
        val upper = floor((last + limit) * 10.0) / 10.0
        return advance(last, move).coerceIn(last, upper)
    }

    fun exceedsLimit(last: Double, next: Double, limit: Int): Boolean =
        next - last > limit + 0.0000001

    /** 米数 → 标准桩号写法，如 350 → K0+350，12050 → K12+050 */
    fun format(meters: Double): String {
        if (meters.isNaN() || meters.isInfinite()) return ""
        val neg = meters < 0
        val tenths = (abs(meters) * 10).roundToLong()
        val km = tenths / 10000
        val rest = tenths % 10000
        val prefix = if (neg) "K-" else "K"
        val body = if (rest % 10 == 0L) String.format(Locale.US, "%s%d+%03d", prefix, km, rest / 10)
            else String.format(Locale.US, "%s%d+%03d.%d", prefix, km, rest / 10, rest % 10)
        return body
    }

    /** 按位移推进：last + move，结果取整到米 */
    fun advance(lastMeters: Double, moveMeters: Double): Double {
        if (moveMeters.isNaN() || moveMeters <= 0.0) return lastMeters
        return (lastMeters + moveMeters).roundToLong().toDouble()
    }

    /**
     * 两点间地表距离（米），Haversine 公式。
     * 精度足够——桩号只需要米级。
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371008.8 // 地球平均半径
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
