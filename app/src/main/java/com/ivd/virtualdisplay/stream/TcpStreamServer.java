package com.ivd.virtualdisplay.stream;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * H.264 Annex-B akışını TCP üzerinden yayınlar (MapCastViewer ile uyumlu).
 *
 * El sıkışma: istemci tek satır "MAPCAST &lt;pin&gt;\n" gönderir.
 *   - PIN doğru → "OK\n", ardından ikili H.264 akışı.
 *   - PIN yanlış → "NO\n" ve kapanır.
 * Her anahtar karenin (IDR) önüne SPS/PPS gömülür (en uyumlu).
 */
public class TcpStreamServer implements StreamEncoder.FrameSink {

    private static final String TAG = "IVD/TcpServer";

    public interface Callback {
        void onClientCount(int n);
        void requestKeyframe();
    }

    private static final class Client {
        final Socket socket;
        final OutputStream out;
        volatile boolean started = false;
        Client(Socket s, OutputStream o) { socket = s; out = o; }
    }

    private final int port;
    private final String pin;
    private final Callback cb;

    private ServerSocket serverSocket;
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private volatile byte[] csd;
    private volatile boolean running = false;

    private volatile int framesEncoded = 0;
    private volatile long bytesSent = 0L;

    public TcpStreamServer(int port, String pin, Callback cb) {
        this.port = port;
        this.pin = pin;
        this.cb = cb;
    }

    public int getFrames() { return framesEncoded; }
    public long getBytes() { return bytesSent; }
    public int getClientCount() { return clients.size(); }

    public void start() {
        running = true;
        new Thread(this::acceptLoop, "ivd-accept").start();
    }

    private void acceptLoop() {
        try {
            ServerSocket ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            serverSocket = ss;
            Log.i(TAG, "listening on " + port);
            while (running) {
                Socket sock = ss.accept();
                new Thread(() -> handshake(sock), "ivd-client").start();
            }
        } catch (Exception e) {
            if (running) Log.w(TAG, "accept loop ended: " + e.getMessage());
        }
    }

    private void handshake(Socket sock) {
        try {
            sock.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String line = reader.readLine();
            if (line == null) { sock.close(); return; }
            String[] parts = line.trim().split(" ");
            boolean ok = parts.length >= 2 && parts[0].equals("MAPCAST") && parts[1].equals(pin);
            OutputStream out = sock.getOutputStream();
            if (!ok) {
                out.write("NO\n".getBytes()); out.flush(); sock.close();
                Log.w(TAG, "client rejected (bad pin)");
                return;
            }
            out.write("OK\n".getBytes()); out.flush();

            Client client = new Client(sock, out);
            clients.add(client);
            if (cb != null) { cb.onClientCount(clients.size()); cb.requestKeyframe(); }
            Log.i(TAG, "client accepted " + sock.getInetAddress());
        } catch (Exception e) {
            Log.w(TAG, "handshake failed: " + e.getMessage());
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onFormat(byte[] csd) {
        this.csd = csd; // SPS/PPS sakla; her IDR'den önce ekle
    }

    @Override
    public void onFrame(byte[] data, boolean keyframe) {
        framesEncoded++;
        if (clients.isEmpty()) return;
        byte[] head = keyframe ? csd : null;
        java.util.ArrayList<Client> dead = new java.util.ArrayList<>();
        for (Client c : clients) {
            if (!c.started) {
                if (!keyframe) continue;
                c.started = true;
            }
            try {
                synchronized (c.out) {
                    if (head != null) { c.out.write(head); bytesSent += head.length; }
                    c.out.write(data); bytesSent += data.length;
                    c.out.flush();
                }
            } catch (Exception e) {
                dead.add(c);
            }
        }
        if (!dead.isEmpty()) {
            for (Client c : dead) {
                try { c.socket.close(); } catch (Exception ignored) {}
                clients.remove(c);
            }
            if (cb != null) cb.onClientCount(clients.size());
        }
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        for (Client c : clients) {
            try { c.socket.close(); } catch (Exception ignored) {}
        }
        clients.clear();
        Log.i(TAG, "stopped");
    }
}
