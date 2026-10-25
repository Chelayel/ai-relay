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

    /** On Apigee, the model ids the gateway publishes (`apigee.agents`); empty
     *  elsewhere. There is no endpoint to ask a gateway what it publishes, so
     *  setup, the IntelliJ settings page and the VS Code wizard all ask for them
     *  there and keep the chosen default in `gemini.model`. */
    private val gatewayModels: List<String> =
        if (connectionMode == ConnectionMode.VERTEX_APIGEE) splitIds(config.get("apigee.agents")).distinct() else emptyList()

    /** The configured model as the active mode spells it. A model Google has
     *  retired, or one named for the other surface, is corrected here — otherwise
     *  a config file written months ago fails every turn with a 404. On Apigee a
     *  saved `gemini.model` the gateway does not publish gives way to its first
     *  model: the IntelliJ settings page fills an empty model field with the
     *  public default, which a gateway may well not carry. */
    var model: String = canonicalModel(
        splitIds(modelOverride).firstOrNull()
            ?: splitIds(config.get("gemini.model")).firstOrNull()?.takeIf { gatewayModels.isEmpty() || it in gatewayModels }
            ?: gatewayModels.firstOrNull()
            ?: DEFAULT_MODEL,
        connectionMode,
    )

    /**
     * Every model the settings name — what an Apigee picker offers, and the
     * head of everyone else's. On Apigee with `apigee.agents` set, exactly
     * those ids and nothing else. Otherwise `gemini.model`, which may be a
     * comma-separated list: the first is the default and the rest are offered.
     */
    val models: List<String> = gatewayModels.ifEmpty {
        splitIds(modelOverride ?: config.get("gemini.model")).map { canonicalModel(it, connectionMode) }.distinct()
    }.ifEmpty { listOf(model) }

    /**
     * What a model picker offers — the agent's `/model` and the panels, and the
     * model list `airelay serve` publishes. An Apigee gateway publishes only what
     * it was told to, so that mode offers [models] and nothing from a shortlist
     * that may 404; elsewhere the configured ones come first, then the shortlist.
     */
    fun pickerModels(): List<String> =
        if (connectionMode == ConnectionMode.VERTEX_APIGEE) models
        else (listOf(model) + models + modelChoices(connectionMode).map { it.first }).distinct()

    /** False only on Apigee, for a model the gateway does not publish (`apigee.agents`). */
    fun isPublished(model: String): Boolean = gatewayModels.isEmpty() || model in gatewayModels

    /** USD per 1M tokens, prompt and output, for one model. */
    data class Price(val prompt: Double, val output: Double) {
        fun cost(promptTokens: Long, outputTokens: Long): Double = (promptTokens * prompt + outputTokens * output) / 1_000_000
    }

    /**
     * `gemini.prices`: what each model costs, as `id=prompt/output` per 1M
     * tokens, comma-separated — `gemini-3.8-flash=0.75/3.75`. Nothing on the
     * wire says what a turn cost, and an Apigee gateway bills at its own rates
     * with no endpoint to ask, so the rates are the user's to state. A model
     * with no entry shows tokens without a cost; a malformed entry is skipped.
     */
    val prices: Map<String, Price> = parsePrices(config.get("gemini.prices")).toMap()

    /** The rate for [model], also when the entry names it as the other surface does
     *  (`gemini-3.1-pro-preview` for Vertex's `gemini-3.1-pro`). Not through
     *  [canonicalModel]'s retired-model fallback, which would price the default. */
    fun priceFor(model: String): Price? =
        prices[model] ?: prices.entries.firstOrNull { (id, _) -> (VERTEX_IDS[id] ?: GEMINI_API_IDS[id]) == model }?.value

    // Gemini API mode.
    val geminiApiKey: String get() = config.get("gemini.api.key").orEmpty()

    // Vertex / Apigee shared project coordinates.
    val vertexProjectId: String get() = config.get("vertex.project").orEmpty()
    val vertexLocation: String get() = config.get("vertex.location", "us-central1")

    // Custom host for the Apigee gateway, e.g. "my-gw.example.com".
    val vertexApiEndpoint: String get() = config.get("vertex.endpoint").orEmpty()

    /** The Vertex API version in the path (`vertex.api.version`, default `v1`); some gateways serve `v1beta1`. */
    val vertexApiVersion: String get() = config.get("vertex.api.version")?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: "v1"

    /**
     * `vertex.base.url`: the whole URL up to `/models`, for a gateway that does
     * not mount Vertex at the standard path. May use `{project}`, `{location}`
     * and `{version}`. The same key opencode-apigee reads, so one setting serves both.
     */
    val vertexBaseUrl: String get() = config.get("vertex.base.url")?.trim().orEmpty()

    /**
     * Everything before `/models/<id>:streamGenerateContent` in the Vertex modes.
     * It was fixed at `https://<host>/v1/projects/…/publishers/google`, and gateways
     * differ in both the version they serve and where they mount Vertex.
     */
    fun vertexBase(): String {
        val project = enc(vertexProjectId)
        val location = enc(vertexLocation)
        if (vertexBaseUrl.isNotBlank()) {
            val filled = vertexBaseUrl.replace("{project}", project).replace("{location}", location).replace("{version}", vertexApiVersion)
            return (if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(filled)) filled else "https://$filled").trimEnd('/')
        }
        val host = if (connectionMode == ConnectionMode.VERTEX) "$vertexLocation-aiplatform.googleapis.com"
        else vertexApiEndpoint.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return "https://$host/$vertexApiVersion/projects/$project/locations/$location/publishers/google"
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    // Apigee OAuth2 client-credentials token endpoint + client id/secret.
    val apigeeTokenUrl: String get() = config.get("apigee.token.url").orEmpty()
    val apigeeClientId: String get() = config.get("apigee.client.id").orEmpty()
    val apigeeClientSecret: String get() = config.get("apigee.client.secret").orEmpty()

    // For standard Vertex: how to obtain an access token. Blank → `gcloud`.
    val gcloudPath: String get() = config.get("gcloud.path").orEmpty()

    val commandTimeoutSeconds: Int get() = config.getInt("command.timeout.seconds", 300).coerceIn(10, 3600)

    val systemPrompt: String get() = config.get("gemini.system.prompt")?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT

    /**
     * How many tool rounds one turn may take. The old fixed cap of 50 was set
     * for a question-shaped task; a whole-repo migration spends that many rounds
     * on one module and then stopped mid-way with the work half applied, which
     * reads as the model giving up. Raise it for long jobs, lower it to fail fast.
     */
    val maxToolRounds: Int get() = config.getInt("gemini.max.tool.rounds", 300).coerceIn(1, 5000)

    /**
     * How many conversation turns are kept before the history is trimmed.
     * Counted in `Content` entries, not tokens — the same unit the loop appends in.
     */
    val historyWindow: Int get() = config.getInt("gemini.history.window", 240).coerceIn(20, 5000)

    /** Tool results kept verbatim in the request; older ones go as one-line stubs. 0 stubs all but none. */
    val historyVerbatim: Int get() = config.getInt("gemini.history.verbatim", 12).coerceIn(0, 5000)

    /**
     * Thinking depth, both spellings, each sent only when set: Gemini 3.x takes
     * `thinkingLevel` ("low"/"high"), the 2.5 family takes a `thinkingBudget` in
     * tokens. Left unset by default because an Apigee gateway validates the
     * request body and rejects a field its schema doesn't know — an opt-in
     * cannot break a working setup.
     */
    val thinkingLevel: String? get() = config.get("gemini.thinking.level")?.takeIf { it.isNotBlank() }
    val thinkingBudget: Int? get() = config.get("gemini.thinking.budget")?.toIntOrNull()

    /** Whether the agent may reach the network beyond the model endpoint. */
    val webEnabled: Boolean get() = config.getBool("web.enabled", true)

    /** True when the active mode has the minimum credentials to attempt a call. */
    fun missingCredentials(): String? = when (connectionMode) {
        ConnectionMode.GEMINI_API ->
            if (geminiApiKey.isBlank()) "Set AIRELAY_GEMINI_API_KEY (or gemini.api.key)." else null
        ConnectionMode.VERTEX ->
            if (vertexProjectId.isBlank()) "Set AIRELAY_VERTEX_PROJECT (or vertex.project)." else null
        ConnectionMode.VERTEX_APIGEE -> when {
            // A full base URL needs the project only if it names it, and no separate host.
            vertexProjectId.isBlank() && (vertexBaseUrl.isBlank() || "{project}" in vertexBaseUrl) -> "Set AIRELAY_VERTEX_PROJECT."
            vertexApiEndpoint.isBlank() && vertexBaseUrl.isBlank() -> "Set AIRELAY_VERTEX_ENDPOINT (Apigee host), or AIRELAY_VERTEX_BASE_URL."
            apigeeTokenUrl.isBlank() -> "Set AIRELAY_APIGEE_TOKEN_URL."
            apigeeClientId.isBlank() -> "Set AIRELAY_APIGEE_CLIENT_ID."
            apigeeClientSecret.isBlank() -> "Set AIRELAY_APIGEE_CLIENT_SECRET."
            else -> null
        }
    }

    companion object {
        /** Google's current workhorse for coding and agentic work, GA under the
         *  same name on both surfaces. 3.7 Flash, the default before it, is
         *  deprecated (retiring on Vertex in January 2027). */
        const val DEFAULT_MODEL = "gemini-3.8-flash"

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
            "gemini-3.8-flash" to "current Flash: fast, strong at coding and agents",
            "gemini-4-argon" to "most capable, for hard coding and agentic work; slower, dearer",
            "gemini-3.1-pro-preview" to "the previous Pro line",
            "gemini-3.7-flash" to "previous Flash, deprecated",
            "gemini-3.5-flash-lite" to "cheapest, for bulk or trivial work",
        )

        /** Suggested models for [mode]; the Apigee gateway publishes its own ids,
         *  so that mode offers `apigee.agents` instead (see [models]). */
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

        internal fun parsePrices(value: String?): List<Pair<String, Price>> =
            splitIds(value?.replace(';', ',')).mapNotNull { entry ->
                val id = entry.substringBefore('=', "").trim()
                val rates = entry.substringAfter('=', "").split('/').map { it.trim().removePrefix("$").toDoubleOrNull() }
                if (id.isEmpty() || rates.size != 2 || rates.any { it == null || it < 0 }) null
                else id to Price(rates[0]!!, rates[1]!!)
            }

        private fun splitIds(value: String?): List<String> =
            value?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

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

        /**
         * The two paragraphs after the basics are there for a specific failure:
         * asked to move a project onto a framework release newer than its
         * training data, the model answered from memory and invented plausible
         * artifact ids and property names, then hand-edited file after file
         * until the tool-round cap ended the turn with the build broken. Both
         * halves of that are addressed here — check the world before asserting
         * a version-specific fact, and drive a large migration from a written
         * plan and the project's own tooling instead of from memory.
         */
        val DEFAULT_SYSTEM_PROMPT = """
            You are AI Relay (Gemini), an agentic coding assistant working inside the user's project from the command line.
            You have tools to read, write, and search files, to run shell commands within the allowed directories, and to
            search and read the web.
            When given a task:
            1. Explore before you answer or change anything. The file the user points at is a starting point, not the
               whole picture: use findFiles (by name) and searchFiles (by content) to locate what it relates to —
               callers, implementations, interfaces, tests, configuration — and readFile to read them. Do not answer
               a question about the code from one file when the answer depends on others.
            2. Make focused edits with editFile; never claim a change you did not apply through a tool.
            3. Use runCommand to build, test, and verify your work, and fix failures before finishing.

            Your training data has a cutoff and the ecosystem has moved since. Before you state or rely on anything
            version-specific — an artifact or module id, a configuration property, a class or method that may have been
            renamed, moved or removed, the current release of a library, what a major version changed — check it with a
            tool first. Use webSearch and then fetchUrl to read the project's own release notes or migration guide, and
            mavenSearch to confirm every dependency coordinate before you write it into a build file. Do this before you
            answer, not after the user corrects you, and say which page you took a fact from. If webSearch is not
            available, fetchUrl still is: go straight to the documentation URL you know.

            For a large mechanical change across many files (a framework or language-version upgrade, a package rename,
            an API sweep), do not start editing file by file. First find out whether the ecosystem already automates it —
            OpenRewrite recipes, a vendor migration tool, a codemod, an IDE inspection — and prefer running that over
            hand-editing, then fix what it leaves behind. Write the plan to a file in the repo, keep it updated as you go,
            and work in batches that each end with a build or test run, so progress survives even if the turn is cut short.

            Be concise in your replies and autonomous in your work.
        """.trimIndent()
    }
}
