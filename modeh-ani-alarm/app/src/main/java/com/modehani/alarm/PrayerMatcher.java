package com.modehani.alarm;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks which words of Modeh Ani appear in recognized speech.
 * Speech-to-text spells Hebrew inconsistently, so words are normalized
 * (no niqqud, no final letters, no inner vav/yud) and compared with a small edit distance.
 */
final class PrayerMatcher {
    static final String PRAYER = "מודה אני לפניך מלך חי וקיים שהחזרת בי נשמתי בחמלה רבה אמונתך";
    static final String[] WORDS = PRAYER.split(" ");
    private static final String[] TARGET = new String[WORDS.length];

    static {
        for (int i = 0; i < WORDS.length; i++) TARGET[i] = norm(WORDS[i]).get(0);
    }

    private PrayerMatcher() {}

    static List<String> norm(String s) {
        String t = s.replaceAll("[\\u0591-\\u05C7]", "")
                .replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ').replace('ף', 'פ').replace('ץ', 'צ')
                .replaceAll("[^\\u05D0-\\u05EA\\s]", " ")
                .replaceAll("(?<=\\S)[וי](?=\\S)", "")
                .trim();
        List<String> out = new ArrayList<>();
        if (t.isEmpty()) return out;
        for (String w : t.split("\\s+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    private static int lev(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++)
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
        return d[a.length()][b.length()];
    }

    /** Marks into {@code hits} every prayer word heard in {@code text}. Earlier hits stay. */
    static void mark(String text, boolean[] hits) {
        List<String> heard = norm(text);
        List<String> pool = new ArrayList<>(heard);
        for (int i = 0; i + 1 < heard.size(); i++) pool.add(heard.get(i) + heard.get(i + 1));
        for (int i = 0; i < TARGET.length; i++) {
            if (hits[i]) continue;
            String t = TARGET[i];
            for (String h : pool) {
                int d = (h.equals(t)) ? 0 : lev(h, t);
                if (d == 0 || (t.length() >= 3 && d <= 1) || (t.length() >= 6 && d <= 2)) { hits[i] = true; break; }
            }
        }
    }

    /** Done when every word but at most one was heard, and the opening "מודה אני" was heard. */
    static boolean complete(boolean[] hits) {
        int n = 0;
        for (boolean h : hits) if (h) n++;
        return hits[0] && hits[1] && n >= WORDS.length - 1;
    }
}
