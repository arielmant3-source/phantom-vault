package com.modehani.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

final class Notifs {
    static final String CH_ALARM = "alarm";
    static final String CH_WAKE = "wake";
    static final String CH_WATCH = "watch";
    static final int ID_ALARM = 1;
    static final int ID_WATCH = 2;
    static final int ID_WAKE = 3;

    private Notifs() {}

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel a = new NotificationChannel(CH_ALARM, "שעון מעורר", NotificationManager.IMPORTANCE_HIGH);
        a.setSound(null, null); // the service plays the sound itself
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(a);
        NotificationChannel w = new NotificationChannel(CH_WAKE, "מסך מודה אני אחרי שינה", NotificationManager.IMPORTANCE_HIGH);
        w.setSound(null, null);
        w.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(w);
        NotificationChannel s = new NotificationChannel(CH_WATCH, "זיהוי שינה", NotificationManager.IMPORTANCE_MIN);
        s.setShowBadge(false);
        nm.createNotificationChannel(s);
    }

    static PendingIntent prayerScreen(Context c, String mode) {
        Intent i = new Intent(c, ModehAniActivity.class)
                .putExtra(ModehAniActivity.EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        int req = ModehAniActivity.MODE_ALARM.equals(mode) ? 10 : 11;
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Opens the prayer screen: directly when allowed, and always through a full-screen notification. */
    static void showPrayerScreen(Context c, String mode, Notification n, int id) {
        if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c)) {
            try {
                c.startActivity(new Intent(c, ModehAniActivity.class)
                        .putExtra(ModehAniActivity.EXTRA_MODE, mode)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
        if (n != null) c.getSystemService(NotificationManager.class).notify(id, n);
    }

    static Notification wakeNotification(Context c) {
        PendingIntent pi = prayerScreen(c, ModehAniActivity.MODE_WAKE);
        return new Notification.Builder(c, CH_WAKE)
                .setSmallIcon(R.drawable.ic_sunrise)
                .setContentTitle("בוקר טוב")
                .setContentText("מודה אני לפניך מלך חי וקיים…")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(30 * 60 * 1000L)
                .build();
    }
}
