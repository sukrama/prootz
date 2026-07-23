package com.prootz;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.util.List;

public class MainActivity extends Activity {

    private TerminalView mTerminalView;
    private TerminalService mService;
    private int mFontSize = 28;
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 72;
    private static final int FONT_STEP = 2;
    private KeyButton mCtrlBtn, mAltBtn;
    private boolean mCtrlActive = false, mAltActive = false;

    private Dialog mInstallDialog;
    private TextView mInstStage, mInstPercent, mInstDetail;
    private ProgressBar mInstBar;
    private PopupWindow mSessionsPopup;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((TerminalService.LocalBinder) binder).getService();
            if (!mService.getSessions().isEmpty()) {
                switchToSession(mService.getSession(0));
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
            createNewSession(installed);
            return;
        }
        showInstallDialog();
        RootfsInstaller.install(this, RootfsInstaller.Distro.UBUNTU,
            (stage, pct, detail) -> runOnUiThread(() -> updateInstallDialog(stage, pct, detail)),
            new RootfsInstaller.Callback() {
                @Override public void onSuccess(RootfsInstaller.Distro d) {
                    dismissInstallDialog();
                    createNewSession(d);
                }
                @Override public void onError(String msg) {
                    dismissInstallDialog();
                    Toast.makeText(MainActivity.this,
                        "Install failed: " + msg, Toast.LENGTH_LONG).show();
                    finish();
                }
            });
    }

    // ---- Install progress dialog ----

    private void showInstallDialog() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_install);
        d.setCancelable(false);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.86f),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        mInstStage   = d.findViewById(R.id.install_stage);
        mInstPercent = d.findViewById(R.id.install_percent);
        mInstBar     = d.findViewById(R.id.install_bar);
        mInstDetail  = d.findViewById(R.id.install_detail);
        mInstallDialog = d;
        d.show();
    }

    private void updateInstallDialog(String stage, int pct, String detail) {
        if (mInstallDialog == null) return;
        if (mInstStage != null && stage != null) mInstStage.setText(stage);
        if (pct < 0) {
            if (mInstBar != null) mInstBar.setIndeterminate(true);
            if (mInstPercent != null) mInstPercent.setText("");
        } else {
            if (mInstBar != null) {
                mInstBar.setIndeterminate(false);
                mInstBar.setProgress(pct);
            }
            if (mInstPercent != null) mInstPercent.setText(pct + "%");
        }
        if (mInstDetail != null && detail != null) mInstDetail.setText(detail);
    }

    private void dismissInstallDialog() {
        if (mInstallDialog != null) {
            try { mInstallDialog.dismiss(); } catch (Exception ignored) {}
            mInstallDialog = null;
        }
    }

    // ---- Multi-session ----

    private void createNewSession(RootfsInstaller.Distro distro) {
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
            "HOME=/root", "USER=root", "LOGNAME=root",
            "SHELL=" + shell,
            "TERM=xterm-256color", "COLORTERM=truecolor",
            "LANG=C.UTF-8", "LC_ALL=C.UTF-8",
            "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            shell, "-l"
        };

        java.util.ArrayList<String> envList = new java.util.ArrayList<>();
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
        switchToSession(session);

        if (mService.getSessions().size() == 1) {
            mService.goForeground();
        }
    }

    private void switchToSession(TerminalSession session) {
        if (session == null) return;
        session.updateTerminalSessionClient(new ProotzSessionClient());
        mTerminalView.attachSession(session);
        mTerminalView.requestFocus();
    }

    private RootfsInstaller.Distro currentDistro() {
        RootfsInstaller.Distro d = RootfsInstaller.installedDistro(this);
        return d != null ? d : RootfsInstaller.Distro.UBUNTU;
    }

    // ---- Sessions drawer (hamburger popup) ----

    private void showSessionsDrawer(View anchor) {
        if (mService == null) return;
        dismissSessionsPopup();

        float density = getResources().getDisplayMetrics().density;
        int widthPx = (int)(220 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#111827"));
        bg.setStroke(dp(1), Color.parseColor("#1E3050"));
        bg.setCornerRadius(dp(10));
        root.setBackground(bg);

        // Title
        TextView title = new TextView(this);
        title.setText("Sessions");
        title.setTextColor(Color.parseColor("#FFB300"));
        title.setTextSize(15f);
        title.setPadding(dp(4), dp(2), 0, dp(8));
        root.addView(title);

        // Session list
        List<TerminalSession> sessions = mService.getSessions();
        TerminalSession current = mTerminalView.mTermSession;
        for (int i = 0; i < sessions.size(); i++) {
            final TerminalSession s = sessions.get(i);
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(8), dp(6), dp(8));

            if (s == current) {
                GradientDrawable sel = new GradientDrawable();
                sel.setColor(Color.parseColor("#1A2235"));
                sel.setCornerRadius(dp(6));
                row.setBackground(sel);
            }

            TextView label = new TextView(this);
            String name = s.mSessionName != null ? s.mSessionName : ("Session " + (i + 1));
            String status = s.isRunning() ? "" : " (exited)";
            label.setText(name + status);
            label.setTextColor(s == current ? Color.parseColor("#42A5F5") : Color.parseColor("#F0F0F0"));
            label.setTextSize(13f);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);

            // Delete button
            TextView del = new TextView(this);
            del.setText("\u2715");
            del.setTextColor(Color.parseColor("#9E9E9E"));
            del.setTextSize(14f);
            del.setPadding(dp(10), 0, dp(4), 0);
            del.setOnClickListener(v -> {
                int removed = mService.removeSession(s);
                dismissSessionsPopup();
                if (mService.getSessions().isEmpty()) {
                    finish();
                } else if (s == mTerminalView.mTermSession) {
                    int next = Math.min(removed, mService.getSessions().size() - 1);
                    switchToSession(mService.getSession(next));
                }
            });
            row.addView(del);

            row.setOnClickListener(v -> {
                switchToSession(s);
                dismissSessionsPopup();
            });
            root.addView(row);
        }

        // Divider
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#1E3050"));
        div.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout.LayoutParams divLp = (LinearLayout.LayoutParams) div.getLayoutParams();
        divLp.topMargin = dp(6);
        divLp.bottomMargin = dp(6);
        root.addView(div);

        // New Session button
        TextView newBtn = new TextView(this);
        newBtn.setText("+ New Session");
        newBtn.setTextColor(Color.parseColor("#FFB300"));
        newBtn.setTextSize(14f);
        newBtn.setPadding(dp(6), dp(8), dp(6), dp(4));
        newBtn.setOnClickListener(v -> {
            dismissSessionsPopup();
            createNewSession(currentDistro());
        });
        root.addView(newBtn);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);

        mSessionsPopup = new PopupWindow(scroll, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        mSessionsPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        mSessionsPopup.setElevation(dp(8));
        mSessionsPopup.setOnDismissListener(() -> mSessionsPopup = null);
        mSessionsPopup.showAsDropDown(anchor, 0, dp(4));
    }

    private void dismissSessionsPopup() {
        if (mSessionsPopup != null) {
            try { mSessionsPopup.dismiss(); } catch (Exception ignored) {}
            mSessionsPopup = null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- Extra keys ----

    private void setupExtraKeys() {
        LinearLayout row1 = findViewById(R.id.keys_row1);
        LinearLayout row2 = findViewById(R.id.keys_row2);

        // Hamburger button (leftmost, row1)
        KeyButton hamburger = new KeyButton(this, "\u2261", true);
        hamburger.setOnClickListener(v -> showSessionsDrawer(v));
        row1.addView(hamburger);

        // Row 1: navigation (5 keys + hamburger = 6)
        String[][] r1 = {{"ESC",null},{"TAB","\t"},{"\u2191",null},{"\u2193",null},{"\u2190",null},{"\u2192",null}};
        for (String[] k : r1) {
            KeyButton btn = new KeyButton(this, k[0], true);
            final String send = k[1];
            btn.setOnClickListener(v -> onExtraKey(k[0], send));
            row1.addView(btn);
        }

        // Row 2: modifiers + useful keys (6 keys)
        mCtrlBtn = new KeyButton(this, "CTRL", false);
        mAltBtn  = new KeyButton(this, "ALT",  false);
        mCtrlBtn.setOnClickListener(v -> { mCtrlActive = !mCtrlActive; mCtrlBtn.setActive(mCtrlActive); });
        mAltBtn.setOnClickListener(v  -> { mAltActive  = !mAltActive;  mAltBtn.setActive(mAltActive); });
        row2.addView(mCtrlBtn);
        row2.addView(mAltBtn);

        String[][] r2 = {{"HOME",null},{"END",null},{"/","/"},{"|","|"},{"~","~"}};
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
            case "\u2191":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP, 0); break;
            case "\u2193":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN, 0); break;
            case "\u2190":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_DPAD_LEFT, 0); break;
            case "\u2192":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 0); break;
            case "HOME":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_MOVE_HOME, 0); break;
            case "END":
                mTerminalView.handleKeyCode(android.view.KeyEvent.KEYCODE_MOVE_END, 0); break;
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
        dismissInstallDialog();
        dismissSessionsPopup();
        try { unbindService(mConnection); } catch (Exception ignored) {}
    }

    // ---- TerminalViewClient ----
    private class ProotzViewClient implements TerminalViewClient {
        @Override public float onScale(float scale) {
            if (scale < 0.94f || scale > 1.06f) {
                int step = scale > 1f ? FONT_STEP : -FONT_STEP;
                int newSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, mFontSize + step));
                if (newSize != mFontSize) {
                    mFontSize = newSize;
                    mTerminalView.setTextSize(mFontSize);
                }
                return 1f;
            }
            return scale;
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
        @Override public void onSessionFinished(TerminalSession s) {
            if (mService != null && mService.getSessions().size() <= 1) {
                finish();
            }
        }
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
