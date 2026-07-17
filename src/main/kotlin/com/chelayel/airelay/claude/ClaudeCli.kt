package com.chelayel.airelay.claude

import java.io.File

/**
 * Locates the `claude` executable. A CLI launched from a login shell usually has
 * a full PATH, but we still probe the common install locations directly before
 * falling back to a bare `claude`. Ported verbatim from Claude Relay.
 */
object ClaudeCli {

    fun detectExecutable(): String {
        val home = System.getProperty("user.home")
        val candidates = listOf(
            "$home/.local/bin/claude",
            "$home/.claude/local/claude",
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
            "/usr/bin/claude",
        )
        return candidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
            ?: "claude"
    }

    /** Extra bin directories to prepend to PATH so nested tools (node, git…) resolve. */
    fun extraPathEntries(): List<String> {
        val home = System.getProperty("user.home")
        return listOf(
            "$home/.local/bin",
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
        )
    }
}
