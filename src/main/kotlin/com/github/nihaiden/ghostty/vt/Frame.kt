package com.github.nihaiden.ghostty.vt

import com.github.nihaiden.ghostty.vt.GhosttyJb.Companion as J

/**
 * A captured viewport, decoded from the flat buffer written by gjb_snapshot.
 * See the layout description in native/src/ghostty_jb.h.
 */
class Frame internal constructor(private val data: IntArray) {
    init {
        require(data.size >= J.HEADER_INTS && data[J.H_MAGIC] == J.FRAME_MAGIC) { "not a ghostty_jb frame" }
    }

    val cols: Int get() = data[J.H_COLS]
    val rows: Int get() = data[J.H_ROWS]

    /** Default foreground/background, 0xRRGGBB. */
    val foreground: Int get() = data[J.H_FG]
    val background: Int get() = data[J.H_BG]

    /** Cursor color requested by the program (OSC 12), or null. */
    val cursorColor: Int? get() = data[J.H_CURSOR_COLOR].let { if (it and J.COLOR_SET != 0) it and 0xffffff else null }

    private val cursorFlags: Int get() = data[J.H_CURSOR_FLAGS]
    val cursorVisible: Boolean
        get() = cursorFlags and J.CURSOR_F_VISIBLE != 0 && cursorFlags and J.CURSOR_F_IN_VIEWPORT != 0
    val cursorBlinking: Boolean get() = cursorFlags and J.CURSOR_F_BLINKING != 0
    val cursorOnWideTail: Boolean get() = cursorFlags and J.CURSOR_F_WIDE_TAIL != 0
    val passwordInput: Boolean get() = cursorFlags and J.CURSOR_F_PASSWORD != 0
    val cursorX: Int get() = data[J.H_CURSOR_X]
    val cursorY: Int get() = data[J.H_CURSOR_Y]
    val cursorStyle: Int get() = data[J.H_CURSOR_STYLE]

    /** Scrollbar in rows: total (scrollback + screen), offset of the viewport top, visible length. */
    val scrollTotal: Int get() = data[J.H_SCROLL_TOTAL]
    val scrollOffset: Int get() = data[J.H_SCROLL_OFFSET]
    val scrollLength: Int get() = data[J.H_SCROLL_LEN]

    private val flags: Int get() = data[J.H_FLAGS]
    val mouseTracking: Boolean get() = flags and J.F_MOUSE_TRACKING != 0
    val altScreen: Boolean get() = flags and J.F_ALT_SCREEN != 0
    val viewportAtBottom: Boolean get() = flags and J.F_VIEWPORT_AT_BOTTOM != 0
    val reverseColors: Boolean get() = flags and J.F_REVERSE_COLORS != 0
    val searchPending: Boolean get() = flags and J.F_SEARCH_PENDING != 0

    /** Total search matches, or -1 when no search is active. */
    val searchTotal: Int get() = data[J.H_SEARCH_TOTAL]

    /** Index of the selected match (0 = newest), or -1. */
    val searchSelected: Int get() = data[J.H_SEARCH_SELECTED]

    private fun base(col: Int, row: Int) = J.HEADER_INTS + (row * cols + col) * J.CELL_INTS

    fun codepoint(col: Int, row: Int): Int = data[base(col, row) + J.C_CODEPOINT]

    /** Explicit foreground (0xRRGGBB) or -1 for the default. */
    fun fg(col: Int, row: Int): Int = color(data[base(col, row) + J.C_FG])

    /** Explicit background (0xRRGGBB) or -1 for the default. */
    fun bg(col: Int, row: Int): Int = color(data[base(col, row) + J.C_BG])

    /** Underline color (0xRRGGBB) or -1 to use the foreground. */
    fun underlineColor(col: Int, row: Int): Int = color(data[base(col, row) + J.C_UNDERLINE])

    /** GJB_A_* bits. */
    fun attrs(col: Int, row: Int): Int = data[base(col, row) + J.C_ATTRS]

    fun wide(col: Int, row: Int): Int = (attrs(col, row) and J.A_WIDE_MASK) shr J.A_WIDE_SHIFT

    fun underline(col: Int, row: Int): Int = (attrs(col, row) and J.A_UNDERLINE_MASK) shr J.A_UNDERLINE_SHIFT

    private fun color(v: Int) = if (v and J.COLOR_SET != 0) v and 0xffffff else -1

    private val graphemes: Map<Int, String> by lazy {
        val off = data[J.H_GRAPHEME_OFFSET]
        val len = data[J.H_GRAPHEME_LEN]
        if (len <= 0) return@lazy emptyMap()
        val map = HashMap<Int, String>()
        var i = off
        val end = off + len
        while (i + 1 < end) {
            val index = data[i]
            val n = data[i + 1]
            val sb = StringBuilder(n * 2)
            for (k in 0 until n) sb.appendCodePoint(data[i + 2 + k])
            map[index] = sb.toString()
            i += 2 + n
        }
        map
    }

    /** The text of a cell: "" for empty cells, otherwise the full grapheme cluster. */
    fun text(col: Int, row: Int): String {
        val cp = codepoint(col, row)
        if (cp == 0) return ""
        if (attrs(col, row) and J.A_GRAPHEME != 0) graphemes[row * cols + col]?.let { return it }
        return String(Character.toChars(cp))
    }

    /** The visible text of a row (spacer cells skipped, trailing blanks trimmed). */
    fun rowText(row: Int): String {
        val sb = StringBuilder(cols)
        for (col in 0 until cols) {
            val w = wide(col, row)
            if (w == J.WIDE_SPACER_TAIL || w == J.WIDE_SPACER_HEAD) continue
            val t = text(col, row)
            sb.append(t.ifEmpty { " " })
        }
        return sb.trimEnd().toString()
    }
}
