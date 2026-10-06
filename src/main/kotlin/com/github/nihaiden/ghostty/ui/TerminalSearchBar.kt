package com.github.nihaiden.ghostty.ui

import com.github.nihaiden.ghostty.vt.Frame
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Find bar: searches the screen and scrollback with libghostty's search. */
class TerminalSearchBar(private val panel: TerminalPanel) : JPanel(BorderLayout()) {
    private val field = SearchTextField(false)
    private val status = JBLabel()

    init {
        isVisible = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBUI.CurrentTheme.Popup.borderColor(true), 0, 1, 1, 1),
            JBUI.Borders.empty(2, 4),
        )
        add(field, BorderLayout.CENTER)
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(status)
            add(InplaceButton(IconButton("Previous match", AllIcons.Actions.PreviousOccurence)) { select(-1) })
            add(InplaceButton(IconButton("Next match", AllIcons.Actions.NextOccurence)) { select(1) })
            add(InplaceButton(IconButton("Close", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { close() })
        }
        add(right, BorderLayout.EAST)
        status.foreground = UIUtil.getContextHelpForeground()

        field.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
        })
        // IDE actions registered on the field win over SearchTextField's own key
        // bindings (it would otherwise eat Escape to clear the text).
        fun bind(vararg strokes: String, block: () -> Unit) {
            DumbAwareAction.create { block() }.registerCustomShortcutSet(
                CustomShortcutSet(*strokes.map { KeyboardShortcut(KeyStroke.getKeyStroke(it), null) }.toTypedArray()),
                field.textEditor,
            )
        }
        bind("ESCAPE") { close() }
        bind("ENTER", "DOWN", "F3") { select(1) }
        bind("shift ENTER", "UP", "shift F3") { select(-1) }
    }

    fun toggle() = if (isVisible && field.textEditor.isFocusOwner) close() else open()

    fun open() {
        isVisible = true
        parent?.doLayout()
        panel.session.terminal.selectionText?.takeIf { it.isNotBlank() && '\n' !in it }?.let { field.text = it }
        revalidate()
        field.selectText()
        field.requestFocusInWindow()
        changed()
    }

    fun close() {
        isVisible = false
        panel.session.terminal.setSearch(null)
        panel.requestRedraw()
        revalidate()
        panel.requestFocusInWindow()
    }

    private fun changed() {
        if (!isVisible) return
        panel.session.terminal.setSearch(field.text)
        panel.requestRedraw()
    }

    private fun select(dir: Int) {
        if (field.text.isEmpty()) return
        panel.session.terminal.searchSelect(dir)
        panel.requestRedraw()
    }

    /** Called with every new frame to refresh the "k of n" label. */
    fun update(frame: Frame) {
        if (!isVisible) return
        status.text = when {
            field.text.isEmpty() || frame.searchTotal < 0 -> ""
            frame.searchTotal == 0 -> if (frame.searchPending) "Searching…" else "No matches"
            frame.searchSelected >= 0 -> "${frame.searchSelected + 1} of ${frame.searchTotal}"
            frame.searchTotal == 1 -> "1 match"
            else -> "${frame.searchTotal} matches"
        }
    }
}
