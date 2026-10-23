package dev.framextv.lumareceiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps the process (and therefore the control server) alive in the background. */
public final class ReceiverService extends Service {
    private static final int NOTIFICATION = 1;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("receiver", "Receiver", NotificationManager.IMPORTANCE_MIN);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
            builder = new Notification.Builder(this, "receiver");
        } else builder = new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name)).setContentText("Ready on port " + ControlServer.PORT).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION, notification);
        ReceiverApp.get(this).server.start();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
