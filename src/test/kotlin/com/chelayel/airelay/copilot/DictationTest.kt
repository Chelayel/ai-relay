package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.agent.CopilotProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saving a reply that wrote the file out instead of calling a tool.
 *
 * M365 Copilot will not emit a write call — it holds that doing so would be
 * pretending to execute something. It will write the file. So the harness reads
 * what it wrote and saves it, which is what was happening anyway.
 */
class DictationTest {

    @Test
    fun `a path above a fence becomes a writeFile`() {
        val reply = """
            Here is the test.

            src/test/kotlin/com/chelayel/airelay/SmokeTest.kt
            ```kotlin
            class SmokeTest { @Test fun works() { assertEquals(4, 2 + 2) } }
            ```
        """.trimIndent()

        val calls = CopilotProtocol.dictatedFiles(reply)
        assertEquals(1, calls.size)
        assertEquals("writeFile", calls[0].name)
        assertEquals("src/test/kotlin/com/chelayel/airelay/SmokeTest.kt", calls[0].args.get("path").asString)
        assertTrue(calls[0].args.get("content").asString.contains("class SmokeTest"))
    }

    @Test
    fun `a decorated heading still names the file`() {
        // How a chat model actually writes a heading.
        for (heading in listOf(
            "**File: src/Foo.kt**",
            "`src/Foo.kt`",
            "File: src/Foo.kt",
            "Create src/Foo.kt:",
        )) {
            val calls = CopilotProtocol.dictatedFiles("$heading\n```kotlin\nval x = 1\n```")
            assertEquals("src/Foo.kt", calls.singleOrNull()?.args?.get("path")?.asString, "for heading: $heading")
        }
    }

    @Test
    fun `prose that is not a path is never written to`() {
        // Inventing a path out of a sentence is the one mistake here that would
        // be worse than doing nothing.
        val reply = "Here is what the test should look like:\n```kotlin\nval x = 1\n```"
        assertTrue(CopilotProtocol.dictatedFiles(reply).isEmpty())
    }

    @Test
    fun `a bare directory name is not a path`() {
        assertTrue(CopilotProtocol.dictatedFiles("src\n```kotlin\nval x = 1\n```").isEmpty())
        assertTrue(CopilotProtocol.dictatedFiles("Summary:\n```kotlin\nval x = 1\n```").isEmpty())
    }

    @Test
    fun `a tool fence is left to the real parser`() {
        val reply = "src/Foo.kt\n```tool\n{\"tool\": \"readFile\", \"args\": {\"path\": \"a\"}}\n```"
        assertTrue(CopilotProtocol.dictatedFiles(reply).isEmpty())
    }

    @Test
    fun `two dictated files both get written`() {
        val reply = """
            src/A.kt
            ```kotlin
            class A
            ```
            src/B.kt
            ```kotlin
            class B
            ```
        """.trimIndent()
        assertEquals(listOf("src/A.kt", "src/B.kt"), CopilotProtocol.dictatedFiles(reply).map { it.args.get("path").asString })
    }

    @Test
    fun `an empty fence writes nothing`() {
        assertTrue(CopilotProtocol.dictatedFiles("src/Foo.kt\n```kotlin\n\n```").isEmpty())
    }

    @Test
    fun `a path named only in prose still places the file`() {
        // Word for word the shape of what M365 Copilot actually replied: it
        // wrote the file, then named the path in a sentence rather than a
        // heading, and said plainly that it had not applied anything.
        val reply = """
            ```kotlin
            package com.chelayel.airelay
            class SmokeTest
            ```
            I have not applied this change to src/test/kotlin/com/chelayel/airelay/SmokeTest.kt.
        """.trimIndent()

        val calls = CopilotProtocol.dictatedFiles(reply)
        assertEquals("src/test/kotlin/com/chelayel/airelay/SmokeTest.kt", calls.single().args.get("path").asString)
    }

    @Test
    fun `two mentioned paths are too ambiguous to write`() {
        val reply = """
            ```kotlin
            class A
            ```
            This would go in src/A.kt and replace src/B.kt.
        """.trimIndent()
        assertTrue(CopilotProtocol.dictatedFiles(reply).isEmpty())
    }

    @Test
    fun `a path inside the file content is not the file's own path`() {
        // An import or a comment naming another file must not become the target.
        val reply = """
            ```kotlin
            // see docs/design/notes.md for why
            class A
            ```
            Save this as src/A.kt
        """.trimIndent()
        assertEquals("src/A.kt", CopilotProtocol.dictatedFiles(reply).single().args.get("path").asString)
    }

    // ---- the regression that corrupted a source file -------------------------

    @Test
    fun `a build log is never written as a source file`() {
        // This exact turn overwrote SmokeTest.kt with gradle output: the log was
        // the only fence in the reply and the file was the only path mentioned,
        // so the two were joined on no evidence at all.
        val reply = """
            ```text
            BUILD SUCCESSFUL
            > Task :compileKotlin UP-TO-DATE
            > Task :test
            ```
            Restored src/test/kotlin/com/chelayel/airelay/SmokeTest.kt and ran ./gradlew test.
        """.trimIndent()

        assertTrue(
            CopilotProtocol.dictatedFiles(reply).isEmpty(),
            "a transcript must never be saved as source",
        )
    }

    @Test
    fun `an untagged fence of build output is still not a file`() {
        val reply = """
            ```
            > Task :compileKotlin
            BUILD SUCCESSFUL in 2s
            ```
            That was src/A.kt.
        """.trimIndent()
        assertTrue(CopilotProtocol.dictatedFiles(reply).isEmpty())
    }

    @Test
    fun `shell and diff fences are not files`() {
        for (tag in listOf("sh", "bash", "console", "diff", "text", "log")) {
            val reply = "src/A.kt" + "\n" + "```" + tag + "\n" + "some content here" + "\n" + "```"
            assertTrue(CopilotProtocol.dictatedFiles(reply).isEmpty(), "tag $tag was treated as a file")
        }
    }

    @Test
    fun `a prose path is only attributed when there is one block to attribute it to`() {
        val reply = """
            ```kotlin
            class A
            ```
            ```kotlin
            class B
            ```
            Both belong near src/A.kt.
        """.trimIndent()
        assertTrue(CopilotProtocol.dictatedFiles(reply).isEmpty())
    }

    @Test
    fun `a real source file mentioning a build line is still saved`() {
        // The guard is a ratio, not a keyword ban: one such line in a real file
        // is imaginable, and must not cost the write.
        val reply = """
            src/Build.kt
            ```kotlin
            package a
            /** Prints BUILD SUCCESSFUL when done. */
            class Build {
                fun run() { println("BUILD SUCCESSFUL") }
                fun other() = 1
                fun more() = 2
            }
            ```
        """.trimIndent()
        assertEquals("src/Build.kt", CopilotProtocol.dictatedFiles(reply).single().args.get("path").asString)
    }
}
