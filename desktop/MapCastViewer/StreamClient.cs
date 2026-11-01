using System.IO;
using System.Net.Sockets;
using System.Text;

namespace MapCastViewer;

/// <summary>
/// Telefona TCP ile bağlanır, "MAPCAST &lt;pin&gt;" el sıkışmasını yapar ve
/// doğrulama başarılıysa video akışının okunacağı Stream'i döndürür.
/// </summary>
public static class StreamClient
{
    public static async Task<(TcpClient client, NetworkStream stream)> ConnectAsync(
        string ip, int port, string pin)
    {
        var client = new TcpClient { NoDelay = true };
        await client.ConnectAsync(ip, port);
        var ns = client.GetStream();

        // El sıkışma satırını gönder
        var hello = Encoding.ASCII.GetBytes($"MAPCAST {pin}\n");
        await ns.WriteAsync(hello);
        await ns.FlushAsync();

        // Tek satır cevap oku ("OK" / "NO"); video baytlarına dokunma
        var sb = new StringBuilder();
        var one = new byte[1];
        while (true)
        {
            int n = await ns.ReadAsync(one.AsMemory(0, 1));
            if (n <= 0) { client.Close(); throw new IOException("Bağlantı kapandı (el sıkışma)."); }
            if (one[0] == (byte)'\n') break;
            if (one[0] != (byte)'\r') sb.Append((char)one[0]);
        }

        var reply = sb.ToString().Trim();
        if (reply != "OK")
        {
            client.Close();
            throw new IOException($"Sunucu reddetti: '{reply}' (PIN yanlış olabilir).");
        }
        return (client, ns);
    }
}
