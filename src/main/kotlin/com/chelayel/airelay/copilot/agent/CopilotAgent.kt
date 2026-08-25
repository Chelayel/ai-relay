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

    /** How many times this turn has been pushed to keep going. */
    private var pushes = 0

    /** What this turn has actually done, for the verify check and the summary. */
    private val filesChanged = linkedSetOf<String>()
    private var commandsRun = 0
    /** Tool calls executed this turn, of any kind. */
    private var callsMade = 0

    /** Signatures of calls already run, to notice a loop. */
    private val callHistory = mutableListOf<String>()

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
        pushes = 0
        filesChanged.clear()
        commandsRun = 0
        callsMade = 0
        callHistory.clear()

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
                // Browser mode reports how it read the turn; replay mode hands
                // back the head of the response itself, which needs labelling.
                val label = if (config.isBrowserMode) "" else "raw: "
                sink.info(label + turn.rawSample.take(600).replace("\n", " ⏎ "))
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

            // Tool calls first. Failing that, take a reply that wrote the file
            // out in prose and save it — this surface refuses to emit a call for
            // a write on the grounds that it would be pretending to execute
            // something, but it writes the file itself without hesitation. The
            // harness does the executing; that was always true, and dictation
            // just stops requiring Copilot to say otherwise.
            val calls = when {
                askMode -> emptyList()
                else -> CopilotProtocol.parseCalls(turn.text)
                    .ifEmpty { CopilotProtocol.dictatedFiles(turn.text) }
            }
            if (calls.isEmpty()) {
                // A reply with no tool call is a turn trying to end. An agent only
                // gets to end when the work is actually done, so each way of
                // stopping short is pushed back on once — and only once, so a
                // model that means it can still finish.
                val push = endOfTurnPush(turn.text)
                if (push != null && pushes < MAX_PUSHES) {
                    pushes++
                    sink.info(push.note)
                    message = compose(push.message, specs)
                    continue
                }
                reportProgress(sink)
                return
            }

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

                val signature = call.name + " " + call.args
                callHistory.add(signature)
                if (callHistory.count { it == signature } > REPEAT_LIMIT) {
                    val note = "You have already run this exact call $REPEAT_LIMIT times and got the " +
                        "same result. Do something different, or finish."
                    sink.toolResult(note, true)
                    results.append(section(call.name, summary, "error: $note"))
                    continue
                }

                val response = tools.execute(call.name, call.args)
                val isError = response.has("error")
                if (!isError) {
                    callsMade++
                    when (call.name) {
                        "writeFile", "editFile" -> call.args.get("path")?.asString?.let(filesChanged::add)
                        "runCommand" -> commandsRun++
                    }
                }
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

    /** A reason to keep going, and what to say to make it happen. */
    private class Push(val note: String, val message: String)

    /**
     * Why this turn should not end yet, or null if it may.
     *
     * Four ways a chat model stops short of finishing: it denies having the
     * files at all, it writes the code out instead of applying it, it changes
     * files and never checks its work, and it asks whether to carry on. An agent
     * does none of those.
     */
    private fun endOfTurnPush(reply: String): Push? = when {
        askMode -> null

        // First, because it is a refusal of the whole arrangement rather than a
        // turn that fell short — and because the correction is different: the
        // others ask for more work, this one corrects a false belief.
        CopilotProtocol.deniesAccess(reply) -> Push(
            "Copilot said it has no access to the project — telling it that asking is the access.",
            "You do have access: AI Relay has this project's files open on the user's machine and " +
                "runs your tool calls against them. Do not ask for anything to be pasted. Emit a " +
                "```tool block now — readFile with the path you need — and the contents will come " +
                "back in the next message.",
        )

        // Only when nothing has been called all turn. A model that has been
        // using its tools and then answers with a fenced quote is reporting,
        // not dodging — the fence is usually the file it just read. Pushing
        // there demands writes for a read-only question, and the model, asked
        // to change files for a question that needed none, talks itself back
        // out of the arrangement entirely: "I cannot actually emit or execute
        // AI Relay's tools." The nudge caused the refusal it was guarding against.
        callsMade == 0 && CopilotProtocol.looksLikeUncalledWork(reply) -> Push(
            "No tool call in that reply — asking Copilot to apply it, not describe it.",
            NUDGE,
        )

        filesChanged.isNotEmpty() && commandsRun == 0 -> Push(
            "Changed ${filesChanged.size} file(s) without checking — asking Copilot to verify.",
            "You changed ${filesChanged.joinToString(", ")} but never ran anything to check it. " +
                "Run the project's build or tests with runCommand, and fix whatever fails. " +
                "If there is genuinely nothing to run here, say so in one line.",
        )

        CopilotProtocol.offersToContinue(reply) -> Push(
            "That reply asked whether to continue — telling Copilot to just do it.",
            "Don't ask whether to continue: do it. Carry out what you just offered, using the tools, " +
                "and only reply in prose once it is done.",
        )

        else -> null
    }

    /** A closing line of what the turn actually changed, as an agent should report. */
    private fun reportProgress(sink: Sink) {
        if (filesChanged.isEmpty() && commandsRun == 0) return
        val parts = buildList {
            if (filesChanged.isNotEmpty()) {
                add("changed ${filesChanged.size} file(s): " + filesChanged.joinToString(", "))
            }
            if (commandsRun > 0) add("ran $commandsRun command(s)")
        }
        sink.info(parts.joinToString("; "))
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

        /** How many times one turn may be pushed to keep working. */
        private const val MAX_PUSHES = 3

        /** How often the same call may repeat before it counts as spinning. */
        private const val REPEAT_LIMIT = 2

        private const val NUDGE =
            "You described the change but did not apply it. Nothing written in prose reaches the " +
                "project. Emit the tool calls now — writeFile for each file, with its full content."
    }
}
