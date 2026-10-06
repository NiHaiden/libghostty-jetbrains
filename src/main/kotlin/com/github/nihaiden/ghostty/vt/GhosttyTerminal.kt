package com.github.nihaiden.ghostty.vt

import com.github.nihaiden.ghostty.vt.GhosttyJb.Companion as J
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A libghostty-vt terminal (via the libghostty-jb facade).
 *
 * Thread-safe: every native call is serialized by an internal lock. Events are
 * delivered to [Listener] synchronously on the thread that triggered them
 * (usually the pty reader thread inside [write]) while the lock is held, so
 * listeners must not block and must not call back into this terminal.
 */
class GhosttyTerminal(cols: Int, rows: Int, private val listener: Listener) : AutoCloseable {

    interface Listener {
        /** Bytes the terminal wants written to the pty (query replies, pastes). */
        fun onWritePty(data: ByteArray) {}
        fun onBell() {}
        fun onTitleChanged(title: String) {}
        fun onPwdChanged(pwd: String) {}
        /** A program asked to set the clipboard (OSC 52). Return true to allow. */
        fun onClipboardWrite(text: String, primary: Boolean): Boolean = false
        fun onNotification(title: String, body: String) {}
        /** OSC 9;4. state: 0 remove, 1 set, 2 error, 3 indeterminate, 4 pause; progress null if absent. */
        fun onProgress(state: Int, progress: Int?) {}
        fun onCommandFinished(exitCode: Int?) {}
    }

    private val lib = NativeLibrary.get()
    private val lock = ReentrantLock()
    private var handle: Pointer?

    // Must stay strongly reachable for as long as native code may call it.
    private val callback = GhosttyJb.EventCallback { _, event, value, data, len ->
        try {
            dispatch(event, value, data, len)
        } catch (_: Throwable) {
            0
        }
    }

    private var frameMem = Memory(4L * (J.HEADER_INTS + 200 * 60 * J.CELL_INTS + 1024))
    private val encodeBuf = ByteArray(256)

    init {
        handle = lib.gjb_new(cols, rows, callback, null)
            ?: throw OutOfMemoryError("ghostty_jb: failed to create terminal")
    }

    private fun dispatch(event: Int, value: Int, data: Pointer?, len: Int): Int {
        val bytes = if (data != null && len > 0) data.getByteArray(0, len) else EMPTY
        when (event) {
            J.EVENT_WRITE_PTY -> listener.onWritePty(bytes)
            J.EVENT_BELL -> listener.onBell()
            J.EVENT_TITLE -> listener.onTitleChanged(bytes.decodeToString())
            J.EVENT_PWD -> listener.onPwdChanged(bytes.decodeToString())
            J.EVENT_CLIPBOARD_WRITE ->
                return if (listener.onClipboardWrite(bytes.decodeToString(), value != 0)) 1 else 0
            J.EVENT_NOTIFICATION -> {
                val nul = bytes.indexOf(0)
                val title = if (nul >= 0) bytes.copyOfRange(0, nul).decodeToString() else bytes.decodeToString()
                val body = if (nul >= 0) bytes.copyOfRange(nul + 1, bytes.size).decodeToString() else ""
                listener.onNotification(title, body)
            }
            J.EVENT_PROGRESS -> {
                val progress = (value and 0xff).toByte().toInt()
                listener.onProgress(value shr 8, if (progress < 0) null else progress)
            }
            J.EVENT_COMMAND_FINISHED ->
                listener.onCommandFinished(if (value == Int.MIN_VALUE) null else value)
        }
        return 0
    }

    private inline fun <T> locked(default: T, block: (Pointer) -> T): T = lock.withLock {
        val h = handle ?: return default
        block(h)
    }

    val isClosed: Boolean get() = lock.withLock { handle == null }

    override fun close() {
        lock.withLock {
            handle?.let { lib.gjb_free(it) }
            handle = null
        }
    }

    // ---- Output from the pty ------------------------------------------------

    fun write(data: ByteArray, length: Int = data.size) {
        if (length <= 0) return
        locked(Unit) { lib.gjb_write(it, data, length) }
    }

    fun reset() = locked(Unit) { lib.gjb_reset(it) }

    // ---- Geometry & configuration -------------------------------------------

    fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int, padLeft: Int, padTop: Int) =
        locked(Unit) { lib.gjb_resize(it, cols, rows, cellWidth, cellHeight, padLeft, padTop) }

    /**
     * Sets default colors (0xRRGGBB, or -1 for the built-in default). When
     * [palette] has fewer than 256 entries and [generate256] is set, the
     * remaining entries are derived from the first 16.
     */
    fun setColors(fg: Int, bg: Int, cursor: Int, palette: IntArray?, generate256: Boolean = true) =
        locked(Unit) {
            lib.gjb_set_colors(it, fg, bg, cursor, palette, palette?.size ?: 0, if (generate256) 1 else 0)
        }

    fun setOption(option: Int, value: Long) = locked(Unit) { lib.gjb_set_option(it, option, value) }

    fun invalidate() = locked(Unit) { lib.gjb_invalidate(it) }

    // ---- Queries ------------------------------------------------------------

    fun query(what: Int, arg: Int = 0): Long = locked(0L) { lib.gjb_query(it, what, arg) }

    val isMouseTracking: Boolean get() = query(J.Q_MOUSE_TRACKING) != 0L
    val isAltScreen: Boolean get() = query(J.Q_ALT_SCREEN) != 0L
    val isViewportAtBottom: Boolean get() = query(J.Q_VIEWPORT_AT_BOTTOM) != 0L
    val hasSelection: Boolean get() = query(J.Q_HAS_SELECTION) != 0L
    fun mode(decMode: Int): Boolean = query(J.Q_MODE, decMode) != 0L

    val title: String get() = queryString { h, buf, cap -> lib.gjb_query_string(h, 1, buf, cap) } ?: ""
    val pwd: String get() = queryString { h, buf, cap -> lib.gjb_query_string(h, 2, buf, cap) } ?: ""

    // ---- Viewport -----------------------------------------------------------

    fun scrollToTop() = locked(Unit) { lib.gjb_scroll(it, J.SCROLL_TOP, 0) }
    fun scrollToBottom() = locked(Unit) { lib.gjb_scroll(it, J.SCROLL_BOTTOM, 0) }
    fun scrollBy(rows: Int) = locked(Unit) { lib.gjb_scroll(it, J.SCROLL_DELTA, rows.toLong()) }
    fun scrollToRow(row: Long) = locked(Unit) { lib.gjb_scroll(it, J.SCROLL_ROW, row) }

    // ---- Frames -------------------------------------------------------------

    /** Captures the viewport. Returns null when nothing changed since the last frame. */
    fun snapshot(): Frame? = locked(null) { h ->
        var n = lib.gjb_snapshot(h, frameMem, (frameMem.size() / 4).toInt())
        if (n < 0) {
            val need = (-n).toLong()
            frameMem = Memory(4L * (need + need / 4 + 1024))
            n = lib.gjb_snapshot(h, frameMem, (frameMem.size() / 4).toInt())
            if (n < 0) return@locked null
        }
        if (frameMem.getInt(4L * J.H_DIRTY) == 0) return@locked null
        val data = IntArray(n)
        frameMem.read(0, data, 0, n)
        Frame(data)
    }

    // ---- Input --------------------------------------------------------------

    /** Encodes a key event; returns the bytes for the pty (possibly empty). */
    fun encodeKey(action: Int, key: Int, mods: Int, consumedMods: Int, unshifted: Int, text: String?): ByteArray =
        locked(EMPTY) { h ->
            val utf8 = text?.takeIf { it.isNotEmpty() }?.encodeToByteArray()
            val n = lib.gjb_encode_key(
                h, action, key, mods, consumedMods, unshifted,
                utf8, utf8?.size ?: 0, encodeBuf, encodeBuf.size,
            )
            if (n > 0) encodeBuf.copyOf(n) else EMPTY
        }

    fun encodeMouse(action: Int, button: Int, mods: Int, x: Float, y: Float, anyButtonPressed: Boolean): ByteArray =
        locked(EMPTY) { h ->
            val n = lib.gjb_encode_mouse(h, action, button, mods, x, y, if (anyButtonPressed) 1 else 0, encodeBuf, encodeBuf.size)
            if (n > 0) encodeBuf.copyOf(n) else EMPTY
        }

    fun encodeFocus(gained: Boolean): ByteArray = locked(EMPTY) { h ->
        val n = lib.gjb_encode_focus(h, if (gained) 1 else 0, encodeBuf, encodeBuf.size)
        if (n > 0) encodeBuf.copyOf(n) else EMPTY
    }

    enum class PasteResult { OK, UNSAFE, ERROR }

    /** Pastes text; the encoded bytes arrive via [Listener.onWritePty]. */
    fun paste(text: String, allowUnsafe: Boolean): PasteResult = locked(PasteResult.ERROR) { h ->
        val utf8 = text.encodeToByteArray()
        when (lib.gjb_paste(h, utf8, utf8.size, if (allowUnsafe) 1 else 0)) {
            0 -> PasteResult.OK
            1 -> PasteResult.UNSAFE
            else -> PasteResult.ERROR
        }
    }

    // ---- Selection ----------------------------------------------------------

    /** See GJB_SEL_* / GJB_SEL_R_* in ghostty_jb.h. */
    fun selectEvent(type: Int, x: Double, y: Double, timeNs: Long, rectangle: Boolean): Int =
        locked(0) { lib.gjb_select_event(it, type, x, y, timeNs, if (rectangle) 1 else 0) }

    fun clearSelection() = locked(Unit) { lib.gjb_select_clear(it) }
    fun selectAll() = locked(Unit) { lib.gjb_select_all(it) }

    val selectionText: String?
        get() = queryString { h, buf, cap -> lib.gjb_selection_text(h, buf, cap) }

    val screenText: String
        get() = queryString { h, buf, cap -> lib.gjb_screen_text(h, buf, cap) } ?: ""

    fun hyperlinkAt(col: Int, row: Int): String? =
        queryString { h, buf, cap -> lib.gjb_hyperlink_at(h, col, row, buf, cap) }?.takeIf { it.isNotEmpty() }

    // ---- Search -------------------------------------------------------------

    fun setSearch(needle: String?) = locked(Unit) { h ->
        val utf8 = needle?.takeIf { it.isNotEmpty() }?.encodeToByteArray()
        lib.gjb_search_set(h, utf8, utf8?.size ?: 0)
    }

    /** Selects the next (older, dir > 0) or previous match. Returns its index or -1. */
    fun searchSelect(dir: Int): Int = locked(-1) { lib.gjb_search_select(it, dir) }

    // ---- Helpers ------------------------------------------------------------

    /** Calls a "copy string into (buf, cap), return full length or -1" native function. */
    private inline fun queryString(fn: (Pointer, ByteArray?, Int) -> Int): String? = locked(null) { h ->
        var buf = ByteArray(1024)
        var n = fn(h, buf, buf.size)
        if (n < 0) return@locked null
        if (n > buf.size) {
            buf = ByteArray(n)
            n = fn(h, buf, buf.size)
            if (n < 0) return@locked null
        }
        String(buf, 0, minOf(n, buf.size), Charsets.UTF_8)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
