# Haritalar — Google Haritalar (apk içi)

Google Haritalar'ı (`https://www.google.com/maps`) tam ekran bir **WebView**
içinde açan Android uygulaması. Harita **%100 Google'dır** ve her şey **apk
içinde** kalır; "Başlat"/navigasyon bağlantıları başka uygulamaya atlamaz,
eşdeğer bir Google Haritalar yol-tarifi olarak aynı ekranda açılır.

## İndir / Kur

- Derlenmiş APK: [`Haritalar-2.1.apk`](./Haritalar-2.1.apk) (imzalı release, ~2.5 MB)
- Telefona kopyalayıp dokunun; "bilinmeyen kaynak" iznini onaylayın.
- İlk açılışta konum iznini verin (haritanın "Konumum" özelliği için).

## Özellikler

- Açılışta doğrudan Google Haritalar yüklenir (gerçek Google haritası).
- Mobil Chrome kullanıcı kimliğiyle tam mobil Google arayüzü.
- Konum izni → "Konumum", arama ve yol tarifi.
- **Uygulamadan dışarı atlama yok:** "Başlat" ve navigasyon derin bağlantıları
  (`intent://`, `geo:`, `google.navigation:`, `comgooglemaps:`) harici
  uygulamaya devredilmez; Google Haritalar web yol-tarifine çevrilip aynı
  WebView içinde açılır.
- "Yol tarifi" gibi yeni pencere açan butonlar ana ekranda açılır.
- Geri tuşu harita geçmişinde gezinir; `tel:`/`sms:`/`mailto:` ilgili uygulamaya.

## Önemli: canlı sesli navigasyon hakkında

Google, **canlı sıra-sıra (turn-by-turn) sesli navigasyonu web/WebView
sürümünde vermez** — bu yalnızca native Google Haritalar uygulamasının
özelliğidir. Bu APK Google haritasını ve yol tarifini apk içinde gösterir,
ama ekranda sesli "sağa dönün" yönlendirmesi yapmaz.

Google haritasının **üstüne sesli navigasyon** istenirse, resmi
**Google Maps Platform (JavaScript API)** ile eklenebilir; bunun için bir
**Google Cloud API anahtarı** gerekir (ücretsiz aylık 200$ kredi + faturalı
hesap). Anahtar sağlanırsa harita Google kalır ve navigasyon katmanı eklenir.

> Tam çalışan, ücretsiz ve anahtarsız sesli navigasyon isteyen için
> açık kaynak (OpenStreetMap) sürümü git geçmişinde `v2.0` etiketli commit'te
> (`c9ef78a`) bulunur — tek farkı harita görünümünün Google değil OSM olmasıdır.

## Teknik bilgiler

| | |
|---|---|
| Paket adı | `com.webview.maps` |
| Uygulama adı | Haritalar |
| Sürüm | 2.1 (versionCode 5) |
| minSdk | 24 (Android 7.0) |
| targetSdk / compileSdk | 35 (Android 15) |
| Dil | Kotlin 2.0.21 |
| AGP / Gradle | 8.7.3 / 8.14.3 |

## Kaynaktan derleme

```bash
./android-sdk-setup.sh          # SDK: platform-35, build-tools 35.0.0
```

Release imzası için anahtar gerekir (`keystore.properties` ve `*.keystore`
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

---

## Ortam durumu (derleme sunucusu)

| Bileşen | Durum |
|---------|-------|
| Java (JDK) | OpenJDK 21 |
| Gradle | 8.14.3 |
| Android SDK | `android-sdk-setup.sh` ile kurulur |
| Platform | android-35 (Android 15) |
| Build-Tools | 35.0.0 |
