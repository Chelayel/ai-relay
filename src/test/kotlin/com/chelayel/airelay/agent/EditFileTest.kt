package com.chelayel.airelay.agent

import com.chelayel.airelay.cli.Workspace
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Editing an existing file by replacing a snippet.
 *
 * Rewriting a whole file is the wrong shape for a change: it costs the entire
 * file in both directions, and the Copilot transport caps a message at a few
 * kilobytes, which puts any real source file out of reach. A targeted edit costs
 * only the lines that change.
 */
class EditFileTest {

    private val dir: File = Files.createTempDirectory("airelay-edit").toFile()
    private val tools = Tools(Workspace(dir, emptyList()), commandTimeoutSeconds = 10)

    @AfterTest fun cleanUp() { dir.deleteRecursively() }

    private fun write(name: String, text: String): File =
        File(dir, name).apply { parentFile?.mkdirs(); writeText(text) }

    private fun call(tool: String, vararg pairs: Pair<String, Any>): JsonObject =
        tools.execute(
            tool,
            JsonObject().apply {
                pairs.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> addProperty(k, v)
                        is Int -> addProperty(k, v)
                        else -> addProperty(k, v.toString())
                    }
                }
            },
        )

    private fun JsonObject.result(): String = get("result")?.asString.orEmpty()
    private fun JsonObject.error(): String = get("error")?.asString.orEmpty()

    @Test
    fun `replaces a snippet and leaves the rest of the file alone`() {
        val f = write("Greet.kt", "package a\n\nfun greet(n: String) = \"hi n\"\n")
        val out = call("editFile", "path" to "Greet.kt", "find" to "\"hi n\"", "replace" to "\"hello n\"")
        assertTrue(out.error().isEmpty(), out.error())
        assertEquals("package a\n\nfun greet(n: String) = \"hello n\"\n", f.readText())
    }

    @Test
    fun `shows what changed`() {
        write("A.kt", "val x = 1\n")
        val out = call("editFile", "path" to "A.kt", "find" to "val x = 1", "replace" to "val x = 2")
        assertTrue(out.result().contains("- val x = 1"), out.result())
        assertTrue(out.result().contains("+ val x = 2"), out.result())
    }

    /** Guessing which occurrence was meant is worse than asking for more context. */
    @Test
    fun `refuses an ambiguous snippet and says how many places matched`() {
        val f = write("B.kt", "val a = 0\nval b = 0\n")
        val out = call("editFile", "path" to "B.kt", "find" to "= 0", "replace" to "= 9")
        assertTrue(out.error().contains("appears 2 times"), out.error())
        assertEquals("val a = 0\nval b = 0\n", f.readText(), "an ambiguous edit must change nothing")
    }

    @Test
    fun `all true changes every occurrence`() {
        val f = write("C.kt", "val a = 0\nval b = 0\n")
        val out = call("editFile", "path" to "C.kt", "find" to "= 0", "replace" to "= 9", "all" to true)
        assertTrue(out.error().isEmpty(), out.error())
        assertEquals("val a = 9\nval b = 9\n", f.readText())
    }

    @Test
    fun `a snippet that is not there is an error, not a silent no-op`() {
        write("D.kt", "val x = 1\n")
        val out = call("editFile", "path" to "D.kt", "find" to "val y = 2", "replace" to "val y = 3")
        assertTrue(out.error().contains("not in D.kt"), out.error())
    }

    @Test
    fun `editing a file that does not exist is an error`() {
        assertTrue(call("editFile", "path" to "nope.kt", "find" to "a", "replace" to "b").error().isNotEmpty())
    }

    @Test
    fun `an edit cannot escape the workspace`() {
        val out = call("editFile", "path" to "../escape.kt", "find" to "a", "replace" to "b")
        assertTrue(out.error().contains("escapes"), out.error())
    }

    // ---- reading part of a file ----------------------------------------------

    @Test
    fun `reads a range of lines and says which ones`() {
        write("Long.kt", (1..50).joinToString("\n") { "line $it" })
        val out = call("readFile", "path" to "Long.kt", "offset" to 10, "limit" to 3).result()
        assertTrue(out.contains("lines 10-12 of 50"), out)
        assertTrue(out.contains("line 10") && out.contains("line 12"))
        assertFalse(out.contains("line 13"))
    }

    @Test
    fun `a range past the end of the file is clamped, not an error`() {
        write("Short.kt", "one\ntwo\n")
        assertTrue(call("readFile", "path" to "Short.kt", "offset" to 99, "limit" to 5).error().isEmpty())
    }

    // ---- appending ------------------------------------------------------------

    @Test
    fun `append adds to the end so a long file can be built across calls`() {
        write("Big.kt", "first\n")
        call("writeFile", "path" to "Big.kt", "content" to "second\n", "append" to true)
        call("writeFile", "path" to "Big.kt", "content" to "third\n", "append" to true)
        assertEquals("first\nsecond\nthird\n", File(dir, "Big.kt").readText())
    }

    @Test
    fun `writeFile without append still replaces the file`() {
        write("Rep.kt", "old\n")
        call("writeFile", "path" to "Rep.kt", "content" to "new\n")
        assertEquals("new\n", File(dir, "Rep.kt").readText())
    }

    @Test
    fun `append is accepted when a model sends it as a string`() {
        write("Str.kt", "a\n")
        call("writeFile", "path" to "Str.kt", "content" to "b\n", "append" to "true")
        assertEquals("a\nb\n", File(dir, "Str.kt").readText())
    }
}
