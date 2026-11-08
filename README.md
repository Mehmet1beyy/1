# Haritalar — Google Haritalar WebView uygulaması

Google Haritalar'ı (`https://www.google.com/maps`) tam ekran bir **WebView**
içinde açan basit bir Android uygulaması. Konum izni verildiğinde haritanın
"Konumum" özelliği çalışır.

## İndir / Kur

- Derlenmiş APK: [`Haritalar-1.2.apk`](./Haritalar-1.2.apk) (imzalı release, ~2.5 MB)
- Telefona kopyalayıp dosyaya dokunarak kurabilirsiniz. İlk kurulumda
  "Bilinmeyen kaynaklara izin ver" uyarısını onaylamanız gerekebilir.

## Özellikler

- Açılışta doğrudan Google Haritalar yüklenir.
- Mobil Chrome kullanıcı kimliği (user-agent) ile tam donanımlı mobil arayüz.
- Konum izni (ACCESS_FINE/COARSE_LOCATION) → "Konumum" ve navigasyon.
- Geri tuşu harita geçmişinde gezinir; geçmiş bitince uygulamadan çıkar.
- JavaScript, DOM storage, yakınlaştırma, ekran döndürme desteği.
- **"Yol tarifi"** gibi yeni pencere açan butonlar ana ekranda açılır
  (çok pencere + `onCreateWindow` desteği).
- **Uygulamadan dışarı atlama yok:** "Başlat" ve navigasyon derin bağlantıları
  (`intent://`, `geo:`, `google.navigation:`, `comgooglemaps:`) başka uygulamaya
  devredilmez; eşdeğer bir Google Haritalar web yol-tarifi adresine çevrilip
  aynı WebView içinde açılır. (Canlı sesli turn-by-turn navigasyon Google'ın web
  sürümünde bulunmadığından verilmez; rota ve adım listesi apk içinde gösterilir.)
- Yalnızca apk içinde karşılığı olmayan `tel:`, `sms:`, `mailto:` bağlantıları
  ilgili sistem uygulamasına açılır.

## Teknik bilgiler

| | |
|---|---|
| Paket adı | `com.webview.maps` |
| Uygulama adı | Haritalar |
| minSdk | 24 (Android 7.0) |
| targetSdk / compileSdk | 35 (Android 15) |
| Dil | Kotlin 2.0.21 |
| AGP / Gradle | 8.7.3 / 8.14.3 |

## Kaynaktan derleme

Android SDK'yı kurmak için (bulut ortamı veya yerel):

```bash
./android-sdk-setup.sh          # SDK: platform-35, build-tools 35.0.0
```

Release APK üretmek için bir imza anahtarı gerekir. `keystore.properties` ve
`*.keystore` dosyaları **depoya dahil değildir** (gizlidir). Kendiniz üretin:

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
```

Sonra derleyin:

```bash
./gradlew :app:assembleRelease
# Çıktı: app/build/outputs/apk/release/app-release.apk
```

İmza dosyaları yoksa `assembleRelease` imzasız APK üretir; imzasız APK çoğu
cihaza doğrudan kurulamaz. Hızlı test için imzasız sorunu olmayan debug APK'yı
da kullanabilirsiniz:

```bash
./gradlew :app:assembleDebug
# Çıktı: app/build/outputs/apk/debug/app-debug.apk
```

## Notlar

- Bu uygulama Google Haritalar web sitesini açar; Google Play Services veya
  Google Maps API anahtarı **gerektirmez**.
- Google'ın web sitesi tasarımını değiştirirse davranış de değişebilir.
- Uygulama Google ile resmi bir ilişki içinde değildir; kişisel kullanım için
  bir WebView sarmalayıcıdır.

---

## Ortam durumu (derleme sunucusu)

| Bileşen | Durum |
|---------|-------|
| Java (JDK) | OpenJDK 21 |
| Gradle | 8.14.3 |
| Android SDK | `android-sdk-setup.sh` ile kurulur |
| Platform | android-35 (Android 15) |
| Build-Tools | 35.0.0 |

SDK indirmek için ağ erişiminde `dl.google.com` (ve Maven depoları) açık olmalı.
