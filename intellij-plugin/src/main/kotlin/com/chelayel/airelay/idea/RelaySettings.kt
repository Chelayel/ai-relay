package com.chelayel.airelay.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * What the plugin remembers. Deliberately little: the connection settings —
 * keys, the Copilot capture, MCP servers — live in `~/.airelay/`, the same
 * files the CLI reads, so configuring one configures both.
 */
@State(name = "AiRelaySettings", storages = [Storage("airelay.xml")])
class RelaySettings : PersistentStateComponent<RelaySettings.State> {

    class State {
        var backend: String = "claude"
        var permissionMode: String = "acceptEdits"
        /** Empty: the IDE's own runtime, which is always a current Java. */
        var javaPath: String = ""
        var extraArgs: String = ""
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    companion object {
        fun get(): RelaySettings = ApplicationManager.getApplication().getService(RelaySettings::class.java)
    }
}
