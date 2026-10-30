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

    // Başlangıç konumu (İstanbul) ve işaretçi.
    private static final double START_LAT = 41.0082;
    private static final double START_LON = 28.9784;
    private static final double START_ZOOM = 15.0;
    private static final double MIN_ZOOM = 3.0;
    private static final double MAX_ZOOM = 19.0;

    private MapView mapView;
    private HudOverlayView hud;

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

            mapView.getController().setZoom(START_ZOOM);
            GeoPoint start = new GeoPoint(START_LAT, START_LON);
            mapView.getController().setCenter(start);

            // Konum işaretçisi
            Marker marker = new Marker(mapView);
            marker.setPosition(start);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle("Konum");
            mapView.getOverlays().add(marker);

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
