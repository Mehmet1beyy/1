package com.mapcast.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Yerel hotspot (startLocalOnlyHotspot) açar. Android, rastgele bir SSID ve
 * parola üretir; bunları kullanıcıya gösteririz, laptop bu ağa bağlanır.
 *
 * Not: Bu özellik konum izni ister ve konum servisinin açık olması gerekir;
 * ayrıca telefon aynı anda internet WiFi'ına bağlı kalamaz.
 */
class HotspotManager(private val context: Context) {

    interface Callback {
        fun onStarted(ssid: String, passphrase: String)
        fun onFailed(reason: Int)
    }

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun start(cb: Callback) {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val (ssid, pass) = extractCredentials(res)
                    cb.onStarted(ssid, pass)
                }

                override fun onFailed(reason: Int) {
                    Log.w(TAG, "hotspot failed: $reason")
                    cb.onFailed(reason)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "startLocalOnlyHotspot threw: ${e.message}")
            cb.onFailed(-1)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractCredentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg = res.softApConfiguration
            val ssid = cfg.ssid ?: "?"
            val pass = cfg.passphrase ?: "?"
            return ssid to pass
        }
        val cfg = res.wifiConfiguration
        val ssid = cfg?.SSID ?: "?"
        val pass = cfg?.preSharedKey ?: "?"
        return ssid to pass
    }

    fun stop() {
        try { reservation?.close() } catch (_: Exception) {}
        reservation = null
    }

    companion object { private const val TAG = "MapCast/Hotspot" }
}
