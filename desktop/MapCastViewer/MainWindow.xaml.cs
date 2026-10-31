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

    private void BtnScan_Click(object sender, RoutedEventArgs e)
    {
        var list = WifiService.ScanSsids();
        LstNetworks.ItemsSource = list;
        TxtStatus.Text = list.Count > 0
            ? $"{list.Count} ağ bulundu. Telefonun SSID'sini seç."
            : "Ağ bulunamadı. WiFi açık mı? 'Ağları Tara'yı tekrar dene.";
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
        var ip = TxtIp.Text.Trim();
        var pin = TxtPin.Text.Trim();
        if (string.IsNullOrWhiteSpace(ip)) { TxtStatus.Text = "Telefon IP'si boş."; return; }

        StopStream();
        TxtStatus.Text = $"{ip}:{_discoveredPort} bağlanılıyor…";
        BtnPlay.IsEnabled = false;
        try
        {
            var (client, stream) = await StreamClient.ConnectAsync(ip, _discoveredPort, pin);
            _client = client;
            _input = new StreamMediaInput(stream);
            _media = new Media(_libVLC!, _input,
                ":demux=h264",
                ":network-caching=150",
                ":live-caching=150",
                ":clock-jitter=0",
                ":clock-synchro=0");
            _player!.Play(_media);
            TxtStatus.Text = "Yayın açıldı. (Görüntü birkaç saniyede gelir.)";
        }
        catch (Exception ex)
        {
            TxtStatus.Text = "Bağlantı hatası: " + ex.Message;
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
    }
}
