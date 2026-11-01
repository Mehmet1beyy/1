package com.ivd.virtualdisplay;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

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

    // Mini "masaüstü": uygulama kapsayıcısı + WebView + alt görev çubuğu.
    private FrameLayout root;
    private FrameLayout appContainer;
    private View webApp;             // araç çubuğu + WebView
    private WebView webView;
    private TextView urlBar;
    private View tabMap;
    private View tabWeb;
    private int currentApp = 0;      // 0 = harita, 1 = web
    private boolean webLoaded = false;
    private String webUrl = "https://www.google.com";
    private long gestureDownTime = 0;

    // Gerçek GPS işaretçisi (ilk düzeltmede oluşturulur).
    private Marker locationMarker;
    // Arama/hedef işaretçisi.
    private Marker searchMarker;
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

        root = new FrameLayout(getContext());
        appContainer = new FrameLayout(getContext());
        root.addView(appContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

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

            appContainer.addView(mapView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // osmdroid'i etkinleştir (tile iş parçacıklarını başlatır).
            mapView.onResume();
        } catch (Throwable t) {
            // İnternet yoksa veya tile sağlayıcı başlatılamazsa uygulama
            // çökmemeli; harita olmadan HUD yine de çalışır.
            Log.e(TAG, "Harita başlatılamadı (çevrimdışı olabilir)", t);
        }

        // İkinci uygulama: araç çubuklu tarayıcı (başlangıçta gizli).
        try {
            webApp = buildWebApp();
            webApp.setVisibility(View.GONE);
            appContainer.addView(webApp, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            Log.e(TAG, "Tarayıcı oluşturulamadı", t);
        }

        // HUD her zaman en üstte: saat/sayaç + sürekli kare üretimi (hangi uygulama
        // önde olursa olsun yayın akmaya devam eder).
        hud = new HudOverlayView(getContext());
        root.addView(hud, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Alt görev çubuğu (Windows taskbar gibi): uygulama simgeleri.
        root.addView(buildTaskbar());

        setContentView(root);
        switchApp(0);
    }

    /** Alt görev çubuğunu oluşturur: 🗺 Harita ve 🌐 Web simgeleri. */
    private View buildTaskbar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(Color.parseColor("#E6101820"));
        int padV = dp(6);
        bar.setPadding(0, padV, 0, padV);

        tabMap = makeTab("🗺", "Harita", 0);
        tabWeb = makeTab("🌐", "Web", 1);
        bar.addView(tabMap);
        bar.addView(tabWeb);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(lp);
        return bar;
    }

    private View makeTab(String icon, String label, int index) {
        LinearLayout item = new LinearLayout(getContext());
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        int padH = dp(26), padV = dp(4);
        item.setPadding(padH, padV, padH, padV);

        TextView ic = new TextView(getContext());
        ic.setText(icon);
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        ic.setGravity(Gravity.CENTER);

        TextView tx = new TextView(getContext());
        tx.setText(label);
        tx.setTextColor(Color.WHITE);
        tx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tx.setGravity(Gravity.CENTER);

        item.addView(ic);
        item.addView(tx);

        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(dp(10), 0, dp(10), 0);
        item.setLayoutParams(ilp);

        item.setOnClickListener(v -> switchApp(index));
        return item;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getContext().getResources().getDisplayMetrics());
    }

    /** Görev çubuğundan uygulama değiştir: 0 = harita, 1 = web. */
    public void switchApp(int index) {
        try {
            currentApp = index;
            if (mapView != null) {
                mapView.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
            }
            if (webApp != null) {
                webApp.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
            }
            if (index == 1 && webView != null && !webLoaded) {
                webLoaded = true;
                webView.loadUrl(webUrl);
            }
            highlightTab(tabMap, index == 0);
            highlightTab(tabWeb, index == 1);
            if (root != null) {
                root.invalidate();
            }
        } catch (Throwable t) {
            Log.e(TAG, "switchApp hatası", t);
        }
    }

    private void highlightTab(View tab, boolean active) {
        if (tab != null) {
            tab.setBackgroundColor(active ? Color.parseColor("#3DDC97") : Color.TRANSPARENT);
        }
    }

    /** Araç çubuklu tarayıcı uygulamasını oluşturur (üstte ◀ ▶ ⟳ + adres, altta WebView). */
    private View buildWebApp() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.parseColor("#101820"));

        LinearLayout toolbar = new LinearLayout(getContext());
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(Color.parseColor("#182029"));
        int pad = dp(6);
        toolbar.setPadding(pad, pad, pad, pad);

        View back = navButton("◀");
        View fwd = navButton("▶");
        View reload = navButton("⟳");

        urlBar = new TextView(getContext());
        urlBar.setText(webUrl);
        urlBar.setTextColor(Color.WHITE);
        urlBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        urlBar.setSingleLine(true);
        urlBar.setBackgroundColor(Color.parseColor("#0E1512"));
        urlBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ulp.setMargins(dp(6), 0, 0, 0);
        urlBar.setLayoutParams(ulp);

        toolbar.addView(back);
        toolbar.addView(fwd);
        toolbar.addView(reload);
        toolbar.addView(urlBar);

        webView = new WebView(getContext());
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                if (urlBar != null) urlBar.setText(url);
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                if (urlBar != null) urlBar.setText(url);
            }
        });

        back.setOnClickListener(v -> { if (webView != null && webView.canGoBack()) webView.goBack(); });
        fwd.setOnClickListener(v -> { if (webView != null && webView.canGoForward()) webView.goForward(); });
        reload.setOnClickListener(v -> { if (webView != null) webView.reload(); });

        col.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private View navButton(String label) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        int p = dp(10);
        b.setPadding(p, dp(4), p, dp(4));
        return b;
    }

    /** Telefon/laptop adres çubuğundan URL yükler ve web uygulamasına geçer. */
    public void loadWebUrl(String url) {
        try {
            if (url == null || url.trim().isEmpty()) return;
            String u = url.trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "https://" + u;
            }
            webUrl = u;
            webLoaded = true;
            if (webView != null) webView.loadUrl(u);
            switchApp(1);
        } catch (Throwable t) {
            Log.e(TAG, "loadWebUrl hatası", t);
        }
    }

    /**
     * Laptoptan gelen dokunma olayı: action 0=down,1=move,2=up. (x,y) sanal ekran
     * koordinatı. Köke gönderilir → öndeki uygulama (harita/web/taskbar) doğal işler.
     */
    public void injectTouch(int action, int x, int y) {
        try {
            if (root == null) return;
            int a = action == 0 ? MotionEvent.ACTION_DOWN
                    : action == 1 ? MotionEvent.ACTION_MOVE
                    : MotionEvent.ACTION_UP;
            long now = SystemClock.uptimeMillis();
            if (a == MotionEvent.ACTION_DOWN) gestureDownTime = now;
            if (a == MotionEvent.ACTION_MOVE) autoFollow = false;
            MotionEvent ev = MotionEvent.obtain(gestureDownTime, now, a, x, y, 0);
            root.dispatchTouchEvent(ev);
            ev.recycle();
        } catch (Throwable t) {
            Log.e(TAG, "injectTouch hatası", t);
        }
    }

    /** Fare tekerleği: web'de sayfayı kaydır, haritada yakınlaştır/uzaklaştır. */
    public void wheel(int dir) {
        try {
            if (currentApp == 1 && webView != null) {
                webView.scrollBy(0, dir > 0 ? -180 : 180);
            } else if (mapView != null) {
                if (dir > 0) mapView.getController().zoomIn();
                else mapView.getController().zoomOut();
            }
        } catch (Throwable t) {
            Log.e(TAG, "wheel hatası", t);
        }
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

    /** Laptoptan gelen sürükleme: haritayı piksel kadar kaydır (ana iş parçacığı). */
    public void panBy(int dx, int dy) {
        try {
            if (mapView != null) {
                autoFollow = false;
                mapView.scrollBy(-dx, -dy);
                mapView.invalidate();
            }
        } catch (Throwable t) {
            Log.e(TAG, "panBy hatası", t);
        }
    }

    /** Arama sonucu / hedef konuma git ve işaretçi bırak (ana iş parçacığı). */
    public void goTo(double lat, double lon, String label) {
        try {
            if (mapView == null) {
                return;
            }
            autoFollow = false;
            GeoPoint gp = new GeoPoint(lat, lon);
            if (searchMarker == null) {
                searchMarker = new Marker(mapView);
                searchMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                mapView.getOverlays().add(searchMarker);
            }
            searchMarker.setPosition(gp);
            searchMarker.setTitle(label != null ? label : "Hedef");
            mapView.getController().setZoom(16.0);
            mapView.getController().animateTo(gp);
            mapView.invalidate();
        } catch (Throwable t) {
            Log.e(TAG, "goTo hatası", t);
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
            if (root != null && ev != null) {
                // Kullanıcı haritayı elle kaydırırsa otomatik takibi geçici kapat.
                if (ev.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                    autoFollow = false;
                }
                // Kök görünüme gönder: görev çubuğu ve öndeki uygulama olayı alır.
                root.dispatchTouchEvent(ev);
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
        try {
            if (webView != null) {
                webView.destroy();
                webView = null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "WebView serbest bırakma hatası", t);
        }
        super.dismiss();
    }
}
