package nl.slimmemodelkiezer.android;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

/** Local, capped, content-free audit trail. No question text, account data or network. */
public final class HistoryStore {
    private HistoryStore() {}
    private static final String PREFS = "model_selection_history";
    private static final String EVENTS = "events";
    private static final int MAX_EVENTS = 100;
    private static final Object LOCK = new Object();

    public static void add(Context ctx, ModelRule.Level requested, String observed,
                           String outcome, long durationMs, String available, String chosenOption) {
        synchronized (LOCK) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray previous = read(p);
            JSONArray next = new JSONArray();
            JSONObject event = new JSONObject();
            try {
                event.put("timestamp", System.currentTimeMillis());
                event.put("requested", requested.name());
                event.put("observed", trim(observed, 90));
                event.put("outcome", trim(outcome, 50));
                event.put("durationMs", Math.max(0, durationMs));
                event.put("available", trim(available, 300));
                event.put("chosenOption", trim(chosenOption, 90));
                event.put("feedback", "");
                next.put(event);
                for (int i = 0; i < previous.length() && next.length() < MAX_EVENTS; i++)
                    next.put(previous.optJSONObject(i));
                if (!p.edit().putString(EVENTS, next.toString()).commit())
                    android.util.Log.e("ModelkiezerHistory", "Event persistence failed");
                else TelemetryClient.send(ctx, event);
            } catch (Exception ignored) { /* Logging never blocks sending. */ }
        }
    }

    /** Diagnostic entry contains no prompt text and never counts as a model selection. */
    public static boolean diagnostic(Context ctx, String outcome) {
        return diagnosticDetail(ctx, outcome, "");
    }

    /** Structured, content-free Android view diagnostics; max 300 characters. */
    public static boolean diagnosticDetail(Context ctx, String outcome, String detail) {
        synchronized (LOCK) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray previous = read(p);
            JSONArray next = new JSONArray();
            try {
                JSONObject event = new JSONObject();
                event.put("timestamp", System.currentTimeMillis());
                event.put("requested", "DIAGNOSTIC");
                event.put("observed", "");
                event.put("outcome", trim(outcome, 50));
                event.put("durationMs", 0);
                event.put("available", trim(detail, 300));
                event.put("chosenOption", "");
                event.put("feedback", "");
                next.put(event);
                for (int i = 0; i < previous.length() && next.length() < MAX_EVENTS; i++)
                    next.put(previous.optJSONObject(i));
                boolean saved = p.edit().putString(EVENTS, next.toString()).commit();
                if (saved) TelemetryClient.send(ctx, event);
                return saved;
            } catch (Exception e) {
                android.util.Log.e("ModelkiezerHistory", "Diagnostic logging failed", e);
                return false;
            }
        }
    }

    /** Keep the exception type and application stack frames; never save message text or user prompts. */
    public static boolean diagnosticFailure(Context ctx, String code, Throwable error) {
        synchronized (LOCK) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray previous = read(p);
            JSONArray next = new JSONArray();
            try {
                JSONObject event = new JSONObject();
                event.put("timestamp", System.currentTimeMillis());
                event.put("requested", "DIAGNOSTIC");
                event.put("observed", error == null ? "Unknown" : trim(error.getClass().getName(), 90));
                event.put("outcome", trim(code, 50));
                event.put("durationMs", 0);
                StringBuilder frames = new StringBuilder();
                if (error != null) for (StackTraceElement frame : error.getStackTrace()) {
                    if (!frame.getClassName().startsWith("nl.slimmemodelkiezer.")) continue;
                    if (frames.length() > 0) frames.append(" | ");
                    String entry = frame.getClassName().substring("nl.slimmemodelkiezer.".length())
                        + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                    if (frames.length() + entry.length() > 290) break;
                    frames.append(entry);
                }
                event.put("available", frames.toString());
                event.put("chosenOption", "");
                event.put("feedback", "");
                next.put(event);
                for (int i = 0; i < previous.length() && next.length() < MAX_EVENTS; i++)
                    next.put(previous.optJSONObject(i));
                boolean saved = p.edit().putString(EVENTS, next.toString()).commit();
                if (saved) TelemetryClient.send(ctx, event);
                android.util.Log.e("ModelkiezerHistory", code, error);
                return saved;
            } catch (Exception e) {
                android.util.Log.e("ModelkiezerHistory", "Failed to save diagnostic failure", e);
                return false;
            }
        }
    }

    public static String technicalReport(Context ctx) {
        JSONArray events = read(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        StringBuilder report = new StringBuilder();
        DateFormat date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
        int count = 0;
        for (int i = 0; i < events.length() && count < 25; i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null || !"DIAGNOSTIC".equals(e.optString("requested"))) continue;
            if (count++ > 0) report.append("\n");
            report.append(date.format(new Date(e.optLong("timestamp"))))
                .append(" — ").append(e.optString("outcome"));
            if (!e.optString("observed").isEmpty())
                report.append(" — ").append(e.optString("observed"));
            if (!e.optString("available").isEmpty())
                report.append("\n").append(e.optString("available"));
        }
        return count == 0 ? "Nog geen technische gebeurtenissen opgeslagen." : report.toString();
    }

    public static String preferred(Context ctx, ModelRule.Level level) {
        synchronized (LOCK) {
            JSONArray events = read(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i);
                if (e != null && level.name().equals(e.optString("requested")) &&
                        "SEND_CLICKED".equals(e.optString("outcome")) &&
                        "right".equals(e.optString("feedback"))) {
                    String observed = e.optString("chosenOption", "");
                    if (!observed.isEmpty()) return observed;
                }
            }
            return "";
        }
    }

    public static void feedback(Context ctx, boolean right) {
        synchronized (LOCK) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray events = read(p);
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i);
                if (e != null && "SEND_CLICKED".equals(e.optString("outcome")) && e.optString("feedback").isEmpty()) {
                    try {
                        e.put("feedback", right ? "right" : "wrong");
                        p.edit().putString(EVENTS, events.toString()).apply();
                    } catch (Exception ignored) { }
                    return;
                }
            }
        }
    }

    public static String summary(Context ctx) {
        JSONArray events = read(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        int clicked=0, failed=0, right=0, wrong=0, diagnostic=0;
        long totalMs=0;
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<events.length(); i++) {
            JSONObject e=events.optJSONObject(i); if (e==null) continue;
            String state=e.optString("outcome");
            if ("DIAGNOSTIC".equals(e.optString("requested"))) diagnostic++;
            else if ("SEND_CLICKED".equals(state)) { clicked++;totalMs+=e.optLong("durationMs"); }
            else failed++;
            if ("right".equals(e.optString("feedback"))) right++;
            if ("wrong".equals(e.optString("feedback"))) wrong++;
        }
        sb.append("Modelkeuzepogingen: ").append(clicked + failed)
            .append("\nDiagnostische gebeurtenissen: ").append(diagnostic)
            .append("\nTotaal opgeslagen regels: ").append(events.length())
            .append("\nVerzendknop geactiveerd na bevestiging: ").append(clicked)
            .append("\nNiet automatisch verzonden: ").append(failed)
            .append("\nBeoordeeld als juist: ").append(right)
            .append("\nBeoordeeld als onjuist: ").append(wrong);
        if (clicked > 0) sb.append("\nGemiddelde APK-wachttijd: ")
            .append(totalMs / clicked).append(" ms");
        sb.append("\n\nLaatste pogingen:\n");
        DateFormat fmt=DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
        for (int i=0; i<Math.min(20,events.length());i++) {
            JSONObject e=events.optJSONObject(i);if(e==null)continue;
            sb.append(fmt.format(new Date(e.optLong("timestamp")))).append(" | ")
              .append(e.optString("requested")).append(" → ")
              .append(e.optString("observed","onbekend")).append("\n")
              .append(e.optString("outcome")).append(" | ")
              .append(e.optLong("durationMs")).append(" ms");
            if (!e.optString("chosenOption").isEmpty())
                sb.append(" | keuze: ").append(e.optString("chosenOption"));
            if(!e.optString("feedback").isEmpty())
                sb.append(" | ").append(e.optString("feedback"));
            sb.append("\n");
        }
        sb.append("\nLet op: 'SEND_CLICKED' betekent dat de app op Verzenden klikte, niet dat ChatGPT de vraag op de server heeft verwerkt.");
        return sb.toString();
    }

    public static String json(Context ctx) {
        return read(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).toString();
    }
    public static void clear(Context ctx) {
        synchronized (LOCK) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        }
    }
    private static JSONArray read(SharedPreferences p) {
        try { return new JSONArray(p.getString(EVENTS,"[]")); }
        catch (Exception ignored) {return new JSONArray();}
    }
    private static String trim(String s, int max) {
        return s == null ? "" : s.substring(0, Math.min(s.length(),max));
    }
}