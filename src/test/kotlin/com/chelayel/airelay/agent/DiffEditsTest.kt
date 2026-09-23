package com.chelayel.airelay.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffEditsTest {

    private val dir: File = Files.createTempDirectory("airelay-edits").toFile()

    @AfterTest fun cleanUp() { dir.deleteRecursively() }

    @Test fun `unified diff shows only the changed hunk with context`() {
        val before = (1..20).joinToString("\n") { "line $it" } + "\n"
        val after = before.replace("line 10", "line ten")
        val d = Diff.unified(before, after, "a.txt")
        assertTrue(d.startsWith("--- a.txt\n+++ a.txt\n@@ -7,7 +7,7 @@\n"), d)
        assertTrue(d.contains("-line 10\n+line ten\n"), d)
        assertTrue(!d.contains("line 1\n") && !d.contains("line 20"), d)
    }

    @Test fun `a new file is all additions and an emptied file all deletions`() {
        assertTrue(Diff.unified("", "a\nb\n", "n").contains("+a\n+b\n"))
        assertTrue(Diff.unified("a\nb\n", "", "n").contains("-a\n-b\n"))
    }

    @Test fun `revert puts the file back and is itself revertable, and refuses a stale revert`() {
        val f = File(dir, "x.kt").apply { writeText("one\n") }
        f.writeText("two\n")
        val c = Edits.record(f, "x.kt", "one\n", "two\n")
        val back = Edits.revert(c.id).getOrThrow()
        assertEquals("one\n", f.readText())
        assertTrue(back.diff.contains("-two\n+one\n"))
        assertTrue(Edits.revert(c.id).isFailure)
        Edits.revert(back.id).getOrThrow()
        assertEquals("two\n", f.readText())
    }

    @Test fun `reverting a created file deletes it`() {
        val f = File(dir, "new.txt").apply { writeText("hello") }
        val c = Edits.record(f, "new.txt", null, "hello")
        Edits.revert(c.id).getOrThrow()
        assertTrue(!f.exists())
    }

    @Test fun `the session patch spans a file's first before to its current content`() {
        val f = File(dir, "p.txt").apply { writeText("a\n") }
        f.writeText("b\n"); Edits.record(f, "p.txt", "a\n", "b\n")
        f.writeText("c\n"); Edits.record(f, "p.txt", "b\n", "c\n")
        val patch = Edits.sessionPatch()
        assertTrue(patch.contains("-a\n+c\n"), patch)
        assertTrue(!patch.contains("+b\n"), patch)
        assertTrue(Edits.touchedFiles().any { it.name == "p.txt" })
    }
}
