package com.chelayel.airelay.copilot.agent

import com.chelayel.airelay.agent.PermissionDecision
import com.chelayel.airelay.agent.ToolSpec
import com.chelayel.airelay.agent.Tools
import com.chelayel.airelay.cli.Agent
import com.chelayel.airelay.cli.PermissionMode
import com.chelayel.airelay.cli.Sink
import com.chelayel.airelay.cli.Workspace
import com.chelayel.airelay.copilot.api.BrowserTransport
import com.chelayel.airelay.copilot.api.CopilotConfig
import com.chelayel.airelay.copilot.api.CopilotTransport
import com.chelayel.airelay.copilot.api.ReplayTransport
import com.chelayel.airelay.copilot.api.SessionExpiredException
import java.io.File

/**
 * The Copilot backend: replays the user's captured browser session against the
 * Copilot web endpoint and runs the same agentic loop the Gemini backend does —
 * send a turn, run any tool calls it asks for, feed the results back, repeat
 * until it answers in plain prose.
 *
 * Two things differ from Gemini, both forced by the transport:
 *
 *  - **Tools live in the prompt.** A chat surface has no `tools` field, so the
 *    contract is taught in the system preamble and parsed back out of the reply
 *    (see [CopilotProtocol]).
 *  - **History usually lives on the server.** The website's conversation already
 *    remembers the exchange, so by default only the new message is sent, and the
 *    system preamble goes out once at the start of a session rather than on
 *    every turn. `copilot.history=local` re-sends a transcript instead, for an
 *    endpoint that turns out to be stateless.
 */
class CopilotAgent(
    private val workspace: Workspace,
    private val config: CopilotConfig,
    private val permission: PermissionMode,
    private val askMode: Boolean,
    /** Prompts the user to approve a tool call; returns their decision. */
    private val confirm: (name: String, summary: String) -> PermissionDecision,
) : Agent {

    private val approvedTools = mutableSetOf<String>()
    private val transcript = mutableListOf<String>()

    /** Mutable so `/model` can switch models mid-session, as the web picker does. */
    @Volatile private var model: String = config.model

    private var preambleSent = false

    /** Set while a message carrying the preamble is in flight but unconfirmed. */
    private var pendingPreamble = false

    /** One nudge per turn when a reply describes work instead of doing it. */
    private var nudged = false

    /**
     * How turns reach Copilot: a replayed HTTP request, or a driven browser.
     * The loop below is identical either way — only the transport differs.
     */
    private val transport: CopilotTransport =
        if (config.isBrowserMode) BrowserTransport(config) else ReplayTransport(config)

    @Volatile private var started = false
    @Volatile private var cancelled = false
    @Volatile private var activeProcess: Process? = null

    override fun describe(): String = transport.describe(model)

    /** The model ids offered by `/model`, as captured from the web picker. */
    fun availableModels(): List<String> = config.models

    /** True when a model can be chosen from the CLI rather than in the browser. */
    fun canChooseModel(): Boolean = transport.canChooseModel

    /** The model in use this session. */
    fun currentModel(): String = model

    /** Switch models for the rest of the session, the way the web picker does. */
    fun useModel(id: String) {
        model = id.trim()
    }

    override fun cancel() {
        cancelled = true
        transport.cancel()
        activeProcess?.let { p ->
            runCatching { p.descendants().forEach { it.destroyForcibly() } }
            runCatching { p.destroyForcibly() }
        }
    }

    override fun close() {
        cancelled = true
        runCatching { transport.close() }
        activeProcess?.let { p -> runCatching { p.destroyForcibly() } }
    }

    override fun send(prompt: String, sink: Sink) {
        cancelled = false
        runCatching {
            if (!started) {
                transport.start { message -> sink.info(message) }
                started = true
            }
            loop(prompt.ifBlank { "Please continue." }, sink)
        }
            .onFailure { e -> sink.error(describe(e)) }
        if (cancelled) sink.error("Stopped.")
        sink.turnComplete()
    }

    private fun loop(userPrompt: String, sink: Sink) {
        val tools = Tools(
            workspace = workspace,
            commandTimeoutSeconds = config.commandTimeoutSeconds,
            onProcessStart = { proc -> activeProcess = proc },
            onProcessEnd = { activeProcess = null },
        )
        // Ask mode is strictly read-only: no tools at all.
        val specs: List<ToolSpec> = if (askMode) emptyList() else tools.specs()

        transcript.add("User: $userPrompt")
        var message = compose(userPrompt, specs)
        var iterations = 0
        nudged = false

        while (!cancelled) {
            if (iterations++ > MAX_ITERATIONS) {
                sink.error("Stopped after $MAX_ITERATIONS tool rounds without a final answer.")
                return
            }

            val filter = ToolBlockFilter { delta -> sink.assistantText(delta) }

            val turn = try {
                transport.send(message, model.takeIf { it.isNotBlank() }) { delta -> filter.accept(delta) }
            } finally {
                filter.finish()
            }
            if (cancelled) return
            if (config.debug && turn.rawSample.isNotBlank()) {
                sink.info("raw: " + turn.rawSample.take(600).replace("\n", " ⏎ "))
            }
            if (turn.text.isBlank()) {
                sink.error(emptyTurnMessage(turn.rawSample))
                return
            }
            // The preamble reached Copilot only now that it has answered.
            if (pendingPreamble) {
                preambleSent = true
                pendingPreamble = false
            }

            transcript.add("Assistant: " + CopilotProtocol.stripToolBlocks(turn.text))

            val calls = if (askMode) emptyList() else CopilotProtocol.parseCalls(turn.text)
            if (calls.isEmpty()) {
                // A reply full of code but no tool call means the work was
                // described rather than done, and nothing reached the project.
                // Worth exactly one nudge before taking it as the final answer.
                if (!askMode && !nudged && CopilotProtocol.looksLikeUncalledWork(turn.text)) {
                    nudged = true
                    sink.info("No tool call in that reply — asking Copilot to apply it, not describe it.")
                    message = compose(NUDGE, specs)
                    continue
                }
                return
            }
            nudged = false

            val results = StringBuilder("Tool results:\n")
            for (call in calls) {
                if (cancelled) return
                val summary = tools.summarize(call.name, call.args)
                sink.toolUse(call.name, summary)

                if (!tools.handles(call.name)) {
                    sink.toolResult("Unknown tool: ${call.name}", true)
                    results.append(section(call.name, summary, "error: no such tool. Use only the listed tools."))
                    continue
                }

                if (needsConfirm(permission, call.name) && call.name !in approvedTools) {
                    when (confirm(call.name, summary)) {
                        PermissionDecision.DENY -> {
                            sink.toolResult("Denied by user.", true)
                            results.append(section(call.name, summary, "error: the user denied permission to run this tool."))
                            continue
                        }
                        PermissionDecision.ALLOW_ALWAYS -> approvedTools.add(call.name)
                        PermissionDecision.ALLOW_ONCE -> {}
                    }
                }

                val response = tools.execute(call.name, call.args)
                val isError = response.has("error")
                val shown = (response.get("error") ?: response.get("result"))?.asString.orEmpty()
                sink.toolResult(shown, isError)
                results.append(section(call.name, summary, if (isError) "error: $shown" else shown))
            }
            if (cancelled) return

            val payload = clip(results.toString(), config.maxMessageChars)
            transcript.add(payload)
            message = compose(payload, specs)
        }
    }

    /**
     * Builds the message actually sent. In server-history mode the preamble goes
     * out only with the first message of the session — the conversation carries
     * it from then on. In local mode every turn re-sends the whole transcript.
     */
    private fun compose(message: String, specs: List<ToolSpec>): String {
        val local = config.historyMode == "local"
        // Project memory first, tool contract last: the contract is the thing the
        // model must still be following several turns later, so it goes closest
        // to the task rather than buried behind a wall of repo notes.
        val preamble = config.systemPrompt + projectOutline() + projectMemory() +
            CopilotProtocol.instructions(specs)

        if (local) {
            val history = transcript.dropLast(1).joinToString("\n\n")
            return clip(
                buildString {
                    append(preamble).append("\n\n--- Conversation so far ---\n")
                    if (history.isNotBlank()) append(history).append("\n\n")
                    append(message)
                },
                config.maxMessageChars,
            )
        }

        // Deliberately not marked sent here: a turn that fails never reaches
        // Copilot, and marking it would leave the session with no tool contract
        // and no idea it is working on a project — which reads as the model
        // simply refusing to use tools.
        if (!preambleSent) {
            pendingPreamble = true
            return clip(preamble + "\n\n--- Task ---\n" + message, config.maxMessageChars)
        }
        // Later turns carry only the message, so restate the contract briefly.
        val reminder = if (specs.isEmpty()) "" else CopilotProtocol.REMINDER
        return clip(message, config.maxMessageChars - reminder.length) + reminder
    }

    /**
     * How much of one tool's output can travel back. A chat composer caps the
     * whole message, so a result larger than that budget would be clipped by the
     * transport instead of here — losing the end of a build log, which is the
     * part that says what failed.
     */
    private fun resultBudget(): Int =
        (config.maxMessageChars - 1_000).coerceIn(1_000, MAX_RESULT_CHARS)

    /** One tool's output, framed so the model can tell the sections apart. */
    private fun section(name: String, summary: String, body: String): String {
        val header = if (summary.isBlank()) "[$name]" else "[$name $summary]"
        return "\n$header\n" + clip(body, resultBudget()) + "\n"
    }

    /**
     * Whether a tool call must be confirmed under [mode]. Read-only built-ins
     * never prompt; writeFile prompts only in ASK; runCommand prompts unless BYPASS.
     */
    private fun needsConfirm(mode: PermissionMode, name: String): Boolean {
        if (mode == PermissionMode.BYPASS) return false
        return when (name) {
            "writeFile", "editFile" -> mode == PermissionMode.ASK
            "runCommand" -> true
            else -> false
        }
    }

    /**
     * Where the work is and what is in it.
     *
     * Without this Copilot has a set of tools and no idea what they would find,
     * and a chat-tuned model does not go exploring on a hunch — asked to write a
     * test it answers "I don't have access to the project's code, please paste
     * it", which is true from where it is sitting. A short listing turns the
     * tools from a theoretical capability into an obvious next step.
     */
    private fun projectOutline(): String {
        val budget = (config.maxMessageChars / 4).coerceIn(400, 4_000)
        val out = StringBuilder("\n\n--- Project ---\n")
        out.append("Working directory: ").append(workspace.primary.path).append("\n")
        workspace.roots.drop(1).forEach { out.append("Also readable: ").append(it.path).append("\n") }

        val listing = fileOutline(budget)
        if (listing.isNotBlank()) {
            out.append("Files (paths are relative to the working directory):\n").append(listing)
        }
        return out.toString()
    }

    /** A breadth-first sketch of the tree, widest level first, within [budget]. */
    private fun fileOutline(budget: Int): String {
        val out = StringBuilder()
        var level = listOf(workspace.primary)
        var depth = 0

        while (level.isNotEmpty() && depth < OUTLINE_DEPTH && out.length < budget) {
            val next = mutableListOf<File>()
            for (dir in level) {
                val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: continue
                for (entry in entries) {
                    if (entry.name.startsWith(".") || entry.name in SKIP_DIRS) continue
                    if (entry.isDirectory) next.add(entry)
                    val label = relative(entry) + if (entry.isDirectory) "/" else ""
                    if (out.length + label.length + 3 > budget) return out.toString() + "  …\n"
                    out.append("  ").append(label).append("\n")
                }
            }
            level = next
            depth++
        }
        return out.toString()
    }

    private fun relative(file: File): String =
        runCatching { workspace.primary.toPath().relativize(file.toPath()).toString() }
            .getOrDefault(file.name)

    /**
     * Project notes, kept well short of the message budget. They are useful
     * context but they are not the contract: left uncapped they crowd out the
     * tool instructions, and on a chat surface the whole middle of the message
     * is then clipped away.
     */
    private fun projectMemory(): String {
        val budget = (config.maxMessageChars / 3).coerceIn(1_000, 20_000)
        for (name in listOf("COPILOT.md", "AGENTS.md", "CLAUDE.md")) {
            val f = File(workspace.primary, name)
            if (f.isFile) {
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                val kept = if (text.length <= budget) text else text.take(budget) + "\n… (truncated)"
                return "\n\n--- Project memory ---\n$kept"
            }
        }
        return ""
    }

    /**
     * The web endpoint enforces a message-size limit that a raw build log will
     * blow straight through, so long payloads keep their head and tail — the
     * error at the end of a failing build is usually the part that matters.
     */
    private fun clip(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val head = text.take(limit * 2 / 3)
        val tail = text.takeLast(limit / 3)
        return "$head\n… (${text.length - limit} characters omitted) …\n$tail"
    }

    private fun describe(e: Throwable): String = when (e) {
        is SessionExpiredException -> e.message ?: "The saved Copilot session is no longer valid."
        else -> {
            val root = generateSequence(e) { it.cause }.last()
            root.message?.takeIf { it.isNotBlank() }
                ?: e.message?.takeIf { it.isNotBlank() }
                ?: "${root::class.simpleName ?: "Error"} (no message)"
        }
    }

    private fun emptyTurnMessage(rawSample: String): String = buildString {
        if (config.isBrowserMode) {
            append("Copilot didn't answer. If the browser window shows a reply, the page's message box ")
            append("may not be the one AI Relay typed into — set copilot.selector.input to its CSS selector.")
            return@buildString
        }
        append("Copilot replied, but no assistant text could be found in the response.")
        if (rawSample.isNotBlank()) {
            append("\n  The response started: ")
            append(rawSample.take(300).replace("\n", " ⏎ "))
            append("\n  If the text sits under a field name AI Relay doesn't know, add it to ")
            append("`copilot.text.keys` in ~/.airelay/config.properties.")
        } else {
            append(" The response was empty — the session may have expired; try `airelay copilot login`.")
        }
    }

    companion object {
        private const val MAX_ITERATIONS = 50
        private const val MAX_RESULT_CHARS = 12_000

        /** How deep the project sketch goes before it is just noise. */
        private const val OUTLINE_DEPTH = 3

        /** Directories that are build output or dependencies, never the project. */
        private val SKIP_DIRS = setOf(
            "build", "node_modules", "target", "dist", "out", "venv", "__pycache__", "vendor",
        )

        private const val NUDGE =
            "You described the change but did not apply it. Nothing written in prose reaches the " +
                "project. Emit the tool calls now — writeFile for each file, with its full content."
    }
}
