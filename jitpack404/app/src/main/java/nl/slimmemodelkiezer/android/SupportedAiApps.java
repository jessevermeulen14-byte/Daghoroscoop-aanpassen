package nl.slimmemodelkiezer.android;

import android.content.Context;
import android.content.SharedPreferences;

/** Only these three explicit, user-selected app packages are ever inspected. */
final class SupportedAiApps {
    static final String CHATGPT = "com.openai.chatgpt";
    static final String CLAUDE = "com.anthropic.claude";
    static final String GEMINI = "com.google.android.apps.bard";
    private static final String PREFS = "supported_ai_apps";

    private SupportedAiApps() {}

    static boolean supported(String pkg) {
        return CHATGPT.equals(pkg) || CLAUDE.equals(pkg) || GEMINI.equals(pkg);
    }

    static boolean enabled(Context context, String pkg) {
        if (!supported(pkg)) return false;
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Existing users retain ChatGPT; third-party apps are strictly opt-in.
        return p.getBoolean(pkg, CHATGPT.equals(pkg));
    }

    static void setEnabled(Context context, String pkg, boolean enabled) {
        if (!supported(pkg)) throw new IllegalArgumentException("Unknown AI app");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(pkg, enabled).apply();
    }

    static String displayName(String pkg) {
        if (CLAUDE.equals(pkg)) return "Claude";
        if (GEMINI.equals(pkg)) return "Gemini";
        return CHATGPT.equals(pkg) ? "ChatGPT" : "onbekend";
    }
}
