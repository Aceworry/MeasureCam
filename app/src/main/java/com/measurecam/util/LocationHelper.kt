package com.measurecam.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 定位与地址反查。只用系统能力，不依赖任何第三方地图 Key。
 */
object LocationHelper {

    private val io = Executors.newSingleThreadExecutor()

    private var trackListener: LocationListener? = null
    private var trackManager: LocationManager? = null

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * 持续跟踪位置。取景页用这个：
     *  - 水印上的坐标要是按下快门那一刻的
     *  - 桩号递增要靠「上次拍摄点到当前点的位移」，所以不能只取一次
     * 重复调用会先停掉上一个监听，不会叠加。
     */
    @Suppress("MissingPermission")
    fun startTracking(context: Context, onResult: (lat: Double, lon: Double) -> Unit) {
        if (!hasPermission(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        stopTracking()

        // 缓存点先给一个，避免刚打开时水印空着
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best.time) best = l
        }
        best?.let { onResult(it.latitude, it.longitude) }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onResult(location.latitude, location.longitude)
            }

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2000L, 2f, listener, Looper.getMainLooper()
            )
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 4000L, 5f, listener, Looper.getMainLooper()
            )
            trackListener = listener
            trackManager = lm
        }
    }

    fun stopTracking() {
        val lm = trackManager
        val l = trackListener
        if (lm != null && l != null) {
            runCatching { lm.removeUpdates(l) }
        }
        trackListener = null
        trackManager = null
    }

    /** 先拿缓存点，再等一次实时更新，最多等 4 秒。 */
    @Suppress("MissingPermission")
    fun request(context: Context, onResult: (lat: Double, lon: Double) -> Unit) {
        if (!hasPermission(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        // 缓存点先给一个，保证水印至少有位置
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best.time) best = l
        }
        best?.let { onResult(it.latitude, it.longitude) }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { lm.removeUpdates(this) }
                onResult(location.latitude, location.longitude)
            }

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper()
            )
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper()
            )
        }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { lm.removeUpdates(listener) }
        }, 4000L)
    }

    /** Geocoder 是阻塞调用，必须离开主线程。 */
    fun reverseGeocode(context: Context, lat: Double, lon: Double, onResult: (String?) -> Unit) {
        io.execute {
            val text = runCatching {
                @Suppress("DEPRECATION")
                val list: List<Address>? = Geocoder(context, Locale.CHINA)
                    .getFromLocation(lat, lon, 1)
                list?.firstOrNull()?.let { a ->
                    val parts = mutableListOf<String>()
                    for (i in 0..a.maxAddressLineIndex) {
                        a.getAddressLine(i)?.let { parts.add(it) }
                    }
                    parts.joinToString(" ").ifBlank { null }
                }
            }.getOrNull()
            Handler(Looper.getMainLooper()).post { onResult(text) }
        }
    }
}
