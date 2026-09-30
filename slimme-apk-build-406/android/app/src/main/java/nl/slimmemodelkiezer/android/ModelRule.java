package nl.slimmemodelkiezer.android;

import java.util.Locale;

/**
 * Fast, offline decision tree. One lowercase conversion, fixed keyword sets,
 * no network calls, no LLM inference and no message persistence.
 * A model's actual availability is checked by the accessibility service.
 */
public final class ModelRule {
    private ModelRule() {}
    public enum Level { INSTANT, THINKING, HIGH }

    private static final String[] HIGH = {
        "bouw een app", "ontwerp een app", "bouw de apk", "complexe architectuur",
        "uitgebreid juridisch", "juridische analyse", "grondige analyse",
        "beveiligingsaudit", "security audit", "meerdere scenario's",
        "bewijs stap voor stap", "diepgaand onderzoek"
    };
    private static final String[] THINKING = {
        "analyseer", "analyse", "vergelijk", "programmeer", "debug",
        "juridisch", "jurisprudentie", "architectuur", "contract",
        "wiskund", "strategie", "bereken", "onderbouw", "refactor",
        "beveiliging", "security", "onderzoek", "stappenplan", "schrijf code"
    };
    private static final String[] SIMPLE = {
        "hoe laat", "vertaal dit", "wat betekent", "geef kort antwoord",
        "kort antwoord", "spelfout", "herschrijf deze zin"
    };

    public static Level choose(String input) {
        if (input == null) return Level.INSTANT;
        String s = input.trim().toLowerCase(Locale.ROOT);
        int length = s.length();
        if (length == 0) return Level.INSTANT;
        // Remote keywords augment local defaults; no prompt is sent to a server.
        RemoteRules.Rules remote = RemoteRules.current();
        // The order and matching thresholds come from GitHub JSON; fallback is local.
        for (String category : remote.choicePriority) {
            if ("high".equals(category) && RemoteRules.matchesAny(s, remote.high)) return Level.HIGH;
            if ("simple".equals(category) && length < remote.simpleMaxLength &&
                RemoteRules.matchesAny(s, remote.simple)) return Level.INSTANT;
            if ("thinking".equals(category) && RemoteRules.matchesAny(s, remote.thinking))
                return Level.THINKING;
        }

        // Route obviously trivial prompts without scanning all keyword groups.
        if (length < remote.simpleMaxLength && (isSimpleArithmetic(s) || containsAny(s, SIMPLE))) {
            return Level.INSTANT;
        }
        // Explicit demanding tasks take precedence over mere prompt length.
        if (containsAny(s, HIGH)) return Level.HIGH;

        int signals = 0;
        for (String word : THINKING) {
            if (s.contains(word)) {
                signals++;
                if (signals >= remote.highSignalCount) break;
            }
        }
        // Long, multi-part questions need deeper reasoning only with supporting signals.
        boolean multiStep = s.contains("stap voor stap") || s.contains("met bronnen") ||
            s.contains("voor- en nadelen") || s.contains("verschillende opties");
        if (signals >= remote.highSignalCount &&
            (length > remote.highMinLength || multiStep)) return Level.HIGH;
        if (signals > 0 || (length > 900 && multiStep)) return Level.THINKING;
        return Level.INSTANT;
    }

    private static boolean containsAny(String s, String[] words) {
        for (String word : words) if (s.contains(word)) return true;
        return false;
    }

    private static boolean isSimpleArithmetic(String s) {
        // Cheap scan instead of regex: numbers, spaces, an operator and optional '?' only.
        if (s.length() > 32) return false;
        int digits = 0;
        boolean op = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') digits++;
            else if (c == '+' || c == '-' || c == '*' || c == '/' || c == '×') op = true;
            else if (!Character.isWhitespace(c) && c != '?' && c != '=' && c != '.') return false;
        }
        return digits >= 2 && op;
    }
}