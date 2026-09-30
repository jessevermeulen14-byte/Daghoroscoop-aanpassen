package nl.slimmemodelkiezer.android;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends content-free technical/model-selection events to the optional remote log endpoint.
 * Prompt text, account data and raw ChatGPT UI text are never transmitted.
 */
final class TelemetryClient {
    private TelemetryClient() {}
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final String PREFS = "telemetry_identity";

    static String status(Context context) {
        String endpoint = RemoteRules.current().telemetryEndpoint;
        if (endpoint == null || endpoint.isEmpty()) return "Externe logging: nog niet gekoppeld.";
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!endpoint.equals(p.getString("last_endpoint", "")))
            return "Externe logging via PostHog: nog geen upload bevestigd.";
        long at = p.getLong("last_success_at", 0L);
        String result = p.getString("last_result", "Nog geen upload uitgevoerd");
        return "Externe logging via PostHog: " + result + (at > 0 ? " (laatste succes: " +
            java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(at)) + ")" : "");
    }

    static void send(Context context, JSONObject source) {
        String endpoint = RemoteRules.current().telemetryEndpoint;
        String token = RemoteRules.current().telemetryToken;
        if (!"https://eu.i.posthog.com/i/v0/e/".equals(endpoint) ||
                token == null || token.isEmpty() || source == null) return;
        String outcome = source.optString("outcome", "");
        boolean modelAttempt = !"DIAGNOSTIC".equals(source.optString("requested", ""));
        boolean usefulDiagnostic = outcome.startsWith("ROUTED_") ||
            outcome.startsWith("SLIM_TAP_") || outcome.startsWith("SEND_") ||
            outcome.startsWith("MODEL_") || outcome.startsWith("SELECTION_") ||
            outcome.startsWith("SCREEN_") ||
            outcome.equals("PICKER_TREE") || outcome.equals("PICKER_DIAG") ||
            outcome.equals("WINDOWS") || outcome.equals("MENU_TREE") ||
            outcome.equals("MENU_WINDOWS") || outcome.equals("MENU_MODELS") ||
            outcome.equals("SLIM_BUTTON_TAP_RECEIVED") ||
            outcome.equals("REMOTE_LOG_TEST_REQUESTED") ||
            outcome.equals("EMPTY_DRAFT") ||
            outcome.equals("SLIM_TAP_EDITOR_NOT_ACCESSIBLE") ||
            outcome.equals("SLIM_FLOATING_BUTTON_VISIBLE") ||
            outcome.equals("MODEL_PICKER_DISABLED_SEND_CURRENT_MODEL") ||
            outcome.startsWith("CONFIG_FETCH_") ||
            outcome.equals("REMOTE_CONFIG_FALLBACK_USED");
        if (!modelAttempt && !usefulDiagnostic) return;
        final Context app = context.getApplicationContext();
        final String body;
        try {
            JSONObject event = new JSONObject();
            event.put("installId", installId(app));
            event.put("timestamp", source.optLong("timestamp"));
            event.put("requested", source.optString("requested"));
            event.put("observed", source.optString("observed"));
            event.put("outcome", source.optString("outcome"));
            event.put("durationMs", source.optLong("durationMs"));
            event.put("appVersionName", appVersionName(app));
            event.put("appVersionCode", appVersionCode(app));
            event.put("configVersion", RemoteRules.current().version);
            // Only these structured diagnostics contain counts, window types,
            // resource-id suffixes or recognized model names, never draft text.
            boolean safeStructure = outcome.equals("PICKER_TREE") ||
                outcome.equals("PICKER_DIAG") || outcome.equals("WINDOWS") ||
                outcome.equals("MENU_TREE") || outcome.equals("MENU_WINDOWS") ||
                outcome.equals("MENU_MODELS") ||
                outcome.equals("MODEL_OPTION_ACTION_DISPATCHED") ||
                outcome.equals("SCREEN_MODEL_OPTION_OBSERVED") ||
                outcome.equals("SCREEN_MENU_DIAG");
            event.put("available", safeStructure ? source.optString("available", "") : "");
            event.put("chosenOption", source.optString("chosenOption"));
            JSONObject capture = new JSONObject();
            capture.put("api_key", token);
            capture.put("distinct_id", installId(app));
            capture.put("event", "slim_model_technical_event");
            event.put("$process_person_profile", false);
            capture.put("properties", event);
            body = capture.toString();
        } catch (Exception ignored) {
            return;
        }
        WORKER.execute(() -> post(app, endpoint, body));
    }

    private static String appVersionName(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long appVersionCode(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
            return info.versionCode;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String installId(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString("install_id", "");
        if (!id.isEmpty()) return id;
        id = UUID.randomUUID().toString();
        p.edit().putString("install_id", id).commit();
        return id;
    }

    private static void post(Context context, String endpoint, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("last_endpoint", endpoint)
                    .putLong("last_success_at", System.currentTimeMillis())
                    .putString("last_result", "upload gelukt").apply();
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("last_endpoint", endpoint)
                    .putString("last_result", "server gaf HTTP " + code).apply();
                android.util.Log.w("SlimTelemetry", "Remote log rejected: HTTP " + code);
            }
        } catch (Exception e) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("last_endpoint", endpoint)
                .putString("last_result", "verbinding mislukt").apply();
            android.util.Log.w("SlimTelemetry", "Remote log unavailable: " + e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
