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

    /**
     * Report how each turn was actually read — which path the answer came from,
     * and what the response looked like.
     *
     * On by default while this backend is still being shaped against real
     * tenants: when a turn comes back wrong, the difference between "read off
     * the socket" and "read off the page" is the first thing worth knowing, and
     * nobody thinks to switch a flag on before the run that went wrong. Set
     * `copilot.debug=false` to silence it.
     */
    val debug: Boolean get() = config.getBool("copilot.debug", true)

    /**
     * `2` (default, negotiate) or `1.1`. Some Substrate endpoints reject HTTP/2
     * with "Received RST_STREAM: Use HTTP/1.1 for request"; that is detected and
     * retried automatically, and this pins it when you'd rather not pay for the
     * failed negotiation at all.
     */
    val httpVersion: String get() = config.get("copilot.http.version", "2").trim()

    /**
     * `replay` (default) re-sends a captured HTTP request. `browser` drives the
     * Copilot page in a real browser instead — the only thing that works where
     * chat streams over a WebSocket, as M365 Copilot's does.
     */
    val mode: String get() = config.get("copilot.mode", "replay").lowercase()

    val isBrowserMode: Boolean get() = mode == "browser"

    /** The Copilot page to drive, in browser mode. */
    val url: String get() = config.get("copilot.url", "https://m365.cloud.microsoft/chat")

    /** Attach to a browser already started with `--remote-debugging-port=PORT`. */
    val attachPort: Int? get() = config.get("copilot.attach.port")?.toIntOrNull()

    /**
     * `auto` (default), `true` or `false`. Headless means no window at all,
     * which is what you want once signed in — but signing in needs a window, so
     * `auto` shows one until a browser profile exists and hides it after that,
     * reopening it if the session turns out to have expired.
     */
    val headless: String get() = config.get("copilot.headless", "auto").lowercase()

    /** CSS for the message box, when the automatic guess picks the wrong one. */
    val inputSelector: String? get() = config.get("copilot.selector.input")?.takeIf { it.isNotBlank() }

    /** Silence, in ms, that marks the end of a streamed answer. */
    val quietMillis: Long get() = config.getInt("copilot.quiet.ms", 2_500).toLong().coerceIn(500, 60_000)

    /** How long one browser turn may take. */
    val turnTimeoutSeconds: Long get() = config.getInt("copilot.turn.timeout.seconds", 180).toLong().coerceIn(10, 3600)

    /**
     * The largest message to send. A chat composer enforces a length limit that
     * an API would not, so browser mode keeps it well below the HTTP default.
     */
    val maxMessageChars: Int
        get() = config.getInt("copilot.max.message.chars", if (isBrowserMode) 7_000 else 30_000)
            .coerceIn(1_000, 200_000)

    val commandTimeoutSeconds: Int get() = config.getInt("command.timeout.seconds", 300).coerceIn(10, 3600)

    val systemPrompt: String
        get() = config.get("copilot.system.prompt")?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT

    /** A short label for the banner: the host we replay against. */
    fun hostLabel(): String = runCatching {
        java.net.URI(if (isBrowserMode) url else endpoint).host
    }.getOrNull().orEmpty().ifBlank { if (isBrowserMode) url else "not configured" }

    /** Null when the saved capture is complete enough to send; otherwise what's missing. */
    fun missingCredentials(): String? = when {
        // Browser mode needs no capture at all: the browser holds the session.
        isBrowserMode -> null
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
            "copilot.history", "copilot.text.keys", "copilot.debug", "copilot.http.version",
            "copilot.system.prompt", "copilot.mode", "copilot.url", "copilot.attach.port",
            "copilot.selector.input", "copilot.quiet.ms", "copilot.turn.timeout.seconds", "copilot.headless",
            "copilot.max.message.chars",
        )

        /** Serialise headers for storage, so `writeAll` can put them in one property. */
        fun headersToJson(headers: Map<String, String>): String =
            JsonObject().apply { headers.forEach { (k, v) -> addProperty(k, v) } }.toString()

        val DEFAULT_SYSTEM_PROMPT = """
            You are AI Relay (Copilot), a coding agent working directly in the user's project from the
            command line. You are not a chat assistant here: you have the project on disk and tools to
            act on it, and the user sees only what you actually do.

            The project's directory and files are listed below. Work like this:

            1. Look first. listFiles, searchFiles and readFile are how you learn the code. Never guess at
               a file's contents, and never ask the user to paste or upload code — you can open it.
            2. Change code with editFile, replacing the exact lines you mean to change. Use writeFile only
               to create a new file or to rewrite one completely.
            3. Verify. Run the project's build or tests with runCommand and fix what fails.
            4. Keep going until the task is finished. Do not stop to ask permission, do not offer a plan
               and wait, and do not print code for the user to copy — apply it.

            Reply with prose only when the work is done or you are genuinely blocked, and then keep it
            short: say what you changed and what you verified. Never claim a change you did not make
            through a tool.
        """.trimIndent()
    }
}
