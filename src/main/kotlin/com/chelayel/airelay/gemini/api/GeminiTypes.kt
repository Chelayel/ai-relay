package com.chelayel.airelay.gemini.api

import com.google.gson.JsonObject

/**
 * The minimal slice of the Gemini `generateContent` schema this tool needs.
 * Both the public Generative Language API and Vertex AI accept the same
 * `contents` / `tools` shape, so one model serves all three connection modes.
 * Ported verbatim from Gemini Relay — the wire format is identical.
 */

/** One turn in the conversation. `role` is "user" or "model". */
class Content(val role: String, val parts: List<Part>)

/** [Content] as the API spells it, and back. Used on the wire and for the session store. */
object ContentJson {
    fun toJson(content: Content): com.google.gson.JsonObject {
        val obj = com.google.gson.JsonObject().apply { addProperty("role", content.role) }
        val parts = com.google.gson.JsonArray()
        for (part in content.parts) parts.add(
            when (part) {
                is Part.Text -> com.google.gson.JsonObject().apply { addProperty("text", part.text) }
                is Part.InlineData -> com.google.gson.JsonObject().apply {
                    add("inlineData", com.google.gson.JsonObject().apply { addProperty("mimeType", part.mimeType); addProperty("data", part.dataBase64) })
                }
                is Part.FunctionCall -> com.google.gson.JsonObject().apply {
                    add("functionCall", com.google.gson.JsonObject().apply { addProperty("name", part.name); add("args", part.args) })
                    part.thoughtSignature?.let { addProperty("thoughtSignature", it) }
                }
                is Part.FunctionResponse -> com.google.gson.JsonObject().apply {
                    add("functionResponse", com.google.gson.JsonObject().apply { addProperty("name", part.name); add("response", part.response) })
                }
            },
        )
        obj.add("parts", parts)
        return obj
    }

    fun fromJson(o: com.google.gson.JsonObject): Content? {
        val role = o.get("role")?.asString ?: return null
        val parts = o.getAsJsonArray("parts")?.mapNotNull { el ->
            val p = el.asJsonObject
            when {
                p.has("text") -> Part.Text(p.get("text").asString)
                p.has("inlineData") -> p.getAsJsonObject("inlineData").let { Part.InlineData(it.get("mimeType").asString, it.get("data").asString) }
                p.has("functionCall") -> p.getAsJsonObject("functionCall").let { Part.FunctionCall(it.get("name").asString, it.getAsJsonObject("args") ?: com.google.gson.JsonObject(), p.get("thoughtSignature")?.asString) }
                p.has("functionResponse") -> p.getAsJsonObject("functionResponse").let { Part.FunctionResponse(it.get("name").asString, it.getAsJsonObject("response") ?: com.google.gson.JsonObject()) }
                else -> null
            }
        } ?: return null
        return Content(role, parts)
    }
}

/** A piece of a [Content]. Exactly one of the fields is meaningful per subtype. */
sealed interface Part {
    /** Plain text, from the user or the model. */
    data class Text(val text: String) : Part

    /** Inline binary content (e.g. an image) — Gemini is natively multimodal. */
    data class InlineData(val mimeType: String, val dataBase64: String) : Part

    /**
     * A model request to invoke a tool. [thoughtSignature] is an opaque token
     * Gemini 2.5+ attaches to the call; it must be echoed back verbatim in the
     * next request or the API rejects the turn (HTTP 400). Null for models /
     * backends that don't emit one.
     */
    data class FunctionCall(
        val name: String,
        val args: JsonObject,
        val thoughtSignature: String? = null,
    ) : Part

    /** Our reply to a [FunctionCall], fed back into the next turn. */
    data class FunctionResponse(val name: String, val response: JsonObject) : Part
}

/**
 * A tool the model may call. [parameters] is an OpenAPI-subset JSON schema
 * object exactly as Gemini expects under `functionDeclarations[].parameters`.
 */
class FunctionDecl(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** Token accounting reported by the API in the final stream chunk. */
data class Usage(
    val promptTokens: Long,
    val candidateTokens: Long,
    val totalTokens: Long,
)

/** The assembled result of one streamed model turn. */
class ModelTurn(
    val parts: List<Part>,
    val finishReason: String?,
    val usage: Usage?,
) {
    val text: String get() = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
    val functionCalls: List<Part.FunctionCall> get() = parts.filterIsInstance<Part.FunctionCall>()
}
