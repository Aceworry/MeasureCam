package com.measurecam

import com.google.gson.Gson
import com.measurecam.data.*
import com.measurecam.storage.CsvExporter
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class RecordWorkflowTest {
    private fun time(day: Int, hour: Int = 0) = Calendar.getInstance().apply {
        clear(); set(2026, Calendar.OCTOBER, day, hour, 0, 0)
    }.timeInMillis

    private val records = listOf(
        CaptureRecord(id = "a", timeMillis = time(29), chainage = "K1+050", categoryName = "基平台",
            photoName = "a.jpg", sticky = listOf(RecordField("项目", values = listOf("航道整治"))),
            fields = listOf(RecordField("深度", "mm", listOf("850")))),
        CaptureRecord(id = "b", timeMillis = time(23), categoryName = "坡面", photoName = "b.jpg"),
        CaptureRecord(id = "c", timeMillis = time(22, 23), categoryName = "基平台", photoName = "c.jpg")
    )

    @Test fun emptyFilterPreservesOrder() { assertEquals(records, RecordFilter().apply(records)) }
    @Test fun searchIgnoresCaseAndOuterWhitespace() {
        assertEquals(listOf("a"), RecordFilter(query = "  k1+050 ").apply(records).map { it.id })
    }
    @Test fun searchCombinesProjectAndMeasurement() {
        assertEquals(1, RecordFilter(query = "航道 850").apply(records).size)
        assertTrue(RecordFilter(query = "航道 999").apply(records).isEmpty())
    }
    @Test fun categoryAndSearchIntersect() {
        assertTrue(RecordFilter(query = "850", category = "坡面").apply(records).isEmpty())
    }
    @Test fun dateTodayIncludesMidnight() {
        assertEquals(listOf("a"), RecordFilter(days = 1).apply(records, time(29, 12)).map { it.id })
    }
    @Test fun lastSevenDaysUsesCalendarBoundary() {
        assertEquals(listOf("a", "b"), RecordFilter(days = 7).apply(records, time(29, 12)).map { it.id })
    }
    @Test fun noMatchReturnsEmpty() { assertTrue(RecordFilter(query = "不存在").apply(records).isEmpty()) }
    @Test fun fileNameIsSearchable() { assertEquals("b", RecordFilter(query = "b.jpg").apply(records).single().id) }
    @Test fun legacyTextFieldsRemainEditable() {
        val field = Gson().fromJson("""{"label":"项目名称","values":["航道"]}""", RecordField::class.java)
        assertEquals(FieldType.TEXT, field.inputFieldType())
    }
    @Test fun integerAndSignedDecimalTypesArePreserved() {
        assertEquals(FieldType.INTEGER, RecordField(type = "INTEGER").inputFieldType())
        assertEquals(FieldType.DECIMAL, RecordField(type = "DECIMAL").inputFieldType())
    }
    @Test fun chainageAlwaysUsesText() {
        assertEquals(FieldType.TEXT, RecordField(role = "CHAINAGE", type = "DECIMAL").inputFieldType())
    }
    @Test fun draftCopiesDoNotChangeOriginalRecord() {
        val original = RecordField("深度", "mm", listOf("850"), type = "DECIMAL")
        val draft = original.copy(values = original.valueList().toList())
        draft.values = listOf("900")
        assertEquals(listOf("850"), original.valueList())
        assertEquals("DECIMAL", draft.copy().type)
    }
    @Test fun exportOmitsCustomChainageRoleButKeepsOtherChainageLabels() {
        val r = CaptureRecord(fields = listOf(
            RecordField("测点编号", values = listOf("K1+000"), role = "CHAINAGE"),
            RecordField("桩号偏差", "mm", listOf("2"), role = "NONE")
        ))
        val csv = CsvExporter.build(listOf(r))
        assertFalse(csv.substringBefore("\r\n").contains("测点编号"))
        assertTrue(csv.substringBefore("\r\n").contains("桩号偏差(mm)"))
        assertEquals("桩号偏差 2", CsvExporter.summaryLine(r))
    }
    @Test fun exportEscapesCommasQuotesAndNewlines() {
        val csv = CsvExporter.build(listOf(CaptureRecord(templateName = "项目,\"一\"\n二")))
        assertTrue(csv.contains("\"项目,\"\"一\"\"\n二\""))
    }
    @Test fun exportRetainsAllMultiValueColumns() {
        val csv = CsvExporter.build(listOf(CaptureRecord(fields = listOf(RecordField("平整度", "mm", listOf("3", "4"))))))
        assertTrue(csv.substringBefore("\r\n").contains("平整度1(mm),平整度2(mm)"))
        assertTrue(csv.contains(",3,4\r\n"))
    }
}
