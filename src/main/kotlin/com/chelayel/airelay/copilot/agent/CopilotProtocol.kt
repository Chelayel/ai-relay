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

    /**
     * A compact restatement of the contract, appended to every later message.
     *
     * The contract is sent once with the first message, but the conversation
     * lives on a chat surface whose model drifts back to being a chat assistant:
     * asked to write a test, it writes the test out in prose and nothing is ever
     * saved. Repeating the rule costs a couple of hundred characters a turn and
     * is what keeps files actually being written.
     */
    val REMINDER = """
        [Reminder: AI Relay has these files open and runs your calls, so asking IS the access —
        never reply that you lack it. Use a ```tool block —
        ```tool
        {"tool": "editFile", "args": {"path": "src/Foo.kt", "find": "old", "replace": "new"}}
        ```
        editFile changes a file, writeFile creates one, runCommand verifies. Code in prose is
        never saved. Keep going until the task is done.]
    """.trimIndent()

    /** Renders the tool contract appended to the system prompt. */
    fun instructions(specs: List<ToolSpec>): String {
        if (specs.isEmpty()) return ""
        val catalogue = specs.joinToString("\n\n") { spec ->
            "- ${spec.name}: ${spec.description}\n  arguments: ${spec.parameters}"
        }
        return """

            --- Tools ---
            You have no access to this project's files, and none is expected of you.
            A program on the user's machine — AI Relay — has the files open and does
            the reading and writing for you. You ask for something; it performs the
            action and sends you the result as the next message.

            So do not reply that you cannot see the project, that the files are not
            available to you, or that the user should paste them. Asking is how you
            see them, and it always works. This is a request you write, not a
            capability you need to possess.

            To ask, reply with a fenced block whose language tag is `tool`, containing
            a single JSON object:

            ```tool
            {"tool": "readFile", "args": {"path": "src/Main.kt"}}
            ```

            Rules:
            - Emit one JSON object per block. Several blocks in one reply run in order.
            - After you emit tool blocks, stop. The results arrive as the next message,
              one section per call, and you continue from there.
            - Put nothing else in a tool block, and never wrap it in extra quoting.
            - Use editFile to change an existing file: send only the lines you are changing,
              copied exactly from the file. writeFile replaces a whole file and is for new
              files. Very long content can go in several writeFile calls with append=true.
            - Read a large file in pieces with readFile offset and limit.
            - Keep working across as many rounds as the task needs. Reply in plain prose with
              no tool block only when the task is finished or you are genuinely blocked.
            - Never claim you changed a file unless the tool call actually succeeded.

            If you would rather not use a tool block for a new or rewritten file, you
            may simply write the file out: put its path on its own line, then the whole
            file in a plain code fence. AI Relay saves what you write there. Do this
            instead of saying you are unable to create the file.

            src/test/kotlin/Example.kt
            ```kotlin
            // the entire file, exactly as it should be saved
            ```

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
        // A chat page can render the same reply twice — once in the thread and
        // once in a live region — and reading it back would then run every tool
        // twice. Identical calls in one reply are one call.
        return calls.distinctBy { it.name to it.args.toString() }
    }

    /** True when a reply is offering code instead of writing it through a tool. */
    fun looksLikeUncalledWork(text: String): Boolean =
        text.contains("```") && parseCalls(text).isEmpty()

    /**
     * True when a reply stops to ask whether to carry on.
     *
     * An assistant checks in before doing more; an agent finishes the job. The
     * phrasings below are how a chat model hands control back — and each one is
     * a turn that ended with the work half done.
     */
    fun offersToContinue(text: String): Boolean {
        val flat = text.lowercase().replace(Regex("\\s+"), " ")
        // A question mark alone is not enough: an answer may legitimately end
        // with one. It has to read as an offer to do more work.
        return CONTINUATION_OFFERS.any { flat.contains(it) }
    }

    private val CONTINUATION_OFFERS = listOf(
        "would you like me", "would you like to", "do you want me", "shall i",
        "let me know if", "let me know whether", "if you'd like", "if you would like",
        "i can also", "i could also", "want me to", "should i proceed",
        "just say the word", "happy to", "next step would be", "you can then",
    )

    /**
     * True when the reply refuses on the grounds of not having the files.
     *
     * The most common way this chat surface declines the whole arrangement:
     * "I can't actually use the project-specific readFile tool described in your
     * prompt, and the project files are not available in my current
     * environment." It is a reasonable thing for a chat assistant to believe and
     * it is wrong — the files are open on the user's machine and asking is what
     * reaches them — so it earns a correction rather than being taken as the
     * final answer.
     *
     * Only counted when the reply asked for nothing: a turn that made a call and
     * mentioned its limits in passing is working, not refusing.
     */
    fun deniesAccess(text: String): Boolean {
        val flat = text.lowercase().replace(Regex("\\s+"), " ")
        return ACCESS_DENIALS.any { flat.contains(it) }
    }

    /**
     * Matched on the shape of the refusal, not its exact wording.
     *
     * The first pass listed the sentence M365 happened to produce that day —
     * "not available in my current environment" — and missed the very next
     * variation, "not available in this environment", by one word. The model
     * rephrases freely; what stays constant is a claim of not having the
     * files, or a request to be given them.
     */
    private val ACCESS_DENIALS = listOf(
        // Not a bare "have access to": our own correction says "You do have
        // access", and the page echoes our messages back. Matching that would
        // let the loop trigger itself on its own push and argue with an echo.
        "only have access to", "don't have access", "do not have access",
        "doesn't have access", "no access to",
        "not available in", "not available to me", "not actually available",
        // Covers "actually use", "actually create", "actually emit",
        // "actually execute" — the verb varies, the hedge does not.
        "i can't actually", "i cannot actually",
        "i don't have the ability to", "i can't read files",
        "i cannot read files", "i don't have file access",
        "please paste", "share the contents", "provide the contents",
        "if you paste", "you'll need to paste",
    )

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

    /**
     * A fenced block. The newline after the language tag is optional because a
     * reply read back off the rendered page has had its newlines collapsed, so
     * the whole fence arrives on one line — and an unrecognised fence is one
     * that gets printed at the user instead of being run and hidden.
     */
    private val FENCE = Regex("```([A-Za-z_]*)[ \\t]*\\r?\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)

    // ---- dictation ----------------------------------------------------------

    /**
     * Read a reply that wrote files out in prose as the writeFile calls it meant.
     *
     * This exists because M365 Copilot will not emit a tool call for a write. It
     * says so directly — "the text would only be plain text here, not an actual
     * tool invocation; I should not pretend it would be executed" — and no
     * rewording of the contract moves it, because the objection is sound from
     * where it is sitting.
     *
     * What it *will* do, readily, is write the file out in a fenced block. That
     * was the original failure of this backend: the code was produced and nothing
     * was saved. So the harness does the saving. Copilot is asked only for a path
     * above the fence, which is formatting rather than capability — and nothing
     * it writes here asserts that anything was executed.
     *
     * Strictly a fallback for when [parseCalls] found nothing: a reply that made
     * real calls is already doing the right thing, and a fenced quote in that
     * reply is usually the file it just read, not a file it wants written.
     */
    fun dictatedFiles(text: String): List<ToolCall> {
        val out = mutableListOf<ToolCall>()
        val eligible = FENCE.findAll(text).filter { isFileContent(it.groupValues[1], it.groupValues[2]) }.toList()
        for (match in eligible) {
            val body = match.groupValues[2]
            val path = pathAbove(text, match.range.first)
            // A path found only in prose is attributed to a block only when
            // there is exactly one to attribute it to. Without that rule a turn
            // that reported `./gradlew test` had its build log written into
            // SmokeTest.kt — the log was the only fence, the file name was the
            // only path mentioned, and the two were joined on no evidence.
                ?: soleMentionedPath(text).takeIf { eligible.size == 1 }
                ?: continue
            out += ToolCall(
                "writeFile",
                JsonObject().apply {
                    addProperty("path", path)
                    addProperty("content", body.trimEnd('\n'))
                },
            )
        }
        return out.distinctBy { it.args.get("path")?.asString }
    }

    /**
     * True when a fenced block is a file being written, not something quoted.
     *
     * Learned the hard way: a reply that ran the tests and pasted the output had
     * that output saved as Kotlin source, because nothing here distinguished a
     * file from a transcript. A block only counts as file content when its
     * language says so and its body does not read like a terminal.
     */
    fun isFileContent(info: String, body: String): Boolean {
        val tag = info.trim().lowercase()
        if (tag in TOOL_FENCES || tag in OUTPUT_FENCES) return false
        if (body.isBlank()) return false
        return !looksLikeTerminal(body)
    }

    /** Fence tags that mark a transcript or a command, never a file. */
    private val OUTPUT_FENCES = setOf(
        "text", "txt", "console", "output", "log", "shell", "sh", "bash", "zsh",
        "shell-session", "sh-session", "terminal", "diff", "patch",
    )

    /** Body that reads as a build log or a shell session rather than a file. */
    fun looksLikeTerminal(body: String): Boolean {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return true
        val telltale = lines.count { line ->
            line.startsWith("> Task") || line.startsWith("$ ") || line.startsWith("% ") ||
                line.startsWith("&gt; Task") ||
                line == "BUILD SUCCESSFUL" || line == "BUILD FAILED" ||
                line.startsWith("BUILD SUCCESSFUL in") || line.startsWith("BUILD FAILED in") ||
                line.startsWith("Task :") || line.contains(" tests completed,")
        }
        // One such line in a real file is imaginable — a string literal, a
        // comment. A block made mostly of them is a transcript.
        return telltale > 0 && telltale * 3 >= lines.size
    }

    /**
     * The file path named just above a fence, if there is one.
     *
     * Looks back over a couple of lines, because a page puts a blank line or a
     * sentence between the heading and the block. Anything that does not read
     * like a path is ignored rather than guessed at — writing to a path invented
     * from a sentence is the one mistake here that would be worse than doing
     * nothing.
     */
    fun pathAbove(text: String, fenceStart: Int): String? {
        val before = text.substring(0, fenceStart).lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in before.takeLast(PATH_LOOKBACK).reversed()) {
            PATH_LINE.find(line)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    /**
     * A line that names a file to write.
     *
     * Deliberately narrow: it must carry a directory separator and a short
     * extension, so "Here is the test:" and "src" are both ignored. Optional
     * decoration around it — a `File:` label, backticks, bold, a trailing colon —
     * is how a chat model actually writes a heading.
     */
    private val PATH_LINE = Regex(
        "^(?:\\*\\*)?(?:file|path|create|new file)?[:\\s]*[`*]*" +
            "([A-Za-z0-9_.\\-]+(?:/[A-Za-z0-9_.\\-]+)+\\.[A-Za-z0-9]{1,6})" +
            "[`*]*[:\\s]*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The one path the whole reply mentions, when no heading sits above a fence.
     *
     * Copilot writes the file and then names it in prose instead of labelling
     * the block — "I have not applied this change to
     * src/test/.../SmokeTest.kt" — so the path is there, just not where a
     * heading would be.
     *
     * Only when the reply mentions exactly one. Two candidates means guessing
     * which file the block belongs to, and writing a file to the wrong path is
     * worse than writing nothing: the caller asked for one file and would get a
     * corrupted other one.
     */
    fun soleMentionedPath(text: String): String? {
        val found = PATH_ANYWHERE.findAll(stripFences(text))
            .map { it.value.trim('`', '*', ',', '.', ':', ')', '(') }
            .filter { it.contains('/') }
            .distinct()
            .toList()
        return found.singleOrNull()
    }

    /** The reply with its code blocks removed, so paths *inside* a file don't count. */
    private fun stripFences(text: String): String = FENCE.replace(text, " ")

    private val PATH_ANYWHERE = Regex("[A-Za-z0-9_.\\-]+(?:/[A-Za-z0-9_.\\-]+)+\\.[A-Za-z0-9]{1,6}")

    /** How many non-blank lines above a fence may carry its path. */
    private const val PATH_LOOKBACK = 3
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

            // The language tag runs to the first whitespace. Terminating on a
            // newline alone would miss a fence that arrived on one line, which
            // is how a reply read back off the rendered page always looks.
            var tagEnd = open + FENCE.length
            while (tagEnd < buffer.length && !buffer[tagEnd].isWhitespace()) tagEnd++
            if (tagEnd >= buffer.length) {
                // The tag hasn't finished arriving; emit what precedes it.
                if (open > 0) { emit(buffer.substring(0, open)); buffer.delete(0, open) }
                if (!flushing) return
                emit(buffer.toString())
                buffer.setLength(0)
                return
            }

            val info = buffer.substring(open + FENCE.length, tagEnd).trim().lowercase()
            // Step over the single separator after the tag, newline or space.
            var contentStart = tagEnd
            if (contentStart < buffer.length && buffer[contentStart] == '\r') contentStart++
            if (contentStart < buffer.length && (buffer[contentStart] == '\n' || buffer[contentStart] == ' ')) {
                contentStart++
            }
            if (open > 0) { emit(buffer.substring(0, open)); buffer.delete(0, open) }
            val cut = contentStart - open

            if (info in HIDDEN) {
                buffer.delete(0, cut)
                hiding = true
            } else {
                // An ordinary code fence: pass the opener through. Its closing
                // ``` reads as a fence with an empty tag, which is also passed
                // through, so fenced code survives intact.
                emit(buffer.substring(0, cut))
                buffer.delete(0, cut)
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
