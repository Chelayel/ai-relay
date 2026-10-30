package com.chelayel.airelay.cli

/**
 * A conversational backend. Both relays implement this: Claude drives the
 * `claude` CLI subprocess, Gemini talks REST. [send] runs one user turn to
 * completion, streaming events to [sink]; conversation state lives in the agent.
 */
interface Agent {
    /**
     * One user turn. [attachments] are images the surface handed over (a paste
     * or a drop); a backend that cannot carry them says so on the sink and
     * answers the text alone rather than failing the turn.
     */
    fun send(prompt: String, sink: Sink, attachments: List<Attachment> = emptyList())
    /** The id this conversation can be resumed by, once known (Claude learns it from its CLI). */
    fun sessionId(): String? = null
    /** Continue an earlier conversation. False when the backend cannot; the transcript is then only replayed. */
    fun resume(id: String, state: com.google.gson.JsonElement?): Boolean = false
    /** The backend's own resumable state to keep beside the transcript, or null. */
    fun saveState(): com.google.gson.JsonElement? = null
    /** Adopt a persona (system prompt) from here on; null clears it. False when the backend cannot. */
    fun usePersona(persona: com.chelayel.airelay.agent.Persona?): Boolean = false
    fun currentPersona(): String? = null
    /** Models worth offering, the current one first. Empty when the backend has no choice to make here. */
    fun models(): List<String> = emptyList()
    /** [models] with what a picker shows beside each id. */
    fun modelInfo(): List<ModelInfo> = models().map { ModelInfo(it) }
    /**
     * Ask the backend which models it can call now — its CLI, its API — rather than
     * trusting a list compiled into this build, which goes stale the day a model
     * ships. Blocking (a subprocess or a request); null when there is nothing to ask.
     * Called off the turn thread, once per process.
     */
    fun discoverModels(): List<ModelInfo>? = null
    /** What the account has used and has left, when the backend can tell without a turn. Blocking; null when unknown. */
    fun quota(): Quota? = null
    fun currentModel(): String? = null
    /** Switch models for the rest of the conversation. False when the backend cannot. */
    fun useModel(name: String): Boolean = false
    /** How hard the current model thinks, as its agent names the levels; empty when it has no such setting. */
    fun efforts(): List<String> = emptyList()
    fun currentEffort(): String? = null
    /** Set the thinking effort for the rest of the conversation. False when the backend cannot. */
    fun useEffort(level: String): Boolean = false
    /**
     * The context-window tiers the agent offers (GitHub Copilot's `default` / `long_context`,
     * the 1M window of tiered-pricing models); empty when it has no such choice.
     */
    fun contexts(): List<String> = emptyList()
    /** The tier chosen here, or null for the agent's own setting. */
    fun currentContext(): String? = null
    /** Use [tier] from the next turn on, keeping the conversation. False when the backend cannot. */
    fun useContext(tier: String): Boolean = false
    /**
     * Summarize the conversation so far into one message and carry on from it, freeing the
     * window. Blocking (a model call). False when the backend keeps its own history (the
     * CLI agents compact themselves) or there is too little to compact.
     */
    fun compact(sink: Sink): Boolean = false
    /**
     * Settings the agent offers that nothing here knows by name — whatever a newer release
     * of its CLI adds — so they reach the picker without an update to AI Relay.
     */
    fun options(): List<AgentOption> = emptyList()
    /** Set one of [options]. False when the agent refuses it. */
    fun setOption(id: String, value: String): Boolean = false
    /** Change how freely tools run, mid-conversation. False when the backend cannot. */
    fun setPermissionMode(mode: PermissionMode): Boolean = false
    /** Let the agent see another directory from now on. False when the backend cannot. */
    fun addDir(dir: java.io.File): Boolean = false
    /**
     * Nothing has happened for a while: let go of what costs while idle (a
     * subprocess, a browser) without forgetting the conversation. The next
     * turn brings it back.
     */
    fun idle() {}
    /** Interrupt the in-flight turn (Ctrl-C handler). */
    fun cancel() {}
    /** Release resources (kill subprocess, etc.). */
    fun close() {}
    /** One-line description shown in the banner. */
    fun describe(): String
}

/**
 * A model as a picker shows it: [id] is what is passed back to switch, [name] what
 * is read, [badge] a short note beside it (GitHub Copilot's "1x" / "15x" premium
 * multiplier).
 */
data class ModelInfo(val id: String, val name: String? = null, val description: String? = null, val badge: String? = null) {
    fun toMap(): Map<String, String> = buildMap {
        put("id", id)
        name?.let { put("name", it) }
        description?.let { put("description", it) }
        badge?.let { put("badge", it) }
    }
}

/**
 * Usage against the account's limits: Claude's five-hour and weekly windows,
 * GitHub Copilot's monthly premium requests. A window gives a percentage, a
 * count, or both; [resetsAt] is epoch milliseconds.
 */
data class Quota(val windows: List<Window>, val note: String? = null) {
    data class Window(
        val label: String,
        val usedPercent: Double? = null,
        val used: Double? = null,
        val limit: Double? = null,
        val resetsAt: Long? = null,
        val unlimited: Boolean = false,
    )

    fun toMap(): Map<String, Any> = buildMap {
        put("windows", windows.map { w ->
            buildMap<String, Any> {
                put("label", w.label)
                w.usedPercent?.let { put("usedPercent", it) }
                w.used?.let { put("used", it) }
                w.limit?.let { put("limit", it) }
                w.resetsAt?.let { put("resetsAt", it) }
                if (w.unlimited) put("unlimited", true)
            }
        })
        note?.let { put("note", it) }
    }

    /** One line for the terminal: "5h 42% · week 6%". */
    fun summary(): String = windows.joinToString(" · ") { w ->
        when {
            w.unlimited -> "${w.label} unlimited"
            w.usedPercent != null -> "${w.label} ${w.usedPercent.toInt()}%"
            w.used != null && w.limit != null -> "${w.label} ${w.used.toInt()}/${w.limit.toInt()}"
            else -> w.label
        }
    }
}

/** A setting an agent offers as a choice of values ([Agent.options]). */
data class AgentOption(val id: String, val name: String, val current: String?, val values: List<Pair<String, String>>, val description: String? = null) {
    fun toMap(): Map<String, Any> = buildMap<String, Any> {
        put("id", id); put("name", name)
        current?.let { put("current", it) }
        description?.let { put("description", it) }
        put("values", this@AgentOption.values.map { (v, n) -> mapOf<String, String>("value" to v, "name" to n) })
    }
}

/** An image attached to a message: what the IDE pasted or dropped, or `/image` read from disk. */
data class Attachment(val name: String, val mimeType: String, val dataBase64: String) {
    val isImage: Boolean get() = mimeType.startsWith("image/")

    companion object {
        private val MIME = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "bmp" to "image/bmp", "svg" to "image/svg+xml",
        )

        fun mimeFor(name: String): String? = MIME[name.substringAfterLast('.', "").lowercase()]

        /** Read an image file; null when it is not one this knows. */
        fun fromFile(file: java.io.File): Attachment? {
            val mime = mimeFor(file.name) ?: return null
            return Attachment(file.name, mime, java.util.Base64.getEncoder().encodeToString(file.readBytes()))
        }
    }
}

/** How aggressively the agent runs tools without asking. */
enum class PermissionMode(val id: String) {
    /**
     * Answers questions about the code and changes nothing: the model can read,
     * list and search the workspace and use the web tools, and is not offered
     * editFile, writeFile, runCommand or MCP tools at all (an MCP tool is someone
     * else's code with whatever effects that server chooses). Unlike `--ask`, which
     * removes every tool, it can still go and look.
     */
    READ_ONLY("readOnly"),
    ASK("ask"),
    ACCEPT_EDITS("acceptEdits"),
    BYPASS("bypass");

    companion object {
        /** Other names people reach for; `search` is what the mode is for. */
        private val ALIASES = mapOf(
            "search" to READ_ONLY, "readonly" to READ_ONLY, "read-only" to READ_ONLY, "read_only" to READ_ONLY,
            "plan" to READ_ONLY, "accept" to ACCEPT_EDITS, "yolo" to BYPASS,
        )

        fun from(id: String?, default: PermissionMode): PermissionMode = parse(id) ?: default

        /** Null when [id] names no mode. */
        fun parse(id: String?): PermissionMode? {
            val key = id?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.id.equals(key, true) || it.name.equals(key, true) } ?: ALIASES[key]
        }

        /** For usage lines. */
        const val CHOICES = "readOnly | ask | acceptEdits | bypass"
    }
}
