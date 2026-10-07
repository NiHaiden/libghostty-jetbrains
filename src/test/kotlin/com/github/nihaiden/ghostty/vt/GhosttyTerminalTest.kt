package com.github.nihaiden.ghostty.vt

import com.github.nihaiden.ghostty.vt.GhosttyJb as J
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Exercises the Kotlin binding against the real native library. */
class GhosttyTerminalTest {
    private val pty = ByteArrayOutputStream()
    private var title = ""
    private var clipboard: String? = null

    private val term = GhosttyTerminal(20, 5, object : GhosttyTerminal.Listener {
        override fun onWritePty(data: ByteArray) = pty.writeBytes(data)
        override fun onTitleChanged(title: String) {
            this@GhosttyTerminalTest.title = title
        }
        override fun onClipboardWrite(text: String, primary: Boolean) {
            clipboard = text
        }
    })

    @After
    fun tearDown() = term.close()

    private fun write(s: String) = term.write(s.encodeToByteArray())

    @Test
    fun rendersStyledText() {
        write("Hello \u001b[1;38;2;255;0;0mred\u001b[0m\r\n日本")
        val f = term.snapshot()!!
        assertEquals(20, f.cols)
        assertEquals(5, f.rows)
        assertEquals("Hello red", f.rowText(0))
        assertEquals(0xff0000, f.fg(6, 0))
        assertEquals(-1, f.fg(0, 0))
        assertTrue(f.attrs(6, 0) and J.A_BOLD != 0)
        assertEquals("日本", f.rowText(1))
        assertEquals(J.WIDE_WIDE, f.wide(0, 1))
        assertEquals(J.WIDE_SPACER_TAIL, f.wide(1, 1))
        assertEquals(4, f.cursorX)
        assertEquals(1, f.cursorY)
        // Nothing changed since: no new frame.
        assertNull(term.snapshot())
    }

    @Test
    fun graphemeClusters() {
        write("👩‍💻!")
        val f = term.snapshot()!!
        assertEquals("👩‍💻", f.text(0, 0))
        assertEquals("!", f.text(2, 0))
    }

    @Test
    fun answersQueriesThroughListener() {
        write("\u001b[c")
        assertEquals("\u001b[?62;22c", pty.toString(Charsets.UTF_8))
        write("\u001b]0;hello\u0007")
        assertEquals("hello", title)
        assertEquals("hello", term.title)
        write("\u001b]52;c;aGk=\u0007")
        assertEquals(null, clipboard) // off by default
        term.setOption(J.OPT_ALLOW_CLIPBOARD_WRITE, 1)
        write("\u001b]52;c;aGk=\u0007")
        assertEquals("hi", clipboard)
    }

    @Test
    fun encodesKeys() {
        fun enc(key: Int, mods: Int = 0, text: String? = null) =
            term.encodeKey(J.KEY_PRESS, key, mods, 0, 0, text).decodeToString()
        assertEquals("\r", enc(GhosttyKey.ENTER))
        assertEquals("\u0003", enc(GhosttyKey.C, J.MOD_CTRL, "c"))
        assertEquals("\u001b[A", enc(GhosttyKey.ARROW_UP))
        assertEquals("\u001b[1;5C", enc(GhosttyKey.ARROW_RIGHT, J.MOD_CTRL))
        assertEquals("\u007f", enc(GhosttyKey.BACKSPACE))
        write("\u001b[?1h")
        assertEquals("\u001bOA", enc(GhosttyKey.ARROW_UP))
        // Kitty keyboard protocol, pushed by the app.
        write("\u001b[>1u")
        assertEquals("\u001b[27u", enc(GhosttyKey.ESCAPE))
    }

    @Test
    fun pasteHonoursBracketedMode() {
        assertEquals(GhosttyTerminal.PasteResult.UNSAFE, term.paste("a\nb", allowUnsafe = false))
        assertEquals(GhosttyTerminal.PasteResult.OK, term.paste("plain", allowUnsafe = false))
        assertEquals("plain", pty.toString(Charsets.UTF_8))
        pty.reset()
        write("\u001b[?2004h")
        assertEquals(GhosttyTerminal.PasteResult.OK, term.paste("a\nb", allowUnsafe = false))
        assertArrayEquals("\u001b[200~a\nb\u001b[201~".encodeToByteArray(), pty.toByteArray())
    }

    @Test
    fun selectionAndCopy() {
        term.resize(20, 5, 10, 20, 0, 0)
        write("one two three")
        term.selectEvent(J.SEL_PRESS, 42.0, 5.0, 1, false)
        val r = term.selectEvent(J.SEL_DRAG, 68.0, 5.0, 0, false)
        assertTrue(r and J.SEL_R_HAS != 0)
        assertEquals("two", term.selectionText)
        assertTrue(term.hasSelection)
        term.clearSelection()
        assertFalse(term.hasSelection)
        assertNull(term.selectionText)
    }

    @Test
    fun scrollbackAndSearch() {
        repeat(40) { write("row $it\r\n") }
        var f = term.snapshot()!!
        assertTrue(f.scrollTotal >= 40)
        assertTrue(f.viewportAtBottom)
        term.scrollBy(-10)
        f = term.snapshot()!!
        assertFalse(f.viewportAtBottom)
        term.scrollToBottom()
        term.setSearch("row 3")
        repeat(5) { term.snapshot() }
        assertEquals(0, term.searchSelect(1))
        f = term.snapshot()!!
        // "row 3" and "row 30".."row 39"
        assertEquals(11, f.searchTotal)
        assertEquals(0, f.searchSelected)
        term.setSearch(null)
        f = term.snapshot()!!
        assertEquals(-1, f.searchTotal)
        assertTrue(term.screenText.contains("row 0\nrow 1\n"))
    }

    @Test
    fun mouseReportingOnlyWhenRequested() {
        term.resize(20, 5, 10, 20, 0, 0)
        assertEquals(0, term.encodeMouse(J.MOUSE_PRESS, J.BUTTON_LEFT, 0, 5f, 5f, true).size)
        write("\u001b[?1000h\u001b[?1006h")
        assertTrue(term.isMouseTracking)
        assertEquals("\u001b[<0;1;1M", term.encodeMouse(J.MOUSE_PRESS, J.BUTTON_LEFT, 0, 5f, 5f, true).decodeToString())
        assertEquals("\u001b[<64;1;1M", term.encodeMouse(J.MOUSE_PRESS, J.BUTTON_WHEEL_UP, 0, 5f, 5f, false).decodeToString())
    }

    @Test
    fun closedTerminalIsInert() {
        term.close()
        assertTrue(term.isClosed)
        write("ignored")
        assertNull(term.snapshot())
        assertEquals(0, term.encodeKey(J.KEY_PRESS, GhosttyKey.ENTER, 0, 0, 0, null).size)
    }
}
