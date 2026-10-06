package com.github.nihaiden.ghostty.vt

import com.sun.jna.Native
import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates and loads libghostty-jb.
 *
 * Lookup order:
 *  1. the `ghostty.jb.library` system property or `GHOSTTY_JB_LIBRARY`
 *     environment variable (absolute path to the library file),
 *  2. `<searchRoot>/native/<os>-<arch>/<lib>` for each registered search root
 *     (the plugin registers its installation directory).
 */
object NativeLibrary {
    private val searchRoots = mutableListOf<() -> Path?>()

    @Volatile
    private var loaded: GhosttyJb? = null

    @Volatile
    private var failure: Throwable? = null

    fun addSearchRoot(root: () -> Path?) {
        synchronized(searchRoots) { searchRoots += root }
    }

    val platform: String
        get() {
            val os = System.getProperty("os.name").lowercase()
            val arch = when (val a = System.getProperty("os.arch").lowercase()) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "aarch64"
                else -> a
            }
            return when {
                os.contains("win") -> "windows-$arch"
                os.contains("mac") || os.contains("darwin") -> "darwin-$arch"
                else -> "linux-$arch"
            }
        }

    val libraryFileName: String
        get() = when {
            platform.startsWith("windows") -> "ghostty-jb.dll"
            platform.startsWith("darwin") -> "libghostty-jb.dylib"
            else -> "libghostty-jb.so"
        }

    /** Candidate paths in lookup order (for diagnostics). */
    fun candidates(): List<Path> {
        val result = mutableListOf<Path>()
        (System.getProperty("ghostty.jb.library") ?: System.getenv("GHOSTTY_JB_LIBRARY"))
            ?.takeIf { it.isNotBlank() }
            ?.let { result.add(Path.of(it)) }
        val roots = synchronized(searchRoots) { searchRoots.toList() }
        for (root in roots) {
            val dir = runCatching { root() }.getOrNull() ?: continue
            result.add(dir.resolve("native").resolve(platform).resolve(libraryFileName))
        }
        return result
    }

    internal fun get(): GhosttyJb {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            failure?.let { throw IllegalStateException(it.message, it) }
            try {
                val lib = load()
                loaded = lib
                return lib
            } catch (t: Throwable) {
                failure = t
                throw t
            }
        }
    }

    /** Returns null if the library can be used, otherwise a human readable reason. */
    fun problem(): String? = try {
        get()
        null
    } catch (t: Throwable) {
        t.message ?: t.toString()
    }

    private fun load(): GhosttyJb {
        val candidates = candidates()
        val path = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: throw UnsatisfiedLinkError(
                "libghostty-jb for $platform not found. Looked in:\n" +
                    candidates.joinToString("\n") { "  $it" },
            )
        val lib = Native.load(path.toAbsolutePath().toString(), GhosttyJb::class.java)
        val abi = lib.gjb_abi_version()
        if (abi != GhosttyJb.ABI_VERSION) {
            throw UnsatisfiedLinkError(
                "$path has ABI version $abi, expected ${GhosttyJb.ABI_VERSION}",
            )
        }
        return lib
    }
}
