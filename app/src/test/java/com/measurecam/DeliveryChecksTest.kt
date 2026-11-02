package com.measurecam

import com.google.gson.Gson
import com.measurecam.data.*
import com.measurecam.storage.CsvExporter
import com.measurecam.util.Chainage
import com.measurecam.util.ReadingChecks
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class DeliveryChecksTest {
    @Test fun zipCsvMatchesPhotoBytesAndMissingNames() {
        val names = listOf("现场.jpg", "失效.jpg", "现场.jpg", "../外部.jpg")
        val records = names.map { CaptureRecord(photoName = it) }
        val sink = ByteArrayOutputStream()
        val opened = mutableListOf<String>()
        val bytes = byteArrayOf(3, 7, 1, -1)
        val counts = CsvExporter.writeBundle(sink, records) {
            opened.add(it)
            if (it == "现场.jpg") bytes.inputStream() else null
        }
        assertEquals(1 to 2, counts)
        assertEquals(listOf("现场.jpg", "失效.jpg"), opened)
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(sink.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes()
            }
        }
        assertArrayEquals(bytes, files["照片/现场.jpg"])
        val csv = files.getValue("记录.csv").toString(Charsets.UTF_8)
        assertTrue(csv.startsWith("\uFEFF"))
        assertEquals(2, csv.split("照片/现场.jpg").size - 1)
        assertEquals("失效.jpg\r\n../外部.jpg", files.getValue("缺失照片.txt").toString(Charsets.UTF_8))
        assertEquals(3, files.size)
    }

    @Test fun bothRangeEndpointsAreIncludedAndEitherMayBeUnset() {
        val range = ReadingRange(-5.0, 5.0)
        assertNull(ReadingChecks.warning("-5", null, range))
        assertNull(ReadingChecks.warning("5", null, range))
        assertNotNull(ReadingChecks.warning("-5.1", null, ReadingRange(min = -5.0)))
        assertNotNull(ReadingChecks.warning("5.1", null, ReadingRange(max = 5.0)))
    }

    @Test fun tenfoldWarningsWorkInBothDirectionsWithNegativeReadings() {
        assertNotNull(ReadingChecks.warning("100", "10", null))
        assertNotNull(ReadingChecks.warning("-1", "-10", null))
        assertNull(ReadingChecks.warning("99", "10", null))
        assertNull(ReadingChecks.warning("0", "10", null))
        assertNull(ReadingChecks.warning("5", "0", null))
        assertNull(ReadingChecks.warning("", "10", ReadingRange(min = 2.0)))
    }

    @Test fun chainageLimitAppliesAfterRoundingAndAcrossKilometres() {
        val origin = 980.06
        val capped = Chainage.limitedAdvance(origin, 200.0, 50)
        val displayed = Chainage.parse(Chainage.format(capped))!!
        assertFalse(Chainage.exceedsLimit(origin, displayed, 50))
        assertEquals("K1+030", Chainage.format(capped))
        assertTrue(Chainage.exceedsLimit(origin, 1030.1, 50))
        assertEquals("K1+000", Chainage.format(999.99))
        assertEquals(12.5, Chainage.parse("12.5")!!, 0.0)
    }

    @Test fun dragPositionScalesWithAvailableTravelAndClampsAtTheEdge() {
        val small = WatermarkArea(10f, 20f, 200f, 100f)
        val point = small.positionAt(60f, 95f)
        assertEquals(WatermarkPosition(0.25f, 0.75f), point)
        val large = WatermarkArea(20f, 40f, 400f, 200f)
        assertEquals(120f, large.x(point), 0f)
        assertEquals(190f, large.y(point), 0f)
        assertEquals(WatermarkPosition(1f, 0f), small.positionAt(300f, -1f))
        assertEquals(WatermarkPosition(0f, 0f), WatermarkArea(0f, 0f, 0f, 0f).positionAt(3f, 4f))
    }

    @Test fun oldSettingsKeepPresetAndReceiveDefaultLimit() {
        val template = Gson().fromJson("""{"watermarkAnchor":"tr"}""", CaptureTemplate::class.java)
        assertNull(template.watermarkPosition)
        assertEquals(50, template.advanceLimit())
        assertEquals(WatermarkPosition(1f, 0f), WatermarkPosition.fromAnchor(template.anchor()))
        val record = CaptureRecord(watermarkPosition = WatermarkPosition(.36f, .83f),
            fields = listOf(RecordField("深度", warningRange = ReadingRange(5.0, 9.0))))
        assertEquals(record, Gson().fromJson(Gson().toJson(record), CaptureRecord::class.java))
    }
}
