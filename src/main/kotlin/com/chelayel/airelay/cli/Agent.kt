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
    fun currentModel(): String? = null
    /** Switch models for the rest of the conversation. False when the backend cannot. */
    fun useModel(name: String): Boolean = false
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
