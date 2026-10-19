package com.chelayel.airelay.serve

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Gemini's thought signatures, kept for the clients that will never send them back.
 *
 * Gemini 3 attaches an opaque signature to a function call and rejects the next
 * request (HTTP 400) unless that call comes back carrying it. The agent keeps
 * the signature in its own history; an OpenAI client keeps only `tool_calls`
 * — id, name, arguments — so a multi-step tool job died on its second request.
 * The server remembers each signature under the id it issued, and under the
 * call's name and arguments for clients that do not echo ids (Ollama's API has
 * none), and puts it back when the call returns in the history.
 *
 * Bounded: an evicted or never-seen signature falls back to [PLACEHOLDER].
 */
class SignatureCache(private val capacity: Int = 4096) {

    private val entries = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
    }

    @Synchronized
    fun put(callId: String, name: String, args: JsonObject, signature: String) {
        entries[ID + callId] = signature
        entries[callKey(name, args)] = signature
    }

    @Synchronized
    fun get(callId: String?, name: String, args: JsonObject): String? =
        callId?.let { entries[ID + it] } ?: entries[callKey(name, args)]

    // Keys sorted: a client that parses the arguments and re-serialises them may reorder them.
    private fun callKey(name: String, args: JsonObject) = "call:$name:${canonical(args)}"

    private fun canonical(e: JsonElement): String = when {
        e.isJsonObject -> e.asJsonObject.entrySet().sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${canonical(v)}" }
        e.isJsonArray -> e.asJsonArray.joinToString(",", "[", "]") { canonical(it) }
        else -> e.toString()
    }

    companion object {
        private const val ID = "id:"

        /**
         * Google's documented stand-in for a signature that was not kept (history
         * built elsewhere, or moved between models): it tells Gemini to skip
         * validating that call rather than refusing the request.
         */
        const val PLACEHOLDER = "skip_thought_signature_validator"
    }
}
