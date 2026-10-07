package com.github.nihaiden.ghostty.ui

import com.github.nihaiden.ghostty.session.TerminalSession
import com.github.nihaiden.ghostty.settings.GhosttySettings
import com.github.nihaiden.ghostty.vt.Frame
import com.github.nihaiden.ghostty.vt.GhosttyJb as J
import com.github.nihaiden.ghostty.vt.GhosttyKey
import com.github.nihaiden.ghostty.vt.GhosttyTerminal
import com.github.nihaiden.ghostty.vt.KeyMapper
import com.intellij.ide.BrowserUtil
import com.intellij.ide.CopyProvider
import com.intellij.ide.IdeEventQueue
import com.intellij.ide.PasteProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.JBUI
import java.awt.AWTEvent
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.font.TextHitInfo
import java.awt.im.InputMethodRequests
import java.io.File
import java.text.AttributedCharacterIterator
import java.text.AttributedString
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler
import kotlin.math.abs
import kotlin.math.max

/**
 * The terminal surface: draws frames from libghostty and turns AWT input into
 * terminal input. One per tab.
 */
class TerminalPanel(
    private val project: Project?,
    val session: TerminalSession,
) : JComponent(), Disposable, UiDataProvider {

    /** Called on the EDT after a new frame was captured. */
    var frameListener: ((Frame) -> Unit)? = null

    /** Actions the panel can't do itself (tabs, find bar). */
    interface Host {
        fun newTab() {}
        fun closeTab() {}
        fun toggleSearch() {}
    }

    var host: Host = object : Host {}

    private val terminal: GhosttyTerminal get() = session.terminal
    private val settings: GhosttySettings.State get() = GhosttySettings.getInstance().state

    private var zoom = 1f
    var fonts: TerminalFonts = TerminalFonts.create(settings)
        private set
    private var theme: TerminalTheme = TerminalTheme.current()
    private val renderer = TerminalRenderer()

    var frame: Frame? = null
        private set
    private val frameDirty = AtomicBoolean(true)
    private val repaintQueued = AtomicBoolean(false)

    private val padX get() = JBUI.scale(4)
    private val padY get() = JBUI.scale(2)
    var cols = 0
        private set
    var rows = 0
        private set
    private var started = false
    private var startError: ((Throwable) -> Unit)? = null

    private var focused = false
    private var blinkOn = true
    private val blinkTimer = Timer(600) {
        blinkOn = !blinkOn
        if (frame?.cursorBlinking == true) repaint()
    }
    private var bellUntil = 0L

    // Keyboard state.
    private val pressedKeys = HashSet<Int>()
    private var suppressTyped = false
    private var pendingKey = GhosttyKey.UNIDENTIFIED
    private var pendingMods = 0
    private var pendingUnshifted = 0
    private var pendingAction = J.KEY_PRESS
    private var dispatchedEvent: KeyEvent? = null

    // Mouse state.
    private var buttonsDown = 0
    private var selecting = false
    private var wheelRemainder = 0.0
    private var lastMouse: MouseEvent? = null
    private val autoscrollTimer = Timer(50) { autoscrollTick() }

    // Link hover (Ctrl/Cmd + mouse).
    private var hoverRow = -1
    private var hoverStart = 0
    private var hoverEnd = 0
    private var hoverLink: LinkFinder.Link? = null
    private var hoverUri: String? = null

    // IME.
    private var preedit: String? = null

    @Volatile
    var currentDirectory: String? = null

    init {
        isFocusable = true
        isOpaque = true
        focusTraversalKeysEnabled = false
        enableEvents(AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or
            AWTEvent.MOUSE_WHEEL_EVENT_MASK or AWTEvent.FOCUS_EVENT_MASK or AWTEvent.INPUT_METHOD_EVENT_MASK)
        enableInputMethods(true)
        addInputMethodListener(ImeListener())
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        transferHandler = FileDropHandler()

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = updateGeometry()
            override fun componentShown(e: ComponentEvent) = updateGeometry()
        })
        applySettings()
        IdeEventQueue.getInstance().addDispatcher(
            object : IdeEventQueue.NonLockedEventDispatcher {
                override fun dispatch(e: AWTEvent): Boolean = dispatchShortcut(e)
            },
            this,
        )
        blinkTimer.isRepeats = true
        Disposer.register(this, session)
    }

    // ---- Lifecycle & configuration ------------------------------------------

    /** Starts the shell once the panel has a real size (so the pty starts with the right dimensions). */
    fun startWhenSized(onError: (Throwable) -> Unit) {
        startError = onError
        if (cols > 0) startSession()
    }

    private fun startSession() {
        if (started || startError == null) return
        started = true
        try {
            session.start(cols, rows)
        } catch (t: Throwable) {
            startError?.invoke(t)
        }
    }

    /** Re-reads settings, fonts and colors. */
    fun applySettings() {
        val s = settings
        fonts = TerminalFonts.create(s, zoom)
        theme = TerminalTheme.current(s)
        terminal.setColors(theme.foreground, theme.background, theme.cursor, theme.palette)
        terminal.setOption(J.OPT_COLOR_SCHEME, if (theme.dark) 1 else 0)
        terminal.setOption(J.OPT_SCROLLBACK_BYTES, s.scrollbackMegabytes.toLong() * 1024 * 1024)
        terminal.setOption(
            J.OPT_CURSOR_STYLE,
            when (s.cursorStyle) {
                GhosttySettings.CursorStyle.BAR -> J.CURSOR_BAR.toLong()
                GhosttySettings.CursorStyle.UNDERLINE -> J.CURSOR_UNDERLINE.toLong()
                GhosttySettings.CursorStyle.BLOCK -> J.CURSOR_BLOCK.toLong()
            },
        )
        terminal.setOption(J.OPT_CURSOR_BLINK, if (s.cursorBlink) 1 else 0)
        terminal.setOption(J.OPT_ALLOW_CLIPBOARD_WRITE, if (s.allowClipboardWrite) 1 else 0)
        terminal.setOption(J.OPT_OPTION_AS_ALT, if (SystemInfo.isMac && s.optionAsAlt) 1 else 0)
        val interval = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int ?: 500
        terminal.setOption(J.OPT_CLICK_INTERVAL_MS, interval.toLong())
        background = java.awt.Color(theme.background)
        cols = 0 // force a resize with the new cell size
        updateGeometry()
        requestRedraw()
    }

    private fun updateGeometry() {
        if (width <= 0 || height <= 0) return
        val newCols = max(1, (width - 2 * padX) / fonts.cellWidth)
        val newRows = max(1, (height - 2 * padY) / fonts.cellHeight)
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        session.resize(cols, rows, fonts.cellWidth, fonts.cellHeight, padX, padY)
        startSession()
        requestRedraw()
    }

    fun zoomBy(delta: Float) {
        zoom = if (delta == 0f) 1f else (zoom + delta).coerceIn(0.5f, 4f)
        applySettings()
    }

    override fun dispose() {
        blinkTimer.stop()
        autoscrollTimer.stop()
    }

    // ---- Painting -----------------------------------------------------------

    /** Thread-safe: marks the terminal as changed and schedules a repaint. */
    fun requestRedraw() {
        frameDirty.set(true)
        if (repaintQueued.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                repaintQueued.set(false)
                repaint()
            }
        }
    }

    private fun pullFrame() {
        if (!frameDirty.getAndSet(false)) return
        val f = terminal.snapshot() ?: return
        frame = f
        if (f.searchPending) requestRedraw()
        updateBlinkTimer(f)
        frameListener?.invoke(f)
    }

    override fun paintComponent(g: Graphics) {
        pullFrame()
        val g2 = g.create() as Graphics2D
        try {
            val f = frame
            if (f == null) {
                g2.color = java.awt.Color(theme.background)
                g2.fillRect(0, 0, width, height)
                return
            }
            val ctx = TerminalRenderer.Context(
                fonts = fonts,
                theme = theme,
                padX = padX,
                padY = padY,
                focused = focused,
                cursorBlinkOn = blinkOn,
                builtinBoxDrawing = settings.builtinBoxDrawing,
                linkRow = hoverRow,
                linkStart = hoverStart,
                linkEnd = hoverEnd,
                preedit = preedit,
            )
            renderer.paint(g2, f, ctx, width, height)
            if (System.currentTimeMillis() < bellUntil) {
                g2.color = java.awt.Color(f.foreground and 0xffffff or (0x30 shl 24), true)
                g2.fillRect(0, 0, width, height)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun updateBlinkTimer(f: Frame) {
        val shouldBlink = focused && f.cursorBlinking && f.cursorVisible
        if (shouldBlink && !blinkTimer.isRunning) {
            blinkOn = true
            blinkTimer.start()
        } else if (!shouldBlink && blinkTimer.isRunning) {
            blinkTimer.stop()
            blinkOn = true
        }
    }

    fun flashBell() {
        bellUntil = System.currentTimeMillis() + 120
        repaint()
        Timer(130) { repaint() }.apply { isRepeats = false; start() }
    }

    // ---- Output helpers -----------------------------------------------------

    private fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        session.send(bytes)
    }

    /** Typing snaps the view back to the prompt and drops a stale selection. */
    private fun afterUserInput() {
        if (frame?.viewportAtBottom == false) terminal.scrollToBottom()
        if (terminal.hasSelection && !selecting) terminal.clearSelection()
        blinkOn = true
        requestRedraw()
    }

    // ---- Clipboard ----------------------------------------------------------

    fun copySelection(): Boolean {
        val text = terminal.selectionText ?: return false
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        return true
    }

    fun paste() {
        val text = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor) ?: return
        pasteText(text)
    }

    private fun pastePrimarySelection() {
        val sel = Toolkit.getDefaultToolkit().systemSelection ?: return paste()
        val text = runCatching { sel.getData(DataFlavor.stringFlavor) as? String }.getOrNull() ?: return
        pasteText(text)
    }

    fun pasteText(text: String) {
        if (text.isEmpty()) return
        when (terminal.paste(text, allowUnsafe = false)) {
            GhosttyTerminal.PasteResult.UNSAFE -> {
                val ok = !settings.confirmUnsafePaste || MessageDialogBuilder
                    .yesNo(
                        "Paste Multi-Line Text?",
                        "The text contains line breaks or control sequences that may run commands immediately:\n\n" +
                            text.take(400) + (if (text.length > 400) "\n…" else ""),
                    )
                    .yesText("Paste")
                    .noText("Cancel")
                    .ask(this)
                if (ok) terminal.paste(text, allowUnsafe = true)
            }
            else -> {}
        }
        afterUserInput()
        requestFocusInWindow()
    }

    fun selectAll() {
        terminal.selectAll()
        requestRedraw()
    }

    /** Clears the scrollback and asks the shell to redraw its prompt (like Cmd+K in macOS terminals). */
    fun clearBuffer() {
        if (frame?.altScreen == true) return
        terminal.write("\u001b[3J".encodeToByteArray())
        terminal.scrollToBottom()
        send(byteArrayOf(0x0c)) // Ctrl+L
        requestRedraw()
    }

    private val copyProvider = object : CopyProvider {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun performCopy(dataContext: DataContext) {
            copySelection()
        }
        override fun isCopyEnabled(dataContext: DataContext) = terminal.hasSelection
        override fun isCopyVisible(dataContext: DataContext) = true
    }

    private val pasteProvider = object : PasteProvider {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun performPaste(dataContext: DataContext) = paste()
        override fun isPastePossible(dataContext: DataContext) = true
        override fun isPasteEnabled(dataContext: DataContext) = true
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[PANEL_KEY] = this
        sink[PlatformDataKeys.COPY_PROVIDER] = copyProvider
        sink[PlatformDataKeys.PASTE_PROVIDER] = pasteProvider
    }

    // ---- Keyboard -----------------------------------------------------------

    /**
     * IDE-wide event dispatcher hook: while this panel has focus, keystrokes go
     * to the terminal instead of IDE actions (except an allowlist), so things
     * like Ctrl+R, Ctrl+W or Esc reach the shell.
     */
    private fun dispatchShortcut(e: AWTEvent): Boolean {
        // At this stage the event's source is still the window; AWT retargets it to
        // the focus owner later, so ask the focus manager instead.
        if (e !is KeyEvent || e.id != KeyEvent.KEY_PRESSED) return false
        if (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner !== this) return false
        if (!settings.overrideIdeShortcuts) return false
        if (isLocalShortcut(e)) {
            processKeyDirect(e)
            return true
        }
        // Cmd shortcuts belong to the IDE on macOS.
        if (SystemInfo.isMac && e.isMetaDown) return false
        if (isAllowedIdeShortcut(e)) return false
        processKeyDirect(e)
        return e.isConsumed
    }

    private fun processKeyDirect(e: KeyEvent) {
        dispatchedEvent = e
        handleKey(e)
    }

    private fun isAllowedIdeShortcut(e: KeyEvent): Boolean {
        val stroke = KeyStroke.getKeyStrokeForEvent(e)
        val ids = KeymapManager.getInstance()?.activeKeymap?.getActionIds(stroke) ?: return false
        return ids.any { it in IDE_ACTIONS_ALLOWED || it.startsWith("Activate") && it.endsWith("ToolWindow") || it.startsWith("Ghostty.") }
    }

    override fun processKeyEvent(e: KeyEvent) {
        val early = dispatchedEvent
        if (early != null && e.id == early.id && e.`when` == early.`when` && e.keyCode == early.keyCode) {
            // Already handled by dispatchShortcut; this is the same press arriving normally.
            dispatchedEvent = null
        } else {
            handleKey(e)
        }
        if (!e.isConsumed) super.processKeyEvent(e)
    }

    private val isMac get() = SystemInfo.isMac

    /** Shortcuts the terminal handles itself rather than sending to the shell. */
    private fun isLocalShortcut(e: KeyEvent): Boolean = localShortcut(e) != null

    private fun localShortcut(e: KeyEvent): (() -> Unit)? {
        val code = e.keyCode
        val ctrl = e.isControlDown
        val shift = e.isShiftDown
        val alt = e.isAltDown
        val meta = e.isMetaDown
        // "Primary" modifier: Cmd on macOS, Ctrl+Shift elsewhere.
        val primary = if (isMac) meta && !ctrl && !alt else ctrl && shift && !alt && !meta
        if (primary) {
            when (code) {
                KeyEvent.VK_C -> return { copySelection() }
                KeyEvent.VK_V -> return { paste() }
                // Not Ctrl+Shift+A elsewhere: that's the IDE's Find Action.
                KeyEvent.VK_A -> if (isMac) return { selectAll() }
                KeyEvent.VK_F -> return { host.toggleSearch() }
                KeyEvent.VK_T -> return { host.newTab() }
                KeyEvent.VK_W -> return { host.closeTab() }
                KeyEvent.VK_K -> if (isMac) return { clearBuffer() }
                KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> return { zoomBy(0.1f) }
                KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> return { zoomBy(-0.1f) }
                KeyEvent.VK_0 -> return { zoomBy(0f) }
            }
        }
        if (!isMac && ctrl && !shift && !alt && code == KeyEvent.VK_C && terminal.hasSelection) {
            // Windows Terminal style: Ctrl+C copies when there's a selection, otherwise interrupts.
            return { copySelection(); terminal.clearSelection(); requestRedraw() }
        }
        if (!isMac && ctrl && !shift && code == KeyEvent.VK_INSERT) return { copySelection() }
        if (shift && !ctrl && !alt && !meta) {
            val f = frame
            when (code) {
                KeyEvent.VK_INSERT -> return { paste() }
                KeyEvent.VK_PAGE_UP -> if (f?.altScreen != true) return { terminal.scrollBy(-(rows - 1).coerceAtLeast(1)); requestRedraw() }
                KeyEvent.VK_PAGE_DOWN -> if (f?.altScreen != true) return { terminal.scrollBy((rows - 1).coerceAtLeast(1)); requestRedraw() }
                KeyEvent.VK_HOME -> if (f?.altScreen != true) return { terminal.scrollToTop(); requestRedraw() }
                KeyEvent.VK_END -> if (f?.altScreen != true) return { terminal.scrollToBottom(); requestRedraw() }
            }
        }
        return null
    }

    private fun handleKey(e: KeyEvent) {
        when (e.id) {
            KeyEvent.KEY_PRESSED -> keyPressed(e)
            KeyEvent.KEY_TYPED -> keyTyped(e)
            KeyEvent.KEY_RELEASED -> keyReleased(e)
        }
    }

    private fun keyPressed(e: KeyEvent) {
        localShortcut(e)?.let {
            // Run outside the event dispatcher (some of these show dialogs).
            SwingUtilities.invokeLater(it)
            suppressTyped = true
            e.consume()
            return
        }
        val code = e.keyCode
        // Leave the remaining Cmd shortcuts to the IDE on macOS.
        if (isMac && e.isMetaDown) return
        val mods = KeyMapper.mods(e)
        val key = KeyMapper.key(e)
        val unshifted = KeyMapper.unshiftedCodepoint(e)
        val action = if (pressedKeys.add(code)) J.KEY_PRESS else J.KEY_REPEAT
        suppressTyped = false

        if (KeyMapper.isModifierKey(code)) {
            // Only produces output under the Kitty "report all keys" mode.
            send(terminal.encodeKey(action, key, mods, 0, unshifted, null))
            return
        }

        val ch = e.keyChar
        val printable = ch != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(ch)
        val ctrl = mods and J.MOD_CTRL != 0
        val alt = mods and J.MOD_ALT != 0
        val altGr = KeyMapper.isAltGraph(e) || (SystemInfo.isWindows && ctrl && alt && printable)
        val macOptionText = isMac && alt && !settings.optionAsAlt
        val chord = (ctrl || alt || mods and J.MOD_SUPER != 0) && !altGr && !macOptionText

        if (KeyMapper.isFunctionalKey(code) || chord) {
            val text = chordText(e, mods, unshifted, printable)
            val bytes = terminal.encodeKey(action, key, mods, 0, unshifted, text)
            suppressTyped = true
            if (bytes.isNotEmpty()) {
                send(bytes)
                afterUserInput()
            }
            e.consume()
        } else {
            // The character arrives with KEY_TYPED (respecting layouts and dead keys).
            pendingKey = key
            pendingMods = mods
            pendingUnshifted = unshifted
            pendingAction = action
        }
    }

    /** Text a chord key would produce without Ctrl, for the encoder. */
    private fun chordText(e: KeyEvent, mods: Int, unshifted: Int, printable: Boolean): String? {
        if (printable) return e.keyChar.toString()
        if (unshifted == 0) return null
        val shifted = mods and J.MOD_SHIFT != 0
        return if (Character.isLetter(unshifted)) {
            String(Character.toChars(if (shifted) Character.toUpperCase(unshifted) else unshifted))
        } else if (!shifted) {
            String(Character.toChars(unshifted))
        } else {
            null
        }
    }

    private fun keyTyped(e: KeyEvent) {
        if (suppressTyped) {
            e.consume()
            return
        }
        val ch = e.keyChar
        if (ch == KeyEvent.CHAR_UNDEFINED || Character.isISOControl(ch)) {
            e.consume()
            return
        }
        val text = ch.toString()
        // Shift/Option/AltGr were used up producing the character.
        val consumed = pendingMods and (J.MOD_SHIFT or J.MOD_ALT or J.MOD_CTRL)
        var bytes = terminal.encodeKey(pendingAction, pendingKey, pendingMods, consumed, pendingUnshifted, text)
        if (bytes.isEmpty()) bytes = text.encodeToByteArray()
        send(bytes)
        pendingKey = GhosttyKey.UNIDENTIFIED
        pendingMods = 0
        pendingUnshifted = 0
        afterUserInput()
        e.consume()
    }

    private fun keyReleased(e: KeyEvent) {
        pressedKeys.remove(e.keyCode)
        if (e.keyCode == (if (isMac) KeyEvent.VK_META else KeyEvent.VK_CONTROL)) clearHover()
        if (isMac && e.isMetaDown) return
        // Only produces output when the Kitty protocol asked for release events.
        send(terminal.encodeKey(J.KEY_RELEASE, KeyMapper.key(e), KeyMapper.mods(e), 0, KeyMapper.unshiftedCodepoint(e), null))
        e.consume()
    }

    // ---- Focus --------------------------------------------------------------

    override fun processFocusEvent(e: FocusEvent) {
        super.processFocusEvent(e)
        val gained = e.id == FocusEvent.FOCUS_GAINED
        if (gained == focused) return
        focused = gained
        pressedKeys.clear()
        if (!gained) clearHover()
        send(terminal.encodeFocus(gained))
        terminal.setOption(J.OPT_FOCUSED, if (gained) 1 else 0)
        blinkOn = true
        frame?.let(::updateBlinkTimer)
        requestRedraw()
    }

    // ---- Mouse --------------------------------------------------------------

    private fun reportsMouse(e: MouseEvent): Boolean = frame?.mouseTracking == true && !e.isShiftDown

    private fun buttonOf(e: MouseEvent): Int = when (e.button) {
        MouseEvent.BUTTON1 -> J.BUTTON_LEFT
        MouseEvent.BUTTON2 -> J.BUTTON_MIDDLE
        MouseEvent.BUTTON3 -> J.BUTTON_RIGHT
        else -> J.BUTTON_NONE
    }

    private fun linkModifier(e: InputEvent) = if (isMac) e.isMetaDown else e.isControlDown

    override fun processMouseEvent(e: MouseEvent) {
        when (e.id) {
            MouseEvent.MOUSE_PRESSED -> mousePressed(e)
            MouseEvent.MOUSE_RELEASED -> mouseReleased(e)
            MouseEvent.MOUSE_EXITED -> clearHover()
        }
        super.processMouseEvent(e)
    }

    override fun processMouseMotionEvent(e: MouseEvent) {
        when (e.id) {
            MouseEvent.MOUSE_DRAGGED -> mouseDragged(e)
            MouseEvent.MOUSE_MOVED -> mouseMoved(e)
        }
        super.processMouseMotionEvent(e)
    }

    private fun mousePressed(e: MouseEvent) {
        requestFocusInWindow()
        lastMouse = e
        if (reportsMouse(e)) {
            val button = buttonOf(e)
            buttonsDown = buttonsDown or (1 shl button)
            send(terminal.encodeMouse(J.MOUSE_PRESS, button, KeyMapper.mods(e), e.x.toFloat(), e.y.toFloat(), true))
            e.consume()
            return
        }
        if (e.isPopupTrigger) {
            showPopup(e)
            return
        }
        when (e.button) {
            MouseEvent.BUTTON1 -> {
                if (linkModifier(e) && openLinkAt(e)) return
                selecting = true
                terminal.selectEvent(J.SEL_PRESS, e.x.toDouble(), e.y.toDouble(), System.nanoTime(), e.isAltDown)
                requestRedraw()
            }
            MouseEvent.BUTTON2 -> if (SystemInfo.isLinux) pastePrimarySelection()
        }
    }

    private fun mouseDragged(e: MouseEvent) {
        lastMouse = e
        if (buttonsDown != 0 && frame?.mouseTracking == true) {
            val button = when {
                buttonsDown and (1 shl J.BUTTON_LEFT) != 0 -> J.BUTTON_LEFT
                buttonsDown and (1 shl J.BUTTON_MIDDLE) != 0 -> J.BUTTON_MIDDLE
                else -> J.BUTTON_RIGHT
            }
            send(terminal.encodeMouse(J.MOUSE_MOTION, button, KeyMapper.mods(e), e.x.toFloat(), e.y.toFloat(), true))
            return
        }
        if (!selecting) return
        val r = terminal.selectEvent(J.SEL_DRAG, e.x.toDouble(), e.y.toDouble(), 0, e.isAltDown)
        if (r and (J.SEL_R_AUTOSCROLL_UP or J.SEL_R_AUTOSCROLL_DOWN) != 0) {
            if (!autoscrollTimer.isRunning) autoscrollTimer.start()
        } else {
            autoscrollTimer.stop()
        }
        requestRedraw()
    }

    private fun autoscrollTick() {
        val e = lastMouse
        if (!selecting || e == null) {
            autoscrollTimer.stop()
            return
        }
        val r = terminal.selectEvent(J.SEL_AUTOSCROLL_TICK, e.x.toDouble(), e.y.toDouble(), 0, e.isAltDown)
        if (r and (J.SEL_R_AUTOSCROLL_UP or J.SEL_R_AUTOSCROLL_DOWN) == 0) autoscrollTimer.stop()
        requestRedraw()
    }

    private fun mouseReleased(e: MouseEvent) {
        if (buttonsDown != 0) {
            val button = buttonOf(e)
            buttonsDown = buttonsDown and (1 shl button).inv()
            send(terminal.encodeMouse(J.MOUSE_RELEASE, button, KeyMapper.mods(e), e.x.toFloat(), e.y.toFloat(), buttonsDown != 0))
            return
        }
        if (e.isPopupTrigger) {
            showPopup(e)
            return
        }
        if (selecting && e.button == MouseEvent.BUTTON1) {
            selecting = false
            autoscrollTimer.stop()
            terminal.selectEvent(J.SEL_RELEASE, e.x.toDouble(), e.y.toDouble(), 0, e.isAltDown)
            terminal.selectionText?.let { text ->
                Toolkit.getDefaultToolkit().systemSelection?.setContents(StringSelection(text), null)
                if (settings.copyOnSelect) CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
            requestRedraw()
        }
    }

    private fun mouseMoved(e: MouseEvent) {
        lastMouse = e
        if (frame?.mouseTracking == true) {
            // Any-event tracking (mode 1003) wants plain motion; the encoder drops it otherwise.
            send(terminal.encodeMouse(J.MOUSE_MOTION, J.BUTTON_NONE, KeyMapper.mods(e), e.x.toFloat(), e.y.toFloat(), false))
        }
        updateHover(e)
    }

    override fun processMouseWheelEvent(e: MouseWheelEvent) {
        if (linkModifier(e) && !e.isShiftDown) {
            zoomBy(if (e.wheelRotation < 0) 0.1f else -0.1f)
            e.consume()
            return
        }
        val f = frame ?: return
        wheelRemainder += e.preciseWheelRotation * if (e.scrollType == MouseWheelEvent.WHEEL_UNIT_SCROLL) e.scrollAmount.toDouble() else rows.toDouble()
        val lines = wheelRemainder.toInt()
        if (lines == 0) {
            e.consume()
            return
        }
        wheelRemainder -= lines
        when {
            f.mouseTracking && !e.isShiftDown -> {
                val button = if (lines < 0) J.BUTTON_WHEEL_UP else J.BUTTON_WHEEL_DOWN
                repeat(abs(lines).coerceAtMost(rows)) {
                    send(terminal.encodeMouse(J.MOUSE_PRESS, button, KeyMapper.mods(e), e.x.toFloat(), e.y.toFloat(), false))
                }
            }
            f.altScreen && terminal.mode(1007) -> {
                // Alternate scroll mode: wheel becomes arrow keys in full-screen apps.
                val key = if (lines < 0) GhosttyKey.ARROW_UP else GhosttyKey.ARROW_DOWN
                val bytes = terminal.encodeKey(J.KEY_PRESS, key, 0, 0, 0, null)
                repeat(abs(lines).coerceAtMost(rows)) { send(bytes) }
            }
            else -> {
                terminal.scrollBy(lines)
                requestRedraw()
            }
        }
        e.consume()
    }

    private fun showPopup(e: MouseEvent) {
        val group = ActionManager.getInstance().getAction("Ghostty.TerminalPopup") as? ActionGroup ?: return
        val popup = ActionManager.getInstance().createActionPopupMenu("GhosttyTerminal", group)
        popup.setTargetComponent(this)
        popup.component.show(this, e.x, e.y)
    }

    // ---- Links --------------------------------------------------------------

    private fun cellAt(e: MouseEvent): Pair<Int, Int>? {
        val f = frame ?: return null
        val col = (e.x - padX) / fonts.cellWidth
        val row = (e.y - padY) / fonts.cellHeight
        if (col !in 0 until f.cols || row !in 0 until f.rows) return null
        return col to row
    }

    /** Row text plus, for each char, the column it came from. */
    private fun rowWithColumns(f: Frame, row: Int): Pair<String, IntArray> {
        val sb = StringBuilder(f.cols)
        val map = ArrayList<Int>(f.cols)
        for (col in 0 until f.cols) {
            val w = f.wide(col, row)
            if (w == J.WIDE_SPACER_TAIL || w == J.WIDE_SPACER_HEAD) continue
            val t = f.text(col, row).ifEmpty { " " }
            sb.append(t)
            repeat(t.length) { map.add(col) }
        }
        return sb.toString() to map.toIntArray()
    }

    private fun updateHover(e: MouseEvent) {
        val f = frame
        val cell = if (linkModifier(e)) cellAt(e) else null
        if (f == null || cell == null) return clearHover()
        val (col, row) = cell
        if (f.attrs(col, row) and J.A_HYPERLINK != 0) {
            val uri = terminal.hyperlinkAt(col, row)
            if (uri != null) {
                // Extend the hover over the neighbouring cells of the same link.
                var s = col
                var en = col
                while (s > 0 && f.attrs(s - 1, row) and J.A_HYPERLINK != 0) s--
                while (en < f.cols - 1 && f.attrs(en + 1, row) and J.A_HYPERLINK != 0) en++
                setHover(row, s, en, null, uri)
                return
            }
        }
        val (text, cols) = rowWithColumns(f, row)
        val charIndex = cols.indexOfFirst { it >= col }.takeIf { it >= 0 } ?: return clearHover()
        val link = LinkFinder.findAt(text, charIndex, currentDirectory) ?: return clearHover()
        setHover(row, cols[link.start], cols[link.end.coerceAtMost(cols.size - 1)], link, null)
    }

    private fun setHover(row: Int, start: Int, end: Int, link: LinkFinder.Link?, uri: String?) {
        if (hoverRow == row && hoverStart == start && hoverEnd == end) return
        hoverRow = row
        hoverStart = start
        hoverEnd = end
        hoverLink = link
        hoverUri = uri
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        repaint()
    }

    private fun clearHover() {
        if (hoverRow < 0) return
        hoverRow = -1
        hoverLink = null
        hoverUri = null
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        repaint()
    }

    private fun openLinkAt(e: MouseEvent): Boolean {
        updateHover(e)
        hoverUri?.let {
            BrowserUtil.browse(it)
            return true
        }
        when (val link = hoverLink) {
            is LinkFinder.Url -> BrowserUtil.browse(link.url)
            is LinkFinder.FilePath -> {
                val p = project ?: return false
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(link.path)) ?: return false
                OpenFileDescriptor(p, vf, (link.line ?: 1) - 1, (link.column ?: 1) - 1).navigate(true)
            }
            null -> return false
        }
        return true
    }

    // ---- IME ----------------------------------------------------------------

    private inner class ImeListener : InputMethodListener {
        override fun inputMethodTextChanged(event: InputMethodEvent) {
            val text = event.text
            val committedCount = event.committedCharacterCount
            if (text != null) {
                val sb = StringBuilder()
                var c = text.first()
                var i = 0
                while (c != AttributedCharacterIterator.DONE && i < committedCount) {
                    sb.append(c); c = text.next(); i++
                }
                if (sb.isNotEmpty()) {
                    send(sb.toString().encodeToByteArray())
                    afterUserInput()
                }
                val rest = StringBuilder()
                while (c != AttributedCharacterIterator.DONE) {
                    rest.append(c); c = text.next()
                }
                preedit = rest.toString().ifEmpty { null }
            } else {
                preedit = null
            }
            repaint()
            event.consume()
        }

        override fun caretPositionChanged(event: InputMethodEvent) {
            event.consume()
        }
    }

    override fun getInputMethodRequests(): InputMethodRequests = object : InputMethodRequests {
        override fun getTextLocation(offset: TextHitInfo?): Rectangle {
            val f = frame
            val x = padX + (f?.cursorX ?: 0) * fonts.cellWidth
            val y = padY + (f?.cursorY ?: 0) * fonts.cellHeight
            val p = java.awt.Point(x, y)
            SwingUtilities.convertPointToScreen(p, this@TerminalPanel)
            return Rectangle(p.x, p.y, fonts.cellWidth, fonts.cellHeight)
        }

        override fun getLocationOffset(x: Int, y: Int): TextHitInfo? = null
        override fun getInsertPositionOffset(): Int = 0
        override fun getCommittedText(begin: Int, end: Int, attributes: Array<out AttributedCharacterIterator.Attribute>?) =
            AttributedString("").iterator
        override fun getCommittedTextLength(): Int = 0
        override fun cancelLatestCommittedText(attributes: Array<out AttributedCharacterIterator.Attribute>?) = null
        override fun getSelectedText(attributes: Array<out AttributedCharacterIterator.Attribute>?): AttributedCharacterIterator =
            AttributedString("").iterator
    }

    // ---- Drag & drop --------------------------------------------------------

    /** Dropping files pastes their shell-quoted paths. */
    private inner class FileDropHandler : TransferHandler() {
        override fun canImport(support: TransferSupport) =
            support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || support.isDataFlavorSupported(DataFlavor.stringFlavor)

        override fun importData(support: TransferSupport): Boolean {
            val t = support.transferable
            val text = if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                files.joinToString(" ") { shellQuote(it.absolutePath) }
            } else {
                t.getTransferData(DataFlavor.stringFlavor) as? String ?: return false
            }
            pasteText(text)
            return true
        }

        private fun shellQuote(s: String): String =
            if (SystemInfo.isWindows) "\"$s\"" else if (s.all { it.isLetterOrDigit() || it in "/._-+:@%" }) s else "'" + s.replace("'", "'\\''") + "'"
    }

    companion object {
        val PANEL_KEY: DataKey<TerminalPanel> = DataKey.create("Ghostty.TerminalPanel")

        /** IDE actions whose shortcuts keep working while the terminal has focus. */
        private val IDE_ACTIONS_ALLOWED = setOf(
            "HideActiveWindow", "HideAllWindows", "JumpToLastWindow", "NextWindow", "PreviousWindow",
            "NextProjectWindow", "PreviousProjectWindow", "GotoAction", "SearchEverywhere", "GotoFile",
            "GotoClass", "GotoSymbol", "RecentFiles", "Switcher", "ShowSettings", "FindInPath",
            "ReplaceInPath", "MaximizeToolWindow", "ResizeToolWindowLeft", "ResizeToolWindowRight",
            "ResizeToolWindowUp", "ResizeToolWindowDown", "ShowBookmarks", "ToggleDistractionFreeMode",
            "ToggleFullScreen", "ToggleZenMode", "TogglePresentationMode",
        )
    }
}
