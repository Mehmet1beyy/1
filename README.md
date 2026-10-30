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

## Kurulum betiği

[`android-sdk-setup.sh`](./android-sdk-setup.sh) Android SDK'yı
(cmdline-tools, platform-tools, build-tools, platform) kurar. Idempotenttir:
zaten kuruluysa atlar.

Bu betik, bulut ortamının **Kurulum betiği (Setup script)** alanına
yapıştırılırsa her yeni oturumda otomatik çalışır.

### Ağ gereksinimi

SDK indirmek için ortamın ağ erişiminde `dl.google.com` adresinin
**Allowed domains** listesinde olması gerekir. (Ortam ayarları → Network access)

### Ortam değişkenleri

```
ANDROID_HOME=/root/android-sdk
ANDROID_SDK_ROOT=/root/android-sdk
```
