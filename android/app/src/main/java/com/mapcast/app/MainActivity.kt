package com.mapcast.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mapcast.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var running = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val locationOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (locationOk) startService() else
            binding.txtStatus.text = "Hotspot için konum izni gerekli. Lütfen izin ver ve tekrar dene."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener {
            if (running) stopService() else requestPermsThenStart()
        }

        StreamState.listener = { info -> render(info) }
        render(StreamState.info)
    }

    override fun onDestroy() {
        StreamState.listener = null
        super.onDestroy()
    }

    private fun requestPermsThenStart() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startService() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startService() {
        val i = Intent(this, StreamService::class.java).setAction(StreamService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopService() {
        val i = Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP)
        startService(i)
    }

    private fun render(info: StreamState.Info) {
        running = info.running
        binding.btnToggle.text = if (running) "Yayını Durdur" else "Yayını Başlat"
        binding.txtStatus.text = buildString {
            appendLine("Durum: ${info.message}")
            if (info.running) {
                appendLine()
                appendLine("Bağlanılacak WiFi")
                appendLine("  SSID   : ${info.ssid ?: "(açılıyor…)"}")
                appendLine("  Parola : ${info.pass ?: "(açılıyor…)"}")
                appendLine()
                appendLine("Yayın")
                appendLine("  Adres  : ${info.ip}:${info.port}")
                appendLine("  PIN    : ${info.pin}")
                appendLine("  İstemci: ${info.clients}")
                if (info.stats.isNotBlank()) appendLine("  ${info.stats}")
                if (info.allIps.isNotBlank()) {
                    appendLine()
                    appendLine("Tüm IP adayları (laptopta birini dene):")
                    appendLine("  ${info.allIps}")
                }
            }
        }.trim()
    }
}
