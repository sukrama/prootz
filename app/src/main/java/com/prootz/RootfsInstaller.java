package com.prootz;

import android.app.Activity;
import android.app.ProgressDialog;
import android.system.ErrnoException;
import android.system.Os;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads and extracts the Alpine proot rootfs on first launch.
 *
 * proot itself is shipped as a jniLibs .so (extracted & made executable by Android), so nothing
 * but the rootfs tarball needs to be fetched here. Extraction is pure-Java (XZ + a small tar
 * reader) so it does not depend on a system tar/xz/ar being present.
 */
final class RootfsInstaller {

    interface Callback {
        void onSuccess();
        void onError(String message);
    }

    private static final String ROOTFS_URL_TEMPLATE =
        "https://easycli.sh/proot-distro/alpine-%s-pd-v4.37.0.tar.xz";
    private static final int STRIP_COMPONENTS = 1;

    static File rootfsDir(Activity a) { return new File(a.getFilesDir(), "rootfs/alpine"); }
    private static File sentinel(Activity a) { return new File(rootfsDir(a), ".installed"); }

    static void reset(Activity a) {
        deleteRecursive(rootfsDir(a));
    }

    static void install(final Activity activity, final Callback callback) {
        if (sentinel(activity).isFile()) {
            callback.onSuccess();
            return;
        }

        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setMessage("Installing Alpine rootfs…");
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            try {
                doInstall(activity);
                activity.runOnUiThread(() -> {
                    dismiss(progress);
                    callback.onSuccess();
                });
            } catch (final Throwable t) {
                final String msg = describe(t);
                activity.runOnUiThread(() -> {
                    dismiss(progress);
                    callback.onError(msg);
                });
            }
        }, "rootfs-installer").start();
    }

    private static void doInstall(Activity activity) throws Exception {
        File filesDir = activity.getFilesDir();
        File tmpDir = new File(filesDir, "tmp");
        tmpDir.mkdirs();
        File rootfs = rootfsDir(activity);
        deleteRecursive(rootfs);
        rootfs.mkdirs();

        String arch = getArch();
        String url = String.format(ROOTFS_URL_TEMPLATE, arch);

        File archive = new File(tmpDir, "rootfs.tar.xz");
        download(url, archive);
        extractTarXz(archive, rootfs, STRIP_COMPONENTS);
        archive.delete();

        RootfsPatcher.patch(rootfs);

        // Mark complete only after everything succeeded; presence of the file is what matters.
        sentinel(activity).createNewFile();
    }

    private static String getArch() {
        String abi = android.os.Build.SUPPORTED_ABIS.length > 0
            ? android.os.Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        abi = abi.toLowerCase();
        if (abi.startsWith("arm64") || abi.contains("aarch64")) return "aarch64";
        if (abi.startsWith("x86_64") || abi.contains("amd64")) return "x86_64";
        if (abi.startsWith("armeabi") || abi.startsWith("arm")) return "arm";
        if (abi.startsWith("x86")) return "i686";
        return "aarch64";
    }

    private static void download(String urlStr, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.connect();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("Download failed: HTTP " + code + " for " + urlStr);
        }
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
    }

    // ---- tar.xz extraction (pure Java) ----

    private static final int BLOCK = 512;

    private static void extractTarXz(File archive, File destDir, int strip) throws Exception {
        try (InputStream in = new BufferedInputStream(
                new XZInputStream(new BufferedInputStream(new java.io.FileInputStream(archive))))) {
            byte[] header = new byte[BLOCK];
            String longName = null;
            String longLink = null;
            while (true) {
                readFully(in, header, 0, BLOCK);
                if (isAllZero(header)) break;

                String name = cstr(header, 0, 100);
                String prefix = cstr(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = octal(header, 124, 12);
                int mode = (int) octal(header, 100, 8);
                char type = (char) (header[156] & 0xff);
                String linkName = cstr(header, 157, 100);

                if (type == 'L') { longName = readString(in, size); continue; }
                if (type == 'K') { longLink = readString(in, size); continue; }
                if (type == 'x' || type == 'g') { skip(in, padded(size)); continue; }

                if (longName != null) { name = longName; longName = null; }
                if (longLink != null) { linkName = longLink; longLink = null; }

                String stripped = stripComponents(name, strip);
                if (stripped == null || stripped.isEmpty()) { skip(in, padded(size)); continue; }

                File outFile = new File(destDir, stripped);
                switch (type) {
                    case '5': // directory
                        outFile.mkdirs();
                        break;
                    case '2': // symlink
                        outFile.getParentFile().mkdirs();
                        outFile.delete();
                        try { Os.symlink(linkName, outFile.getAbsolutePath()); }
                        catch (ErrnoException ignored) {}
                        break;
                    case '1': // hardlink
                        outFile.getParentFile().mkdirs();
                        outFile.delete();
                        String target = stripComponents(linkName, strip);
                        File src = new File(destDir, target != null ? target : linkName);
                        try { Os.link(src.getAbsolutePath(), outFile.getAbsolutePath()); }
                        catch (ErrnoException e) { copyFile(src, outFile); }
                        break;
                    case '0':
                    case '\0':
                    default: // regular file
                        outFile.getParentFile().mkdirs();
                        try (OutputStream fo = new FileOutputStream(outFile)) {
                            copyExact(in, fo, size);
                        }
                        applyMode(outFile, mode);
                        skip(in, padded(size) - size);
                        continue;
                }
                skip(in, padded(size));
            }
        }
    }

    private static void applyMode(File f, int mode) {
        f.setReadable((mode & 0400) != 0, false);
        f.setWritable((mode & 0200) != 0, true);
        f.setExecutable((mode & 0100) != 0, false);
    }

    private static String stripComponents(String path, int count) {
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        if (count <= 0) return p;
        int idx = 0, seen = 0;
        for (int i = 0; i < p.length() && seen < count; i++) {
            if (p.charAt(i) == '/') { seen++; idx = i + 1; }
        }
        if (seen < count) return null;
        return p.substring(idx);
    }

    // ---- low-level helpers ----

    private static boolean isAllZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off).trim();
    }

    private static long octal(byte[] b, int off, int len) {
        String s = cstr(b, off, len).trim();
        if (s.isEmpty()) return 0L;
        try { return Long.parseLong(s, 8); } catch (NumberFormatException e) { return 0L; }
    }

    private static long padded(long size) {
        long rem = size % BLOCK;
        return rem == 0 ? size : size + (BLOCK - rem);
    }

    private static String readString(InputStream in, long size) throws Exception {
        byte[] buf = new byte[(int) size];
        readFully(in, buf, 0, buf.length);
        skip(in, padded(size) - size);
        int end = buf.length;
        while (end > 0 && (buf[end - 1] == 0 || buf[end - 1] == '\n')) end--;
        return new String(buf, 0, end);
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws Exception {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n < 0) throw new java.io.EOFException("Unexpected EOF in tar stream");
            read += n;
        }
    }

    private static void copyExact(InputStream in, OutputStream out, long size) throws Exception {
        byte[] buf = new byte[65536];
        long rem = size;
        while (rem > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, rem));
            if (n < 0) throw new java.io.EOFException("Unexpected EOF in tar entry");
            out.write(buf, 0, n);
            rem -= n;
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        dst.setExecutable(src.canExecute(), false);
    }

    private static void skip(InputStream in, long n) throws Exception {
        long rem = n;
        byte[] buf = new byte[8192];
        while (rem > 0) {
            long s = in.skip(rem);
            if (s > 0) { rem -= s; continue; }
            int r = in.read(buf, 0, (int) Math.min(buf.length, rem));
            if (r < 0) break;
            rem -= r;
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File c : kids) deleteRecursive(c);
        }
        f.delete();
    }

    private static void dismiss(ProgressDialog d) {
        try { d.dismiss(); } catch (RuntimeException ignored) {}
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(String.valueOf(t.getMessage()));
        Throwable c = t.getCause();
        if (c != null) sb.append("\nCaused by: ").append(c);
        return sb.toString();
    }

    private RootfsInstaller() {}
}
