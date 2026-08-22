package com.chelayel.airelay.copilot.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** The assembled result of one Copilot turn. */
class CopilotTurn(
    val text: String,
    /** A conversation id seen in the response, so the next turn can continue it. */
    val conversationId: String?,
    /** The first slice of the raw response — shown only when nothing could be parsed. */
    val rawSample: String,
)

/** The saved session no longer authenticates; the user must re-capture it. */
class SessionExpiredException(message: String) : RuntimeException(message)

/** Anything else that went wrong talking to Copilot. */
class CopilotException(message: String) : RuntimeException(message)

/**
 * Sends one turn by replaying the captured request with a new prompt in it, and
 * parses whatever comes back.
 *
 * The response format is undocumented and varies — some tenants stream
 * Server-Sent Events, some newline-delimited JSON, some answer with a single
 * JSON document — so this deliberately sniffs rather than assumes, and pulls
 * assistant text out of unknown JSON by walking it for the keys such payloads
 * conventionally use. When that finds nothing, [CopilotTurn.rawSample] carries
 * the head of the response so the user can point `copilot.text.keys` at the
 * right field instead of being told "empty response".
 */
class CopilotClient(private val config: CopilotConfig) {

    @Volatile private var stream: InputStream? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        val s = stream ?: return
        // Closing a TLS stream can block on a network write; never do it inline.
        Thread { runCatching { s.close() } }.apply { isDaemon = true; start() }
    }

    /**
     * Replay the captured request with [prompt] in the prompt slot. [onText]
     * receives assistant text as it arrives.
     */
    fun send(
        prompt: String,
        model: String?,
        conversationId: String?,
        onText: (String) -> Unit,
    ): CopilotTurn {
        val body = config.body.render(prompt, model, conversationId)

        val builder = HttpRequest.newBuilder(URI.create(config.endpoint))
            .timeout(Duration.ofMinutes(10))
            .method(config.method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        for ((name, value) in config.headers) {
            // The JDK forbids a handful of headers it manages itself; the captured
            // request will contain some of them, and skipping is the right answer.
            runCatching { builder.header(name, value) }
        }

        val response = runCatching {
            HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        }.getOrElse { e ->
            if (cancelled) return CopilotTurn("", conversationId, "")
            throw CopilotException("Could not reach ${config.hostLabel()}: ${e.message ?: e.toString()}")
        }

        val status = response.statusCode()
        stream = response.body()

        if (status == 401 || status == 403) {
            val detail = readSome(response.body(), 400)
            throw SessionExpiredException(
                "Copilot rejected the saved session (HTTP $status). Open Copilot in your browser, " +
                    "make sure you're still signed in, then re-capture with `airelay copilot login`." +
                    detail.takeIf { it.isNotBlank() }?.let { "\n  $it" }.orEmpty(),
            )
        }
        if (status / 100 != 2) {
            throw CopilotException("Copilot request failed (HTTP $status): ${readSome(response.body(), 400)}")
        }

        val contentType = response.headers().firstValue("content-type").orElse("").lowercase()
        return parse(response.body(), contentType, conversationId, onText)
    }

    // ---- response parsing ----------------------------------------------------

    private fun parse(
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
            if (contentType.contains("event-stream")) {
                readSse(reader, sample) { payload -> consumeChunk(payload) }
            } else {
                // Everything else: try the whole body as one document, and fall
                // back to treating it as one JSON value per line.
                val whole = readAll(reader, sample)
                val single = runCatching { JsonParser.parseString(whole) }.getOrNull()
                    ?.takeIf { it.isJsonObject || it.isJsonArray }
                if (single != null) {
                    consumeChunk(single)
                } else {
                    var parsedAny = false
                    for (line in whole.lineSequence()) {
                        if (cancelled) break
                        val trimmed = line.trim().removePrefix("data:").trim()
                        if (trimmed.isEmpty() || trimmed == "[DONE]") continue
                        val el = runCatching { JsonParser.parseString(trimmed) }.getOrNull() ?: continue
                        if (el.isJsonObject || el.isJsonArray) { parsedAny = true; consumeChunk(el) }
                    }
                    // Not JSON in any form — the body is the answer itself.
                    if (!parsedAny && whole.isNotBlank()) assembler.offer(whole)
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
            val el = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return
            if (el.isJsonObject || el.isJsonArray) onChunk(el)
        }

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (cancelled) break
            val raw = line!!
            if (sample.length < SAMPLE_LIMIT) sample.append(raw).append('\n')
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

    private fun readAll(reader: BufferedReader, sample: StringBuilder): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        while (!cancelled) {
            val n = reader.read(buf)
            if (n < 0) break
            sb.appendRange(buf, 0, n)
            if (sample.length < SAMPLE_LIMIT) {
                sample.append(buf, 0, minOf(n, SAMPLE_LIMIT - sample.length))
            }
        }
        return sb.toString()
    }

    private fun readSome(input: InputStream, limit: Int): String = runCatching {
        val text = input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        text.trim().replace(Regex("\\s+"), " ").take(limit)
    }.getOrDefault("")

    private val extractor by lazy { TextExtractor(config.extraTextKeys) }

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
        private val HTTP: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            // Honour the JVM's proxy settings — these endpoints usually sit
            // behind a corporate proxy on the machines that can reach them.
            .proxy(ProxySelector.getDefault())
            .build()

        private const val SAMPLE_LIMIT = 2_000

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
