package com.prootz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.List;

/** Foreground service owning a list of terminal sessions, with an optional wake lock. */
public class TerminalService extends Service {

    static final String ACTION_STOP = "com.prootz.ACTION_STOP";
    static final String ACTION_WAKE_LOCK = "com.prootz.ACTION_WAKE_LOCK";
    static final String ACTION_WAKE_UNLOCK = "com.prootz.ACTION_WAKE_UNLOCK";
    static final String CHANNEL_ID = "prootz_terminal";
    static final int NOTIF_ID = 1;

    private final List<TerminalSession> mSessions = new ArrayList<>();
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    public class LocalBinder extends Binder {
        TerminalService getService() { return TerminalService.this; }
    }
    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            killAllSessions();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_WAKE_LOCK.equals(action)) {
            acquireWakeLock();
            updateNotification();
            return START_STICKY;
        }
        if (ACTION_WAKE_UNLOCK.equals(action)) {
            releaseWakeLock();
            updateNotification();
            return START_STICKY;
        }
        return START_STICKY;
    }

    // ---- Session management ----

    TerminalSession createSession(String exe, String cwd, String[] args,
                                  String[] env, TerminalSessionClient client) {
        TerminalSession s = new TerminalSession(exe, cwd, args, env, 3000, client);
        s.mSessionName = "Session " + (mSessions.size() + 1);
        mSessions.add(s);
        updateNotification();
        return s;
    }

    List<TerminalSession> getSessions() { return mSessions; }

    TerminalSession getSession(int index) {
        if (index >= 0 && index < mSessions.size()) return mSessions.get(index);
        return null;
    }

    int removeSession(TerminalSession s) {
        s.finishIfRunning();
        int idx = mSessions.indexOf(s);
        mSessions.remove(s);
        if (mSessions.isEmpty()) {
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        } else {
            updateNotification();
        }
        return idx;
    }

    void killAllSessions() {
        for (TerminalSession s : new ArrayList<>(mSessions)) s.finishIfRunning();
        mSessions.clear();
    }

    // ---- Wake lock (force background) ----

    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "prootz:keep-cpu");
            mWakeLock.acquire();
        }
        if (mWifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "prootz:wifi");
            mWifiLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null) { mWakeLock.release(); mWakeLock = null; }
        if (mWifiLock != null) { mWifiLock.release(); mWifiLock = null; }
    }

    boolean isWakeLockHeld() { return mWakeLock != null; }

    // ---- Foreground notification ----

    void goForeground() {
        setupNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

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

        String title = mSessions.size() + " session" + (mSessions.size() != 1 ? "s" : "");
        String text = isWakeLockHeld() ? "Wake lock held" : "Running";

        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
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
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        killAllSessions();
    }
}
