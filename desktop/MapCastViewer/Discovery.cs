using System.Net;
using System.Net.Sockets;
using System.Text;

namespace MapCastViewer;

/// <summary>
/// Telefonun UDP yayınını dinler:  "MAPCAST;ip=...;port=...;pin=..."
/// Bulununca IP/port bilgisini geri çağırır.
/// </summary>
public sealed class Discovery : IDisposable
{
    public const int UdpPort = 8099;
    private UdpClient? _udp;
    private CancellationTokenSource? _cts;

    public event Action<string, int>? Found; // ip, port

    public void Start()
    {
        Stop();
        _cts = new CancellationTokenSource();
        _udp = new UdpClient();
        _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udp.Client.Bind(new IPEndPoint(IPAddress.Any, UdpPort));
        _ = Task.Run(() => Loop(_cts.Token));
    }

    private async Task Loop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _udp != null)
        {
            try
            {
                var res = await _udp.ReceiveAsync(ct);
                var text = Encoding.UTF8.GetString(res.Buffer);
                if (!text.StartsWith("MAPCAST")) continue;
                string? ip = null; int port = 0;
                foreach (var part in text.Split(';'))
                {
                    var kv = part.Split('=', 2);
                    if (kv.Length != 2) continue;
                    if (kv[0] == "ip") ip = kv[1];
                    if (kv[0] == "port") int.TryParse(kv[1], out port);
                }
                if (ip != null && port > 0) Found?.Invoke(ip, port);
            }
            catch (OperationCanceledException) { break; }
            catch { /* yoksay, dinlemeye devam */ }
        }
    }

    public void Stop()
    {
        try { _cts?.Cancel(); } catch { }
        try { _udp?.Close(); } catch { }
        _udp = null;
        _cts = null;
    }

    public void Dispose() => Stop();
}
