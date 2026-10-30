package com.ivd.virtualdisplay;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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

    private Button startButton;
    private Button stopButton;
    private ImageView preview;
    private TextView statusText;
    private TextView resolutionText;
    private TextView framesText;
    private TextView fpsText;

    private VirtualDisplayService service;
    private boolean bound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            VirtualDisplayService.LocalBinder localBinder =
                    (VirtualDisplayService.LocalBinder) binder;
            service = localBinder.getService();
            bound = true;
            service.setListener(MainActivity.this);
            updateControls(service.isRunning());
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
        preview = findViewById(R.id.preview);
        statusText = findViewById(R.id.statusText);
        resolutionText = findViewById(R.id.resolutionText);
        framesText = findViewById(R.id.framesText);
        fpsText = findViewById(R.id.fpsText);

        resolutionText.setText(getString(R.string.resolution_value,
                VirtualDisplayService.WIDTH, VirtualDisplayService.HEIGHT));

        startButton.setOnClickListener(v -> onStartClicked());
        stopButton.setOnClickListener(v -> onStopClicked());

        updateControls(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Servise bağlan (oluşturur ama başlatmaz). Başlatma yalnızca
        // kullanıcı düğmeye bastığında startForegroundService ile yapılır.
        bindService(new Intent(this, VirtualDisplayService.class),
                connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
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
        statusText.setText(R.string.status_stopped);
        framesText.setText(getString(R.string.frames_value, 0L));
        fpsText.setText(getString(R.string.fps_value, 0.0));
        updateControls(false);
    }

    private void updateControls(boolean running) {
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
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

    // ----- VirtualDisplayService.Listener (ana iş parçacığında çağrılır) -----

    @Override
    public void onPreview(Bitmap bmp) {
        preview.setImageBitmap(bmp);
    }

    @Override
    public void onStats(boolean running, long frames, double fps) {
        statusText.setText(running ? R.string.status_running : R.string.status_stopped);
        framesText.setText(getString(R.string.frames_value, frames));
        fpsText.setText(getString(R.string.fps_value, fps));
        updateControls(running);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Bildirim izni reddedilse bile servis çalışır; yalnızca bildirim
        // görünmeyebilir. Ek işlem gerekmez.
    }
}
