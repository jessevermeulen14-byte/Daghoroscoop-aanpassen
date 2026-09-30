package nl.slimmemodelkiezer.android;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Data-only remote rules, not downloaded executable code.
 * Public JSON is fetched over HTTPS. Prompt contents never leave the device.
 * Missing, invalid, or offline configurations keep the local default behavior.
 */
final class RemoteRules {
    private static final String PREFS = "remote_rules";
    // Public data-only configuration; no prompt content or credentials are transmitted.
    private static final String DEFAULT_URL =
        "https://raw.githubusercontent.com/jessevermeulen14-byte/Daghoroscoop-aanpassen/main/slimme-modelkiezer/personal-config.json";
    private static final long REFRESH_MS = 60 * 1000L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static volatile Rules active = Rules.defaults();
    private static volatile long lastCheck = 0L;
    private static volatile boolean fetching = false;

    private RemoteRules() {}

    static final class Rules {
        final String[] high, thinking, simple, sendLabels, sendIds, pickerLabels, pickerIds;
        final int pickerRetries, pickerRetryDelayMs, sendRetries, retryDelayMs, buttonXdp, buttonYdp;
        final int buttonSizeDp, selectionTimeoutMs, cooldownMs;
        final String telemetryEndpoint, telemetryToken;
        final String buttonLabel;
        final boolean tapFeedback, interceptNativeSend;
        final boolean skipMissingPicker;
        final int version;
        final int simpleMaxLength, highMinLength, highSignalCount;
        final String[] choicePriority;
        final Map<String, AppRules> appRules;

        static final class AppRules {
            final String[] sendLabels, sendIds, pickerLabels, pickerIds;
            final String[] instantModels, thinkingModels, highModels;
            final boolean modelPickerEnabled;

            AppRules(String[] sendLabels, String[] sendIds, String[] pickerLabels, String[] pickerIds,
                     String[] instantModels, String[] thinkingModels, String[] highModels,
                     boolean modelPickerEnabled) {
                this.sendLabels = sendLabels; this.sendIds = sendIds;
                this.pickerLabels = pickerLabels; this.pickerIds = pickerIds;
                this.instantModels = instantModels; this.thinkingModels = thinkingModels;
                this.highModels = highModels;
                this.modelPickerEnabled = modelPickerEnabled;
            }

            String[] models(ModelRule.Level level) {
                if (level == ModelRule.Level.HIGH) return highModels;
                if (level == ModelRule.Level.THINKING) return thinkingModels;
                return instantModels;
            }
        }

        AppRules forPackage(String pkg) {
            AppRules rules = appRules.get(pkg);
            return rules == null ? new AppRules(new String[0], new String[0],
                new String[0], new String[0], new String[0], new String[0],
                new String[0], false) : rules;
        }

        private Rules(int version, String[] high, String[] thinking, String[] simple,
                      String[] sendLabels, String[] sendIds, String[] pickerLabels, String[] pickerIds,
                      int pickerRetries, int pickerRetryDelayMs, int sendRetries, int retryDelayMs,
                      int buttonXdp, int buttonYdp, boolean skipMissingPicker,
                      int buttonSizeDp, int selectionTimeoutMs, int cooldownMs,
                      String buttonLabel, boolean tapFeedback, boolean interceptNativeSend,
                      String telemetryEndpoint, String telemetryToken,
                      int simpleMaxLength, int highMinLength, int highSignalCount,
                      String[] choicePriority, Map<String, AppRules> appRules) {
            this.version = version;
            this.high = high; this.thinking = thinking; this.simple = simple;
            this.sendLabels = sendLabels; this.sendIds = sendIds;
            this.pickerLabels = pickerLabels; this.pickerIds = pickerIds;
            this.pickerRetries = pickerRetries; this.pickerRetryDelayMs = pickerRetryDelayMs;
            this.buttonXdp = buttonXdp; this.buttonYdp = buttonYdp;
            this.buttonSizeDp = buttonSizeDp;
            this.selectionTimeoutMs = selectionTimeoutMs;
            this.cooldownMs = cooldownMs;
            this.buttonLabel = buttonLabel;
            this.tapFeedback = tapFeedback;
            this.interceptNativeSend = interceptNativeSend;
            this.telemetryEndpoint = telemetryEndpoint == null ? "" : telemetryEndpoint;
            this.telemetryToken = telemetryToken == null ? "" : telemetryToken;
            this.sendRetries = sendRetries;
            this.retryDelayMs = retryDelayMs; this.skipMissingPicker = skipMissingPicker;
            this.simpleMaxLength = simpleMaxLength;
            this.highMinLength = highMinLength;
            this.highSignalCount = highSignalCount;
            this.choicePriority = choicePriority;
            this.appRules = Collections.unmodifiableMap(new HashMap<>(appRules));
        }

        static Rules defaults() {
            return new Rules(0, new String[0], new String[0], new String[0],
                new String[0], new String[0], new String[0], new String[0],
                4, 160, 8, 150, 12, 112, true,
                56, 8500, 450, "SLIM", true, true,
                "https://eu.i.posthog.com/i/v0/e/", "phc_wovAoSyfzdYqJtMQrmE3U7aKgRFe3gxMCDKS8DXhFftC",
                65, 450, 2, new String[]{"high", "simple", "thinking"},
                Collections.emptyMap());
        }

        static Rules parse(String raw) throws Exception {
            if (raw == null || raw.length() > 32768) throw new IllegalArgumentException("config size");
            JSONObject o = new JSONObject(raw);
            if (o.getInt("schema") != 1) throw new IllegalArgumentException("unsupported schema");
            int version = o.getInt("version");
            if (version < 1) throw new IllegalArgumentException("version");
            JSONObject choice = o.optJSONObject("modelChoice");
            JSONObject send = o.optJSONObject("send");
            JSONObject picker = o.optJSONObject("picker");
            return new Rules(version,
                strings(choice, "highKeywords"), strings(choice, "thinkingKeywords"),
                strings(choice, "simpleKeywords"),
                strings(send, "labels"), strings(send, "resourceIds"),
                strings(picker, "labels"), strings(picker, "resourceIds"),
                bounded(picker, "retries", 4, 1, 25),
                bounded(picker, "retryDelayMs", 50, 50, 1000),
                bounded(send, "retries", 8, 1, 20),
                bounded(send, "retryDelayMs", 50, 50, 1000),
                bounded(o.optJSONObject("button"), "xDp", 12, 0, 240),
                bounded(o.optJSONObject("button"), "yDp", 310, 48, 600),
                picker == null || picker.optBoolean("skipWhenMissing", true),
                bounded(o.optJSONObject("button"), "sizeDp", 56, 44, 72),
                bounded(o.optJSONObject("button"), "selectionTimeoutMs", 8500, 3000, 15000),
                bounded(o.optJSONObject("button"), "cooldownMs", 450, 0, 2000),
                buttonLabel(o.optJSONObject("button")),
                o.optJSONObject("button") == null || o.optJSONObject("button").optBoolean("tapFeedback", true),
                o.optJSONObject("button") == null || o.optJSONObject("button").optBoolean("interceptNativeSend", true),
                telemetryEndpoint(o.optJSONObject("telemetry")),
                telemetryToken(o.optJSONObject("telemetry")),
                bounded(choice, "simpleMaxLength", 65, 1, 5000),
                bounded(choice, "highMinLength", 450, 1, 10000),
                bounded(choice, "highSignalCount", 2, 1, 10),
                priority(choice), parseApps(o.optJSONObject("apps")));
        }


        private static String telemetryEndpoint(JSONObject telemetry) {
            if (telemetry == null) return "";
            String endpoint = telemetry.optString("endpoint", "").trim();
            if (endpoint.isEmpty()) return "";
            try {
                URL u = new URL(endpoint);
                String host = u.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equals(u.getProtocol()) || u.getUserInfo() != null ||
                        (u.getPort() != -1 && u.getPort() != 443) ||
                        !host.equals("eu.i.posthog.com") ||
                        !"/i/v0/e/".equals(u.getPath()) || u.getQuery() != null)
                    throw new IllegalArgumentException("telemetry endpoint");
                return endpoint;
            } catch (Exception e) {
                throw new IllegalArgumentException("telemetry endpoint");
            }
        }

        private static String telemetryToken(JSONObject telemetry) {
            if (telemetry == null) return "";
            String token = telemetry.optString("projectToken", "").trim();
            if (!token.isEmpty() && !token.matches("phc_[A-Za-z0-9]{20,}"))
                throw new IllegalArgumentException("telemetry project token");
            return token;
        }

        private static String buttonLabel(JSONObject button) {
            String label = button == null ? "SLIM" : button.optString("label", "SLIM").trim();
            if (!label.matches("[A-Za-z0-9 ]{2,12}")) throw new IllegalArgumentException("button label");
            return label;
        }

        private static Map<String, AppRules> parseApps(JSONObject apps) {
            Map<String, AppRules> result = new HashMap<>();
            if (apps == null) return result;
            for (String pkg : new String[]{SupportedAiApps.CHATGPT,
                    SupportedAiApps.CLAUDE, SupportedAiApps.GEMINI}) {
                String key = SupportedAiApps.CHATGPT.equals(pkg) ? "chatgpt" :
                    SupportedAiApps.CLAUDE.equals(pkg) ? "claude" : "gemini";
                JSONObject app = apps.optJSONObject(key);
                if (app == null) continue;
                JSONObject send = app.optJSONObject("send");
                JSONObject picker = app.optJSONObject("picker");
                JSONObject modes = app.optJSONObject("modelModes");
                String[] instant = strings(modes, "instant");
                String[] thinking = strings(modes, "thinking");
                String[] high = strings(modes, "high");
                boolean enableModels = app.optBoolean("modelPickerEnabled", false);
                if (enableModels && (instant.length == 0 || thinking.length == 0 ||
                        high.length == 0 ||
                        (strings(picker, "labels").length == 0 &&
                         strings(picker, "resourceIds").length == 0)))
                    throw new IllegalArgumentException("Incomplete provider model menu: " + key);
                result.put(pkg, new AppRules(
                    strings(send, "labels"), strings(send, "resourceIds"),
                    strings(picker, "labels"), strings(picker, "resourceIds"),
                    instant, thinking, high, enableModels));
            }
            return result;
        }

        private static String[] priority(JSONObject choice) {
            String[] defaults = new String[]{"high", "simple", "thinking"};
            if (choice == null || !choice.has("priority")) return defaults;
            JSONArray array = choice.optJSONArray("priority");
            if (array == null || array.length() != 3) throw new IllegalArgumentException("priority");
            String[] result = new String[3];
            for (int i = 0; i < 3; i++) {
                String s = array.optString(i, "").toLowerCase(Locale.ROOT);
                if (!("high".equals(s) || "simple".equals(s) || "thinking".equals(s)))
                    throw new IllegalArgumentException("priority");
                for (int j = 0; j < i; j++)
                    if (result[j].equals(s)) throw new IllegalArgumentException("priority duplicate");
                result[i] = s;
            }
            return result;
        }

        private static int bounded(JSONObject o, String name, int fallback, int min, int max) {
            int n = o == null ? fallback : o.optInt(name, fallback);
            if (n < min || n > max) throw new IllegalArgumentException(name);
            return n;
        }

        private static String[] strings(JSONObject o, String name) {
            if (o == null || !o.has(name)) return new String[0];
            JSONArray a = o.optJSONArray(name);
            if (a == null || a.length() > 50) throw new IllegalArgumentException(name);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "").trim().toLowerCase(Locale.ROOT);
                if (s.length() < 2 || s.length() > 80) throw new IllegalArgumentException(name);
                result.add(s);
            }
            return result.toArray(new String[0]);
        }
    }

    static Rules current() { return active; }

    static boolean matchesAny(String candidate, String[] aliases) {
        if (candidate == null || candidate.isEmpty()) return false;
        String lowered = candidate.toLowerCase(Locale.ROOT);
        for (String alias : aliases) if (lowered.contains(alias)) return true;
        return false;
    }

    /** Exact UI labels and full/suffix IDs, unlike substring keyword matching. */
    static boolean matchesUiLabel(String candidate, String[] aliases) {
        if (candidate == null || candidate.isEmpty()) return false;
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        for (String alias : aliases) if (normalized.equals(alias)) return true;
        return false;
    }

    static boolean matchesResourceId(String candidate, String[] aliases) {
        if (candidate == null || candidate.isEmpty()) return false;
        String id = candidate.trim().toLowerCase(Locale.ROOT);
        for (String alias : aliases) {
            String name = alias.trim().toLowerCase(Locale.ROOT);
            if (id.equals(name) || id.endsWith("/" + name)) return true;
        }
        return false;
    }

    static synchronized boolean setUrl(Context context, String text) {
        String url = text == null ? "" : text.trim();
        if (!url.isEmpty() && !validUrl(url)) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", url).putBoolean("explicitChoice", true)
            .remove("cache").remove("last_fetch").apply();
        active = Rules.defaults();
        lastCheck = 0L;
        return true;
    }

    static String getUrl(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = p.getString("url", DEFAULT_URL);
        // Migrate older installs that stored a blank URL before the free default existed.
        return (stored == null || stored.isEmpty()) && !p.getBoolean("explicitChoice", false)
            ? DEFAULT_URL : stored;
    }

    static String status(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (getUrl(context).isEmpty()) return "Niet gekoppeld: lokale regels actief.";
        String state = p.getString("status", "Nog niet gecontroleerd");
        return state + " | geladen versie: " + active.version;
    }

    static void initialize(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!getUrl(context).isEmpty()) {
            try { active = Rules.parse(p.getString("cache", "")); }
            catch (Exception ignored) { active = Rules.defaults(); }
            refresh(context, false);
        }
    }

    static void refresh(Context context, boolean force) {
        final Context app = context.getApplicationContext();
        final String configured = getUrl(app);
        if (configured.isEmpty() || !validUrl(configured)) return;
        synchronized (RemoteRules.class) {
            if (fetching || (!force && System.currentTimeMillis() - lastCheck < REFRESH_MS)) return;
            fetching = true;
            lastCheck = System.currentTimeMillis();
        }
        WORKER.execute(() -> {
            HttpURLConnection conn = null;
            String status;
            try {
                conn = (HttpURLConnection) new URL(configured).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept", "application/json");
                if (conn.getResponseCode() != 200) throw new IllegalStateException("HTTP " + conn.getResponseCode());
                if (conn.getContentLength() > 32768) throw new IllegalStateException("too large");
                StringBuilder body = new StringBuilder();
                try (InputStream stream = conn.getInputStream();
                     BufferedReader in = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        body.append(line).append('\n');
                        if (body.length() > 32768) throw new IllegalStateException("too large");
                    }
                }
                Rules newRules = Rules.parse(body.toString());
                // Only commit a response from the URL still selected in settings.
                if (configured.equals(getUrl(app)) && newRules.version >= active.version) {
                    active = newRules;
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("cache", body.toString())
                        .putLong("last_fetch", System.currentTimeMillis())
                        .apply();
                    status = "Online regels geladen";
                    HistoryStore.diagnostic(app, "CONFIG_FETCH_SUCCESS");
                } else {
                    status = "Verouderde of gewijzigde configuratie genegeerd";
                    HistoryStore.diagnostic(app, "CONFIG_FETCH_IGNORED");
                }
            } catch (Exception e) {
                status = "Offline of ongeldige configuratie; vorige regels blijven actief (" +
                    e.getClass().getSimpleName() + ")";
                HistoryStore.diagnostic(app, "CONFIG_FETCH_FAILED");
                HistoryStore.diagnostic(app, "REMOTE_CONFIG_FALLBACK_USED");
            } finally {
                if (conn != null) conn.disconnect();
                synchronized (RemoteRules.class) { fetching = false; }
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("status", status).apply();
        });
    }

    private static boolean validUrl(String input) {
        try {
            URL u = new URL(input);
            String host = u.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(u.getProtocol()) && u.getUserInfo() == null
                && (u.getPort() == -1 || u.getPort() == 443)
                && ("raw.githubusercontent.com".equals(host)
                    || "gist.githubusercontent.com".equals(host));
        } catch (Exception e) { return false; }
    }
}
