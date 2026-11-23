package com.bradyxu.headunitmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        AccessibilityRestoreReceiver.schedule(context);
        Rules.syncHmiService(context);
        PendingResult pending = goAsync();
        new Thread(() -> {
            try { Rules.dispatch(context, Rules.TRIGGER_BOOT, "", null); }
            finally { pending.finish(); }
        }).start();
        if (Rules.hasTrigger(context, Rules.TRIGGER_LOG) &&
            Privilege.has(context, "android.permission.READ_LOGS"))
            context.startService(new Intent(context, LogService.class));
    }
}
