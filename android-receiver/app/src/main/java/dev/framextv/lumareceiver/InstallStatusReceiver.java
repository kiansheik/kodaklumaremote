package dev.framextv.lumareceiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** PackageInstaller session callback for self-updates. */
public final class InstallStatusReceiver extends BroadcastReceiver {
    @Override @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        ReceiverApp.get(context).updater.onInstallStatus(status, confirm);
    }
}
