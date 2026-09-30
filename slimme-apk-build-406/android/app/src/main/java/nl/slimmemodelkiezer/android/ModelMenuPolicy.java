package nl.slimmemodelkiezer.android;

import java.util.Locale;
import java.util.regex.Pattern;

/** Match only an explicit current mode label, not a container listing every mode. */
final class ModelMenuPolicy {
    private ModelMenuPolicy() {}
    private static boolean token(String s, String word) {
        return Pattern.compile("(?<![\\p{L}])" + Pattern.quote(word) + "(?![\\p{L}])")
            .matcher(s).find();
    }
    static boolean matches(ModelRule.Level target, String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        String s = raw.toLowerCase(Locale.ROOT);
        boolean instant = token(s, "instant") || token(s, "instantaan") ||
            s.matches("(?s).*(?<![\\p{L}\\d.])6\\s+luna(?![\\p{L}]).*");
        boolean medium = token(s, "medium") || token(s, "gemiddeld") || token(s, "standard") ||
            s.matches("(?s).*(?<![\\p{L}\\d.])6\\s+sol(?![\\p{L}]).*") ||
            (token(s, "thinking") && token(s, "standard"));
        boolean high = (token(s, "high") && !token(s, "extra")) || token(s, "extended") ||
            s.matches("(?s).*(?<![\\p{L}\\d.])6\\s+astra(?![\\p{L}]).*") ||
            (token(s, "hoog") && !token(s, "zeer")) ||
            (token(s, "thinking") && token(s, "extended"));
        boolean extraHigh = token(s, "extra high") || token(s, "zeer hoog") ||
            (token(s, "thinking") && token(s, "heavy"));
        // A dialog container can expose all its child labels in one string.
        // Never click or "verify" such a label as an active mode.
        int activeModes = (instant ? 1 : 0) + (medium ? 1 : 0) +
            (high ? 1 : 0) + (extraHigh ? 1 : 0);
        // "Instant Thinking" is a container listing choices, not an active Instant mode.
        if (instant && token(s, "thinking")) return false;
        if (extraHigh || activeModes != 1) return false;
        if (target == ModelRule.Level.INSTANT) return instant;
        if (target == ModelRule.Level.THINKING) return medium;
        return high;
    }
}
