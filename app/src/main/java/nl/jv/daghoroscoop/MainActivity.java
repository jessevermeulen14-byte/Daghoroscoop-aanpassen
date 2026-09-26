package nl.jv.daghoroscoop;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 60, 40, 40);
        status = new TextView(this);
        status.setTextSize(20);
        layout.addView(status);
        Button test = new Button(this);
        test.setText("CW-daghoroscoop nu testen");
        test.setOnClickListener(v -> {
            Intent target = Target.find(this);
            if (target != null) startActivity(target);
            else status.setText("CW-daghoroscoop niet gevonden. Is dit een losse app of een web-snelkoppeling?");
        });
        layout.addView(test);
        Button permissions = new Button(this);
        permissions.setText("Toegang voor precies 00:00 toestaan");
        permissions.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 31) {
                Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        });
        layout.addView(permissions);
        Button notification = new Button(this);
        notification.setText("Meldingen toestaan (reserveoptie)");
        notification.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        });
        layout.addView(notification);
        setContentView(layout);
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        updateStatus();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }
    @Override protected void onResume() { super.onResume(); if (status != null) updateStatus(); }
    private void updateStatus() {
        boolean exact = Scheduler.schedule(this);
        boolean found = Target.find(this) != null;
        status.setText("Elke nacht om 00:00 ingepland.\n\nDoelapp: " + (found ? "gevonden" : "NIET gevonden") +
                "\nExact alarm: " + (exact ? "toegestaan" : "geen toestemming; tijdstip kan afwijken") +
                "\n\nAndroid kan openen vanuit de achtergrond blokkeren. In dat geval ontvang je een melding om zelf te openen.");
    }
}
