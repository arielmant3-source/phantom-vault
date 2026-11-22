package com.modehani.alarm;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** Colors and small view builders shared by both screens (dawn palette). */
final class Ui {
    static final int BG = Color.parseColor("#10131D");
    static final int SURFACE = Color.parseColor("#1A1F2E");
    static final int LINE = Color.parseColor("#2C3246");
    static final int FG = Color.parseColor("#ECE8DF");
    static final int MUTED = Color.parseColor("#9AA0B3");
    static final int ACCENT = Color.parseColor("#E39A5F");
    static final int OK = Color.parseColor("#5CC486");
    static final int RING = Color.parseColor("#F0645A");
    static final Typeface SERIF = Typeface.create("serif", Typeface.BOLD);

    private Ui() {}

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.25f);
        return t;
    }

    static GradientDrawable box(Context c, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 14));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static Button button(Context c, String s, boolean primary) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(primary ? Color.parseColor("#1B1206") : FG);
        b.setBackground(box(c, primary ? ACCENT : SURFACE, primary ? 0 : LINE));
        b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        b.setGravity(Gravity.CENTER);
        return b;
    }
}
