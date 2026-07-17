package com.chelayel.airelay.gemini.agent

import com.chelayel.airelay.cli.Agent
import com.chelayel.airelay.cli.PermissionMode
import com.chelayel.airelay.cli.Sink
import com.chelayel.airelay.cli.Workspace
import com.chelayel.airelay.gemini.api.Content
import com.chelayel.airelay.gemini.api.GeminiClient
import com.chelayel.airelay.gemini.api.GeminiConfig
import com.chelayel.airelay.gemini.api.Part
import com.google.gson.JsonObject
import java.io.File

/** The user's answer to a permission prompt. */
enum class PermissionDecision { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

/**
 * The Gemini backend: connects via the API key / Vertex / Apigee transport and
 * runs the agentic loop — stream a model turn, run any tool calls, feed the
 * results back, and repeat until the model answers with plain text (or the cap
 * is hit). Ported from Gemini Relay's AgentSession, made synchronous (each [send]
 * blocks until the turn finishes) and console-driven.
 */
class GeminiAgent(
    private val workspace: Workspace,
    private val config: GeminiConfig,
    private val permission: PermissionMode,
    private val askMode: Boolean,
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
            onProcessEnd = { activeProcess = null }
        )
        // Ask mode is strictly read-only: no tools at all.
        val declarations = if (askMode) emptyList() else tools.declarations()

        var iterations = 0
        while (!cancelled) {
            if (iterations++ > MAX_ITERATIONS) {
                sink.error("Stopped after $MAX_ITERATIONS tool rounds without a final answer.")
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

    private fun trimmed(): List<Content> =
        if (history.size <= MEMORY_WINDOW) history.toList() else history.takeLast(MEMORY_WINDOW)

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
        private const val MEMORY_WINDOW = 100
        private const val MAX_ITERATIONS = 50
    }
}
