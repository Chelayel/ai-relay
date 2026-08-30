package com.chelayel.airelay.mcp

import com.chelayel.airelay.config.Config
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** One configured MCP server: how to start it, and whether to. */
data class McpServerConfig(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
)

/**
 * Where the CLI's MCP servers come from.
 *
 * The plugin kept its servers in the IDE's persistent state; a CLI has no such
 * store, so they are read from a JSON file in the **same `mcpServers` shape
 * Claude Desktop and Claude Code use**. That is deliberate: the point of MCP is
 * that a server is configured once, and asking the user to re-declare servers
 * they already have would defeat it. `airelay mcp list` prints what was found.
 *
 * Resolution order: `AIRELAY_MCP_CONFIG` / the `mcp.config` key, then
 * `~/.airelay/mcp.json`, then `.mcp.json` in the project root.
 */
object McpConfig {

    /** The files searched, in order, for the given project root. */
    fun candidates(projectRoot: File?, config: Config): List<File> = buildList {
        config.get("mcp.config")?.let { add(File(it)) }
        val home = System.getProperty("user.home") ?: "."
        add(File(home, ".airelay/mcp.json"))
        projectRoot?.let { add(File(it, ".mcp.json")) }
    }

    /** The first candidate that exists, or null. */
    fun file(projectRoot: File?, config: Config): File? =
        candidates(projectRoot, config).firstOrNull { it.isFile }

    /**
     * The servers declared in the first config file found. A file that is
     * missing yields no servers; a file that is present but malformed throws,
     * because silently ignoring a config the user wrote is worse than saying so.
     */
    fun load(projectRoot: File?, config: Config): List<McpServerConfig> {
        val f = file(projectRoot, config) ?: return emptyList()
        val root = runCatching { JsonParser.parseString(f.readText()).asJsonObject }
            .getOrElse { throw IllegalArgumentException("${f.path} is not valid JSON: ${it.message}") }
        val servers = root.getAsJsonObject("mcpServers")
            ?: throw IllegalArgumentException("${f.path} has no \"mcpServers\" object.")

        return servers.entrySet().mapNotNull { (name, el) ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val command = o.get("command")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (command.isBlank()) return@mapNotNull null
            McpServerConfig(
                name = name,
                command = command,
                args = readArgs(o),
                env = readEnv(o),
                // Both spellings appear in the wild: "disabled" (Claude Desktop)
                // and "enabled".
                enabled = o.get("enabled")?.asBoolean ?: !(o.get("disabled")?.asBoolean ?: false),
            )
        }
    }

    /** `args` is normally an array; a plain string is accepted and split. */
    private fun readArgs(o: JsonObject): List<String> {
        val el = o.get("args") ?: return emptyList()
        return when {
            el.isJsonArray -> el.asJsonArray.mapNotNull { it.takeIf(::isPrimitive)?.asString }
            el.isJsonPrimitive -> el.asString.trim().takeIf { it.isNotEmpty() }
                ?.split(Regex("\\s+")).orEmpty()
            else -> emptyList()
        }
    }

    /**
     * Values of the form `${VAR}` are resolved from the environment, so a
     * checked-in `.mcp.json` can name a token without holding one.
     */
    private fun readEnv(o: JsonObject): Map<String, String> {
        val el = o.getAsJsonObject("env") ?: return emptyMap()
        return el.entrySet().mapNotNull { (k, v) ->
            if (!isPrimitive(v)) return@mapNotNull null
            k to expand(v.asString)
        }.toMap()
    }

    private fun expand(value: String): String =
        Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").replace(value) { m ->
            System.getenv(m.groupValues[1]).orEmpty()
        }

    private fun isPrimitive(el: com.google.gson.JsonElement): Boolean = el.isJsonPrimitive
}
