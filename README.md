# Android derleme ortamı

Bu depo, Claude Code bulut ortamında Android uygulaması derleyip indirilebilir
bir APK üretmek için hazırlanmıştır.

## Ortam durumu

| Bileşen | Durum |
|---------|-------|
| Java (JDK) | OpenJDK 21 (kurulu) |
| Gradle | 8.14.3 (kurulu) |
| Kotlin | 2.0.21 (Gradle ile) |
| Android SDK | `android-sdk-setup.sh` ile kurulur |
| Platform | android-35 (Android 15) |
| Build-Tools | 35.0.0 |

## Betikler

| Betik | Görev |
|-------|-------|
| [`android-sdk-setup.sh`](./android-sdk-setup.sh) | Android SDK'yı (cmdline-tools, platform-tools, build-tools, platform) kurar. Idempotenttir: zaten kuruluysa atlar. |
| [`build-apk.sh`](./build-apk.sh) | Bir Gradle Android projesini derleyip APK üretir. |

### `android-sdk-setup.sh`

Bu betik, bulut ortamının **Kurulum betiği (Setup script)** alanına
yapıştırılırsa her yeni oturumda otomatik çalışır. Ortam ayarlarına ulaşmak
için: oturum başlığındaki bulut ortamı menüsü → **Edit** → **Setup script**.
Yeni oturumlar düzeltilmiş betiği çalıştırır.

> **Not:** Betiğin kurulum satırındaki `yes | sdkmanager` boru hattı,
> `sdkmanager` girdiyi okumayı bitirip stdin'i kapatınca `yes`'e SIGPIPE
> (exit 141) gönderiyordu ve `set -o pipefail` altında bu, gerçek bir hata
> olmadığı halde betiği öldürüyordu. Betik artık bu çağrıları `pipefail`
> kapalıyken çalıştırıyor; böylece pipeline durumu `sdkmanager`'ın gerçek
> çıkış kodu oluyor.

### `build-apk.sh`

```bash
./build-apk.sh [proje-dizini] [debug|release]
```

- `proje-dizini`: `gradlew` dosyasının bulunduğu dizin (varsayılan: `.`)
- varyant: `debug` (varsayılan) veya `release`

Betik SDK'nın kurulu olduğunu doğrular, Gradle'a SDK yolunu bildirmek için
gerekirse `local.properties` yazar (`sdk.dir=...`), `./gradlew assembleDebug`
(veya `assembleRelease`) çalıştırır ve üretilen `.apk` dosyalarının yolunu
listeler.

### Ağ gereksinimi

SDK indirmek için ortamın ağ erişiminde `dl.google.com` adresinin
**Allowed domains** listesinde olması gerekir. (Ortam ayarları → Network access)

### Ortam değişkenleri

`android-sdk-setup.sh` bu değişkenleri `~/.bashrc` ve `~/.profile` dosyalarına
kalıcı olarak yazar; yeni kabuk oturumlarında otomatik yüklenir:

```
ANDROID_HOME=$HOME/android-sdk          # bu ortamda: /root/android-sdk
ANDROID_SDK_ROOT=$ANDROID_HOME
PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
```

> **ÖNEMLİ — ortam ayarlarındaki değişken değeri:** Bulut ortamının
> **Environment variables** bölümünde `ANDROID_HOME` ve `ANDROID_SDK_ROOT`
> değerleri baştaki `/` olmadan (`root/android-sdk`) tanımlıydı. Bu **göreli**
> bir yoldur ve çalışma dizinine göre yanlış yere çözülür. Doğrusu:
>
> ```
> ANDROID_HOME=/root/android-sdk
> ANDROID_SDK_ROOT=/root/android-sdk
> ```
>
> Bunları ortam ayarlarından düzeltin (oturum başlığı → bulut ortamı menüsü →
> **Edit** → **Environment variables**) **veya** tamamen silin; betikler
> `ANDROID_HOME`'u zaten `$HOME/android-sdk` olarak ayarlar. Güvenlik için her
> iki betik de kendisine gelen `ANDROID_HOME` mutlak değilse uyarıp mutlak
> varsayılana düşer, böylece bozuk değerle bile çalışır.
