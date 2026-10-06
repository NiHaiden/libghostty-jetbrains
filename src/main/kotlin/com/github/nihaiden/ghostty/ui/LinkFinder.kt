package com.github.nihaiden.ghostty.ui

import java.io.File

/** Finds clickable things in a line of terminal text: URLs and file paths (with optional :line:col). */
object LinkFinder {

    sealed interface Link {
        val start: Int
        val end: Int // inclusive, in characters of the scanned text
    }

    data class Url(override val start: Int, override val end: Int, val url: String) : Link
    data class FilePath(
        override val start: Int,
        override val end: Int,
        val path: String,
        val line: Int?,
        val column: Int?,
    ) : Link

    private val URL = Regex("""(?:https?|ftp|file)://[^\s<>"'`]+|mailto:[^\s<>"'`]+""")

    // Paths like src/Main.kt:12:5, ./a/b.txt, /abs/path.py:3, C:\x\y.cs(10,2) is not handled.
    private val PATH = Regex("""(?<![\w/.~-])((?:~|\.{1,2})?/?(?:[\w.@+-]+/)*[\w.@+-]+\.[A-Za-z0-9]{1,10})(?::(\d+))?(?::(\d+))?""")

    /** The link covering character index [at] of [text], if any. */
    fun findAt(text: String, at: Int, cwd: String?): Link? {
        for (m in URL.findAll(text)) {
            if (at in m.range) {
                val trimmed = m.value.trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?')
                if (at >= m.range.first + trimmed.length) return null
                return Url(m.range.first, m.range.first + trimmed.length - 1, trimmed)
            }
        }
        for (m in PATH.findAll(text)) {
            if (at !in m.range) continue
            val raw = m.groupValues[1]
            val file = resolve(raw, cwd) ?: return null
            return FilePath(
                m.range.first,
                m.range.last,
                file.path,
                m.groupValues[2].toIntOrNull(),
                m.groupValues[3].toIntOrNull(),
            )
        }
        return null
    }

    private fun resolve(raw: String, cwd: String?): File? {
        val expanded = if (raw.startsWith("~/")) System.getProperty("user.home") + raw.substring(1) else raw
        val f = File(expanded)
        val candidate = if (f.isAbsolute) f else if (cwd != null) File(cwd, expanded) else return null
        return candidate.takeIf { it.isFile }
    }

    /** Converts an OSC 7 style "file://host/path" (or plain path) into a local path. */
    fun pwdToPath(pwd: String): String? {
        if (pwd.isBlank()) return null
        if (!pwd.startsWith("file://")) return pwd.takeIf { File(it).isAbsolute }
        val rest = pwd.removePrefix("file://")
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val path = java.net.URLDecoder.decode(rest.substring(slash).replace("+", "%2B"), Charsets.UTF_8)
        // Windows: file://host/C:/dir
        return if (path.length > 2 && path[2] == ':') path.substring(1) else path
    }
}
