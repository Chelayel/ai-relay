package com.chelayel.airelay.copilot.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Reads one Copilot response, whatever form it takes.
 *
 * The endpoint is undocumented and its response format varies: some tenants
 * stream Server-Sent Events, some newline-delimited JSON, some answer with a
 * single JSON document, and some just return prose. So this sniffs rather than
 * assumes, and pulls assistant text out of unknown JSON by walking it for the
 * keys such payloads conventionally use.
 *
 * When those conventions don't match — a tenant that streams its text under a
 * name nobody guessed — [survey] answers the question directly by looking at
 * what actually came back, so `airelay copilot diagnose` can name the field
 * instead of leaving the user to hunt for it.
 *
 * Kept apart from [CopilotClient] because it is the part most likely to need
 * adjusting when the site changes, and the only part that can be tested without
 * a live browser session.
 */
internal class ResponseReader(
    extraTextKeys: List<String>,
    /** Lets a cancelled turn stop mid-stream instead of draining the response. */
    private val cancelled: () -> Boolean = { false },
) {

    private val extractor = TextExtractor(extraTextKeys)

    fun read(
        input: InputStream,
        contentType: String,
        priorConversationId: String?,
        onText: (String) -> Unit,
    ): CopilotTurn {
        val assembler = TextAssembler(onText)
        val sample = StringBuilder()
        var conversationId: String? = null

        dispatch(
            input, contentType, sample, raw = null,
            onChunk = { chunk ->
                extractError(chunk)?.let { throw CopilotException("Copilot returned an error: $it") }
                findConversationId(chunk)?.let { conversationId = it }
                extractor.extract(chunk)?.let { assembler.offer(it) }
            },
            onPlainText = { assembler.offer(it) },
        )

        return CopilotTurn(
            text = assembler.text(),
            conversationId = conversationId ?: priorConversationId,
            rawSample = sample.toString(),
        )
    }

    /**
     * Read the response without trying to interpret it, recording every string
     * field it contains and where it sat. This is what turns "no assistant text
     * could be found" into "the text is under `speak` — set copilot.text.keys".
     */
    fun survey(input: InputStream, contentType: String): CopilotDiagnosis {
        val sample = StringBuilder()
        val raw = StringBuilder()
        val surveyor = ResponseSurvey()
        dispatch(
            input, contentType, sample, raw,
            onChunk = surveyor::observe,
            onPlainText = surveyor::observePlainText,
        )
        return CopilotDiagnosis(raw.toString(), surveyor.candidates())
    }

    // ---- chunking ------------------------------------------------------------

    /**
     * Split the response into chunks by whichever framing it uses, and hand each
     * to [onChunk]. A body that isn't JSON in any form goes to [onPlainText]
     * whole. Shared by [read] and [survey] so a diagnosis sees exactly the same
     * chunks a real turn would.
     */
    private fun dispatch(
        input: InputStream,
        contentType: String,
        sample: StringBuilder,
        raw: StringBuilder?,
        onChunk: (JsonElement) -> Unit,
        onPlainText: (String) -> Unit,
    ) {
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            when {
                contentType.contains("event-stream") -> readSse(reader, sample, raw, onChunk)

                // Declared as a JSON stream: parse per line as it arrives.
                JSON_STREAM_TYPES.any { contentType.contains(it) } ->
                    readJsonLines(reader, sample, raw, onChunk)

                else -> {
                    val whole = readAll(reader, sample, raw)
                    val single = asJson(whole)
                    val lines = jsonLines(whole)
                    when {
                        single != null -> onChunk(single)
                        // An undeclared JSON stream: every line has to be JSON.
                        // One JSON-looking line inside prose does not count —
                        // that is an answer containing a code block, and treating
                        // it as a stream would swallow the whole reply.
                        lines != null -> lines.forEach(onChunk)
                        whole.isNotBlank() -> onPlainText(whole)
                    }
                }
            }
        }
    }

    /** Read an SSE stream, handing each `data:` payload that parses to [onChunk]. */
    private fun readSse(
        reader: BufferedReader,
        sample: StringBuilder,
        raw: StringBuilder?,
        onChunk: (JsonElement) -> Unit,
    ) {
        val data = StringBuilder()
        var eventName = ""

        fun flush() {
            val payload = data.toString().trim()
            data.setLength(0)
            val name = eventName
            eventName = ""
            if (payload.isEmpty() || payload == "[DONE]") return
            if (name.lowercase() in SKIP_EVENTS) return
            asJson(payload)?.let(onChunk)
        }

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (cancelled()) break
            val text = line!!
            record(sample, raw, text)
            when {
                text.isBlank() -> flush()                      // end of one event
                text.startsWith(":") -> Unit                   // comment / keep-alive
                text.startsWith("event:") -> eventName = text.removePrefix("event:").trim()
                text.startsWith("data:") -> data.append(text.removePrefix("data:").trim())
                else -> Unit                                   // id:, retry:, unknown field
            }
        }
        flush()
    }

    /** Read newline-delimited JSON, one chunk per line. */
    private fun readJsonLines(
        reader: BufferedReader,
        sample: StringBuilder,
        raw: StringBuilder?,
        onChunk: (JsonElement) -> Unit,
    ) {
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (cancelled()) break
            val text = line!!
            record(sample, raw, text)
            payloadOf(text)?.let { asJson(it)?.let(onChunk) }
        }
    }

    private fun readAll(reader: BufferedReader, sample: StringBuilder, raw: StringBuilder?): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        while (!cancelled()) {
            val n = reader.read(buf)
            if (n < 0) break
            sb.appendRange(buf, 0, n)
            if (sample.length < SAMPLE_LIMIT) {
                sample.append(buf, 0, minOf(n, SAMPLE_LIMIT - sample.length))
            }
            if (raw != null && raw.length < RAW_LIMIT) {
                raw.append(buf, 0, minOf(n, RAW_LIMIT - raw.length))
            }
        }
        return sb.toString()
    }

    private fun record(sample: StringBuilder, raw: StringBuilder?, line: String) {
        if (sample.length < SAMPLE_LIMIT) sample.append(line).append('\n')
        if (raw != null && raw.length < RAW_LIMIT) raw.append(line).append('\n')
    }

    /**
     * Every line of [whole] as JSON, or null if any line isn't — which is what
     * separates a newline-delimited JSON stream from prose that happens to
     * contain a JSON object.
     */
    private fun jsonLines(whole: String): List<JsonElement>? {
        val out = mutableListOf<JsonElement>()
        for (line in whole.lineSequence()) {
            val payload = payloadOf(line) ?: continue
            out.add(asJson(payload) ?: return null)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** The meaningful part of a stream line, or null for blanks and terminators. */
    private fun payloadOf(line: String): String? =
        line.trim().removePrefix("data:").trim().takeIf { it.isNotEmpty() && it != "[DONE]" }

    private fun asJson(text: String): JsonElement? =
        runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonObject || it.isJsonArray }

    /** A top-level error object in a chunk, rendered for the user. */
    private fun extractError(chunk: JsonElement): String? {
        val obj = chunk as? JsonObject ?: return null
        val err = obj.get("error") ?: return null
        if (err.isJsonPrimitive) return err.asString.takeIf { it.isNotBlank() }
        val eo = err as? JsonObject ?: return null
        return listOf("message", "detail", "description", "code")
            .firstNotNullOfOrNull { eo.get(it)?.takeIf { v -> v.isJsonPrimitive }?.asString }
            ?: err.toString().take(200)
    }

    private fun findConversationId(chunk: JsonElement): String? =
        CONVERSATION_KEYS.firstNotNullOfOrNull { key ->
            Json.findKeyPath(chunk, listOf(key))?.let { Json.getString(chunk, it) }
        }?.takeIf { it.isNotBlank() }

    companion object {
        private const val SAMPLE_LIMIT = 2_000
        private const val RAW_LIMIT = 2_000_000

        private val JSON_STREAM_TYPES = listOf("ndjson", "json-seq", "jsonl", "json-lines")

        private val SKIP_EVENTS = setOf("done", "ping", "heartbeat", "keepalive", "keep-alive")

        private val CONVERSATION_KEYS = listOf(
            "conversationId", "conversation_id", "threadId", "thread_id", "chatId", "chat_id",
        )
    }
}

/** What a diagnostic run learned about a response nobody could parse. */
class CopilotDiagnosis(
    /** The response as it arrived, for writing to a file. */
    val raw: String,
    /** The fields that might hold the answer, most likely first. */
    val candidates: List<TextFieldCandidate>,
)

/** One field seen in a response, aggregated across every chunk it appeared in. */
class TextFieldCandidate(
    /** The field name — what goes into `copilot.text.keys`. */
    val key: String,
    /** Where it sat, with array indices collapsed, e.g. `messages[]/text`. */
    val path: String,
    val text: String,
    val chunks: Int,
) {
    /** Higher is more likely to be the answer. */
    val score: Int
        get() {
            var s = text.length + chunks * 20
            if (text.contains(' ')) s += 200
            if (SENTENCE.containsMatchIn(text)) s += 200
            if (text.startsWith("http")) s -= 500
            if (LOOKS_LIKE_ID.matches(text)) s -= 500
            if (!text.contains(' ') && text.length < 40) s -= 200
            return s
        }

    private companion object {
        val SENTENCE = Regex("[a-z] [a-z]", RegexOption.IGNORE_CASE)
        val LOOKS_LIKE_ID = Regex("[0-9a-fA-F-]{8,}|[A-Za-z0-9_+/=]{24,}")
    }
}

/**
 * Works out where the assistant text actually lives in a response nobody could
 * parse, by recording every string in it and ranking the candidates.
 *
 * The signal is simple and shape-independent: streamed prose arrives as many
 * chunks under one repeated path, adds up to a lot of characters, and reads like
 * sentences. Ids, URLs, enums and timestamps do none of those things.
 */
internal class ResponseSurvey {

    private val seen = LinkedHashMap<String, StringBuilder>()
    private val counts = LinkedHashMap<String, Int>()
    private val keys = LinkedHashMap<String, String>()

    fun observe(chunk: JsonElement) = walk(chunk, "", "", 0)

    fun observePlainText(text: String) {
        add("(whole body)", "(whole body)", text)
    }

    private fun walk(el: JsonElement, path: String, key: String, depth: Int) {
        if (depth > MAX_DEPTH || seen.size > MAX_PATHS) return
        when {
            el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                el.asString.takeIf { it.isNotBlank() }?.let { add(path, key, it) }

            el.isJsonArray -> el.asJsonArray.forEach { walk(it, "$path[]", key, depth + 1) }

            el.isJsonObject -> el.asJsonObject.entrySet().forEach { (name, value) ->
                walk(value, if (path.isEmpty()) name else "$path/$name", name, depth + 1)
            }
        }
    }

    private fun add(path: String, key: String, text: String) {
        val buffer = seen.getOrPut(path) { StringBuilder() }
        // Cumulative streams repeat everything; keep the longest rather than
        // concatenating the same prose over and over.
        if (text.startsWith(buffer)) {
            buffer.setLength(0)
            buffer.append(text.take(MAX_TEXT))
        } else if (buffer.length < MAX_TEXT) {
            buffer.append(text.take(MAX_TEXT - buffer.length))
        }
        counts[path] = (counts[path] ?: 0) + 1
        keys[path] = key
    }

    /** The most likely text fields, best first. */
    fun candidates(): List<TextFieldCandidate> = seen.entries
        .map { (path, text) -> TextFieldCandidate(keys[path] ?: path, path, text.toString(), counts[path] ?: 0) }
        .filter { it.text.isNotBlank() }
        .sortedByDescending { it.score }
        .take(8)

    private companion object {
        const val MAX_DEPTH = 12
        const val MAX_PATHS = 400
        const val MAX_TEXT = 4_000
    }
}

/**
 * Accumulates assistant text from chunks that may be either *deltas* (each chunk
 * is new text) or *cumulative* (each chunk repeats everything so far). Telling
 * them apart matters: getting it wrong either duplicates the whole answer or
 * drops most of it.
 *
 * A chunk that starts with everything seen so far is a cumulative frame, so only
 * its tail is emitted; anything else is new text and is appended as-is. The rule
 * is deliberately narrow — a chunk that merely repeats the last few characters
 * is kept, because in a delta stream that is an ordinary repeated word, and
 * silently dropping it would corrupt the answer.
 */
internal class TextAssembler(private val onText: (String) -> Unit) {
    private val sb = StringBuilder()

    fun offer(candidate: String) {
        if (candidate.isEmpty()) return
        val soFar = sb.toString()
        val delta = when {
            soFar.isEmpty() -> candidate
            candidate == soFar -> return                       // cumulative frame, unchanged
            candidate.startsWith(soFar) -> candidate.substring(soFar.length)
            else -> candidate
        }
        if (delta.isEmpty()) return
        sb.append(delta)
        onText(delta)
    }

    fun isEmpty(): Boolean = sb.isEmpty()
    fun text(): String = sb.toString()
}

/**
 * Pulls assistant text out of a JSON chunk of unknown shape by preferring the
 * keys chat APIs conventionally use, in order, and descending through the
 * containers they conventionally nest under. Chunks that announce themselves as
 * something other than assistant prose — citations, suggestions, telemetry — are
 * skipped so they don't land in the transcript.
 *
 * Configured keys from `copilot.text.keys` come first, and when any are set the
 * label filter is relaxed: an explicit instruction to read a field should not be
 * overridden by a guess about what the chunk is.
 */
internal class TextExtractor(private val extraKeys: List<String>) {

    private val keys: List<String> = (extraKeys + DEFAULT_KEYS).distinct()

    fun extract(el: JsonElement, depth: Int = 0): String? {
        if (depth > MAX_DEPTH) return null
        return when {
            el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.takeIf { it.isNotEmpty() }

            el.isJsonArray -> {
                val sb = StringBuilder()
                for (item in el.asJsonArray) extract(item, depth + 1)?.let { sb.append(it) }
                sb.toString().takeIf { it.isNotEmpty() }
            }

            el.isJsonObject -> {
                val obj = el.asJsonObject
                // A configured key present on this object wins outright.
                extraKeys.firstNotNullOfOrNull { key ->
                    obj.get(key)?.let { extract(it, depth + 1) }
                } ?: if (isNotAssistantProse(obj)) null
                else keys.firstNotNullOfOrNull { key ->
                    obj.get(key)?.let { extract(it, depth + 1) }
                }
            }

            else -> null
        }
    }

    /** True when the object labels itself as something we shouldn't print. */
    private fun isNotAssistantProse(obj: JsonObject): Boolean {
        obj.get("role")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase()?.let { role ->
            if (role !in ASSISTANT_ROLES) return true
        }
        for (label in LABEL_KEYS) {
            val value = obj.get(label)?.takeIf { it.isJsonPrimitive }?.asString?.lowercase() ?: continue
            if (SKIP_MARKERS.any { value.contains(it) }) return true
        }
        return false
    }

    companion object {
        private const val MAX_DEPTH = 10

        /**
         * Ordered: terminal text fields first, then the containers those fields
         * hide behind, so the first match down any branch is the most specific.
         */
        private val DEFAULT_KEYS = listOf(
            "text", "displayText", "markdown", "delta", "content", "message", "body",
            "chunk", "answer", "data", "payload", "choices", "parts", "items", "item",
            "messages", "result", "response", "value", "arguments",
        )

        private val ASSISTANT_ROLES = setOf("assistant", "bot", "model", "ai", "copilot")

        private val LABEL_KEYS = listOf("type", "messageType", "event", "eventType", "name", "kind")

        private val SKIP_MARKERS = listOf(
            "citation", "suggest", "usage", "telemetry", "throttl", "typing", "progress",
            "internalsearch", "internalloader", "rendercard", "heartbeat", "ping",
            "metadata", "reference", "attribution", "followup", "disengaged",
        )
    }
}
