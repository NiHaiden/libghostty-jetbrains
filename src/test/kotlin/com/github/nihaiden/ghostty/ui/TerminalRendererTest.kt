package com.github.nihaiden.ghostty.ui

import com.github.nihaiden.ghostty.settings.GhosttySettings
import com.github.nihaiden.ghostty.vt.GhosttyJb
import com.github.nihaiden.ghostty.vt.GhosttyTerminal
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage

/** Renders frames offscreen inside a headless IDE and checks pixels. */
class TerminalRendererTest : BasePlatformTestCase() {
    private lateinit var term: GhosttyTerminal
    private lateinit var fonts: TerminalFonts
    private val theme = TerminalTheme.parseGhosttyTheme(
        """
        background = #000000
        foreground = #ffffff
        cursor-color = #ff00ff
        selection-background = #0000ff
        """.trimIndent(),
    )

    override fun setUp() {
        super.setUp()
        fonts = TerminalFonts.create(GhosttySettings.State())
        term = GhosttyTerminal(20, 4, object : GhosttyTerminal.Listener {})
        term.setColors(theme.foreground, theme.background, theme.cursor, theme.palette)
        term.resize(20, 4, fonts.cellWidth, fonts.cellHeight, 0, 0)
    }

    override fun tearDown() {
        try {
            term.close()
        } finally {
            super.tearDown()
        }
    }

    private fun render(focused: Boolean = true, blinkOn: Boolean = true): BufferedImage {
        val frame = term.snapshot()!!
        val w = fonts.cellWidth * frame.cols
        val h = fonts.cellHeight * frame.rows
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        TerminalRenderer().paint(
            g, frame,
            TerminalRenderer.Context(fonts, theme, 0, 0, focused, blinkOn, builtinBoxDrawing = true),
            w, h,
        )
        g.dispose()
        return img
    }

    private fun BufferedImage.at(col: Int, row: Int, dx: Int = 1, dy: Int = 1) =
        getRGB(col * fonts.cellWidth + dx, row * fonts.cellHeight + dy) and 0xffffff

    fun testBlockCursorIsDrawn() {
        term.write("ab".encodeToByteArray())
        val img = render()
        assertEquals(0xff00ff, img.at(2, 0))
        assertEquals(0x000000, img.at(5, 0))
    }

    fun testHollowCursorWhenUnfocused() {
        term.write("ab".encodeToByteArray())
        val img = render(focused = false)
        assertEquals(0xff00ff, img.at(2, 0, dx = 0, dy = 0))
        assertEquals(0x000000, img.at(2, 0, dx = fonts.cellWidth / 2, dy = fonts.cellHeight / 2))
    }

    fun testBlinkingCursorHiddenInOffPhase() {
        term.setOption(GhosttyJb.OPT_CURSOR_BLINK, 1)
        term.write("\u001b[0 q".encodeToByteArray()) // DECSCUSR reset to the (blinking) default
        assertEquals(0x000000, render(blinkOn = false).at(0, 0))
        term.invalidate()
        assertEquals(0xff00ff, render(blinkOn = true).at(0, 0))
    }

    fun testBackgroundColorsAndSelection() {
        term.write("\u001b[41mAB\u001b[0m".encodeToByteArray())
        var img = render()
        assertEquals(0xcc6666, img.at(0, 0)) // red from libghostty's default palette
        term.selectAll()
        img = render()
        assertEquals(0x0000ff, img.at(0, 0)) // selection wins over the cell background
    }

    fun testBoxDrawingJoinsAcrossCells() {
        term.write("───".encodeToByteArray())
        val img = render()
        val y = fonts.cellHeight / 2
        // The horizontal line is continuous across all three cells.
        for (x in 0 until fonts.cellWidth * 3) assertEquals("x=$x", 0xffffff, img.getRGB(x, y) and 0xffffff)
    }
}
