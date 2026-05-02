package com.termux.apiextended;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service that keeps the TCP server alive.
 * Required on Android 8+ to prevent the system from killing the background service.
 */
public class ApiService extends Service {

    private static final String TAG = "ApiService";
    private static final int NOTIFICATION_ID = 9001;
    private static final String CHANNEL_ID = "termux_api_extended";

    private TcpServer tcpServer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."));

        tcpServer = new TcpServer(this);

        if (tcpServer.start()) {
            updateNotification("Running on port " + tcpServer.getPort());
            Log.i(TAG, "API service started successfully");
        } else {
            updateNotification("Failed to start!");
            Log.e(TAG, "Failed to start TCP server");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (tcpServer != null) {
            tcpServer.stop();
        }
        stopForeground(true);
        Log.i(TAG, "API service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Termux API Extended",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("TCP server for Termux integration");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String status) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("Termux API Extended")
               .setContentText(status)
               .setSmallIcon(android.R.drawable.ic_menu_info_details)
               .setOngoing(true)
               .setPriority(Notification.PRIORITY_LOW);

        return builder.build();
    }

    private void updateNotification(String status) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }
}
