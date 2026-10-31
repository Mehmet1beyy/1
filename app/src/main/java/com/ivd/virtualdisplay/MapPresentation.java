package com.ivd.virtualdisplay;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

/**
 * Sanal ekranda (VirtualDisplay) gösterilen Presentation. İçeriği, telefonun
 * fiziksel ekranından bağımsız olarak doğrudan burada oluşturulan **gerçek bir
 * OpenStreetMap haritasıdır** (osmdroid). Üstünde saat/sayaç HUD'u bulunur.
 *
 * VirtualDisplay/ImageReader mimarisi değişmez; yalnızca Presentation'ın içeriği
 * demo çizimden gerçek haritaya dönüştürülmüştür.
 */
public class MapPresentation extends Presentation {

    private static final String TAG = "IVDMap";

    // İlk GPS düzeltmesi gelene kadar yalnızca KAMERA için nötr başlangıç görünümü
    // (konum işaretçisi değildir; sabit/simüle konum olarak kullanılmaz).
    private static final double INITIAL_CAMERA_LAT = 39.0;
    private static final double INITIAL_CAMERA_LON = 35.0;
    private static final double INITIAL_CAMERA_ZOOM = 5.0;
    private static final double FIRST_FIX_ZOOM = 17.0;
    private static final double MIN_ZOOM = 3.0;
    private static final double MAX_ZOOM = 19.0;

    private MapView mapView;
    private HudOverlayView hud;

    // Gerçek GPS işaretçisi (ilk düzeltmede oluşturulur).
    private Marker locationMarker;
    private Double lastLat;
    private Double lastLon;
    private boolean firstFix = true;
    // Otomatik konum takibi; kullanıcı haritayı elle kaydırınca geçici kapanır.
    private boolean autoFollow = true;

    public MapPresentation(Context outerContext, Display display) {
        super(outerContext, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#DDDDDD")));
            window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }

        FrameLayout root = new FrameLayout(getContext());

        try {
            // osmdroid yapılandırması: OSM sunucuları User-Agent zorunlu kılar.
            Configuration.getInstance().setUserAgentValue(getContext().getPackageName());
            Configuration.getInstance().load(
                    getContext(),
                    PreferenceManager.getDefaultSharedPreferences(getContext()));

            mapView = new MapView(getContext());
            mapView.setTileSource(TileSourceFactory.MAPNIK);
            mapView.setUseDataConnection(true);
            mapView.setMultiTouchControls(true);
            mapView.setMinZoomLevel(MIN_ZOOM);
            mapView.setMaxZoomLevel(MAX_ZOOM);
            // Sanal ekranda dokunuş yok; harita içi zoom düğmelerini gizle
            // (yakınlaştırma ana uygulamadaki +/- düğmeleriyle yapılır).
            mapView.getZoomController()
                    .setVisibility(CustomZoomButtonsController.Visibility.NEVER);

            // Yalnızca kamera başlangıç görünümü; işaretçi GPS düzeltmesiyle eklenir.
            mapView.getController().setZoom(INITIAL_CAMERA_ZOOM);
            mapView.getController().setCenter(new GeoPoint(INITIAL_CAMERA_LAT, INITIAL_CAMERA_LON));

            root.addView(mapView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // osmdroid'i etkinleştir (tile iş parçacıklarını başlatır).
            mapView.onResume();
        } catch (Throwable t) {
            // İnternet yoksa veya tile sağlayıcı başlatılamazsa uygulama
            // çökmemeli; harita olmadan HUD yine de çalışır.
            Log.e(TAG, "Harita başlatılamadı (çevrimdışı olabilir)", t);
        }

        // HUD her zaman eklenir: harita başlatılamasa bile sanal ekran kare üretir.
        hud = new HudOverlayView(getContext());
        root.addView(hud, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    /** Ana uygulamadan çağrılır (ana iş parçacığında). */
    public void zoomIn() {
        try {
            if (mapView != null) {
                mapView.getController().zoomIn();
            }
        } catch (Throwable t) {
            Log.e(TAG, "zoomIn hatası", t);
        }
    }

    public void zoomOut() {
        try {
            if (mapView != null) {
                mapView.getController().zoomOut();
            }
        } catch (Throwable t) {
            Log.e(TAG, "zoomOut hatası", t);
        }
    }

    /**
     * Ana uygulamadaki önizlemeden iletilen dokunma olayını, sanal ekrandaki
     * haritaya (MapView) doğrudan uygular. Koordinatlar çağrı yapan tarafından
     * sanal ekran piksel uzayına (1280x720) ölçeklenmiş olarak gelir.
     * Ana iş parçacığında çağrılır.
     */
    public void dispatchMapTouch(android.view.MotionEvent ev) {
        try {
            if (mapView != null && ev != null) {
                // Kullanıcı haritayı elle kaydırırsa otomatik takibi geçici kapat.
                if (ev.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                    autoFollow = false;
                }
                mapView.dispatchTouchEvent(ev);
            }
        } catch (Throwable t) {
            Log.e(TAG, "dispatchMapTouch hatası", t);
        }
    }

    /**
     * Gerçek GPS konumunu haritaya uygular (ana iş parçacığında). İşaretçi yoksa
     * oluşturulur; otomatik takip açıksa harita konuma ortalanır. Konum
     * değişmediyse gereksiz yenileme yapılmaz.
     */
    public void updateLocation(double lat, double lon, float accuracy) {
        try {
            if (mapView == null) {
                return;
            }
            // Konum değişmediyse gereksiz harita yenilemesi yapma.
            if (lastLat != null && lastLon != null
                    && lastLat == lat && lastLon == lon) {
                return;
            }
            lastLat = lat;
            lastLon = lon;
            GeoPoint gp = new GeoPoint(lat, lon);

            if (locationMarker == null) {
                locationMarker = new Marker(mapView);
                locationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                locationMarker.setTitle("Konumum");
                mapView.getOverlays().add(locationMarker);
            }
            locationMarker.setPosition(gp);
            locationMarker.setSnippet(String.format(java.util.Locale.US, "±%.0f m", accuracy));

            if (firstFix) {
                firstFix = false;
                mapView.getController().setZoom(FIRST_FIX_ZOOM);
                mapView.getController().setCenter(gp);
            } else if (autoFollow) {
                // En düşük gecikme için anında ortala.
                mapView.getController().setCenter(gp);
            }
            mapView.invalidate();
        } catch (Throwable t) {
            Log.e(TAG, "updateLocation hatası", t);
        }
    }

    /** "Konumuma dön": otomatik takibi aç ve son konuma git. */
    public void recenter() {
        try {
            autoFollow = true;
            if (mapView != null && lastLat != null && lastLon != null) {
                mapView.getController().animateTo(new GeoPoint(lastLat, lastLon));
            }
        } catch (Throwable t) {
            Log.e(TAG, "recenter hatası", t);
        }
    }

    @Override
    public void dismiss() {
        // Kaynakları düzgün serbest bırak.
        try {
            if (mapView != null) {
                mapView.onPause();
                mapView.onDetach();
                mapView = null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Harita serbest bırakma hatası", t);
        }
        super.dismiss();
    }
}
