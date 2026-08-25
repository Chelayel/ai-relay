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

    /** The configured model as the active mode spells it. A model Google has
     *  retired, or one named for the other surface, is corrected here — otherwise
     *  a config file written months ago fails every turn with a 404. */
    val model: String = canonicalModel(
        (modelOverride ?: config.get("gemini.model"))?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
        connectionMode,
    )

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
        /** Google's current workhorse for coding and agentic work, and the one id
         *  that is GA under the same name in every mode. The 2.5 family it replaces
         *  is two generations behind and a poor default for an agent loop. */
        const val DEFAULT_MODEL = "gemini-3.7-flash"

        /** Gemini API id → Vertex id, for the models the two name differently:
         *  a preview on one surface is often GA on the other. */
        private val VERTEX_IDS = mapOf(
            "gemini-3.1-pro-preview" to "gemini-3.1-pro",
            "gemini-3-flash-preview" to "gemini-3-flash",
        )

        private val GEMINI_API_IDS = VERTEX_IDS.entries.associate { (api, vertex) -> vertex to api }

        /** Suggested models for `airelay gemini setup`, best first. `-m` still takes
         *  any id — this is a shortlist, not a whitelist. */
        private val GEMINI_API_MODELS = listOf(
            "gemini-3.7-flash" to "current flagship for coding and agents",
            "gemini-3.1-pro-preview" to "the Pro line: deepest reasoning, slower",
            "gemini-3.6-flash" to "previous flagship, still strong",
            "gemini-3.5-flash" to "older 3.x baseline",
            "gemini-3.5-flash-lite" to "cheapest, for bulk or trivial work",
            "gemini-2.5-pro" to "legacy",
            "gemini-2.5-flash" to "legacy",
        )

        /** Suggested models for [mode]; the Apigee gateway publishes its own ids,
         *  so that mode is driven by `apigee.agents` instead. */
        fun modelChoices(mode: ConnectionMode): List<Pair<String, String>> =
            if (mode == ConnectionMode.GEMINI_API) GEMINI_API_MODELS
            else GEMINI_API_MODELS.map { (id, blurb) -> (VERTEX_IDS[id] ?: id) to blurb }

        /** Models Google has shut down; requests to them 404, so they're replaced
         *  with [DEFAULT_MODEL] on read. Extend as Google retires more. */
        private val RETIRED_MODELS = setOf(
            "gemini-3-pro-preview",
            "gemini-2.0-flash",
            "gemini-2.0-flash-001",
            "gemini-2.0-flash-lite",
            "gemini-2.0-flash-lite-001",
            "gemini-1.5-pro",
            "gemini-1.5-flash",
            "gemini-1.0-pro",
            "gemini-pro",
        )

        /** The id [model] goes by in [mode], with retired models replaced outright. */
        fun canonicalModel(model: String, mode: ConnectionMode): String {
            val trimmed = model.trim()
            if (trimmed.isEmpty() || trimmed in RETIRED_MODELS) return canonicalModel(DEFAULT_MODEL, mode)
            return when (mode) {
                ConnectionMode.GEMINI_API -> GEMINI_API_IDS[trimmed] ?: trimmed
                ConnectionMode.VERTEX -> VERTEX_IDS[trimmed] ?: trimmed
                ConnectionMode.VERTEX_APIGEE -> trimmed
            }
        }

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
