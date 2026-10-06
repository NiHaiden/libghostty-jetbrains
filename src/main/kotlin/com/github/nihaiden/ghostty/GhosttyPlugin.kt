package com.github.nihaiden.ghostty

import com.github.nihaiden.ghostty.vt.NativeLibrary
import java.net.URI
import java.nio.file.Path

object GhosttyPlugin {
    const val ID = "com.github.nihaiden.ghostty"

    @Volatile
    private var initialized = false

    /** Tells the native loader where the plugin (and its native/ dir) is installed. */
    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NativeLibrary.addSearchRoot(::pluginDirectory)
            initialized = true
        }
    }

    /**
     * The plugin's installation directory, found from our own jar
     * (`<plugin>/lib/<jar>`), which avoids internal plugin-manager APIs.
     */
    internal fun pluginDirectory(): Path? {
        // Plugin class loaders don't set a CodeSource, but resource URLs are
        // regular jar: URLs ("jar:file:/…/<plugin>/lib/x.jar!/com/…/GhosttyPlugin.class").
        val url = GhosttyPlugin::class.java.getResource("GhosttyPlugin.class") ?: return null
        return runCatching {
            when (url.protocol) {
                "jar" -> {
                    val file = url.toString().removePrefix("jar:").substringBefore("!/")
                    Path.of(URI(file)).parent?.parent
                }
                // Exploded classes (e.g. tests): <plugin>/classes/... has no native/ dir.
                else -> null
            }
        }.getOrNull()
    }
}
