package com.github.nihaiden.ghostty.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.image.BufferedImage

class GhosttyTerminalWidgetTest : BasePlatformTestCase() {
    private fun layout(c: java.awt.Component) {
        c.doLayout()
        if (c is java.awt.Container) c.components.forEach(::layout)
    }

    fun testSearchBarFloatsOverTerminal() {
        val widget = GhosttyTerminalWidget(project, null)
        try {
            widget.setSize(900, 400)
            layout(widget)
            val panelBefore = widget.panel.bounds
            widget.toggleSearch()
            UIUtil.dispatchAllInvocationEvents()
            layout(widget)
            val bar = UIUtil.findComponentOfType(widget, TerminalSearchBar::class.java)!!
            assertTrue(bar.isVisible)
            assertTrue("bar has no size: ${bar.bounds}", bar.width > 0 && bar.height > 0)
            assertEquals("terminal must not resize", panelBefore.size, widget.panel.size)
            // And it is actually drawn on top of the terminal: the bar's area must
            // differ from a render with the bar hidden.
            fun render(): BufferedImage {
                val img = BufferedImage(widget.width, widget.height, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                widget.paint(g)
                g.dispose()
                return img
            }
            val with = render()
            bar.isVisible = false
            val without = render()
            var differing = 0
            for (x in bar.x until bar.x + bar.width) for (y in bar.y until bar.y + bar.height) {
                if (with.getRGB(x, y) != without.getRGB(x, y)) differing++
            }
            assertTrue("search bar is not painted over the terminal", differing > bar.width)
        } finally {
            Disposer.dispose(widget)
        }
    }
}
