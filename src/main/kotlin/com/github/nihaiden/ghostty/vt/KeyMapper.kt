package com.github.nihaiden.ghostty.vt

import com.github.nihaiden.ghostty.vt.GhosttyJb as J
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/** Maps AWT key events onto libghostty's W3C-style key codes and modifier bits. */
object KeyMapper {

    fun key(e: KeyEvent): Int {
        val numpad = e.keyLocation == KeyEvent.KEY_LOCATION_NUMPAD
        val right = e.keyLocation == KeyEvent.KEY_LOCATION_RIGHT
        val code = e.keyCode
        if (code in KeyEvent.VK_A..KeyEvent.VK_Z) return GhosttyKey.A + (code - KeyEvent.VK_A)
        if (code in KeyEvent.VK_0..KeyEvent.VK_9) {
            return if (numpad) GhosttyKey.NUMPAD_0 + (code - KeyEvent.VK_0) else GhosttyKey.DIGIT_0 + (code - KeyEvent.VK_0)
        }
        if (code in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9) return GhosttyKey.NUMPAD_0 + (code - KeyEvent.VK_NUMPAD0)
        if (code in KeyEvent.VK_F1..KeyEvent.VK_F12) return GhosttyKey.F1 + (code - KeyEvent.VK_F1)
        if (code in KeyEvent.VK_F13..KeyEvent.VK_F24) return GhosttyKey.F13 + (code - KeyEvent.VK_F13)

        return when (code) {
            KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_DEAD_GRAVE -> GhosttyKey.BACKQUOTE
            KeyEvent.VK_BACK_SLASH -> GhosttyKey.BACKSLASH
            KeyEvent.VK_OPEN_BRACKET -> GhosttyKey.BRACKET_LEFT
            KeyEvent.VK_CLOSE_BRACKET -> GhosttyKey.BRACKET_RIGHT
            KeyEvent.VK_COMMA -> GhosttyKey.COMMA
            KeyEvent.VK_EQUALS -> if (numpad) GhosttyKey.NUMPAD_EQUAL else GhosttyKey.EQUAL
            KeyEvent.VK_MINUS -> GhosttyKey.MINUS
            KeyEvent.VK_PERIOD -> GhosttyKey.PERIOD
            KeyEvent.VK_QUOTE, KeyEvent.VK_DEAD_ACUTE -> GhosttyKey.QUOTE
            KeyEvent.VK_SEMICOLON -> GhosttyKey.SEMICOLON
            KeyEvent.VK_SLASH -> GhosttyKey.SLASH
            KeyEvent.VK_LESS, KeyEvent.VK_GREATER -> GhosttyKey.INTL_BACKSLASH

            KeyEvent.VK_ALT -> if (right) GhosttyKey.ALT_RIGHT else GhosttyKey.ALT_LEFT
            KeyEvent.VK_ALT_GRAPH -> GhosttyKey.ALT_RIGHT
            KeyEvent.VK_CONTROL -> if (right) GhosttyKey.CONTROL_RIGHT else GhosttyKey.CONTROL_LEFT
            KeyEvent.VK_SHIFT -> if (right) GhosttyKey.SHIFT_RIGHT else GhosttyKey.SHIFT_LEFT
            KeyEvent.VK_META, KeyEvent.VK_WINDOWS -> if (right) GhosttyKey.META_RIGHT else GhosttyKey.META_LEFT
            KeyEvent.VK_CAPS_LOCK -> GhosttyKey.CAPS_LOCK
            KeyEvent.VK_CONTEXT_MENU -> GhosttyKey.CONTEXT_MENU
            KeyEvent.VK_BACK_SPACE -> GhosttyKey.BACKSPACE
            KeyEvent.VK_ENTER -> if (numpad) GhosttyKey.NUMPAD_ENTER else GhosttyKey.ENTER
            KeyEvent.VK_SPACE -> GhosttyKey.SPACE
            KeyEvent.VK_TAB -> GhosttyKey.TAB
            KeyEvent.VK_CONVERT -> GhosttyKey.CONVERT
            KeyEvent.VK_NONCONVERT -> GhosttyKey.NON_CONVERT
            KeyEvent.VK_KANA -> GhosttyKey.KANA_MODE

            KeyEvent.VK_DELETE -> if (numpad) GhosttyKey.NUMPAD_DELETE else GhosttyKey.DELETE
            KeyEvent.VK_END -> if (numpad) GhosttyKey.NUMPAD_END else GhosttyKey.END
            KeyEvent.VK_HELP -> GhosttyKey.HELP
            KeyEvent.VK_HOME -> if (numpad) GhosttyKey.NUMPAD_HOME else GhosttyKey.HOME
            KeyEvent.VK_INSERT -> if (numpad) GhosttyKey.NUMPAD_INSERT else GhosttyKey.INSERT
            KeyEvent.VK_PAGE_DOWN -> if (numpad) GhosttyKey.NUMPAD_PAGE_DOWN else GhosttyKey.PAGE_DOWN
            KeyEvent.VK_PAGE_UP -> if (numpad) GhosttyKey.NUMPAD_PAGE_UP else GhosttyKey.PAGE_UP

            KeyEvent.VK_DOWN -> if (numpad) GhosttyKey.NUMPAD_DOWN else GhosttyKey.ARROW_DOWN
            KeyEvent.VK_LEFT -> if (numpad) GhosttyKey.NUMPAD_LEFT else GhosttyKey.ARROW_LEFT
            KeyEvent.VK_RIGHT -> if (numpad) GhosttyKey.NUMPAD_RIGHT else GhosttyKey.ARROW_RIGHT
            KeyEvent.VK_UP -> if (numpad) GhosttyKey.NUMPAD_UP else GhosttyKey.ARROW_UP
            KeyEvent.VK_KP_DOWN -> GhosttyKey.NUMPAD_DOWN
            KeyEvent.VK_KP_LEFT -> GhosttyKey.NUMPAD_LEFT
            KeyEvent.VK_KP_RIGHT -> GhosttyKey.NUMPAD_RIGHT
            KeyEvent.VK_KP_UP -> GhosttyKey.NUMPAD_UP
            KeyEvent.VK_BEGIN, KeyEvent.VK_CLEAR -> GhosttyKey.NUMPAD_BEGIN

            KeyEvent.VK_NUM_LOCK -> GhosttyKey.NUM_LOCK
            KeyEvent.VK_ADD -> GhosttyKey.NUMPAD_ADD
            KeyEvent.VK_SUBTRACT -> GhosttyKey.NUMPAD_SUBTRACT
            KeyEvent.VK_MULTIPLY -> GhosttyKey.NUMPAD_MULTIPLY
            KeyEvent.VK_DIVIDE -> GhosttyKey.NUMPAD_DIVIDE
            KeyEvent.VK_DECIMAL -> GhosttyKey.NUMPAD_DECIMAL
            KeyEvent.VK_SEPARATOR -> GhosttyKey.NUMPAD_SEPARATOR

            KeyEvent.VK_ESCAPE -> GhosttyKey.ESCAPE
            KeyEvent.VK_PRINTSCREEN -> GhosttyKey.PRINT_SCREEN
            KeyEvent.VK_SCROLL_LOCK -> GhosttyKey.SCROLL_LOCK
            KeyEvent.VK_PAUSE -> GhosttyKey.PAUSE
            KeyEvent.VK_COPY -> GhosttyKey.COPY
            KeyEvent.VK_CUT -> GhosttyKey.CUT
            KeyEvent.VK_PASTE -> GhosttyKey.PASTE
            // Layout-specific keys (e.g. 'ö') have no W3C code; their text and
            // unshifted codepoint still reach the encoder.
            else -> GhosttyKey.UNIDENTIFIED
        }
    }

    /** Codepoint the key produces without shift, best effort (0 if unknown). */
    fun unshiftedCodepoint(e: KeyEvent): Int {
        val ext = e.extendedKeyCode
        if (ext and 0x01000000 != 0) return Character.toLowerCase(ext and 0x00ffffff)
        val code = e.keyCode
        return when (code) {
            in KeyEvent.VK_A..KeyEvent.VK_Z -> 'a'.code + (code - KeyEvent.VK_A)
            in KeyEvent.VK_0..KeyEvent.VK_9 -> '0'.code + (code - KeyEvent.VK_0)
            KeyEvent.VK_SPACE -> ' '.code
            KeyEvent.VK_BACK_QUOTE -> '`'.code
            KeyEvent.VK_BACK_SLASH -> '\\'.code
            KeyEvent.VK_OPEN_BRACKET -> '['.code
            KeyEvent.VK_CLOSE_BRACKET -> ']'.code
            KeyEvent.VK_COMMA -> ','.code
            KeyEvent.VK_EQUALS -> '='.code
            KeyEvent.VK_MINUS -> '-'.code
            KeyEvent.VK_PERIOD -> '.'.code
            KeyEvent.VK_QUOTE -> '\''.code
            KeyEvent.VK_SEMICOLON -> ';'.code
            KeyEvent.VK_SLASH -> '/'.code
            else -> 0
        }
    }

    fun mods(e: InputEvent): Int {
        var m = 0
        val ex = e.modifiersEx
        if (ex and InputEvent.SHIFT_DOWN_MASK != 0) m = m or J.MOD_SHIFT
        if (ex and InputEvent.CTRL_DOWN_MASK != 0) m = m or J.MOD_CTRL
        if (ex and InputEvent.ALT_DOWN_MASK != 0) m = m or J.MOD_ALT
        if (ex and InputEvent.META_DOWN_MASK != 0) m = m or J.MOD_SUPER
        if (e is KeyEvent) {
            val tk = runCatching { java.awt.Toolkit.getDefaultToolkit() }.getOrNull()
            if (tk != null) {
                if (runCatching { tk.getLockingKeyState(KeyEvent.VK_CAPS_LOCK) }.getOrDefault(false)) m = m or J.MOD_CAPS_LOCK
                if (runCatching { tk.getLockingKeyState(KeyEvent.VK_NUM_LOCK) }.getOrDefault(false)) m = m or J.MOD_NUM_LOCK
            }
            // Side bits for the modifier key itself.
            if (e.keyLocation == KeyEvent.KEY_LOCATION_RIGHT) {
                when (e.keyCode) {
                    KeyEvent.VK_SHIFT -> m = m or J.MOD_SHIFT_RIGHT
                    KeyEvent.VK_CONTROL -> m = m or J.MOD_CTRL_RIGHT
                    KeyEvent.VK_ALT -> m = m or J.MOD_ALT_RIGHT
                    KeyEvent.VK_META -> m = m or J.MOD_SUPER_RIGHT
                }
            }
        }
        return m
    }

    fun isAltGraph(e: InputEvent): Boolean = e.modifiersEx and InputEvent.ALT_GRAPH_DOWN_MASK != 0

    fun isModifierKey(code: Int): Boolean = when (code) {
        KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH,
        KeyEvent.VK_META, KeyEvent.VK_WINDOWS, KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_NUM_LOCK,
        KeyEvent.VK_SCROLL_LOCK,
        -> true
        else -> false
    }

    /**
     * Keys whose press never produces a KEY_TYPED character we want, so they
     * are encoded straight from KEY_PRESSED.
     */
    fun isFunctionalKey(code: Int): Boolean = when (code) {
        KeyEvent.VK_ENTER, KeyEvent.VK_TAB, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_ESCAPE,
        KeyEvent.VK_DELETE, KeyEvent.VK_INSERT, KeyEvent.VK_HOME, KeyEvent.VK_END,
        KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
        KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_KP_UP, KeyEvent.VK_KP_DOWN,
        KeyEvent.VK_KP_LEFT, KeyEvent.VK_KP_RIGHT, KeyEvent.VK_BEGIN, KeyEvent.VK_CLEAR,
        KeyEvent.VK_PAUSE, KeyEvent.VK_PRINTSCREEN, KeyEvent.VK_CONTEXT_MENU, KeyEvent.VK_HELP,
        in KeyEvent.VK_F1..KeyEvent.VK_F12, in KeyEvent.VK_F13..KeyEvent.VK_F24,
        -> true
        else -> false
    }
}
