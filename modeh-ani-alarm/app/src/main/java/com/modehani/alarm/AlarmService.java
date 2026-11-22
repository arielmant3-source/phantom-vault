package com.modehani.alarm;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Rings until the prayer has been said. There is no stop button anywhere. */
public class AlarmService extends Service {
    static final String ACTION_STOP = "com.modehani.alarm.STOP";

    private static AlarmService instance;
    static volatile boolean running;

    private MediaPlayer player;
    private ToneGenerator tone;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startedAt;
    private boolean ducked;

    static void start(Context c) {
        Intent i = new Intent(c, AlarmService.class);
        c.startForegroundService(i);
    }

    /** Called by the prayer screen once the prayer was said. */
    static void stop(Context c) {
        c.startService(new Intent(c, AlarmService.class).setAction(ACTION_STOP));
    }

    /** Lowers the sound while the microphone listens, so the words can be heard. */
    static void duck(boolean on) {
        AlarmService s = instance;
        if (s != null) s.handler.post(() -> s.setDucked(on));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        instance = this;
        running = true;
        Notifs.channels(this);

        PendingIntent screen = Notifs.prayerScreen(this, ModehAniActivity.MODE_ALARM);
        Notification n = new Notification.Builder(this, Notifs.CH_ALARM)
                .setSmallIcon(R.drawable.ic_sunrise)
                .setContentTitle("בוקר טוב! הגיע הזמן לקום")
                .setContentText("כדי לעצור, אמור מודה אני")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(screen, true)
                .setContentIntent(screen)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifs.ID_ALARM, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(Notifs.ID_ALARM, n);
        }
        Notifs.showPrayerScreen(this, ModehAniActivity.MODE_ALARM, null, 0);

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "modehani:ring");
        wakeLock.acquire(3 * 60 * 60 * 1000L);

        startedAt = System.currentTimeMillis();
        startSound();
        startVibration();
        handler.post(ramp);
        return START_STICKY;
    }

    private void startSound() {
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.setVolume(0.3f, 0.3f);
            player.start();
        } catch (Exception e) {
            if (player != null) { player.release(); player = null; }
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            handler.post(beeper);
        }
    }

    private final Runnable beeper = new Runnable() {
        @Override public void run() {
            if (tone != null && !ducked) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800);
            handler.postDelayed(this, 1500);
        }
    };

    /** Starts at 30% and reaches full volume after one minute. */
    private final Runnable ramp = new Runnable() {
        @Override public void run() {
            applyVolume();
            handler.postDelayed(this, 1000);
        }
    };

    private void applyVolume() {
        if (player == null) return;
        float elapsed = (System.currentTimeMillis() - startedAt) / 1000f;
        float v = ducked ? 0.04f : Math.min(1f, 0.3f + elapsed / 60f * 0.7f);
        try { player.setVolume(v, v); } catch (IllegalStateException ignored) { }
    }

    private void startVibration() {
        vibrator = getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 900, 700}, 0),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
    }

    private void setDucked(boolean on) {
        if (!running || ducked == on) return;
        ducked = on;
        applyVolume();
        if (on) { if (vibrator != null) vibrator.cancel(); } else startVibration();
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        handler.removeCallbacksAndMessages(null);
        if (player != null) { try { player.stop(); } catch (Exception ignored) { } player.release(); player = null; }
        if (tone != null) { tone.release(); tone = null; }
        if (vibrator != null) vibrator.cancel();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
