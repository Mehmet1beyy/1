using System.Windows;

namespace MapCastViewer;

public partial class App : Application
{
    public App()
    {
        // LibVLC yerel kütüphanelerini başlat (VideoLAN.LibVLC.Windows paketinden).
        LibVLCSharp.Shared.Core.Initialize();
    }
}
