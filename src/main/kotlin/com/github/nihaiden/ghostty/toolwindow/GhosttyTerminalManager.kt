package com.github.nihaiden.ghostty.toolwindow

import com.github.nihaiden.ghostty.GhosttyPlugin
import com.github.nihaiden.ghostty.settings.GhosttySettings
import com.github.nihaiden.ghostty.ui.GhosttyTerminalWidget
import com.github.nihaiden.ghostty.ui.TerminalPanel
import com.github.nihaiden.ghostty.vt.NativeLibrary
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Owns the Ghostty tool window's tabs for a project. */
@Service(Service.Level.PROJECT)
class GhosttyTerminalManager(private val project: Project) {

    private val toolWindow: ToolWindow?
        get() = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)

    /** Opens a new terminal tab (showing the tool window) in [workingDirectory] or the project root. */
    fun createTab(workingDirectory: String? = null, requestFocus: Boolean = true) {
        val tw = toolWindow ?: return
        tw.activate({ addTab(tw, workingDirectory, requestFocus) }, requestFocus)
    }

    internal fun addTab(tw: ToolWindow, workingDirectory: String?, requestFocus: Boolean = true): Content {
        val cm = tw.contentManager
        val factory = ContentFactory.getInstance()

        NativeLibrary.problem()?.let { problem ->
            val content = factory.createContent(errorPanel(problem), "Ghostty", false)
            cm.addContent(content)
            cm.setSelectedContent(content)
            return content
        }

        val widget = GhosttyTerminalWidget(project, workingDirectory)
        val content = factory.createContent(widget, widget.shellName, false)
        content.isCloseable = true
        content.preferredFocusableComponent = widget.panel
        content.setDisposer(widget)
        content.putUserData(WIDGET_KEY, widget)

        val defaultName = widget.shellName
        var title = defaultName
        var progress: String? = null
        fun refreshName() {
            val base = title.ifBlank { defaultName }.let { if (it.length > 40) it.take(39) + "…" else it }
            content.displayName = if (progress != null) "$base [$progress]" else base
            content.description = title.ifBlank { null }
        }

        widget.panel.host = object : TerminalPanel.Host {
            override fun newTab() = createTab(widget.workingDirectory)
            override fun closeTab() {
                cm.removeContent(content, true)
            }
            override fun toggleSearch() = widget.toggleSearch()
        }
        widget.onTitleChanged = { title = it; refreshName() }
        widget.onProgress = { state, value ->
            progress = when (state) {
                1 -> "${value ?: 0}%"
                2 -> "error"
                3 -> "…"
                4 -> "paused"
                else -> null
            }
            refreshName()
        }
        widget.onExit = { code ->
            if (GhosttySettings.getInstance().state.closeTabOnExit && code == 0) {
                if (!project.isDisposed && cm.getIndexOfContent(content) >= 0) cm.removeContent(content, true)
            } else {
                title = "${title.ifBlank { defaultName }} (exited: $code)"
                refreshName()
            }
        }

        cm.addContent(content)
        cm.setSelectedContent(content, requestFocus)
        widget.start { error ->
            LOG.warn("Failed to start ${widget.shellName}", error)
            content.component = errorPanel("Failed to start the shell: ${error.message}")
            Disposer.dispose(widget)
        }
        return content
    }

    val activeWidget: GhosttyTerminalWidget?
        get() = toolWindow?.contentManager?.selectedContent?.getUserData(WIDGET_KEY)

    private fun errorPanel(message: String) = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(16)
        add(
            JBLabel(
                "<html><b>Ghostty terminal is unavailable</b><br><br>" +
                    message.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") +
                    "</html>",
                SwingConstants.LEFT,
            ).apply {
                foreground = UIUtil.getErrorForeground()
                verticalAlignment = SwingConstants.TOP
            },
            BorderLayout.CENTER,
        )
    }

    companion object {
        const val TOOL_WINDOW_ID = "Ghostty"
        private val LOG = logger<GhosttyTerminalManager>()
        private val WIDGET_KEY = com.intellij.openapi.util.Key.create<GhosttyTerminalWidget>("Ghostty.Widget")

        fun getInstance(project: Project): GhosttyTerminalManager {
            GhosttyPlugin.ensureInitialized()
            return project.getService(GhosttyTerminalManager::class.java)
        }
    }
}
