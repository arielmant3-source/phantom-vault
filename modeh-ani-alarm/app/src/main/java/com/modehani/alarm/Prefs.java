package com.modehani.alarm;

import android.content.Context;
import android.content.SharedPreferences;

/** All saved settings and state in one place. */
final class Prefs {
    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences("modeh_ani", Context.MODE_PRIVATE);
    }

    static boolean alarmOn(Context c) { return get(c).getBoolean("alarm_on", false); }
    static int hour(Context c) { return get(c).getInt("hour", 6); }
    static int minute(Context c) { return get(c).getInt("minute", 30); }
    static boolean sleepOn(Context c) { return get(c).getBoolean("sleep_on", false); }
    static int sleepHours(Context c) { return get(c).getInt("sleep_hours", 5); }
    /** When the phone went still (screen off), or 0 when it is in use. */
    static long sleepStart(Context c) { return get(c).getLong("sleep_start", 0); }
    /** Last time the prayer was said, to avoid showing the screen twice in one morning. */
    static long lastSaid(Context c) { return get(c).getLong("last_said", 0); }

    static void setAlarm(Context c, boolean on, int h, int m) {
        get(c).edit().putBoolean("alarm_on", on).putInt("hour", h).putInt("minute", m).apply();
    }
    static void setSleep(Context c, boolean on, int hours) {
        get(c).edit().putBoolean("sleep_on", on).putInt("sleep_hours", hours).apply();
    }
    static void setSleepStart(Context c, long t) { get(c).edit().putLong("sleep_start", t).apply(); }
    static void setLastSaid(Context c, long t) { get(c).edit().putLong("last_said", t).apply(); }
}
