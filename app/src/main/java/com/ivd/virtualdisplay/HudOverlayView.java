package com.ivd.virtualdisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Haritanın üzerine çizilen şeffaf HUD katmanı.
 *
 * İki görevi vardır:
 *  1) Her saniye değişen saat ve her saniye artan test sayacını gösterir
 *     (sanal ekranın canlı ve bağımsız olduğunu kanıtlar).
 *  2) Her karede kendini yeniden geçersiz kılarak Presentation penceresinin
 *     sürekli yeniden çizilmesini — dolayısıyla VirtualDisplay'in sürekli kare
 *     üretmesini — sağlar. Böylece ImageReader kare almaya ve FPS/sayaç artmaya
 *     devam eder (harita hareketsiz olsa bile).
 *
 * Harita çizimine karışmaz; yalnızca üstüne küçük paneller koyar.
 */
public class HudOverlayView extends View {

    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liveDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liveText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final long startTime = SystemClock.uptimeMillis();
    private long lastSecond = -1;
    private String clockText = "--:--:--";

    public HudOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);

        panel.setColor(Color.parseColor("#C8101820"));
        value.setColor(Color.WHITE);
        value.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        label.setColor(Color.parseColor("#9DB2BF"));
        label.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        liveDot.setColor(Color.parseColor("#E53935"));
        liveText.setColor(Color.WHITE);
        liveText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            postInvalidateOnAnimation();
            return;
        }
        // Ekran boyutuna göre ölçek (1280x720 referans).
        final float s = Math.min(w / 1280f, h / 720f);

        long nowMs = System.currentTimeMillis();
        long sec = nowMs / 1000L;
        if (sec != lastSecond) {
            lastSecond = sec;
            clockText = clockFormat.format(new Date(nowMs));
        }
        int counter = (int) ((SystemClock.uptimeMillis() - startTime) / 1000L);

        // Üst-orta panel: SAAT | TEST SAYACI
        float pw = 440 * s, ph = 96 * s;
        float left = (w - pw) / 2f, top = 16 * s, mid = left + pw / 2f;
        rect.set(left, top, left + pw, top + ph);
        canvas.drawRoundRect(rect, 16 * s, 16 * s, panel);

        label.setTextAlign(Paint.Align.CENTER);
        value.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(22 * s);
        value.setTextSize(44 * s);
        canvas.drawText("SAAT", (left + mid) / 2f, top + 34 * s, label);
        canvas.drawText(clockText, (left + mid) / 2f, top + 80 * s, value);
        canvas.drawText("TEST SAYACI", (mid + left + pw) / 2f, top + 34 * s, label);
        canvas.drawText(String.valueOf(counter), (mid + left + pw) / 2f, top + 80 * s, value);

        // Sol-üst: CANLI HARİTA göstergesi (yanıp sönen nokta)
        float by = 40 * s, bx = 24 * s;
        rect.set(bx, by - 20 * s, bx + 220 * s, by + 18 * s);
        canvas.drawRoundRect(rect, 12 * s, 12 * s, panel);
        boolean on = (System.currentTimeMillis() / 500L) % 2 == 0;
        liveDot.setAlpha(on ? 255 : 90);
        canvas.drawCircle(bx + 22 * s, by, 9 * s, liveDot);
        liveText.setTextAlign(Paint.Align.LEFT);
        liveText.setTextSize(22 * s);
        canvas.drawText("CANLI HARİTA", bx + 42 * s, by + 8 * s, liveText);

        // Sürekli yeniden çizim -> sürekli kare üretimi.
        postInvalidateOnAnimation();
    }
}
