package com.chelayel.airelay.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A minimal Model Context Protocol client speaking JSON-RPC 2.0 over a child
 * process's stdio (newline-delimited messages). Enough to handshake, list a
 * server's tools, and call them — which is all the function-calling loop needs.
 *
 * Ported from Gemini Relay's client with the IntelliJ process API replaced by
 * [ProcessBuilder], and with the server's stderr drained (see [ensureConnected]).
 *
 * Calls are synchronous and serialized; one client wraps one server process.
 */
class McpClient(private val config: McpServerConfig) {

    data class McpTool(val name: String, val description: String, val inputSchema: JsonObject)

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mcp-${config.name}").apply { isDaemon = true }
    }
    private var nextId = 0
    private var connected = false

    /** The tail of the server's stderr, for reporting a startup that failed. */
    private val stderrTail = ArrayDeque<String>()

    @Synchronized
    fun ensureConnected() {
        if (connected) return
        val pb = ProcessBuilder(listOf(config.command) + config.args)
        pb.environment().putAll(config.env)
        val p = pb.start()
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
        reader = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))

        // MCP servers log to stderr, often chattily. An undrained stderr pipe
        // fills its OS buffer and the server blocks writing to it — which looks
        // from here like a server that handshook and then stopped answering.
        // Keep the last few lines: when a server dies on startup, its stderr is
        // the only thing that says why.
        Thread {
            runCatching {
                BufferedReader(InputStreamReader(p.errorStream, StandardCharsets.UTF_8)).use { err ->
                    while (true) {
                        val line = err.readLine() ?: break
                        synchronized(stderrTail) {
                            stderrTail.addLast(line)
                            if (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
                        }
                    }
                }
            }
        }.apply { isDaemon = true; name = "mcp-${config.name}-stderr"; start() }

        val init = JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", "ai-relay")
                addProperty("version", "0.1.0")
            })
        }
        rpc("initialize", init)
        notify("notifications/initialized", JsonObject())
        connected = true
    }

    fun listTools(): List<McpTool> {
        ensureConnected()
        val result = rpc("tools/list", JsonObject())
        val tools = result.getAsJsonArray("tools") ?: return emptyList()
        return tools.mapNotNull { el ->
            val obj = el.asJsonObject
            val name = obj.get("name")?.asString ?: return@mapNotNull null
            val desc = obj.get("description")?.asString ?: ""
            val schema = obj.getAsJsonObject("inputSchema") ?: JsonObject().apply { addProperty("type", "object") }
            McpTool(name, desc, schema)
        }
    }

    /** Call a tool and return its textual content (concatenated text blocks). */
    fun callTool(name: String, arguments: JsonObject): String {
        ensureConnected()
        val params = JsonObject().apply {
            addProperty("name", name)
            add("arguments", arguments)
        }
        val result = rpc("tools/call", params)
        val content = result.getAsJsonArray("content") ?: return result.toString()
        val text = content.mapNotNull { it.asJsonObject.get("text")?.asString }.joinToString("\n")
        // `isError` marks a tool that ran and failed, as opposed to a transport
        // fault; surface the text either way so the model can correct itself.
        val failed = result.get("isError")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        val body = text.ifBlank { "(no text content)" }
        return if (failed) "The tool reported an error: $body" else body
    }

    @Synchronized
    fun close() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.destroy() }
        io.shutdownNow()
        connected = false
    }

    /** The last lines the server wrote to stderr, if any. */
    fun stderrTail(): String = synchronized(stderrTail) { stderrTail.joinToString("\n") }

    // ---- JSON-RPC plumbing ---------------------------------------------------

    @Synchronized
    private fun rpc(method: String, params: JsonObject): JsonObject {
        val id = ++nextId
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        writeLine(request.toString())

        // Read until the response with our id arrives (skipping notifications).
        val future = io.submit<JsonObject> {
            val r = reader ?: error("MCP server not connected")
            while (true) {
                val line = r.readLine() ?: error(closedMessage())
                val msg = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                if (!msg.has("id") || msg.get("id").isJsonNull) continue
                if (runCatching { msg.get("id").asInt }.getOrNull() != id) continue
                msg.getAsJsonObject("error")?.let { err ->
                    error("MCP '$method' failed: ${err.get("message")?.asString ?: err}")
                }
                return@submit msg.getAsJsonObject("result") ?: JsonObject()
            }
            @Suppress("UNREACHABLE_CODE") JsonObject()
        }
        return runCatching { future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .getOrElse { throw RuntimeException("MCP '$method' on '${config.name}': ${it.cause?.message ?: it.message}") }
    }

    /** A server that closed its pipe usually said why on stderr first. */
    private fun closedMessage(): String {
        val tail = stderrTail().takeIf { it.isNotBlank() }
        val exit = process?.takeIf { !it.isAlive }?.exitValue()
        return buildString {
            append("MCP server '${config.name}' closed the connection")
            if (exit != null) append(" (exited $exit)")
            if (tail != null) append("\n").append(tail)
        }
    }

    private fun notify(method: String, params: JsonObject) {
        val msg = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
        }
        writeLine(msg.toString())
    }

    private fun writeLine(line: String) {
        val w = writer ?: error("MCP server not connected")
        synchronized(w) {
            w.write(line)
            w.write("\n")
            w.flush()
        }
    }

    companion object {
        private const val PROTOCOL_VERSION = "2024-11-05"
        private const val TIMEOUT_SECONDS = 60L
        private const val STDERR_TAIL_LINES = 20

        private val ALLOWED_SCHEMA_KEYS = setOf(
            "type", "description", "properties", "required", "items", "enum", "nullable",
        )

        /** Strip schema keys Gemini's function-declaration validator rejects. */
        fun sanitizeSchema(schema: JsonObject): JsonObject {
            val out = JsonObject()
            for ((key, value) in schema.entrySet()) {
                if (key !in ALLOWED_SCHEMA_KEYS) continue
                when {
                    key == "properties" && value.isJsonObject -> {
                        val props = JsonObject()
                        for ((pk, pv) in value.asJsonObject.entrySet()) {
                            if (pv.isJsonObject) props.add(pk, sanitizeSchema(pv.asJsonObject))
                        }
                        out.add("properties", props)
                    }
                    key == "items" && value.isJsonObject -> out.add("items", sanitizeSchema(value.asJsonObject))
                    key == "required" && value.isJsonArray -> out.add("required", value)
                    else -> out.add(key, value)
                }
            }
            if (!out.has("type")) out.addProperty("type", "object")
            return out
        }

        /** Collapse a server+tool name into a Gemini-safe function identifier. */
        fun functionName(server: String, tool: String): String =
            "${server}_$tool".replace(Regex("[^a-zA-Z0-9_]"), "_").take(60)
    }
}
