package com.chelayel.airelay.gemini.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Issues a single streaming `streamGenerateContent` call against whichever
 * backend the [config] selects, parses the Server-Sent-Events stream, emits text
 * deltas as they arrive, and returns the fully assembled [ModelTurn].
 *
 * Ported from Gemini Relay's GeminiClient; the only changes are dropping the IDE
 * logger and running the cancel-time socket close on a plain daemon thread.
 */
class GeminiClient(private val config: GeminiConfig) {

    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        val conn = connection ?: return
        // disconnect() closes the socket; for a TLS connection to a slow gateway
        // that close is a blocking network write, so never do it inline.
        Thread { runCatching { conn.disconnect() } }.apply { isDaemon = true; start() }
    }

    /**
     * Runs one model turn. [onText] receives incremental assistant text;
     * the returned [ModelTurn] carries the full text plus any function calls.
     * A 401 transparently refreshes the token once and retries.
     */
    fun streamTurn(
        contents: List<Content>,
        systemPrompt: String?,
        tools: List<FunctionDecl>,
        onText: (String) -> Unit,
    ): ModelTurn {
        return try {
            request(contents, systemPrompt, tools, onText)
        } catch (e: UnauthorizedException) {
            AuthProvider.invalidate()
            try {
                request(contents, systemPrompt, tools, onText)
            } catch (e2: UnauthorizedException) {
                throw GeminiException(e2.detail)
            }
        }
    }

    private class UnauthorizedException(val detail: String) : RuntimeException(detail)

    private fun request(
        contents: List<Content>,
        systemPrompt: String?,
        tools: List<FunctionDecl>,
        onText: (String) -> Unit,
    ): ModelTurn {
        val sanitizedContents = sanitizeContents(contents)
        if (sanitizedContents.isEmpty()) {
            throw GeminiException("Cannot send an empty prompt. Add text or attach context and try again.")
        }

        val auth = AuthProvider.resolve(config)
        val url = URI(endpoint(auth)).toURL()
        val body = buildRequest(sanitizedContents, systemPrompt, tools).toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 5 * 60_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (auth is Auth.Bearer) setRequestProperty("Authorization", "Bearer ${auth.token}")
        }
        connection = conn

        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        val status = conn.responseCode
        if (status == 401 || status == 403) {
            val err = conn.errorStream?.let { readAll(it) }.orEmpty()
            throw UnauthorizedException(humanizeError(status, err))
        }
        if (status / 100 != 2) {
            val err = conn.errorStream?.let { readAll(it) }.orEmpty()
            throw GeminiException(humanizeError(status, err))
        }

        return BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
            parseSse(reader, onText)
        }
    }

    /**
     * The models this API key can call `generateContent` on, newest generation
     * first and without the families this CLI can't drive (embeddings, TTS,
     * Live, image and video generation). Gemini API mode only — Vertex has no
     * equivalent key-scoped list, so callers fall back to the shortlist.
     */
    fun listModels(): List<String> {
        if (config.connectionMode != ConnectionMode.GEMINI_API) return emptyList()
        val key = config.geminiApiKey
        if (key.isBlank()) return emptyList()

        val url = URI("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&key=${enc(key)}").toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        if (conn.responseCode / 100 != 2) {
            throw GeminiException(humanizeError(conn.responseCode, conn.errorStream?.let { readAll(it) }.orEmpty()))
        }

        val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val models = JsonParser.parseString(body).asJsonObject.getAsJsonArray("models") ?: return emptyList()
        return models.mapNotNull { el ->
            val obj = el.asJsonObject
            val methods = obj.getAsJsonArray("supportedGenerationMethods")?.mapNotNull { it.asString } ?: emptyList()
            if ("generateContent" !in methods) return@mapNotNull null
            obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix("models/")
        }.filter { isChatModel(it) }
            .distinct()
            .sortedWith(compareByDescending<String> { familyVersion(it) }.thenBy { it })
    }

    /** Families a coding agent has no use for, plus the dated snapshots
     *  (`-001`, `-preview-05-20`) that only clutter the list next to the alias. */
    private fun isChatModel(id: String): Boolean =
        NON_CHAT_MARKERS.none { it in id } && !DATED_SNAPSHOT.containsMatchIn(id)

    /** The generation as a number — 3.7 from `gemini-3.7-flash`, 3 from
     *  `gemini-3-flash` — so newer families sort first whatever order the API
     *  returned. Unrecognised ids sort last, not first. */
    private fun familyVersion(id: String): Double =
        FAMILY.find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0

    // ---- SSE parsing ---------------------------------------------------------

    private fun parseSse(reader: BufferedReader, onText: (String) -> Unit): ModelTurn {
        val texts = StringBuilder()
        val calls = mutableListOf<Part.FunctionCall>()
        var finishReason: String? = null
        var usage: Usage? = null

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (cancelled) break
            val raw = line!!.trim()
            if (!raw.startsWith("data:")) continue
            val payload = raw.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue

            val chunk = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue

            chunk.getAsJsonObject("usageMetadata")?.let { usage = parseUsage(it) }

            val candidate = chunk.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject ?: continue
            candidate.get("finishReason")?.takeIf { it.isJsonPrimitive }?.let { finishReason = it.asString }

            val parts = candidate.getAsJsonObject("content")?.getAsJsonArray("parts") ?: continue
            for (el in parts) {
                val part = el.asJsonObject
                part.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let { t ->
                    if (t.isNotEmpty()) { texts.append(t); onText(t) }
                }
                part.getAsJsonObject("functionCall")?.let { fc ->
                    val name = fc.get("name")?.asString ?: return@let
                    val args = fc.getAsJsonObject("args") ?: JsonObject()
                    // The thought signature is a sibling of `functionCall` on the
                    // part; it must be echoed back verbatim next turn (see below).
                    val signature = part.get("thoughtSignature")?.takeIf { it.isJsonPrimitive }?.asString
                    calls.add(Part.FunctionCall(name, args, signature))
                }
            }
        }

        val assembled = buildList {
            if (texts.isNotEmpty()) add(Part.Text(texts.toString()))
            addAll(calls)
        }
        return ModelTurn(assembled, finishReason, usage)
    }

    private fun parseUsage(obj: JsonObject): Usage = Usage(
        promptTokens = obj.longOr("promptTokenCount"),
        candidateTokens = obj.longOr("candidatesTokenCount"),
        totalTokens = obj.longOr("totalTokenCount"),
    )

    // ---- request body --------------------------------------------------------

    private fun buildRequest(
        contents: List<Content>,
        systemPrompt: String?,
        tools: List<FunctionDecl>,
    ): JsonObject {
        val root = JsonObject()

        val contentArr = JsonArray()
        contents.forEach { contentArr.add(contentJson(it)) }
        root.add("contents", contentArr)

        if (!systemPrompt.isNullOrBlank()) {
            val sys = JsonObject()
            val partArr = JsonArray()
            partArr.add(JsonObject().apply { addProperty("text", systemPrompt) })
            sys.add("parts", partArr)
            root.add("systemInstruction", sys)
        }

        if (tools.isNotEmpty()) {
            val decls = JsonArray()
            tools.forEach { decls.add(functionDeclJson(it)) }
            val toolObj = JsonObject().apply { add("functionDeclarations", decls) }
            root.add("tools", JsonArray().apply { add(toolObj) })
        }

        return root
    }

    /** Drop empty/invalid parts so every content sent to Gemini has at least one usable part. */
    private fun sanitizeContents(contents: List<Content>): List<Content> = contents.mapNotNull { content ->
        val parts = content.parts.filter(::isValidPart)
        if (parts.isEmpty()) null else Content(content.role, parts)
    }

    private fun isValidPart(part: Part): Boolean = when (part) {
        is Part.Text -> part.text.isNotBlank()
        is Part.InlineData -> part.mimeType.isNotBlank() && part.dataBase64.isNotBlank()
        is Part.FunctionCall -> part.name.isNotBlank()
        is Part.FunctionResponse -> part.name.isNotBlank()
    }

    private fun contentJson(content: Content): JsonObject {
        val obj = JsonObject().apply { addProperty("role", content.role) }
        val parts = JsonArray()
        for (part in content.parts) {
            parts.add(
                when (part) {
                    is Part.Text -> JsonObject().apply { addProperty("text", part.text) }
                    is Part.InlineData -> JsonObject().apply {
                        add("inlineData", JsonObject().apply {
                            addProperty("mimeType", part.mimeType)
                            addProperty("data", part.dataBase64)
                        })
                    }
                    is Part.FunctionCall -> JsonObject().apply {
                        add("functionCall", JsonObject().apply {
                            addProperty("name", part.name)
                            add("args", part.args)
                        })
                        // Echo the signature verbatim, as a sibling of functionCall,
                        // or Gemini 2.5+ rejects the follow-up request (HTTP 400).
                        part.thoughtSignature?.let { addProperty("thoughtSignature", it) }
                    }
                    is Part.FunctionResponse -> JsonObject().apply {
                        add("functionResponse", JsonObject().apply {
                            addProperty("name", part.name)
                            add("response", part.response)
                        })
                    }
                },
            )
        }
        obj.add("parts", parts)
        return obj
    }

    private fun functionDeclJson(decl: FunctionDecl): JsonObject = JsonObject().apply {
        addProperty("name", decl.name)
        addProperty("description", decl.description)
        add("parameters", decl.parameters)
    }

    // ---- endpoint resolution -------------------------------------------------

    /** Builds the full streaming URL for the active mode (SSE enabled). */
    private fun endpoint(auth: Auth): String {
        val model = config.model
        return when (config.connectionMode) {
            ConnectionMode.GEMINI_API -> {
                val key = (auth as Auth.QueryKey).key
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "${enc(model)}:streamGenerateContent?alt=sse&key=${enc(key)}"
            }

            ConnectionMode.VERTEX -> {
                val host = "${config.vertexLocation}-aiplatform.googleapis.com"
                vertexUrl(host, model)
            }

            ConnectionMode.VERTEX_APIGEE -> {
                val host = config.vertexApiEndpoint.removePrefix("https://").removePrefix("http://").trimEnd('/')
                vertexUrl(host, model)
            }
        }
    }

    private fun vertexUrl(host: String, model: String): String {
        val project = config.vertexProjectId
        val location = config.vertexLocation
        return "https://$host/v1/projects/${enc(project)}/locations/${enc(location)}" +
            "/publishers/google/models/${enc(model)}:streamGenerateContent?alt=sse"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

    private fun humanizeError(status: Int, body: String): String {
        val detail = runCatching {
            JsonParser.parseString(body).asJsonObject
                .getAsJsonObject("error")?.get("message")?.asString
        }.getOrNull()
        val base = "Gemini request failed (HTTP $status)" + (detail?.let { ": $it" } ?: ". ${body.take(300)}")
        // Google retires models, and a config file written months ago then fails
        // every turn with a 404. Say which model, and where to find a live one.
        val modelGone = status == 404 ||
            detail?.contains("no longer available", ignoreCase = true) == true ||
            detail?.contains("not found", ignoreCase = true) == true
        return if (modelGone)
            "$base\n\nThe model \"${config.model}\" looks unavailable or retired. " +
                "List what this connection can call with `airelay gemini models` " +
                "(${GeminiConfig.DEFAULT_MODEL} is a safe default), then pass it with -m."
        else base
    }

    private fun readAll(stream: java.io.InputStream): String =
        stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private fun JsonObject.longOr(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    private fun JsonArray.firstOrNull() = if (size() > 0) get(0) else null

    class GeminiException(message: String) : RuntimeException(message)

    private companion object {
        /** `generateContent` is offered by more than chat models — embeddings,
         *  speech, Live and the image/video generators all answer to it, and none
         *  of them can run an agent loop. */
        val NON_CHAT_MARKERS = listOf(
            "embedding", "aqa", "imagen", "veo", "-tts", "-live", "-image", "learnlm", "gemma",
        )

        /** A dated or numbered snapshot — `-001`, `-preview-05-20`, `-latest`. */
        val DATED_SNAPSHOT = Regex("-(\\d{3}|latest|\\d{2}-\\d{2})$")

        /** The generation in a Gemini id: `gemini-3.7-flash` → 3.7, `gemini-3-flash` → 3. */
        val FAMILY = Regex("gemini-(\\d+(?:\\.\\d+)?)")
    }
}
