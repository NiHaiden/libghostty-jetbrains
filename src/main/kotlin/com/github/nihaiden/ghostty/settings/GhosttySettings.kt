package com.github.nihaiden.ghostty.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

/** Application-wide settings for the Ghostty terminal. */
@Service(Service.Level.APP)
@State(name = "GhosttyTerminalSettings", storages = [Storage("ghostty-terminal.xml")])
class GhosttySettings : PersistentStateComponent<GhosttySettings.State> {

    enum class ColorSource { IDE, GHOSTTY_DEFAULT, GHOSTTY_THEME_FILE }
    enum class CursorStyle { BLOCK, BAR, UNDERLINE }
    enum class Bell { VISUAL, AUDIBLE, NONE }

    class State {
        /** Shell command line; empty = detect ($SHELL, PowerShell on Windows). */
        var shellCommand: String = ""

        /** Extra environment, "KEY=value" per line. */
        var environment: String = ""

        /** Empty = editor console font. */
        var fontFamily: String = ""

        /** <= 0 = editor console font size. */
        var fontSize: Float = 0f
        /** <= 0 = editor console line spacing. */
        var lineSpacing: Float = 0f

        var scrollbackMegabytes: Int = 10

        var cursorStyle: CursorStyle = CursorStyle.BLOCK
        var cursorBlink: Boolean = true

        var colorSource: ColorSource = ColorSource.IDE

        /** A Ghostty theme or config file (palette = N=#rrggbb, background = ...). */
        var themeFile: String = ""

        var copyOnSelect: Boolean = false

        /** Ask before pasting text that could run commands. */
        var confirmUnsafePaste: Boolean = true

        /** Let programs set the clipboard via OSC 52. */
        var allowClipboardWrite: Boolean = true

        /** macOS: treat Option as Alt instead of composing characters. */
        var optionAsAlt: Boolean = false

        /** Send almost all keystrokes to the terminal instead of IDE actions. */
        var overrideIdeShortcuts: Boolean = true

        var bell: Bell = Bell.VISUAL
        var closeTabOnExit: Boolean = true

        /** Draw box drawing / block elements geometrically so lines join up. */
        var builtinBoxDrawing: Boolean = true

        /** Show OSC 9 / 777 notifications from programs as IDE notifications. */
        var desktopNotifications: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun update(block: State.() -> Unit) {
        state.block()
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).settingsChanged()
    }

    fun interface Listener {
        fun settingsChanged()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("Ghostty terminal settings", Listener::class.java)

        @JvmStatic
        fun getInstance(): GhosttySettings = ApplicationManager.getApplication().getService(GhosttySettings::class.java)
    }
}
