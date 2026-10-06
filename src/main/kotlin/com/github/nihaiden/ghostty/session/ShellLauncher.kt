package com.github.nihaiden.ghostty.session

import com.github.nihaiden.ghostty.settings.GhosttySettings
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File

/** Works out what to run and with which environment. */
object ShellLauncher {

    fun spec(project: Project?, workingDirectory: String?, settings: GhosttySettings.State): TerminalSession.LaunchSpec {
        val dir = workingDirectory?.takeIf { File(it).isDirectory }
            ?: project?.guessProjectDir()?.path?.takeIf { File(it).isDirectory }
            ?: System.getProperty("user.home")
        return TerminalSession.LaunchSpec(
            command = command(settings),
            workingDirectory = dir,
            environment = environment(settings),
        )
    }

    fun command(settings: GhosttySettings.State): List<String> {
        val custom = settings.shellCommand.trim()
        if (custom.isNotEmpty()) return ParametersListUtil.parse(custom)
        return defaultShell()
    }

    fun defaultShell(): List<String> {
        if (SystemInfo.isWindows) {
            val pwsh = PathEnvironmentVariableUtil.findInPath("pwsh.exe")
            if (pwsh != null) return listOf(pwsh.absolutePath)
            return listOf("powershell.exe")
        }
        val shell = System.getenv("SHELL")?.takeIf { File(it).canExecute() }
            ?: listOf("/bin/zsh", "/bin/bash", "/bin/sh").first { File(it).canExecute() }
        // macOS terminals traditionally start login shells so the profile runs.
        return if (SystemInfo.isMac) listOf(shell, "-l") else listOf(shell)
    }

    fun environment(settings: GhosttySettings.State): Map<String, String> {
        val env = LinkedHashMap(EnvironmentUtil.getEnvironmentMap())
        // Variables describing whatever terminal launched the IDE are wrong here.
        listOf("TERM_PROGRAM", "TERM_PROGRAM_VERSION", "TERM_SESSION_ID", "ITERM_SESSION_ID",
            "KITTY_WINDOW_ID", "GHOSTTY_RESOURCES_DIR", "GHOSTTY_BIN_DIR", "VTE_VERSION", "WT_SESSION")
            .forEach(env::remove)
        if (!SystemInfo.isWindows) {
            env["TERM"] = "xterm-256color"
            env["COLORTERM"] = "truecolor"
            if (SystemInfo.isMac && env.keys.none { it == "LANG" || it == "LC_ALL" || it == "LC_CTYPE" }) {
                env["LANG"] = "en_US.UTF-8"
            }
        }
        env["TERMINAL_EMULATOR"] = "JetBrains-Ghostty"
        for (line in settings.environment.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            env[line.substring(0, eq).trim()] = line.substring(eq + 1)
        }
        return env
    }
}
