# Independent Virtual Display

Android telefonun **fiziksel ekranından bağımsız** olarak 1280×720 çözünürlükte
bir **sanal ekran** oluşturan, bu ekranda örnek bir navigasyon arayüzü çizen ve
üretilen kareleri `ImageReader` ile yakalayıp uygulamada canlı önizleyen,
**Java tabanlı** bir Android uygulaması.

Hedef cihaz: **vivo X200 Pro · Android 15 · ARM64** (minSdk 29, targetSdk 35).

## Ne yapar?

- `DisplayManager.createVirtualDisplay(...)` ile uygulamaya özel (private) bir
  **VirtualDisplay** oluşturur (1280×720). Bayraklar:
  `VIRTUAL_DISPLAY_FLAG_PRESENTATION | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`
  (ekran görüntüsü yakalama / MediaProjection veya ek izin gerektirmez).
- Sanal ekrana bir **`Presentation`** (`MapPresentation`) bağlar ve içine
  **gerçek bir OpenStreetMap haritası** (osmdroid `MapView`) ile bir **konum
  işaretçisi** yerleştirir. Harita, telefonun fiziksel ekranından **bağımsız**
  olarak doğrudan sanal ekranda oluşturulur (ekran kopyalama/mirror yok).
- Haritanın üzerine şeffaf bir **`HudOverlayView`** çizilir: **her saniye değişen
  saat (HH:mm:ss)** ve **her saniye artan test sayacı** (sanal ekranın canlı ve
  bağımsız olduğunu kanıtlar). Bu katman her karede kendini yeniden çizerek
  sürekli kare üretimini garanti eder (harita hareketsiz olsa bile FPS/sayaç
  artmaya devam eder).
- **Yakınlaştırma/uzaklaştırma:** ana uygulamadaki `+` / `−` düğmeleri, bağlı
  servis üzerinden sanal ekrandaki haritanın zoom'unu kontrol eder.
- **Çevrimdışı dayanıklılık:** harita kurulumu `try/catch` ile korunur; internet
  yoksa döşeme indirilemese bile uygulama çökmez, HUD çalışmaya devam eder.
- Kareleri **`ImageReader`** üzerinden **RGBA_8888** formatında yakalar; toplam
  kare sayısını ve anlık FPS'i hesaplar.
- Tüm bu iş bir **foreground service** (`VirtualDisplayService`,
  `foregroundServiceType="specialUse"`) içinde yapılır; böylece Activity
  `onStop` olduğunda bile (başka uygulamaya geçildiğinde) sanal ekran **çalışmaya
  ve kare üretmeye devam eder**. Kaynaklar yalnızca kullanıcı "Durdur" dediğinde
  (veya servis yok edildiğinde) serbest bırakılır.
- Ana ekrandaki `ImageView`, sanal ekranın ürettiği karelerin **canlı
  önizlemesini** gösterir (CPU/bellek için ~15 fps'e sınırlandırılmıştır; kare
  sayacı yine de tüm kareleri sayar).

## Arayüz

- Sanal ekranı **Başlat** / **Durdur** düğmeleri
- Canlı önizleme alanı (`ImageView`)
- Durum yazısı (Durdu / Başlatılıyor / Çalışıyor)
- Toplam kare sayacı
- Anlık FPS göstergesi
- Çözünürlük bilgisi (1280 × 720)

## İzinler (yalnızca gerekli olanlar)

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_SPECIAL_USE`
- `POST_NOTIFICATIONS` (Android 13+ bildirim için; reddedilse bile servis çalışır)
- `INTERNET` (OSM harita döşemelerini indirmek için)
- `ACCESS_NETWORK_STATE` (osmdroid bağlantı kontrolü için)

Root, sistem dosyası değişikliği, konum (GPS) veya ekran yakalama izni
**gerektirmez**.

## Proje yapısı

```
app/src/main/java/com/ivd/virtualdisplay/
  MainActivity.java            # Arayüz + servise bağlanma, canlı önizleme, zoom
  VirtualDisplayService.java   # VirtualDisplay + ImageReader + Presentation (foreground)
  MapPresentation.java         # Sanal ekrana bağlanan Presentation: OSM haritası + işaretçi
  HudOverlayView.java          # Harita üstü şeffaf HUD: saat/sayaç + sürekli kare
app/src/main/res/...           # layout, strings, tema, simgeler
```

## Bağımlılıklar

- `androidx.appcompat`, `com.google.android.material`
- `org.osmdroid:osmdroid-android:6.1.20` (OpenStreetMap harita motoru)

## Derleme

Ortam Android SDK gerektirir (`android-sdk-setup.sh` kurar). Ardından:

```bash
./gradlew :app:assembleDebug
```

Çıktı (debug imzalı, kurulabilir APK):

```
app/build/outputs/apk/debug/app-debug.apk
```

> Not: Maven Central zaman zaman 429 (Too Many Requests) döndürdüğü için
> `settings.gradle.kts` içinde Google tarafından barındırılan Maven Central
> aynası birincil depo olarak tanımlıdır.

## Cihaza kurulum

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

veya APK'yı telefona kopyalayıp dosya yöneticisinden kurun (bilinmeyen
kaynaklara izin vermeniz gerekebilir).

## Test senaryosu (cihazda)

1. Uygulamayı aç, **Başlat**'a bas.
2. Önizlemede gerçek harita + konum işaretçisi + saat/sayacı gör; kare sayacı ve
   FPS artmaya başlar.
3. `+` / `−` düğmeleriyle sanal ekrandaki haritayı yakınlaştır/uzaklaştır.
4. Ana ekrana dön, WhatsApp/tarayıcı gibi başka bir uygulama aç.
5. Uygulamaya geri döndüğünde önizleme **güncel haritayı** gösterir; **kare
   sayacı ve FPS'in artmış** olduğunu gör (sanal ekran arka planda üretmeye
   devam etti).
6. **Durdur**'a basınca kaynaklar serbest bırakılır.

---

## Android derleme ortamı (altyapı)

`android-sdk-setup.sh` Android SDK'yı (cmdline-tools, platform-tools,
build-tools 35.0.0, platform android-35) kurar; idempotenttir. Bulut ortamının
**Kurulum betiği** alanına yapıştırılırsa her oturumda otomatik çalışır.

| Bileşen | Durum |
|---------|-------|
| Java (JDK) | OpenJDK 21 |
| Gradle | 8.14.3 |
| Android Gradle Plugin | 8.7.3 |
| Android SDK | android-35 / build-tools 35.0.0 |

Ağ: SDK indirmek için `dl.google.com` **Allowed domains** listesinde olmalıdır.

Ortam değişkenleri:

```
ANDROID_HOME=/root/android-sdk
ANDROID_SDK_ROOT=/root/android-sdk
```
