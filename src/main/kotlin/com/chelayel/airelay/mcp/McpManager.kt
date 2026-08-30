package com.chelayel.airelay.mcp

import com.chelayel.airelay.agent.ToolSpec
import com.google.gson.JsonObject
import java.io.Closeable

/**
 * Owns the configured MCP servers for one CLI session: connects lazily, merges
 * their tools into the agent's tool set, and routes calls back to the right
 * server. A server that fails to start contributes no tools and one line of
 * explanation rather than taking the session down with it.
 *
 * Ported from Gemini Relay's manager, with one structural change: tools are
 * exposed as backend-neutral [ToolSpec]s rather than Gemini `FunctionDecl`s, so
 * the Copilot backend — which has no function calling and is taught a JSON
 * protocol in its prompt instead — gets the same MCP servers for free.
 */
class McpManager(private val servers: List<McpServerConfig>) : Closeable {

    private class Entry(val client: McpClient, val originalName: String, val spec: ToolSpec)

    private var entries: List<Entry>? = null
    private val clients = mutableListOf<McpClient>()
    private val errors = mutableListOf<String>()

    /** True when there is nothing configured, so callers can skip the work. */
    val isEmpty: Boolean get() = servers.none { it.enabled && it.command.isNotBlank() }

    /** Connects (once) and returns the merged tool declarations. */
    @Synchronized
    fun specs(): List<ToolSpec> {
        entries?.let { return it.map { e -> e.spec } }
        val built = mutableListOf<Entry>()
        errors.clear()
        for (config in servers.filter { it.enabled && it.command.isNotBlank() }) {
            val client = McpClient(config)
            clients.add(client)
            runCatching {
                client.listTools().forEach { tool ->
                    val fnName = McpClient.functionName(config.name.ifBlank { "mcp" }, tool.name)
                    val spec = ToolSpec(fnName, tool.description, McpClient.sanitizeSchema(tool.inputSchema))
                    built.add(Entry(client, tool.name, spec))
                }
            }.onFailure { e ->
                errors.add("${config.name}: ${e.message ?: e::class.simpleName}")
                runCatching { client.close() }
            }
        }
        entries = built
        return built.map { it.spec }
    }

    fun handles(functionName: String): Boolean = entries?.any { it.spec.name == functionName } == true

    fun summarize(functionName: String): String =
        entries?.firstOrNull { it.spec.name == functionName }?.originalName ?: functionName

    fun execute(functionName: String, args: JsonObject): JsonObject {
        val entry = entries?.firstOrNull { it.spec.name == functionName }
            ?: return JsonObject().apply { addProperty("error", "Unknown MCP tool: $functionName") }
        return runCatching {
            JsonObject().apply { addProperty("result", entry.client.callTool(entry.originalName, args)) }
        }.getOrElse {
            JsonObject().apply { addProperty("error", it.message ?: "MCP tool '$functionName' failed.") }
        }
    }

    /** Any connection/list errors from the last [specs] build. */
    fun lastErrors(): List<String> = errors.toList()

    /** How many servers contributed how many tools — one line for the banner. */
    fun describe(): String? {
        val built = entries ?: return null
        if (built.isEmpty()) return null
        val perServer = built.groupingBy { it.client }.eachCount()
        return "MCP: ${built.size} tool(s) from ${perServer.size} server(s)"
    }

    override fun close() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        entries = null
    }

    companion object {
        val EMPTY = McpManager(emptyList())
    }
}
