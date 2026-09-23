package com.chelayel.airelay.agent

import java.io.File

/**
 * An agent persona: a markdown file whose body is a system prompt, in the
 * folders Claude Code keeps its custom agents (the `.md` files in `.claude/agents`) plus the
 * ones Gemini Relay used. Claude applies one through its own `--agent`; for
 * Gemini it becomes the system prompt's head, for Copilot the preamble's.
 */
data class Persona(val name: String, val description: String?, val file: File, val source: String) {
    fun prompt(): String = Skills.stripFrontmatter(file.readText()).trim()
}

object Personas {
    private val PROJECT_DIRS = listOf(".claude/agents", ".gemini/agents", ".gemini/personas", ".airelay/agents")

    fun discover(roots: List<File>, home: File? = System.getProperty("user.home")?.let(::File)): List<Persona> {
        val found = LinkedHashMap<String, Persona>()
        fun scan(dir: File, source: String) {
            dir.listFiles()?.filter { it.isFile && it.extension == "md" }?.sortedBy { it.name }?.forEach { f ->
                val p = runCatching { parse(f, source) }.getOrNull() ?: return@forEach
                found.putIfAbsent(p.name, p)
            }
        }
        for (root in roots) for (rel in PROJECT_DIRS) scan(File(root, rel), root.name)
        home?.let { scan(File(it, ".claude/agents"), "~"); scan(File(it, ".airelay/agents"), "~") }
        return found.values.toList()
    }

    fun parse(file: File, source: String): Persona {
        val fm = Skills.frontmatter(file.readText())
        return Persona(fm["name"]?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension, fm["description"]?.takeIf { it.isNotBlank() }, file, source)
    }
}
