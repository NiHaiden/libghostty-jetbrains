// JNI smoke test of a shipped native library without Gradle or the IntelliJ
// test harness (which doesn't run on every platform, e.g. Windows arm64):
//
//   java native/smoke/GhosttyNative.java native/dist/<platform>/<library>
//
// Declares the same class and native signatures as the plugin's
// src/main/kotlin/.../vt/GhosttyNative.kt, so the exported symbols resolve.
package com.github.nihaiden.ghostty.vt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class GhosttyNative {
    static native int abiVersion();
    static native long create(int cols, int rows, Object listener);
    static native void destroy(long handle);
    static native void write(long handle, byte[] data, int offset, int len);
    static native int[] snapshot(long handle);
    static native String screenText(long handle);
    static native byte[] encodeKey(long handle, int action, int key, int mods, int consumed, int unshifted, String text);

    public static final class Sink {
        final ByteArrayOutputStream pty = new ByteArrayOutputStream();
        public void onEvent(int type, int value, byte[] data) {
            if (type == 1 && data != null) pty.writeBytes(data); // WRITE_PTY
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("ok   " + what);
    }

    public static void main(String[] args) {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        check(abiVersion() == 2, "abi version");
        Sink sink = new Sink();
        long t = create(20, 5, sink);
        byte[] out = "hello \u001b[1mghostty\u001b[0m\r\n\u001b[c".getBytes(StandardCharsets.UTF_8);
        write(t, out, 0, out.length);
        check(screenText(t).startsWith("hello ghostty"), "screen text");
        int[] frame = snapshot(t);
        check(frame != null && frame[0] == 0x474a4231 && frame[1] == 20 && frame[2] == 5, "frame header");
        check(sink.pty.toString(StandardCharsets.UTF_8).equals("\u001b[?62;22c"), "device attributes reply");
        check(new String(encodeKey(t, 1, 58 /* GHOSTTY_KEY_ENTER */, 0, 0, 0, null)).equals("\r"), "key encoding");
        destroy(t);
        try {
            screenText(t);
            check(false, "stale handle rejected");
        } catch (IllegalStateException e) {
            check(true, "stale handle rejected");
        }
    }
}
