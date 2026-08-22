package com.chelayel.airelay.copilot.agent

import com.chelayel.airelay.agent.ToolSpec
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One tool invocation the model asked for. */
class ToolCall(val name: String, val args: JsonObject)

/**
 * The tool-calling protocol for the Copilot backend.
 *
 * The Copilot web endpoint is a chat surface, not a model API: there is no
 * `tools` field to send and no structured tool call to read back. So the tools
 * are described in the system preamble and requested as fenced JSON blocks,
 * which is a convention every current model handles well. Everything here is
 * forgiving by design — a model that decorates the block, uses ```json instead
 * of ```tool, or emits prose around it still gets its call executed.
 */
object CopilotProtocol {

    /** Fence info strings that mark a block as a tool call rather than shown code. */
    private val TOOL_FENCES = setOf("tool", "tool_call", "toolcall", "airelay")

    /** Renders the tool contract appended to the system prompt. */
    fun instructions(specs: List<ToolSpec>): String {
        if (specs.isEmpty()) return ""
        val catalogue = specs.joinToString("\n\n") { spec ->
            "- ${spec.name}: ${spec.description}\n  arguments: ${spec.parameters}"
        }
        return """

            --- Tools ---
            You can act on the user's project by calling tools. To call one, reply with a
            fenced block whose language tag is `tool`, containing a single JSON object:

            ```tool
            {"tool": "readFile", "args": {"path": "src/Main.kt"}}
            ```

            Rules:
            - Emit one JSON object per block. Several blocks in one reply run in order.
            - After you emit tool blocks, stop. The results arrive as the next message,
              one section per call, and you continue from there.
            - Put nothing else in a tool block, and never wrap it in extra quoting.
            - When the task is done, reply in plain prose with no tool block at all.
            - Never claim you changed a file unless a writeFile call actually succeeded.

            Available tools:
            $catalogue
        """.trimIndent()
    }

    /**
     * Extracts every tool call in [text] — from `tool`-tagged fences first, and
     * otherwise from any fenced or bare JSON object that carries a tool name.
     */
    fun parseCalls(text: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        for (match in FENCE.findAll(text)) {
            val info = match.groupValues[1].trim().lowercase()
            val payload = match.groupValues[2]
            if (info.isNotEmpty() && info !in TOOL_FENCES && info != "json") continue
            calls.addAll(callsIn(payload))
        }
        if (calls.isEmpty()) calls.addAll(callsIn(FENCE.replace(text, "")))
        return calls
    }

    /** [text] with tool-call fences removed, for what we keep in the transcript. */
    fun stripToolBlocks(text: String): String =
        FENCE.replace(text) { match ->
            val info = match.groupValues[1].trim().lowercase()
            if (info in TOOL_FENCES) "" else match.value
        }.trim()

    /** Parse every top-level JSON object in [payload] that names a tool. */
    private fun callsIn(payload: String): List<ToolCall> =
        jsonObjects(payload).mapNotNull { obj ->
            val name = listOf("tool", "name", "tool_name", "function")
                .firstNotNullOfOrNull { obj.get(it)?.takeIf { v -> v.isJsonPrimitive }?.asString }
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val args = listOf("args", "arguments", "parameters", "input")
                .firstNotNullOfOrNull { obj.get(it)?.takeIf { v -> v.isJsonObject }?.asJsonObject }
                ?: JsonObject()
            ToolCall(name, args)
        }

    /**
     * Scans [text] for balanced top-level `{...}` runs and parses each. Brace
     * counting (rather than a regex) is what lets a tool call carry a file's
     * whole contents, braces and all, in a `writeFile` argument.
     */
    private fun jsonObjects(text: String): List<JsonObject> {
        val found = mutableListOf<JsonObject>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false

        for ((i, c) in text.withIndex()) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            runCatching { JsonParser.parseString(text.substring(start, i + 1)) }
                                .getOrNull()?.takeIf { it.isJsonObject }
                                ?.let { found.add(it.asJsonObject) }
                            start = -1
                        }
                    }
                }
            }
        }
        return found
    }

    private val FENCE = Regex("```([A-Za-z_]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
}

/**
 * Hides tool-call fences from the live transcript while the reply streams.
 *
 * Text arrives in small deltas, so the decision "is this the start of a tool
 * block?" can't be made from one delta alone. This withholds text from the
 * first backtick that might open a fence, releases it as soon as the fence turns
 * out to be ordinary code, and swallows it when it turns out to be a tool call —
 * so the user reads prose and sees a `⚙ readFile` line, not raw JSON.
 */
class ToolBlockFilter(private val emit: (String) -> Unit) {

    private val buffer = StringBuilder()
    private var hiding = false

    fun accept(delta: String) {
        buffer.append(delta)
        drain(flushing = false)
    }

    /** Called at the end of a turn: release anything still held back. */
    fun finish() {
        drain(flushing = true)
        if (!hiding && buffer.isNotEmpty()) {
            emit(buffer.toString())
        }
        buffer.setLength(0)
        hiding = false
    }

    private fun drain(flushing: Boolean) {
        while (true) {
            if (hiding) {
                val close = buffer.indexOf(FENCE)
                if (close < 0) return                       // still inside the tool block
                buffer.delete(0, close + FENCE.length)
                if (buffer.startsWith("\n")) buffer.delete(0, 1)
                hiding = false
                continue
            }

            val open = buffer.indexOf(FENCE)
            if (open < 0) {
                // Nothing to hide. Hold back a trailing partial fence so the next
                // delta can complete it — unless the turn is over.
                val keep = if (flushing) 0 else trailingBackticks()
                if (buffer.length > keep) {
                    emit(buffer.substring(0, buffer.length - keep))
                    buffer.delete(0, buffer.length - keep)
                }
                return
            }

            val newline = buffer.indexOf("\n", open)
            if (newline < 0) {
                // The fence's language tag hasn't arrived yet; emit what precedes it.
                if (open > 0) { emit(buffer.substring(0, open)); buffer.delete(0, open) }
                if (!flushing) return
                emit(buffer.toString())
                buffer.setLength(0)
                return
            }

            val info = buffer.substring(open + FENCE.length, newline).trim().lowercase()
            if (open > 0) { emit(buffer.substring(0, open)); buffer.delete(0, open) }
            val relativeNewline = newline - open

            if (info in HIDDEN) {
                buffer.delete(0, relativeNewline + 1)
                hiding = true
            } else {
                // An ordinary code fence: pass the opener through. Its closing
                // ``` reads as a fence with an empty tag, which is also passed
                // through, so fenced code survives intact.
                emit(buffer.substring(0, relativeNewline + 1))
                buffer.delete(0, relativeNewline + 1)
            }
        }
    }

    /** How many trailing backticks could still grow into a fence. */
    private fun trailingBackticks(): Int {
        var n = 0
        while (n < buffer.length && n < FENCE.length && buffer[buffer.length - 1 - n] == '`') n++
        return if (n in 1 until FENCE.length) n else 0
    }

    private companion object {
        const val FENCE = "```"
        val HIDDEN = setOf("tool", "tool_call", "toolcall", "airelay")
    }
}
