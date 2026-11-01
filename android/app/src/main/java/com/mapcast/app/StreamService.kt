package com.mapcast.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

/**
 * Her şeyi bir arada yürüten ön plan (foreground) servisi:
 * hotspot + sanal ekran kodlayıcı + TCP yayın sunucusu + UDP keşif.
 * Böylece uygulama arka plandayken bile yayın devam eder.
 */
class StreamService : Service() {

    private var streamer: VirtualDisplayStreamer? = null
    private var server: TcpStreamServer? = null
    private var beacon: DiscoveryBeacon? = null
    private var hotspot: HotspotManager? = null
    private var presentation: MapPresentation? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ipTicker = object : Runnable {
        override fun run() {
            val ips = localIps()
            StreamState.update { it.copy(ip = primaryIp(ips) ?: it.ip, allIps = ips.joinToString("  ")) }
            handler.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundSafely()
        startStreaming()
        return START_STICKY
    }

    private fun startStreaming() {
        val pin = Random.nextInt(1000, 9999).toString()
        val ips = localIps()
        val ip = primaryIp(ips) ?: "192.168.49.1"

        StreamState.update {
            it.copy(running = true, ip = ip, allIps = ips.joinToString("  "),
                port = TCP_PORT, pin = pin, clients = 0,
                message = "Yayında • Laptopu AYNI WiFi'a bağla → aşağıdaki IP + PIN ile 'Yayını Aç'")
        }

        val srv = TcpStreamServer(
            port = TCP_PORT,
            pin = pin,
            onClientCount = { n -> StreamState.update { it.copy(clients = n) } },
            requestKeyframe = { streamer?.requestKeyframe() }
        )
        server = srv
        srv.start()

        val vds = VirtualDisplayStreamer(
            context = this,
            width = WIDTH, height = HEIGHT, dpi = DPI,
            fps = FPS, bitRate = BITRATE,
            sink = srv
        )
        streamer = vds
        try {
            vds.start()
            vds.display?.let { display ->
                val p = MapPresentation(this, display)
                p.show()
                presentation = p
            }
        } catch (e: Exception) {
            Log.e(TAG, "streamer start failed: ${e.message}")
            StreamState.update { it.copy(message = "Kodlayıcı hatası: ${e.message}") }
        }

        beacon = DiscoveryBeacon(UDP_PORT) {
            val cur = StreamState.info
            "MAPCAST;ip=${cur.ip};port=${cur.port};pin=1"
        }.also { it.start() }

        hotspot = HotspotManager(this).also {
            it.start(object : HotspotManager.Callback {
                override fun onStarted(ssid: String, passphrase: String) {
                    val freshIp = primaryIp(localIps()) ?: ip
                    StreamState.update { s ->
                        s.copy(ssid = ssid, pass = passphrase, ip = freshIp,
                            message = "Yayında • bu hotspot'a VEYA aynı WiFi'a bağlan, sonra IP + PIN")
                    }
                    updateNotification()
                }
                override fun onFailed(reason: Int) {
                    // Bu telefon hotspot açmaya izin vermedi (örn. MIUI/HyperOS).
                    // Sorun değil: yayın zaten mevcut WiFi IP'sinde çalışıyor.
                    StreamState.update { s ->
                        s.copy(message = "Yayında • Hotspot bu telefonda açılamadı. " +
                            "Laptopu AYNI WiFi'a bağla → IP ${s.ip}  PIN ${s.pin} → 'Yayını Aç'.")
                    }
                    updateNotification()
                }
            })
        }
        updateNotification()
        handler.post(ipTicker)
    }

    private fun stopEverything() {
        handler.removeCallbacks(ipTicker)
        try { presentation?.dismiss() } catch (_: Exception) {}
        presentation = null
        beacon?.stop(); beacon = null
        hotspot?.stop(); hotspot = null
        streamer?.stop(); streamer = null
        server?.stop(); server = null
        StreamState.update { StreamState.Info(running = false, message = "Durduruldu") }
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // --- Ön plan bildirimi ---

    private fun startForegroundSafely() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "MapCast yayını",
                NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val s = StreamState.info
        val text = buildString {
            append(s.message)
            if (s.ssid != null) append("\nSSID: ${s.ssid}  Parola: ${s.pass}")
            append("\nIP: ${s.ip}:${s.port}  PIN: ${s.pin}  İstemci: ${s.clients}")
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("MapCast sanal ekran yayını")
            .setContentText(s.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    /** Tüm yerel IPv4 adresleri (WiFi, hotspot, USB tethering vb.). */
    private fun localIps(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        } catch (e: Exception) { emptyList() }
    }

    // Bağlanmak için en olası adres: önce 192.168.x, sonra 10.x/172.x, sonra ilk.
    private fun primaryIp(addrs: List<String>): String? {
        return addrs.firstOrNull { it.startsWith("192.168.") }
            ?: addrs.firstOrNull { it.startsWith("10.") || it.startsWith("172.") }
            ?: addrs.firstOrNull()
    }

    companion object {
        private const val TAG = "MapCast/Service"
        private const val CHANNEL = "mapcast"
        private const val NOTIF_ID = 42
        const val ACTION_START = "com.mapcast.app.START"
        const val ACTION_STOP = "com.mapcast.app.STOP"

        const val TCP_PORT = 8080
        const val UDP_PORT = 8099
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val DPI = 320
        const val FPS = 30
        const val BITRATE = 4_000_000
    }
}
