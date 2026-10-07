package com.github.nihaiden.ghostty.settings

import com.github.nihaiden.ghostty.GhosttyPlugin
import com.github.nihaiden.ghostty.session.ShellLauncher
import com.github.nihaiden.ghostty.vt.NativeLibrary
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.execution.ParametersListUtil

class GhosttyConfigurable : BoundSearchableConfigurable("Ghostty Terminal", "ghostty.terminal", "ghostty.terminal") {

    private val state get() = GhosttySettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        GhosttyPlugin.ensureInitialized()
        NativeLibrary.problem()?.let { problem ->
            row { comment("<b>Native library problem:</b> ${problem.replace("\n", "<br>")}") }
        }

        group("Shell") {
            row("Shell command:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select Shell"))
                    .bindText(state::shellCommand)
                    .align(AlignX.FILL)
                    .comment("Empty runs ${ParametersListUtil.join(ShellLauncher.defaultShell())}")
            }
            row("Environment:") {
                textArea().rows(3).align(AlignX.FILL).bindText(state::environment)
                    .comment("One KEY=value per line")
            }
            row {
                checkBox("Close tab when the shell exits successfully").bindSelected(state::closeTabOnExit)
            }
            row("Open new tabs in:") {
                comboBox(
                    EnumComboBoxModel(GhosttySettings.TabLocation::class.java),
                    textListCellRenderer {
                        when (it) {
                            GhosttySettings.TabLocation.TERMINAL_TOOL_WINDOW -> "Terminal tool window"
                            else -> "Ghostty tool window"
                        }
                    },
                ).bindItem(state::tabLocation.toNullableProperty())
                    .comment("The Terminal tool window's new-tab dropdown (+ ▾) always offers <b>Ghostty</b>")
            }
        }

        group("Appearance") {
            row("Font family:") {
                textField().bindText(state::fontFamily).comment("Empty uses the editor's console font")
            }
            row("Font size:") {
                textField().bindText(
                    { if (state.fontSize > 0) state.fontSize.toString() else "" },
                    { state.fontSize = it.toFloatOrNull() ?: 0f },
                ).columns(6).comment("Empty uses the console font size")
            }
            row("Line spacing:") {
                textField().bindText(
                    { if (state.lineSpacing > 0) state.lineSpacing.toString() else "" },
                    { state.lineSpacing = it.toFloatOrNull() ?: 0f },
                ).columns(6)
            }
            row("Colors:") {
                comboBox(
                    EnumComboBoxModel(GhosttySettings.ColorSource::class.java),
                    textListCellRenderer {
                        when (it) {
                            GhosttySettings.ColorSource.IDE -> "IDE console colors"
                            GhosttySettings.ColorSource.GHOSTTY_DEFAULT -> "Ghostty defaults"
                            GhosttySettings.ColorSource.GHOSTTY_THEME_FILE -> "Ghostty theme file"
                            null -> ""
                        }
                    },
                ).bindItem(state::colorSource.toNullableProperty())
            }
            row("Theme file:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select Ghostty Theme"))
                    .bindText(state::themeFile)
                    .align(AlignX.FILL)
                    .comment("A Ghostty theme or config file (palette = N=#rrggbb, background = …)")
            }
            row("Cursor:") {
                comboBox(EnumComboBoxModel(GhosttySettings.CursorStyle::class.java), titleCase<GhosttySettings.CursorStyle>())
                    .bindItem(state::cursorStyle.toNullableProperty())
                checkBox("Blink").bindSelected(state::cursorBlink)
            }
            row {
                checkBox("Draw box-drawing characters geometrically").bindSelected(state::builtinBoxDrawing)
            }
            row("Bell:") {
                comboBox(EnumComboBoxModel(GhosttySettings.Bell::class.java), titleCase<GhosttySettings.Bell>())
                    .bindItem(state::bell.toNullableProperty())
            }
            row("Scrollback (MB):") {
                intTextField(0..4096).bindIntText(state::scrollbackMegabytes).columns(6)
            }
        }

        group("Behavior") {
            row {
                checkBox("Send IDE shortcuts to the terminal (except window/navigation actions)")
                    .bindSelected(state::overrideIdeShortcuts)
            }
            row { checkBox("Copy on select").bindSelected(state::copyOnSelect) }
            row { checkBox("Confirm before pasting text that may run commands").bindSelected(state::confirmUnsafePaste) }
            row { checkBox("Allow programs to set the clipboard (OSC 52)").bindSelected(state::allowClipboardWrite) }
            row { checkBox("Show program notifications (OSC 9 / 777)").bindSelected(state::desktopNotifications) }
            row { checkBox("Use Option as Alt").bindSelected(state::optionAsAlt) }
                .visibleIf(ComponentPredicate.fromValue(SystemInfo.isMac))
        }
    }

    private fun <T : Enum<T>> titleCase() = textListCellRenderer<T?> {
        it?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: ""
    }

    override fun apply() {
        super.apply()
        GhosttySettings.getInstance().update {}
    }
}
