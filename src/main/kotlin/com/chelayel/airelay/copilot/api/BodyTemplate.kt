package com.chelayel.airelay.copilot.api

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Turns the body of one captured Copilot request into a template we can re-send
 * with a different prompt, and optionally a different model.
 *
 * The captured body is whatever the web app happened to post — a nested chat
 * envelope, a flat `{"message": "..."}`, or something form-encoded. Rather than
 * assume a shape, setup locates the *user's own typed text* inside the captured
 * body and remembers where it sat ([promptPath]); every later turn writes the
 * new prompt into that same slot. The model picker works the same way: whatever
 * field held the model id keeps holding it, so `--model` writes there.
 *
 * Paths are slash-separated, with array indices as numbers — e.g.
 * `messages/0/content` for `{"messages":[{"content":"hi"}]}`. When the body
 * isn't JSON at all, the template degrades to literal placeholder substitution.
 */
class BodyTemplate(
    /** The captured body, with placeholders already substituted in raw mode. */
    val raw: String,
    val isJson: Boolean,
    val promptPath: String?,
    val modelPath: String?,
    val conversationPath: String?,
) {

    /** Render the body for one turn. Null values leave the captured value in place. */
    fun render(prompt: String, model: String?, conversationId: String?): String {
        if (!isJson) return renderRaw(prompt)

        val root = JsonParser.parseString(raw)
        promptPath?.let { Json.set(root, it, prompt) }
        model?.takeIf { it.isNotBlank() }?.let { m -> modelPath?.let { Json.set(root, it, m) } }
        conversationId?.takeIf { it.isNotBlank() }?.let { c -> conversationPath?.let { Json.set(root, it, c) } }
        return root.toString()
    }

    /** The model id sitting in the captured body, if we found a field for it. */
    fun capturedModel(): String? =
        if (!isJson || modelPath == null) null
        else Json.getString(JsonParser.parseString(raw), modelPath)

    /** The conversation id sitting in the captured body, if any. */
    fun capturedConversationId(): String? =
        if (!isJson || conversationPath == null) null
        else Json.getString(JsonParser.parseString(raw), conversationPath)

    private fun renderRaw(prompt: String): String {
        val escaped = when {
            raw.trimStart().startsWith("{") || raw.trimStart().startsWith("[") -> jsonEscape(prompt)
            raw.contains('=') -> URLEncoder.encode(prompt, StandardCharsets.UTF_8)
            else -> prompt
        }
        return raw.replace(PROMPT_PLACEHOLDER, escaped)
    }

    companion object {
        /** Marks the prompt slot in a body we could not parse as JSON. */
        const val PROMPT_PLACEHOLDER = "{{AIRELAY_PROMPT}}"

        /** Field names that plausibly carry the model id in a chat request. */
        private val MODEL_KEYS = listOf(
            "modelId", "model_id", "model", "modelName", "model_name",
            "llm", "llmModel", "modelHint", "modelPreference", "gptId", "engine",
        )

        /** Field names that plausibly carry the conversation/thread id. */
        private val CONVERSATION_KEYS = listOf(
            "conversationId", "conversation_id", "threadId", "thread_id",
            "chatId", "chat_id", "sessionId", "session_id",
        )

        /**
         * Build a template from a captured [body] by locating [typedPrompt] —
         * the text the user actually typed into Copilot when capturing.
         * Returns null when the prompt can't be found, which means the capture
         * and the remembered prompt text don't match.
         */
        fun from(body: String, typedPrompt: String): BodyTemplate? {
            val parsed = runCatching { JsonParser.parseString(body) }.getOrNull()
            if (parsed != null && (parsed.isJsonObject || parsed.isJsonArray)) {
                val promptPath = Json.findStringPath(parsed, typedPrompt) ?: return null
                return BodyTemplate(
                    raw = body,
                    isJson = true,
                    promptPath = promptPath,
                    modelPath = Json.findKeyPath(parsed, MODEL_KEYS),
                    conversationPath = Json.findKeyPath(parsed, CONVERSATION_KEYS),
                )
            }
            // Not JSON: fall back to literal substitution of the typed text.
            val needle = if (body.contains(typedPrompt)) typedPrompt
            else URLEncoder.encode(typedPrompt, StandardCharsets.UTF_8).takeIf { body.contains(it) }
                ?: return null
            return BodyTemplate(
                raw = body.replace(needle, PROMPT_PLACEHOLDER),
                isJson = false,
                promptPath = null,
                modelPath = null,
                conversationPath = null,
            )
        }

        /** Rebuild a template from the values persisted in the config file. */
        fun restore(raw: String, isJson: Boolean, promptPath: String?, modelPath: String?, conversationPath: String?) =
            BodyTemplate(raw, isJson, promptPath, modelPath, conversationPath)

        private fun jsonEscape(s: String): String =
            JsonPrimitive(s).toString().let { it.substring(1, it.length - 1) }
    }
}

/** Slash-separated path access over a Gson tree, tolerant of every missing case. */
object Json {

    /** The string at [path], or null if the path is absent or not a string. */
    fun getString(root: JsonElement, path: String): String? =
        at(root, path)?.takeIf { it.isJsonPrimitive }?.asString

    /** Write [value] at [path]. Silently does nothing when the path no longer exists. */
    fun set(root: JsonElement, path: String, value: String) {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return
        val parent = at(root, segments.dropLast(1).joinToString("/")) ?: return
        val leaf = segments.last()
        when {
            parent.isJsonObject -> parent.asJsonObject.addProperty(leaf, value)
            parent.isJsonArray -> {
                val arr = parent.asJsonArray
                val idx = leaf.toIntOrNull() ?: return
                if (idx >= 0 && idx < arr.size()) arr.set(idx, JsonPrimitive(value))
            }
        }
    }

    /** Resolve [path] against [root]; an empty path is [root] itself. */
    fun at(root: JsonElement, path: String): JsonElement? {
        var cur: JsonElement = root
        for (seg in path.split('/').filter { it.isNotEmpty() }) {
            val node = cur
            cur = when {
                node.isJsonObject -> node.asJsonObject.get(seg) ?: return null
                node.isJsonArray -> {
                    val arr = node.asJsonArray
                    val idx = seg.toIntOrNull() ?: return null
                    if (idx < 0 || idx >= arr.size()) return null
                    arr.get(idx)
                }
                else -> return null
            }
        }
        return cur
    }

    /**
     * Breadth-first search for a string primitive equal to [needle] (falling back
     * to one that contains it, for apps that decorate the text). Breadth-first so
     * the shallowest — and so most likely intended — slot wins.
     */
    fun findStringPath(root: JsonElement, needle: String): String? {
        if (needle.isBlank()) return null
        for (pass in 0..1) {
            for ((path, el) in breadthFirst(root)) {
                if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) continue
                val s = el.asString
                val hit = if (pass == 0) s == needle else s.contains(needle)
                if (hit) return path
            }
        }
        return null
    }

    /**
     * Breadth-first search for the shallowest field named as one of [keys] whose
     * value is a string or null — the shape a model id has.
     */
    fun findKeyPath(root: JsonElement, keys: List<String>): String? {
        val wanted = keys.map { it.lowercase() }
        var bestRank = Int.MAX_VALUE
        var bestPath: String? = null
        for ((path, el) in breadthFirst(root)) {
            if (!el.isJsonObject) continue
            for ((name, value) in el.asJsonObject.entrySet()) {
                val rank = wanted.indexOf(name.lowercase())
                if (rank < 0) continue
                val usable = value is JsonNull || (value.isJsonPrimitive && value.asJsonPrimitive.isString)
                if (!usable) continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestPath = if (path.isEmpty()) name else "$path/$name"
                }
            }
        }
        return bestPath
    }

    /** Every (path, element) pair in the tree, shallowest first. */
    private fun breadthFirst(root: JsonElement): Sequence<Pair<String, JsonElement>> = sequence {
        val queue = ArrayDeque<Pair<String, JsonElement>>()
        queue.add("" to root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_NODES) {
            val (path, el) = queue.removeFirst()
            yield(path to el)
            when {
                el.isJsonObject -> for ((k, v) in el.asJsonObject.entrySet()) {
                    queue.add((if (path.isEmpty()) k else "$path/$k") to v)
                }
                el.isJsonArray -> el.asJsonArray.forEachIndexed { i, v ->
                    queue.add((if (path.isEmpty()) "$i" else "$path/$i") to v)
                }
            }
        }
    }

    /** Guard against a pathological body walking forever. */
    private const val MAX_NODES = 20_000
}
