package com.fo.castbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.NsdManager;
import android.net.NsdServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.provider.OpenableColumns;
import java.util.Set;
import java.util.UUID;
import java.util.Enumeration;

public class CastService extends Service {
    public static CastService i;
    public interface Ui {
        void onDeviceFound(String name, String host, int port);
        void onStatus(String json);
        void onLog(String msg);
    }
    public volatile Ui ui;
    final Handler main = new Handler(Looper.getMainLooper());
    Server server;
    MediaPlayer player;
    AudioManager am;
    NsdManager nsd;
    NsdManager.DiscoveryListener disc;
    NsdManager.RegistrationListener reg;
    volatile File servingFile;
    String castHost; int castPort;
    volatile boolean isReceiver = false;
    String nowTitle = "Nothing playing";
    BluetoothAdapter btAdapter;
    BluetoothServerSocket btServer;
    volatile BluetoothSocket btClient;
    volatile boolean btCastActive = false;
    static final UUID BT_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");

    @Override public IBinder onBind(Intent i2) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        i = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        makeChannel();
    }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        goForeground("Ready");
        return START_STICKY;
    }

    void goForeground(String text) {
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, buildNotif(text), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else
                startForeground(1, buildNotif(text));
        } catch (Exception e) {}
    }

    void makeChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("castbox", "CastBox", NotificationManager.IMPORTANCE_LOW));
    }

    Notification buildNotif(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "castbox") : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("CastBox").setContentText(text).setOngoing(true).build();
    }

    void updateNotif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, buildNotif(text));
    }

    public boolean receiving() { return isReceiver; }

    public void startReceiving() {
        isReceiver = true;
        startServer();
        registerNsd();
        startBtListener();
        updateNotif("Speaker mode on");
        say("Speaker mode on. Other CastBox devices on this wifi can find you now.");
    }

    public void stopReceiving() {
        isReceiver = false;
        unregisterNsd();
        closeBtServer();
        stopPlayer();
        updateNotif("Ready");
        say("Speaker mode off.");
    }

    void startBtListener() {
        new Thread(() -> {
            try {
                if (btAdapter == null) btAdapter = BluetoothAdapter.getDefaultAdapter();
                if (btAdapter == null || !btAdapter.isEnabled()) { say("Bluetooth is off so bluetooth mode is skipped, wifi casting still works"); return; }
                btServer = btAdapter.listenUsingRfcommWithServiceRecord("CastBox", BT_UUID);
                while (true) {
                    BluetoothSocket s = btServer.accept();
                    handleBt(s);
                }
            } catch (Exception e) { if (isReceiver) say("Bluetooth listener stopped: " + e.getMessage()); }
        }).start();
    }

    void handleBt(BluetoothSocket s) {
        new Thread(() -> {
            try {
                InputStream in = s.getInputStream();
                StringBuilder hb = new StringBuilder();
                int c; while ((c = in.read()) > -1 && c != '\n') hb.append((char) c);
                String[] hp = hb.toString().split("\\|");
                String title = hp.length > 0 && hp[0].length() > 0 ? hp[0] : "Bluetooth cast";
                long size = hp.length > 1 ? Long.parseLong(hp[1]) : 0;
                File f = new File(getCacheDir(), "btcast.bin");
                OutputStream fo = new FileOutputStream(f);
                byte[] buf = new byte[65536]; long left = size; int n;
                while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) { fo.write(buf, 0, n); left -= n; }
                fo.flush(); fo.close();
                main.post(() -> { goForeground("Playing " + title); nowTitle = title; preparePlayer(Uri.fromFile(f).toString(), title); });
                BufferedReader r = new BufferedReader(new InputStreamReader(in));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().length() == 0) continue;
                    try {
                        String resp = outerDispatch(new JSONObject(line));
                        OutputStream os = s.getOutputStream();
                        os.write((resp + "\n").getBytes()); os.flush();
                    } catch (Exception e) {}
                }
                s.close();
            } catch (Exception e) { try { s.close(); } catch (Exception e2) {} }
        }).start();
    }

    void closeBtServer() {
        if (btServer != null) try { btServer.close(); } catch (Exception e) {}
        btServer = null;
    }

    public void listBtDevices(Ui u) {
        ui = u;
        new Thread(() -> {
            try {
                if (btAdapter == null) btAdapter = BluetoothAdapter.getDefaultAdapter();
                if (btAdapter == null || !btAdapter.isEnabled()) { say("Turn Bluetooth on in quick settings, then tap Bluetooth again"); return; }
                Set<BluetoothDevice> bs = btAdapter.getBondedDevices();
                int added = 0;
                for (BluetoothDevice d : bs) {
                    String name; try { name = d.getName() != null ? d.getName() : d.getAddress(); } catch (Exception e) { name = d.getAddress(); }
                    final String mac = d.getAddress(), fname = "BT: " + name;
                    main.post(() -> { if (ui != null) ui.onDeviceFound(fname, mac, -1); });
                    added++;
                }
                if (added == 0) say("No paired bluetooth devices yet. Pair one in android settings, then tap Bluetooth again");
            } catch (SecurityException e) { say("Bluetooth permission missing, allow it and try again"); }
        }).start();
    }

    public void btCastTo(String mac, Uri audio, String title) {
        new Thread(() -> {
            try {
                if (btAdapter == null) btAdapter = BluetoothAdapter.getDefaultAdapter();
                if (btAdapter == null || !btAdapter.isEnabled()) { say("Turn Bluetooth on first"); return; }
                BluetoothDevice d = btAdapter.getRemoteDevice(mac);
                BluetoothSocket sock = d.createRfcommSocketToServiceRecord(BT_UUID);
                try { if (btAdapter.isDiscovering()) btAdapter.cancelDiscovery(); } catch (Exception e) {}
                sock.connect();
                long size = sizeOf(audio);
                if (size <= 0) { sock.close(); say("Couldn't read that file's size, pick a different one"); return; }
                InputStream in = getContentResolver().openInputStream(audio);
                OutputStream os = sock.getOutputStream();
                os.write((title + "|" + size + "\n").getBytes());
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
                btClient = sock; btCastActive = true;
                String dn; try { dn = d.getName() != null ? d.getName() : mac; } catch (Exception e) { dn = mac; }
                say("Casting " + title + " over bluetooth to " + dn + ". Bluetooth is slower than wifi, give it a few seconds. Controls below drive their speaker.");
            } catch (Exception e) {
                btCastActive = false; btClient = null;
                say("Bluetooth cast failed: " + e.getMessage());
            }
        }).start();
    }

    long sizeOf(Uri u) {
        try {
            Cursor c = getContentResolver().query(u, null, null, null, null);
            if (c != null) {
                try { if (c.moveToFirst()) { int ix = c.getColumnIndex(OpenableColumns.SIZE); if (ix >= 0 && !c.isNull(ix)) return c.getLong(ix); } }
                finally { c.close(); }
            }
        } catch (Exception e) {}
        return 0;
    }

    void btSend(final JSONObject jo) {
        new Thread(() -> {
            synchronized (this) {
                try {
                    OutputStream os = btClient.getOutputStream();
                    os.write((jo.toString() + "\n").getBytes()); os.flush();
                    BufferedReader br = new BufferedReader(new InputStreamReader(btClient.getInputStream()));
                    String resp = br.readLine();
                    if (resp != null && resp.startsWith("{")) main.post(() -> { if (ui != null) { try { ui.onStatus(resp); } catch (Exception e) {} } });
                } catch (Exception e) { btCastActive = false; btClient = null; say("Bluetooth link lost"); }
            }
        }).start();
    }

    void startServer() {
        if (server != null) return;
        new Thread(() -> {
            try { server = new Server(); } catch (Exception e) { say("Server failed: " + e.getMessage()); }
        }).start();
    }

    void registerNsd() {
        try {
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        if (nsd == null) { say("This device doesn't support device discovery, sorry"); return; }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("CastBox-" + Build.MODEL);
        info.setServiceType("_castbox._tcp.");
        info.setPort(8765);
        reg = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo s) {}
            @Override public void onRegistrationFailed(NsdServiceInfo s, int e) { say("Registering speaker failed, try again"); }
            @Override public void onServiceUnregistered(NsdServiceInfo s) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg);
        } catch (Exception e) { say("Speaker registration failed: " + e); }
    }

    void unregisterNsd() {
        if (nsd != null && reg != null) try { nsd.unregisterService(reg); } catch (Exception e) {}
    }

    public void startDiscovery(Ui u) {
        ui = u;
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        stopDiscovery();
        disc = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo s) {
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {}
                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        final String name = info.getServiceName();
                        final String host = info.getHost() != null ? info.getHost().getHostAddress() : "";
                        final int port = info.getPort();
                        if (host.length() == 0) return;
                        main.post(() -> { if (ui != null) ui.onDeviceFound(name, host, port); });
                    }
                });
            }
            @Override public void onServiceLost(NsdServiceInfo s) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onStartDiscoveryFailed(String t, int e) { say("Discovery failed, try again"); }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
        };
        nsd.discoverServices("_castbox._tcp.", NsdManager.PROTOCOL_DNS_SD, disc);
    }

    public void stopDiscovery() {
        if (nsd != null && disc != null) try { nsd.stopServiceDiscovery(disc); } catch (Exception e) {}
        disc = null;
    }

    public void castTo(String host, int port, Uri audio, String title) {
        castHost = host; castPort = port;
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "cast.bin");
                InputStream in = getContentResolver().openInputStream(audio);
                OutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close(); out.close();
                servingFile = f;
                startServer();
                String url = "http://" + selfIp() + ":8765/audio";
                sendCommand(host, port, new JSONObject().put("cmd", "load").put("url", url).put("title", title).toString());
                say("Casting " + title + " to " + host + ". Controls below drive their speaker.");
            } catch (Exception e) {
                castHost = null;
                say("Cast failed: " + e.getMessage());
            }
        }).start();
    }

    public void control(String cmd, int val) {
        if (btCastActive && btClient != null) {
            try { btSend(new JSONObject().put("cmd", cmd).put("value", val)); } catch (Exception e) {}
            return;
        }
        if (castHost != null) {
            try { sendCommand(castHost, castPort, new JSONObject().put("cmd", cmd).put("value", val).toString()); } catch (Exception e) {}
        } else {
            applyLocal(cmd, val);
        }
    }

    void applyLocal(String cmd, int val) {
        main.post(() -> {
            if (player == null) return;
            switch (cmd) {
                case "playpause": if (player.isPlaying()) player.pause(); else player.start(); break;
                case "stop": stopPlayerNow(); break;
                case "seek": player.seekTo(val); break;
                case "vol": setVol(val); break;
            }
        });
    }

    void setVol(int pct) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, Math.round(pct * max / 100f))), 0);
    }

    int volPct() {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    public boolean hasDuration() { try { return player != null && player.getDuration() > 0; } catch (Exception e) { return false; } }
    public int dur() { try { return player != null ? player.getDuration() : 0; } catch (Exception e) { return 0; } }

    void stopPlayerNow() {
        if (player != null) {
            try { player.stop(); } catch (Exception e) {}
            player.release(); player = null;
        }
        nowTitle = "Nothing playing";
        updateNotif("Ready");
    }

    void stopPlayer() { main.post(this::stopPlayerNow); }

    void preparePlayer(String url, String title) {
        stopPlayerNow();
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        player.setOnPreparedListener(mp -> { mp.start(); nowTitle = title; updateNotif("Playing " + title); });
        player.setOnCompletionListener(mp -> { nowTitle = "Nothing playing"; updateNotif("Ready"); });
        try {
            player.setDataSource(url);
            player.prepareAsync();
        } catch (Exception e) { say("Playback error: " + e.getMessage()); }
    }

    public void requestStatus(Ui u) {
        ui = u;
        if (btCastActive && btClient != null) {
            try { btSend(new JSONObject().put("cmd", "status")); } catch (Exception e) {}
            return;
        }
        if (castHost != null) {
            try { sendCommand(castHost, castPort, new JSONObject().put("cmd", "status").toString()); } catch (Exception e) {}
        } else {
            main.post(() -> { if (ui != null) ui.onStatus(statusJson()); });
        }
    }

    String statusJson() {
        try {
            JSONObject o = new JSONObject();
            if (player != null && player.isPlaying()) o.put("state", "playing");
            else if (player != null) o.put("state", "paused");
            else o.put("state", "stopped");
            o.put("pos", player != null ? player.getCurrentPosition() : 0);
            o.put("dur", player != null ? player.getDuration() : 0);
            o.put("vol", volPct());
            o.put("title", nowTitle);
            return o.toString();
        } catch (Exception e) { return "{\"state\":\"stopped\"}"; }
    }

    String selfIp() throws Exception {
        Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
        while (nis.hasMoreElements()) {
            NetworkInterface ni = nis.nextElement();
            if (!ni.isUp() || ni.isLoopback()) continue;
            Enumeration<InetAddress> ads = ni.getInetAddresses();
            while (ads.hasMoreElements()) {
                InetAddress a = ads.nextElement();
                if (!a.isLoopbackAddress() && a.getHostAddress().indexOf(':') < 0) return a.getHostAddress();
            }
        }
        return "127.0.0.1";
    }

    void sendCommand(final String host, final int port, final String json) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/cmd").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                OutputStream os = c.getOutputStream();
                os.write(json.getBytes());
                os.close();
                InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
                BufferedReader r = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
                String resp = sb.toString().trim();
                if (resp.startsWith("{")) {
                    main.post(() -> { if (ui != null) { try { ui.onStatus(resp); } catch (Exception e) {} } });
                }
            } catch (Exception e) {}
        }).start();
    }

    void say(final String m) {
        main.post(() -> { if (ui != null) ui.onLog(m); });
    }

    class Server {
        ServerSocket ss;
        Server() throws Exception {
            ss = new ServerSocket(8765);
            new Thread(this::run).start();
        }
        void run() {
            while (true) {
                try {
                    Socket s = ss.accept();
                    new Thread(() -> handle(s)).start();
                } catch (Exception e) { break; }
            }
        }
        void handle(Socket s) {
            try {
                s.setSoTimeout(15000);
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
                String req = r.readLine();
                if (req == null) { s.close(); return; }
                String[] p = req.split(" ");
                String method = p[0], path = p[1];
                int cl = 0; String range = null;
                String hl;
                while ((hl = r.readLine()) != null && hl.length() > 0) {
                    String low = hl.toLowerCase();
                    if (low.startsWith("content-length:")) cl = Integer.parseInt(hl.substring(15).trim());
                    if (low.startsWith("range:")) range = hl.substring(6).trim();
                }
                if (path.startsWith("/audio")) { serve(s, range); return; }
                if (method.equals("POST") && path.equals("/cmd")) {
                    char[] body = new char[Math.max(0, cl)];
                    int off = 0;
                    while (off < cl) { int n = r.read(body, off, cl - off); if (n < 0) break; off += n; }
                    JSONObject jo = new JSONObject(new String(body));
                    String resp = outerDispatch(jo);
                    OutputStream os = s.getOutputStream();
                    byte[] rb = resp.getBytes();
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + rb.length + "\r\nConnection: close\r\n\r\n").getBytes());
                    os.write(rb); os.flush(); s.close();
                    return;
                }
                OutputStream os = s.getOutputStream();
                os.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                os.flush(); s.close();
            } catch (Exception e) { try { s.close(); } catch (Exception e2) {} }
        }
        void serve(Socket s, String range) throws Exception {
            File f = servingFile;
            if (f == null || !f.exists()) {
                OutputStream os = s.getOutputStream();
                os.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes());
                os.flush(); s.close(); return;
            }
            long len = f.length(), start = 0, end = len - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                try {
                    String v = range.substring(6).split(",")[0].trim();
                    String[] pr = v.split("-");
                    start = Long.parseLong(pr[0].trim());
                    if (pr.length > 1 && pr[1].trim().length() > 0) end = Long.parseLong(pr[1].trim());
                    partial = true;
                } catch (Exception e) {}
            }
            OutputStream os = s.getOutputStream();
            StringBuilder head = new StringBuilder();
            head.append(partial ? "HTTP/1.1 206 Partial Content" : "HTTP/1.1 200 OK").append("\r\n");
            head.append("Content-Type: audio/mpeg\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Content-Length: ").append(end - start + 1).append("\r\n");
            if (partial) head.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(len).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            os.write(head.toString().getBytes());
            InputStream in = new FileInputStream(f);
            in.skip(start);
            byte[] buf = new byte[65536];
            long remaining = end - start + 1;
            int n;
            while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                os.write(buf, 0, n);
                remaining -= n;
            }
            in.close(); os.flush(); s.close();
        }
        String dispatch(JSONObject jo) { return outerDispatch(jo); }
    }

    String outerDispatch(JSONObject jo) {
            final String cmd = jo.optString("cmd");
            final int val = jo.optInt("value", 0);
            final String url = jo.optString("url");
            final String title = jo.optString("title", "Now playing");
            switch (cmd) {
                case "load":
                    main.post(() -> {
                        goForeground("Playing " + title);
                        nowTitle = title;
                        preparePlayer(url, title);
                    });
                    break;
                case "playpause":
                    main.post(() -> { if (player != null) { if (player.isPlaying()) player.pause(); else player.start(); } });
                    break;
                case "stop":
                    stopPlayer();
                    break;
                case "seek":
                    main.post(() -> { if (player != null) player.seekTo(val); });
                    break;
                case "vol":
                    main.post(() -> setVol(val));
                    break;
                case "status":
                    return statusJson();
            }
            return "{\"ok\":true}";
        }

    @Override public void onDestroy() {
        i = null;
        btCastActive = false;
        if (btClient != null) try { btClient.close(); } catch (Exception e) {}
        closeBtServer();
        stopDiscovery();
        unregisterNsd();
        stopPlayerNow();
        super.onDestroy();
    }
}
