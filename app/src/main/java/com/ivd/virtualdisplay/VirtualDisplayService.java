package com.ivd.virtualdisplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sanal ekranı (VirtualDisplay) oluşturan, {@link NavigationPresentation} ile
 * içerik çizen ve {@link ImageReader} aracılığıyla kareleri yakalayan
 * foreground service.
 *
 * Service olarak çalıştığı için Activity {@code onStop} olduğunda bile sanal
 * ekran yaşamaya ve kare üretmeye devam eder. Kaynaklar yalnızca kullanıcı
 * durdurduğunda serbest bırakılır.
 */
public class VirtualDisplayService extends Service {

    private static final String TAG = "IVDService";

    public static final String ACTION_START = "com.ivd.virtualdisplay.START";
    public static final String ACTION_STOP = "com.ivd.virtualdisplay.STOP";

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;
    private static final int DENSITY_DPI = 320;

    private static final String CHANNEL_ID = "ivd_channel";
    private static final int NOTIF_ID = 1001;

    private final IBinder binder = new LocalBinder();

    private DisplayManager displayManager;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private NavigationPresentation presentation;

    private HandlerThread readerThread;
    private Handler readerHandler;
    private Handler mainHandler;

    private volatile boolean running = false;

    private final AtomicLong frameCount = new AtomicLong(0);
    private volatile double fps = 0;
    private long lastFrameNs = 0;

    private Bitmap readerBitmap; // ImageReader düzlemi için yeniden kullanılan bitmap
    private long lastPreviewMs = 0;
    private long lastStatsMs = 0;

    @Nullable
    private volatile Listener listener;

    /** Activity'ye önizleme ve istatistik iletmek için geri çağrı. */
    public interface Listener {
        void onPreview(Bitmap preview);

        void onStats(boolean running, long frames, double fps);
    }

    public class LocalBinder extends Binder {
        VirtualDisplayService getService() {
            return VirtualDisplayService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundInternal();

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopCapture();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Varsayılan: sanal ekranı başlat.
        startCapture();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Activity ayrılsa bile servis (ve sanal ekran) yaşamaya devam eder.
        // Yalnızca sızıntıyı önlemek için dinleyiciyi bırakıyoruz.
        listener = null;
        return false;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }

    // ----- Genel API (binder üzerinden Activity tarafından kullanılır) -----

    public void setListener(@Nullable Listener l) {
        this.listener = l;
        // Yeni bağlanan Activity'ye mevcut durumu hemen bildir.
        notifyStats();
    }

    public boolean isRunning() {
        return running;
    }

    public long getFrameCount() {
        return frameCount.get();
    }

    public double getFps() {
        return fps;
    }

    // ----- Yakalama yaşam döngüsü -----

    private synchronized void startCapture() {
        if (running) {
            return;
        }
        try {
            readerThread = new HandlerThread("ivd-reader");
            readerThread.start();
            readerHandler = new Handler(readerThread.getLooper());

            imageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3);
            imageReader.setOnImageAvailableListener(onImageAvailable, readerHandler);

            int displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

            virtualDisplay = displayManager.createVirtualDisplay(
                    "IndependentVirtualDisplay",
                    WIDTH, HEIGHT, DENSITY_DPI,
                    imageReader.getSurface(),
                    displayFlags);

            if (virtualDisplay == null) {
                Log.e(TAG, "VirtualDisplay oluşturulamadı (null döndü).");
                cleanupAfterFailure();
                return;
            }

            frameCount.set(0);
            fps = 0;
            lastFrameNs = 0;
            running = true;

            final Display display = virtualDisplay.getDisplay();
            mainHandler.post(() -> showPresentation(display));

            Log.i(TAG, "Sanal ekran oluşturuldu: " + WIDTH + "x" + HEIGHT);
            notifyStats();
        } catch (Exception e) {
            Log.e(TAG, "startCapture hatası", e);
            cleanupAfterFailure();
        }
    }

    private void showPresentation(Display display) {
        try {
            if (!running || display == null) {
                return;
            }
            presentation = new NavigationPresentation(this, display);
            presentation.show();
        } catch (WindowManager.InvalidDisplayException e) {
            Log.e(TAG, "Presentation gösterilemedi: geçersiz ekran", e);
        } catch (Exception e) {
            Log.e(TAG, "Presentation gösterilemedi", e);
        }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailable = reader -> {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }

            long frames = frameCount.incrementAndGet();

            long now = System.nanoTime();
            if (lastFrameNs != 0) {
                double inst = 1_000_000_000.0 / (now - lastFrameNs);
                fps = (fps == 0) ? inst : (fps * 0.9 + inst * 0.1);
            }
            lastFrameNs = now;

            long ms = SystemClock.uptimeMillis();

            // Önizlemeyi ~15 fps ile sınırla (CPU/bellek tasarrufu).
            Listener l = listener;
            if (l != null && (ms - lastPreviewMs) >= 66) {
                lastPreviewMs = ms;
                final Bitmap preview = toBitmap(image);
                if (preview != null) {
                    mainHandler.post(() -> {
                        Listener cur = listener;
                        if (cur != null) {
                            cur.onPreview(preview);
                        }
                    });
                }
            }

            // İstatistikleri ~4 kez/sn güncelle.
            if ((ms - lastStatsMs) >= 250) {
                lastStatsMs = ms;
                notifyStats();
            }
        } catch (Exception e) {
            Log.e(TAG, "Kare işleme hatası", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    };

    /** ImageReader görüntüsünü kırpılmış bir ARGB_8888 bitmap'e kopyalar. */
    @Nullable
    private Bitmap toBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) {
            return null;
        }
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * WIDTH;
        int paddedWidth = WIDTH + (pixelStride > 0 ? rowPadding / pixelStride : 0);

        if (readerBitmap == null
                || readerBitmap.getWidth() != paddedWidth
                || readerBitmap.getHeight() != HEIGHT) {
            if (readerBitmap != null) {
                readerBitmap.recycle();
            }
            readerBitmap = Bitmap.createBitmap(paddedWidth, HEIGHT, Bitmap.Config.ARGB_8888);
        }

        buffer.rewind();
        readerBitmap.copyPixelsFromBuffer(buffer);

        // Dolgu sütunlarını atarak UI için bağımsız bir kopya üret.
        return Bitmap.createBitmap(readerBitmap, 0, 0, WIDTH, HEIGHT);
    }

    private void notifyStats() {
        Listener l = listener;
        if (l == null) {
            return;
        }
        final boolean r = running;
        final long fc = frameCount.get();
        final double f = fps;
        mainHandler.post(() -> {
            Listener cur = listener;
            if (cur != null) {
                cur.onStats(r, fc, f);
            }
        });
    }

    private synchronized void stopCapture() {
        running = false;

        final NavigationPresentation p = presentation;
        presentation = null;
        if (p != null) {
            mainHandler.post(() -> {
                try {
                    p.dismiss();
                } catch (Exception ignored) {
                }
            });
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        if (readerThread != null) {
            readerThread.quitSafely();
            readerThread = null;
            readerHandler = null;
        }
        if (readerBitmap != null) {
            readerBitmap.recycle();
            readerBitmap = null;
        }
        lastFrameNs = 0;
        notifyStats();
        Log.i(TAG, "Sanal ekran durduruldu ve kaynaklar serbest bırakıldı.");
    }

    private void cleanupAfterFailure() {
        running = false;
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (readerThread != null) {
            readerThread.quitSafely();
            readerThread = null;
            readerHandler = null;
        }
    }

    // ----- Foreground / bildirim -----

    private void startForegroundInternal() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Independent Virtual Display")
                .setContentText("Sanal ekran arka planda çalışıyor")
                .setSmallIcon(R.drawable.ic_stat_display)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Sanal Ekran Servisi",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Bağımsız sanal ekran çalışırken gösterilir.");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }
}
