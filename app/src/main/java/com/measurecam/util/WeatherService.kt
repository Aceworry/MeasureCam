package com.measurecam.util

import android.content.Context
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** MET Norway 小时预报；缓存整个响应，并只选取与快门时间接近的时段。 */
object WeatherService {
    private const val PREF = "weather_cache"
    private fun time(text: String): Long = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(text)?.time ?: 0L

    fun symbol(code: String): String {
        val base = code.substringBefore('_')
        return when {
            "thunder" in base -> "雷雨"
            "sleet" in base -> "雨夹雪"
            "snow" in base -> "雪"
            "heavyrain" in base -> "大雨"
            "lightrain" in base -> "小雨"
            "rain" in base -> "雨"
            base == "clearsky" -> "晴"
            base == "fair" -> "晴间多云"
            base == "partlycloudy" -> "多云"
            base == "cloudy" -> "阴"
            base == "fog" -> "雾"
            else -> "天气状况未知"
        }
    }
    internal fun parse(raw: String, now: Long): String? = runCatching {
        val series = JsonParser.parseString(raw).asJsonObject.getAsJsonObject("properties").getAsJsonArray("timeseries")
        val nearest = series.minByOrNull { abs(time(it.asJsonObject.get("time").asString) - now) }?.asJsonObject ?: return null
        if (abs(time(nearest.get("time").asString) - now) > 90 * 60_000L) return null
        val data = nearest.getAsJsonObject("data")
        val temp = data.getAsJsonObject("instant").getAsJsonObject("details").get("air_temperature").asDouble
        if (!temp.isFinite() || temp !in -100.0..70.0) return null
        val forecast = data.getAsJsonObject("next_1_hours") ?: data.getAsJsonObject("next_6_hours") ?: return null
        val summary = symbol(forecast.getAsJsonObject("summary").get("symbol_code").asString)
        "天气：$summary ${String.format(Locale.US, "%.1f", temp)}℃"
    }.getOrNull()

    fun cached(c: Context, lat: Double?, lon: Double?, now: Long = System.currentTimeMillis()): String? {
        if (lat == null || lon == null) return null
        val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val la = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lo = p.getString("lon", null)?.toDoubleOrNull() ?: return null
        if (Chainage.distanceMeters(lat, lon, la, lo) > 5000 || now - p.getLong("fetched", 0) !in 0..60 * 60_000L) return null
        return parse(p.getString("body", "").orEmpty(), now)
    }

    /** 后台线程调用；尊重服务端过期时间，相同位置失败后至少等待 5 分钟。 */
    @Synchronized fun refresh(c: Context, lat: Double, lon: Double) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val key = String.format(Locale.US, "%.2f,%.2f", lat, lon)
        if (key == p.getString("requestKey", null) && now < p.getLong("nextRequest", 0)) return
        p.edit().putString("requestKey", key).putLong("nextRequest", now + 5 * 60_000L).apply()
        val parts = key.split(',')
        val connection = URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${parts[0]}&lon=${parts[1]}").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "MeasureCam/1.8 (com.measurecam; Android field camera)")
            if (connection.responseCode != 200) return
            val body = connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder(); val buffer = CharArray(4096)
                while (true) { val n = reader.read(buffer); if (n < 0) break; result.append(buffer, 0, n); if (result.length > 512_000) error("Response too large") }
                result.toString()
            }
            if (parse(body, now) == null) return
            p.edit().putString("body", body).putString("lat", parts[0]).putString("lon", parts[1])
                .putLong("fetched", now).putLong("nextRequest", maxOf(now + 30 * 60_000L, connection.expiration)).apply()
        } finally { connection.disconnect() }
    }
}
