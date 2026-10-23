package dev.framextv.lumareceiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot: start serving in the background. Self-update: also come back on screen. */
public final class StartupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ReceiverApp.startReceiverService(context);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) MainActivity.bringToFront(context);
    }
}
