package com.chelayel.airelay.acp

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The client side of the Agent Client Protocol (agentclientprotocol.com): JSON-RPC
 * 2.0, one message per line, over an agent's stdin and stdout. The agent sends
 * notifications (`session/update`) and requests of its own
 * (`session/request_permission`) while a `session/prompt` is in flight, so
 * those are answered on their own threads: a handler that blocks on the user
 * must not stop the reader, or the reply it waits for could never arrive.
 *
 * Generic on purpose: GitHub Copilot's CLI speaks it, and so do other agents.
 */
class AcpClient(
    command: List<String>,
    workingDir: java.io.File,
    env: Map<String, String> = emptyMap(),
    private val onNotification: (method: String, params: JsonObject) -> Unit,
    private val onRequest: (method: String, params: JsonObject) -> JsonElement?,
) : AutoCloseable {

    class AcpError(val code: Int, message: String, val data: JsonElement? = null) : RuntimeException(message)

    private val process: Process
    private val writer: BufferedWriter
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonElement>>()
    private val handlers = Executors.newCachedThreadPool { r -> Thread(r, "acp-handler").apply { isDaemon = true } }
    private val stderrTail = ArrayDeque<String>()

    init {
        val pb = ProcessBuilder(command).directory(workingDir)
        pb.environment().putAll(env)
        process = pb.start()
        writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        Thread({ readLoop() }, "acp-reader").apply { isDaemon = true; start() }
        // Drained so a chatty agent never blocks on a full pipe; the tail explains a death.
        Thread({
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderrTail) { stderrTail.addLast(line); while (stderrTail.size > 40) stderrTail.removeFirst() }
                }
            }
        }, "acp-stderr").apply { isDaemon = true; start() }
    }

    val isAlive: Boolean get() = process.isAlive

    fun stderr(): String = synchronized(stderrTail) { stderrTail.joinToString("\n") }

    /** Blocks until the agent answers, the process dies, or [timeoutSeconds] pass (0: no limit). */
    fun request(method: String, params: JsonObject, timeoutSeconds: Long = 0): JsonElement {
        val id = ids.incrementAndGet()
        val future = CompletableFuture<JsonElement>()
        pending[id] = future
        write(JsonObject().apply {
            addProperty("jsonrpc", "2.0"); addProperty("id", id); addProperty("method", method); add("params", params)
        })
        try {
            return if (timeoutSeconds > 0) future.get(timeoutSeconds, TimeUnit.SECONDS) else future.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: java.util.concurrent.TimeoutException) {
            throw AcpError(-1, "The agent did not answer $method within $timeoutSeconds s.")
        } finally {
            pending.remove(id)
        }
    }

    fun notify(method: String, params: JsonObject) = write(JsonObject().apply {
        addProperty("jsonrpc", "2.0"); addProperty("method", method); add("params", params)
    })

    private fun write(msg: JsonObject) {
        synchronized(writer) {
            writer.write(msg.toString())
            writer.write("\n")
            writer.flush()
        }
    }

    private fun readLoop() {
        runCatching {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val msg = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return@forEachLine
                val method = msg.get("method")?.takeIf { it.isJsonPrimitive }?.asString
                val id = msg.get("id")
                val params = msg.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                when {
                    method == null && id != null && id.isJsonPrimitive -> {
                        val future = pending[runCatching { id.asInt }.getOrDefault(-1)] ?: return@forEachLine
                        val error = msg.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
                        if (error != null) future.completeExceptionally(AcpError(
                            error.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1,
                            error.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "Agent error",
                            error.get("data"),
                        ))
                        else future.complete(msg.get("result") ?: JsonNull.INSTANCE)
                    }
                    method != null && id != null -> handlers.execute {
                        val reply = JsonObject().apply { addProperty("jsonrpc", "2.0"); add("id", id) }
                        runCatching { onRequest(method, params) }
                            .onSuccess { result -> if (result == null) reply.add("error", errorObject(-32601, "Method not supported: $method")) else reply.add("result", result) }
                            .onFailure { e -> reply.add("error", errorObject(-32603, e.message ?: "Client error")) }
                        runCatching { write(reply) }
                    }
                    method != null -> runCatching { onNotification(method, params) }
                }
            }
        }
        // The agent is gone: nothing still waiting will ever be answered.
        val death = AcpError(-2, "The agent exited." + stderr().takeLast(600).let { if (it.isBlank()) "" else "\n$it" })
        pending.values.forEach { it.completeExceptionally(death) }
    }

    private fun errorObject(code: Int, message: String) = JsonObject().apply { addProperty("code", code); addProperty("message", message) }

    override fun close() {
        runCatching { writer.close() }
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
        handlers.shutdownNow()
    }
}
