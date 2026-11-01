package com.mapcast.app

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * H.264 Annex-B akışını TCP üzerinden yayınlar.
 *
 * El sıkışma (handshake): yeni istemci tek satır gönderir:  "MAPCAST <pin>\n"
 *   - PIN doğruysa sunucu "OK\n" yollar, sonra ikili (binary) video akışı başlar.
 *   - PIN yanlışsa "NO\n" yollar ve bağlantıyı kapatır.
 * Bağlanan her istemciye önce SPS/PPS, ardından ilk anahtar kareden (IDR)
 * itibaren akış iletilir.
 */
class TcpStreamServer(
    private val port: Int,
    private val pin: String,
    private val onClientCount: (Int) -> Unit,
    private val requestKeyframe: () -> Unit
) : VirtualDisplayStreamer.FrameSink {

    private class Client(val socket: Socket, val out: OutputStream) {
        @Volatile var started = false // ilk anahtar kareyi aldı mı
    }

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var csd: ByteArray? = null
    @Volatile private var running = false

    // Teşhis sayaçları
    @Volatile private var framesEncoded = 0
    @Volatile private var bytesSent = 0L
    fun stats(): Triple<Int, Long, Int> = Triple(framesEncoded, bytesSent, clients.size)

    fun start() {
        running = true
        Thread({ acceptLoop() }, "mapcast-accept").start()
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket(port)
            ss.reuseAddress = true
            serverSocket = ss
            Log.i(TAG, "TCP server listening on $port")
            while (running) {
                val sock = ss.accept()
                Thread({ handshake(sock) }, "mapcast-client").start()
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "accept loop ended: ${e.message}")
        }
    }

    private fun handshake(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val line = reader.readLine() ?: run { sock.close(); return }
            val parts = line.trim().split(" ")
            val ok = parts.size >= 2 && parts[0] == "MAPCAST" && parts[1] == pin
            val out = sock.getOutputStream()
            if (!ok) {
                out.write("NO\n".toByteArray()); out.flush(); sock.close()
                Log.w(TAG, "client rejected (bad pin)")
                return
            }
            out.write("OK\n".toByteArray()); out.flush()

            val client = Client(sock, out)
            // SPS/PPS her IDR'den önce gömülü gönderilecek; şimdi anahtar kare iste.
            clients.add(client)
            onClientCount(clients.size)
            requestKeyframe()
            Log.i(TAG, "client accepted ${sock.inetAddress}")
        } catch (e: Exception) {
            Log.w(TAG, "handshake failed: ${e.message}")
            try { sock.close() } catch (_: Exception) {}
        }
    }

    override fun onFormat(csd: ByteArray) {
        // SPS/PPS'i sakla; her anahtar karenin önüne gömeceğiz (en uyumlu).
        this.csd = csd
    }

    override fun onFrame(data: ByteArray, isKeyframe: Boolean) {
        framesEncoded++
        if (clients.isEmpty()) return
        val head = if (isKeyframe) csd else null
        val dead = ArrayList<Client>()
        for (c in clients) {
            // Akışa yalnızca anahtar kareyle başla (GOP ortasından girme).
            if (!c.started) {
                if (!isKeyframe) continue
                c.started = true
            }
            try {
                synchronized(c.out) {
                    if (head != null) { c.out.write(head); bytesSent += head.size }
                    c.out.write(data); bytesSent += data.size
                    c.out.flush()
                }
            } catch (e: Exception) {
                dead.add(c)
            }
        }
        if (dead.isNotEmpty()) {
            for (c in dead) { try { c.socket.close() } catch (_: Exception) {}; clients.remove(c) }
            onClientCount(clients.size)
        }
    }

    fun clientCount(): Int = clients.size

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        for (c in clients) { try { c.socket.close() } catch (_: Exception) {} }
        clients.clear()
        Log.i(TAG, "TCP server stopped")
    }

    companion object { private const val TAG = "MapCast/Server" }
}
