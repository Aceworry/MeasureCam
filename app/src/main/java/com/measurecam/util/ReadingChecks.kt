package com.measurecam.util

import com.measurecam.data.ReadingRange
import kotlin.math.abs

object ReadingChecks {
    /** 多值字段由调用方逐格传入同位置的前值。零没有可比较的倍数。 */
    fun warning(raw: String, previous: String?, range: ReadingRange?): String? {
        val value = raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        range?.min?.let { if (value < it) return "低于下限 $it" }
        range?.max?.let { if (value > it) return "高于上限 $it" }
        val old = previous?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it != 0.0 } ?: return null
        if (value == 0.0) return null
        val ratio = abs(value / old)
        return when {
            ratio >= 10 -> "与前值 $previous 相比，读数增至 10 倍或以上"
            ratio <= 0.1 -> "与前值 $previous 相比，读数降至十分之一或以下"
            else -> null
        }
    }
}
