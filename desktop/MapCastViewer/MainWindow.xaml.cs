using System.IO;
using System.Net.Sockets;
using System.Windows;
using LibVLCSharp.Shared;

namespace MapCastViewer;

public partial class MainWindow : Window
{
    private LibVLC? _libVLC;
    private MediaPlayer? _player;

    private readonly Discovery _discovery = new();
    private int _discoveredPort = 8080;

    private TcpClient? _client;
    private StreamMediaInput? _input;
    private Media? _media;
    private StreamWriter? _logWriter;

    public MainWindow()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Closing += OnClosing;
    }

    private void OnLoaded(object? sender, RoutedEventArgs e)
    {
        _libVLC = new LibVLC();
        _player = new MediaPlayer(_libVLC);
        Video.MediaPlayer = _player;

        // VLC loglarını exe'nin yanındaki dosyaya yaz (teşhis için)
        try
        {
            var logPath = Path.Combine(AppContext.BaseDirectory, "mapcast-vlc.log");
            _logWriter = new StreamWriter(logPath, append: false) { AutoFlush = true };
            _libVLC.Log += (s, le) =>
            {
                try { _logWriter?.WriteLine($"[{le.Level}] {le.Module}: {le.Message}"); } catch { }
            };
        }
        catch { }

        // VLC ne yapıyor? Durumu ekrana yansıt.
        _player.Vout += (s, ev) => Dispatcher.Invoke(() =>
            TxtStatus.Text = $"Görüntü geldi ✓ ({ev.Count} video çıkışı).");
        _player.EncounteredError += (s, ev) => Dispatcher.Invoke(() =>
            TxtStatus.Text = "VLC çözme hatası. Ayrıntı: exe yanındaki mapcast-vlc.log");
        _player.Buffering += (s, ev) => Dispatcher.Invoke(() =>
        {
            if (ev.Cache < 100) TxtStatus.Text = $"Arabelleğe alınıyor… %{ev.Cache:0}";
        });

        _discovery.Found += (ip, port) => Dispatcher.Invoke(() =>
        {
            _discoveredPort = port;
            if (TxtIp.Text != ip)
            {
                TxtIp.Text = ip;
                TxtStatus.Text = $"Telefon ağda bulundu: {ip}:{port}";
            }
        });
        _discovery.Start();

        BtnScan_Click(this, e);
    }

    private async void BtnScan_Click(object sender, RoutedEventArgs e)
    {
        BtnScan.IsEnabled = false;
        TxtStatus.Text = "Ağlar taranıyor…";
        try
        {
            var list = await WifiService.ScanAsync();
            LstNetworks.ItemsSource = list;
            TxtStatus.Text = list.Count > 0
                ? $"{list.Count} ağ bulundu. Telefonun SSID'sini seç — VEYA bu adımı atla: " +
                  "laptopu telefonla aynı WiFi'a bağla, aşağıya Telefon IP + PIN yazıp 'Yayını Aç'."
                : "Ağ bulunamadı (laptopun WiFi'ı kapalı olabilir). Gerek de yok: " +
                  "laptopu telefonla AYNI ağa bağla, aşağıya Telefon IP + PIN yazıp 'Yayını Aç'.";
        }
        finally { BtnScan.IsEnabled = true; }
    }

    private async void BtnWifiConnect_Click(object sender, RoutedEventArgs e)
    {
        if (LstNetworks.SelectedItem is not string ssid)
        {
            TxtStatus.Text = "Önce listeden telefonun ağını seç.";
            return;
        }
        var pass = TxtWifiPass.Password;
        TxtStatus.Text = $"'{ssid}' ağına bağlanılıyor…";
        BtnWifiConnect.IsEnabled = false;
        try
        {
            var ok = await WifiService.ConnectAsync(ssid, pass);
            TxtStatus.Text = ok
                ? $"'{ssid}' bağlı. Şimdi PIN'i girip 'Yayını Aç'a bas."
                : "WiFi bağlantısı başarısız. Parolayı kontrol et ya da Windows'tan elle bağlan.";
        }
        catch (Exception ex)
        {
            TxtStatus.Text = "WiFi hatası: " + ex.Message +
                "\n(Elle bağlanıp yine de 'Yayını Aç'ı deneyebilirsin.)";
        }
        finally { BtnWifiConnect.IsEnabled = true; }
    }

    private async void BtnPlay_Click(object sender, RoutedEventArgs e)
    {
        var raw = TxtIp.Text.Trim();
        var pin = TxtPin.Text.Trim();
        if (string.IsNullOrWhiteSpace(raw)) { TxtStatus.Text = "Telefon IP'si boş."; return; }

        // "192.168.39.93" veya "192.168.39.93:8080" — ikisini de kabul et.
        string host = raw;
        int port = _discoveredPort;
        int idx = raw.LastIndexOf(':');
        if (idx > 0 && idx < raw.Length - 1 && int.TryParse(raw[(idx + 1)..], out var parsedPort))
        {
            host = raw[..idx];
            port = parsedPort;
        }

        StopStream();
        TxtStatus.Text = $"{host}:{port} bağlanılıyor…";
        BtnPlay.IsEnabled = false;
        try
        {
            var (client, stream) = await StreamClient.ConnectAsync(host, port, pin);
            _client = client;
            _input = new StreamMediaInput(stream);
            _media = new Media(_libVLC!, _input,
                ":demux=h264",
                ":no-audio",
                ":avcodec-hw=none",
                ":h264-fps=30",
                ":network-caching=200",
                ":live-caching=200",
                ":clock-jitter=0",
                ":clock-synchro=0");
            _player!.Play(_media);
            TxtStatus.Text = "Yayın açıldı. (Görüntü birkaç saniyede gelir.)";
        }
        catch (Exception ex)
        {
            TxtStatus.Text = "Bağlantı hatası: " + ex.Message +
                "\nİpucu: Telefon IP kutusuna SADECE IP yaz (örn. 192.168.39.93). " +
                "Laptop ile telefon AYNI WiFi ağında mı? (Aksi halde birbirlerini göremez.)";
            StopStream();
        }
        finally { BtnPlay.IsEnabled = true; }
    }

    private void StopStream()
    {
        try { _player?.Stop(); } catch { }
        try { _media?.Dispose(); } catch { }
        try { _input?.Dispose(); } catch { }
        try { _client?.Close(); } catch { }
        _media = null; _input = null; _client = null;
    }

    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        _discovery.Dispose();
        StopStream();
        try { _player?.Dispose(); } catch { }
        try { _libVLC?.Dispose(); } catch { }
        try { _logWriter?.Dispose(); } catch { }
    }
}
