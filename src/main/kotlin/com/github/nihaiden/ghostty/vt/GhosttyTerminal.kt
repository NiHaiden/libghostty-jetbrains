package com.github.nihaiden.ghostty.vt

import com.github.nihaiden.ghostty.vt.GhosttyJb as J
import com.github.nihaiden.ghostty.vt.GhosttyNative as N
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A libghostty-vt terminal (via the Rust bridge in native/).
 *
 * Thread-safe: every native call is serialized by an internal lock. Events are
 * delivered to [Listener] synchronously on the thread that triggered them
 * (usually the pty reader thread inside [write]), so listeners must not block.
 */
class GhosttyTerminal(cols: Int, rows: Int, private val listener: Listener) : AutoCloseable {

    interface Listener {
        /** Bytes the terminal wants written to the pty (query replies, pastes). */
        fun onWritePty(data: ByteArray) {}
        fun onBell() {}
        fun onTitleChanged(title: String) {}
        fun onPwdChanged(pwd: String) {}
        /**
         * A program set the clipboard (OSC 52). Only reported while
         * [GhosttyJb.OPT_ALLOW_CLIPBOARD_WRITE] is on; the program has
         * already been told it succeeded.
         */
        fun onClipboardWrite(text: String, primary: Boolean) {}
        fun onNotification(title: String, body: String) {}
        /** OSC 9;4. state: 0 remove, 1 set, 2 error, 3 indeterminate, 4 pause; progress null if absent. */
        fun onProgress(state: Int, progress: Int?) {}
        fun onCommandFinished(exitCode: Int?) {}
    }

    private val lock = ReentrantLock()
    private var handle: Long

    init {
        NativeLibrary.ensureLoaded()
        handle = N.create(cols, rows, N.EventSink { type, value, data ->
            try {
                dispatch(type, value, data ?: EMPTY)
            } catch (_: Throwable) {
                // Never let a listener failure unwind through native code.
            }
        })
    }

    private fun dispatch(event: Int, value: Int, bytes: ByteArray) {
        when (event) {
            J.EVENT_WRITE_PTY -> listener.onWritePty(bytes)
            J.EVENT_BELL -> listener.onBell()
            J.EVENT_TITLE -> listener.onTitleChanged(bytes.decodeToString())
            J.EVENT_PWD -> listener.onPwdChanged(bytes.decodeToString())
            J.EVENT_CLIPBOARD_WRITE -> listener.onClipboardWrite(bytes.decodeToString(), value != 0)
            J.EVENT_NOTIFICATION -> {
                val nul = bytes.indexOf(0)
                val title = if (nul >= 0) bytes.copyOfRange(0, nul).decodeToString() else bytes.decodeToString()
                val body = if (nul >= 0) bytes.copyOfRange(nul + 1, bytes.size).decodeToString() else ""
                listener.onNotification(title, body)
            }
            J.EVENT_PROGRESS -> {
                val progress = value and 0xff
                listener.onProgress(value shr 8, if (progress == 0xff) null else progress)
            }
            J.EVENT_COMMAND_FINISHED ->
                listener.onCommandFinished(if (value == Int.MIN_VALUE) null else value)
        }
    }

    private inline fun <T> locked(default: T, block: (Long) -> T): T = lock.withLock {
        val h = handle
        if (h == 0L) return default
        block(h)
    }

    val isClosed: Boolean get() = lock.withLock { handle == 0L }

    override fun close() {
        lock.withLock {
            if (handle != 0L) N.destroy(handle)
            handle = 0L
        }
    }

    // ---- Output from the pty ------------------------------------------------

    fun write(data: ByteArray, length: Int = data.size) {
        if (length <= 0) return
        locked(Unit) { N.write(it, data, 0, length) }
    }

    fun reset() = locked(Unit) { N.reset(it) }

    // ---- Geometry & configuration -------------------------------------------

    fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int, padLeft: Int, padTop: Int) {
        locked(Unit) { N.resize(it, cols, rows, cellWidth, cellHeight, padLeft, padTop) }
    }

    /**
     * Sets default colors (0xRRGGBB, or -1 for the built-in default). When
     * [palette] has fewer than 256 entries and [generate256] is set, the
     * remaining entries are derived from the first 16.
     */
    fun setColors(fg: Int, bg: Int, cursor: Int, palette: IntArray?, generate256: Boolean = true) =
        locked(Unit) { N.setColors(it, fg, bg, cursor, palette, generate256) }

    fun setOption(option: Int, value: Long) {
        locked(Unit) { N.setOption(it, option, value) }
    }

    fun invalidate() = locked(Unit) { N.invalidate(it) }

    // ---- Queries ------------------------------------------------------------

    fun query(what: Int, arg: Int = 0): Long = locked(0L) { N.query(it, what, arg) }

    val isMouseTracking: Boolean get() = query(J.Q_MOUSE_TRACKING) != 0L
    val isAltScreen: Boolean get() = query(J.Q_ALT_SCREEN) != 0L
    val isViewportAtBottom: Boolean get() = query(J.Q_VIEWPORT_AT_BOTTOM) != 0L
    val hasSelection: Boolean get() = query(J.Q_HAS_SELECTION) != 0L
    fun mode(decMode: Int): Boolean = query(J.Q_MODE, decMode) != 0L

    val title: String get() = locked(null) { N.queryString(it, 1) } ?: ""
    val pwd: String get() = locked(null) { N.queryString(it, 2) } ?: ""

    // ---- Viewport -----------------------------------------------------------

    fun scrollToTop() = locked(Unit) { N.scroll(it, J.SCROLL_TOP, 0) }
    fun scrollToBottom() = locked(Unit) { N.scroll(it, J.SCROLL_BOTTOM, 0) }
    fun scrollBy(rows: Int) = locked(Unit) { N.scroll(it, J.SCROLL_DELTA, rows.toLong()) }
    fun scrollToRow(row: Long) = locked(Unit) { N.scroll(it, J.SCROLL_ROW, row) }

    // ---- Frames -------------------------------------------------------------

    /** Captures the viewport. Returns null when nothing changed since the last frame. */
    fun snapshot(): Frame? = locked(null) { N.snapshot(it) }?.let(::Frame)

    // ---- Input --------------------------------------------------------------

    /** Encodes a key event; returns the bytes for the pty (possibly empty). */
    fun encodeKey(action: Int, key: Int, mods: Int, consumedMods: Int, unshifted: Int, text: String?): ByteArray =
        locked(EMPTY) { N.encodeKey(it, action, key, mods, consumedMods, unshifted, text) }

    fun encodeMouse(action: Int, button: Int, mods: Int, x: Float, y: Float, anyButtonPressed: Boolean): ByteArray =
        locked(EMPTY) { N.encodeMouse(it, action, button, mods, x, y, anyButtonPressed) }

    fun encodeFocus(gained: Boolean): ByteArray = locked(EMPTY) { N.encodeFocus(it, gained) }

    enum class PasteResult { OK, UNSAFE, ERROR }

    /** Pastes text; the encoded bytes arrive via [Listener.onWritePty]. */
    fun paste(text: String, allowUnsafe: Boolean): PasteResult = locked(PasteResult.ERROR) {
        when (N.paste(it, text, allowUnsafe)) {
            0 -> PasteResult.OK
            1 -> PasteResult.UNSAFE
            else -> PasteResult.ERROR
        }
    }

    // ---- Selection ----------------------------------------------------------

    /** type is one of `GhosttyJb.SEL_*`; returns `GhosttyJb.SEL_R_*` bits. */
    fun selectEvent(type: Int, x: Double, y: Double, timeNs: Long, rectangle: Boolean): Int =
        locked(0) { N.selectEvent(it, type, x, y, timeNs, rectangle) }

    fun clearSelection() = locked(Unit) { N.selectClear(it) }
    fun selectAll() = locked(Unit) { N.selectAll(it) }

    val selectionText: String? get() = locked(null) { N.selectionText(it) }

    val screenText: String get() = locked(null) { N.screenText(it) } ?: ""

    fun hyperlinkAt(col: Int, row: Int): String? = locked(null) { N.hyperlinkAt(it, col, row) }

    // ---- Search -------------------------------------------------------------

    fun setSearch(needle: String?) {
        locked(Unit) { N.searchSet(it, needle) }
    }

    /** Selects the next (older, dir > 0) or previous match. Returns its index or -1. */
    fun searchSelect(dir: Int): Int = locked(-1) { N.searchSelect(it, dir) }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
