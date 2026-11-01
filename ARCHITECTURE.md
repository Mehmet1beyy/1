# Mimari ve Çalışma Sistemi

> Bu belge, APK'yı çözümlemeye (decompile) gerek kalmadan uygulamanın nasıl
> çalıştığını hızlıca anlamak içindir. Kaynak kodun tamamı bu depodadır.

Paket: `com.ivd.virtualdisplay` · minSdk 29 · targetSdk 35 · Java · Gradle (AGP 8.7.3)
Tek harici bağımlılık (harita): `org.osmdroid:osmdroid-android:6.1.20`

---

## 1. Tek cümlede

Uygulama, telefonun **fiziksel ekranından bağımsız** 1280×720 bir **VirtualDisplay**
oluşturur; bu ekranda bir **Presentation** içinde gerçek bir **OpenStreetMap**
haritası çizer; ekranın kareleri **ImageReader** ile yakalanıp ana uygulamadaki
bir **ImageView**'de canlı önizlenir. Harita, ana uygulamadaki önizlemeye gelen
**dokunuşlarla** kontrol edilir ve işaretçi **gerçek GPS** konumunu takip eder.
Tüm bu iş bir **foreground service** içinde yürür; uygulama arka plandayken de
sürer.

## 2. Bileşenler (dosya → görev)

| Dosya | Görev |
|-------|-------|
| `VirtualDisplayService.java` | **Çekirdek.** Foreground service. VirtualDisplay + ImageReader + Presentation'ı yönetir, kareleri yakalar, FPS/sayaç hesaplar, GPS dinler. Activity'ye binder + `Listener` callback'leriyle veri verir. |
| `MapPresentation.java` | VirtualDisplay'e bağlı `Presentation`. İçinde osmdroid `MapView` (gerçek OSM) + GPS işaretçisi. Zoom, dokunma iletimi, otomatik takip ve "konumuma dön" burada. |
| `HudOverlayView.java` | Haritanın üstünde şeffaf katman. Saat + test sayacı çizer ve **her karede kendini invalidate ederek sürekli kare üretimini garanti eder**. |
| `MainActivity.java` | Arayüz. Servise bağlanır, canlı önizlemeyi gösterir, dokunuşları ölçekleyip servise iletir, GPS bilgi panelini ve izinleri yönetir. |
| `activity_main.xml` | Önizleme ImageView + GPS paneli + Başlat/Durdur + zoom + "konumuma dön" düğmeleri. |

## 3. Süreç ve iş parçacığı modeli

- **Tek süreç.** MainActivity ve VirtualDisplayService aynı süreçte, aynı **ana
  (main) Looper**'ı paylaşır. Activity ↔ Service iletişimi `LocalBinder` ile
  doğrudan metot çağrısıdır (IPC yok).
- **Ana iş parçacığı:** UI, Presentation/MapView çizimi, GPS callback'leri,
  dokunma iletimi. Hepsi hafif tutulur.
- **`ivd-reader` HandlerThread:** ImageReader karelerinin alınıp Bitmap'e
  dönüştürülmesi burada yapılır; böylece ana iş parçacığı kilitlenmez.
- osmdroid kendi tile indirme iş parçacıklarını yönetir (ağ ana thread'de değil).

## 4. Üç ana veri akışı

### A) Kare boru hattı (sanal ekran → canlı önizleme)
```
Navigation/HUD + MapView çizer
      → VirtualDisplay (1280x720, surface = ImageReader.getSurface())
      → ImageReader.onImageAvailable  [ivd-reader thread]
      → toBitmap(): RGBA_8888 düzlemi, rowStride dolgusu giderilip
        ARGB_8888 Bitmap'e kopyalanır (kırpılarak 1280x720)
      → mainHandler.post → Listener.onPreview(bitmap)
      → MainActivity: ImageView.setImageBitmap(); önceki Bitmap recycle()
```
- VirtualDisplay bayrakları: `VIRTUAL_DISPLAY_FLAG_PRESENTATION | OWN_CONTENT_ONLY`
  → uygulamaya özel, **ekran kopyalama/MediaProjection gerektirmez**.
- Önizleme ~15 fps'e sınırlı (CPU/bellek); kare sayacı ise **her** kareyi sayar.
- Sürekli kare: `HudOverlayView.onDraw` sonunda `postInvalidateOnAnimation()`.

### B) Dokunma iletimi (önizleme → sanal ekran haritası)
```
ImageView dokunuşu (MainActivity.onPreviewTouch)
      → fitCenter letterbox ofseti çıkarılır, (x,y) 1280x720 uzayına ölçeklenir
      → çok parmaklı MotionEvent yeniden kurulur (MotionEvent.obtain)
      → service.forwardTouch → MapPresentation.dispatchMapTouch
      → MapView.dispatchTouchEvent(ev)   [osmdroid gestures]
```
- Sürükleme, çift dokunma zoom, pinch (`setMultiTouchControls(true)`), basılı tutma
  osmdroid tarafından doğrudan işlenir. Ana uygulamada **ayrı harita yoktur**;
  aynı MapView kontrol edilir.
- Kullanıcı sürükleyince (`ACTION_MOVE`) otomatik GPS takibi geçici kapanır.

### C) GPS senkronizasyonu (gerçek konum → sanal ekran işaretçisi)
```
GPS donanımı → LocationManager (olay tabanlı; minTime=0, minDistance=0; polling yok)
      → LocationListener.onLocationChanged  [main Looper]
      → servis alanları güncellenir (lat/lon/accuracy/fixTime/arrival)
      → MapPresentation.updateLocation(): işaretçi taşınır; autoFollow ise setCenter
      → Listener.onLocation → MainActivity GPS paneli güncellenir
```
- Konum değişmediyse `updateLocation` erken döner (gereksiz yenileme yok).
- Sinyal/izin kaybında son konum korunur, işaretçi oynatılmaz; durum panelde belli
  olur; GPS gelince otomatik devam eder.
- "Geçen süre" etiketi için MainActivity'de 500 ms'lik hafif bir UI tiker var —
  bu yalnızca gösterim tazeler, **GPS polling değildir**.

## 5. Yaşam döngüsü ve arka plan

- **Başlat:** MainActivity → `startForegroundService(ACTION_START)` →
  `onStartCommand` → `startForeground(...)` (tip: `specialUse | location`) →
  `startCapture()` (VirtualDisplay+ImageReader+Presentation) + izin varsa
  `startLocation()`.
- **Arka plan:** Activity `onStop`'ta yalnızca binder çözülür ve listener bırakılır;
  **service foreground olduğu için VirtualDisplay, kare üretimi ve GPS sürer.**
- **Geri dönüş:** `onStart` → yeniden bind → `setListener` → en güncel kare/konum
  hemen gösterilir.
- **Durdur:** `ACTION_STOP` → `stopCapture()`: GPS dinleyici kaldırılır,
  Presentation dismiss (MapView `onPause`/`onDetach`), VirtualDisplay release,
  ImageReader close, reader thread quit, Bitmap'ler recycle → `stopForeground`.

## 6. İzinler (yalnızca gerekli olanlar)

`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
`FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `INTERNET`,
`ACCESS_NETWORK_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`.
Root / sistem değişikliği / ekran yakalama **yok**. Konum izni ilk açılışta istenir;
reddedilirse çökme olmaz, uyarı gösterilir, sonradan verilince
`enableLocation()` ile algılanır.

## 7. Bellek ve dayanıklılık notları

- ImageReader `Image` her zaman `finally`'de `close()` edilir.
- Okuma Bitmap'i (`readerBitmap`) yeniden kullanılır; önizleme Bitmap'i yeni kare
  gelince/durdurulunca `recycle()` edilir.
- Harita kurulumu `try/catch(Throwable)` ile sarılıdır; internet yoksa tile
  gelmese bile uygulama çökmez, HUD çalışır.

## 8. Derleme

```bash
./gradlew :app:assembleDebug
# çıktı: app/build/outputs/apk/debug/app-debug.apk
```
Not: `settings.gradle.kts` Maven Central için Google aynasını birincil depo yapar
(429 hız sınırı önlemi).

## 9. Nereden okumaya başlamalı

1. `VirtualDisplayService.java` — boru hattının kalbi (bölüm 4A, 4C burada).
2. `MapPresentation.java` — harita + işaretçi + zoom + dokunma (4B, 4C).
3. `MainActivity.java` — UI, önizleme, dokunma ölçekleme (4A, 4B), GPS paneli.
4. `HudOverlayView.java` — sürekli kare üretimi + saat/sayaç.
