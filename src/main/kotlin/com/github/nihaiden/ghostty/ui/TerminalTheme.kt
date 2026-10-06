package com.github.nihaiden.ghostty.ui

import com.github.nihaiden.ghostty.settings.GhosttySettings
import com.intellij.execution.process.ConsoleHighlighter
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.ColorUtil
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path

/**
 * Colors used to draw a terminal. Ints are 0xRRGGBB; -1 means "let
 * libghostty use its built-in default".
 */
data class TerminalTheme(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val cursorText: Int,
    val selectionBackground: Int,
    /** -1 = keep the cell's own foreground. */
    val selectionForeground: Int,
    val searchMatch: Int,
    val searchSelected: Int,
    /** The 16 base ANSI colors, or null to keep libghostty's palette. */
    val palette: IntArray?,
    val dark: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is TerminalTheme && toString() == other.toString() && palette.contentEquals(other.palette)

    override fun hashCode(): Int = toString().hashCode() * 31 + palette.contentHashCode()

    companion object {
        private val LOG = logger<TerminalTheme>()

        /** Ghostty's own defaults (see Ghostty's config docs). */
        private val GHOSTTY_DEFAULT = TerminalTheme(
            foreground = 0xffffff,
            background = 0x282c34,
            cursor = 0xffffff,
            cursorText = 0x282c34,
            selectionBackground = 0xffffff,
            selectionForeground = 0x282c34,
            searchMatch = 0xffe082,
            searchSelected = 0xf2a57e,
            palette = null,
            dark = true,
        )

        fun current(settings: GhosttySettings.State = GhosttySettings.getInstance().state): TerminalTheme =
            when (settings.colorSource) {
                GhosttySettings.ColorSource.IDE -> fromScheme(EditorColorsManager.getInstance().globalScheme)
                GhosttySettings.ColorSource.GHOSTTY_DEFAULT -> GHOSTTY_DEFAULT
                GhosttySettings.ColorSource.GHOSTTY_THEME_FILE -> fromGhosttyFile(settings.themeFile)
            }

        private val ANSI_KEYS: List<TextAttributesKey> = listOf(
            ConsoleHighlighter.BLACK, ConsoleHighlighter.RED, ConsoleHighlighter.GREEN, ConsoleHighlighter.YELLOW,
            ConsoleHighlighter.BLUE, ConsoleHighlighter.MAGENTA, ConsoleHighlighter.CYAN, ConsoleHighlighter.GRAY,
            ConsoleHighlighter.DARKGRAY, ConsoleHighlighter.RED_BRIGHT, ConsoleHighlighter.GREEN_BRIGHT,
            ConsoleHighlighter.YELLOW_BRIGHT, ConsoleHighlighter.BLUE_BRIGHT, ConsoleHighlighter.MAGENTA_BRIGHT,
            ConsoleHighlighter.CYAN_BRIGHT, ConsoleHighlighter.WHITE,
        )

        /** Derives the theme from the IDE's console colors so the terminal matches the editor. */
        fun fromScheme(scheme: EditorColorsScheme): TerminalTheme {
            val bg = scheme.getColor(ConsoleViewContentType.CONSOLE_BACKGROUND_KEY) ?: scheme.defaultBackground
            val fg = scheme.getAttributes(ConsoleViewContentType.NORMAL_OUTPUT_KEY)?.foregroundColor
                ?: scheme.defaultForeground
            val palette = IntArray(16) { i ->
                val c = scheme.getAttributes(ANSI_KEYS[i])?.foregroundColor ?: XTERM_16[i]
                rgb(c)
            }
            val caret = scheme.getColor(EditorColors.CARET_COLOR) ?: fg
            val selection = scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR) ?: ColorUtil.mix(bg, fg, 0.3)
            val selectionFg = scheme.getColor(EditorColors.SELECTION_FOREGROUND_COLOR)
            val dark = ColorUtil.isDark(bg)
            val searchMatch = scheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)?.backgroundColor
                ?: if (dark) Color(0x32593d) else Color(0xffe082)
            val searchSelected = scheme.getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES)?.backgroundColor
                ?: if (dark) Color(0x5a4a1f) else Color(0xf2a57e)
            return TerminalTheme(
                foreground = rgb(fg),
                background = rgb(bg),
                cursor = rgb(caret),
                cursorText = rgb(bg),
                selectionBackground = rgb(selection),
                selectionForeground = selectionFg?.let(::rgb) ?: -1,
                searchMatch = rgb(searchMatch),
                searchSelected = rgb(searchSelected),
                palette = palette,
                dark = dark,
            )
        }

        /**
         * Reads a Ghostty theme or config file. Understands `background`,
         * `foreground`, `cursor-color`, `cursor-text`, `selection-background`,
         * `selection-foreground` and `palette = N=#rrggbb`; everything else is
         * ignored. Missing values fall back to Ghostty's defaults.
         */
        fun fromGhosttyFile(path: String): TerminalTheme {
            if (path.isBlank()) return GHOSTTY_DEFAULT
            val text = try {
                Files.readString(Path.of(path.trim().replaceFirst("~", System.getProperty("user.home"))))
            } catch (e: Exception) {
                LOG.warn("Cannot read Ghostty theme $path", e)
                return GHOSTTY_DEFAULT
            }
            return parseGhosttyTheme(text)
        }

        fun parseGhosttyTheme(text: String): TerminalTheme {
            var t = GHOSTTY_DEFAULT
            var palette: IntArray? = null
            var cursorSet = false
            var cursorTextSet = false
            for (raw in text.lineSequence()) {
                // Ghostty only has full-line comments; values like #282c34 contain '#'.
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim().trim('"')
                when (key) {
                    "background" -> parseColor(value)?.let { t = t.copy(background = it) }
                    "foreground" -> parseColor(value)?.let { t = t.copy(foreground = it) }
                    "cursor-color" -> parseColor(value)?.let { t = t.copy(cursor = it); cursorSet = true }
                    "cursor-text" -> parseColor(value)?.let { t = t.copy(cursorText = it); cursorTextSet = true }
                    "selection-background" -> parseColor(value)?.let { t = t.copy(selectionBackground = it) }
                    "selection-foreground" -> parseColor(value)?.let { t = t.copy(selectionForeground = it) }
                    "palette" -> {
                        val idx = value.substringBefore('=').trim().toIntOrNull() ?: continue
                        val color = parseColor(value.substringAfter('=').trim()) ?: continue
                        if (idx in 0..15) {
                            val p = palette ?: IntArray(16) { XTERM_16[it].rgb and 0xffffff }.also { palette = it }
                            p[idx] = color
                        }
                    }
                }
            }
            if (!cursorSet) t = t.copy(cursor = t.foreground)
            if (!cursorTextSet) t = t.copy(cursorText = t.background)
            return t.copy(palette = palette, dark = ColorUtil.isDark(Color(t.background)))
        }

        private fun parseColor(value: String): Int? {
            val v = value.removePrefix("#")
            if (v.length != 6) return null
            return v.toIntOrNull(16)
        }

        private fun rgb(c: Color): Int = c.rgb and 0xffffff

        /** xterm's default 16 colors, used for anything the scheme doesn't define. */
        private val XTERM_16 = listOf(
            0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
            0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff,
        ).map(::Color)
    }
}
