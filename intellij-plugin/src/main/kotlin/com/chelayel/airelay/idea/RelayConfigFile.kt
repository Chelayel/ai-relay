package com.chelayel.airelay.idea

import java.io.File
import java.util.Properties

/**
 * The CLI's configuration file, `~/.airelay/config.properties`, read and
 * written here so the settings page is a form rather than a terminal wizard.
 * It is the same file `airelay gemini setup` writes: one configuration for the
 * command line, this plugin and the VS Code extension. Secrets live in it too,
 * owner-readable only — that is the CLI's contract and the plugin keeps it.
 */
object RelayConfigFile {

    fun file(): File {
        System.getenv("AIRELAY_CONFIG")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return File(System.getProperty("user.home"), ".airelay/config.properties")
    }

    fun read(): Properties {
        val props = Properties()
        file().takeIf { it.isFile }?.let { f -> runCatching { f.inputStream().use { props.load(it) } } }
        return props
    }

    /** Merge [updates] in; a blank value removes the key. */
    fun write(updates: Map<String, String?>) {
        val props = read()
        for ((k, v) in updates) if (v.isNullOrBlank()) props.remove(k) else props.setProperty(k, v.trim())
        val f = file()
        f.parentFile?.mkdirs()
        f.outputStream().use { props.store(it, "AI Relay configuration — shared by the CLI and the IDE plugins") }
        runCatching {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        }
    }
}
