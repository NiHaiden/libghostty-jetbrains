package com.github.nihaiden.ghostty.integration

import com.github.nihaiden.ghostty.toolwindow.GhosttyTerminalManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import org.jetbrains.plugins.terminal.ui.OpenPredefinedTerminalActionProvider

/**
 * Adds "Ghostty" to the built-in Terminal tool window's new-session dropdown
 * (the ▾ next to +). Loaded only when the Terminal plugin is present (see
 * ghostty-terminal.xml).
 */
class GhosttyPredefinedTerminalProvider : OpenPredefinedTerminalActionProvider {
    override fun listOpenPredefinedTerminalActions(project: Project): List<AnAction> = listOf(OpenGhosttyAction())

    private class OpenGhosttyAction : DumbAwareAction(
        "Ghostty",
        "Open a Ghostty (libghostty) terminal tab in the Terminal tool window",
        IconLoader.getIcon("/icons/ghostty.svg", GhosttyPredefinedTerminalProvider::class.java),
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val manager = GhosttyTerminalManager.getInstance(project)
            manager.createTabInTerminalToolWindow(manager.activeWidget?.workingDirectory)
        }
    }
}
