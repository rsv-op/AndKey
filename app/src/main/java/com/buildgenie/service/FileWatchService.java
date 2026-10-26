package com.buildgenie.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.FileObserver;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;

/**
 * Foreground service that watches the staging inbox for file changes.
 * Complies with Android 14 foregroundServiceType and notification rules.
 */
public class FileWatchService extends Service {

    private static final String CHANNEL_ID = "buildgenie_watch";
    private static final int NOTIF_ID = 1001;
    private static final String ACTION_FILE_INGESTED = "com.buildgenie.ACTION_FILE_INGESTED";

    private FileObserver fileObserver;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        File staging = new File(getFilesDir(), "staging");
        if (!staging.exists()) staging.mkdirs();

        // Recursive observer not needed for single-level staging; use FileObserver (deprecated but still works on API 26-34)
        // On Android 10+ we could use WorkManager periodic scan as fallback
        fileObserver = new FileObserver(staging.getPath(), FileObserver.CREATE | FileObserver.MODIFY | FileObserver.MOVED_TO | FileObserver.DELETE) {
            @Override
            public void onEvent(int event, @Nullable String path) {
                if (path == null) return;
                // Debounce and broadcast
                sendBroadcast(new Intent(ACTION_FILE_INGESTED));
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        // Android 14 requires foregroundServiceType declared in manifest (dataSync)
        startForeground(NOTIF_ID, notification);
        try {
            fileObserver.startWatching();
        } catch (Exception ignored) {}
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (fileObserver != null) fileObserver.stopWatching();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "BuildGenie File Watch",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Watches staging inbox for new files offline");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BuildGenie Offline")
                .setContentText("Watching staging folder for new files")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }
}
