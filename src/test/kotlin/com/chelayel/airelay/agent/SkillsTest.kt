package com.chelayel.airelay.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Skills are `SKILL.md` files in the folders Claude Code uses, so one written
 * for Claude serves Gemini and Copilot here too.
 */
class SkillsTest {

    private val root: File = Files.createTempDirectory("airelay-skills").toFile()
    private val home: File = Files.createTempDirectory("airelay-home").toFile()

    @AfterTest fun cleanUp() { root.deleteRecursively(); home.deleteRecursively() }

    private fun skill(base: File, dir: String, body: String) = File(base, "$dir/SKILL.md").apply { parentFile.mkdirs(); writeText(body) }

    @Test fun `frontmatter gives name and description, quotes removed`() {
        skill(root, ".claude/skills/review", "---\nname: review\ndescription: \"Review a diff: risks, then tests.\"\n---\n\n# Steps\nRead the diff.\n")
        val found = Skills.discover(listOf(root), home)
        assertEquals(listOf("review"), found.map { it.name })
        assertEquals("Review a diff: risks, then tests.", found[0].description)
        assertEquals("# Steps\nRead the diff.", found[0].instructions().trim())
    }

    @Test fun `folder name stands in for a missing name`() {
        skill(root, ".gemini/skills/deploy", "No frontmatter at all.\n")
        val found = Skills.discover(listOf(root), home)
        assertEquals("deploy", found[0].name)
        assertNull(found[0].description)
        assertEquals("No frontmatter at all.", found[0].instructions().trim())
    }

    @Test fun `project skill shadows a user skill of the same name, and nested libraries are found`() {
        skill(root, ".claude/skills/pdf", "---\nname: pdf\ndescription: project one\n---\nproject\n")
        skill(home, ".claude/skills/synced/lib-1/pdf", "---\nname: pdf\ndescription: user one\n---\nuser\n")
        skill(home, ".claude/skills/synced/lib-1/xlsx", "---\nname: xlsx\n---\nsheets\n")
        val found = Skills.discover(listOf(root), home)
        assertEquals(listOf("pdf", "xlsx"), found.map { it.name })
        assertEquals("project one", found[0].description)
        assertEquals("~", found[1].source)
    }

    @Test fun `attach puts the instructions before the message and reports unknown names`() {
        skill(root, ".claude/skills/review", "---\nname: review\n---\nRead the diff.\n")
        val skills = Skills.discover(listOf(root), home)
        val unknown = mutableListOf<String>()
        val text = Skills.attach("look at PR 12", listOf("review", "nope"), skills) { unknown.add(it) }
        assertTrue(text.startsWith("Use the \"review\" skill for this request. Its instructions:\n\nRead the diff."))
        assertTrue(text.endsWith("\n\nlook at PR 12"))
        assertEquals(listOf("nope"), unknown)
    }

    @Test fun `a long skill is cut, not dropped`() {
        skill(root, ".claude/skills/big", "---\nname: big\n---\n" + "x".repeat(Skills.MAX_CHARS + 500))
        val prompt = Skills.discover(listOf(root), home)[0].asPrompt()
        assertTrue(prompt.endsWith("… (truncated)"))
        assertTrue(prompt.length < Skills.MAX_CHARS + 200)
    }
}
