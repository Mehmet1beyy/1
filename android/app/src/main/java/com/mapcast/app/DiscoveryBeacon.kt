package com.mapcast.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Laptop uygulamasının telefonu otomatik bulabilmesi için ağa saniyede bir
 * UDP yayını (broadcast) gönderir:
 *   "MAPCAST;ip=<ip>;port=<port>;pin=<0|1>"
 */
class DiscoveryBeacon(
    private val udpPort: Int,
    private val payload: () -> String
) {
    @Volatile private var running = false

    fun start() {
        running = true
        Thread({
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    val addr = InetAddress.getByName("255.255.255.255")
                    while (running) {
                        val bytes = payload().toByteArray()
                        sock.send(DatagramPacket(bytes, bytes.size, addr, udpPort))
                        Thread.sleep(1000)
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "beacon ended: ${e.message}")
            }
        }, "mapcast-beacon").start()
    }

    fun stop() { running = false }

    companion object { private const val TAG = "MapCast/Beacon" }
}
