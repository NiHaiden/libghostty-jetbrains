package com.github.nihaiden.ghostty.ui

import com.github.nihaiden.ghostty.session.ShellLauncher
import com.github.nihaiden.ghostty.session.TerminalSession
import com.github.nihaiden.ghostty.settings.GhosttySettings
import com.github.nihaiden.ghostty.vt.Frame
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollBar
import java.awt.Adjustable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * One terminal tab: the [TerminalPanel] plus a scrollbar and a find bar,
 * connected to a [TerminalSession].
 */
class GhosttyTerminalWidget(
    private val project: Project?,
    workingDirectory: String?,
) : JPanel(BorderLayout()), Disposable {

    private val settings get() = GhosttySettings.getInstance().state
    private val spec = ShellLauncher.spec(project, workingDirectory, settings)

    val session = TerminalSession(spec, 80, 24)
    val panel = TerminalPanel(project, session)
    private val scrollBar = JBScrollBar(Adjustable.VERTICAL)
    private val searchBar = TerminalSearchBar(panel)
    private var syncingScrollbar = false

    /** Listeners for the hosting tab (all called on the EDT). */
    var onTitleChanged: (String) -> Unit = {}
    var onExit: (Int) -> Unit = {}
    var onProgress: (state: Int, progress: Int?) -> Unit = { _, _ -> }

    val shellName: String = File(spec.command.firstOrNull() ?: "shell").name.removeSuffix(".exe")

    val workingDirectory: String? get() = panel.currentDirectory

    init {
        // The find bar floats over the top-right corner (like Ghostty's) instead of
        // taking a row, so opening it doesn't resize the terminal.
        val layers = object : JLayeredPane() {
            override fun doLayout() {
                panel.setBounds(0, 0, width, height)
                if (searchBar.isVisible) {
                    val pref = searchBar.preferredSize
                    val w = minOf(width, maxOf(JBUI.scale(380), pref.width))
                    searchBar.setBounds(width - w, 0, w, pref.height)
                }
            }

            override fun getPreferredSize(): Dimension = panel.preferredSize
        }
        // Pass layers as Any: as Int they'd pick add(Component, index) instead.
        layers.add(panel, JLayeredPane.DEFAULT_LAYER as Any)
        layers.add(searchBar, JLayeredPane.PALETTE_LAYER as Any)
        add(layers, BorderLayout.CENTER)
        add(scrollBar, BorderLayout.EAST)
        panel.currentDirectory = spec.workingDirectory
        Disposer.register(this, panel)

        scrollBar.addAdjustmentListener {
            if (!syncingScrollbar) {
                session.terminal.scrollToRow(scrollBar.value.toLong())
                panel.requestRedraw()
            }
        }
        panel.frameListener = { frame ->
            syncScrollbar(frame)
            searchBar.update(frame)
        }
        session.listener = SessionListener()

        val bus = ApplicationManager.getApplication().messageBus.connect(this)
        bus.subscribe(GhosttySettings.TOPIC, GhosttySettings.Listener { panel.applySettings() })
        bus.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { panel.applySettings() })
    }

    /** Starts the shell as soon as the panel is laid out. */
    fun start(onError: (Throwable) -> Unit) = panel.startWhenSized(onError)

    fun toggleSearch() = searchBar.toggle()

    private fun syncScrollbar(f: Frame) {
        syncingScrollbar = true
        try {
            val total = maxOf(f.scrollTotal, f.scrollLength)
            scrollBar.setValues(f.scrollOffset, f.scrollLength, 0, total)
            scrollBar.unitIncrement = 1
            scrollBar.blockIncrement = maxOf(1, f.scrollLength - 1)
        } finally {
            syncingScrollbar = false
        }
    }

    private inner class SessionListener : TerminalSession.Listener {
        override fun onOutput() = panel.requestRedraw()

        override fun onBell() = edt {
            when (settings.bell) {
                GhosttySettings.Bell.VISUAL -> panel.flashBell()
                GhosttySettings.Bell.AUDIBLE -> Toolkit.getDefaultToolkit().beep()
                GhosttySettings.Bell.NONE -> {}
            }
        }

        override fun onTitleChanged(title: String) = edt { onTitleChanged.invoke(title) }

        override fun onPwdChanged(pwd: String) {
            LinkFinder.pwdToPath(pwd)?.let { panel.currentDirectory = it }
        }

        // Only reported while OPT_ALLOW_CLIPBOARD_WRITE is on (see TerminalPanel).
        override fun onClipboardWrite(text: String, primary: Boolean) = edt {
            if (primary) {
                Toolkit.getDefaultToolkit().systemSelection?.setContents(StringSelection(text), null)
            } else {
                CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        }

        override fun onNotification(title: String, body: String) = edt {
            if (!settings.desktopNotifications) return@edt
            if (panel.isFocusOwner && panel.isShowing) return@edt
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Ghostty Terminal")
                .createNotification(title.ifBlank { shellName }, body, NotificationType.INFORMATION)
                .notify(project)
        }

        override fun onProgress(state: Int, progress: Int?) = edt { onProgress.invoke(state, progress) }

        override fun onExit(exitCode: Int) = edt { onExit.invoke(exitCode) }
    }

    private fun edt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    override fun dispose() {}
}
