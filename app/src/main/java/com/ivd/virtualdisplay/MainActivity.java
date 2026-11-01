package com.ivd.virtualdisplay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Uygulamanın ana ekranı.
 *
 * - Sanal ekranı başlat / durdur düğmeleri
 * - Sanal ekranın canlı önizlemesi (ImageView)
 * - Durum, çözünürlük, toplam kare ve anlık FPS göstergeleri
 *
 * Sanal ekranın asıl işi {@link VirtualDisplayService} içinde yapılır; bu
 * Activity yalnızca kontrol ve görselleştirme sağlar. Activity arka plana
 * alındığında servis çalışmaya devam eder.
 */
public class MainActivity extends AppCompatActivity implements VirtualDisplayService.Listener {

    private static final int REQ_POST_NOTIF = 42;
    private static final int REQ_LOCATION = 43;
    private static final long STALE_MS = 5000L; // bu süreden eski fix "sinyal zayıf"

    private Button startButton;
    private Button stopButton;
    private Button zoomInButton;
    private Button zoomOutButton;
    private Button recenterButton;
    private Button streamButton;
    private TextView streamInfo;
    private ImageView preview;
    private TextView statusText;
    private TextView resolutionText;
    private TextView framesText;
    private TextView fpsText;

    // GPS göstergeleri
    private TextView gpsStatusText;
    private TextView latText;
    private TextView lonText;
    private TextView accText;
    private TextView fixTimeText;
    private TextView arrivalText;
    private TextView latencyText;
    private TextView elapsedText;
    private TextView permWarning;

    private VirtualDisplayService service;
    private boolean bound = false;

    // Gösterilen son önizleme karesi (yenisi gelince geri dönüştürülür).
    private Bitmap currentPreview;

    // GPS durum takibi (yalnızca gösterim; marker hareketi servis callback'inden).
    private boolean hasFix = false;
    private long lastArrivalMs = 0L;
    private boolean gpsProviderEnabled = false;
    private boolean permGranted = false;

    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    // Hafif UI tiker: yalnızca "geçen süre" etiketini ve bayatlık durumunu
    // tazeler (GPS polling DEĞİL — konum tamamen olay tabanlıdır).
    private final Handler uiTicker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshElapsedAndStatus();
            refreshStreamInfo();
            uiTicker.postDelayed(this, 500L);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            VirtualDisplayService.LocalBinder localBinder =
                    (VirtualDisplayService.LocalBinder) binder;
            service = localBinder.getService();
            bound = true;
            service.setListener(MainActivity.this);
            updateControls(service.isRunning());
            // İzin sonradan verildiyse ve yakalama sürüyorsa GPS'i başlat.
            if (service.isRunning() && hasLocationPermission()) {
                service.enableLocation();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        zoomInButton = findViewById(R.id.zoomInButton);
        zoomOutButton = findViewById(R.id.zoomOutButton);
        recenterButton = findViewById(R.id.recenterButton);
        streamButton = findViewById(R.id.streamButton);
        streamInfo = findViewById(R.id.streamInfo);
        preview = findViewById(R.id.preview);
        statusText = findViewById(R.id.statusText);
        resolutionText = findViewById(R.id.resolutionText);
        framesText = findViewById(R.id.framesText);
        fpsText = findViewById(R.id.fpsText);

        gpsStatusText = findViewById(R.id.gpsStatusText);
        latText = findViewById(R.id.latText);
        lonText = findViewById(R.id.lonText);
        accText = findViewById(R.id.accText);
        fixTimeText = findViewById(R.id.fixTimeText);
        arrivalText = findViewById(R.id.arrivalText);
        latencyText = findViewById(R.id.latencyText);
        elapsedText = findViewById(R.id.elapsedText);
        permWarning = findViewById(R.id.permWarning);

        resolutionText.setText(getString(R.string.resolution_value,
                VirtualDisplayService.WIDTH, VirtualDisplayService.HEIGHT));

        startButton.setOnClickListener(v -> onStartClicked());
        stopButton.setOnClickListener(v -> onStopClicked());
        zoomInButton.setOnClickListener(v -> {
            if (bound && service != null) {
                service.zoomIn();
            }
        });
        zoomOutButton.setOnClickListener(v -> {
            if (bound && service != null) {
                service.zoomOut();
            }
        });
        recenterButton.setOnClickListener(v -> {
            if (bound && service != null) {
                service.recenter();
            }
        });
        streamButton.setOnClickListener(v -> onStreamClicked());

        // Önizlemeye dokunuş -> sanal ekrandaki haritaya aktar.
        preview.setOnTouchListener(this::onPreviewTouch);

        // İlk açılışta gerekli izinleri iste.
        requestInitialPermissions();

        updateControls(false);
        updatePermWarning();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Servise bağlan (oluşturur ama başlatmaz). Başlatma yalnızca
        // kullanıcı düğmeye bastığında startForegroundService ile yapılır.
        bindService(new Intent(this, VirtualDisplayService.class),
                connection, Context.BIND_AUTO_CREATE);
        // Kullanıcı izni ayarlardan sonradan vermiş olabilir: algıla ve uyarıyı güncelle.
        updatePermWarning();
        // "Geçen süre"/bayatlık göstergesi için hafif UI tazeleyici.
        uiTicker.removeCallbacks(tick);
        uiTicker.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiTicker.removeCallbacks(tick);
        // Sızıntıyı önlemek için dinleyiciyi bırak ve bağlantıyı çöz.
        // Servis başlatıldıysa (foreground) çalışmaya devam eder.
        if (bound) {
            if (service != null) {
                service.setListener(null);
            }
            unbindService(connection);
            bound = false;
        }
    }

    private void onStartClicked() {
        ensureNotificationPermission();
        ensureLocationPermission();
        Intent intent = new Intent(this, VirtualDisplayService.class)
                .setAction(VirtualDisplayService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
        // Yeni başlatılan servise bağlanmayı garanti et.
        if (!bound) {
            bindService(new Intent(this, VirtualDisplayService.class),
                    connection, Context.BIND_AUTO_CREATE);
        }
        statusText.setText(R.string.status_starting);
        updateControls(true);
    }

    private void onStopClicked() {
        Intent intent = new Intent(this, VirtualDisplayService.class)
                .setAction(VirtualDisplayService.ACTION_STOP);
        startService(intent);
        preview.setImageDrawable(null);
        if (currentPreview != null && !currentPreview.isRecycled()) {
            currentPreview.recycle();
        }
        currentPreview = null;
        statusText.setText(R.string.status_stopped);
        framesText.setText(getString(R.string.frames_value, 0L));
        fpsText.setText(getString(R.string.fps_value, 0.0));
        updateControls(false);
    }

    private void updateControls(boolean running) {
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        zoomInButton.setEnabled(running);
        zoomOutButton.setEnabled(running);
        recenterButton.setEnabled(running);
        streamButton.setEnabled(running);
        refreshStreamInfo();
    }

    private void onStreamClicked() {
        if (!bound || service == null) {
            return;
        }
        if (service.isStreaming()) {
            service.stopStreaming();
        } else {
            if (!service.isRunning()) {
                streamInfo.setText("Önce 'Başlat' ile sanal ekranı çalıştır.");
                return;
            }
            service.startStreaming();
        }
        refreshStreamInfo();
    }

    private void refreshStreamInfo() {
        boolean streaming = bound && service != null && service.isStreaming();
        streamButton.setText(streaming ? "Yayını Durdur" : "Yayını Başlat");
        if (streaming) {
            streamInfo.setText(service.getStreamSummary());
        } else if (bound && service != null && service.isRunning()) {
            streamInfo.setText("Kapalı — başlatmak için 'Yayını Başlat'a dokun.");
        } else {
            streamInfo.setText("Önce sanal ekranı başlat.");
        }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIF);
            }
        }
    }

    private void requestInitialPermissions() {
        // İlk açılışta bildirim + konum izinlerini iste.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIF);
        }
        ensureLocationPermission();
    }

    private void ensureLocationPermission() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updatePermWarning() {
        permGranted = hasLocationPermission();
        permWarning.setVisibility(permGranted ? View.GONE : View.VISIBLE);
    }

    /**
     * Önizleme ImageView'ine gelen dokunuşu, sanal ekrandaki haritanın piksel
     * uzayına (1280x720) ölçekleyip MotionEvent olarak servise iletir. Böylece
     * sürükleme, çift dokunma, pinch ve basılı tutma gibi tüm hareketler
     * sanal ekrandaki MapView tarafından doğrudan işlenir.
     *
     * fitCenter ölçeklemesi nedeniyle görüntü harf kutusu (letterbox) ile
     * ortalandığından, dokunma koordinatı önce gösterilen görüntü alanına göre
     * düzeltilir. İşlem hafiftir ve ana iş parçacığını kilitlemez.
     */
    @SuppressLint("ClickableViewAccessibility")
    private boolean onPreviewTouch(View v, MotionEvent ev) {
        if (!bound || service == null) {
            return false;
        }
        final int viewW = v.getWidth();
        final int viewH = v.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            return false;
        }

        // Dokunma başladığında ScrollView'in (ebeveyn) olayı ele geçirip sayfayı
        // kaydırmasını engelle; sürükleme/pinch haritaya gitsin.
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && v.getParent() != null) {
            v.getParent().requestDisallowInterceptTouchEvent(true);
        }

        final int mapW = VirtualDisplayService.WIDTH;
        final int mapH = VirtualDisplayService.HEIGHT;

        final float scale = Math.min(viewW / (float) mapW, viewH / (float) mapH);
        if (scale <= 0f) {
            return false;
        }
        final float offX = (viewW - mapW * scale) / 2f;
        final float offY = (viewH - mapH * scale) / 2f;

        final int pc = ev.getPointerCount();
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[pc];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pc];
        for (int i = 0; i < pc; i++) {
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
            ev.getPointerProperties(i, p);
            props[i] = p;

            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
            ev.getPointerCoords(i, c);
            c.x = clamp((c.x - offX) / scale, 0f, mapW);
            c.y = clamp((c.y - offY) / scale, 0f, mapH);
            coords[i] = c;
        }

        MotionEvent translated = MotionEvent.obtain(
                ev.getDownTime(), ev.getEventTime(), ev.getAction(), pc,
                props, coords, ev.getMetaState(), ev.getButtonState(),
                ev.getXPrecision(), ev.getYPrecision(), ev.getDeviceId(),
                ev.getEdgeFlags(), ev.getSource(), ev.getFlags());
        try {
            service.forwardTouch(translated);
        } finally {
            translated.recycle();
        }
        return true;
    }

    private static float clamp(float value, float lo, float hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }

    // ----- VirtualDisplayService.Listener (ana iş parçacığında çağrılır) -----

    @Override
    public void onPreview(Bitmap bmp) {
        preview.setImageBitmap(bmp);
        // Yeni kare yerleştirildiğine göre önceki kareye artık referans yok;
        // belleği hemen serbest bırak.
        if (currentPreview != null && currentPreview != bmp && !currentPreview.isRecycled()) {
            currentPreview.recycle();
        }
        currentPreview = bmp;
    }

    @Override
    public void onStats(boolean running, long frames, double fps) {
        statusText.setText(running ? R.string.status_running : R.string.status_stopped);
        framesText.setText(getString(R.string.frames_value, frames));
        fpsText.setText(getString(R.string.fps_value, fps));
        updateControls(running);
    }

    @Override
    public void onLocation(double lat, double lon, float accuracy,
                           long fixTimeMs, long arrivalMs, String provider) {
        hasFix = true;
        lastArrivalMs = arrivalMs;

        latText.setText(getString(R.string.lat_value, lat));
        lonText.setText(getString(R.string.lon_value, lon));
        accText.setText(getString(R.string.acc_value, accuracy));
        fixTimeText.setText(getString(R.string.fixtime_value, timeFmt.format(new Date(fixTimeMs))));
        arrivalText.setText(getString(R.string.arrival_value, timeFmt.format(new Date(arrivalMs))));
        long latency = arrivalMs - fixTimeMs;
        if (latency < 0) {
            latency = 0;
        }
        latencyText.setText(getString(R.string.latency_value, latency));
        refreshElapsedAndStatus();
    }

    @Override
    public void onGpsAvailability(boolean providerEnabled, boolean permissionGranted) {
        gpsProviderEnabled = providerEnabled;
        permGranted = permissionGranted;
        permWarning.setVisibility(permissionGranted ? View.GONE : View.VISIBLE);
        refreshElapsedAndStatus();
    }

    /**
     * Yalnızca "geçen süre" etiketini ve GPS bağlantı durumunu tazeler.
     * Konum verisi event tabanlı gelir; bu yalnızca gösterimi günceller.
     */
    private void refreshElapsedAndStatus() {
        if (!permGranted) {
            gpsStatusText.setText(R.string.gps_status_noperm);
            gpsStatusText.setTextColor(0xFFFF7043);
            elapsedText.setText(R.string.elapsed_initial);
            return;
        }
        if (!hasFix) {
            gpsStatusText.setText(gpsProviderEnabled
                    ? R.string.gps_status_waiting : R.string.gps_status_disabled);
            gpsStatusText.setTextColor(0xFFFFB74D);
            return;
        }
        float ageSec = (System.currentTimeMillis() - lastArrivalMs) / 1000f;
        elapsedText.setText(getString(R.string.elapsed_value, ageSec));

        if (!gpsProviderEnabled) {
            gpsStatusText.setText(R.string.gps_status_disabled);
            gpsStatusText.setTextColor(0xFFFF7043);
        } else if ((System.currentTimeMillis() - lastArrivalMs) > STALE_MS) {
            gpsStatusText.setText(R.string.gps_status_nosignal);
            gpsStatusText.setTextColor(0xFFFF7043);
        } else {
            gpsStatusText.setText(R.string.gps_status_connected);
            gpsStatusText.setTextColor(0xFF4CAF50);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            updatePermWarning();
            if (hasLocationPermission() && bound && service != null && service.isRunning()) {
                // İzin verildi ve yakalama sürüyor: GPS'i hemen başlat.
                service.enableLocation();
            }
        }
        // Bildirim izni reddedilse bile servis çalışır; ek işlem gerekmez.
    }
}
