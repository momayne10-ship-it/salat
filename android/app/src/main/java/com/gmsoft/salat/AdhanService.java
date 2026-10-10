package com.gmsoft.salat;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

public class AdhanService extends Service {

    private static final int NOTIF_ID = 4411;
    private static final long MAX_PLAY_MS = 10 * 60 * 1000L;
    private static final long NETWORK_TIMEOUT_MS = 7000;

    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean prepared = false;
    private boolean localFallback = false;
    private boolean stopped = false;

    private final Runnable safetyStop = new Runnable() {
        @Override
        public void run() { stopAll(); }
    };

    private final Runnable networkTimeout = new Runnable() {
        @Override
        public void run() {
            if (!prepared && !localFallback) playLocal();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopAll();
            return START_NOT_STICKY;
        }

        String title = intent != null ? intent.getStringExtra("title") : null;
        String src = intent != null ? intent.getStringExtra("src") : null;

        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(title),
                    Build.VERSION.SDK_INT >= 29
                            ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                            : 0);
        } catch (Exception ignored) {}

        acquireWakeLock();
        play(src);
        handler.removeCallbacks(safetyStop);
        handler.postDelayed(safetyStop, MAX_PLAY_MS);
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String title) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, AdhanService.class).setAction("stop");
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String t = (title == null || title.isEmpty()) ? "حان الآن وقت الصلاة" : title;
        return new NotificationCompat.Builder(this, SalatPlugin.CH_LIVE)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(t)
                .setContentText("الأذان يُبث الآن")
                .setContentIntent(content)
                .addAction(0, "إيقاف", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build();
    }

    private AudioAttributes alarmAttrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private void play(String src) {
        try {
            releasePlayer();
            prepared = false;
            localFallback = false;
            player = new MediaPlayer();
            player.setAudioAttributes(alarmAttrs());

            if (src != null && (src.startsWith("http://") || src.startsWith("https://"))) {
                player.setDataSource(src);
                player.setOnPreparedListener(p -> {
                    prepared = true;
                    handler.removeCallbacks(networkTimeout);
                    try { p.start(); } catch (Exception ignored) {}
                });
                player.setOnErrorListener((p, what, extra) -> {
                    if (!prepared) playLocal(); else stopAll();
                    return true;
                });
                player.setOnCompletionListener(p -> stopAll());
                player.prepareAsync();
                handler.postDelayed(networkTimeout, NETWORK_TIMEOUT_MS);
            } else {
                playLocal();
            }
        } catch (Exception e) {
            playLocal();
        }
    }

    private void playLocal() {
        if (stopped || localFallback) return;
        localFallback = true;
        handler.removeCallbacks(networkTimeout);
        try {
            if (player != null) {
                player.reset();
                player.release();
            }
        } catch (Exception ignored) {}

        try {
            prepared = false;
            player = new MediaPlayer();
            player.setAudioAttributes(alarmAttrs());
            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.athan_default);
            if (afd == null) { stopAll(); return; }
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            player.setOnPreparedListener(p -> {
                prepared = true;
                try { p.start(); } catch (Exception ignored) {}
            });
            player.setOnCompletionListener(p -> stopAll());
            player.setOnErrorListener((p, what, extra) -> {
                stopAll();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            stopAll();
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock == null || !wakeLock.isHeld()) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "salat:adhan");
                wakeLock.acquire(MAX_PLAY_MS + 30000);
            }
        } catch (Exception ignored) {}
    }

    private void stopAll() {
        if (stopped) return;
        stopped = true;
        handler.removeCallbacks(safetyStop);
        handler.removeCallbacks(networkTimeout);
        releasePlayer();
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {}
        stopSelf();
    }

    private void releasePlayer() {
        try {
            if (player != null) {
                player.reset();
                player.release();
            }
        } catch (Exception ignored) {}
        player = null;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        handler.removeCallbacks(safetyStop);
        handler.removeCallbacks(networkTimeout);
        releasePlayer();
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
