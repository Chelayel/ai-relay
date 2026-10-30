package com.chelayel.airelay.gemini.agent

import com.chelayel.airelay.agent.PermissionDecision
import com.chelayel.airelay.agent.ToolSpec
import com.chelayel.airelay.agent.Tools
import com.chelayel.airelay.agent.Web
import com.chelayel.airelay.cli.Agent
import com.chelayel.airelay.cli.Attachment
import com.chelayel.airelay.cli.PermissionMode
import com.chelayel.airelay.cli.Sink
import com.chelayel.airelay.cli.Workspace
import com.chelayel.airelay.gemini.api.Content
import com.chelayel.airelay.gemini.api.ContentJson
import com.chelayel.airelay.gemini.api.FunctionDecl
import com.chelayel.airelay.gemini.api.GeminiClient
import com.chelayel.airelay.gemini.api.GeminiConfig
import com.chelayel.airelay.gemini.api.Part
import com.chelayel.airelay.mcp.McpManager
import com.google.gson.JsonObject
import java.io.File

/**
 * The Gemini backend: connects via the API key / Vertex / Apigee transport and
 * runs the agentic loop — stream a model turn, run any tool calls, feed the
 * results back, and repeat until the model answers with plain text (or the cap
 * is hit). Ported from Gemini Relay's AgentSession, made synchronous (each [send]
 * blocks until the turn finishes) and console-driven.
 *
 * The tools it offers are the union of three sources — the workspace built-ins,
 * the web tools, and whatever the configured MCP servers expose — merged here
 * because Gemini takes one flat list of function declarations and the model
 * should not be able to tell them apart.
 */
class GeminiAgent(
    private val workspace: Workspace,
    private val config: GeminiConfig,
    private var permission: PermissionMode,
    private val askMode: Boolean,
    /** Tools from the configured MCP servers; [McpManager.EMPTY] when none. */
    private val mcp: McpManager = McpManager.EMPTY,
    /** Web search and page fetch, or null when the agent is kept offline. */
    private val web: Web? = null,
    /** Prompts the user to approve a tool call; returns their decision. */
    private val confirm: (name: String, summary: String, detail: String) -> PermissionDecision,
) : Agent {

    private val history = mutableListOf<Content>()
    private val approvedTools = mutableSetOf<String>()
    /** How often each call (name + arguments) ran this turn: the third is refused. */
    private val callCounts = HashMap<String, Int>()
    private fun repeated(call: Part.FunctionCall): Boolean = (callCounts[call.name + "\u0000" + call.args.toString()] ?: 0) >= 2
    private val baseSystemPrompt: String = composeSystemPrompt()
    @Volatile private var persona: com.chelayel.airelay.agent.Persona? = null
    // The project map, taken once per turn: walking the tree every tool round would cost
    // a disk walk per round for a picture that barely changes within one turn.
    @Volatile private var outline: String = ""
    private val systemPrompt: String get() {
        val base = persona?.let { "You are \"${it.name}\".\n\n${it.prompt()}\n\n---\n\n$baseSystemPrompt" } ?: baseSystemPrompt
        val withMap = if (outline.isBlank()) base else "$base\n\n$outline"
        return if (permission == PermissionMode.READ_ONLY) withMap + READ_ONLY_NOTE else withMap
    }

    override fun usePersona(persona: com.chelayel.airelay.agent.Persona?): Boolean { this.persona = persona; return true }
    override fun currentPersona(): String? = persona?.name

    @Volatile private var client: GeminiClient? = null
    @Volatile private var cancelled = false
    @Volatile private var activeProcess: Process? = null

    override fun describe(): String =
        "Gemini · ${config.connectionMode.label} · ${config.model}"

    private var id = java.util.UUID.randomUUID().toString()
    override fun sessionId(): String = id

    /** The whole history is the state, so a saved conversation resumes exactly where it was. */
    override fun saveState(): com.google.gson.JsonElement =
        com.google.gson.JsonArray().apply { history.forEach { add(ContentJson.toJson(it)) } }

    override fun resume(sessionId: String, state: com.google.gson.JsonElement?): Boolean {
        val contents = state?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { ContentJson.fromJson(it.asJsonObject) } ?: return false
        history.clear(); history.addAll(contents)
        id = sessionId
        return true
    }

    override fun models(): List<String> = config.pickerModels()
    override fun currentModel(): String = config.model

    /**
     * With a Gemini API key, what the key can call today, newest family first. An
     * Apigee gateway publishes exactly the ids it was given (`apigee.agents`) and
     * Vertex has no listing this connection can rely on, so those keep their list.
     */
    override fun discoverModels(): List<com.chelayel.airelay.cli.ModelInfo>? {
        if (config.connectionMode != com.chelayel.airelay.gemini.api.ConnectionMode.GEMINI_API) return null
        val live = runCatching { com.chelayel.airelay.gemini.api.GeminiClient(config).listModels() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return (listOf(config.model) + config.models + live).distinct().map { com.chelayel.airelay.cli.ModelInfo(it) }
    }
    override fun useModel(name: String): Boolean {
        config.model = GeminiConfig.canonicalModel(name, config.connectionMode)
        return true
    }

    override fun setPermissionMode(mode: PermissionMode): Boolean { permission = mode; return true }
    override fun addDir(dir: File): Boolean = workspace.add(dir)

    override fun cancel() {
        cancelled = true
        client?.cancel()
        activeProcess?.let { p ->
            runCatching {
                p.descendants().forEach { it.destroyForcibly() }
            }
            runCatching { p.destroyForcibly() }
        }
    }

    override fun close() {
        cancel()
    }

    // Every round re-sends the whole history, so each one is billed in full;
    // the session total is what the header shows (as Claude's CLI reports it).
    private var sessionCost = 0.0
    private var priced = false
    private var turnContext = 0L
    /** The last turn's context, which decides whether the next one starts by compacting. */
    private var lastContext = 0L
    // Said once, and again only when it changes: the header already names the servers.
    private var mcpAnnounced: String? = null

    override fun send(prompt: String, sink: Sink, attachments: List<Attachment>) {
        cancelled = false
        // Gemini is multimodal: an image goes in as an inline part beside the text.
        val images = attachments.filter { it.isImage }.map { Part.InlineData(it.mimeType, it.dataBase64) }
        attachments.filter { !it.isImage }.forEach { sink.info("Skipped ${it.name}: only images can be attached.") }
        // Near the window, the conversation so far becomes a summary before this message goes out.
        val window = config.contextWindow()
        if (window != null && config.compactAt > 0 && lastContext > window * config.compactAt) {
            sink.info("The conversation is at ${lastContext * 100 / window}% of the context window: summarizing it to carry on.")
            compact(sink)
        }
        history.add(Content("user", images + Part.Text(prompt.ifBlank { "Please continue." })))
        turnContext = 0
        callCounts.clear()
        // Gemini was told nothing about the project — not even where it is — and, handed one
        // file by the IDE, answered from that file. Copilot's agent gets this map too.
        outline = runCatching { com.chelayel.airelay.agent.ProjectOutline.describe(workspace, OUTLINE_BUDGET) }.getOrDefault("")
        runCatching { loop(sink) }
            .onFailure { e -> sink.error(describe(e)) }
        // Once per turn, not per round: the header shows it, the transcript is left to the work.
        if (turnContext > 0) { lastContext = turnContext; sink.usage(turnContext, sessionCost.takeIf { priced }, config.contextWindow()) }
        if (cancelled) {
            sink.error("Stopped.")
        }
        sink.turnComplete()
    }

    private fun loop(sink: Sink) {
        val tools = Tools(
            workspace = workspace,
            commandTimeoutSeconds = config.commandTimeoutSeconds,
            onProcessStart = { proc -> activeProcess = proc },
            onProcessEnd = { activeProcess = null },
            web = web,
            mcp = mcp,
        )
        // Ask mode is strictly read-only: no tools at all.
        val declarations = if (askMode) emptyList() else {
            // Read-only is offered only the readers and the web; the mode is read per turn,
            // so /mode takes effect with the next message.
            val specs = tools.specs(permission)
            if (permission != PermissionMode.READ_ONLY) {
                mcp.lastErrors().forEach { sink.error("MCP server unavailable — $it") }
                mcp.describe()?.takeIf { it != mcpAnnounced }?.let { sink.info(it); mcpAnnounced = it }
            }
            specs.map(::asFunctionDecl)
        }

        val maxRounds = config.maxToolRounds
        var iterations = 0
        var emptyTurns = 0
        while (!cancelled) {
            if (iterations++ > maxRounds) {
                sink.error(
                    "Stopped after $maxRounds tool rounds without a final answer. " +
                        "Raise gemini.max.tool.rounds if the task legitimately needs more.",
                )
                return
            }
            val c = GeminiClient(config)
            client = c

            val turn = c.streamTurn(trimmed(), systemPrompt, declarations) { delta ->
                sink.assistantText(delta)
            }
            if (cancelled) break

            turn.usage?.let { u ->
                turnContext = u.totalTokens
                config.priceFor(config.model)?.let { p ->
                    sessionCost += p.cost(u.promptTokens, u.candidateTokens + u.thoughtTokens)
                    priced = true
                }
            }
            val calls = turn.functionCalls
            if (turn.parts.isNotEmpty()) history.add(Content("model", turn.parts))
            if (calls.isEmpty()) {
                if (turn.text.isNotBlank()) return
                blockedTurnMessage(turn.finishReason)?.let { sink.error(it); return }
                if (++emptyTurns > EMPTY_TURN_RETRIES) {
                    sink.error(emptyTurnMessage(turn.finishReason, emptyTurns - 1))
                    return
                }
                // Nothing was added to history (an empty turn has no parts), so the
                // next round re-sends the identical request. On the last attempt,
                // ask for the answer explicitly: a model that has silently decided
                // it is finished needs a new turn to react to, not the same one.
                if (emptyTurns == EMPTY_TURN_RETRIES) {
                    history.add(Content("user", listOf(Part.Text(EMPTY_TURN_NUDGE))))
                }
                sink.info("Gemini returned nothing; retrying ($emptyTurns/$EMPTY_TURN_RETRIES).")
                if (!sleep(RETRY_BACKOFF_MS * emptyTurns)) break
                continue
            }
            emptyTurns = 0

            val responses = mutableListOf<Part>()
            // A round of nothing but reads and searches runs them side by side: the model
            // asked for them together, none changes anything, and none needs a question.
            // Results still go back, and to the screen, in the order they were asked.
            val prefetched = if (calls.size > 1 && calls.all { !tools.mutates(it.name) && !needsConfirm(permission, it.name) && !repeated(it) })
                calls.map { call -> java.util.concurrent.CompletableFuture.supplyAsync({ tools.execute(call.name, call.args) }, PARALLEL) }
            else null
            // Whatever ends this round — a cancel, a throwing confirm, a tool that blew up —
            // every call the model made gets a response part, or Gemini rejects the whole
            // conversation from then on ("function response parts must match function calls").
            try { for ((index, call) in calls.withIndex()) {
                if (cancelled) break
                val summary = tools.summarize(call.name, call.args)
                sink.toolUse(call.name, summary)

                // The same call again, after it already ran twice this turn with these
                // arguments: a model going round in circles. Telling it so costs a line;
                // running it again re-sends the whole output.
                if (prefetched == null && repeated(call)) {
                    sink.toolResult(REPEAT_REFUSAL, true)
                    responses.add(Part.FunctionResponse(call.name, JsonObject().apply { addProperty("error", REPEAT_REFUSAL) }))
                    continue
                }
                callCounts.merge(call.name + "\u0000" + call.args.toString(), 1, Int::plus)

                // Not offered in read-only, but a model can still name a tool it saw earlier in the
                // conversation. Refused outright: read-only is a promise, not a default to confirm past.
                if (permission == PermissionMode.READ_ONLY && tools.mutates(call.name)) {
                    sink.toolResult(READ_ONLY_REFUSAL, true)
                    responses.add(Part.FunctionResponse(call.name, JsonObject().apply { addProperty("error", READ_ONLY_REFUSAL) }))
                    continue
                }

                if (needsConfirm(permission, call.name) && call.name !in approvedTools) {
                    when (confirm(call.name, summary, tools.detail(call.name, call.args))) {
                        PermissionDecision.DENY -> {
                            sink.toolResult("Denied by user.", true)
                            responses.add(Part.FunctionResponse(call.name, JsonObject().apply {
                                addProperty("error", "The user denied permission to run this tool.")
                            }))
                            continue
                        }
                        PermissionDecision.ALLOW_ALWAYS -> approvedTools.add(call.name)
                        PermissionDecision.ALLOW_ONCE -> {}
                    }
                }

                val response = prefetched?.get(index)?.get() ?: tools.execute(call.name, call.args)
                val change = tools.takeChange(response)
                val isError = response.has("error")
                val shown = (response.get("error") ?: response.get("result"))?.asString.orEmpty()
                sink.toolResult(shown, isError)
                change?.let { sink.fileChanged(it.path, it.diff, it.revertId) }
                responses.add(Part.FunctionResponse(call.name, response))
            } } finally {
                for (call in calls.drop(responses.size)) {
                    responses.add(Part.FunctionResponse(call.name, JsonObject().apply { addProperty("error", "Not run: the turn was interrupted.") }))
                }
                // Gemini expects function results in a user-role turn.
                history.add(Content("user", responses))
            }
            if (cancelled) break
        }
    }

    /** Gemini declares tools on the wire, so render each neutral spec as a declaration. */
    private fun asFunctionDecl(spec: ToolSpec): FunctionDecl =
        FunctionDecl(spec.name, spec.description, spec.parameters)

    /**
     * Whether a tool call must be confirmed under [mode]. Read-only built-ins
     * never prompt; writeFile prompts only in ASK; runCommand prompts unless BYPASS.
     *
     * An MCP tool prompts in ASK too: it is somebody else's code, its effects are
     * whatever that server chooses, and unlike the built-ins it is not confined
     * to the workspace. Grouping it with the edits rather than with runCommand
     * keeps a read-mostly server (docs lookup, issue search) usable in the
     * accept-edits mode people actually work in.
     */
    private fun needsConfirm(mode: PermissionMode, name: String): Boolean {
        if (mode == PermissionMode.BYPASS) return false
        if (mcp.handles(name)) return mode == PermissionMode.ASK
        return when (name) {
            "writeFile", "editFile" -> mode == PermissionMode.ASK
            "runCommand" -> true
            else -> false
        }
    }

    /** Fold any project-memory files into the base system prompt. */
    private fun composeSystemPrompt(): String {
        val memory = readProjectMemory()
        return if (memory.isNullOrBlank()) config.systemPrompt
        else config.systemPrompt + "\n\n--- Project memory ---\n" + memory
    }

    private fun readProjectMemory(): String? {
        for (name in listOf("GEMINI.md", "AGENTS.md", "CLAUDE.md")) {
            val f = File(workspace.primary, name)
            if (f.isFile) return runCatching { f.readText().take(20_000) }.getOrNull()
        }
        return null
    }

    /**
     * The slice of history sent this round.
     *
     * A plain `takeLast` was wrong in two ways that only showed up on long jobs.
     * It could begin the window on a turn carrying `functionResponse` parts whose
     * `functionCall` had just been cut away, which Gemini rejects outright — so
     * the window is advanced to the next turn that isn't a set of orphaned
     * results. And it dropped the very first message, which is the task itself:
     * a migration that ran long enough to trim lost the description of what it
     * was migrating and drifted. The opening request is always kept, with a note
     * that the middle is gone so the model knows to re-read its own plan file.
     */
    /**
     * The whole history so far becomes one summary, written by the model itself with no
     * tools, and the conversation carries on from it. Only between turns: the history then
     * ends on a model answer, so nothing pairs a call with a result across the cut.
     */
    override fun compact(sink: Sink): Boolean {
        if (history.size < 2) return false
        val c = GeminiClient(config)
        client = c
        val summary = runCatching {
            c.streamTurn(trimmed() + Content("user", listOf(Part.Text(COMPACT_REQUEST))), "You summarize a coding session so it can continue.", emptyList()) {}
                .text.trim()
        }.getOrElse { e -> sink.error("Could not summarize the conversation: ${describe(e)}"); return false }
        if (summary.isBlank()) { sink.error("Could not summarize the conversation: the model returned nothing."); return false }
        val before = history.size
        history.clear()
        history.add(Content("user", listOf(Part.Text("$COMPACT_PREFIX\n\n$summary"))))
        history.add(Content("model", listOf(Part.Text("Understood. I'll carry on from that summary."))))
        lastContext = 0
        sink.info("Summarized $before messages into one; the conversation carries on from the summary.")
        return true
    }

    private fun trimmed(): List<Content> = HistoryCompactor.compact(windowed(), config.historyVerbatim)

    private fun windowed(): List<Content> {
        val window = config.historyWindow
        if (history.size <= window) return history.toList()

        var start = history.size - window
        while (start < history.size && history[start].parts.any { it is Part.FunctionResponse }) start++
        if (start >= history.size) return history.takeLast(1)

        val tail = history.subList(start, history.size).toList()
        if (start == 0) return tail
        return listOf(history.first(), Content("user", listOf(Part.Text(TRIM_NOTICE)))) + tail
    }

    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val msg = root.message?.takeIf { it.isNotBlank() } ?: e.message?.takeIf { it.isNotBlank() }
        return msg ?: "${root::class.simpleName ?: "Error"} (no message)"
    }

    /** Sleeps between retries; false when the turn was cancelled meanwhile. */
    private fun sleep(millis: Long): Boolean {
        return try {
            Thread.sleep(millis)
            !cancelled
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    companion object {
        const val COMPACT_REQUEST = "Summarize our conversation so far so you can continue the work from the summary alone. " +
            "Include: the user's task in their words; decisions made and why; every file read or changed, with paths and what changed; " +
            "commands run and their outcomes; open problems; and what remains to do next. Be specific; omit pleasantries."
        const val COMPACT_PREFIX = "Summary of our conversation so far (the earlier messages were compacted to save context):"
        const val REPEAT_REFUSAL = "Not run again: this exact call already ran twice in this turn and its result is above. " +
            "Use that result, or change the arguments."
        /** Side-by-side reads (see the round loop); daemon threads, so an exiting CLI never waits on one. */
        private val PARALLEL = java.util.concurrent.Executors.newFixedThreadPool(4) { r -> Thread(r, "gemini-tools").apply { isDaemon = true } }
        const val READ_ONLY_REFUSAL = "Read-only mode: this tool is not available. Answer from what you can read, and describe any change for the user to make."
        /** Characters of project map in the system prompt: enough to see the layout, not a dump. */
        private const val OUTLINE_BUDGET = 6_000

        const val READ_ONLY_NOTE = "\n\n--- Read-only ---\nThis session is read-only. Answer the user's question by reading and searching the " +
            "project (and the web tools, when offered). Do not edit, create or delete files and do not run commands: " +
            "those tools are not available. Where a change is the answer, describe it or show it as a snippet for the user to apply."

        private const val TRIM_NOTICE =
            "(Earlier turns in this conversation were trimmed to fit the context window. " +
                "If you have been working from a plan or notes file in the repo, re-read it before continuing.)"

        /**
         * How many times an empty turn is re-sent before the user is told.
         *
         * A turn that ends `STOP` with no text and no function call is a model
         * that finished without saying anything — most often after a tool result,
         * where the whole visible answer goes missing and the job stops one step
         * from done. It is not deterministic: the same request usually answers on
         * the next attempt, so re-send it rather than handing the user an error
         * and an unfinished task.
         */
        internal const val EMPTY_TURN_RETRIES = 2

        private const val RETRY_BACKOFF_MS = 600L

        private const val EMPTY_TURN_NUDGE =
            "Your last response came back empty. Continue from where you left off: " +
                "either call the next tool you need, or give the final answer as text."

        /**
         * The error for an empty turn that will not answer differently if asked
         * again — a filter refused it, or it ran out of output budget. Null for
         * the reasons worth retrying (`STOP`, `OTHER`, or a stream that ended
         * without saying why), which is the common case.
         */
        internal fun blockedTurnMessage(finishReason: String?): String? = when (finishReason?.uppercase()) {
            "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "RECITATION", "IMAGE_SAFETY" ->
                "Gemini blocked this response (finish reason: $finishReason). Please rephrase and try again."
            "MAX_TOKENS" ->
                "Gemini stopped before producing a visible answer (max output tokens reached). Try a shorter request."
            else -> null
        }

        internal fun emptyTurnMessage(finishReason: String?, retries: Int): String {
            val reason = if (finishReason.isNullOrBlank()) "" else " (finish reason: $finishReason)"
            val tried = if (retries > 0) " after $retries retr${if (retries == 1) "y" else "ies"}" else ""
            return "Gemini returned an empty response$reason$tried. Please try again."
        }
    }
}
