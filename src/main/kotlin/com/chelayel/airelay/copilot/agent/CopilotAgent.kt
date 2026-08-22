package com.chelayel.airelay.copilot.agent

import com.chelayel.airelay.agent.PermissionDecision
import com.chelayel.airelay.agent.ToolSpec
import com.chelayel.airelay.agent.Tools
import com.chelayel.airelay.cli.Agent
import com.chelayel.airelay.cli.PermissionMode
import com.chelayel.airelay.cli.Sink
import com.chelayel.airelay.cli.Workspace
import com.chelayel.airelay.copilot.api.CopilotClient
import com.chelayel.airelay.copilot.api.CopilotConfig
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

    private var conversationId: String? = config.conversationId
    private var preambleSent = false

    @Volatile private var client: CopilotClient? = null
    @Volatile private var cancelled = false
    @Volatile private var activeProcess: Process? = null

    override fun describe(): String {
        val chosen = model.takeIf { it.isNotBlank() } ?: "site default model"
        return "Copilot · ${config.hostLabel()} · $chosen"
    }

    /** The model ids offered by `/model`, as captured from the web picker. */
    fun availableModels(): List<String> = config.models

    /** True when the capture had a model field to write into. */
    fun canChooseModel(): Boolean = config.canChooseModel

    /** The model in use this session. */
    fun currentModel(): String = model

    /** Switch models for the rest of the session, the way the web picker does. */
    fun useModel(id: String) {
        model = id.trim()
    }

    override fun cancel() {
        cancelled = true
        client?.cancel()
        activeProcess?.let { p ->
            runCatching { p.descendants().forEach { it.destroyForcibly() } }
            runCatching { p.destroyForcibly() }
        }
    }

    override fun close() {
        cancel()
    }

    override fun send(prompt: String, sink: Sink) {
        cancelled = false
        runCatching { loop(prompt.ifBlank { "Please continue." }, sink) }
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

        while (!cancelled) {
            if (iterations++ > MAX_ITERATIONS) {
                sink.error("Stopped after $MAX_ITERATIONS tool rounds without a final answer.")
                return
            }

            val c = CopilotClient(config)
            client = c
            val filter = ToolBlockFilter { delta -> sink.assistantText(delta) }

            val turn = try {
                c.send(message, model.takeIf { it.isNotBlank() }, conversationId) { delta ->
                    filter.accept(delta)
                }
            } finally {
                filter.finish()
            }
            if (cancelled) return

            conversationId = turn.conversationId
            if (config.debug && turn.rawSample.isNotBlank()) {
                sink.info("raw: " + turn.rawSample.take(600).replace("\n", " ⏎ "))
            }
            if (turn.text.isBlank()) {
                sink.error(emptyTurnMessage(turn.rawSample))
                return
            }

            transcript.add("Assistant: " + CopilotProtocol.stripToolBlocks(turn.text))

            val calls = if (askMode) emptyList() else CopilotProtocol.parseCalls(turn.text)
            if (calls.isEmpty()) return

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

            val payload = clip(results.toString(), MAX_MESSAGE_CHARS)
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
        val preamble = config.systemPrompt + CopilotProtocol.instructions(specs) + projectMemory()

        if (local) {
            val history = transcript.dropLast(1).joinToString("\n\n")
            return clip(
                buildString {
                    append(preamble).append("\n\n--- Conversation so far ---\n")
                    if (history.isNotBlank()) append(history).append("\n\n")
                    append(message)
                },
                MAX_MESSAGE_CHARS,
            )
        }

        if (!preambleSent) {
            preambleSent = true
            return clip(preamble + "\n\n--- Task ---\n" + message, MAX_MESSAGE_CHARS)
        }
        return clip(message, MAX_MESSAGE_CHARS)
    }

    /** One tool's output, framed so the model can tell the sections apart. */
    private fun section(name: String, summary: String, body: String): String {
        val header = if (summary.isBlank()) "[$name]" else "[$name $summary]"
        return "\n$header\n" + clip(body, MAX_RESULT_CHARS) + "\n"
    }

    /**
     * Whether a tool call must be confirmed under [mode]. Read-only built-ins
     * never prompt; writeFile prompts only in ASK; runCommand prompts unless BYPASS.
     */
    private fun needsConfirm(mode: PermissionMode, name: String): Boolean {
        if (mode == PermissionMode.BYPASS) return false
        return when (name) {
            "writeFile" -> mode == PermissionMode.ASK
            "runCommand" -> true
            else -> false
        }
    }

    private fun projectMemory(): String {
        for (name in listOf("COPILOT.md", "AGENTS.md", "CLAUDE.md")) {
            val f = File(workspace.primary, name)
            if (f.isFile) {
                val text = runCatching { f.readText().take(20_000) }.getOrNull() ?: continue
                return "\n\n--- Project memory ---\n$text"
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
        private const val MAX_MESSAGE_CHARS = 30_000
    }
}
