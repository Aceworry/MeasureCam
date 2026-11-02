package com.measurecam

import com.google.gson.Gson
import com.measurecam.data.*
import com.measurecam.util.WeatherService
import com.measurecam.storage.CsvExporter
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class StyleWeatherTest {
    private val appearance = FieldAppearance(TextFormat(bold = false, underline = true, color = "#FF0000"),
        TextFormat(bold = true, background = "#FFEB3B"))
    @Test fun oldTemplateAndRecordKeepDefaults() {
        val t = Gson().fromJson("{}", CaptureTemplate::class.java)
        assertNull(t.watermarkStyle); assertFalse(t.showWeather)
        assertEquals(1f, t.scale())
        val r = Gson().fromJson("{}", CaptureRecord::class.java)
        assertNull(r.weather); assertNull(r.watermarkStyle)
    }
    @Test fun stylesRoundTripWithoutLosingFalseOrColor() {
        val r = CaptureRecord(watermarkStyle = WatermarkStyle(opacity = 0.25f), weather = "晴 24℃",
            fields = listOf(RecordField("深度", values = listOf("850"), appearance = appearance)))
        assertEquals(r, Gson().fromJson(Gson().toJson(r), CaptureRecord::class.java))
    }
    @Test fun templateOrderRoundTripsWithStableIds() {
        val a = FieldDef(id = "a", label = "深度", appearance = appearance)
        val b = FieldDef(id = "b", label = "宽度")
        val t = CaptureTemplate(categories = mutableListOf(MeasureCategory(name = "测试分类", fields = mutableListOf(b, a))))
        val reloaded = Gson().fromJson(Gson().toJson(t), CaptureTemplate::class.java)
        assertEquals(listOf("b", "a"), reloaded.activeCategories().single().fieldList().map { it.id })
        assertEquals(appearance, reloaded.activeCategories().single().fieldList()[1].appearance)
    }
    @Test fun opacityBoundariesAreSafe() {
        assertEquals(0, WatermarkStyle(opacity = -1f).alpha())
        assertEquals(255, WatermarkStyle(opacity = 2f).alpha())
        assertEquals(153, WatermarkStyle(opacity = Float.NaN).alpha())
    }
    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse("2026-10-30 06:00")!!.time
    private val payload = """{"properties":{"timeseries":[{"time":"2026-10-30T06:00:00Z","data":{"instant":{"details":{"air_temperature":24.5}},"next_1_hours":{"summary":{"symbol_code":"partlycloudy_day"}}}}]}}"""
    @Test fun forecastDecodesWithoutSourceSuffix() {
        assertEquals("天气：多云 24.5℃", WeatherService.parse(payload, now()))
    }
    @Test fun staleAndMalformedWeatherIsRejected() {
        assertNull(WeatherService.parse(payload, now() + 2 * 60 * 60_000))
        assertNull(WeatherService.parse("{}", now()))
        assertNull(WeatherService.parse("not-json", now()))
    }
    @Test fun rainSnowThunderAndUnknownAreDistinguished() {
        assertEquals("雷雨", WeatherService.symbol("heavyrainandthunder"))
        assertEquals("雨夹雪", WeatherService.symbol("sleetshowers_day"))
        assertEquals("雪", WeatherService.symbol("snow_night"))
        assertEquals("天气状况未知", WeatherService.symbol("new_code"))
    }
    @Test fun invalidTemperatureIsRejected() { assertNull(WeatherService.parse(payload.replace("24.5", "999"), now())) }
    @Test fun weatherSnapshotIsIncludedInExport() {
        val csv = CsvExporter.build(listOf(CaptureRecord(weather = "天气：晴 24℃")))
        assertTrue(csv.substringBefore("\r\n").contains("天气")); assertTrue(csv.contains("天气：晴 24℃"))
    }
}

