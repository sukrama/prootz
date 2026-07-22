package com.prootz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;

public class TerminalService extends Service {

    static final String ACTION_STOP = "com.prootz.STOP";
    static final String CHANNEL_ID = "prootz_terminal";
    static final int NOTIF_ID = 1;

    private TerminalSession mSession;

    public class LocalBinder extends Binder {
        TerminalService getService() { return TerminalService.this; }
    }
    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            if (mSession != null) mSession.finishIfRunning();
            return START_NOT_STICKY;
        }
        setupNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    TerminalSession createSession(String prootExec, String filesDir, String[] args, String[] env,
                                  TerminalSessionClient client) {
        mSession = new TerminalSession(prootExec, filesDir, args, env, 3000, client);
        return mSession;
    }

    TerminalSession getSession() { return mSession; }

    void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, TerminalService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent openIntent = new Intent(this, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("prootz")
            .setContentText("Alpine terminal running")
            .setSmallIcon(android.R.drawable.ic_menu_terminal)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Exit", stopPi)
            .build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "prootz Terminal", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mSession != null) mSession.finishIfRunning();
    }
}
