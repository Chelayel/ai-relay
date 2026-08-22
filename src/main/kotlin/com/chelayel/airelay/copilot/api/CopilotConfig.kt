package com.chelayel.airelay.copilot.api

import com.chelayel.airelay.config.Config
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Typed, read-only view over [Config] for the Copilot backend.
 *
 * Where the Gemini backend has three well-known connection modes it can encode
 * up front, Copilot has exactly one: **replay the user's own browser session**.
 * Everything about the request — host, path, headers, body shape, where the
 * prompt goes, where the model id goes — is learned once by `airelay copilot
 * setup` from a request captured out of DevTools, and lives here.
 *
 * That means the stored headers include whatever bearer token or cookie the
 * browser was using. They are secrets, and the config file is written
 * owner-only for exactly that reason.
 */
class CopilotConfig(
    private val config: Config,
    modelOverride: String? = null,
) {

    val endpoint: String get() = config.get("copilot.endpoint").orEmpty()

    val method: String get() = config.get("copilot.method", "POST").uppercase()

    /** The selected model id — CLI flag, then saved default, then whatever was captured. */
    val model: String = (modelOverride ?: config.get("copilot.model"))?.takeIf { it.isNotBlank() }.orEmpty()

    /** Model ids offered by `/model` and `airelay copilot models`, as seen in the web picker. */
    val models: List<String>
        get() = config.get("copilot.models").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** True when setup found a field in the captured body that holds the model id. */
    val canChooseModel: Boolean get() = !config.get("copilot.model.path").isNullOrBlank()

    /** The replayed request headers, in capture order. */
    val headers: LinkedHashMap<String, String>
        get() {
            val out = LinkedHashMap<String, String>()
            val raw = config.get("copilot.headers") ?: return out
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return out
            for ((k, v) in obj.entrySet()) {
                if (v.isJsonPrimitive) out[k] = v.asString
            }
            return out
        }

    /** The captured body, as a re-renderable template. */
    val body: BodyTemplate
        get() = BodyTemplate.restore(
            raw = config.get("copilot.body").orEmpty(),
            isJson = config.getBool("copilot.body.json", true),
            promptPath = config.get("copilot.prompt.path"),
            modelPath = config.get("copilot.model.path"),
            conversationPath = config.get("copilot.conversation.path"),
        )

    /**
     * `server` (default) sends only the new message and lets the Copilot
     * conversation hold the history, exactly as the website does. `local`
     * re-sends a flattened transcript every turn, for an endpoint that turns
     * out to be stateless.
     */
    val historyMode: String get() = config.get("copilot.history", "server").lowercase()

    /** The conversation the captured request belonged to, if setup found one. */
    val conversationId: String? get() = config.get("copilot.conversation.id")?.takeIf { it.isNotBlank() }

    /**
     * Extra JSON keys to treat as assistant text when parsing the response.
     * The response shape is undocumented, so this is the escape hatch when a
     * tenant streams its text under a key the built-in list doesn't know.
     */
    val extraTextKeys: List<String>
        get() = config.get("copilot.text.keys").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Echo raw response chunks — the first thing to turn on when a reply comes back empty. */
    val debug: Boolean get() = config.getBool("copilot.debug", false)

    val commandTimeoutSeconds: Int get() = config.getInt("command.timeout.seconds", 300).coerceIn(10, 3600)

    val systemPrompt: String
        get() = config.get("copilot.system.prompt")?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT

    /** A short label for the banner: the host we replay against. */
    fun hostLabel(): String = runCatching {
        java.net.URI(endpoint).host ?: endpoint
    }.getOrDefault(endpoint).ifBlank { "not configured" }

    /** Null when the saved capture is complete enough to send; otherwise what's missing. */
    fun missingCredentials(): String? = when {
        endpoint.isBlank() -> "No captured request. Run `airelay copilot setup`."
        headers.isEmpty() -> "No captured headers — re-run `airelay copilot setup`."
        headers.keys.none { it.equals("authorization", true) || it.equals("cookie", true) } ->
            "The capture has no Authorization or Cookie header, so it isn't signed in. Re-capture with `airelay copilot login`."
        config.get("copilot.body").isNullOrBlank() -> "The captured request had no body — re-run `airelay copilot setup`."
        config.get("copilot.prompt.path").isNullOrBlank() && !config.get("copilot.body").orEmpty()
            .contains(BodyTemplate.PROMPT_PLACEHOLDER) ->
            "Setup never located the prompt field — re-run `airelay copilot setup`."
        else -> null
    }

    companion object {
        /** Every key this backend owns, for `reset`. */
        val ALL_KEYS = listOf(
            "copilot.endpoint", "copilot.method", "copilot.headers",
            "copilot.body", "copilot.body.json", "copilot.prompt.path",
            "copilot.model", "copilot.model.path", "copilot.models",
            "copilot.conversation.path", "copilot.conversation.id",
            "copilot.history", "copilot.text.keys", "copilot.debug",
            "copilot.system.prompt",
        )

        /** Serialise headers for storage, so `writeAll` can put them in one property. */
        fun headersToJson(headers: Map<String, String>): String =
            JsonObject().apply { headers.forEach { (k, v) -> addProperty(k, v) } }.toString()

        val DEFAULT_SYSTEM_PROMPT = """
            You are AI Relay (Copilot), an agentic coding assistant working inside the user's project from the command line.
            You have tools to read, write, and search files and to run shell commands within the allowed directories.
            When given a task:
            1. Use searchFiles and readFile to understand the relevant code before changing anything.
            2. Make focused edits with writeFile; never claim a change you did not apply through a tool.
            3. Use runCommand to build, test, and verify your work, and fix failures before finishing.
            Be concise in your replies and autonomous in your work.
        """.trimIndent()
    }
}
