package com.chelayel.airelay.config

import java.io.File
import java.util.Properties

/**
 * Reads configuration from two layered sources, with environment variables
 * winning over the file:
 *
 *   1. `~/.airelay/config.properties` (or `$AIRELAY_CONFIG`) — dotted keys, e.g.
 *      `gemini.api.key=…`, `apigee.client.secret=…`.
 *   2. Environment variables — the same keys upper-cased with dots as `_` and an
 *      `AIRELAY_` prefix (e.g. `AIRELAY_GEMINI_API_KEY`). A few well-known names
 *      without the prefix are also honoured (`GEMINI_API_KEY`, `GOOGLE_CLOUD_PROJECT`).
 *
 * This replaces the IDE PasswordSafe / PersistentStateComponent the plugins used:
 * a CLI has no IDE, so secrets come from the environment or a user-owned file.
 */
class Config private constructor(
    private val fileProps: Properties,
    private val env: Map<String, String>,
) {

    /** Look up [dottedKey], preferring an env override, then the config file. */
    fun get(dottedKey: String): String? {
        val envKey = "AIRELAY_" + dottedKey.uppercase().replace('.', '_')
        env[envKey]?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        // A couple of conventional unprefixed aliases.
        ALIASES[dottedKey]?.forEach { alias ->
            env[alias]?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        }
        return fileProps.getProperty(dottedKey)?.takeIf { it.isNotBlank() }?.trim()
    }

    fun get(dottedKey: String, default: String): String = get(dottedKey) ?: default

    fun getBool(dottedKey: String, default: Boolean): Boolean =
        get(dottedKey)?.let { it.equals("true", true) || it == "1" || it.equals("yes", true) } ?: default

    fun getInt(dottedKey: String, default: Int): Int = get(dottedKey)?.toIntOrNull() ?: default

    companion object {
        private val ALIASES: Map<String, List<String>> = mapOf(
            "gemini.api.key" to listOf("GEMINI_API_KEY", "GOOGLE_API_KEY"),
            "vertex.project" to listOf("GOOGLE_CLOUD_PROJECT"),
            "vertex.location" to listOf("GOOGLE_CLOUD_LOCATION", "CLOUD_ML_REGION"),
        )

        fun load(): Config {
            val props = readRaw()
            return Config(props, System.getenv())
        }

        /** The config file path (may not exist yet). */
        fun file(): File {
            System.getenv("AIRELAY_CONFIG")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val home = System.getProperty("user.home") ?: "."
            return File(home, ".airelay/config.properties")
        }

        /** The raw persisted keys (ignores env overrides). */
        fun readRaw(): Properties {
            val props = Properties()
            file().takeIf { it.isFile }?.let { f ->
                runCatching { f.inputStream().use { props.load(it) } }
            }
            return props
        }

        /**
         * Merge [updates] into the config file (blank values delete the key),
         * writing owner-only (0600) since it may hold secrets. Returns the file.
         */
        fun writeAll(updates: Map<String, String?>): File {
            val props = readRaw()
            for ((k, v) in updates) {
                if (v.isNullOrBlank()) props.remove(k) else props.setProperty(k, v)
            }
            val f = file()
            f.parentFile?.mkdirs()
            f.outputStream().use { props.store(it, "AI Relay configuration — edit with `airelay gemini setup` / `airelay copilot setup`") }
            runCatching { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) }
            return f
        }

        /** Remove every key whose name starts with one of [prefixes]. */
        fun clearKeys(prefixes: List<String>): List<String> {
            val props = readRaw()
            val removed = props.stringPropertyNames().filter { name -> prefixes.any { name.startsWith(it) } }
            removed.forEach { props.remove(it) }
            val f = file()
            if (f.isFile) f.outputStream().use { props.store(it, "AI Relay configuration") }
            return removed
        }
    }
}
