package com.prootz;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Minimal post-extraction fixups so the rootfs has working DNS, hosts and writable temp dirs. */
final class RootfsPatcher {

    static void patch(File rootfs) {
        File etc = new File(rootfs, "etc");
        etc.mkdirs();

        write(new File(etc, "resolv.conf"),
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");

        write(new File(etc, "hosts"),
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");

        // proot needs these to exist and be writable.
        for (String p : new String[]{"tmp", "var/tmp", "root", "dev", "proc", "sys"}) {
            File d = new File(rootfs, p);
            d.mkdirs();
        }
        File tmp = new File(rootfs, "tmp");
        tmp.setReadable(true, false);
        tmp.setWritable(true, false);
        tmp.setExecutable(true, false);
    }

    private static void write(File f, String content) {
        try {
            f.getParentFile().mkdirs();
            // Replace symlinked resolv.conf (Alpine ships it as a symlink) with a real file.
            f.delete();
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(content.getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
    }

    private RootfsPatcher() {}
}
