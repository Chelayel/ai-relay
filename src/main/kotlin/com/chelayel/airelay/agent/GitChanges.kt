package com.chelayel.airelay.agent

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The working tree's uncommitted changes as text to attach to a message — what
 * Copilot's `#changes` and AI Chat's "uncommitted changes" give the model. One
 * `git diff HEAD` per workspace root that is a repository, plus the untracked files
 * by name; capped, because a message is not the place for a generated lockfile.
 */
object GitChanges {
    private const val CAP = 60_000

    fun describe(roots: List<File>): String {
        val out = StringBuilder()
        for (root in roots) {
            val top = git(root, "rev-parse", "--show-toplevel")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val diff = git(root, "diff", "HEAD", "--no-color", "--no-ext-diff").orEmpty()
            val untracked = git(root, "ls-files", "--others", "--exclude-standard").orEmpty().lines().filter { it.isNotBlank() }
            if (diff.isBlank() && untracked.isEmpty()) continue
            if (roots.size > 1) out.append("# ").append(top).append('\n')
            if (diff.isNotBlank()) out.append(diff.trimEnd()).append('\n')
            if (untracked.isNotEmpty()) out.append("\nUntracked files:\n").append(untracked.take(100).joinToString("\n") { "  $it" }).append('\n')
        }
        if (out.isEmpty()) return "No uncommitted changes."
        return if (out.length <= CAP) out.toString() else out.substring(0, CAP) + "\n… (cut at ${CAP / 1000} KB; ask for a file's diff to see the rest)"
    }

    private fun git(dir: File, vararg args: String): String? = runCatching {
        val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.outputStream.close()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        text.takeIf { p.exitValue() == 0 }
    }.getOrNull()
}
