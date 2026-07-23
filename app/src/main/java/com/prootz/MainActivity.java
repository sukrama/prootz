package com.prootz;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;

public class MainActivity extends Activity {

    private TerminalView mTerminalView;
    private TerminalService mService;
    private int mFontSize = 28;
    private KeyButton mCtrlBtn, mAltBtn;
    private boolean mCtrlActive = false, mAltActive = false;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((TerminalService.LocalBinder) binder).getService();
            if (mService.getSession() != null) {
                mTerminalView.attachSession(mService.getSession());
            } else {
                checkAndInstall();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { mService = null; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(new ProotzViewClient());
        mTerminalView.setTextSize(mFontSize);
        mTerminalView.requestFocus();

        setupExtraKeys();

        Intent svc = new Intent(this, TerminalService.class);
        startService(svc);
        bindService(svc, mConnection, Context.BIND_AUTO_CREATE);
    }

    private void checkAndInstall() {
        RootfsInstaller.Distro installed = RootfsInstaller.installedDistro(this);
        if (installed != null) {
            startSession(installed);
            return;
        }
        // Auto-install Ubuntu langsung tanpa dialog popup
        RootfsInstaller.install(this, RootfsInstaller.Distro.UBUNTU,
            (stage, pct, detail) -> { /* progress bisa ditambahkan nanti */ },
            new RootfsInstaller.Callback() {
                @Override public void onSuccess(RootfsInstaller.Distro d) {
                    startSession(d);
                }
                @Override public void onError(String msg) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                            "Install failed: " + msg, Toast.LENGTH_LONG).show();
                        finish();
                    });
                }
            });
    }

    private void startSession(RootfsInstaller.Distro distro) {
        if (mService == null) return;
        File filesDir = getFilesDir();
        File nativeLibDir = new File(getApplicationInfo().nativeLibraryDir);
        File prootExec = new File(nativeLibDir, "libproot_exec.so");
        File prootLoader = new File(nativeLibDir, "libproot_loader.so");
        File rootfsDir = RootfsInstaller.rootfsDir(this, distro);
        File tmpDir = new File(filesDir, "tmp");
        tmpDir.mkdirs();

        String shell = new File(rootfsDir, distro.shell.substring(1)).exists()
            ? distro.shell : "/bin/sh";

        String[] args = {
            prootExec.getAbsolutePath(),
            "--root-id", "--kill-on-exit", "--link2symlink", "--sysvipc",
            "--kernel-release=6.2.1-PRoot-Distro",
            "-r", rootfsDir.getAbsolutePath(),
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "SHELL=" + shell,
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            shell, "-l"
        };

        // Pass through Android system env vars (same as termux)
        java.util.List<String> envList = new java.util.ArrayList<>();
        envList.add("PROOT_LOADER=" + prootLoader.getAbsolutePath());
        envList.add("PROOT_TMP_DIR=" + tmpDir.getAbsolutePath());
        envList.add("TMPDIR=" + tmpDir.getAbsolutePath());
        for (String key : new String[]{
            "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ASSETS", "ANDROID_STORAGE",
            "ANDROID_RUNTIME_ROOT", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT",
            "ANDROID_TZDATA_ROOT", "EXTERNAL_STORAGE", "BOOTCLASSPATH",
            "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH"}) {
            String val = System.getenv(key);
            if (val != null) envList.add(key + "=" + val);
        }

        TerminalSession session = mService.createSession(
            prootExec.getAbsolutePath(), filesDir.getAbsolutePath(),
            args, envList.toArray(new String[0]), new ProotzSessionClient());
        mTerminalView.attachSession(session);
        mService.updateNotification();
    }

    // ---- Extra keys ----

    private void setupExtraKeys() {
        LinearLayout row1 = findViewById(R.id.keys_row1);
        LinearLayout row2 = findViewById(R.id.keys_row2);

        // Row 1: navigation
        String[][] r1 = {{"ESC",null},{"TAB","\t"},{"↑",null},{"↓",null},{"←",null},{"→",null},{"HOME",null},{"END",null},{"PGUP",null},{"PGDN",null}};
        for (String[] k : r1) {
            KeyButton btn = new KeyButton(this, k[0], true);
            final String send = k[1];
            btn.setOnClickListener(v -> onExtraKey(k[0], send));
            row1.addView(btn);
        }

        // Row 2: modifiers + symbols
        mCtrlBtn = new KeyButton(this, "CTRL", false);
        mAltBtn  = new KeyButton(this, "ALT",  false);
        mCtrlBtn.setOnClickListener(v -> { mCtrlActive = !mCtrlActive; mCtrlBtn.setActive(mCtrlActive); });
        mAltBtn.setOnClickListener(v  -> { mAltActive  = !mAltActive;  mAltBtn.setActive(mAltActive); });
        row2.addView(mCtrlBtn);
        row2.addView(mAltBtn);

        String[][] r2 = {{"/","/"},{"-","-"},{"_","_"},{"|","|"},{"\\","\\"},{"~","~"},{"\"","\""},{"'","'"},{"[","["},{"]","]"},{"{","{"},{"}","}"},{"#","#"},{"$","$"},{"&","&"},{"*","*"}};
        for (String[] k : r2) {
            KeyButton btn = new KeyButton(this, k[0], false);
            final String send = k[1];
            btn.setOnClickListener(v -> onExtraKey(k[0], send));
            row2.addView(btn);
        }
    }

    private void onExtraKey(String label, String send) {
        if (mTerminalView.mTermSession == null) return;
        switch (label) {
            case "ESC":
                mTerminalView.mTermSession.write(new byte[]{0x1b}, 0, 1); break;
            case "↑":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP, 0); break;
            case "↓":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN, 0); break;
            case "←":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_DPAD_LEFT, 0); break;
            case "→":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 0); break;
            case "HOME":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_MOVE_HOME, 0); break;
            case "END":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_MOVE_END, 0); break;
            case "PGUP":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_PAGE_UP, 0); break;
            case "PGDN":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_PAGE_DOWN, 0); break;
            default:
                if (send != null) {
                    String out = send;
                    if (mCtrlActive && send.length() == 1) {
                        char c = send.charAt(0);
                        if (c >= 'a' && c <= 'z') out = String.valueOf((char)(c - 96));
                        else if (c >= 'A' && c <= 'Z') out = String.valueOf((char)(c - 64));
                        mCtrlActive = false; mCtrlBtn.setActive(false);
                    } else if (mAltActive) {
                        out = "\033" + send;
                        mAltActive = false; mAltBtn.setActive(false);
                    }
                    byte[] b = out.getBytes();
                    mTerminalView.mTermSession.write(b, 0, b.length);
                }
        }
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(mTerminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unbindService(mConnection); } catch (Exception ignored) {}
    }

    // ---- TerminalViewClient ----
    private class ProotzViewClient implements TerminalViewClient {
        @Override public float onScale(float scale) {
            mFontSize = Math.max(8, Math.min(72, (int)(mFontSize * scale)));
            mTerminalView.setTextSize(mFontSize);
            return 1f;
        }
        @Override public void onSingleTapUp(MotionEvent e) { showKeyboard(); }
        @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
        @Override public boolean shouldEnforceCharBasedInput() { return false; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        @Override public boolean isTerminalViewSelected() { return true; }
        @Override public void copyModeChanged(boolean copyMode) {}
        @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
            if (mCtrlActive && keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
                byte ctrl = (byte)(keyCode - KeyEvent.KEYCODE_A + 1);
                session.write(new byte[]{ctrl}, 0, 1);
                mCtrlActive = false; if (mCtrlBtn != null) mCtrlBtn.setActive(false);
                return true;
            }
            return false;
        }
        @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
        @Override public boolean onLongPress(MotionEvent event) { return false; }
        @Override public boolean readControlKey() { return mCtrlActive; }
        @Override public boolean readAltKey() { return mAltActive; }
        @Override public boolean readShiftKey() { return false; }
        @Override public boolean readFnKey() { return false; }
        @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) { return false; }
        @Override public void onEmulatorSet() {}
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    }

    // ---- TerminalSessionClient ----
    private class ProotzSessionClient implements TerminalSessionClient {
        @Override public void onTextChanged(TerminalSession s) { mTerminalView.onScreenUpdated(); }
        @Override public void onTitleChanged(TerminalSession s) {}
        @Override public void onSessionFinished(TerminalSession s) { finish(); }
        @Override public void onCopyTextToClipboard(TerminalSession s, String text) {}
        @Override public void onPasteTextFromClipboard(TerminalSession s) {}
        @Override public void onBell(TerminalSession s) {}
        @Override public void onColorsChanged(TerminalSession s) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public void setTerminalShellPid(TerminalSession s, int pid) {}
        @Override public Integer getTerminalCursorStyle() { return null; }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    }
}
