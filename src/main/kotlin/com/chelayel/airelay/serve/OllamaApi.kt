package com.chelayel.airelay.serve

import com.chelayel.airelay.gemini.api.ModelTurn
import com.chelayel.airelay.serve.OpenAiTranslator.objects
import com.chelayel.airelay.serve.OpenAiTranslator.str
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant

/**
 * Ollama's API, for the clients whose only outside-model option is "Ollama" —
 * GitHub Copilot for JetBrains among them. Its chat request is rewritten into
 * the OpenAI shape and goes through [OpenAiTranslator] like any other, so
 * there is one translation to Gemini, not two.
 *
 * Ollama clients send no API key, so these endpoints are reachable only when
 * the server runs without a token (`airelay serve --no-token`).
 */
object OllamaApi {

    /** The Ollama release whose API this mirrors; some clients refuse an older one. */
    const val VERSION = "0.12.6"

    fun tags(models: List<String>): JsonObject = JsonObject().apply {
        add("models", JsonArray().apply {
            models.forEach { id ->
                add(JsonObject().apply {
                    addProperty("name", id)
                    addProperty("model", id)
                    addProperty("modified_at", Instant.EPOCH.toString())
                    addProperty("size", 0)
                    addProperty("digest", "")
                    add("details", details())
                })
            }
        })
    }

    /** `/api/show`: what the model can do, which is how a client decides to offer tools and images. */
    fun show(model: String): JsonObject {
        val limits = ModelInfo.limits(model)
        return JsonObject().apply {
            addProperty("modelfile", "")
            addProperty("parameters", "")
            addProperty("template", "")
            add("details", details())
            add("model_info", JsonObject().apply {
                addProperty("general.architecture", "gemini")
                addProperty("general.basename", ModelInfo.displayName(model))
                addProperty("gemini.context_length", limits.inputTokens)
            })
            add("capabilities", JsonArray().apply {
                add("completion")
                if (limits.tools) add("tools")
                if (limits.images) add("vision")
            })
        }
    }

    private fun details() = JsonObject().apply {
        addProperty("format", "api")
        addProperty("family", "gemini")
        addProperty("parameter_size", "")
        addProperty("quantization_level", "")
    }

    /**
     * `/api/chat` → an OpenAI chat request. Ollama's tool calls have no ids and
     * its tool results name the tool instead, so ids are made up here and each
     * result is matched to the earliest unanswered call of that name.
     */
    fun toOpenAi(body: JsonObject): JsonObject {
        val messages = JsonArray()
        val pending = ArrayList<Pair<String, String>>()
        var n = 0
        for (m in body.get("messages").objects()) {
            val role = m.str("role") ?: continue
            val text = m.str("content").orEmpty()
            val out = JsonObject().apply { addProperty("role", role) }
            when (role) {
                "tool" -> {
                    val name = m.str("tool_name") ?: m.str("name")
                    val match = pending.firstOrNull { it.second == name } ?: pending.firstOrNull()
                    match?.let { pending.remove(it); out.addProperty("tool_call_id", it.first) }
                    name?.let { out.addProperty("name", it) }
                    out.addProperty("content", text)
                }
                "assistant" -> {
                    out.addProperty("content", text)
                    val calls = m.get("tool_calls").objects()
                    if (calls.isNotEmpty()) out.add("tool_calls", JsonArray().apply {
                        calls.forEach { c ->
                            val f = c.getAsJsonObject("function") ?: return@forEach
                            val name = f.str("name") ?: return@forEach
                            val id = c.str("id") ?: "call_ollama_${n++}"
                            pending.add(id to name)
                            val args = f.get("arguments")?.let { a -> if (a.isJsonPrimitive) a.asString else a.toString() } ?: "{}"
                            add(JsonObject().apply {
                                addProperty("id", id); addProperty("type", "function")
                                add("function", JsonObject().apply { addProperty("name", name); addProperty("arguments", args) })
                            })
                        }
                    })
                }
                else -> {
                    val images = m.getAsJsonArray("images")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
                    if (images.isEmpty()) out.addProperty("content", text)
                    else out.add("content", JsonArray().apply {
                        if (text.isNotBlank()) add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) })
                        images.forEach { b64 ->
                            add(JsonObject().apply {
                                addProperty("type", "image_url")
                                add("image_url", JsonObject().apply { addProperty("url", "data:${mimeOf(b64)};base64,$b64") })
                            })
                        }
                    })
                }
            }
            messages.add(out)
        }
        return JsonObject().apply {
            body.str("model")?.let { addProperty("model", it) }
            add("messages", messages)
            body.getAsJsonArray("tools")?.let { add("tools", it) }
            addProperty("stream", body.get("stream")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true)
            body.getAsJsonObject("options")?.let { o ->
                o.get("temperature")?.let { add("temperature", it) }
                o.get("top_p")?.let { add("top_p", it) }
                o.get("num_predict")?.takeIf { it.isJsonPrimitive && it.asInt > 0 }?.let { add("max_tokens", it) }
                o.get("seed")?.let { add("seed", it) }
                o.get("stop")?.let { add("stop", it) }
            }
            if (body.str("format") == "json") add("response_format", JsonObject().apply { addProperty("type", "json_object") })
        }
    }

    /** Ollama sends images as bare base64; the mime type is in the first bytes. */
    private fun mimeOf(b64: String): String = when {
        b64.startsWith("iVBOR") -> "image/png"
        b64.startsWith("/9j/") -> "image/jpeg"
        b64.startsWith("R0lG") -> "image/gif"
        b64.startsWith("UklG") -> "image/webp"
        else -> "image/png"
    }

    fun chunk(model: String, text: String): JsonObject = JsonObject().apply {
        addProperty("model", model)
        addProperty("created_at", Instant.now().toString())
        add("message", JsonObject().apply { addProperty("role", "assistant"); addProperty("content", text) })
        addProperty("done", false)
    }

    /** The last line of a stream, or the whole answer when [text] is given (not streamed). */
    fun done(model: String, turn: ModelTurn, calls: List<IssuedCall>, elapsedNanos: Long, text: String = ""): JsonObject = JsonObject().apply {
        addProperty("model", model)
        addProperty("created_at", Instant.now().toString())
        add("message", JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", text)
            if (calls.isNotEmpty()) add("tool_calls", JsonArray().apply {
                calls.forEach { c ->
                    add(JsonObject().apply {
                        addProperty("id", c.id)
                        add("function", JsonObject().apply { addProperty("name", c.name); add("arguments", c.args) })
                    })
                }
            })
        })
        addProperty("done", true)
        addProperty("done_reason", if (OpenAiTranslator.finishReason(turn, false) == "length") "length" else "stop")
        addProperty("total_duration", elapsedNanos)
        addProperty("load_duration", 0)
        addProperty("prompt_eval_count", turn.usage?.promptTokens ?: 0)
        addProperty("eval_count", (turn.usage?.candidateTokens ?: 0) + (turn.usage?.thoughtTokens ?: 0))
        addProperty("eval_duration", elapsedNanos)
    }
}
