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

        fun consumeChunk(chunk: JsonElement) {
            extractError(chunk)?.let { throw CopilotException("Copilot returned an error: $it") }
            findConversationId(chunk)?.let { conversationId = it }
            extractor.extract(chunk)?.let { assembler.offer(it) }
        }

        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            when {
                contentType.contains("event-stream") -> readSse(reader, sample, ::consumeChunk)

                // Declared as a JSON stream: parse per line as it arrives.
                JSON_STREAM_TYPES.any { contentType.contains(it) } ->
                    readJsonLines(reader, sample, ::consumeChunk)

                else -> {
                    val whole = readAll(reader, sample)
                    val single = asJson(whole)
                    val lines = jsonLines(whole)
                    when {
                        single != null -> consumeChunk(single)
                        // An undeclared JSON stream: every line has to be JSON.
                        // One JSON-looking line inside prose does not count —
                        // that is an answer containing a code block, and treating
                        // it as a stream would swallow the whole reply.
                        lines != null -> lines.forEach(::consumeChunk)
                        whole.isNotBlank() -> assembler.offer(whole)
                    }
                }
            }
        }

        return CopilotTurn(
            text = assembler.text(),
            conversationId = conversationId ?: priorConversationId,
            rawSample = sample.toString(),
        )
    }

    /** Read an SSE stream, handing each `data:` payload that parses to [onChunk]. */
    private fun readSse(reader: BufferedReader, sample: StringBuilder, onChunk: (JsonElement) -> Unit) {
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
            val raw = line!!
            record(sample, raw)
            when {
                raw.isBlank() -> flush()                       // end of one event
                raw.startsWith(":") -> Unit                    // comment / keep-alive
                raw.startsWith("event:") -> eventName = raw.removePrefix("event:").trim()
                raw.startsWith("data:") -> data.append(raw.removePrefix("data:").trim())
                else -> Unit                                   // id:, retry:, unknown field
            }
        }
        flush()
    }

    /** Read newline-delimited JSON, one chunk per line. */
    private fun readJsonLines(reader: BufferedReader, sample: StringBuilder, onChunk: (JsonElement) -> Unit) {
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (cancelled()) break
            val raw = line!!
            record(sample, raw)
            payloadOf(raw)?.let { asJson(it)?.let(onChunk) }
        }
    }

    private fun readAll(reader: BufferedReader, sample: StringBuilder): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        while (!cancelled()) {
            val n = reader.read(buf)
            if (n < 0) break
            sb.appendRange(buf, 0, n)
            if (sample.length < SAMPLE_LIMIT) {
                sample.append(buf, 0, minOf(n, SAMPLE_LIMIT - sample.length))
            }
        }
        return sb.toString()
    }

    private fun record(sample: StringBuilder, line: String) {
        if (sample.length < SAMPLE_LIMIT) sample.append(line).append('\n')
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

        private val JSON_STREAM_TYPES = listOf("ndjson", "json-seq", "jsonl", "json-lines")

        private val SKIP_EVENTS = setOf("done", "ping", "heartbeat", "keepalive", "keep-alive")

        private val CONVERSATION_KEYS = listOf(
            "conversationId", "conversation_id", "threadId", "thread_id", "chatId", "chat_id",
        )
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
 */
internal class TextExtractor(extraKeys: List<String>) {

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
                if (isNotAssistantProse(obj)) null
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
