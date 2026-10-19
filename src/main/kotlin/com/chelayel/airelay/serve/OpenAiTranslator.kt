package com.chelayel.airelay.serve

import com.chelayel.airelay.gemini.api.Content
import com.chelayel.airelay.gemini.api.FunctionDecl
import com.chelayel.airelay.gemini.api.ModelTurn
import com.chelayel.airelay.gemini.api.Part
import com.chelayel.airelay.gemini.api.Usage
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.security.SecureRandom

/** A client's request that cannot be translated; answered with HTTP 400 and this message. */
class BadRequest(message: String) : RuntimeException(message)

/** An OpenAI chat request in Gemini's terms. */
class GeminiRequest(
    val model: String,
    val contents: List<Content>,
    val system: String?,
    val tools: List<FunctionDecl>,
    /** `generationConfig` / `toolConfig`, merged into the body by `GeminiClient`. */
    val extras: JsonObject?,
    val stream: Boolean,
    val includeUsage: Boolean,
) {
    /** The same request with every tool declared as full JSON Schema — the retry when Gemini refuses a cleaned one. */
    fun withJsonSchemaTools(raw: List<JsonObject?>): GeminiRequest = GeminiRequest(
        model, contents, system,
        tools.mapIndexed { i, t -> FunctionDecl(t.name, t.description, JsonObject(), raw.getOrNull(i)?.let(OpenAiTranslator::stripMeta)) },
        extras, stream, includeUsage,
    )
}

/** A function call as it goes back to the client: the id we issued, the name, the arguments. */
class IssuedCall(val id: String, val name: String, val args: JsonObject)

/**
 * The OpenAI Chat Completions shape ↔ Gemini's `generateContent`, both ways.
 * Pure functions over JSON, so the whole translation is unit-tested without a
 * network; [RelayServer] only moves bytes.
 *
 * Copilot is the client this was written for, and it runs its own agent: the
 * tools in a request are Copilot's, executed by Copilot. Nothing of AI Relay's
 * agent (system prompt, tools, history trimming) is added — every request is
 * forwarded as the client wrote it, the whole history included.
 */
object OpenAiTranslator {

    // ---- request -------------------------------------------------------------

    fun toGemini(req: JsonObject, signatures: SignatureCache): GeminiRequest {
        val model = req.str("model")?.takeIf { it.isNotBlank() } ?: throw BadRequest("\"model\" is required.")
        val messages = req.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw BadRequest("\"messages\" must be an array.")

        // Gemini answers a tool result by function name, OpenAI by call id: learn the names first.
        val callNames = HashMap<String, String>()
        for (m in messages.objects()) for (c in m.get("tool_calls").objects()) {
            val id = c.str("id") ?: continue
            c.getAsJsonObject("function")?.str("name")?.let { callNames[id] = it }
        }

        val system = StringBuilder()
        val contents = ArrayList<Content>()
        fun add(role: String, parts: List<Part>) {
            if (parts.isEmpty()) return
            val last = contents.lastOrNull()
            // Consecutive turns of one role are one turn to Gemini; tool results after
            // a call are sent together, as the call's answer.
            if (last != null && last.role == role) contents[contents.lastIndex] = Content(role, last.parts + parts)
            else contents.add(Content(role, parts))
        }

        for (m in messages.objects()) {
            when (m.str("role")) {
                "system", "developer" -> textOf(m.get("content")).takeIf { it.isNotBlank() }?.let {
                    if (system.isNotEmpty()) system.append("\n\n"); system.append(it)
                }
                "user" -> add("user", userParts(m.get("content")))
                "assistant" -> add("model", assistantParts(m, model, signatures))
                "tool" -> {
                    val name = m.str("tool_call_id")?.let(callNames::get) ?: m.str("name") ?: "tool"
                    add("user", listOf(Part.FunctionResponse(name, responseOf(m.get("content")))))
                }
                "function" -> add("user", listOf(Part.FunctionResponse(m.str("name") ?: "tool", responseOf(m.get("content")))))
                else -> {}
            }
        }
        if (contents.isEmpty()) throw BadRequest("No user message to answer.")

        val tools = req.get("tools").objects().filter { (it.str("type") ?: "function") == "function" }.mapNotNull { t ->
            val f = t.getAsJsonObject("function") ?: return@mapNotNull null
            val name = f.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FunctionDecl(name, f.str("description").orEmpty(), cleanSchema(f.getAsJsonObject("parameters")) ?: JsonObject())
        }

        val generation = JsonObject()
        req.num("temperature")?.let { generation.add("temperature", it) }
        req.num("top_p")?.let { generation.add("topP", it) }
        (req.num("max_completion_tokens") ?: req.num("max_tokens"))?.let { generation.add("maxOutputTokens", it) }
        req.num("seed")?.let { generation.add("seed", it) }
        req.num("presence_penalty")?.let { generation.add("presencePenalty", it) }
        req.num("frequency_penalty")?.let { generation.add("frequencyPenalty", it) }
        req.get("stop")?.let { stop ->
            val list = when {
                stop.isJsonArray -> stop.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                stop.isJsonPrimitive -> listOf(stop.asString)
                else -> emptyList()
            }
            if (list.isNotEmpty()) generation.add("stopSequences", JsonArray().apply { list.forEach { add(it) } })
        }
        if (req.getAsJsonObject("response_format")?.str("type") == "json_object") generation.addProperty("responseMimeType", "application/json")

        val extras = JsonObject()
        if (generation.size() > 0) extras.add("generationConfig", generation)
        if (tools.isNotEmpty()) toolConfig(req.get("tool_choice"))?.let { extras.add("toolConfig", it) }

        return GeminiRequest(
            model = model,
            contents = contents,
            system = system.toString().ifBlank { null },
            tools = tools,
            extras = extras.takeIf { it.size() > 0 },
            stream = req.get("stream")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            includeUsage = req.getAsJsonObject("stream_options")?.get("include_usage")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
        )
    }

    /** The tools' schemas as the client sent them, for the JSON Schema retry. */
    fun rawToolSchemas(req: JsonObject): List<JsonObject?> =
        req.get("tools").objects().filter { (it.str("type") ?: "function") == "function" }
            .filter { it.getAsJsonObject("function")?.str("name")?.isNotBlank() == true }
            .map { it.getAsJsonObject("function")?.getAsJsonObject("parameters")?.takeIf { p -> hasProperties(p) } }

    private fun toolConfig(choice: JsonElement?): JsonObject? {
        val mode: String
        var only: String? = null
        when {
            choice == null || choice.isJsonNull -> return null
            choice.isJsonPrimitive -> mode = when (choice.asString) { "none" -> "NONE"; "required" -> "ANY"; else -> return null }
            choice.isJsonObject -> {
                only = choice.asJsonObject.getAsJsonObject("function")?.str("name") ?: return null
                mode = "ANY"
            }
            else -> return null
        }
        val cfg = JsonObject().apply { addProperty("mode", mode); only?.let { add("allowedFunctionNames", JsonArray().apply { add(it) }) } }
        return JsonObject().apply { add("functionCallingConfig", cfg) }
    }

    private fun userParts(content: JsonElement?): List<Part> {
        if (content == null || content.isJsonNull) return emptyList()
        if (content.isJsonPrimitive) return listOf(Part.Text(content.asString)).filter { it.text.isNotBlank() }
        return content.asJsonArray.objects().mapNotNull { p ->
            when (p.str("type")) {
                "text", "input_text" -> p.str("text")?.takeIf { it.isNotBlank() }?.let { Part.Text(it) }
                "image_url" -> {
                    val url = p.get("image_url")?.let { if (it.isJsonObject) it.asJsonObject.str("url") else it.asString }.orEmpty()
                    dataUrl(url) ?: Part.Text("[An image was attached by URL, which this connection cannot fetch: $url]")
                }
                else -> null
            }
        }
    }

    private fun dataUrl(url: String): Part.InlineData? {
        val m = Regex("^data:([^;,]+);base64,(.*)$", RegexOption.DOT_MATCHES_ALL).find(url) ?: return null
        return Part.InlineData(m.groupValues[1], m.groupValues[2])
    }

    private fun assistantParts(m: JsonObject, model: String, signatures: SignatureCache): List<Part> {
        val parts = ArrayList<Part>()
        textOf(m.get("content")).takeIf { it.isNotBlank() }?.let { parts.add(Part.Text(it)) }
        val calls = m.get("tool_calls").objects().mapNotNull { c ->
            val f = c.getAsJsonObject("function") ?: return@mapNotNull null
            val name = f.str("name") ?: return@mapNotNull null
            Triple(c.str("id"), name, argsOf(f.get("arguments")))
        }
        val known = calls.map { (id, name, args) -> signatures.get(id, name, args) }
        // Gemini signs the first call of a turn; mirror that when nothing was kept.
        val placeholder = known.all { it == null } && needsSignature(model)
        calls.forEachIndexed { i, (_, name, args) ->
            parts.add(Part.FunctionCall(name, args, known[i] ?: if (placeholder && i == 0) SignatureCache.PLACEHOLDER else null))
        }
        return parts
    }

    /** Gemini 3 and later validate signatures; an id we cannot place is treated as new. */
    private fun needsSignature(model: String): Boolean {
        val version = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(model)?.groupValues?.get(1)?.toDoubleOrNull() ?: return true
        return version >= 3
    }

    private fun argsOf(e: JsonElement?): JsonObject {
        if (e == null || e.isJsonNull) return JsonObject()
        if (e.isJsonObject) return e.asJsonObject
        val text = if (e.isJsonPrimitive) e.asString else e.toString()
        if (text.isBlank()) return JsonObject()
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return JsonObject().apply { addProperty("input", text) }
        return if (parsed.isJsonObject) parsed.asJsonObject else JsonObject().apply { add("value", parsed) }
    }

    private fun responseOf(content: JsonElement?): JsonObject = JsonObject().apply { addProperty("content", textOf(content)) }

    /** A message's text, whether it is a string or an array of parts. */
    private fun textOf(content: JsonElement?): String = when {
        content == null || content.isJsonNull -> ""
        content.isJsonPrimitive -> content.asString
        content.isJsonArray -> content.asJsonArray.objects().mapNotNull { it.str("text") }.joinToString("\n")
        else -> content.toString()
    }

    // ---- tool schemas --------------------------------------------------------

    private val KEPT = setOf(
        "type", "format", "description", "nullable", "enum", "properties", "required", "items", "anyOf",
        "minItems", "maxItems", "minimum", "maximum", "minLength", "maxLength", "pattern", "title", "propertyOrdering",
    )
    private val STRING_FORMATS = setOf("enum", "date-time")
    private val NUMBER_FORMATS = setOf("int32", "int64", "float", "double")

    /**
     * A client's JSON Schema cut down to the OpenAPI subset Gemini's `parameters`
     * accepts. Copilot's tools and MCP servers use what JSON Schema allows —
     * `$ref`, `additionalProperties`, `const`, `oneOf`, type arrays — and Gemini
     * rejects the whole request over one keyword it does not know. Returns null
     * for a schema with no arguments, which is declared without `parameters`.
     */
    fun cleanSchema(schema: JsonObject?): JsonObject? {
        if (schema == null || !hasProperties(schema)) return null
        val defs = schema.getAsJsonObject("\$defs") ?: schema.getAsJsonObject("definitions")
        return clean(schema, defs, 0)
    }

    private fun hasProperties(schema: JsonObject): Boolean =
        schema.getAsJsonObject("properties")?.size()?.let { it > 0 } ?: (schema.has("\$ref") || schema.has("anyOf") || schema.has("oneOf") || schema.has("allOf"))

    private fun clean(input: JsonObject, defs: JsonObject?, depth: Int): JsonObject {
        if (depth > 16) return JsonObject().apply { addProperty("type", "string") }
        var s = input
        s.str("\$ref")?.let { ref ->
            val target = defs?.getAsJsonObject(ref.substringAfterLast('/'))
            return if (target != null) clean(target, defs, depth + 1).also { r -> s.str("description")?.let { r.addProperty("description", it) } }
            else JsonObject().apply { addProperty("type", "string"); s.str("description")?.let { addProperty("description", it) } }
        }
        s.get("allOf")?.takeIf { it.isJsonArray }?.let { all -> s = mergeAll(s, all.asJsonArray.objects()) }

        val out = JsonObject()
        // A type array — ["string", "null"] — is one type that may be null.
        s.get("type")?.let { t ->
            if (t.isJsonArray) {
                val types = t.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                types.firstOrNull { it != "null" }?.let { out.addProperty("type", it) }
                if ("null" in types) out.addProperty("nullable", true)
            } else if (t.isJsonPrimitive) out.addProperty("type", t.asString)
        }
        (s.get("anyOf") ?: s.get("oneOf"))?.takeIf { it.isJsonArray }?.let { alts ->
            val options = alts.asJsonArray.objects()
            val real = options.filter { it.str("type") != "null" }
            if (real.size < options.size) out.addProperty("nullable", true)
            if (real.size == 1) {
                clean(real[0], defs, depth + 1).entrySet().forEach { (k, v) -> if (!out.has(k)) out.add(k, v) }
            } else if (real.isNotEmpty()) {
                out.add("anyOf", JsonArray().apply { real.forEach { add(clean(it, defs, depth + 1)) } })
            }
        }
        s.get("const")?.takeIf { it.isJsonPrimitive }?.let { c ->
            out.add("enum", JsonArray().apply { add(c.asString) }); if (!out.has("type")) out.addProperty("type", "string")
        }
        for ((k, v) in s.entrySet()) {
            if (k !in KEPT || out.has(k)) continue
            when (k) {
                "properties" -> if (v.isJsonObject) {
                    val props = JsonObject()
                    v.asJsonObject.entrySet().forEach { (name, p) -> if (p.isJsonObject) props.add(name, clean(p.asJsonObject, defs, depth + 1)) }
                    if (props.size() > 0) out.add("properties", props)
                }
                "items" -> if (v.isJsonObject) out.add("items", clean(v.asJsonObject, defs, depth + 1))
                "enum" -> if (v.isJsonArray && v.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) out.add("enum", v)
                "anyOf" -> {}
                else -> out.add(k, v)
            }
        }
        if (!out.has("type") && !out.has("anyOf")) out.addProperty("type", when {
            out.has("properties") -> "object"
            out.has("items") -> "array"
            else -> "string"
        })
        // Gemini's enum is for strings only; a format it does not know is a 400.
        if (out.has("enum")) out.addProperty("type", "string")
        out.str("format")?.let { f ->
            val ok = when (out.str("type")) { "string" -> f in STRING_FORMATS; "integer", "number" -> f in NUMBER_FORMATS; else -> false }
            if (!ok) out.remove("format")
        }
        if (out.str("type") == "array" && !out.has("items")) out.add("items", JsonObject().apply { addProperty("type", "string") })
        out.getAsJsonArray("required")?.let { req ->
            val names = out.getAsJsonObject("properties")?.keySet().orEmpty()
            val kept = req.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.filter { it in names }
            if (kept.isEmpty()) out.remove("required") else out.add("required", JsonArray().apply { kept.forEach { add(it) } })
        }
        return out
    }

    private fun mergeAll(base: JsonObject, parts: List<JsonObject>): JsonObject {
        val merged = base.deepCopy().apply { remove("allOf") }
        val props = merged.getAsJsonObject("properties")?.deepCopy() ?: JsonObject()
        val required = JsonArray().apply { merged.getAsJsonArray("required")?.forEach { add(it) } }
        for (p in parts) {
            p.getAsJsonObject("properties")?.entrySet()?.forEach { (k, v) -> props.add(k, v) }
            p.getAsJsonArray("required")?.forEach { if (!required.contains(it)) required.add(it) }
            p.entrySet().filter { it.key !in setOf("properties", "required") && !merged.has(it.key) }.forEach { merged.add(it.key, it.value) }
        }
        if (props.size() > 0) merged.add("properties", props)
        if (required.size() > 0) merged.add("required", required)
        return merged
    }

    /** A client's schema with the keywords no API reads, for `parametersJsonSchema`. */
    fun stripMeta(schema: JsonObject): JsonObject = schema.deepCopy().apply { remove("\$schema"); remove("\$id") }

    // ---- response ------------------------------------------------------------

    private val random = SecureRandom()
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun newId(prefix: String): String = prefix + (1..24).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** The turn's calls with ids, each signature kept for when the call comes back. */
    fun issueCalls(turn: ModelTurn, signatures: SignatureCache): List<IssuedCall> = turn.functionCalls.map { c ->
        val id = newId("call_")
        c.thoughtSignature?.let { signatures.put(id, c.name, c.args, it) }
        IssuedCall(id, c.name, c.args)
    }

    /** Gemini's finish reason as OpenAI names it. */
    fun finishReason(turn: ModelTurn, hasCalls: Boolean): String = when {
        hasCalls -> "tool_calls"
        turn.finishReason.equals("MAX_TOKENS", true) -> "length"
        turn.finishReason?.uppercase() in setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY") -> "content_filter"
        else -> "stop"
    }

    fun toolCallsJson(calls: List<IssuedCall>, withIndex: Boolean): JsonArray = JsonArray().apply {
        calls.forEachIndexed { i, c ->
            add(JsonObject().apply {
                if (withIndex) addProperty("index", i)
                addProperty("id", c.id)
                addProperty("type", "function")
                add("function", JsonObject().apply { addProperty("name", c.name); addProperty("arguments", c.args.toString()) })
            })
        }
    }

    fun usageJson(u: Usage?): JsonObject? = u?.let {
        JsonObject().apply {
            addProperty("prompt_tokens", it.promptTokens)
            addProperty("completion_tokens", it.candidateTokens + it.thoughtTokens)
            addProperty("total_tokens", it.totalTokens.takeIf { t -> t > 0 } ?: (it.promptTokens + it.candidateTokens + it.thoughtTokens))
            if (it.thoughtTokens > 0) add("completion_tokens_details", JsonObject().apply { addProperty("reasoning_tokens", it.thoughtTokens) })
        }
    }

    /** A whole (non-streamed) completion. */
    fun completion(id: String, model: String, created: Long, turn: ModelTurn, calls: List<IssuedCall>): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("object", "chat.completion")
        addProperty("created", created)
        addProperty("model", model)
        add("choices", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("index", 0)
                add("message", JsonObject().apply {
                    addProperty("role", "assistant")
                    if (turn.text.isEmpty()) add("content", JsonNull.INSTANCE) else addProperty("content", turn.text)
                    if (calls.isNotEmpty()) add("tool_calls", toolCallsJson(calls, withIndex = false))
                })
                addProperty("finish_reason", finishReason(turn, calls.isNotEmpty()))
            })
        })
        usageJson(turn.usage)?.let { add("usage", it) }
    }

    /** One streamed chunk carrying [delta]; [finish] on the last. */
    fun chunk(id: String, model: String, created: Long, delta: JsonObject, finish: String? = null): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("object", "chat.completion.chunk")
        addProperty("created", created)
        addProperty("model", model)
        add("choices", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("index", 0)
                add("delta", delta)
                if (finish == null) add("finish_reason", JsonNull.INSTANCE) else addProperty("finish_reason", finish)
            })
        })
    }

    fun usageChunk(id: String, model: String, created: Long, usage: JsonObject): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("object", "chat.completion.chunk")
        addProperty("created", created)
        addProperty("model", model)
        add("choices", JsonArray())
        add("usage", usage)
    }

    fun error(message: String, type: String, code: String? = null): JsonObject = JsonObject().apply {
        add("error", JsonObject().apply {
            addProperty("message", message); addProperty("type", type); code?.let { addProperty("code", it) }
        })
    }

    // ---- json helpers --------------------------------------------------------

    internal fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.num(key: String): JsonPrimitive? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asJsonPrimitive
    internal fun JsonElement?.objects(): List<JsonObject> =
        if (this != null && isJsonArray) asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject } else emptyList()
}
