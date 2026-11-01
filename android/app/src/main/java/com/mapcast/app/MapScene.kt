package com.mapcast.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/**
 * Harita simülasyonunun çizimi — tek bir Canvas'a çizer.
 * Hem GL render hem (istenirse) View bu fonksiyonu kullanır.
 * Gerçek projende burayı kendi harita çiziminle değiştirebilirsin.
 */
object MapScene {
    private val bg = Paint().apply { color = Color.parseColor("#0E1512") }
    private val grid = Paint().apply {
        color = Color.parseColor("#1C2A24"); strokeWidth = 2f; isAntiAlias = true
    }
    private val road = Paint().apply {
        color = Color.parseColor("#26382F"); strokeWidth = 14f; isAntiAlias = true
    }
    private val routePaint = Paint().apply {
        color = Color.parseColor("#3DDC97"); strokeWidth = 6f; isAntiAlias = true
        style = Paint.Style.STROKE
    }
    private val marker = Paint().apply { color = Color.parseColor("#7CE0B0"); isAntiAlias = true }
    private val markerDark = Paint().apply { color = Color.parseColor("#0E1512"); isAntiAlias = true }
    private val hud = Paint().apply {
        color = Color.parseColor("#E6F2EC"); textSize = 30f; isAntiAlias = true
    }
    private val hudMuted = Paint().apply {
        color = Color.parseColor("#8FB3A3"); textSize = 24f; isAntiAlias = true
    }

    fun draw(canvas: Canvas, width: Int, height: Int, t: Double) {
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bg)

        // Kaydırılan ızgara (hareket hissi)
        val step = 80f
        val offset = ((t * 60.0) % step).toFloat()
        var x = -offset
        while (x < w) { canvas.drawLine(x, 0f, x, h, grid); x += step }
        var y = -offset
        while (y < h) { canvas.drawLine(0f, y, w, y, grid); y += step }

        // Ana caddeler
        canvas.drawLine(0f, h * 0.35f, w, h * 0.35f, road)
        canvas.drawLine(0f, h * 0.72f, w, h * 0.72f, road)
        canvas.drawLine(w * 0.28f, 0f, w * 0.28f, h, road)
        canvas.drawLine(w * 0.66f, 0f, w * 0.66f, h, road)

        // Dairesel rota + üzerinde ilerleyen araç
        val cx = w / 2f
        val cy = h / 2f
        val rx = w * 0.30f
        val ry = h * 0.26f
        val route = Path()
        var a = 0.0
        while (a <= Math.PI * 2 + 0.01) {
            val px = cx + rx * cos(a).toFloat()
            val py = cy + ry * sin(a).toFloat()
            if (a == 0.0) route.moveTo(px, py) else route.lineTo(px, py)
            a += 0.15
        }
        canvas.drawPath(route, routePaint)

        val ang = t * 0.7
        val mx = cx + rx * cos(ang).toFloat()
        val my = cy + ry * sin(ang).toFloat()
        canvas.drawCircle(mx, my, 18f, marker)
        canvas.drawCircle(mx, my, 8f, markerDark)

        // HUD / sahte telemetri
        val lat = 41.0082 + 0.0009 * sin(ang)
        val lon = 28.9784 + 0.0009 * cos(ang)
        val speed = (40 + 15 * sin(t)).toInt()
        canvas.drawText("MapCast • sanal ekran yayını", 24f, 48f, hud)
        canvas.drawText("Konum: %.4f, %.4f".format(lat, lon), 24f, h - 70f, hudMuted)
        canvas.drawText("Hız: $speed km/s   •   t=%.1fs".format(t), 24f, h - 36f, hudMuted)
    }
}
