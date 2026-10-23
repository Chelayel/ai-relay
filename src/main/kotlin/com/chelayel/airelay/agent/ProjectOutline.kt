package com.chelayel.airelay.agent

import com.chelayel.airelay.cli.Workspace
import java.io.File

/**
 * Where the work is and what is in it: the working directory, the other
 * readable roots, and a breadth-first sketch of the tree within a budget.
 *
 * Without it an agent has tools and no idea what they would find. A chat-tuned
 * model does not go exploring on a hunch — asked to write a test it answered
 * "I don't have access to the project's code, please paste it" — and a model
 * handed one file answers from that file. A short map makes "find the caller,
 * read the test" an obvious next step. Shared by both agentic backends.
 */
object ProjectOutline {
    /** How deep the sketch goes before it is just noise. */
    private const val DEPTH = 3

    /** Directories that are build output or dependencies, never the project. */
    val SKIP_DIRS = setOf("build", "node_modules", "target", "dist", "out", "venv", "__pycache__", "vendor")

    fun describe(workspace: Workspace, budget: Int): String {
        val out = StringBuilder("--- Project ---\n")
        out.append("Working directory: ").append(workspace.primary.path).append("\n")
        workspace.roots.drop(1).forEach { out.append("Also readable: ").append(it.path).append("\n") }
        val listing = files(workspace.primary, budget)
        if (listing.isNotBlank()) out.append("Files (paths are relative to the working directory):\n").append(listing)
        return out.toString()
    }

    /** The tree under [root], widest level first, within [budget] characters. */
    fun files(root: File, budget: Int): String {
        val out = StringBuilder()
        var level = listOf(root)
        var depth = 0
        while (level.isNotEmpty() && depth < DEPTH && out.length < budget) {
            val next = mutableListOf<File>()
            for (dir in level) {
                val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: continue
                for (entry in entries) {
                    if (entry.name.startsWith(".") || entry.name in SKIP_DIRS) continue
                    if (entry.isDirectory) next.add(entry)
                    val label = relative(root, entry) + if (entry.isDirectory) "/" else ""
                    if (out.length + label.length + 3 > budget) return out.toString() + "  …\n"
                    out.append("  ").append(label).append("\n")
                }
            }
            level = next
            depth++
        }
        return out.toString()
    }

    private fun relative(root: File, file: File): String =
        runCatching { root.toPath().relativize(file.toPath()).toString().replace('\\', '/') }.getOrDefault(file.name)
}
