# MapCastViewer (Windows .exe)

Telefonun **sanal ekranındaki** harita görüntüsünü WiFi üzerinden izleyen native
Windows uygulaması (.NET 8 / WPF). Video çözme için LibVLC, WiFi için Windows
Native WiFi API kullanır.

## Akış

1. **Ağları Tara** → telefon hotspot'unun SSID'sini seç.
2. **Parola** gir → **WiFi'a Bağlan** (ya da Windows'tan elle bağlan).
3. Telefon IP'si otomatik dolar (UDP keşfi); dolmazsa `192.168.49.1` yaz.
4. Telefonun ekranındaki **PIN**'i gir → **Yayını Aç**.

## Derleme

Gerekli: [.NET 8 SDK](https://dotnet.microsoft.com/download) (Windows).

```powershell
cd desktop\MapCastViewer
dotnet restore
dotnet build -c Release
# Çalıştır:
dotnet run -c Release
```

### Tek dosya .exe üretmek

```powershell
dotnet publish -c Release -r win-x64 --self-contained true `
  -p:PublishSingleFile=true
# Çıktı: bin\Release\net8.0-windows\win-x64\publish\MapCastViewer.exe
```

> LibVLC yerel kütüphaneleri (`libvlc.dll`, `plugins\`) `VideoLAN.LibVLC.Windows`
> paketinden gelir ve publish çıktısına kopyalanır. `.exe`'yi taşırken yanındaki
> `libvlc` dosyalarını da taşı (tek-dosya publish bunları exe'nin yanına açar).

## Notlar

- **WiFi'a programatik bağlanma** bazı kurumsal makinelerde kısıtlı olabilir.
  Çalışmazsa Windows'tan elle hotspot'a bağlan, sonra IP + PIN ile **Yayını Aç**.
- Protokol: TCP'de tek satır `MAPCAST <pin>\n` → sunucu `OK\n` → ardından
  H.264 Annex-B elementer akışı. VLC `:demux=h264` ile çözer.
- Gecikme düşük tutulmuştur (`network-caching=150ms`); WiFi koşuluna göre ayarla.
