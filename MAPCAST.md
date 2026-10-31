# MapCast — Sanal ekranı WiFi ile laptopa yayınlama

Telefonda arka planda **sanal ekrana** (`VirtualDisplay`) çizilen harita
görüntüsünü, telefon ekranını hiç kullanmadan, WiFi üzerinden bir Windows
uygulamasına (.exe) canlı yayınlar.

```
┌──────────────────────── TELEFON (Android) ────────────────────────┐
│                                                                    │
│   MapView (harita çizimi)                                          │
│        │                                                           │
│        ▼                                                           │
│   Presentation ──► VirtualDisplay ──► MediaCodec (H.264 kodlayıcı) │
│        (sanal ekran; telefon ekranı DEĞİL)        │                │
│                                                   ▼                │
│                              TcpStreamServer (PIN + H.264 Annex-B) │
│                              DiscoveryBeacon (UDP keşif)           │
│                              HotspotManager (yerel WiFi noktası)   │
└──────────────────────────────────┬─────────────────────────────── ┘
                                    │  WiFi (telefon = hotspot)
                                    ▼
┌──────────────────────── LAPTOP (Windows .exe) ────────────────────┐
│   WifiService → hotspot'a bağlan   │   Discovery → IP'yi bul       │
│   StreamClient → PIN el sıkışması  │   LibVLC → H.264 çöz + göster  │
└────────────────────────────────────────────────────────────────── ┘
```

## Neden bu yöntem? (Samsung DeX ile fark)

- **Samsung DeX** yalnızca Samsung cihazlarda çalışır ve uygulamanın *normal
  penceresini* masaüstüne taşır; arka plandaki görünmez bir `VirtualDisplay`'i
  tek başına yansıtmaz.
- Bu proje **her Android'de (8.0+) çalışır**, Samsung gerektirmez ve tam olarak
  istediğin şeyi yapar: **sadece sanal ekranı** yayınlar, telefon ekranını değil.
- `MediaProjection` (ekran kaydı izni) **gerekmez**, çünkü sanal ekran bizim
  kontrolümüzde; çıkışını doğrudan kodlayıcıya bağlıyoruz.

## İki parça

| Klasör | Ne | Durum |
|--------|-----|-------|
| `android/` | Demo harita + sanal ekran + WiFi yayın sunucusu (Kotlin) | ✅ APK burada derlenir |
| `desktop/` | Windows izleyici .exe (.NET 8 / WPF + LibVLC) | ⚠️ Windows'ta derlenir |

## Android tarafı (APK)

```bash
cd android
gradle :app:assembleDebug        # veya ./gradlew (wrapper üretildiyse)
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

Telefona kur, **Yayını Başlat**'a bas. İzinler: Konum (hotspot için) + Bildirim.
Ekranda SSID, parola, IP ve PIN görünür.

### Kendi haritanı koymak

`MapView` yerine kendi harita çizimini (Canvas / OpenGL / harita SDK'sı View'i)
`MapPresentation` içine koy. Yayın zinciri (VirtualDisplay → MediaCodec → TCP)
aynen çalışır. Yani demo haritayı söküp kendi simülasyonunu takabilirsin.

## Laptop tarafı (.exe)

Bkz. [`desktop/README.md`](desktop/README.md). Özet:

```powershell
cd desktop\MapCastViewer
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```

Çalıştır → **Ağları Tara** → telefonun SSID'si → parola → **WiFi'a Bağlan** →
IP otomatik dolar → **PIN** gir → **Yayını Aç**.

## Protokol (özet)

1. Telefon yerel hotspot açar (rastgele SSID + parola) ve `0.0.0.0:8080`'de TCP
   dinler; `:8099`'dan UDP keşif yayını yapar.
2. Laptop hotspot'a bağlanır, keşifle IP'yi bulur.
3. Laptop `MAPCAST <pin>\n` gönderir → sunucu `OK\n` → ardından **H.264 Annex-B**
   elementer akışı (önce SPS/PPS, sonra ilk IDR'den itibaren kareler).
4. LibVLC `:demux=h264` ile çözüp gösterir.

## Sınırlamalar / notlar

- Yerel hotspot için telefonun **konum servisi açık** olmalı ve telefon o sırada
  internet WiFi'ına bağlı kalamaz. Çalışmazsa: telefonun hotspot'unu elle aç,
  laptopu ona bağla, uygulamada IP+PIN ile **Yayını Aç**.
- Tek izleyici için ayarlı; TCP yazımı bloklayıcıdır (demo için yeterli).
  Çok izleyici / daha düşük gecikme istiyorsan WebRTC'ye geçilebilir.
- `.exe` bu Linux ortamında **derlenip test edilemez**; kaynak + talimat verildi,
  son derlemeyi Windows'ta `dotnet` ile sen yaparsın.
