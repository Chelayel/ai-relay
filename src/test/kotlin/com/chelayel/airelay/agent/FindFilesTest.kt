package com.chelayel.airelay.agent

import com.chelayel.airelay.cli.PermissionMode
import com.chelayel.airelay.cli.Workspace
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Finding files by name — the first step of reading beyond the file the user pointed at. */
class FindFilesTest {

    private val dir: File = Files.createTempDirectory("airelay-find").toFile().apply {
        listOf(
            "src/main/kotlin/app/UserService.kt", "src/main/kotlin/app/UserRepository.kt",
            "src/test/kotlin/app/UserServiceTest.kt", "build.gradle.kts", "README.md",
            "build/generated/UserService.kt", "node_modules/x/index.js", ".git/config",
        ).forEach { File(this, it).apply { parentFile.mkdirs(); writeText("x") } }
    }
    private val tools = Tools(Workspace(dir, emptyList()), commandTimeoutSeconds = 5)

    private fun find(pattern: String): List<String> {
        val r = tools.execute("findFiles", JsonObject().apply { addProperty("pattern", pattern) })
        return r.get("result")?.asString.orEmpty().lines().filter { it.isNotBlank() && !it.startsWith("No files") && !it.startsWith("…") }
    }

    @Test
    fun `plain text matches anywhere in the path, ignoring case`() {
        assertEquals(setOf("src/main/kotlin/app/UserService.kt", "src/test/kotlin/app/UserServiceTest.kt"), find("userservice").toSet())
    }

    @Test
    fun `a name glob matches file names at any depth`() {
        assertEquals(setOf("src/main/kotlin/app/UserServiceTest.kt".replace("main", "test")), find("*Test.kt").toSet())
        assertTrue(find("*.kts").contains("build.gradle.kts"))
    }

    @Test
    fun `a path glob matches the relative path, with double star crossing directories`() {
        assertEquals(setOf("src/test/kotlin/app/UserServiceTest.kt"), find("src/test/**/*.kt").toSet())
        assertEquals(setOf("src/main/kotlin/app/UserService.kt", "src/main/kotlin/app/UserRepository.kt"), find("**/main/**/User*.kt").toSet())
    }

    @Test
    fun `build output, dependencies and dot folders are skipped`() {
        val all = find("**/*")
        assertFalse(all.any { it.startsWith("build/") || it.startsWith("node_modules/") || it.startsWith(".git/") }, all.toString())
    }

    @Test
    fun `it is offered in read-only mode and changes nothing`() {
        assertTrue(tools.specs(PermissionMode.READ_ONLY).any { it.name == "findFiles" })
        assertFalse(tools.mutates("findFiles"))
    }

    @Test
    fun `no match says so`() {
        val r = tools.execute("findFiles", JsonObject().apply { addProperty("pattern", "nothing-like-this") })
        assertTrue(r.get("result").asString.startsWith("No files match"))
    }

    @Test
    fun `the project outline names the working directory and the layout, without build output`() {
        val o = ProjectOutline.describe(Workspace(dir, emptyList()), 2_000)
        assertTrue(o.contains("Working directory: ${dir.canonicalPath}"), o)
        assertTrue(o.contains("src/main/") && o.contains("README.md"), o)
        assertFalse(o.contains("node_modules") || o.contains("build/generated"), o)
    }
}
