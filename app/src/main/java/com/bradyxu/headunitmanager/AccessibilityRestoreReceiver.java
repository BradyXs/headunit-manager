package com.bradyxu.headunitmanager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

public final class AccessibilityRestoreReceiver extends BroadcastReceiver {
    private static final String ACTION_RESTORE =
        "com.bradyxu.headunitmanager.action.RESTORE_ACCESSIBILITY";
    private static final long[] RETRY_DELAYS_MS = {
        8_000L, 25_000L, 60_000L, 120_000L
    };

    static void schedule(Context context) {
        if (!AccessibilityTools.hasRememberedServices(context)) return;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < RETRY_DELAYS_MS.length; i++) {
            Intent intent = new Intent(context, AccessibilityRestoreReceiver.class);
            intent.setAction(ACTION_RESTORE);
            intent.putExtra("attempt", i + 1);
            PendingIntent operation = PendingIntent.getBroadcast(context, 7150 + i, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + RETRY_DELAYS_MS[i], operation);
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_RESTORE.equals(intent.getAction())) return;
        final int attempt = intent.getIntExtra("attempt", 0);
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                String result = AccessibilityTools.restoreRememberedServices(context);
                Log.i("HeadunitManager", "Accessibility boot restore attempt " + attempt + ": " + result);
            } catch (RuntimeException error) {
                Log.w("HeadunitManager", "Accessibility boot restore failed", error);
            } finally {
                pending.finish();
            }
        }, "accessibility-boot-restore").start();
    }
}
