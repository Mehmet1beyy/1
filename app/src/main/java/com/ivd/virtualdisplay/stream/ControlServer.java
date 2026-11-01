package com.ivd.virtualdisplay.stream;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Laptoptan gelen kontrol komutlarını dinler (ikinci TCP kanalı, port 8081).
 *
 * El sıkışma: "MAPCAST &lt;pin&gt;\n" → "OK\n". Ardından satır satır komutlar:
 *   PAN &lt;dx&gt; &lt;dy&gt;   — haritayı piksel kadar kaydır
 *   ZOOM &lt;1|-1&gt;         — yakınlaştır / uzaklaştır
 */
public class ControlServer {

    private static final String TAG = "IVD/ControlServer";

    public interface Handler {
        void onPan(int dx, int dy);
        void onZoom(int dir);
    }

    private final int port;
    private final String pin;
    private final Handler handler;

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public ControlServer(int port, String pin, Handler handler) {
        this.port = port;
        this.pin = pin;
        this.handler = handler;
    }

    public void start() {
        running = true;
        new Thread(this::acceptLoop, "ivd-ctrl-accept").start();
    }

    private void acceptLoop() {
        try {
            ServerSocket ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            serverSocket = ss;
            Log.i(TAG, "control listening on " + port);
            while (running) {
                Socket sock = ss.accept();
                new Thread(() -> handle(sock), "ivd-ctrl-client").start();
            }
        } catch (Exception e) {
            if (running) Log.w(TAG, "accept ended: " + e.getMessage());
        }
    }

    private void handle(Socket sock) {
        try {
            sock.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            OutputStream out = sock.getOutputStream();

            String hello = reader.readLine();
            String[] hp = hello == null ? new String[0] : hello.trim().split(" ");
            boolean ok = hp.length >= 2 && hp[0].equals("MAPCAST") && hp[1].equals(pin);
            if (!ok) {
                out.write("NO\n".getBytes()); out.flush(); sock.close();
                return;
            }
            out.write("OK\n".getBytes()); out.flush();
            Log.i(TAG, "control client " + sock.getInetAddress());

            String line;
            while (running && (line = reader.readLine()) != null) {
                parse(line.trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "control client ended: " + e.getMessage());
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    private void parse(String line) {
        if (line.isEmpty() || handler == null) return;
        try {
            String[] p = line.split(" ");
            switch (p[0]) {
                case "PAN":
                    if (p.length >= 3) {
                        handler.onPan(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    }
                    break;
                case "ZOOM":
                    if (p.length >= 2) {
                        handler.onZoom(Integer.parseInt(p[1]));
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "bad command: " + line);
        }
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        Log.i(TAG, "control stopped");
    }
}
