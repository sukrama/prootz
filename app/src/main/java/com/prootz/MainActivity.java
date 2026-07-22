package com.prootz;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.content.Context;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;

public class MainActivity extends Activity {

    private TerminalView mTerminalView;
    private TerminalSession mSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(new ProotzViewClient());
        mTerminalView.setTextSize(28);
        mTerminalView.requestFocus();

        RootfsInstaller.install(this, new RootfsInstaller.Callback() {
            @Override
            public void onSuccess() {
                startSession();
            }
            @Override
            public void onError(String message) {
                showError(message);
            }
        });
    }

    private void startSession() {
        File filesDir = getFilesDir();
        File nativeLibDir = new File(getApplicationInfo().nativeLibraryDir);
        File prootExec = new File(nativeLibDir, "libproot_exec.so");
        File prootLoader = new File(nativeLibDir, "libproot_loader.so");
        File rootfsDir = new File(filesDir, "rootfs/alpine");
        File tmpDir = new File(filesDir, "tmp");
        tmpDir.mkdirs();

        String shell = new File(rootfsDir, "bin/ash").exists() ? "/bin/ash"
                     : new File(rootfsDir, "bin/sh").exists()  ? "/bin/sh"
                     : "/bin/sh";

        String[] args = {
            prootExec.getAbsolutePath(),
            "--root-id",
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--kernel-release=6.2.1-PRoot-Distro",
            "-r", rootfsDir.getAbsolutePath(),
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            shell, "-l"
        };

        String[] env = {
            "PROOT_LOADER=" + prootLoader.getAbsolutePath(),
            "PROOT_TMP_DIR=" + tmpDir.getAbsolutePath(),
            "TMPDIR=" + tmpDir.getAbsolutePath(),
        };

        mSession = new TerminalSession(
            prootExec.getAbsolutePath(),
            filesDir.getAbsolutePath(),
            args,
            env,
            3000,
            new ProotzSessionClient()
        );
        mTerminalView.attachSession(mSession);
    }

    private void showError(final String message) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
            .setTitle("Bootstrap Error")
            .setMessage(message)
            .setPositiveButton("Retry", (d, w) -> {
                d.dismiss();
                RootfsInstaller.reset(this);
                RootfsInstaller.install(this, new RootfsInstaller.Callback() {
                    @Override public void onSuccess() { startSession(); }
                    @Override public void onError(String msg) { showError(msg); }
                });
            })
            .setNegativeButton("Exit", (d, w) -> finish())
            .show());
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            mTerminalView.requestFocus();
            imm.showSoftInput(mTerminalView, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    // --- minimal TerminalViewClient ---
    private class ProotzViewClient implements TerminalViewClient {
        @Override public float onScale(float scale) { return scale; }
        @Override public void onSingleTapUp(android.view.MotionEvent e) { showKeyboard(); }
        @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
        @Override public boolean shouldEnforceCharBasedInput() { return false; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        @Override public boolean isTerminalViewSelected() { return true; }
        @Override public void copyModeChanged(boolean copyMode) {}
        @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent e, TerminalSession session) { return false; }
        @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent e) { return false; }
        @Override public boolean onLongPress(android.view.MotionEvent event) { return false; }
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

    // --- minimal TerminalSessionClient ---
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
