package com.prootz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;

public class MainActivity extends Activity {

    private TerminalView mTerminalView;
    private TerminalService mService;
    private int mFontSize = 28;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((TerminalService.LocalBinder) binder).getService();
            if (mService.getSession() != null) {
                mTerminalView.attachSession(mService.getSession());
            } else {
                RootfsInstaller.install(MainActivity.this, new RootfsInstaller.Callback() {
                    @Override public void onSuccess() { startSession(); }
                    @Override public void onError(String msg) { showError(msg); }
                });
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

        Intent serviceIntent = new Intent(this, TerminalService.class);
        startService(serviceIntent);
        bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE);
    }

    private void setupExtraKeys() {
        LinearLayout bar = findViewById(R.id.extra_keys);
        String[][] keys = {
            {"ESC", "\033"},
            {"TAB", "\t"},
            {"CTRL", null},
            {"ALT", null},
            {"↑", null},
            {"↓", null},
            {"←", null},
            {"→", null},
            {"/", "/"},
            {"-", "-"},
            {"|", "|"},
            {"HOME", null},
            {"END", null},
            {"PGUP", null},
            {"PGDN", null},
        };
        for (String[] key : keys) {
            Button btn = new Button(this);
            btn.setText(key[0]);
            btn.setTextSize(11);
            btn.setPadding(16, 0, 16, 0);
            btn.setAllCaps(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
            lp.setMargins(2, 2, 2, 2);
            btn.setLayoutParams(lp);
            final String label = key[0];
            final String send = key[1];
            btn.setOnClickListener(v -> onExtraKey(label, send));
            bar.addView(btn);
        }
    }

    private void onExtraKey(String label, String send) {
        if (mTerminalView.mTermSession == null) return;
        switch (label) {
            case "CTRL":
                // Toggle ctrl — handled via next key; for simplicity send ctrl+space as toggle indicator
                // Real ctrl combos: user holds CTRL then taps another key via onCodePoint
                // Here we just send a visible caret for now; full sticky-ctrl needs state tracking
                break;
            case "ALT":
                break;
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
                    byte[] b = send.getBytes();
                    mTerminalView.mTermSession.write(b, 0, b.length);
                }
        }
    }

    private void startSession() {
        if (mService == null) return;
        File filesDir = getFilesDir();
        File nativeLibDir = new File(getApplicationInfo().nativeLibraryDir);
        File prootExec = new File(nativeLibDir, "libproot_exec.so");
        File prootLoader = new File(nativeLibDir, "libproot_loader.so");
        File rootfsDir = new File(filesDir, "rootfs/alpine");
        File tmpDir = new File(filesDir, "tmp");
        tmpDir.mkdirs();

        String shell = new File(rootfsDir, "bin/ash").exists() ? "/bin/ash" : "/bin/sh";

        String[] args = {
            prootExec.getAbsolutePath(),
            "--root-id", "--kill-on-exit", "--link2symlink", "--sysvipc",
            "--kernel-release=6.2.1-PRoot-Distro",
            "-r", rootfsDir.getAbsolutePath(),
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color", "LANG=C.UTF-8",
            shell, "-l"
        };
        String[] env = {
            "PROOT_LOADER=" + prootLoader.getAbsolutePath(),
            "PROOT_TMP_DIR=" + tmpDir.getAbsolutePath(),
            "TMPDIR=" + tmpDir.getAbsolutePath(),
        };

        TerminalSession session = mService.createSession(
            prootExec.getAbsolutePath(), filesDir.getAbsolutePath(),
            args, env, new ProotzSessionClient());
        mTerminalView.attachSession(session);
        mService.updateNotification();
    }

    private void showError(final String message) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
            .setTitle("Bootstrap Error").setMessage(message)
            .setPositiveButton("Retry", (d, w) -> {
                d.dismiss();
                RootfsInstaller.reset(this);
                RootfsInstaller.install(this, new RootfsInstaller.Callback() {
                    @Override public void onSuccess() { startSession(); }
                    @Override public void onError(String msg) { showError(msg); }
                });
            })
            .setNegativeButton("Exit", (d, w) -> finish()).show());
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
            return 1f; // reset accumulator
        }
        @Override public void onSingleTapUp(MotionEvent e) { showKeyboard(); }
        @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
        @Override public boolean shouldEnforceCharBasedInput() { return false; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        @Override public boolean isTerminalViewSelected() { return true; }
        @Override public void copyModeChanged(boolean copyMode) {}
        @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }
        @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
        @Override public boolean onLongPress(MotionEvent event) { return false; }
        @Override public boolean readControlKey() { return false; }
        @Override public boolean readAltKey() { return false; }
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
