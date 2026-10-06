package com.github.nihaiden.ghostty.ui

import com.github.nihaiden.ghostty.settings.GhosttySettings
import com.intellij.ide.ui.AntialiasingType
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The four style variants of the terminal font plus cell metrics and a
 * per-codepoint fallback for glyphs the main font lacks.
 */
class TerminalFonts private constructor(
    val regular: Font,
    private val bold: Font,
    private val italic: Font,
    private val boldItalic: Font,
    lineSpacing: Float,
) {
    val frc: FontRenderContext = FontRenderContext(
        null,
        AntialiasingType.getKeyForCurrentScope(true) ?: RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        RenderingHints.VALUE_FRACTIONALMETRICS_OFF,
    )

    /** Cell size in user-space pixels. */
    val cellWidth: Int
    val cellHeight: Int

    /** Baseline offset from the top of a cell. */
    val baseline: Int
    val underlineOffset: Int
    val strokeWidth: Int

    init {
        val bounds = regular.getStringBounds("M", frc)
        val lm = regular.getLineMetrics("Mg|", frc)
        cellWidth = max(1, bounds.width.roundToInt())
        val natural = lm.ascent + lm.descent + lm.leading
        cellHeight = max(1, ceil(natural * lineSpacing).toInt())
        // Center the natural line inside the (possibly taller) cell.
        baseline = ((cellHeight - natural) / 2 + lm.ascent).roundToInt()
        strokeWidth = max(1, lm.underlineThickness.roundToInt())
        underlineOffset = max(1, lm.underlineOffset.roundToInt())
    }

    fun font(bold: Boolean, italic: Boolean): Font = when {
        bold && italic -> boldItalic
        bold -> this.bold
        italic -> this.italic
        else -> regular
    }

    private val fallbackBase: List<Font> = listOf(
        Font(Font.MONOSPACED, Font.PLAIN, regular.size),
        Font(Font.DIALOG, Font.PLAIN, regular.size),
    )
    private val fallbackCache = HashMap<Int, Int>()

    /** Font able to display [cp] in the given style (the primary font when possible). */
    fun fontFor(cp: Int, bold: Boolean, italic: Boolean): Font {
        val primary = font(bold, italic)
        if (primary.canDisplay(cp)) return primary
        val idx = fallbackCache.getOrPut(cp) { fallbackBase.indexOfFirst { it.canDisplay(cp) } }
        if (idx < 0) return primary
        val style = (if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0)
        return fallbackBase[idx].deriveFont(style, regular.size2D)
    }

    companion object {
        fun create(settings: GhosttySettings.State, zoom: Float = 1f): TerminalFonts {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val family = settings.fontFamily.ifBlank { scheme.consoleFontName }
            val size = (if (settings.fontSize > 0) settings.fontSize else scheme.consoleFontSize2D) * zoom
            // Scheme font sizes are already adjusted for the IDE scale.
            val base = Font(family, Font.PLAIN, 1).deriveFont(size)
            val spacing = if (settings.lineSpacing > 0) settings.lineSpacing else scheme.consoleLineSpacing
            return TerminalFonts(
                regular = base,
                bold = base.deriveFont(Font.BOLD),
                italic = base.deriveFont(Font.ITALIC),
                boldItalic = base.deriveFont(Font.BOLD or Font.ITALIC),
                lineSpacing = spacing,
            )
        }
    }
}
