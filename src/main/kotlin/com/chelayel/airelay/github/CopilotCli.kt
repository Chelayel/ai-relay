package com.chelayel.airelay.github

import java.io.File

/**
 * Finds GitHub's Copilot CLI. An IDE started from the Dock has a short PATH, so
 * the usual install places are tried by name before falling back to `copilot`
 * and letting the process launch say what is missing.
 */
object CopilotCli {
    fun detectExecutable(): String {
        System.getenv("AIRELAY_COPILOT_CLI")?.takeIf { File(it).canExecute() }?.let { return it }
        val name = if (System.getProperty("os.name").lowercase().contains("win")) "copilot.cmd" else "copilot"
        val home = System.getProperty("user.home")
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf(
            "/opt/homebrew/bin", "/usr/local/bin", "$home/.local/bin", "$home/.npm-global/bin", "$home/AppData/Roaming/npm",
        )
        return dirs.filter { it.isNotBlank() }.map { File(it, name) }.firstOrNull { it.canExecute() }?.path ?: name
    }
}
