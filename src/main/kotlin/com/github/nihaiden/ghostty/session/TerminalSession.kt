package com.github.nihaiden.ghostty.session

import com.github.nihaiden.ghostty.vt.GhosttyJb
import com.github.nihaiden.ghostty.vt.GhosttyTerminal
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A shell running in a pty, wired to a [GhosttyTerminal].
 *
 * Output is read on a dedicated thread and fed straight into libghostty;
 * the UI is notified through [Listener.onOutput] and pulls frames when it
 * paints. Input is written through a single-threaded queue so that typing
 * never blocks the EDT on a full pty buffer.
 */
class TerminalSession(
    private val spec: LaunchSpec,
    cols: Int,
    rows: Int,
) : Disposable {

    data class LaunchSpec(
        val command: List<String>,
        val workingDirectory: String?,
        val environment: Map<String, String>,
    )

    /** Session events. Called on background threads; implementations must hop to the EDT themselves. */
    interface Listener {
        fun onOutput() {}
        fun onBell() {}
        fun onTitleChanged(title: String) {}
        fun onPwdChanged(pwd: String) {}
        fun onClipboardWrite(text: String, primary: Boolean) {}
        fun onNotification(title: String, body: String) {}
        fun onProgress(state: Int, progress: Int?) {}
        fun onCommandFinished(exitCode: Int?) {}
        fun onExit(exitCode: Int) {}
    }

    @Volatile
    var listener: Listener = object : Listener {}

    val terminal: GhosttyTerminal = GhosttyTerminal(cols, rows, object : GhosttyTerminal.Listener {
        override fun onWritePty(data: ByteArray) = send(data)
        override fun onBell() = listener.onBell()
        override fun onTitleChanged(title: String) = listener.onTitleChanged(title)
        override fun onPwdChanged(pwd: String) = listener.onPwdChanged(pwd)
        override fun onClipboardWrite(text: String, primary: Boolean) = listener.onClipboardWrite(text, primary)
        override fun onNotification(title: String, body: String) = listener.onNotification(title, body)
        override fun onProgress(state: Int, progress: Int?) = listener.onProgress(state, progress)
        override fun onCommandFinished(exitCode: Int?) = listener.onCommandFinished(exitCode)
    })

    private var process: PtyProcess? = null
    private val writer: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("Ghostty pty writer", 1)
    private val disposed = AtomicBoolean(false)

    @Volatile
    var exitCode: Int? = null
        private set

    val isRunning: Boolean get() = process?.isAlive == true

    init {
        if (SystemInfo.isWindows) {
            // ConPTY keeps its own screen buffer without scrollback; don't let a
            // resize pull rows back out of our scrollback (see libghostty docs).
            terminal.setOption(GhosttyJb.OPT_RESIZE_PULL_SCROLLBACK, 0)
        }
    }

    /** Starts the process. Throws on failure (e.g. shell not found). */
    fun start(cols: Int, rows: Int) {
        check(process == null) { "already started" }
        val builder = PtyProcessBuilder(spec.command.toTypedArray())
            .setEnvironment(spec.environment)
            .setInitialColumns(cols)
            .setInitialRows(rows)
            .setConsole(false)
            .setUseWinConPty(true)
        spec.workingDirectory?.let { builder.setDirectory(it) }
        val p = builder.start()
        process = p

        Thread({ readLoop(p) }, "Ghostty pty reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(p: PtyProcess) {
        val buf = ByteArray(64 * 1024)
        val input = p.inputStream
        try {
            while (!disposed.get()) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                terminal.write(buf, n)
                listener.onOutput()
            }
        } catch (e: IOException) {
            // Pty closed: normal on exit, especially on Windows.
            LOG.debug("pty read ended", e)
        } catch (t: Throwable) {
            LOG.warn("pty reader failed", t)
        }
        val code = try {
            if (p.waitFor(5, TimeUnit.SECONDS)) p.exitValue() else -1
        } catch (_: InterruptedException) {
            -1
        }
        exitCode = code
        if (!disposed.get()) listener.onExit(code)
    }

    /** Writes bytes to the pty asynchronously, preserving order. */
    fun send(data: ByteArray) {
        if (data.isEmpty() || disposed.get()) return
        val p = process ?: return
        writer.execute {
            try {
                p.outputStream.write(data)
                p.outputStream.flush()
            } catch (e: IOException) {
                LOG.debug("pty write failed", e)
            }
        }
    }

    fun sendText(text: String) = send(text.encodeToByteArray())

    fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int, padLeft: Int, padTop: Int) {
        terminal.resize(cols, rows, cellWidth, cellHeight, padLeft, padTop)
        val p = process ?: return
        if (!p.isAlive) return
        try {
            p.winSize = WinSize(cols, rows)
        } catch (e: Exception) {
            LOG.debug("resize failed", e)
        }
    }

    fun terminate() {
        process?.let { if (it.isAlive) it.destroy() }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        terminate()
        writer.shutdown()
        terminal.close()
    }

    private companion object {
        val LOG = logger<TerminalSession>()
    }
}
