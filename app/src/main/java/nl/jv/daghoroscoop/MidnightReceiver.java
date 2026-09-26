package nl.jv.daghoroscoop;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

public final class MidnightReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "daily_open";
    @Override public void onReceive(Context context, Intent ignored) {
        if (!context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("enabled", false)) return;
        Scheduler.schedule(context);
        Intent target = Target.find(context);
        if (target == null) {
            Log.w("Horoscoop", "CW-daghoroscoop not installed or not launchable");
            notifyUser(context, new Intent(context, MainActivity.class), "CW-daghoroscoop niet gevonden");
            return;
        }
        try {
            context.startActivity(target);
        } catch (Exception ex) {
            Log.w("Horoscoop", "Direct launch blocked by Android", ex);
        }
        notifyUser(context, target, "Tik om CW-daghoroscoop te openen");
    }
    private void notifyUser(Context context, Intent intent, String message) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Dagelijkse daghoroscoop", NotificationManager.IMPORTANCE_HIGH));
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        PendingIntent click = PendingIntent.getActivity(context, 200, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification note = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_today)
                .setContentTitle("Daghoroscoop")
                .setContentText(message)
                .setContentIntent(click).setAutoCancel(true).build();
        nm.notify(200, note);
    }
}
