package nl.jv.daghoroscoop;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

final class Target {
    private Target() { }
    static Intent find(Context context) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        ApplicationInfo candidate = null;
        int best = 0;
        for (ApplicationInfo app : apps) {
            if (app.packageName.equals(context.getPackageName())) continue;
            Intent launch = pm.getLaunchIntentForPackage(app.packageName);
            if (launch == null) continue;
            String label = normalize(String.valueOf(pm.getApplicationLabel(app)));
            String pkg = normalize(app.packageName);
            int score = 0;
            if (label.equals("cw daghoroscoop")) score = 100;
            else if (label.equals("daghoroscoop")) score = 95;
            else if (label.contains("cw") && label.contains("daghoroscoop")) score = 90;
            else if (label.contains("daghoroscoop")) score = 80;
            else if (pkg.contains("daghoroscoop")) score = 60;
            if (score > best) { candidate = app; best = score; }
        }
        if (candidate == null) return null;
        Intent launch = pm.getLaunchIntentForPackage(candidate.packageName);
        if (launch != null) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return launch;
    }
    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
