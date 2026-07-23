package com.prootz;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.util.List;

public class MainActivity extends Activity {

    private TerminalView mTerminalView;
    private TerminalService mService;
    private DrawerLayout mDrawerLayout;
    private LinearLayout mDrawerPanel;
    private LinearLayout mDrawerSessionList;
    private int mFontSize = 28;
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 72;
    private static final int FONT_STEP = 2;
    private KeyButton mCtrlBtn, mAltBtn;
    private boolean mCtrlActive = false, mAltActive = false;
    private static final int REQ_STORAGE = 1002;
    private static final String PREFS = "prootz";

    private Dialog mInstallDialog;
    private TextView mInstStage, mInstPercent, mInstDetail;
    private ProgressBar mInstBar;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((TerminalService.LocalBinder) binder).getService();
            if (!mService.getSessions().isEmpty()) {
                // Find first still-running session, or fall back to last
                TerminalSession alive = null;
                for (TerminalSession s : mService.getSessions()) {
                    if (s.isRunning()) { alive = s; break; }
                }
                switchToSession(alive != null ? alive : mService.getSession(0));
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

        mDrawerLayout = findViewById(R.id.drawer_layout);
        mDrawerPanel = findViewById(R.id.drawer_panel);
        mDrawerSessionList = findViewById(R.id.drawer_session_list);
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(new ProotzViewClient());
        mTerminalView.setTextSize(mFontSize);
        mTerminalView.requestFocus();

        setupDrawer();
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

        String[] baseArgs = {
            prootExec.getAbsolutePath(),
            "--root-id", "--kill-on-exit", "--link2symlink", "--sysvipc",
            "--kernel-release=6.2.1-PRoot-Distro",
            "-r", rootfsDir.getAbsolutePath(),
            "-b", "/dev", "-b", "/proc", "-b", "/sys"
        };
        java.util.ArrayList<String> args = new java.util.ArrayList<>(java.util.Arrays.asList(baseArgs));

        // Shared storage bind mounts (only when the user has set up storage access)
        if (isStorageEnabled()) addStorageBinds(args);

        for (String a : new String[]{
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root",
            "SHELL=" + shell,
            "TERM=xterm-256color", "COLORTERM=truecolor",
            "LANG=C.UTF-8", "LC_ALL=C.UTF-8",
            "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            shell, "-l"}) {
            args.add(a);
        }

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
            args.toArray(new String[0]), envList.toArray(new String[0]), new ProotzSessionClient());
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

    // ---- Drawer (sessions panel) ----

    private void setupDrawer() {
        findViewById(R.id.drawer_new_session).setOnClickListener(v -> {
            mDrawerLayout.closeDrawers();
            createNewSession(currentDistro());
        });
        findViewById(R.id.drawer_setup_storage).setOnClickListener(v -> onSetupStorageClicked());
        updateSetupStorageButton();

        mDrawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerOpened(View drawerView) {
                refreshDrawerSessionList();
                updateSetupStorageButton();
            }
        });
    }

    // ---- Shared storage access ----

    private boolean isStorageSetupPref() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("storage_setup", false);
    }

    private boolean isStoragePermGranted() {
        return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean isStorageEnabled() {
        return isStorageSetupPref() && isStoragePermGranted();
    }

    private void updateSetupStorageButton() {
        View b = findViewById(R.id.drawer_setup_storage);
        if (b != null) b.setVisibility(isStorageSetupPref() ? View.GONE : View.VISIBLE);
    }

    private void onSetupStorageClicked() {
        if (isStoragePermGranted()) {
            enableStorage();
        } else {
            requestPermissions(new String[]{
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
        }
    }

    private void enableStorage() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("storage_setup", true).apply();
        updateSetupStorageButton();
        mDrawerLayout.closeDrawers();
        Toast.makeText(this, "Storage enabled. Opening a new session with /root/storage...",
            Toast.LENGTH_LONG).show();
        createNewSession(currentDistro());
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_STORAGE) {
            if (isStoragePermGranted()) enableStorage();
            else Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    private void addStorageBinds(java.util.ArrayList<String> args) {
        try {
            File shared = android.os.Environment.getExternalStorageDirectory();
            bindDir(args, shared, "/root/storage/shared");
            bindPublic(args, android.os.Environment.DIRECTORY_DOWNLOADS, "/root/storage/downloads");
            bindPublic(args, android.os.Environment.DIRECTORY_DCIM, "/root/storage/dcim");
            bindPublic(args, android.os.Environment.DIRECTORY_PICTURES, "/root/storage/pictures");
            bindPublic(args, android.os.Environment.DIRECTORY_MUSIC, "/root/storage/music");
            bindPublic(args, android.os.Environment.DIRECTORY_MOVIES, "/root/storage/movies");
            File ext = getExternalFilesDir(null);
            bindDir(args, ext, "/root/storage/external-0");
        } catch (Exception ignored) {}
    }

    private void bindPublic(java.util.ArrayList<String> args, String type, String guest) {
        bindDir(args, android.os.Environment.getExternalStoragePublicDirectory(type), guest);
    }

    private void bindDir(java.util.ArrayList<String> args, File host, String guest) {
        if (host == null) return;
        args.add("-b");
        args.add(host.getAbsolutePath() + ":" + guest);
    }

    private void toggleDrawer() {
        if (mDrawerLayout.isDrawerOpen(Gravity.START)) {
            mDrawerLayout.closeDrawer(Gravity.START);
        } else {
            refreshDrawerSessionList();
            mDrawerLayout.openDrawer(Gravity.START);
        }
    }

    private void refreshDrawerSessionList() {
        if (mService == null || mDrawerSessionList == null) return;
        mDrawerSessionList.removeAllViews();
        List<TerminalSession> sessions = mService.getSessions();
        TerminalSession current = mTerminalView.mTermSession;

        for (int i = 0; i < sessions.size(); i++) {
            final TerminalSession s = sessions.get(i);
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(12), dp(12));

            if (s == current) {
                GradientDrawable sel = new GradientDrawable();
                sel.setColor(Color.parseColor("#1A2235"));
                row.setBackground(sel);
            }

            // Session icon (circle)
            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(s.isRunning() ? Color.parseColor("#4CAF50") : Color.parseColor("#9E9E9E"));
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotLp.rightMargin = dp(10);
            dot.setLayoutParams(dotLp);
            row.addView(dot);

            // Label
            TextView label = new TextView(this);
            String name = s.mSessionName != null ? s.mSessionName : ("Session " + (i + 1));
            label.setText(name);
            label.setTextColor(s == current ? Color.parseColor("#42A5F5") : Color.parseColor("#F0F0F0"));
            label.setTextSize(14f);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);

            // Delete button
            TextView del = new TextView(this);
            del.setText("\u2715");
            del.setTextColor(Color.parseColor("#9E9E9E"));
            del.setTextSize(16f);
            del.setPadding(dp(12), 0, dp(4), 0);
            del.setOnClickListener(v -> {
                int removed = mService.removeSession(s);
                if (mService.getSessions().isEmpty()) {
                    finish();
                    return;
                }
                if (s == mTerminalView.mTermSession) {
                    int next = Math.min(removed, mService.getSessions().size() - 1);
                    switchToSession(mService.getSession(next));
                }
                refreshDrawerSessionList();
            });
            row.addView(del);

            row.setOnClickListener(v -> {
                switchToSession(s);
                mDrawerLayout.closeDrawers();
            });
            mDrawerSessionList.addView(row);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- Extra keys ----

    private void setupExtraKeys() {
        LinearLayout row1 = findViewById(R.id.keys_row1);
        LinearLayout row2 = findViewById(R.id.keys_row2);

        // Hamburger button (leftmost, row1) -> opens drawer
        KeyButton hamburger = new KeyButton(this, "\u2261", true);
        hamburger.setOnClickListener(v -> toggleDrawer());
        row1.addView(hamburger);

        String[][] r1 = {{"ESC",null},{"TAB","\t"},{"\u2191",null},{"\u2193",null},{"\u2190",null},{"\u2192",null}};
        for (String[] k : r1) {
            KeyButton btn = new KeyButton(this, k[0], true);
            final String send = k[1];
            btn.setOnClickListener(v -> onExtraKey(k[0], send));
            row1.addView(btn);
        }

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
            // Remove the dead session and switch to another, or close app if last
            runOnUiThread(() -> {
                if (mService == null) return;
                int removed = mService.removeSession(s);
                if (mService.getSessions().isEmpty()) {
                    finish();
                    return;
                }
                if (s == mTerminalView.mTermSession) {
                    int next = Math.min(removed, mService.getSessions().size() - 1);
                    switchToSession(mService.getSession(next));
                }
            });
        }
        @Override public void onCopyTextToClipboard(TerminalSession s, String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("prootz", text));
                Toast.makeText(MainActivity.this, "Copied", Toast.LENGTH_SHORT).show();
            }
        }
        @Override public void onPasteTextFromClipboard(TerminalSession s) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                if (text != null && text.length() > 0) {
                    byte[] data = text.toString().getBytes();
                    s.write(data, 0, data.length);
                }
            }
        }
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
