package com.github.nihaiden.ghostty.vt

/**
 * JNI entry points implemented in Rust (native/src/jni_api.rs). Use
 * [GhosttyTerminal] instead; this is the raw surface.
 *
 * Terminals are opaque ids, never pointers: a stale id makes the native side
 * throw [IllegalStateException] rather than touch freed memory.
 */
object GhosttyNative {
    /** Receives terminal events; see `GhosttyJb.EVENT_*`. */
    fun interface EventSink {
        fun onEvent(type: Int, value: Int, data: ByteArray?)
    }

    @JvmStatic external fun abiVersion(): Int

    /** [listener] must be an [EventSink]; typed as Any to keep the JNI signature simple. */
    @JvmStatic external fun create(cols: Int, rows: Int, listener: Any): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun write(handle: Long, data: ByteArray, offset: Int, len: Int)
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun resize(handle: Long, cols: Int, rows: Int, cellW: Int, cellH: Int, padLeft: Int, padTop: Int): Boolean
    @JvmStatic external fun setColors(handle: Long, fg: Int, bg: Int, cursor: Int, palette: IntArray?, generate256: Boolean)
    @JvmStatic external fun setOption(handle: Long, option: Int, value: Long): Boolean
    @JvmStatic external fun query(handle: Long, what: Int, arg: Int): Long
    /** what: 1 title, 2 working directory. */
    @JvmStatic external fun queryString(handle: Long, what: Int): String?
    @JvmStatic external fun scroll(handle: Long, kind: Int, value: Long)
    @JvmStatic external fun invalidate(handle: Long)
    /** The next frame, or null when nothing changed. */
    @JvmStatic external fun snapshot(handle: Long): IntArray?
    @JvmStatic external fun encodeKey(handle: Long, action: Int, key: Int, mods: Int, consumed: Int, unshifted: Int, text: String?): ByteArray
    @JvmStatic external fun encodeMouse(handle: Long, action: Int, button: Int, mods: Int, x: Float, y: Float, anyPressed: Boolean): ByteArray
    @JvmStatic external fun encodeFocus(handle: Long, gained: Boolean): ByteArray
    /** 0 ok, 1 rejected as unsafe, -1 error. */
    @JvmStatic external fun paste(handle: Long, text: String, allowUnsafe: Boolean): Int
    @JvmStatic external fun selectEvent(handle: Long, kind: Int, x: Double, y: Double, timeNs: Long, rectangle: Boolean): Int
    @JvmStatic external fun selectClear(handle: Long)
    @JvmStatic external fun selectAll(handle: Long)
    @JvmStatic external fun selectionText(handle: Long): String?
    @JvmStatic external fun screenText(handle: Long): String?
    @JvmStatic external fun hyperlinkAt(handle: Long, col: Int, row: Int): String?
    @JvmStatic external fun searchSet(handle: Long, needle: String?): Boolean
    @JvmStatic external fun searchSelect(handle: Long, dir: Int): Int
}
