using System.IO;
using System.Net.Sockets;
using System.Text;

namespace MapCastViewer;

/// <summary>
/// Laptoptan telefona kontrol komutları gönderir (ikinci TCP kanalı, port 8081).
/// El sıkışma yayın kanalıyla aynı: "MAPCAST &lt;pin&gt;" → "OK".
/// </summary>
public sealed class ControlClient : IDisposable
{
    private TcpClient? _client;
    private NetworkStream? _stream;

    public bool Connected => _stream != null;

    public async Task ConnectAsync(string host, int port, string pin)
    {
        _client = new TcpClient { NoDelay = true };
        await _client.ConnectAsync(host, port);
        var ns = _client.GetStream();

        var hello = Encoding.ASCII.GetBytes($"MAPCAST {pin}\n");
        await ns.WriteAsync(hello);
        await ns.FlushAsync();

        var sb = new StringBuilder();
        var one = new byte[1];
        while (true)
        {
            int n = await ns.ReadAsync(one.AsMemory(0, 1));
            if (n <= 0) { _client.Close(); throw new IOException("kontrol bağlantısı kapandı"); }
            if (one[0] == (byte)'\n') break;
            if (one[0] != (byte)'\r') sb.Append((char)one[0]);
        }
        if (sb.ToString().Trim() != "OK")
        {
            _client.Close();
            throw new IOException("kontrol reddedildi (PIN?)");
        }
        _stream = ns;
    }

    private void Send(string cmd)
    {
        var s = _stream;
        if (s == null) return;
        try
        {
            var b = Encoding.ASCII.GetBytes(cmd + "\n");
            s.Write(b, 0, b.Length);
            s.Flush();
        }
        catch
        {
            // kontrol kanalı koparsa görüntü akışını etkilemesin
        }
    }

    public void Pan(int dx, int dy) => Send($"PAN {dx} {dy}");
    public void Zoom(int dir) => Send($"ZOOM {dir}");

    public void Dispose()
    {
        try { _stream?.Dispose(); } catch { }
        try { _client?.Close(); } catch { }
        _stream = null;
        _client = null;
    }
}
