using System.Net;
using System.Security;
using ManagedNativeWifi;

namespace MapCastViewer;

/// <summary>
/// Windows Native WiFi API üzerinden ağ tarama ve bağlanma (ManagedNativeWifi).
/// </summary>
public static class WifiService
{
    /// <summary>Gerçek bir tarama tetikler, sonra bulunan SSID'leri döndürür.</summary>
    public static async Task<List<string>> ScanAsync()
    {
        try { await NativeWifi.ScanNetworksAsync(TimeSpan.FromSeconds(8)); }
        catch { /* WiFi arayüzü yok/kapalı olabilir */ }
        return ScanSsids();
    }

    /// <summary>Son tarama sonucundaki SSID'ler (tarama tetiklemez).</summary>
    public static List<string> ScanSsids()
    {
        try
        {
            return NativeWifi.EnumerateAvailableNetworkSsids()
                .Select(s => s.ToString())
                .Where(s => !string.IsNullOrWhiteSpace(s))
                .Distinct()
                .ToList();
        }
        catch
        {
            return new List<string>();
        }
    }

    /// <summary>Verilen SSID'ye WPA2-PSK parolasıyla bağlanmayı dener.</summary>
    public static async Task<bool> ConnectAsync(string ssid, string password)
    {
        var target = NativeWifi.EnumerateAvailableNetworks()
            .FirstOrDefault(n => n.Ssid.ToString() == ssid);
        if (target == null) return false;

        var interfaceId = target.Interface.Id;
        var xml = BuildProfileXml(ssid, password);

        var set = NativeWifi.SetProfile(
            interfaceId,
            ProfileType.AllUser,
            xml,
            null,
            overwrite: true);
        if (!set) return false;

        return await NativeWifi.ConnectNetworkAsync(
            interfaceId,
            ssid,
            BssType.Infrastructure,
            TimeSpan.FromSeconds(15));
    }

    private static string BuildProfileXml(string ssid, string password)
    {
        var escSsid = WebUtility.HtmlEncode(ssid);
        var escPass = WebUtility.HtmlEncode(password);
        // WPA2-PSK / AES profili
        return $@"<?xml version=""1.0""?>
<WLANProfile xmlns=""http://www.microsoft.com/networking/WLAN/profile/v1"">
  <name>{escSsid}</name>
  <SSIDConfig><SSID><name>{escSsid}</name></SSID></SSIDConfig>
  <connectionType>ESS</connectionType>
  <connectionMode>auto</connectionMode>
  <MSM><security>
    <authEncryption>
      <authentication>WPA2PSK</authentication>
      <encryption>AES</encryption>
      <useOneX>false</useOneX>
    </authEncryption>
    <sharedKey>
      <keyType>passPhrase</keyType>
      <protected>false</protected>
      <keyMaterial>{escPass}</keyMaterial>
    </sharedKey>
  </security></MSM>
</WLANProfile>";
    }
}
