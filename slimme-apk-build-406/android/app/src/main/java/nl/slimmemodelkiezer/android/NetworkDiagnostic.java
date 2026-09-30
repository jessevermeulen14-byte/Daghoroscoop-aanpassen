package nl.slimmemodelkiezer.android;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/**
 * Checks the APK's own internet access. No prompt text, credentials, identifiers
 * or ChatGPT calls are sent. HTTPS reachability is distinct from model control.
 */
public final class NetworkDiagnostic {
    private NetworkDiagnostic() {}
    public interface Callback { void onResult(Result result); }
    public static final class Result {
        public final String userMessage;
        public final String logCode;
        private Result(String userMessage, String logCode) {
            this.userMessage = userMessage;
            this.logCode = logCode;
        }
    }
    public static void checkAsync(Activity activity, Callback callback) {
        new Thread(() -> {
            Result result = check(activity.getApplicationContext());
            activity.runOnUiThread(() -> callback.onResult(result));
        }, "modelkiezer-network-check").start();
    }
    static Result check(Context context) {
        ConnectivityManager cm =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return new Result("Netwerkstatus niet beschikbaar.", "NETWORK_STATUS_UNKNOWN");
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            return new Result("Geen actieve internetverbinding. Controleer wifi of mobiele data.",
                "NETWORK_OFFLINE");
        HttpsURLConnection conn = null;
        try {
            // Fixed public HTTPS reachability endpoint. Sends NO chat or model data.
            URL url = new URL("https://www.google.com/generate_204");
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            conn.setUseCaches(false);
            int status = conn.getResponseCode();
            if (status == 204)
                return new Result("Internet werkt: de APK heeft zelf een HTTPS-verbinding.",
                    "HTTPS_CONNECTED");
            return new Result("Internet aanwezig, maar HTTPS-controle gaf status " + status +
                ". ChatGPT-koppeling is hiermee niet bevestigd.", "HTTPS_UNEXPECTED_RESPONSE");
        } catch (Exception e) {
            return new Result("Internet gedetecteerd, maar HTTPS-test mislukt (" +
                e.getClass().getSimpleName() + ").", "HTTPS_FAILED");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
