package com.modehani.alarm;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Watches for sleep: the screen stays off and the phone is not carried around for N hours.
 * When the screen next turns on, the Modeh Ani screen opens.
 *
 * A screen that lights up briefly at night (a notification) does not end the sleep:
 * only unlocking, or keeping the screen on for 45 seconds, counts as being awake.
 */
public class SleepWatchService extends Service {
    private static final long AWAKE_AFTER_MS = 45_000;
    private static final long SAID_RECENTLY_MS = 3 * 60 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SensorManager sensors;
    private Sensor motion;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, SleepWatchService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, SleepWatchService.class));
    }

    private final TriggerEventListener motionListener = new TriggerEventListener() {
        @Override public void onTrigger(TriggerEvent event) {
            // The phone is being carried: whoever holds it is awake. Count stillness from now.
            if (Prefs.sleepStart(SleepWatchService.this) != 0) {
                Prefs.setSleepStart(SleepWatchService.this, System.currentTimeMillis());
            }
            armMotion();
        }
    };

    private final Runnable markAwake = () -> Prefs.setSleepStart(this, 0);

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            long now = System.currentTimeMillis();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                handler.removeCallbacks(markAwake);
                if (Prefs.sleepStart(c) == 0) Prefs.setSleepStart(c, now);
                armMotion();
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                long start = Prefs.sleepStart(c);
                long need = Prefs.sleepHours(c) * 60L * 60L * 1000L;
                if (start != 0 && now - start >= need
                        && !AlarmService.running
                        && now - Prefs.lastSaid(c) > SAID_RECENTLY_MS) {
                    Prefs.setSleepStart(c, 0);
                    Notifs.showPrayerScreen(c, ModehAniActivity.MODE_WAKE, Notifs.wakeNotification(c), Notifs.ID_WAKE);
                }
                handler.removeCallbacks(markAwake);
                handler.postDelayed(markAwake, AWAKE_AFTER_MS);
                disarmMotion();
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                handler.removeCallbacks(markAwake);
                Prefs.setSleepStart(c, 0);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Notifs.channels(this);
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, Notifs.CH_WATCH)
                .setSmallIcon(R.drawable.ic_sunrise)
                .setContentTitle("מודה אני מחכה לבוקר")
                .setContentText("אחרי " + Prefs.sleepHours(this) + " שעות שינה יופיע מסך מודה אני")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifs.ID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(Notifs.ID_WATCH, n);
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen, f);
        sensors = getSystemService(SensorManager.class);
        motion = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
    }

    private void armMotion() {
        if (motion != null) sensors.requestTriggerSensor(motionListener, motion);
    }

    private void disarmMotion() {
        if (motion != null) sensors.cancelTriggerSensor(motionListener, motion);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screen);
        disarmMotion();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
