package com.prootz;

import android.app.Activity;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class RootfsInstaller {

    enum Distro {
        ALPINE("Alpine Linux",
            "https://easycli.sh/proot-distro/alpine-%s-pd-v4.37.0.tar.xz",
            "alpine", "/bin/ash"),
        DEBIAN("Debian (Trixie)",
            "https://easycli.sh/proot-distro/debian-trixie-%s-pd-v4.37.0.tar.xz",
            "debian", "/bin/bash"),
        UBUNTU("Ubuntu (25.10)",
            "https://easycli.sh/proot-distro/ubuntu-questing-%s-pd-v4.37.0.tar.xz",
            "ubuntu", "/bin/bash");

        final String displayName;
        final String urlTemplate;
        final String dirName;
        final String shell;

        Distro(String displayName, String urlTemplate, String dirName, String shell) {
            this.displayName = displayName;
            this.urlTemplate = urlTemplate;
            this.dirName = dirName;
            this.shell = shell;
        }
    }

    interface Callback {
        void onSuccess(Distro distro);
        void onError(String message);
    }

    interface ProgressCallback {
        void onProgress(String stage, int pct, String detail);
    }

    static File rootfsDir(Activity a, Distro d) {
        return new File(a.getFilesDir(), "rootfs/" + d.dirName);
    }

    static File sentinel(Activity a, Distro d) {
        return new File(rootfsDir(a, d), ".installed");
    }

    static Distro installedDistro(Activity a) {
        for (Distro d : Distro.values()) {
            if (sentinel(a, d).isFile()) return d;
        }
        return null;
    }

    static void reset(Activity a, Distro d) {
        deleteRecursive(rootfsDir(a, d));
    }

    static void install(Activity activity, Distro distro, ProgressCallback progress, Callback callback) {
        new Thread(() -> {
            try {
                doInstall(activity, distro, progress);
                activity.runOnUiThread(() -> callback.onSuccess(distro));
            } catch (Throwable t) {
                String msg = describe(t);
                activity.runOnUiThread(() -> callback.onError(msg));
            }
        }, "rootfs-installer").start();
    }

    private static void doInstall(Activity activity, Distro distro, ProgressCallback progress) throws Exception {
        File filesDir = activity.getFilesDir();
        File tmpDir = new File(filesDir, "tmp");
        tmpDir.mkdirs();
        File rootfs = rootfsDir(activity, distro);
        deleteRecursive(rootfs);
        rootfs.mkdirs();

        String arch = getArch();
        String url = String.format(distro.urlTemplate, arch);
        File archive = new File(tmpDir, "rootfs.tar.xz");

        // Download
        downloadWithProgress(url, archive, progress);

        // Extract
        extractTarXz(archive, rootfs, 1, progress);
        archive.delete();

        // Patch
        progress.onProgress("Patching rootfs…", 98, "resolv.conf, hosts, tmp");
        RootfsPatcher.patch(rootfs);

        sentinel(activity, distro).createNewFile();
        progress.onProgress("Done", 100, "");
    }

    private static void downloadWithProgress(String urlStr, File dest, ProgressCallback cb) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.connect();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300)
            throw new RuntimeException("HTTP " + code + " for " + urlStr);
        long total = conn.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            long read = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                int pct = total > 0 ? (int) (read * 100 / total) : -1;
                String detail = formatBytes(read) + (total > 0 ? " / " + formatBytes(total) : "");
                cb.onProgress("Downloading…", pct, detail);
            }
        } finally {
            conn.disconnect();
        }
    }

    // ---- tar.xz extraction ----

    private static final int BLOCK = 512;

    private static void extractTarXz(File archive, File destDir, int strip, ProgressCallback cb) throws Exception {
        long archiveSize = archive.length();
        CountingInputStream counting =
                new CountingInputStream(new BufferedInputStream(new FileInputStream(archive)));
        try (InputStream in = new BufferedInputStream(new XZInputStream(counting))) {
            byte[] header = new byte[BLOCK];
            String longName = null, longLink = null;
            long entries = 0;
            long lastEmit = 0;
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
                    case '5':
                        outFile.mkdirs();
                        skip(in, padded(size));
                        break;
                    case '2':
                        outFile.getParentFile().mkdirs();
                        outFile.delete();
                        try { Os.symlink(linkName, outFile.getAbsolutePath()); }
                        catch (ErrnoException ignored) {}
                        skip(in, padded(size));
                        break;
                    case '1':
                        outFile.getParentFile().mkdirs();
                        outFile.delete();
                        String tgt = stripComponents(linkName, strip);
                        File src = new File(destDir, tgt != null ? tgt : linkName);
                        try { Os.link(src.getAbsolutePath(), outFile.getAbsolutePath()); }
                        catch (ErrnoException e) { if (src.exists()) copyFile(src, outFile); }
                        skip(in, padded(size));
                        break;
                    default:
                        outFile.getParentFile().mkdirs();
                        try (OutputStream fo = new FileOutputStream(outFile)) {
                            copyExact(in, fo, size);
                        }
                        applyMode(outFile, mode);
                        skip(in, padded(size) - size);
                        break;
                }

                entries++;
                long now = System.currentTimeMillis();
                if (now - lastEmit >= 50) {
                    lastEmit = now;
                    int pct = archiveSize > 0
                            ? (int) Math.min(100, counting.count() * 100 / archiveSize) : -1;
                    String shortPath = stripped.length() > 42
                            ? "..." + stripped.substring(stripped.length() - 40) : stripped;
                    cb.onProgress("Extracting...", pct, entries + " files  -  " + shortPath);
                }
            }
            cb.onProgress("Extracting...", 100, entries + " files extracted");
        }
    }

    /** Counts bytes pulled from the underlying (compressed) stream, for extraction progress. */
    private static final class CountingInputStream extends java.io.FilterInputStream {
        private long count = 0;
        CountingInputStream(InputStream in) { super(in); }
        long count() { return count; }
        @Override public int read() throws java.io.IOException {
            int r = super.read();
            if (r >= 0) count++;
            return r;
        }
        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            int r = super.read(b, off, len);
            if (r > 0) count += r;
            return r;
        }
        @Override public long skip(long n) throws java.io.IOException {
            long r = super.skip(n);
            if (r > 0) count += r;
            return r;
        }
    }

    static String getArch() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        abi = abi.toLowerCase();
        if (abi.startsWith("arm64") || abi.contains("aarch64")) return "aarch64";
        if (abi.startsWith("x86_64") || abi.contains("amd64")) return "x86_64";
        if (abi.startsWith("armeabi") || abi.startsWith("arm")) return "arm";
        if (abi.startsWith("x86")) return "i686";
        return "aarch64";
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
        return seen < count ? null : p.substring(idx);
    }

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
            if (n < 0) throw new java.io.EOFException("Unexpected EOF");
            read += n;
        }
    }

    private static void copyExact(InputStream in, OutputStream out, long size) throws Exception {
        byte[] buf = new byte[65536];
        long rem = size;
        while (rem > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, rem));
            if (n < 0) throw new java.io.EOFException("Unexpected EOF in entry");
            out.write(buf, 0, n);
            rem -= n;
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
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

    static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File c : kids) deleteRecursive(c);
        }
        f.delete();
    }

    private static String formatBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format("%.1f MB", b / 1048576.0);
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(String.valueOf(t.getMessage()));
        Throwable c = t.getCause();
        if (c != null) sb.append("\nCaused by: ").append(c);
        return sb.toString();
    }

    private RootfsInstaller() {}
}
