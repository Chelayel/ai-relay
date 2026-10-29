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

    /**
     * The account's usage as last reported. Claude says it only inside a turn, so
     * without this a fresh panel shows no usage until the first answer. A window
     * whose reset time has passed is shown as reset.
     */
    fun loadQuota(backend: String): Quota? = runCatching {
        val f = File(dir, "quota-$backend.json").takeIf { it.isFile } ?: return null
        val o = JsonParser.parseString(f.readText()).asJsonObject
        val now = System.currentTimeMillis()
        val windows = o.getAsJsonArray("windows").mapNotNull { el ->
            val w = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            fun d(k: String) = w.get(k)?.takeIf { it.isJsonPrimitive }?.asDouble
            val resets = w.get("resetsAt")?.takeIf { it.isJsonPrimitive }?.asLong
            val over = resets != null && resets < now
            Quota.Window(w.get("label")?.asString ?: return@mapNotNull null,
                usedPercent = if (over && d("usedPercent") != null) 0.0 else d("usedPercent"),
                used = if (over && d("used") != null) 0.0 else d("used"), limit = d("limit"),
                resetsAt = if (over) null else resets, unlimited = w.get("unlimited")?.asBoolean ?: false)
        }
        if (windows.isEmpty()) null else Quota(windows, o.get("note")?.takeIf { it.isJsonPrimitive }?.asString)
    }.getOrNull()

    fun saveQuota(backend: String, quota: Quota) {
        runCatching { dir.mkdirs(); File(dir, "quota-$backend.json").writeText(com.google.gson.Gson().toJson(quota.toMap())) }
    }

    /** Never fails the caller: a cache that cannot be written is only slower next time. */
    fun save(backend: String, models: List<ModelInfo>) {
        runCatching {
            dir.mkdirs()
            file(backend).writeText(com.google.gson.Gson().toJson(models.map { it.toMap() }))
        }
    }
}
