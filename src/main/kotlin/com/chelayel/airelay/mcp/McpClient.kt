package com.chelayel.airelay.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
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
    // One reader for the life of the connection, dispatching each reply to the call
    // that asked. The old per-call reader outlived its timeout and went on eating
    // lines, so one slow tool swallowed every later reply on that server.
    @Volatile private var readerThread: Thread? = null
    private val pending = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CompletableFuture<JsonObject>>()
    private val nextId = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var connected = false
    @Volatile private var dead: String? = null

    private fun startReader(r: BufferedReader) {
        readerThread = Thread({
            try {
                while (true) {
                    val line = r.readLine() ?: break
                    val msg = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                    val id = msg.get("id")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: continue
                    pending.remove(id)?.complete(msg)
                }
            } catch (_: Throwable) {
            } finally {
                // No lock here: ensureConnected() holds this object's monitor while it waits
                // on `initialize`, and a server that dies at startup must fail that wait at
                // once, with its stderr, not after the timeout. The identity check is enough:
                // only the current connection's reader may declare it dead.
                if (readerThread === Thread.currentThread()) {
                    val why = closedMessage()
                    val waiting = pending.values.toList(); pending.clear()
                    dead = why
                    // A server that died is started again by the next call, not mourned for the session.
                    connected = false
                    waiting.forEach { it.completeExceptionally(IllegalStateException(why)) }
                }
            }
        }, "mcp-${config.name}").apply { isDaemon = true; start() }
    }

    /** The tail of the server's stderr, for reporting a startup that failed. */
    private val stderrTail = ArrayDeque<String>()

    @Synchronized
    fun ensureConnected() {
        if (connected) return
        // A previous attempt that timed out left its server running; it goes first.
        readerThread = null
        process?.let { killTree(it) }
        val pb = ProcessBuilder(listOf(config.command) + config.args)
        pb.environment().putAll(config.env)
        val p = pb.start()
        process = p
        runCatching { writer?.close() }
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
        val r = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
        reader = r
        dead = null
        startReader(r)

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
        // The whole tree: a launcher (`cmd /c npx …`, uvx) leaves a child holding stdout,
        // and killing only the launcher gives the reader no EOF. The reader itself is never
        // closed here: it sits in readLine() holding the BufferedReader's lock, which close()
        // would wait on for as long as anything holds the pipe. It is a daemon; EOF ends it.
        readerThread = null
        process?.let { killTree(it) }
        runCatching { writer?.close() }
        pending.values.forEach { it.completeExceptionally(IllegalStateException("closed")) }; pending.clear()
        connected = false
    }

    private fun killTree(p: Process) {
        runCatching { p.descendants().forEach { it.destroyForcibly() } }
        runCatching { p.destroyForcibly() }
    }

    /** The last lines the server wrote to stderr, if any. */
    fun stderrTail(): String = synchronized(stderrTail) { stderrTail.joinToString("\n") }

    // ---- JSON-RPC plumbing ---------------------------------------------------

    private fun rpc(method: String, params: JsonObject): JsonObject {
        dead?.let { throw RuntimeException("MCP '$method' on '${config.name}': $it") }
        val id = nextId.incrementAndGet()
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        val future = java.util.concurrent.CompletableFuture<JsonObject>()
        pending[id] = future
        runCatching { writeLine(request.toString()) }.onFailure {
            pending.remove(id)
            // A write to a server that already exited: what it said on stderr is the explanation,
            // not "Stream closed". Give it a moment to finish exiting so the tail is complete.
            runCatching { process?.waitFor(500, TimeUnit.MILLISECONDS) }
            throw RuntimeException("MCP '$method' on '${config.name}': ${if (process?.isAlive == false) closedMessage() else it.message}")
        }

        val msg = try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            // A late reply is dropped by the reader (nothing waits on that id any more);
            // the next call is unaffected.
            pending.remove(id)
            if (e is InterruptedException) Thread.currentThread().interrupt()
            val why = (e as? java.util.concurrent.ExecutionException)?.cause?.message ?: if (e is java.util.concurrent.TimeoutException) "no reply within ${TIMEOUT_SECONDS}s" else e.message ?: e.toString()
            throw RuntimeException("MCP '$method' on '${config.name}': $why")
        }
        msg.getAsJsonObject("error")?.let { err ->
            throw RuntimeException("MCP '$method' failed: ${err.get("message")?.asString ?: err}")
        }
        return msg.getAsJsonObject("result") ?: JsonObject()
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
