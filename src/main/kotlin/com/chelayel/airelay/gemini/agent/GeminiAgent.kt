package com.chelayel.airelay.gemini.agent

import com.chelayel.airelay.agent.PermissionDecision
import com.chelayel.airelay.agent.ToolSpec
import com.chelayel.airelay.agent.Tools
import com.chelayel.airelay.agent.Web
import com.chelayel.airelay.cli.Agent
import com.chelayel.airelay.cli.PermissionMode
import com.chelayel.airelay.cli.Sink
import com.chelayel.airelay.cli.Workspace
import com.chelayel.airelay.gemini.api.Content
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
    private val permission: PermissionMode,
    private val askMode: Boolean,
    /** Tools from the configured MCP servers; [McpManager.EMPTY] when none. */
    private val mcp: McpManager = McpManager.EMPTY,
    /** Web search and page fetch, or null when the agent is kept offline. */
    private val web: Web? = null,
    /** Prompts the user to approve a tool call; returns their decision. */
    private val confirm: (name: String, summary: String) -> PermissionDecision,
) : Agent {

    private val history = mutableListOf<Content>()
    private val approvedTools = mutableSetOf<String>()
    private val systemPrompt: String = composeSystemPrompt()

    @Volatile private var client: GeminiClient? = null
    @Volatile private var cancelled = false
    @Volatile private var activeProcess: Process? = null

    override fun describe(): String =
        "Gemini · ${config.connectionMode.label} · ${config.model}"

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

    override fun send(prompt: String, sink: Sink) {
        cancelled = false
        history.add(Content("user", listOf(Part.Text(prompt.ifBlank { "Please continue." }))))
        runCatching { loop(sink) }
            .onFailure { e -> sink.error(describe(e)) }
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
            val specs = tools.specs()
            mcp.lastErrors().forEach { sink.error("MCP server unavailable — $it") }
            mcp.describe()?.let { sink.info(it) }
            specs.map(::asFunctionDecl)
        }

        val maxRounds = config.maxToolRounds
        var iterations = 0
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

            turn.usage?.let { u -> sink.info("tokens: ${u.totalTokens} (prompt ${u.promptTokens} / output ${u.candidateTokens})") }
            val calls = turn.functionCalls
            if (turn.parts.isNotEmpty()) history.add(Content("model", turn.parts))
            if (calls.isEmpty()) {
                if (turn.text.isBlank()) sink.error(emptyTurnMessage(turn.finishReason))
                return
            }

            val responses = mutableListOf<Part>()
            for (call in calls) {
                if (cancelled) break
                val summary = tools.summarize(call.name, call.args)
                sink.toolUse(call.name, summary)

                if (needsConfirm(permission, call.name) && call.name !in approvedTools) {
                    when (confirm(call.name, summary)) {
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

                val response = tools.execute(call.name, call.args)
                val isError = response.has("error")
                val shown = (response.get("error") ?: response.get("result"))?.asString.orEmpty()
                sink.toolResult(shown, isError)
                responses.add(Part.FunctionResponse(call.name, response))
            }
            if (cancelled) break
            // Gemini expects function results in a user-role turn.
            history.add(Content("user", responses))
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
    private fun trimmed(): List<Content> {
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

    private fun emptyTurnMessage(finishReason: String?): String = when (finishReason?.uppercase()) {
        "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" ->
            "Gemini blocked this response due to safety filters. Please rephrase and try again."
        "MAX_TOKENS" ->
            "Gemini stopped before producing a visible answer (max output tokens reached). Try a shorter request."
        null, "" -> "Gemini returned an empty response. Please try again."
        else -> "Gemini returned an empty response (finish reason: $finishReason). Please try again."
    }

    companion object {
        private const val TRIM_NOTICE =
            "(Earlier turns in this conversation were trimmed to fit the context window. " +
                "If you have been working from a plan or notes file in the repo, re-read it before continuing.)"
    }
}
