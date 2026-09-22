package com.chelayel.airelay.agent

import java.io.File

/**
 * A skill: a `SKILL.md` with a frontmatter `name` and `description`, the shape
 * Claude Code uses, so a skill written once serves every backend here. The
 * Claude backend never needs this — its CLI loads the same folders itself —
 * but Gemini and Copilot have no such thing, and this is how they get one:
 * the instructions go into the message they are attached to.
 */
data class Skill(val name: String, val description: String?, val file: File, val source: String) {

    /** The markdown body, frontmatter removed. */
    fun instructions(): String = Skills.stripFrontmatter(file.readText())

    /** What goes into a message when this skill is attached to it. */
    fun asPrompt(cap: Int = Skills.MAX_CHARS): String {
        val body = instructions().trim().let { if (it.length > cap) it.take(cap) + "\n… (truncated)" else it }
        return "Use the \"$name\" skill for this request. Its instructions:\n\n$body"
    }
}

object Skills {

    /** Copilot's composer is small; a skill longer than this is cut, not dropped. */
    const val MAX_CHARS = 12_000

    /** Skill folders, relative to a workspace root. `.claude` first: it is the one people already have. */
    private val PROJECT_DIRS = listOf(".claude/skills", ".gemini/skills", ".airelay/skills")

    /**
     * Every skill visible from [roots]: each root's skill folders, then the
     * user's. A skill is `<dir>/<name>/SKILL.md`, or one level deeper, which is
     * how a synced skill library is laid out. Same name twice: the first wins,
     * so a project skill shadows a user one.
     */
    fun discover(roots: List<File>, home: File? = System.getProperty("user.home")?.let(::File)): List<Skill> {
        val found = LinkedHashMap<String, Skill>()
        // A skill folder holds SKILL.md; a folder that does not is looked into,
        // two levels down at most: a synced library is `synced/<id>/<name>/`.
        fun scan(dir: File, source: String, depth: Int = 2) {
            if (!dir.isDirectory) return
            val subs = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name } ?: return
            for (sub in subs) {
                val md = File(sub, "SKILL.md")
                if (md.isFile) {
                    val skill = runCatching { parse(md, source) }.getOrNull() ?: continue
                    found.putIfAbsent(skill.name, skill)
                } else if (depth > 0) {
                    scan(sub, source, depth - 1)
                }
            }
        }
        for (root in roots) for (rel in PROJECT_DIRS) scan(File(root, rel), root.name)
        home?.let { scan(File(it, ".claude/skills"), "~"); scan(File(it, ".airelay/skills"), "~") }
        return found.values.toList()
    }

    /** Name and description from the frontmatter; the folder name when there is no `name`. */
    fun parse(file: File, source: String): Skill {
        val fm = frontmatter(file.readText())
        val name = fm["name"]?.takeIf { it.isNotBlank() } ?: file.parentFile.name
        return Skill(name, fm["description"]?.takeIf { it.isNotBlank() }, file, source)
    }

    /** `key: value` pairs between the leading `---` markers. Quotes around a value are removed. */
    fun frontmatter(text: String): Map<String, String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 } ?: return emptyMap()
        return lines.subList(1, end + 1).mapNotNull { line ->
            val i = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            val key = line.substring(0, i).trim()
            if (key.isEmpty() || key.first().isWhitespace() || line.first().isWhitespace()) return@mapNotNull null
            var value = line.substring(i + 1).trim()
            if (value.length >= 2 && (value.first() == '"' && value.last() == '"' || value.first() == '\'' && value.last() == '\'')) {
                value = value.substring(1, value.length - 1)
            }
            key to value
        }.toMap()
    }

    fun stripFrontmatter(text: String): String {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return text
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 } ?: return text
        return lines.drop(end + 2).joinToString("\n")
    }

    /** [text] with the named skills' instructions in front of it; unknown names are reported, not silently dropped. */
    fun attach(text: String, names: List<String>, skills: List<Skill>, unknown: (String) -> Unit = {}): String {
        val parts = names.mapNotNull { n -> skills.firstOrNull { it.name == n }?.asPrompt() ?: run { unknown(n); null } }
        return if (parts.isEmpty()) text else (parts + text).joinToString("\n\n")
    }
}
