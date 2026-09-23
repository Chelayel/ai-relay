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
    /** Models worth offering, the current one first. Empty when the backend has no choice to make here. */
    fun models(): List<String> = emptyList()
    fun currentModel(): String? = null
    /** Switch models for the rest of the conversation. False when the backend cannot. */
    fun useModel(name: String): Boolean = false
    /** Change how freely tools run, mid-conversation. False when the backend cannot. */
    fun setPermissionMode(mode: PermissionMode): Boolean = false
    /** Let the agent see another directory from now on. False when the backend cannot. */
    fun addDir(dir: java.io.File): Boolean = false
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
    ASK("ask"),
    ACCEPT_EDITS("acceptEdits"),
    BYPASS("bypass");

    companion object {
        fun from(id: String?, default: PermissionMode): PermissionMode =
            entries.firstOrNull { it.id.equals(id, true) || it.name.equals(id, true) } ?: default
    }
}
