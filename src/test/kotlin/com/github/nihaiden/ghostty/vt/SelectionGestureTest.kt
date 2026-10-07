package com.github.nihaiden.ghostty.vt

import com.github.nihaiden.ghostty.vt.GhosttyJb as J
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mouse gesture sequences as the panel sends them. */
class SelectionGestureTest {
    private val term = GhosttyTerminal(40, 4, object : GhosttyTerminal.Listener {}).apply {
        resize(40, 4, 10, 20, 0, 0)
        write("selecting this text works fine".encodeToByteArray())
    }

    @After
    fun tearDown() = term.close()

    private var now = 1_000_000_000L
    private fun click(x: Double, gapMs: Long = 80) {
        now += gapMs * 1_000_000
        term.selectEvent(J.SEL_PRESS, x, 5.0, now, false)
        term.selectEvent(J.SEL_RELEASE, x, 5.0, 0, false)
    }

    @Test
    fun doubleClickAfterDragSelectsWord() {
        term.selectEvent(J.SEL_PRESS, 1.0, 5.0, now, false)
        term.selectEvent(J.SEL_DRAG, 100.0, 5.0, 0, false)
        term.selectEvent(J.SEL_RELEASE, 100.0, 5.0, 0, false)
        assertEquals("selecting", term.selectionText)
        click(225.0, gapMs = 900)
        click(225.0)
        assertEquals("works", term.selectionText)
        click(225.0)
        assertEquals("selecting this text works fine", term.selectionText)
    }
}
