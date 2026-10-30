package com.ivd.virtualdisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Sanal ekrana çizilen örnek navigasyon arayüzü.
 *
 * Bu View telefonun fiziksel ekranından tamamen bağımsızdır; bir
 * {@link android.app.Presentation} aracılığıyla VirtualDisplay üzerinde
 * gösterilir. Her karede kendini yeniden geçersiz kılarak (invalidate)
 * sürekli görüntü üretir, böylece VirtualDisplay'in gerçekten kare
 * ürettiği ImageReader tarafından doğrulanabilir.
 *
 * Tasarım 1280x720 hedef çözünürlüğe göre sabit koordinatlarla çizilir ve
 * gerçek genişlik/yükseklik oranına göre ölçeklenir.
 */
public class NavigationView extends View {

    private static final float BASE_W = 1280f;
    private static final float BASE_H = 720f;
    private static final long LOOP_MS = 9000L; // rotanın bir turu

    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint junctionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routeGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint destPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint carPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Saat + test sayacı HUD'u (her saniye değişen canlı içerik)
    private final Paint hudPanel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudValue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudDivider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private long lastClockSecond = -1;
    private String clockText = "--:--:--";
    private int testCounter = 0;

    private final Path routePath = new Path();
    private final RectF rectF = new RectF();

    // Rota noktaları (1280x720 koordinat sisteminde) - yollar üzerinden geçer.
    private final float[] routeX = {150, 150, 400, 400, 750, 750, 1050, 1050};
    private final float[] routeY = {650, 450, 450, 250, 250, 420, 420, 160};

    private final float[] vRoads = {150, 400, 750, 1050}; // dikey yollar (x)
    private final float[] hRoads = {160, 250, 420, 450, 650}; // yatay yollar (y)

    private final long startTime;

    public NavigationView(Context context) {
        super(context);
        startTime = SystemClock.uptimeMillis();

        mapPaint.setColor(Color.parseColor("#E8ECE4"));
        roadPaint.setColor(Color.parseColor("#FFFFFF"));
        roadPaint.setStyle(Paint.Style.STROKE);
        roadPaint.setStrokeCap(Paint.Cap.ROUND);
        roadEdge.setColor(Color.parseColor("#C9D2C2"));
        roadEdge.setStyle(Paint.Style.STROKE);
        roadEdge.setStrokeCap(Paint.Cap.ROUND);
        junctionPaint.setColor(Color.parseColor("#FFFFFF"));

        routeGlow.setColor(Color.parseColor("#662979FF"));
        routeGlow.setStyle(Paint.Style.STROKE);
        routeGlow.setStrokeCap(Paint.Cap.ROUND);
        routeGlow.setStrokeJoin(Paint.Join.ROUND);

        routePaint.setColor(Color.parseColor("#2979FF"));
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);

        destPaint.setColor(Color.parseColor("#E53935"));
        whitePaint.setColor(Color.WHITE);
        carPaint.setColor(Color.parseColor("#1565C0"));

        panelPaint.setColor(Color.parseColor("#E6102027"));

        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        subTextPaint.setColor(Color.parseColor("#B8C7D0"));

        statusPaint.setColor(Color.WHITE);
        statusPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        hudPanel.setColor(Color.parseColor("#EC0D1A20"));
        hudValue.setColor(Color.WHITE);
        hudValue.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        hudLabel.setColor(Color.parseColor("#8FA6B2"));
        hudLabel.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        hudDivider.setColor(Color.parseColor("#33FFFFFF"));
        hudDivider.setStrokeWidth(2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final float sx = getWidth() / BASE_W;
        final float sy = getHeight() / BASE_H;
        final float s = Math.min(sx, sy);

        canvas.save();
        // Ölçekle, böylece 1280x720 koordinatlarıyla çizebiliriz.
        canvas.scale(sx, sy);

        // Arka plan (harita zemini)
        canvas.drawColor(Color.parseColor("#E8ECE4"));
        canvas.drawRect(0, 0, BASE_W, BASE_H, mapPaint);

        // Parklar / bloklar (dekoratif)
        drawBlocks(canvas);

        // Yollar
        roadEdge.setStrokeWidth(44);
        roadPaint.setStrokeWidth(36);
        for (float x : vRoads) {
            canvas.drawLine(x, 40, x, BASE_H - 20, roadEdge);
        }
        for (float y : hRoads) {
            canvas.drawLine(40, y, BASE_W - 20, y, roadEdge);
        }
        for (float x : vRoads) {
            canvas.drawLine(x, 40, x, BASE_H - 20, roadPaint);
        }
        for (float y : hRoads) {
            canvas.drawLine(40, y, BASE_W - 20, y, roadPaint);
        }
        // Kavşaklar
        for (float x : vRoads) {
            for (float y : hRoads) {
                canvas.drawCircle(x, y, 20, junctionPaint);
            }
        }

        // Rota
        routePath.reset();
        routePath.moveTo(routeX[0], routeY[0]);
        for (int i = 1; i < routeX.length; i++) {
            routePath.lineTo(routeX[i], routeY[i]);
        }
        routeGlow.setStrokeWidth(26);
        canvas.drawPath(routePath, routeGlow);
        routePaint.setStrokeWidth(14);
        canvas.drawPath(routePath, routePaint);

        // Başlangıç noktası
        canvas.drawCircle(routeX[0], routeY[0], 16, whitePaint);
        carPaint.setColor(Color.parseColor("#1565C0"));
        canvas.drawCircle(routeX[0], routeY[0], 10, carPaint);

        // Hedef işareti (kırmızı)
        float dx = routeX[routeX.length - 1];
        float dy = routeY[routeY.length - 1];
        drawDestination(canvas, dx, dy);

        // Hareketli araç
        float t = ((SystemClock.uptimeMillis() - startTime) % LOOP_MS) / (float) LOOP_MS;
        float[] pos = pointAlongRoute(t);
        drawCar(canvas, pos[0], pos[1], pos[2]);

        // Üst bilgi paneli
        drawTopPanel(canvas, t);

        // Saat + test sayacı HUD'u (her saniye değişir/artar)
        drawHud(canvas);

        // Alt durum çubuğu
        drawBottomBar(canvas);

        canvas.restore();

        // Sürekli yeniden çizim -> sürekli kare üretimi
        postInvalidateOnAnimation();
    }

    private void drawBlocks(Canvas canvas) {
        mapPaint.setColor(Color.parseColor("#DDE6D6"));
        float[][] blocks = {
                {210, 210, 740, 410},
                {810, 210, 1010, 410},
                {210, 500, 360, 620},
                {810, 490, 1010, 620},
        };
        for (float[] b : blocks) {
            rectF.set(b[0], b[1], b[2], b[3]);
            canvas.drawRoundRect(rectF, 14, 14, mapPaint);
        }
        mapPaint.setColor(Color.parseColor("#CFE3C4")); // yeşil alan (park)
        rectF.set(470, 500, 720, 630);
        canvas.drawRoundRect(rectF, 18, 18, mapPaint);
    }

    private void drawDestination(Canvas canvas, float x, float y) {
        Path pin = new Path();
        float r = 22;
        pin.addCircle(x, y - r, r, Path.Direction.CW);
        pin.moveTo(x - r * 0.75f, y - r * 0.6f);
        pin.lineTo(x, y + r * 0.9f);
        pin.lineTo(x + r * 0.75f, y - r * 0.6f);
        pin.close();
        canvas.drawPath(pin, destPaint);
        canvas.drawCircle(x, y - r, r * 0.45f, whitePaint);
    }

    private void drawCar(Canvas canvas, float x, float y, float angleDeg) {
        canvas.save();
        canvas.rotate(angleDeg, x, y);
        // Yön halkası
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setColor(Color.parseColor("#332979FF"));
        canvas.drawCircle(x, y, 26, halo);
        canvas.drawCircle(x, y, 18, whitePaint);
        carPaint.setColor(Color.parseColor("#1565C0"));
        canvas.drawCircle(x, y, 14, carPaint);
        // Yön oku
        Path arrow = new Path();
        arrow.moveTo(x, y - 8);
        arrow.lineTo(x - 5, y + 3);
        arrow.lineTo(x + 5, y + 3);
        arrow.close();
        canvas.drawPath(arrow, whitePaint);
        canvas.restore();
    }

    private void drawTopPanel(Canvas canvas, float t) {
        rectF.set(20, 20, BASE_W - 20, 112);
        canvas.drawRoundRect(rectF, 18, 18, panelPaint);

        // Yön oku (manevra)
        Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(Color.parseColor("#2979FF"));
        rectF.set(40, 36, 112, 96);
        canvas.drawRoundRect(rectF, 12, 12, arrowPaint);
        Path man = new Path();
        man.moveTo(76, 48);
        man.lineTo(96, 70);
        man.lineTo(84, 70);
        man.lineTo(84, 86);
        man.lineTo(68, 86);
        man.lineTo(68, 70);
        man.lineTo(56, 70);
        man.close();
        canvas.drawPath(man, whitePaint);

        textPaint.setTextSize(34);
        canvas.drawText("200 m sonra sağa dönün", 132, 60, textPaint);
        subTextPaint.setTextSize(24);
        canvas.drawText("Independent Virtual Display · Atatürk Blv.", 132, 94, subTextPaint);

        // Sağ üst: kalan mesafe / süre
        int remainingM = Math.round((1 - t) * 1800) + 200;
        int etaMin = Math.max(1, Math.round((1 - t) * 7));
        textPaint.setTextSize(34);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(etaMin + " dk", BASE_W - 44, 60, textPaint);
        subTextPaint.setTextSize(24);
        subTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(remainingM + " m kaldı", BASE_W - 44, 94, subTextPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
        subTextPaint.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * Sanal ekranın canlı/bağımsız olduğunu kanıtlayan HUD: her saniye değişen
     * bir saat ve her saniye artan bir test sayacı. Değerler yalnızca saniye
     * değiştiğinde yeniden biçimlenir (gereksiz ayırma yok).
     */
    private void drawHud(Canvas canvas) {
        long nowMs = System.currentTimeMillis();
        long curSecond = nowMs / 1000L;
        if (curSecond != lastClockSecond) {
            lastClockSecond = curSecond;
            clockText = clockFormat.format(new Date(nowMs));
        }
        testCounter = (int) ((SystemClock.uptimeMillis() - startTime) / 1000L);

        final float w = 440;
        final float h = 104;
        final float left = (BASE_W - w) / 2f;
        final float top = 124;
        final float mid = left + w / 2f;

        rectF.set(left, top, left + w, top + h);
        canvas.drawRoundRect(rectF, 18, 18, hudPanel);
        canvas.drawLine(mid, top + 20, mid, top + h - 20, hudDivider);

        // Sol: SAAT
        hudLabel.setTextSize(22);
        hudLabel.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("SAAT", (left + mid) / 2f, top + 38, hudLabel);
        hudValue.setTextSize(46);
        hudValue.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(clockText, (left + mid) / 2f, top + 84, hudValue);

        // Sağ: TEST SAYACI
        hudLabel.setTextSize(22);
        canvas.drawText("TEST SAYACI", (mid + left + w) / 2f, top + 38, hudLabel);
        hudValue.setTextSize(46);
        canvas.drawText(String.valueOf(testCounter), (mid + left + w) / 2f, top + 84, hudValue);

        hudLabel.setTextAlign(Paint.Align.LEFT);
        hudValue.setTextAlign(Paint.Align.LEFT);
    }

    private void drawBottomBar(Canvas canvas) {
        rectF.set(20, BASE_H - 86, BASE_W - 20, BASE_H - 18);
        canvas.drawRoundRect(rectF, 16, 16, panelPaint);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.parseColor("#4CAF50"));
        canvas.drawCircle(52, BASE_H - 52, 10, dot);

        statusPaint.setTextSize(28);
        canvas.drawText("Navigasyon aktif  ·  Bağımsız sanal ekran çalışıyor", 78, BASE_H - 42, statusPaint);

        subTextPaint.setTextSize(24);
        subTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("1280 × 720", BASE_W - 44, BASE_H - 42, subTextPaint);
        subTextPaint.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * Rota üzerinde t (0..1) oranına karşılık gelen konumu ve yön açısını
     * döndürür: {x, y, angleDeg}.
     */
    private float[] pointAlongRoute(float t) {
        int n = routeX.length;
        float total = 0;
        float[] segLen = new float[n - 1];
        for (int i = 0; i < n - 1; i++) {
            float dx = routeX[i + 1] - routeX[i];
            float dy = routeY[i + 1] - routeY[i];
            segLen[i] = (float) Math.hypot(dx, dy);
            total += segLen[i];
        }
        float target = t * total;
        float acc = 0;
        for (int i = 0; i < n - 1; i++) {
            if (acc + segLen[i] >= target || i == n - 2) {
                float local = segLen[i] == 0 ? 0 : (target - acc) / segLen[i];
                local = Math.max(0, Math.min(1, local));
                float x = routeX[i] + (routeX[i + 1] - routeX[i]) * local;
                float y = routeY[i] + (routeY[i + 1] - routeY[i]) * local;
                float angle = (float) Math.toDegrees(
                        Math.atan2(routeY[i + 1] - routeY[i], routeX[i + 1] - routeX[i])) + 90f;
                return new float[]{x, y, angle};
            }
            acc += segLen[i];
        }
        return new float[]{routeX[0], routeY[0], 0};
    }
}
