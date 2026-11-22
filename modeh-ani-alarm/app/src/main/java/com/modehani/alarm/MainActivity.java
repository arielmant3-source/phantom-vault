package com.modehani.alarm;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView timeText, nextText, hoursText;
    private Switch alarmSwitch, sleepSwitch;
    private LinearLayout checks;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        Notifs.channels(this);
        buildUi();
        askRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 18);
        c.setPadding(p, p, p, p);
        c.setBackground(Ui.box(this, Ui.SURFACE, Ui.LINE));
        TextView t = Ui.text(this, title, 22, Ui.FG);
        t.setTypeface(Ui.SERIF);
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 16);
        parent.addView(c, lp);
        return c;
    }

    private Switch toggle(LinearLayout parent, String label) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.FG);
        s.setTextSize(17);
        s.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        parent.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private void buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = Ui.dp(this, 18);
        col.setPadding(pad, Ui.dp(this, 28), pad, Ui.dp(this, 32));

        TextView title = Ui.text(this, "מודה אני", 34, Ui.ACCENT);
        title.setTypeface(Ui.SERIF);
        col.addView(title);
        col.addView(Ui.text(this, "שעון מעורר שנעצר רק כשאומרים מודה אני", 15, Ui.MUTED));

        // Alarm
        LinearLayout a = card(col, "שעון מעורר");
        timeText = Ui.text(this, "", 56, Ui.FG);
        timeText.setTypeface(Ui.SERIF);
        timeText.setGravity(Gravity.CENTER);
        timeText.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        timeText.setOnClickListener(v -> pickTime());
        a.addView(timeText, new LinearLayout.LayoutParams(-1, -2));
        Button change = Ui.button(this, "שנה שעה", false);
        change.setOnClickListener(v -> pickTime());
        a.addView(change, new LinearLayout.LayoutParams(-1, -2));
        alarmSwitch = toggle(a, "השעון פעיל (כל יום)");
        alarmSwitch.setOnCheckedChangeListener((v, on) -> {
            if (on == Prefs.alarmOn(this)) return;
            Prefs.setAlarm(this, on, Prefs.hour(this), Prefs.minute(this));
            AlarmScheduler.schedule(this);
            refresh();
        });
        nextText = Ui.text(this, "", 14, Ui.MUTED);
        a.addView(nextText);
        Button test = Ui.button(this, "נסה צלצול עכשיו", false);
        test.setOnClickListener(v -> AlarmService.start(this));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Ui.dp(this, 12);
        a.addView(test, tl);

        // Sleep
        LinearLayout s = card(col, "מודה אני אחרי שינה");
        s.addView(Ui.text(this, "כשהמסך כבוי והטלפון לא זז במשך כמה שעות, האפליקציה מבינה שישנת. "
                + "בפעם הבאה שהמסך נדלק יופיע מסך מודה אני. אפשר לדלג עליו.", 14, Ui.MUTED));
        sleepSwitch = toggle(s, "הפעל זיהוי שינה");
        sleepSwitch.setOnCheckedChangeListener((v, on) -> {
            if (on == Prefs.sleepOn(this)) return;
            Prefs.setSleep(this, on, Prefs.sleepHours(this));
            if (on) SleepWatchService.start(this); else SleepWatchService.stop(this);
            refresh();
        });
        LinearLayout hours = new LinearLayout(this);
        hours.setGravity(Gravity.CENTER_VERTICAL);
        hours.setPadding(0, Ui.dp(this, 8), 0, 0);
        Button minus = Ui.button(this, "−", false);
        Button plus = Ui.button(this, "+", false);
        hoursText = Ui.text(this, "", 17, Ui.FG);
        hoursText.setGravity(Gravity.CENTER);
        minus.setOnClickListener(v -> changeHours(-1));
        plus.setOnClickListener(v -> changeHours(1));
        hours.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 56), -2));
        hours.addView(hoursText, new LinearLayout.LayoutParams(0, -2, 1));
        hours.addView(minus, new LinearLayout.LayoutParams(Ui.dp(this, 56), -2));
        s.addView(hours, new LinearLayout.LayoutParams(-1, -2));

        // Permissions
        LinearLayout p = card(col, "כדי שזה יעבוד כמו שעון אמיתי");
        checks = new LinearLayout(this);
        checks.setOrientation(LinearLayout.VERTICAL);
        p.addView(checks);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.addView(col);
        setContentView(scroll);
    }

    private void pickTime() {
        new TimePickerDialog(this, android.R.style.Theme_Material_Dialog, (v, h, m) -> {
            Prefs.setAlarm(this, true, h, m);
            AlarmScheduler.schedule(this);
            refresh();
        }, Prefs.hour(this), Prefs.minute(this), true).show();
    }

    private void changeHours(int d) {
        int h = Math.max(2, Math.min(12, Prefs.sleepHours(this) + d));
        Prefs.setSleep(this, Prefs.sleepOn(this), h);
        if (Prefs.sleepOn(this)) { SleepWatchService.stop(this); SleepWatchService.start(this); }
        refresh();
    }

    private void refresh() {
        timeText.setText(String.format(Locale.US, "%02d:%02d", Prefs.hour(this), Prefs.minute(this)));
        alarmSwitch.setChecked(Prefs.alarmOn(this));
        if (Prefs.alarmOn(this)) {
            long at = AlarmScheduler.nextTrigger(this);
            long mins = (at - System.currentTimeMillis()) / 60000;
            nextText.setText("הצלצול הבא: " + new SimpleDateFormat("EEEE HH:mm", new Locale("he")).format(new Date(at))
                    + " (בעוד " + (mins / 60) + " שעות ו־" + (mins % 60) + " דקות)");
        } else {
            nextText.setText("השעון כבוי.");
        }
        sleepSwitch.setChecked(Prefs.sleepOn(this));
        hoursText.setText("אחרי " + Prefs.sleepHours(this) + " שעות");
        refreshChecks();
    }

    // ---------- Permissions ----------

    private void askRuntimePermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        refreshChecks();
    }

    private void addCheck(boolean ok, String label, String why, Runnable fix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView t = Ui.text(this, (ok ? "✓  " : "✗  ") + label, 16, ok ? Ui.OK : Ui.RING);
        row.addView(t);
        if (!ok) {
            row.addView(Ui.text(this, why, 13, Ui.MUTED));
            Button b = Ui.button(this, "לתקן", true);
            b.setOnClickListener(v -> { try { fix.run(); } catch (Exception ignored) { } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.dp(this, 6);
            row.addView(b, lp);
        }
        checks.addView(row);
    }

    private void refreshChecks() {
        if (checks == null) return;
        checks.removeAllViews();
        String pkg = getPackageName();
        Runnable appSettings = () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));

        addCheck(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                "מיקרופון", "בלי מיקרופון צריך להקליד את מודה אני כדי לעצור את השעון.", () -> {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                            || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
                    else appSettings.run();
                });

        boolean notifOk = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        addCheck(notifOk, "התראות", "השעון משתמש בהתראה כדי לפתוח את המסך כשהטלפון נעול.", appSettings);

        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean fullOk = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent();
        addCheck(fullOk, "מסך מלא על מסך הנעילה", "בלי זה, מסך מודה אני לא ייפתח לבד כשהטלפון נעול.",
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + pkg))));

        PowerManager pm = getSystemService(PowerManager.class);
        addCheck(pm.isIgnoringBatteryOptimizations(pkg), "בלי הגבלת סוללה",
                "חלק מהטלפונים (סמסונג, שיאומי) סוגרים אפליקציות ברקע. זה חשוב במיוחד לזיהוי השינה.",
                () -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg))));

        addCheck(Settings.canDrawOverlays(this), "הצגה מעל אפליקציות אחרות (מומלץ)",
                "מאפשר לפתוח את מסך מודה אני מיד, גם כשהטלפון לא נעול.",
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + pkg))));
    }
}
