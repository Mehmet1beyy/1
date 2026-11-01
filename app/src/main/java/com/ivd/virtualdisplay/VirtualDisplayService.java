package com.ivd.virtualdisplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.ivd.virtualdisplay.stream.ControlServer;
import com.ivd.virtualdisplay.stream.StreamEncoder;
import com.ivd.virtualdisplay.stream.TcpStreamServer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sanal ekranı (VirtualDisplay) oluşturan, {@link MapPresentation} ile
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

    // WiFi yayını
    public static final int STREAM_PORT = 8080;
    public static final int STREAM_CTRL_PORT = 8081;
    private static final int STREAM_FPS = 20;
    private static final int STREAM_BITRATE = 4_000_000;

    private static final String CHANNEL_ID = "ivd_channel";
    private static final int NOTIF_ID = 1001;

    private final IBinder binder = new LocalBinder();

    private DisplayManager displayManager;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private MapPresentation presentation;

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

    // --- WiFi yayını ---
    private StreamEncoder streamEncoder;
    private TcpStreamServer streamServer;
    private ControlServer controlServer;
    private volatile boolean streaming = false;
    private String streamPin = "";
    private long lastStreamMs = 0;

    // --- GPS (olay tabanlı) ---
    private LocationManager locationManager;
    private boolean locationActive = false;
    private volatile boolean gpsProviderEnabled = false;

    // Son geçerli konum (sinyal kaybında korunur; eski veriyi yeni gibi göstermeyiz).
    private volatile boolean hasFix = false;
    private double lastLat;
    private double lastLon;
    private float lastAccuracy;
    private long lastFixTimeMs;   // GPS ölçüm zamanı (Location.getTime)
    private long lastArrivalMs;   // Uygulamaya ulaşma zamanı
    private String lastProvider = "";

    @Nullable
    private volatile Listener listener;

    /** Activity'ye önizleme, istatistik ve konum iletmek için geri çağrı. */
    public interface Listener {
        void onPreview(Bitmap preview);

        void onStats(boolean running, long frames, double fps);

        /** Yeni gerçek GPS düzeltmesi geldiğinde (ana iş parçacığında). */
        void onLocation(double lat, double lon, float accuracy,
                        long fixTimeMs, long arrivalMs, String provider);

        /** GPS sağlayıcı/izin durumu değiştiğinde. */
        void onGpsAvailability(boolean providerEnabled, boolean permissionGranted);
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
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean locationPermitted = hasLocationPermission();
        startForegroundInternal(locationPermitted);

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopCapture();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Varsayılan: sanal ekranı başlat.
        startCapture();
        // İzin verildiyse GPS'i hemen başlat (olay tabanlı).
        if (locationPermitted) {
            startLocation();
        } else {
            notifyGpsAvailability();
        }
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
        notifyGpsAvailability();
        // Geri dönen Activity, en son geçerli konumu hemen görsün.
        if (l != null && hasFix) {
            l.onLocation(lastLat, lastLon, lastAccuracy, lastFixTimeMs, lastArrivalMs, lastProvider);
        }
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

    /** Sanal ekrandaki haritayı yakınlaştır (ana iş parçacığında uygulanır). */
    public void zoomIn() {
        mainHandler.post(() -> {
            MapPresentation p = presentation;
            if (p != null) {
                p.zoomIn();
            }
        });
    }

    /** Sanal ekrandaki haritayı uzaklaştır (ana iş parçacığında uygulanır). */
    public void zoomOut() {
        mainHandler.post(() -> {
            MapPresentation p = presentation;
            if (p != null) {
                p.zoomOut();
            }
        });
    }

    /**
     * Ana uygulamanın önizlemesinden gelen (zaten sanal ekran koordinatlarına
     * ölçeklenmiş) dokunma olayını haritaya iletir. Çağıran ana iş parçacığında
     * olduğundan doğrudan uygulanır; ağ/tile işleri osmdroid'de arka plandadır,
     * bu yüzden UI kilitlenmez.
     */
    public void forwardTouch(android.view.MotionEvent ev) {
        MapPresentation p = presentation;
        if (p != null) {
            p.dispatchMapTouch(ev);
        }
    }

    /** "Konumuma dön": haritayı son gerçek konuma ortala ve takibi aç. */
    public void recenter() {
        mainHandler.post(() -> {
            MapPresentation p = presentation;
            if (p != null) {
                p.recenter();
            }
        });
    }

    /**
     * Konum izni sonradan verildiğinde Activity tarafından çağrılır. Foreground
     * servisi {@code location} tipini içerecek şekilde yükseltir ve GPS'i başlatır.
     */
    public void enableLocation() {
        if (!running) {
            return;
        }
        if (!hasLocationPermission()) {
            notifyGpsAvailability();
            return;
        }
        // Foreground servis tipini location içerecek şekilde güncelle.
        startForegroundInternal(true);
        startLocation();
    }

    // ----- WiFi yayını (laptopa canlı) -----

    /** Sanal ekran çalışıyorsa yayını başlatır. */
    public synchronized void startStreaming() {
        if (streaming || !running) {
            return;
        }
        final String pin = String.valueOf(1000 + new Random().nextInt(9000));
        TcpStreamServer server = new TcpStreamServer(STREAM_PORT, pin, new TcpStreamServer.Callback() {
            @Override
            public void onClientCount(int n) {
                // UI, 500 ms'lik tiker ile getStreamSummary() okuyor.
            }
            @Override
            public void requestKeyframe() {
                StreamEncoder e = streamEncoder;
                if (e != null) {
                    e.requestKeyframe();
                }
            }
        });
        StreamEncoder enc = new StreamEncoder(WIDTH, HEIGHT, STREAM_FPS, STREAM_BITRATE, server);
        try {
            server.start();
            enc.start();
        } catch (Exception e) {
            Log.e(TAG, "Yayın başlatılamadı", e);
            try { enc.stop(); } catch (Exception ignored) {}
            try { server.stop(); } catch (Exception ignored) {}
            return;
        }
        streamServer = server;
        streamEncoder = enc;
        streamPin = pin;
        streaming = true;

        // Laptoptan kontrol (sürükle/zoom) kanalı.
        ControlServer cs = new ControlServer(STREAM_CTRL_PORT, pin, new ControlServer.Handler() {
            @Override
            public void onTouch(int action, int x, int y) {
                mainHandler.post(() -> {
                    MapPresentation p = presentation;
                    if (p != null) p.injectTouch(action, x, y);
                });
            }
            @Override
            public void onWheel(int dir) {
                mainHandler.post(() -> {
                    MapPresentation p = presentation;
                    if (p != null) p.wheel(dir);
                });
            }
            @Override
            public void onUrl(String url) {
                mainHandler.post(() -> {
                    MapPresentation p = presentation;
                    if (p != null) p.loadWebUrl(url);
                });
            }
        });
        cs.start();
        controlServer = cs;

        Log.i(TAG, "Yayın başladı, pin=" + pin);
    }

    public synchronized void stopStreaming() {
        streaming = false;
        StreamEncoder e = streamEncoder;
        TcpStreamServer s = streamServer;
        ControlServer c = controlServer;
        streamEncoder = null;
        streamServer = null;
        controlServer = null;
        if (e != null) e.stop();
        if (s != null) s.stop();
        if (c != null) c.stop();
        Log.i(TAG, "Yayın durduruldu");
    }

    /** Arama sonucu konuma git + işaretçi (ana iş parçacığına aktarılır). */
    public void goToLocation(double lat, double lon, String label) {
        mainHandler.post(() -> {
            MapPresentation p = presentation;
            if (p != null) {
                p.goTo(lat, lon, label);
            }
        });
    }

    /** Telefon adres çubuğundan web adresi yükle (web uygulamasına geçer). */
    public void loadWeb(String url) {
        mainHandler.post(() -> {
            MapPresentation p = presentation;
            if (p != null) {
                p.loadWebUrl(url);
            }
        });
    }

    public boolean isStreaming() {
        return streaming;
    }

    /** UI'de gösterilecek çok satırlı yayın özeti (IP + PIN + sayaçlar). */
    public String getStreamSummary() {
        if (!streaming) {
            return "";
        }
        TcpStreamServer s = streamServer;
        List<String> ips = localIps();
        String primary = primaryIp(ips);
        StringBuilder sb = new StringBuilder();
        sb.append("Yayında  •  PIN: ").append(streamPin)
                .append("  •  Port: ").append(STREAM_PORT).append('\n');
        sb.append("Telefon IP: ").append(primary == null ? "?" : primary).append('\n');
        if (ips.size() > 1) {
            sb.append("Tüm IP'ler: ").append(String.join("  ", ips)).append('\n');
        }
        if (s != null) {
            sb.append("Kodlanan: ").append(s.getFrames()).append(" kare  •  Gönderilen: ")
                    .append(s.getBytes() / 1024).append(" KB  •  İstemci: ").append(s.getClientCount());
        }
        return sb.toString();
    }

    private List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String h = a.getHostAddress();
                        if (h != null && !out.contains(h)) out.add(h);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private String primaryIp(List<String> a) {
        for (String s : a) if (s.startsWith("192.168.")) return s;
        for (String s : a) if (s.startsWith("10.") || s.startsWith("172.")) return s;
        return a.isEmpty() ? null : a.get(0);
    }

    // ----- GPS (olay tabanlı) -----

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            long arrival = System.currentTimeMillis();
            lastLat = location.getLatitude();
            lastLon = location.getLongitude();
            lastAccuracy = location.hasAccuracy() ? location.getAccuracy() : 0f;
            lastFixTimeMs = location.getTime();
            lastArrivalMs = arrival;
            lastProvider = location.getProvider() != null ? location.getProvider() : "";
            hasFix = true;
            gpsProviderEnabled = true;

            // Callback ana Looper'da gelir: haritayı doğrudan güncelle (hafif).
            MapPresentation p = presentation;
            if (p != null) {
                p.updateLocation(lastLat, lastLon, lastAccuracy);
            }
            Listener l = listener;
            if (l != null) {
                l.onLocation(lastLat, lastLon, lastAccuracy, lastFixTimeMs, arrival, lastProvider);
            }
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            gpsProviderEnabled = true;
            notifyGpsAvailability();
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            // Sağlayıcı kapandı; son konumu koru, işaretçiyi oynatma.
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                gpsProviderEnabled = false;
            }
            notifyGpsAvailability();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Eski API'ler için; modern cihazlarda genelde çağrılmaz.
        }
    };

    @SuppressWarnings("MissingPermission")
    private void startLocation() {
        if (locationActive || locationManager == null || !hasLocationPermission()) {
            return;
        }
        try {
            boolean anyProvider = false;
            // En yüksek sıklık: minTime=0, minDistance=0 (polling/timer yok).
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 0L, 0f,
                        locationListener, Looper.getMainLooper());
                gpsProviderEnabled = true;
                anyProvider = true;
            }
            // Ağ sağlayıcısı: daha hızlı ilk düzeltme için (varsa).
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 0L, 0f,
                        locationListener, Looper.getMainLooper());
                anyProvider = true;
            }
            locationActive = true;
            gpsProviderEnabled = gpsProviderEnabled || anyProvider;
            Log.i(TAG, "GPS dinleme başladı (olay tabanlı).");
        } catch (SecurityException e) {
            Log.e(TAG, "GPS izni yok", e);
        } catch (Exception e) {
            Log.e(TAG, "startLocation hatası", e);
        }
        notifyGpsAvailability();
    }

    private void stopLocation() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (Exception e) {
                Log.e(TAG, "stopLocation hatası", e);
            }
        }
        locationActive = false;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void notifyGpsAvailability() {
        Listener l = listener;
        if (l == null) {
            return;
        }
        final boolean enabled = gpsProviderEnabled;
        final boolean granted = hasLocationPermission();
        mainHandler.post(() -> {
            Listener cur = listener;
            if (cur != null) {
                cur.onGpsAvailability(enabled, granted);
            }
        });
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
            presentation = new MapPresentation(this, display);
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

            // Yayın açıksa kareyi kodlayıcıya ver (~STREAM_FPS ile sınırlı).
            StreamEncoder enc = streamEncoder;
            if (streaming && enc != null && (ms - lastStreamMs) >= (1000L / STREAM_FPS)) {
                lastStreamMs = ms;
                Bitmap sb = toBitmap(image);
                if (sb != null) {
                    enc.submitBitmap(sb);
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

        // Yayını da durdur (açıksa).
        stopStreaming();

        // GPS dinleyicilerini düzgün kapat.
        stopLocation();

        final MapPresentation p = presentation;
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

    private void startForegroundInternal(boolean withLocation) {
        boolean locationType = withLocation && hasLocationPermission();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Independent Virtual Display")
                .setContentText(locationType
                        ? "Sanal ekran + GPS arka planda çalışıyor"
                        : "Sanal ekran arka planda çalışıyor")
                .setSmallIcon(R.drawable.ic_stat_display)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (locationType) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            startForeground(NOTIF_ID, notification, type);
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
