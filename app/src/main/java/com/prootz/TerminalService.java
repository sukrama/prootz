package com.prootz;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/** Minimal bound service that owns the terminal session; lifecycle follows the Activity. */
public class TerminalService extends Service {

    private TerminalSession mSession;

    public class LocalBinder extends Binder {
        TerminalService getService() { return TerminalService.this; }
    }
    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    TerminalSession createSession(String prootExec, String filesDir, String[] args, String[] env,
                                  TerminalSessionClient client) {
        mSession = new TerminalSession(prootExec, filesDir, args, env, 3000, client);
        return mSession;
    }

    TerminalSession getSession() { return mSession; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mSession != null) mSession.finishIfRunning();
    }
}
