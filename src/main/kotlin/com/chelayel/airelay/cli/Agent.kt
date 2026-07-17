package com.chelayel.airelay.cli

/**
 * A conversational backend. Both relays implement this: Claude drives the
 * `claude` CLI subprocess, Gemini talks REST. [send] runs one user turn to
 * completion, streaming events to [sink]; conversation state lives in the agent.
 */
interface Agent {
    fun send(prompt: String, sink: Sink)
    /** Interrupt the in-flight turn (Ctrl-C handler). */
    fun cancel() {}
    /** Release resources (kill subprocess, etc.). */
    fun close() {}
    /** One-line description shown in the banner. */
    fun describe(): String
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
