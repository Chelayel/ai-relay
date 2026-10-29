package com.chelayel.airelay.cli

import com.google.gson.JsonParser
import java.io.File

/**
 * The model list each backend last reported (see [Agent.discoverModels]), kept in
 * `~/.airelay/cache/models-<backend>.json`. Discovery takes a subprocess or a
 * request; with the cache the picker opens on what was live last time, and the
 * fresh list replaces it a moment later.
 */
object ModelCache {
    private val dir = File(File(System.getProperty("user.home"), ".airelay"), "cache")

    private fun file(backend: String) = File(dir, "models-$backend.json")

    fun load(backend: String): List<ModelInfo>? = runCatching {
        val f = file(backend).takeIf { it.isFile } ?: return null
        JsonParser.parseString(f.readText()).asJsonArray.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            ModelInfo(s("id") ?: return@mapNotNull null, s("name"), s("description"), s("badge"))
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Never fails the caller: a cache that cannot be written is only slower next time. */
    fun save(backend: String, models: List<ModelInfo>) {
        runCatching {
            dir.mkdirs()
            file(backend).writeText(com.google.gson.Gson().toJson(models.map { it.toMap() }))
        }
    }
}
