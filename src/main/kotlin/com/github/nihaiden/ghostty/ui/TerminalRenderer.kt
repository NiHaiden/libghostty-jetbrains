package com.github.nihaiden.ghostty.ui

import com.github.nihaiden.ghostty.vt.Frame
import com.github.nihaiden.ghostty.vt.GhosttyJb.Companion as J
import com.intellij.ide.ui.AntialiasingType
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.GeneralPath
import java.awt.geom.Point2D
import kotlin.math.max
import kotlin.math.min

/** Paints a [Frame] with Java2D. Stateless apart from small caches; EDT only. */
class TerminalRenderer {

    class Context(
        val fonts: TerminalFonts,
        val theme: TerminalTheme,
        val padX: Int,
        val padY: Int,
        val focused: Boolean,
        /** False during the "off" phase of cursor blinking. */
        val cursorBlinkOn: Boolean,
        val builtinBoxDrawing: Boolean,
        /** Hovered link: row and inclusive column range, or row = -1. */
        val linkRow: Int = -1,
        val linkStart: Int = 0,
        val linkEnd: Int = 0,
        /** IME composition text shown at the cursor. */
        val preedit: String? = null,
    )

    private val colors = HashMap<Int, Color>()
    private fun color(rgb: Int): Color = colors.getOrPut(rgb) { Color(rgb) }

    private var rowFg = IntArray(0)
    private var rowBg = IntArray(0)
    private var runChars = CharArray(0)

    fun paint(g: Graphics2D, frame: Frame, ctx: Context, width: Int, height: Int) {
        val cols = frame.cols
        val rows = frame.rows
        if (rowFg.size < cols) {
            rowFg = IntArray(cols)
            rowBg = IntArray(cols)
            runChars = CharArray(cols)
        }
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            AntialiasingType.getKeyForCurrentScope(true) ?: RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)

        val reverse = frame.reverseColors
        val defFg = if (reverse) frame.background else frame.foreground
        val defBg = if (reverse) frame.foreground else frame.background
        g.color = color(defBg)
        g.fillRect(0, 0, width, height)

        val cw = ctx.fonts.cellWidth
        val ch = ctx.fonts.cellHeight
        for (row in 0 until rows) {
            val y = ctx.padY + row * ch
            resolveRow(frame, row, cols, defFg, defBg, ctx.theme)
            paintBackgrounds(g, cols, ctx.padX, y, cw, ch, defBg)
            paintText(g, frame, row, cols, ctx, y)
            paintDecorations(g, frame, row, cols, ctx, y)
        }
        paintCursor(g, frame, ctx, defBg)
    }

    /** Computes the effective fg/bg of every cell in a row into rowFg/rowBg. */
    private fun resolveRow(frame: Frame, row: Int, cols: Int, defFg: Int, defBg: Int, theme: TerminalTheme) {
        for (col in 0 until cols) {
            val attrs = frame.attrs(col, row)
            var fg = frame.fg(col, row).let { if (it < 0) defFg else it }
            var bg = frame.bg(col, row).let { if (it < 0) defBg else it }
            if (attrs and J.A_INVERSE != 0) {
                val t = fg; fg = bg; bg = t
            }
            when {
                attrs and J.A_SEARCH_SELECTED != 0 -> {
                    bg = theme.searchSelected; fg = contrastText(bg)
                }
                attrs and J.A_SEARCH_MATCH != 0 -> {
                    bg = theme.searchMatch; fg = contrastText(bg)
                }
                attrs and J.A_SELECTED != 0 -> {
                    bg = theme.selectionBackground
                    if (theme.selectionForeground >= 0) fg = theme.selectionForeground
                }
            }
            if (attrs and J.A_FAINT != 0) fg = blend(fg, bg, 0.5f)
            if (attrs and J.A_INVISIBLE != 0) fg = bg
            rowFg[col] = fg
            rowBg[col] = bg
        }
    }

    private fun paintBackgrounds(g: Graphics2D, cols: Int, padX: Int, y: Int, cw: Int, ch: Int, defBg: Int) {
        var start = 0
        while (start < cols) {
            val bg = rowBg[start]
            var end = start + 1
            while (end < cols && rowBg[end] == bg) end++
            if (bg != defBg) {
                g.color = color(bg)
                g.fillRect(padX + start * cw, y, (end - start) * cw, ch)
            }
            start = end
        }
    }

    private fun paintText(g: Graphics2D, frame: Frame, row: Int, cols: Int, ctx: Context, y: Int) {
        val fonts = ctx.fonts
        val cw = fonts.cellWidth
        val baseline = y + fonts.baseline
        var runStart = -1
        var runLen = 0
        var runFont: Font? = null
        var runFg = 0

        fun flush() {
            if (runLen > 0) {
                val font = runFont!!
                val gv = font.createGlyphVector(g.fontRenderContext, runChars.copyOf(runLen))
                if (gv.numGlyphs == runLen) {
                    for (i in 0 until runLen) gv.setGlyphPosition(i, Point2D.Float((i * cw).toFloat(), 0f))
                }
                g.color = color(runFg)
                g.drawGlyphVector(gv, (ctx.padX + runStart * cw).toFloat(), baseline.toFloat())
            }
            runLen = 0
            runStart = -1
        }

        for (col in 0 until cols) {
            val attrs = frame.attrs(col, row)
            val wide = (attrs and J.A_WIDE_MASK) shr J.A_WIDE_SHIFT
            if (wide == J.WIDE_SPACER_TAIL || wide == J.WIDE_SPACER_HEAD) {
                flush(); continue
            }
            val cp = frame.codepoint(col, row)
            if (cp == 0 || cp == ' '.code || attrs and J.A_INVISIBLE != 0) {
                flush(); continue
            }
            val fg = rowFg[col]
            val bold = attrs and J.A_BOLD != 0
            val italic = attrs and J.A_ITALIC != 0
            val x = ctx.padX + col * cw

            if (ctx.builtinBoxDrawing && BoxDrawing.handles(cp)) {
                flush()
                BoxDrawing.draw(g, cp, x, y, cw, fonts.cellHeight, color(fg))
                continue
            }
            val simple = wide == J.WIDE_NARROW && attrs and J.A_GRAPHEME == 0 && cp < 0x10000 &&
                !Character.isSurrogate(cp.toChar())
            val font = fonts.fontFor(cp, bold, italic)
            if (simple && font === fonts.font(bold, italic)) {
                if (runLen > 0 && (font !== runFont || fg != runFg || runStart + runLen != col)) flush()
                if (runLen == 0) {
                    runStart = col; runFont = font; runFg = fg
                }
                runChars[runLen++] = cp.toChar()
                continue
            }
            // Wide glyphs, grapheme clusters, astral planes and fallback fonts: one at a time.
            flush()
            val text = frame.text(col, row)
            val cells = if (wide == J.WIDE_WIDE) 2 else 1
            g.font = font
            g.color = color(fg)
            val advance = font.getStringBounds(text, g.fontRenderContext).width
            val slot = cells * cw
            val dx = if (advance < slot) (slot - advance) / 2 else 0.0
            if (advance > slot * 1.2 && advance > 0) {
                // Too wide for its cells (e.g. emoji from a proportional fallback): squeeze it.
                val old = g.transform
                g.translate(x.toDouble(), 0.0)
                g.scale(slot / advance, 1.0)
                g.drawString(text, 0f, baseline.toFloat())
                g.transform = old
            } else {
                g.drawString(text, (x + dx).toFloat(), baseline.toFloat())
            }
        }
        flush()
    }

    private fun paintDecorations(g: Graphics2D, frame: Frame, row: Int, cols: Int, ctx: Context, y: Int) {
        val fonts = ctx.fonts
        val cw = fonts.cellWidth
        val sw = fonts.strokeWidth
        val ulY = min(y + fonts.cellHeight - sw, y + fonts.baseline + fonts.underlineOffset)
        for (col in 0 until cols) {
            val attrs = frame.attrs(col, row)
            val linkHover = row == ctx.linkRow && col in ctx.linkStart..ctx.linkEnd
            val underline = (attrs and J.A_UNDERLINE_MASK) shr J.A_UNDERLINE_SHIFT
            if (underline == 0 && !linkHover && attrs and (J.A_STRIKETHROUGH or J.A_OVERLINE) == 0) continue
            val x = ctx.padX + col * cw
            val fg = rowFg[col]
            if (underline != 0 || linkHover) {
                val ul = frame.underlineColor(col, row)
                g.color = color(if (ul >= 0 && !linkHover) ul else fg)
                when (if (linkHover) 1 else underline) {
                    2 -> { // double
                        g.fillRect(x, ulY, cw, sw)
                        g.fillRect(x, max(y, ulY - 2 * sw), cw, sw)
                    }
                    3 -> curly(g, x, ulY, cw, sw)
                    4 -> { // dotted
                        var dx = 0
                        while (dx < cw) {
                            g.fillRect(x + dx, ulY, sw, sw); dx += 2 * sw
                        }
                    }
                    5 -> { // dashed
                        g.fillRect(x, ulY, cw / 2, sw)
                    }
                    else -> g.fillRect(x, ulY, cw, sw)
                }
            }
            g.color = color(fg)
            if (attrs and J.A_STRIKETHROUGH != 0) {
                g.fillRect(x, y + fonts.baseline - fonts.cellHeight / 4 - sw / 2, cw, sw)
            }
            if (attrs and J.A_OVERLINE != 0) g.fillRect(x, y, cw, sw)
        }
    }

    private fun curly(g: Graphics2D, x: Int, y: Int, w: Int, sw: Int) {
        val amp = max(1, sw).toFloat()
        val p = GeneralPath()
        p.moveTo(x.toFloat(), y.toFloat())
        p.quadTo(x + w / 4f, y - amp, x + w / 2f, y.toFloat())
        p.quadTo(x + 3 * w / 4f, y + amp, (x + w).toFloat(), y.toFloat())
        val old = g.stroke
        val oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.stroke = BasicStroke(sw.toFloat())
        g.draw(p)
        g.stroke = old
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa)
    }

    private fun paintCursor(g: Graphics2D, frame: Frame, ctx: Context, defBg: Int) {
        val preedit = ctx.preedit
        if (!frame.cursorVisible && preedit.isNullOrEmpty()) return
        val fonts = ctx.fonts
        val cw = fonts.cellWidth
        val ch = fonts.cellHeight
        var col = frame.cursorX
        if (frame.cursorOnWideTail && col > 0) col--
        val row = frame.cursorY
        if (row !in 0 until frame.rows || col !in 0 until frame.cols) return
        val x = ctx.padX + col * cw
        val y = ctx.padY + row * ch
        val cursorColor = color(frame.cursorColor ?: ctx.theme.cursor)

        if (!preedit.isNullOrEmpty()) {
            val font = fonts.regular
            val width = max(cw, g.getFontMetrics(font).stringWidth(preedit))
            g.color = color(defBg)
            g.fillRect(x, y, width, ch)
            g.font = font
            g.color = color(frame.foreground)
            g.drawString(preedit, x, y + fonts.baseline)
            g.fillRect(x, y + ch - fonts.strokeWidth, width, fonts.strokeWidth)
            return
        }

        if (frame.cursorBlinking && ctx.focused && !ctx.cursorBlinkOn) return
        val cells = if (frame.wide(col, row) == J.WIDE_WIDE) 2 else 1
        val w = cw * cells
        g.color = cursorColor
        val style = if (!ctx.focused) J.CURSOR_BLOCK_HOLLOW else frame.cursorStyle
        when (style) {
            J.CURSOR_BAR -> g.fillRect(x, y, max(1, JBUI.scale(2)), ch)
            J.CURSOR_UNDERLINE -> g.fillRect(x, y + ch - max(2, fonts.strokeWidth * 2), w, max(2, fonts.strokeWidth * 2))
            J.CURSOR_BLOCK_HOLLOW -> {
                val sw = max(1, fonts.strokeWidth)
                g.fillRect(x, y, w, sw)
                g.fillRect(x, y + ch - sw, w, sw)
                g.fillRect(x, y, sw, ch)
                g.fillRect(x + w - sw, y, sw, ch)
            }
            else -> {
                g.fillRect(x, y, w, ch)
                // Redraw the glyph under the block cursor in the cursor text color.
                val cp = frame.codepoint(col, row)
                if (cp != 0 && cp != ' '.code) {
                    val attrs = frame.attrs(col, row)
                    val textColor = color(ctx.theme.cursorText)
                    if (ctx.builtinBoxDrawing && BoxDrawing.handles(cp)) {
                        BoxDrawing.draw(g, cp, x, y, cw, ch, textColor)
                    } else {
                        g.font = fonts.fontFor(cp, attrs and J.A_BOLD != 0, attrs and J.A_ITALIC != 0)
                        g.color = textColor
                        g.drawString(frame.text(col, row), x, y + fonts.baseline)
                    }
                }
            }
        }
    }

    private fun contrastText(bg: Int): Int {
        val r = (bg shr 16) and 0xff
        val gr = (bg shr 8) and 0xff
        val b = bg and 0xff
        return if (0.299 * r + 0.587 * gr + 0.114 * b > 140) 0x000000 else 0xffffff
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xff) * (1 - t) + ((b shr shift) and 0xff) * t).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
