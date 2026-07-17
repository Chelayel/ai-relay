package com.chelayel.airelay.claude

import com.chelayel.airelay.cli.Agent
import com.chelayel.airelay.cli.Sink
import com.chelayel.airelay.cli.Workspace
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch

/**
 * The Claude backend: drives Claude Code through a single, long-lived `claude`
 * CLI process using the realtime streaming protocol (`--input-format stream-json
 * --output-format stream-json`). Authentication is "automatic" — whatever the
 * installed `claude` CLI is already logged in with; this tool never handles a key.
 *
 * Keeping one process alive for the whole conversation keeps the prompt cache
 * warm, so each turn costs roughly its new tokens plus cheap cache reads. Ported
 * from Claude Relay's ClaudeCliClient: the IntelliJ process/threading APIs are
 * replaced by [ProcessBuilder] + daemon threads, and [send] blocks until the turn
 * completes (a CLI has no event loop to post callbacks to).
 */
class ClaudeAgent(
    private val workspace: Workspace,
    private val model: String?,
    private val permissionMode: String,
    private val agent: String? = null,
    private val disallowedTools: List<String> = emptyList(),
    private val executable: String = ClaudeCli.detectExecutable(),
) : Agent {

    private val workingDir: String = workspace.primary.path
    private val addDirs: List<String> = workspace.roots.drop(1).map { it.path }

    private val lock = Any()

    @Volatile private var process: Process? = null
    private var writer: BufferedWriter? = null

    @Volatile private var liveSessionId: String? = null
    @Volatile private var currentSink: Sink? = null
    @Volatile private var turnActive = false
    @Volatile private var cancelled = false
    @Volatile private var closed = false

    /** Signals turn completion back to the blocking [send]. */
    @Volatile private var doneLatch: CountDownLatch? = null

    override fun describe(): String =
        "Claude · CLI (auto-auth)" + (model?.let { " · $it" } ?: "")

    override fun cancel() {
        synchronized(lock) {
            cancelled = true
            stopProcess()
        }
        doneLatch?.countDown()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            stopProcess()
        }
        doneLatch?.countDown()
    }

    override fun send(prompt: String, sink: Sink) {
        val latch = CountDownLatch(1)
        synchronized(lock) {
            if (closed) return
            cancelled = false
            currentSink = sink
            turnActive = true
            doneLatch = latch

            // Reuse the running process only when it's alive and driving the same
            // session; otherwise (first turn) start it.
            val reusable = process?.isAlive == true
            if (!reusable) {
                try {
                    startProcess(resumeId = liveSessionId)
                } catch (e: Exception) {
                    turnActive = false
                    sink.error(e.message ?: "Failed to launch Claude. Is the CLI installed?")
                    sink.turnComplete()
                    return
                }
            }

            try {
                writeUserMessage(prompt)
            } catch (e: Exception) {
                turnActive = false
                stopProcess()
                sink.error(e.message ?: "Lost connection to Claude.")
                sink.turnComplete()
                return
            }
        }

        // Block until the reader thread reports the turn is done.
        runCatching { latch.await() }
    }

    // ---- process lifecycle (start/stop under `lock`) -------------------------

    private fun startProcess(resumeId: String?) {
        stopProcess()

        val cmd = buildList {
            add(executable)
            add("--print")
            add("--input-format"); add("stream-json")
            add("--output-format"); add("stream-json")
            add("--verbose")
            if (!resumeId.isNullOrBlank()) { add("--resume"); add(resumeId) }
            if (permissionMode.isNotBlank()) { add("--permission-mode"); add(permissionMode) }
            if (!model.isNullOrBlank()) { add("--model"); add(model) }
            if (!agent.isNullOrBlank()) { add("--agent"); add(agent) }
            for (dir in addDirs) { add("--add-dir"); add(dir) }
            if (disallowedTools.isNotEmpty()) {
                add("--disallowedTools")
                addAll(disallowedTools)
            }
        }

        val pb = ProcessBuilder(cmd)
        pb.directory(java.io.File(workingDir))
        // Make sure the spawned process can find its own runtime deps.
        val env = pb.environment()
        val existingPath = env["PATH"].orEmpty()
        env["PATH"] = (ClaudeCli.extraPathEntries() + existingPath).filter { it.isNotBlank() }.joinToString(":")

        val p = pb.start()
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
        liveSessionId = resumeId

        val stderr = StringBuffer()
        Thread {
            runCatching {
                BufferedReader(InputStreamReader(p.errorStream, StandardCharsets.UTF_8)).forEachLine {
                    stderr.appendLine(it)
                }
            }
        }.apply { isDaemon = true; name = "claude-stderr"; start() }

        Thread { readLoop(p, stderr) }.apply { isDaemon = true; name = "claude-reader"; start() }
    }

    private fun stopProcess() {
        writer?.let { runCatching { it.close() } }
        writer = null
        process?.let { runCatching { it.destroy() } }
        process = null
    }

    private fun writeUserMessage(prompt: String) {
        val msg = JsonObject().apply {
            addProperty("type", "user")
            add("message", JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", prompt)
            })
        }
        val w = writer ?: throw IllegalStateException("Claude process is not running.")
        w.write(msg.toString())
        w.write("\n")
        w.flush()
    }

    // ---- output stream --------------------------------------------------------

    private fun readLoop(p: Process, stderr: StringBuffer) {
        runCatching {
            BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (process !== p) break // superseded by a newer process
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    runCatching { handleLine(trimmed) }
                }
            }
        }

        val code = runCatching { p.waitFor() }.getOrDefault(-1)

        var sink: Sink? = null
        var errorMsg: String? = null
        synchronized(lock) {
            if (process != null && process !== p) return
            if (process === p) {
                process = null
                writer = null
            }
            if (turnActive) {
                turnActive = false
                if (!closed) {
                    sink = currentSink
                    errorMsg = when {
                        cancelled -> "Stopped."
                        code != 0 -> stderr.toString().trim().ifEmpty { "Claude exited with code $code." }
                        else -> "Claude ended the session unexpectedly."
                    }
                }
            }
        }
        sink?.let { s ->
            errorMsg?.let { m -> s.error(m) }
            s.turnComplete()
        }
        doneLatch?.countDown()
    }

    private fun handleLine(line: String) {
        if (closed) return
        val sink = currentSink ?: return
        val obj = JsonParser.parseString(line).asJsonObject
        when (obj.str("type")) {
            "system" -> {
                if (obj.str("subtype") == "init") {
                    obj.str("session_id")?.let { id -> liveSessionId = id }
                }
            }

            "assistant" -> {
                val content = obj.getAsJsonObject("message")?.getAsJsonArray("content") ?: return
                for (el in content) {
                    val block = el.asJsonObject
                    when (block.str("type")) {
                        "text" -> block.str("text")?.takeIf { it.isNotBlank() }?.let { sink.assistantText(it) }
                        "thinking" -> block.str("thinking")?.takeIf { it.isNotBlank() }?.let { sink.thinking(it) }
                        "tool_use" -> {
                            val name = block.str("name") ?: "tool"
                            sink.toolUse(name, summarizeToolInput(block.getAsJsonObject("input")))
                        }
                    }
                }
            }

            "user" -> {
                // Tool results are echoed back as user messages.
                val content = obj.getAsJsonObject("message")?.getAsJsonArray("content") ?: return
                for (el in content) {
                    val block = el.asJsonObject
                    if (block.str("type") == "tool_result") {
                        val isError = block.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                        val text = extractToolResultText(block)
                        if (text.isNotBlank()) sink.toolResult(text, isError)
                    }
                }
            }

            "result" -> {
                obj.str("session_id")?.let { liveSessionId = it }
                val cost = obj.get("total_cost_usd")?.takeIf { it.isJsonPrimitive }?.asDouble
                val isError = obj.str("subtype") != "success"
                if (isError) sink.error(obj.str("result") ?: "Run did not complete successfully.")

                val usage = obj.getAsJsonObject("usage")
                val contextUsed = if (usage != null) {
                    usage.long("input_tokens") + usage.long("cache_read_input_tokens") + usage.long("cache_creation_input_tokens")
                } else 0L
                if (contextUsed > 0 || cost != null) {
                    val costStr = cost?.let { " · $${"%.4f".format(it)}" } ?: ""
                    sink.info("context: $contextUsed tokens$costStr")
                }

                // Turn done, but the process stays alive for the next message.
                turnActive = false
                sink.turnComplete()
                doneLatch?.countDown()
            }
        }
    }

    private fun summarizeToolInput(input: JsonObject?): String {
        if (input == null) return ""
        for (key in listOf("file_path", "command", "path", "pattern", "url", "query", "prompt", "description")) {
            input.str(key)?.let { return it.lineSequence().first().take(160) }
        }
        return input.toString().take(160)
    }

    private fun extractToolResultText(block: JsonObject): String {
        val content = block.get("content") ?: return ""
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray
                .mapNotNull { it.asJsonObject.str("text") }
                .joinToString("\n")
            else -> ""
        }.trim()
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.long(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
}
