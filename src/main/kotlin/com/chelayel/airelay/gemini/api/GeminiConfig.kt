package com.chelayel.airelay.gemini.api

import com.chelayel.airelay.config.Config

/**
 * The three ways to reach a Gemini model — the same trio Gemini Relay offered:
 * the public Generative Language API, a standard Vertex AI project, and a Vertex
 * deployment fronted by a custom Apigee OAuth gateway (the enterprise pattern).
 */
enum class ConnectionMode(val id: String, val label: String) {
    GEMINI_API("gemini-api", "Gemini API (API key)"),
    VERTEX("vertex", "Vertex AI (gcloud token)"),
    VERTEX_APIGEE("apigee", "Vertex via Apigee (OAuth gateway)");

    companion object {
        fun from(id: String?): ConnectionMode =
            entries.firstOrNull { it.id.equals(id, true) || it.name.equals(id, true) } ?: GEMINI_API
    }
}

/**
 * Typed, read-only view over [Config] for the Gemini backend. Mirrors the fields
 * `GeminiSettings` exposed in the plugin, but sourced from env vars / the config
 * file instead of the IDE's persistent store. A CLI flag can override the mode
 * and model at construction.
 */
class GeminiConfig(
    private val config: Config,
    modeOverride: ConnectionMode? = null,
    modelOverride: String? = null,
) {
    val connectionMode: ConnectionMode =
        modeOverride ?: ConnectionMode.from(config.get("gemini.mode"))

    val model: String =
        (modelOverride ?: config.get("gemini.model"))?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    // Gemini API mode.
    val geminiApiKey: String get() = config.get("gemini.api.key").orEmpty()

    // Vertex / Apigee shared project coordinates.
    val vertexProjectId: String get() = config.get("vertex.project").orEmpty()
    val vertexLocation: String get() = config.get("vertex.location", "us-central1")

    // Custom host for the Apigee gateway, e.g. "my-gw.example.com".
    val vertexApiEndpoint: String get() = config.get("vertex.endpoint").orEmpty()

    // Apigee OAuth2 client-credentials token endpoint + client id/secret.
    val apigeeTokenUrl: String get() = config.get("apigee.token.url").orEmpty()
    val apigeeClientId: String get() = config.get("apigee.client.id").orEmpty()
    val apigeeClientSecret: String get() = config.get("apigee.client.secret").orEmpty()

    // For standard Vertex: how to obtain an access token. Blank → `gcloud`.
    val gcloudPath: String get() = config.get("gcloud.path").orEmpty()

    val commandTimeoutSeconds: Int get() = config.getInt("command.timeout.seconds", 300).coerceIn(10, 3600)

    val systemPrompt: String get() = config.get("gemini.system.prompt")?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT

    /** True when the active mode has the minimum credentials to attempt a call. */
    fun missingCredentials(): String? = when (connectionMode) {
        ConnectionMode.GEMINI_API ->
            if (geminiApiKey.isBlank()) "Set AIRELAY_GEMINI_API_KEY (or gemini.api.key)." else null
        ConnectionMode.VERTEX ->
            if (vertexProjectId.isBlank()) "Set AIRELAY_VERTEX_PROJECT (or vertex.project)." else null
        ConnectionMode.VERTEX_APIGEE -> when {
            vertexProjectId.isBlank() -> "Set AIRELAY_VERTEX_PROJECT."
            vertexApiEndpoint.isBlank() -> "Set AIRELAY_VERTEX_ENDPOINT (Apigee host)."
            apigeeTokenUrl.isBlank() -> "Set AIRELAY_APIGEE_TOKEN_URL."
            apigeeClientId.isBlank() -> "Set AIRELAY_APIGEE_CLIENT_ID."
            apigeeClientSecret.isBlank() -> "Set AIRELAY_APIGEE_CLIENT_SECRET."
            else -> null
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-pro"

        val DEFAULT_SYSTEM_PROMPT = """
            You are AI Relay (Gemini), an agentic coding assistant working inside the user's project from the command line.
            You have tools to read, write, and search files and to run shell commands within the allowed directories.
            When given a task:
            1. Use searchFiles and readFile to understand the relevant code before changing anything.
            2. Make focused edits with writeFile; never claim a change you did not apply through a tool.
            3. Use runCommand to build, test, and verify your work, and fix failures before finishing.
            Be concise in your replies and autonomous in your work.
        """.trimIndent()
    }
}
