package nl.slimmemodelkiezer.android;

import java.util.Locale;

/** Exact control names only: conversation sentences cannot be menu controls. */
final class MenuEntryPolicy {
    static boolean configure(String value) {
        String s = normalize(value);
        return s.equals("configureren") || s.equals("configure") ||
            s.equals("model configureren") || s.equals("configure model");
    }

    static boolean overflow(String value) {
        String s = normalize(value);
        return s.equals("meer opties") || s.equals("more options") ||
            s.equals("meer") || s.equals("more") ||
            s.equals("gespreksopties") || s.equals("conversation options") ||
            s.equals("overloopmenu") || s.equals("overflow menu");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
