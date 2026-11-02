package com.measurecam.data

import java.util.Calendar

/** 统一列表和导出的筛选范围；时间边界使用设备本地日历。 */
data class RecordFilter(
    val query: String = "",
    val category: String? = null,
    val days: Int = 0
) {
    fun apply(records: List<CaptureRecord>, now: Long = System.currentTimeMillis()): List<CaptureRecord> {
        val since = if (days > 0) Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1 - days)
        }.timeInMillis else Long.MIN_VALUE
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return records.filter { r ->
            (category == null || r.categoryName == category) && r.timeMillis >= since &&
                (terms.isEmpty() || buildString {
                    append(r.chainage).append(' ').append(r.templateName).append(' ')
                    append(r.categoryName).append(' ').append(r.photoName).append(' ')
                    append(r.address.orEmpty()).append(' ')
                    (r.stickyList() + r.fieldList()).forEach {
                        append(it.label).append(' ').append(it.valueList().joinToString(" ")).append(' ')
                    }
                }.let { text -> terms.all { text.contains(it, ignoreCase = true) } })
        }
    }
}
