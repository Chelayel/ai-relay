package com.chelayel.airelay.claude

import com.chelayel.airelay.cli.Agent
import com.chelayel.airelay.cli.Attachment
import com.chelayel.airelay.cli.PermissionMode
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
    private var model: String?,
    private var permissionMode: String,
    private var agent: String? = null,
    private val disallowedTools: List<String> = emptyList(),
    private val executable: String = ClaudeCli.detectExecutable(),
) : Agent {

    private val workingDir: String = workspace.primary.path
    // Read at each start, not once: `/add-dir` grows the workspace and the restart must carry it.
    private val addDirs: List<String> get() = workspace.roots.drop(1).map { it.path }

    private val lock = Any()

    @Volatile private var process: Process? = null
    private var writer: BufferedWriter? = null

    @Volatile private var liveSessionId: String? = null
    /** Files an Edit/Write is about to touch, keyed by the call id, with what they held before. */
    private val pendingEdits = java.util.concurrent.ConcurrentHashMap<String, Pair<java.io.File, String?>>()
    /** Set by a mode or workspace change: the next turn restarts the CLI (resuming the session) with the new flags. */
    @Volatile private var restartPending = false
    @Volatile private var currentSink: Sink? = null
    @Volatile private var turnActive = false
    @Volatile private var cancelled = false
    @Volatile private var closed = false

    /** Signals turn completion back to the blocking [send]. */
    @Volatile private var doneLatch: CountDownLatch? = null

    override fun describe(): String =
        "Claude · CLI (auto-auth)" + (model?.let { " · $it" } ?: "")

    /** The claude process is a few hundred MB; drop it, and the next turn resumes the session. */
    override fun idle() { synchronized(lock) { if (!turnActive) stopProcess() } }

    override fun sessionId(): String? = liveSessionId

    /** Claude's CLI has its own agents (`.claude/agents`); a persona found here is the same file, passed by name. */
    override fun usePersona(persona: com.chelayel.airelay.agent.Persona?): Boolean {
        agent = persona?.name
        restartPending = true
        return true
    }
    override fun currentPersona(): String? = agent

    /** Claude keeps the conversation itself; resuming is a restart with `--resume`. */
    override fun resume(id: String, state: com.google.gson.JsonElement?): Boolean {
        liveSessionId = id
        restartPending = true
        return true
    }

    override fun models(): List<String> = (listOfNotNull(model) + CLAUDE_MODELS).distinct()
    override fun currentModel(): String? = model ?: "default"
    override fun useModel(name: String): Boolean {
        model = name.trim().ifBlank { null }
        restartPending = true
        return true
    }

    override fun setPermissionMode(mode: PermissionMode): Boolean {
        permissionMode = when (mode) {
            PermissionMode.ASK -> "default"
            PermissionMode.ACCEPT_EDITS -> "acceptEdits"
            PermissionMode.BYPASS -> "bypassPermissions"
        }
        restartPending = true
        return true
    }

    override fun addDir(dir: java.io.File): Boolean {
        if (!workspace.add(dir)) return false
        restartPending = true
        return true
    }

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

    override fun send(prompt: String, sink: Sink, attachments: List<Attachment>) {
        val latch = CountDownLatch(1)
        synchronized(lock) {
            if (closed) return
            cancelled = false
            currentSink = sink
            turnActive = true
            doneLatch = latch

            // Reuse the running process only when it's alive and driving the same
            // session; otherwise (first turn) start it.
            if (restartPending) { stopProcess(); restartPending = false }
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
                writeUserMessage(prompt, attachments)
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
        // Only entries that exist, joined with this OS's separator: on Windows ':' fused the
        // Unix guesses into the first real PATH entry.
        val extras = ClaudeCli.extraPathEntries().filter { java.io.File(it).isDirectory }
        env["PATH"] = (extras + existingPath).filter { it.isNotBlank() }.joinToString(java.io.File.pathSeparator)

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
        process?.let { p ->
            runCatching {
                p.descendants().forEach { it.destroyForcibly() }
            }
            runCatching { p.destroyForcibly() }
        }
        process = null
    }

    private fun writeUserMessage(prompt: String, attachments: List<Attachment> = emptyList()) {
        val msg = JsonObject().apply {
            addProperty("type", "user")
            add("message", JsonObject().apply {
                addProperty("role", "user")
                if (attachments.isEmpty()) addProperty("content", prompt)
                else add("content", com.google.gson.JsonArray().apply {
                    // Images first, then the text: the same content-block shape the API takes.
                    for (a in attachments) add(JsonObject().apply {
                        addProperty("type", "image")
                        add("source", JsonObject().apply {
                            addProperty("type", "base64"); addProperty("media_type", a.mimeType); addProperty("data", a.dataBase64)
                        })
                    })
                    add(JsonObject().apply { addProperty("type", "text"); addProperty("text", prompt) })
                })
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
                            val input = block.getAsJsonObject("input")
                            sink.toolUse(name, summarizeToolInput(input))
                            // Claude's own editing tools: remember the file as it is now, so the
                            // result can be shown as a diff and reverted like any other edit.
                            if (name in EDIT_TOOLS) {
                                val path = input?.str("file_path") ?: input?.str("path")
                                val id = block.str("id")
                                if (path != null && id != null) {
                                    val f = java.io.File(path)
                                    pendingEdits[id] = f to (if (f.isFile) runCatching { f.readText() }.getOrNull() else null)
                                }
                            }
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
                        block.str("tool_use_id")?.let { id -> pendingEdits.remove(id) }?.let { (f, before) ->
                            val after = if (f.isFile) runCatching { f.readText() }.getOrNull() else null
                            if (!isError && after != null && after != before) {
                                val label = workspace.roots.firstOrNull { f.path.startsWith(it.path) }?.let { f.relativeTo(it).path } ?: f.path
                                val c = com.chelayel.airelay.agent.Edits.record(f, label, before, after)
                                sink.fileChanged(c.path, c.diff, c.id)
                            }
                        }
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
                if (contextUsed > 0 || cost != null) sink.usage(contextUsed, cost)

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

/** Claude's own file-editing tools, whose effect is recorded like any other edit. */
private val EDIT_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")

/** Offered in the picker; any other id typed by hand is passed through to the CLI as is. */
private val CLAUDE_MODELS = listOf("claude-fable-5-1", "claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5-20251001")
