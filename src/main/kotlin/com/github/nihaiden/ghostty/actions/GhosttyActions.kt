package com.github.nihaiden.ghostty.actions

import com.github.nihaiden.ghostty.settings.GhosttyConfigurable
import com.github.nihaiden.ghostty.toolwindow.GhosttyTerminalManager
import com.github.nihaiden.ghostty.ui.TerminalPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction

/** Opens a new Ghostty terminal tab. */
class NewTabAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = e.getData(TerminalPanel.PANEL_KEY)?.currentDirectory
            ?: GhosttyTerminalManager.getInstance(project).activeWidget?.workingDirectory
        GhosttyTerminalManager.getInstance(project).createTab(dir)
    }
}

/** "Open in Ghostty Terminal" for files and directories in the project view / editor tabs. */
class OpenInGhosttyAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && file.isInLocalFileSystem
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val dir = if (file.isDirectory) file else file.parent ?: return
        GhosttyTerminalManager.getInstance(project).createTab(dir.path)
    }
}

/** Base for actions acting on the focused terminal panel. */
abstract class PanelAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val panel = e.getData(TerminalPanel.PANEL_KEY)
        e.presentation.isEnabledAndVisible = panel != null
        if (panel != null) update(e, panel)
    }

    open fun update(e: AnActionEvent, panel: TerminalPanel) {}

    override fun actionPerformed(e: AnActionEvent) {
        perform(e.getData(TerminalPanel.PANEL_KEY) ?: return)
    }

    abstract fun perform(panel: TerminalPanel)
}

class CopyAction : PanelAction() {
    override fun update(e: AnActionEvent, panel: TerminalPanel) {
        e.presentation.isEnabled = panel.session.terminal.hasSelection
    }
    override fun perform(panel: TerminalPanel) {
        panel.copySelection()
    }
}

class PasteAction : PanelAction() {
    override fun perform(panel: TerminalPanel) = panel.paste()
}

class SelectAllAction : PanelAction() {
    override fun perform(panel: TerminalPanel) = panel.selectAll()
}

class FindAction : PanelAction() {
    override fun perform(panel: TerminalPanel) = panel.host.toggleSearch()
}

class ClearBufferAction : PanelAction() {
    override fun perform(panel: TerminalPanel) = panel.clearBuffer()
}

class CloseTabAction : PanelAction() {
    override fun perform(panel: TerminalPanel) = panel.host.closeTab()
}

class ResetZoomAction : PanelAction() {
    override fun perform(panel: TerminalPanel) = panel.zoomBy(0f)
}

class OpenSettingsAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, GhosttyConfigurable::class.java)
    }
}
