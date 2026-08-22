package com.chelayel.airelay.copilot.api

import java.io.InputStream
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
 * Sends one turn by replaying the captured request with a new prompt in it.
 *
 * This half is only transport: build the request from the capture, send it, and
 * turn a failure into a message the user can act on. Making sense of whatever
 * comes back is [ResponseReader]'s job.
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
        val (contentType, stream) = execute(prompt, model, conversationId)
            ?: return CopilotTurn("", conversationId, "")
        val reader = ResponseReader(config.extraTextKeys) { cancelled }
        return reader.read(stream, contentType, conversationId, onText)
    }

    /**
     * Send one turn but record the response instead of interpreting it, so
     * `airelay copilot diagnose` can say which field holds the text when the
     * built-in names don't match this tenant.
     */
    fun probe(prompt: String, model: String?, conversationId: String?): CopilotDiagnosis {
        val (contentType, stream) = execute(prompt, model, conversationId)
            ?: return CopilotDiagnosis("", emptyList())
        val reader = ResponseReader(config.extraTextKeys) { cancelled }
        return reader.survey(stream, contentType)
    }

    /** The content type of the last response a probe or send received. */
    @Volatile var lastContentType: String = ""
        private set

    /** Perform the request; null when the turn was cancelled mid-flight. */
    private fun execute(
        prompt: String,
        model: String?,
        conversationId: String?,
    ): Pair<String, InputStream>? {
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
            if (cancelled) return null
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
        lastContentType = contentType
        return contentType to response.body()
    }

    private fun readSome(input: InputStream, limit: Int): String = runCatching {
        val text = input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        text.trim().replace(Regex("\\s+"), " ").take(limit)
    }.getOrDefault("")

    companion object {
        private val HTTP: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            // Honour the JVM's proxy settings — these endpoints usually sit
            // behind a corporate proxy on the machines that can reach them.
            .proxy(ProxySelector.getDefault())
            .build()
    }
}
