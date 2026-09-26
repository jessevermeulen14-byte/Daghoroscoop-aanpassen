package nl.jv.daghoroscoop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SystemReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("enabled", false)) {
            Scheduler.schedule(context);
        }
    }
}
