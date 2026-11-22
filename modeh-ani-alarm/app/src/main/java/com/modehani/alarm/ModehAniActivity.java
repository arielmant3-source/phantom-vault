package com.modehani.alarm;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * The Modeh Ani screen. In alarm mode it cannot be closed until the prayer is said aloud.
 * In wake mode (after a long sleep) it opens the same way but can be skipped.
 */
public class ModehAniActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String MODE_ALARM = "alarm";
    static final String MODE_WAKE = "wake";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean[] hits = new boolean[PrayerMatcher.WORDS.length];
    private String mode = MODE_ALARM;
    private boolean done, resumed, listening;
    private int errorsInRow;

    private SpeechRecognizer recognizer;
    private TextView prayer, heard, status, doneText, clock;
    private Button mic, skip;
    private EditText typed;
    private String settled = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        readMode(getIntent());
        buildUi();
        paint();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String old = mode;
        readMode(intent);
        if (MODE_ALARM.equals(mode) && !MODE_ALARM.equals(old)) {
            done = false;
            hits = new boolean[PrayerMatcher.WORDS.length];
            settled = "";
            buildUi();
            paint();
        }
    }

    private void readMode(Intent i) {
        String m = i == null ? null : i.getStringExtra(EXTRA_MODE);
        mode = MODE_WAKE.equals(m) && !AlarmService.running ? MODE_WAKE : MODE_ALARM;
        getSystemService(NotificationManager.class).cancel(Notifs.ID_WAKE);
    }

    private void buildUi() {
        int pad = Ui.dp(this, 20);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        col.setPadding(pad, Ui.dp(this, 40), pad, Ui.dp(this, 32));

        clock = Ui.text(this, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), 64,
                MODE_ALARM.equals(mode) ? Ui.RING : Ui.ACCENT);
        clock.setTypeface(Ui.SERIF);
        clock.setGravity(Gravity.CENTER);
        col.addView(clock);

        TextView lead = Ui.text(this, MODE_ALARM.equals(mode)
                ? "כדי לעצור את השעון, אמור בקול:"
                : "בוקר טוב. לפני הכול, אמור:", 17, Ui.FG);
        lead.setGravity(Gravity.CENTER);
        lead.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 16));
        col.addView(lead);

        prayer = Ui.text(this, PrayerMatcher.PRAYER, 30, Ui.MUTED);
        prayer.setTypeface(Ui.SERIF);
        prayer.setGravity(Gravity.CENTER);
        prayer.setLineSpacing(0, 1.45f);
        prayer.setBackground(Ui.box(this, Ui.SURFACE, Ui.LINE));
        prayer.setPadding(pad, pad, pad, pad);
        col.addView(prayer, new LinearLayout.LayoutParams(-1, -2));

        status = Ui.text(this, "", 15, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        col.addView(status);

        heard = Ui.text(this, "", 16, Ui.MUTED);
        heard.setGravity(Gravity.CENTER);
        heard.setMinHeight(Ui.dp(this, 48));
        col.addView(heard, new LinearLayout.LayoutParams(-1, -2));

        mic = Ui.button(this, "🎙  להקשבה, לחץ ואמור מודה אני", true);
        mic.setOnClickListener(v -> { errorsInRow = 0; listen(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 12);
        col.addView(mic, lp);

        typed = new EditText(this);
        typed.setHint("הקלד כאן את כל המשפט");
        typed.setTextColor(Ui.FG);
        typed.setHintTextColor(Ui.MUTED);
        typed.setTextSize(20);
        typed.setBackground(Ui.box(this, Ui.SURFACE, Ui.LINE));
        typed.setPadding(pad, pad, pad, pad);
        typed.setVisibility(View.GONE);
        typed.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) { check(e.toString()); }
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(this, 12);
        col.addView(typed, tp);

        doneText = Ui.text(this, "בוקר טוב ☀", 32, Ui.OK);
        doneText.setTypeface(Ui.SERIF);
        doneText.setGravity(Gravity.CENTER);
        doneText.setVisibility(View.GONE);
        col.addView(doneText);

        skip = Ui.button(this, "לא עכשיו", false);
        skip.setOnClickListener(v -> finishScreen(false));
        skip.setVisibility(MODE_WAKE.equals(mode) ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = Ui.dp(this, 20);
        col.addView(skip, sp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        scroll.addView(col);
        setContentView(scroll);

        boolean canSpeak = SpeechRecognizer.isRecognitionAvailable(this)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!canSpeak) useTyping(checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                ? "אין הרשאה למיקרופון. הקלד את המשפט במקום."
                : "זיהוי דיבור לא זמין בטלפון. הקלד את המשפט במקום.");
    }

    private void useTyping(String why) {
        status.setText(why);
        mic.setVisibility(View.GONE);
        typed.setVisibility(View.VISIBLE);
    }

    // ---------- Speech ----------

    private void listen() {
        if (done || !resumed || typed.getVisibility() == View.VISIBLE) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        try {
            recognizer.startListening(i);
            setListening(true);
        } catch (Exception e) {
            setListening(false);
        }
    }

    private void setListening(boolean on) {
        listening = on;
        AlarmService.duck(on);
        mic.setText(on ? "⏺  מקשיב… אמור מודה אני" : "🎙  להקשבה, לחץ ואמור מודה אני");
        mic.setBackground(Ui.box(this, on ? Ui.RING : Ui.ACCENT, 0));
    }

    private void again(long delay) {
        handler.postDelayed(this::listen, delay);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        public void onReadyForSpeech(Bundle p) { status.setText("מקשיב…"); }
        public void onBeginningOfSpeech() { }
        public void onRmsChanged(float v) { }
        public void onBufferReceived(byte[] b) { }
        public void onEndOfSpeech() { }
        public void onEvent(int t, Bundle p) { }

        public void onPartialResults(Bundle r) {
            ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l == null || l.isEmpty()) return;
            heard.setText((settled + " " + l.get(0)).trim());
            for (String s : l) check(s);
        }

        public void onResults(Bundle r) {
            setListening(false);
            errorsInRow = 0;
            ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l != null && !l.isEmpty()) {
                settled = (settled + " " + l.get(0)).trim();
                heard.setText(settled);
                for (String s : l) check(s);
            }
            if (!done) { status.setText("עוד לא שמעתי הכול. המשך לומר."); again(300); }
        }

        public void onError(int error) {
            setListening(false);
            if (done) return;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                useTyping("אין הרשאה למיקרופון. הקלד את המשפט במקום.");
                return;
            }
            if (Build.VERSION.SDK_INT >= 31 && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                    || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                useTyping("זיהוי דיבור בעברית לא זמין בטלפון. הקלד את המשפט במקום.");
                return;
            }
            errorsInRow++;
            if (errorsInRow > 6) {
                status.setText("לא הצלחתי לשמוע. לחץ על המיקרופון ונסה שוב.");
                return;
            }
            boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (silence) errorsInRow = Math.min(errorsInRow, 3); // silence is normal: keep listening
            status.setText(silence ? "לא שמעתי. אמור מודה אני בקול." : "מנסה להקשיב שוב…");
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                if (recognizer != null) { recognizer.destroy(); recognizer = null; }
                again(1200);
            } else {
                again(500);
            }
        }
    };

    // ---------- Matching ----------

    private void check(String text) {
        if (done) return;
        PrayerMatcher.mark(text, hits);
        paint();
        if (PrayerMatcher.complete(hits)) finishScreen(true);
    }

    private void paint() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < PrayerMatcher.WORDS.length; i++) {
            if (i > 0) sb.append(' ');
            int s = sb.length();
            sb.append(PrayerMatcher.WORDS[i]);
            sb.setSpan(new ForegroundColorSpan(done || hits[i] ? Ui.OK : Ui.MUTED), s, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        prayer.setText(sb);
    }

    private void finishScreen(boolean said) {
        if (done) return;
        done = true;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        AlarmService.duck(false);
        if (said) {
            Prefs.setLastSaid(this, System.currentTimeMillis());
            AlarmService.stop(this);
            paint();
            mic.setVisibility(View.GONE);
            typed.setVisibility(View.GONE);
            skip.setVisibility(View.GONE);
            status.setText("");
            doneText.setVisibility(View.VISIBLE);
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km != null) km.requestDismissKeyguard(this, null);
            handler.postDelayed(this::finish, 2500);
        } else {
            finish();
        }
    }

    // ---------- Lifecycle ----------

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (!done) again(600); // start listening by itself
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) { recognizer.cancel(); }
        if (listening) setListening(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (MODE_WAKE.equals(mode) || done) super.onBackPressed();
        // In alarm mode, Back does nothing: the alarm stops only after the prayer.
    }
}
