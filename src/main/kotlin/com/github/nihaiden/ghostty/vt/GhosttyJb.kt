package com.github.nihaiden.ghostty.vt

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer

/**
 * JNA binding for native/src/ghostty_jb.h. Constants mirror the header; keep
 * them in sync (ABI_VERSION is checked at load time).
 */
@Suppress("FunctionName")
internal interface GhosttyJb : Library {
    fun interface EventCallback : Callback {
        fun invoke(userdata: Pointer?, event: Int, value: Int, data: Pointer?, len: Int): Int
    }

    fun gjb_abi_version(): Int
    fun gjb_new(cols: Int, rows: Int, cb: EventCallback, userdata: Pointer?): Pointer?
    fun gjb_free(t: Pointer)
    fun gjb_write(t: Pointer, data: ByteArray, len: Int)
    fun gjb_reset(t: Pointer)
    fun gjb_resize(t: Pointer, cols: Int, rows: Int, cellW: Int, cellH: Int, padLeft: Int, padTop: Int): Int
    fun gjb_set_colors(t: Pointer, fg: Int, bg: Int, cursor: Int, palette: IntArray?, paletteLen: Int, generate256: Int)
    fun gjb_set_option(t: Pointer, option: Int, value: Long): Int
    fun gjb_query(t: Pointer, what: Int, arg: Int): Long
    fun gjb_query_string(t: Pointer, what: Int, out: ByteArray?, cap: Int): Int
    fun gjb_scroll(t: Pointer, kind: Int, value: Long)
    fun gjb_snapshot(t: Pointer, buf: Pointer, cap: Int): Int
    fun gjb_invalidate(t: Pointer)
    fun gjb_encode_key(
        t: Pointer, action: Int, key: Int, mods: Int, consumedMods: Int, unshifted: Int,
        utf8: ByteArray?, utf8Len: Int, out: ByteArray, cap: Int,
    ): Int
    fun gjb_encode_mouse(
        t: Pointer, action: Int, button: Int, mods: Int, x: Float, y: Float,
        anyButtonPressed: Int, out: ByteArray, cap: Int,
    ): Int
    fun gjb_encode_focus(t: Pointer, gained: Int, out: ByteArray, cap: Int): Int
    fun gjb_paste(t: Pointer, utf8: ByteArray, len: Int, allowUnsafe: Int): Int
    fun gjb_select_event(t: Pointer, type: Int, x: Double, y: Double, timeNs: Long, rectangle: Int): Int
    fun gjb_select_clear(t: Pointer)
    fun gjb_select_all(t: Pointer)
    fun gjb_selection_text(t: Pointer, out: ByteArray?, cap: Int): Int
    fun gjb_screen_text(t: Pointer, out: ByteArray?, cap: Int): Int
    fun gjb_hyperlink_at(t: Pointer, col: Int, row: Int, out: ByteArray?, cap: Int): Int
    fun gjb_search_set(t: Pointer, utf8: ByteArray?, len: Int): Int
    fun gjb_search_select(t: Pointer, dir: Int): Int

    companion object {
        const val ABI_VERSION = 1

        const val EVENT_WRITE_PTY = 1
        const val EVENT_BELL = 2
        const val EVENT_TITLE = 3
        const val EVENT_PWD = 4
        const val EVENT_CLIPBOARD_WRITE = 5
        const val EVENT_NOTIFICATION = 6
        const val EVENT_PROGRESS = 7
        const val EVENT_COMMAND_FINISHED = 8

        const val OPT_SCROLLBACK_BYTES = 1
        const val OPT_CURSOR_STYLE = 2
        const val OPT_CURSOR_BLINK = 3
        const val OPT_RESIZE_PULL_SCROLLBACK = 4
        const val OPT_OPTION_AS_ALT = 5
        const val OPT_TITLE_REPORT = 6
        const val OPT_COLOR_SCHEME = 7
        const val OPT_FOCUSED = 8
        const val OPT_CLICK_INTERVAL_MS = 9

        const val Q_COLS = 1
        const val Q_ROWS = 2
        const val Q_MOUSE_TRACKING = 3
        const val Q_ALT_SCREEN = 4
        const val Q_VIEWPORT_AT_BOTTOM = 5
        const val Q_MODE = 6
        const val Q_SCROLLBACK_ROWS = 7
        const val Q_CURSOR_X = 8
        const val Q_CURSOR_Y = 9
        const val Q_HAS_SELECTION = 10
        const val Q_MOUSE_SHAPE = 11

        const val SCROLL_TOP = 0
        const val SCROLL_BOTTOM = 1
        const val SCROLL_DELTA = 2
        const val SCROLL_ROW = 3

        const val HEADER_INTS = 32
        const val CELL_INTS = 5

        const val H_MAGIC = 0
        const val H_COLS = 1
        const val H_ROWS = 2
        const val H_DIRTY = 3
        const val H_FG = 4
        const val H_BG = 5
        const val H_CURSOR_COLOR = 6
        const val H_CURSOR_FLAGS = 7
        const val H_CURSOR_X = 8
        const val H_CURSOR_Y = 9
        const val H_CURSOR_STYLE = 10
        const val H_SCROLL_TOTAL = 11
        const val H_SCROLL_OFFSET = 12
        const val H_SCROLL_LEN = 13
        const val H_FLAGS = 14
        const val H_GRAPHEME_OFFSET = 15
        const val H_GRAPHEME_LEN = 16
        const val H_SEARCH_TOTAL = 17
        const val H_SEARCH_SELECTED = 18
        const val H_FRAME_INTS = 19

        const val FRAME_MAGIC = 0x474a4231
        const val COLOR_SET = 1 shl 24

        const val CURSOR_F_VISIBLE = 1 shl 0
        const val CURSOR_F_BLINKING = 1 shl 1
        const val CURSOR_F_WIDE_TAIL = 1 shl 2
        const val CURSOR_F_IN_VIEWPORT = 1 shl 3
        const val CURSOR_F_PASSWORD = 1 shl 4

        const val CURSOR_BAR = 0
        const val CURSOR_BLOCK = 1
        const val CURSOR_UNDERLINE = 2
        const val CURSOR_BLOCK_HOLLOW = 3

        const val F_MOUSE_TRACKING = 1 shl 0
        const val F_ALT_SCREEN = 1 shl 1
        const val F_VIEWPORT_AT_BOTTOM = 1 shl 2
        const val F_RENDER_HELD = 1 shl 3
        const val F_REVERSE_COLORS = 1 shl 4
        const val F_SEARCH_PENDING = 1 shl 5

        const val C_CODEPOINT = 0
        const val C_FG = 1
        const val C_BG = 2
        const val C_ATTRS = 3
        const val C_UNDERLINE = 4

        const val A_BOLD = 1 shl 0
        const val A_ITALIC = 1 shl 1
        const val A_FAINT = 1 shl 2
        const val A_BLINK = 1 shl 3
        const val A_INVERSE = 1 shl 4
        const val A_INVISIBLE = 1 shl 5
        const val A_STRIKETHROUGH = 1 shl 6
        const val A_OVERLINE = 1 shl 7
        const val A_UNDERLINE_SHIFT = 8
        const val A_UNDERLINE_MASK = 7 shl 8
        const val A_WIDE_SHIFT = 11
        const val A_WIDE_MASK = 3 shl 11
        const val A_SELECTED = 1 shl 13
        const val A_GRAPHEME = 1 shl 14
        const val A_HYPERLINK = 1 shl 15
        const val A_SEARCH_MATCH = 1 shl 16
        const val A_SEARCH_SELECTED = 1 shl 17

        const val WIDE_NARROW = 0
        const val WIDE_WIDE = 1
        const val WIDE_SPACER_TAIL = 2
        const val WIDE_SPACER_HEAD = 3

        const val SEL_PRESS = 0
        const val SEL_RELEASE = 1
        const val SEL_DRAG = 2
        const val SEL_AUTOSCROLL_TICK = 3

        const val SEL_R_CHANGED = 1 shl 0
        const val SEL_R_HAS = 1 shl 1
        const val SEL_R_AUTOSCROLL_UP = 1 shl 2
        const val SEL_R_AUTOSCROLL_DOWN = 1 shl 3

        // GhosttyMods
        const val MOD_SHIFT = 1 shl 0
        const val MOD_CTRL = 1 shl 1
        const val MOD_ALT = 1 shl 2
        const val MOD_SUPER = 1 shl 3
        const val MOD_CAPS_LOCK = 1 shl 4
        const val MOD_NUM_LOCK = 1 shl 5
        const val MOD_SHIFT_RIGHT = 1 shl 6
        const val MOD_CTRL_RIGHT = 1 shl 7
        const val MOD_ALT_RIGHT = 1 shl 8
        const val MOD_SUPER_RIGHT = 1 shl 9

        // GhosttyKeyAction
        const val KEY_RELEASE = 0
        const val KEY_PRESS = 1
        const val KEY_REPEAT = 2

        // GhosttyMouseAction / GhosttyMouseButton
        const val MOUSE_PRESS = 0
        const val MOUSE_RELEASE = 1
        const val MOUSE_MOTION = 2
        const val BUTTON_NONE = 0
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 2
        const val BUTTON_MIDDLE = 3
        const val BUTTON_WHEEL_UP = 4
        const val BUTTON_WHEEL_DOWN = 5
        const val BUTTON_WHEEL_LEFT = 6
        const val BUTTON_WHEEL_RIGHT = 7

        // GhosttyMouseShape (subset we map to AWT cursors)
        const val MOUSE_SHAPE_DEFAULT = 0
        const val MOUSE_SHAPE_POINTER = 3
        const val MOUSE_SHAPE_TEXT = 8
    }
}
