# Haritalar — apk içi sesli navigasyon

Telefonun GPS'iyle **gerçek sıra-sıra (turn-by-turn) Türkçe sesli navigasyon**
yapan Android uygulaması. Her şey **apk içinde** çalışır; harici bir uygulamaya
atlamaz ve **API anahtarı / ücret gerektirmez**.

> Not: 1.x sürümleri Google Haritalar web sitesini WebView'da sarıyordu.
> Google web sürümünde canlı navigasyon vermediği için 2.0 ile harita motoru
> açık kaynak yığına taşındı (aşağıya bakın).

## İndir / Kur

- Derlenmiş APK: [`Haritalar-2.0.apk`](./Haritalar-2.0.apk) (imzalı release, ~2.6 MB)
- Telefona kopyalayıp dokunun; "bilinmeyen kaynak" iznini onaylayın.
- İlk açılışta **konum iznini verin** (navigasyon için şart).

## Nasıl kullanılır

1. Üstteki arama kutusuna hedefi yazın (adres veya yer adı) ve listeden seçin.
2. Alttaki panelde mesafe/varış görünür; **Başlat**'a basın.
3. Uygulama rotayı çizer, sizi takip eder ve **Türkçe sesli** olarak
   "200 metre sonra sağa dönün" gibi yönlendirir.
4. Rotadan saparsanız otomatik **yeniden hesaplar**. **Durdur** ile bitirir.

## Özellikler

- Gerçek turn-by-turn navigasyon + Türkçe sesli anons (Android TextToSpeech).
- Telefon GPS'iyle canlı konum takibi ve haritayı otomatik ortalama (follow).
- Yer/adres arama (Photon, yedek Nominatim).
- Rota çizimi, mesafe + tahmini varış saati, manevra ikonları.
- Rotadan sapınca otomatik yeniden hesaplama.
- Navigasyon sırasında ekran açık kalır.
- Dışarı atlama yok; her şey uygulama içinde.

## Harita motoru (açık kaynak, ücretsiz)

| Katman | Kaynak |
|--------|--------|
| Harita + döşemeler | [Leaflet](https://leafletjs.com) + OpenStreetMap |
| Rota (yol tarifi) | [OSRM](https://project-osrm.org) genel sunucusu |
| Arama (geocoding) | Photon (komoot) · yedek: Nominatim (OSM) |
| Sesli yönlendirme | Android TextToSpeech (tr-TR) · yedek: Web Speech |

Leaflet uygulama içine gömülüdür (`assets/nav/vendor/`). Harita döşemeleri,
rota ve arama için internet gerekir. Kullanılan genel servisler (OSRM demo,
Nominatim, Photon, OSM tile) kişisel kullanım içindir; yoğun/ticari kullanımda
kendi sunucunuzu veya anahtarlı bir sağlayıcıyı koymanız önerilir.

## Teknik bilgiler

| | |
|---|---|
| Paket adı | `com.webview.maps` |
| Uygulama adı | Haritalar |
| Sürüm | 2.0 (versionCode 4) |
| minSdk | 24 (Android 7.0) |
| targetSdk / compileSdk | 35 (Android 15) |
| Dil | Kotlin 2.0.21 + yerel HTML/JS (assets) |
| AGP / Gradle | 8.7.3 / 8.14.3 |

Uygulama arayüzü `app/src/main/assets/nav/` altındadır
(`index.html`, `style.css`, `app.js`) ve güvenli bir https origin'den
(`WebViewAssetLoader`) yüklenir; böylece konum ve ses güvenli bağlamda çalışır.

## Kaynaktan derleme

```bash
./android-sdk-setup.sh          # SDK: platform-35, build-tools 35.0.0
```

Release imzası için bir anahtar gerekir (`keystore.properties` ve `*.keystore`
**depoya dahil değildir**; kendiniz üretin):

```bash
keytool -genkeypair -v -keystore release.keystore -alias harita \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass DEGISTIRIN -keypass DEGISTIRIN \
  -dname "CN=Haritalar, O=Personal, C=TR"

cat > keystore.properties <<EOF
storeFile=release.keystore
storePassword=DEGISTIRIN
keyAlias=harita
keyPassword=DEGISTIRIN
EOF

./gradlew :app:assembleRelease
# Çıktı: app/build/outputs/apk/release/app-release.apk
```

İmzasız hızlı test için: `./gradlew :app:assembleDebug`.

## Sınırlar / notlar

- Sesli navigasyon için cihazda bir Türkçe TTS motoru olmalı (çoğu telefonda
  hazır gelir; yoksa Ayarlar → Erişilebilirlik → Metin okuma ile eklenebilir).
- Genel OSRM/Photon sunucuları zaman zaman yavaşlayabilir; bu uygulamanın değil
  servisin durumuyla ilgilidir.
- Harita görünümü Google değil OpenStreetMap'tir (ücretsiz ve açık).

---

## Ortam durumu (derleme sunucusu)

| Bileşen | Durum |
|---------|-------|
| Java (JDK) | OpenJDK 21 |
| Gradle | 8.14.3 |
| Android SDK | `android-sdk-setup.sh` ile kurulur |
| Platform | android-35 (Android 15) |
| Build-Tools | 35.0.0 |
