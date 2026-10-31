package com.mapcast.app

import android.os.Handler
import android.os.Looper

/** Servis ile Activity arasında paylaşılan basit durum (aynı süreç). */
object StreamState {
    data class Info(
        val running: Boolean = false,
        val ssid: String? = null,
        val pass: String? = null,
        val ip: String? = null,
        val port: Int = 0,
        val pin: String = "",
        val clients: Int = 0,
        val message: String = "Hazır"
    )

    @Volatile var info = Info()
        private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile var listener: ((Info) -> Unit)? = null

    fun update(transform: (Info) -> Info) {
        info = transform(info)
        val snap = info
        main.post { listener?.invoke(snap) }
    }
}
