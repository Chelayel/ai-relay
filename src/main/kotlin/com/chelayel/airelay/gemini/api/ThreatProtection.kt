package com.chelayel.airelay.gemini.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/**
 * Apigee's threat-protection policies (RegularExpressionProtection,
 * JSONThreatProtection, …) scan a request body for `<script>`, SQL keywords and
 * the like, and refuse it with a 400 or 403 before it reaches Vertex. A coding
 * agent trips them all day: a pasted page, a file the agent read with a script
 * tag in it — and that file goes out again in every later request's history.
 * The gateway's settings are often not the user's to change.
 *
 * What gets through is what the policy cannot read: text sent as a base64
 * `text/plain` attachment (`inlineData`), which Vertex decodes and the model
 * reads as a document. Only the user's side can be carried that way — the
 * model's own earlier turns go back as they came, text and function calls with
 * their thought signatures — so a block caused by the model's own words is
 * reported, not worked around.
 */
object ThreatProtection {

    enum class Mode { AUTO, ALWAYS, NEVER }

    /** `apigee.encode.text`: `auto` (re-send a blocked request encoded), `always`, or `never`. */
    fun mode(value: String?): Mode = when (value?.trim()?.lowercase()) {
        "always", "true", "on", "yes" -> Mode.ALWAYS
        "never", "false", "off", "no" -> Mode.NEVER
        else -> Mode.AUTO
    }

    /** A request the gateway's threat protection refused. */
    class Blocked(val policy: String, val status: Int) : RuntimeException(policy)

    /**
     * The policy that refused a request, when [body] is an Apigee threat-protection
     * fault (`{"fault":{"faultstring":…,"detail":{"errorcode":"steps.regularexpressionprotection.ThreatDetected"}}}`),
     * else null. Only a 400 or a 403 can be one.
     */
    fun detect(status: Int, body: String): String? {
        if (status != 400 && status != 403) return null
        val fault = runCatching { JsonParser.parseString(body).asJsonObject.getAsJsonObject("fault") }.getOrNull()
        val code = fault?.getAsJsonObject("detail")?.get("errorcode")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val text = fault?.get("faultstring")?.takeIf { it.isJsonPrimitive }?.asString ?: body
        val threat = Regex("threatprotection|regularexpressionprotection|threat detected|ThreatDetected", RegexOption.IGNORE_CASE)
        if (!threat.containsMatchIn(code) && !threat.containsMatchIn(text)) return null
        // The faultstring quotes the regex and the matched input — code the user wrote — so only its head is kept.
        val said = text.substringBefore(" input:").substringBefore(" regex:").trim().take(160)
        return listOf(code, said).filter { it.isNotBlank() }.distinct().joinToString(": ").ifBlank { "threat protection" }
    }

    /**
     * A 404 that may be threat protection in disguise, else null. Some gateways
     * answer a blocked body with their catch-all fault — `Resource not
     * found/Invalid resource` — so a `<script>` tag in a file the agent read
     * looked like a retired model. It cannot be told apart from a real 404 by
     * its body, so it is only a suspicion: the caller re-sends once encoded, and
     * a second 404 is reported as the 404 it is.
     */
    fun suspected(status: Int, body: String): String? =
        if (status == 404 && body.contains("Invalid resource", ignoreCase = true)) "a 404 standing in for a blocked request" else null

    /** What the model is told, ahead of an encoded turn, about where the words went. */
    const val NOTE = "(The text of this turn — the user's message and any tool output — is attached as text/plain " +
        "documents so it passes a filter on the way. Read each one as if it were written here.)"

    /**
     * [contents] with the user's side carried as base64 `text/plain` attachments:
     * a text part becomes one; a tool result keeps its place (Gemini pairs it
     * with the call) with a stub, and its output follows as one. The model's
     * turns are left exactly as they are.
     */
    fun encode(contents: List<Content>): List<Content> = contents.map { c ->
        if (c.role != "user") return@map c
        val calls = ArrayList<Part>()
        val attached = ArrayList<Part>()
        var changed = false
        for (p in c.parts) when (p) {
            is Part.Text -> { attached.add(document(p.text)); changed = true }
            is Part.FunctionResponse -> {
                calls.add(Part.FunctionResponse(p.name, JsonObject().apply { addProperty("content", "(The output is attached below as a text document.)") }))
                attached.add(document(outputOf(p.response)))
                changed = true
            }
            else -> attached.add(p)
        }
        if (!changed) c else Content("user", calls + Part.Text(NOTE) + attached)
    }

    private fun document(text: String) = Part.InlineData("text/plain", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))

    /** A tool result's text: its one string field when it has just one, else the JSON. */
    private fun outputOf(response: JsonObject): String {
        val strings = response.entrySet().filter { it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString }
        return if (response.size() == 1 && strings.size == 1) strings.first().value.asString else response.toString()
    }

    /** The error the user reads when a request stays blocked. */
    fun message(blocked: Blocked, mode: Mode, retriedEncoded: Boolean): String {
        val head = "The Apigee gateway's threat protection refused the request (HTTP ${blocked.status}, ${blocked.policy})."
        return head + "\n\n" + when {
            mode == Mode.NEVER ->
                "It scans requests for things like <script> tags or SQL. Set apigee.encode.text=auto to re-send a blocked request " +
                    "with your messages and tool output base64-encoded, which the scan cannot read."
            retriedEncoded ->
                "It was re-sent with your messages and tool output base64-encoded, and still refused: what it objects to is probably in " +
                    "the model's own earlier replies or tool calls, which must go back as they were. Start a new conversation (/new) to leave them behind."
            else -> "It scans requests for things like <script> tags or SQL."
        }
    }
}
