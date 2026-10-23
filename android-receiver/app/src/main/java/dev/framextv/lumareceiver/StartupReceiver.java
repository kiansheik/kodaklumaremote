package dev.framextv.lumareceiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot/update: start serving immediately and make the receiver the visible screen. */
public final class StartupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ReceiverApp.startReceiverService(context);
        if (showOnStartup(intent == null ? null : intent.getAction())) MainActivity.bringToFront(context);
    }

    static boolean showOnStartup(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    }
}
