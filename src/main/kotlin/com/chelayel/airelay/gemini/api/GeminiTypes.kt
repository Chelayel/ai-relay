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
