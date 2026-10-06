package com.github.nihaiden.ghostty.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

class GhosttyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val manager = GhosttyTerminalManager.getInstance(project)
        ActionManager.getInstance().getAction("Ghostty.NewTab")?.let { newTab ->
            (toolWindow as? ToolWindowEx)?.setTabActions(newTab)
        }
        manager.addTab(toolWindow, workingDirectory = null, requestFocus = false)
        // Last tab closed: hide the window; it reopens with a fresh tab.
        toolWindow.contentManager.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
            override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                if (toolWindow.contentManager.contentCount == 0 && !project.isDisposed) toolWindow.hide()
            }
        })
        toolWindow.setToHideOnEmptyContent(true)
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(shown: ToolWindow) {
                    if (shown.id == toolWindow.id && shown.contentManager.contentCount == 0) {
                        manager.addTab(shown, workingDirectory = null)
                    }
                }
            },
        )
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
