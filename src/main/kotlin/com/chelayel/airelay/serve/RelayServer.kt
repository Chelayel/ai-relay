package com.chelayel.airelay.serve

import com.chelayel.airelay.config.Config
import com.chelayel.airelay.gemini.api.ConnectionMode
import com.chelayel.airelay.gemini.api.GeminiClient
import com.chelayel.airelay.gemini.api.GeminiClient.GeminiException
import com.chelayel.airelay.gemini.api.GeminiConfig
import com.chelayel.airelay.gemini.api.ModelTurn
import com.chelayel.airelay.serve.OpenAiTranslator.str
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors

/** One Gemini turn: the seam the tests replace with a scripted model. */
fun interface ModelBackend {
    fun turn(config: GeminiConfig, request: GeminiRequest, onText: (String) -> Unit): ModelTurn
}

class GeminiBackend : ModelBackend {
    override fun turn(config: GeminiConfig, request: GeminiRequest, onText: (String) -> Unit): ModelTurn =
        GeminiClient(config).streamTurn(request.contents, request.system, request.tools, request.extras, onText)
}

/**
 * `airelay serve`: the configured Gemini connection as a local model server,
 * for clients that bring their own model — GitHub Copilot in VS Code (Custom
 * Endpoint, or this extension's model provider), Copilot CLI, Copilot for
 * JetBrains through its Ollama option. They run the agent; this forwards each
 * model call, holds the credentials (the Apigee secret never leaves here) and
 * prices the turn.
 *
 * Loopback only, and three gates in front of every model call: the Host header
 * must name this machine (a web page cannot reach it by rebinding a hostname
 * to 127.0.0.1), a browser's request (one carrying `Origin`) is refused, and
 * the token must match unless the server was started without one.
 *
 * The config is read per request, so a model added in the IDE settings is
 * offered without a restart.
 */
class RelayServer(
    private val port: Int,
    private val token: String?,
    private val modeOverride: ConnectionMode? = null,
    private val loadConfig: () -> Config = Config::load,
    private val backend: ModelBackend = GeminiBackend(),
    private val log: (String) -> Unit = ::println,
) {
    private val signatures = SignatureCache()
    private val servers = ArrayList<HttpServer>()
    @Volatile private var liveModels: Pair<Long, List<String>>? = null

    /**
     * Starts listening on 127.0.0.1 and, on the same port, ::1; returns the port
     * (useful when [port] is 0). Both, because the address handed out is
     * `localhost`, and many clients (Node, so VS Code) try its IPv6 form first:
     * on 127.0.0.1 alone they were refused. A machine without IPv6 gets the one.
     */
    fun start(): Int {
        val first = listen(InetAddress.getByName("127.0.0.1"), port)
        runCatching { listen(InetAddress.getByName("::1"), first) }
        return first
    }

    private fun listen(address: InetAddress, port: Int): Int {
        val s = HttpServer.create(InetSocketAddress(address, port), 0)
        s.createContext("/") { ex -> handle(ex) }
        s.executor = Executors.newVirtualThreadPerTaskExecutor()
        s.start()
        servers.add(s)
        return s.address.port
    }

    fun stop() { servers.forEach { it.stop(0) }; servers.clear() }

    fun geminiConfig(model: String? = null) = GeminiConfig(loadConfig(), modeOverride, model)

    // ---- routing -------------------------------------------------------------

    private fun handle(ex: HttpExchange) {
        try {
            if (!hostAllowed(ex.requestHeaders.getFirst("Host"))) return send(ex, 403, OpenAiTranslator.error("Requests must be addressed to localhost.", "forbidden"))
            if (ex.requestHeaders.getFirst("Origin") != null) return send(ex, 403, OpenAiTranslator.error("Requests from a web page are refused.", "forbidden"))
            val path = ex.requestURI.path.trimEnd('/').ifEmpty { "/" }
            val method = ex.requestMethod.uppercase()
            if (path == "/") return sendText(ex, 200, "AI Relay is running")
            if (token != null && !authorized(ex)) {
                return send(ex, 401, OpenAiTranslator.error("Missing or wrong token. Use the token `airelay serve` printed as the API key.", "authentication_error", "invalid_api_key"))
            }
            when {
                method == "GET" && path in setOf("/v1/models", "/models") -> send(ex, 200, modelList())
                method == "GET" && path.startsWith("/v1/models/") -> modelOne(ex, path.removePrefix("/v1/models/"))
                // Any path ending in /chat/completions: clients asking for a "deployment URL"
                // differ on whether they append that themselves (…/v1/chat/completions/chat/completions),
                // and Azure-style ones post to /openai/deployments/<model>/chat/completions.
                method == "POST" && path.endsWith("/chat/completions") -> chat(ex, withModelFromPath(readJson(ex) ?: return badJson(ex), path), ollama = false)
                method == "GET" && path == "/api/version" -> send(ex, 200, JsonObject().apply { addProperty("version", OllamaApi.VERSION) })
                method == "GET" && path == "/api/tags" -> send(ex, 200, OllamaApi.tags(models()))
                method == "POST" && path == "/api/show" -> {
                    val body = readJson(ex) ?: return badJson(ex)
                    val model = body.str("model") ?: body.str("name") ?: return send(ex, 400, OpenAiTranslator.error("\"model\" is required.", "invalid_request_error"))
                    send(ex, 200, OllamaApi.show(model))
                }
                method == "POST" && path == "/api/chat" -> chat(ex, OllamaApi.toOpenAi(readJson(ex) ?: return badJson(ex)), ollama = true)
                else -> send(ex, 404, OpenAiTranslator.error("No such endpoint: $method $path", "not_found"))
            }
        } catch (_: ClientGone) {
            // The client went away; nothing to tell it.
        } catch (_: IOException) {
        } catch (e: Exception) {
            runCatching { send(ex, 500, OpenAiTranslator.error(e.message ?: e.javaClass.simpleName, "server_error")) }
        } finally {
            ex.close()
        }
    }

    /** An Azure-style request names its model in the path and may leave it out of the body. */
    private fun withModelFromPath(body: JsonObject, path: String): JsonObject {
        if (!body.str("model").isNullOrBlank()) return body
        val model = Regex("/deployments/([^/]+)/").find(path)?.groupValues?.get(1) ?: return body
        return body.apply { addProperty("model", java.net.URLDecoder.decode(model, StandardCharsets.UTF_8)) }
    }

    private fun hostAllowed(host: String?): Boolean {
        if (host == null) return true
        val name = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
        return name.lowercase() in setOf("127.0.0.1", "localhost", "[::1]")
    }

    private fun authorized(ex: HttpExchange): Boolean {
        val h = ex.requestHeaders
        val given = h.getFirst("Authorization")?.removePrefix("Bearer ")?.trim() ?: h.getFirst("x-api-key") ?: h.getFirst("api-key") ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token!!.toByteArray())
    }

    // ---- models --------------------------------------------------------------

    /**
     * What a client's picker offers: the same list as AI Relay's own picker. In
     * Gemini API mode the key can also be asked what it can call, and that live
     * list is added (cached for ten minutes).
     */
    fun models(): List<String> {
        val cfg = geminiConfig()
        val configured = cfg.pickerModels()
        if (cfg.connectionMode != ConnectionMode.GEMINI_API) return configured
        val now = System.currentTimeMillis()
        val live = liveModels?.takeIf { now - it.first < 10 * 60_000 }?.second
            ?: runCatching { GeminiClient(cfg).listModels() }.getOrDefault(emptyList()).also { liveModels = now to it }
        return (configured + live).distinct()
    }

    private fun modelJson(id: String): JsonObject {
        val limits = ModelInfo.limits(id)
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "model")
            addProperty("created", 0)
            addProperty("owned_by", "airelay")
            addProperty("display_name", ModelInfo.displayName(id))
            addProperty("context_length", limits.inputTokens)
            addProperty("max_output_tokens", limits.outputTokens)
            add("capabilities", JsonObject().apply { addProperty("tool_calling", limits.tools); addProperty("image_input", limits.images) })
        }
    }

    private fun modelList(): JsonObject = JsonObject().apply {
        addProperty("object", "list")
        add("data", JsonArray().apply { models().forEach { add(modelJson(it)) } })
    }

    private fun modelOne(ex: HttpExchange, id: String) =
        if (id in models()) send(ex, 200, modelJson(id))
        else send(ex, 404, OpenAiTranslator.error("No such model: $id", "invalid_request_error", "model_not_found"))

    // ---- chat ----------------------------------------------------------------

    private fun chat(ex: HttpExchange, body: JsonObject, ollama: Boolean) {
        val request = try { OpenAiTranslator.toGemini(body, signatures) } catch (e: BadRequest) {
            return send(ex, 400, OpenAiTranslator.error(e.message ?: "Bad request.", "invalid_request_error"))
        }
        val cfg = geminiConfig(request.model)
        cfg.missingCredentials()?.let {
            return send(ex, 503, OpenAiTranslator.error("The Gemini connection is not set up: $it Run `airelay gemini setup`.", "server_error", "not_configured"))
        }
        if (!cfg.isPublished(cfg.model)) {
            return send(ex, 404, OpenAiTranslator.error(
                "The gateway does not publish \"${request.model}\". Available: ${cfg.models.joinToString(", ")}.", "invalid_request_error", "model_not_found",
            ))
        }

        val id = OpenAiTranslator.newId("chatcmpl-")
        val created = System.currentTimeMillis() / 1000
        val started = System.nanoTime()
        log("→ ${request.model} · ${body.getAsJsonArray("messages")?.size() ?: 0} messages · ${request.tools.size} tools")

        val stream = Stream(ex, if (ollama) "application/x-ndjson" else "text/event-stream")
        var opened = false
        // The opening chunk (role) goes with the first real one, so a failure before
        // any output still gets a real HTTP status.
        fun emit(obj: JsonObject) {
            if (ollama) return stream.line(obj.toString())
            if (!opened) { opened = true; stream.line("data: ${OpenAiTranslator.chunk(id, request.model, created, JsonObject().apply { addProperty("role", "assistant"); addProperty("content", "") })}\n") }
            stream.line("data: $obj\n")
        }

        val turn = try {
            runTurn(cfg, request, OpenAiTranslator.rawToolSchemas(body)) { text ->
                if (!request.stream) return@runTurn
                emit(if (ollama) OllamaApi.chunk(request.model, text) else OpenAiTranslator.chunk(id, request.model, created, JsonObject().apply { addProperty("content", text) }))
            }
        } catch (e: ClientGone) {
            throw e
        } catch (e: Exception) {
            // Anything else — an HTTP error, an unreachable gateway or token URL — is upstream's.
            val (status, type) = when {
                e is GeminiException && e.status == 429 -> 429 to "rate_limit_error"
                e is GeminiException && e.status == 400 -> 400 to "invalid_request_error"
                else -> 502 to "upstream_error"
            }
            val message = if (e is GeminiException) e.message ?: "Gemini request failed." else unreachable(e)
            log("✗ ${request.model} · ${message.lineSequence().first()}")
            val err = OpenAiTranslator.error(message, type)
            if (!stream.begun) return send(ex, status, err)
            if (ollama) stream.line(JsonObject().apply { addProperty("error", message) }.toString())
            else { stream.line("data: $err\n"); stream.line("data: [DONE]\n") }
            return
        }

        val calls = OpenAiTranslator.issueCalls(turn, signatures)
        val elapsed = System.nanoTime() - started
        logDone(cfg, request.model, turn, calls.size, elapsed)

        when {
            ollama && request.stream -> { stream.begin(); stream.line(OllamaApi.done(request.model, turn, calls, elapsed).toString()) }
            ollama -> send(ex, 200, OllamaApi.done(request.model, turn, calls, elapsed, text = turn.text))
            request.stream -> {
                if (calls.isNotEmpty()) emit(OpenAiTranslator.chunk(id, request.model, created, JsonObject().apply { add("tool_calls", OpenAiTranslator.toolCallsJson(calls, withIndex = true)) }))
                emit(OpenAiTranslator.chunk(id, request.model, created, JsonObject(), OpenAiTranslator.finishReason(turn, calls.isNotEmpty())))
                if (request.includeUsage) OpenAiTranslator.usageJson(turn.usage)?.let { emit(OpenAiTranslator.usageChunk(id, request.model, created, it)) }
                stream.line("data: [DONE]\n")
            }
            else -> send(ex, 200, OpenAiTranslator.completion(id, request.model, created, turn, calls))
        }
    }

    /**
     * One turn, with the two retries this proxy owes its clients. Gemini rejects
     * a tool schema it cannot read — the request is sent again with the tools as
     * full JSON Schema (`parametersJsonSchema`), which takes what the cleaned
     * subset could not express. And Gemini sometimes ends a turn with nothing at
     * all, which asked again usually answers (see GeminiAgent's empty turns).
     */
    private fun runTurn(cfg: GeminiConfig, request: GeminiRequest, rawSchemas: List<JsonObject?>, onText: (String) -> Unit): ModelTurn {
        var current = request
        var schemaRetried = false
        var empties = 0
        while (true) {
            var streamed = false
            val turn = try {
                backend.turn(cfg, current) { streamed = true; onText(it) }
            } catch (e: GeminiException) {
                val schemaProblem = e.status == 400 && Regex("function_declarations|parameters|schema", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())
                if (!schemaRetried && !streamed && current.tools.isNotEmpty() && schemaProblem) {
                    schemaRetried = true
                    log("  Gemini refused a tool schema; retrying with the tools as JSON Schema")
                    current = current.withJsonSchemaTools(rawSchemas)
                    continue
                }
                throw e
            }
            val empty = turn.text.isEmpty() && turn.functionCalls.isEmpty() && (turn.finishReason == null || turn.finishReason.equals("STOP", true))
            if (empty && !streamed && empties < EMPTY_RETRIES) { empties++; continue }
            return turn
        }
    }

    /** A network failure in words: Java's HTTP client often leaves the message empty and puts it on a cause. */
    private fun unreachable(e: Throwable): String {
        val detail = generateSequence(e) { it.cause }.mapNotNull { it.message?.takeIf(String::isNotBlank) }.firstOrNull()
        return "Could not reach Gemini (${e.javaClass.simpleName}${detail?.let { ": $it" } ?: ""}). " +
            "Check the endpoint and token URL with `airelay gemini setup`."
    }

    private fun logDone(cfg: GeminiConfig, model: String, turn: ModelTurn, calls: Int, elapsedNanos: Long) {
        val u = turn.usage
        val cost = u?.let { cfg.priceFor(cfg.model)?.cost(it.promptTokens, it.candidateTokens + it.thoughtTokens) }
        val parts = listOfNotNull(
            "%.1fs".format(elapsedNanos / 1e9),
            u?.let { "%,d tokens".format(it.totalTokens) },
            cost?.let { "$%.4f".format(it) },
            if (calls > 0) "$calls tool call${if (calls == 1) "" else "s"}" else turn.finishReason?.lowercase(),
        )
        log("← $model · ${parts.joinToString(" · ")}")
    }

    // ---- io ------------------------------------------------------------------

    private fun readJson(ex: HttpExchange): JsonObject? {
        val bytes = ex.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) return null
        return runCatching { JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)).asJsonObject }.getOrNull()
    }

    private fun badJson(ex: HttpExchange) = send(ex, 400, OpenAiTranslator.error("The body must be a JSON object (at most ${MAX_BODY / 1_000_000} MB).", "invalid_request_error"))

    private fun send(ex: HttpExchange, status: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sendText(ex: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        ex.sendResponseHeaders(status, if (ex.requestMethod.equals("HEAD", true)) -1 else bytes.size.toLong())
        if (!ex.requestMethod.equals("HEAD", true)) ex.responseBody.use { it.write(bytes) }
    }

    /**
     * A streamed response whose headers go out with its first line, not before:
     * until then an upstream failure can still be answered with a real status
     * instead of an error buried in a 200 stream.
     */
    private class Stream(private val ex: HttpExchange, private val contentType: String) {
        var begun = false; private set
        private lateinit var out: OutputStream

        fun begin() {
            if (begun) return
            ex.responseHeaders.set("Content-Type", contentType)
            ex.responseHeaders.set("Cache-Control", "no-cache")
            ex.sendResponseHeaders(200, 0)
            out = ex.responseBody
            begun = true
        }

        fun line(text: String) {
            try {
                begin()
                out.write((text + "\n").toByteArray(StandardCharsets.UTF_8))
                out.flush()
            } catch (e: IOException) {
                throw ClientGone(e)
            }
        }
    }

    /** A write to the client failed: it hung up. Told apart from upstream's own I/O failures. */
    private class ClientGone(cause: IOException) : RuntimeException(cause)

    private companion object {
        const val MAX_BODY = 64_000_000
        const val EMPTY_RETRIES = 2
    }
}
