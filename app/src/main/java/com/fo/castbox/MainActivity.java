package com.fo.castbox;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity implements CastService.Ui {
    TextView nowTitle, timeText, log;
    LinearLayout deviceList;
    MaterialButton btnPlay;
    SeekBar seek, vol;
    final Handler h = new Handler(Looper.getMainLooper());
    String pickHost; int pickPort;
    boolean dragging = false;
    boolean pickBt = false;

    final Runnable poll = new Runnable() {
        @Override public void run() {
            if (CastService.i != null) CastService.i.requestStatus(MainActivity.this);
            h.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        nowTitle = findViewById(R.id.nowTitle);
        timeText = findViewById(R.id.timeText);
        log = findViewById(R.id.log);
        deviceList = findViewById(R.id.deviceList);
        btnPlay = findViewById(R.id.btnPlay);
        seek = findViewById(R.id.seek);
        vol = findViewById(R.id.vol);

        if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);

        findViewById(R.id.btnReceive).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            ensureThenRun(this::tapReceive, 0);
        });

        findViewById(R.id.btnDiscover).setOnClickListener(v -> ensureThenRun(() -> {
            CastService.i.startDiscovery(this);
            log.setText("Searching your wifi for CastBox devices...");
        }, 0));

        findViewById(R.id.btnBt).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
                return;
            }
            ensureThenRun(() -> { CastService.i.listBtDevices(this); log.setText("Loading your paired bluetooth devices..."); }, 0);
        });

        btnPlay.setOnClickListener(v -> { if (CastService.i != null) CastService.i.control("playpause", 0); });
        findViewById(R.id.btnStop).setOnClickListener(v -> { if (CastService.i != null) CastService.i.control("stop", 0); });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fu) {
                if (fu && CastService.i != null && CastService.i.hasDuration()) timeText.setText(fmt(p) + " / " + fmt(CastService.i.dur()));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { dragging = false; if (CastService.i != null) CastService.i.control("seek", sb.getProgress()); }
        });
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fu) {}
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { if (CastService.i != null) CastService.i.control("vol", sb.getProgress()); }
        });

        h.postDelayed(poll, 1500);
    }

    void ensureThenRun(final Runnable r, final int tries) {
        if (CastService.i != null) { r.run(); return; }
        if (tries == 0) startForegroundService(new Intent(this, CastService.class));
        if (tries > 15) { log.setText("Couldn't start the background service, close and reopen the app then try again"); return; }
        h.postDelayed(() -> ensureThenRun(r, tries + 1), 200);
    }

    void tapReceive() {
        try {
            MaterialButton b = findViewById(R.id.btnReceive);
            if (CastService.i.receiving()) { CastService.i.stopReceiving(); b.setText("Be the speaker"); }
            else { CastService.i.startReceiving(); b.setText("Stop speaker"); }
        } catch (Exception e) {
            log.setText("Speaker mode failed: " + e);
        }
    }

    static String fmt(int ms) { return String.format("%d:%02d", ms / 60000, (ms / 1000) % 60); }

    @Override public void onDeviceFound(final String name, final String host, final int port) {
        for (int x = 0; x < deviceList.getChildCount(); x++) if (name.equals(deviceList.getChildAt(x).getTag())) return;
        MaterialButton b = new MaterialButton(this);
        b.setText(name);
        b.setTag(name);
        b.setOnClickListener(v -> {
            pickBt = port < 0;
            pickHost = host; pickPort = port;
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("audio/*");
            startActivityForResult(it, 7);
        });
        deviceList.addView(b);
        log.setText("Found " + name + ". Tap it, then pick a song to cast.");
    }

    @Override protected void onActivityResult(int rq, int rc, Intent data) {
        super.onActivityResult(rq, rc, data);
        if (rq == 7 && rc == Activity.RESULT_OK && data != null && data.getData() != null && pickHost != null && CastService.i != null) {
            if (pickBt) CastService.i.btCastTo(pickHost, data.getData(), fileName(data.getData()));
            else CastService.i.castTo(pickHost, pickPort, data.getData(), fileName(data.getData()));
        }
    }

    String fileName(Uri u) {
        String n = "Audio";
        Cursor c = getContentResolver().query(u, null, null, null, null);
        if (c != null) {
            try { if (c.moveToFirst()) { int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ix >= 0) n = c.getString(ix); } }
            finally { c.close(); }
        }
        return n;
    }

    @Override public void onStatus(final String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            String state = o.optString("state", "stopped");
            int pos = o.optInt("pos", 0), dur = o.optInt("dur", 0), v = o.optInt("vol", vol.getProgress());
            String t = o.optString("title", "");
            if (t.length() > 0 && !t.equals(nowTitle.getText().toString())) nowTitle.setText(t);
            if (!dragging && dur > 0) {
                seek.setMax(dur);
                seek.setProgress(pos);
                timeText.setText(fmt(pos) + " / " + fmt(dur));
            }
            if (vol.getProgress() != v) vol.setProgress(v);
            btnPlay.setText("playing".equals(state) ? "Pause" : "Play");
        } catch (Exception e) {}
    }

    @Override public void onLog(final String m) { log.setText(m); }
}
