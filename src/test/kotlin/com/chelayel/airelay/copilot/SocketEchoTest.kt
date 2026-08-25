package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.agent.CopilotProtocol
import com.chelayel.airelay.copilot.api.CopilotBrowser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The socket path needs the same echo cleaning as the page path.
 *
 * Frames were left uncleaned on the reasoning that they carry the model's words
 * rather than the page's. Against real M365 Copilot they carried the echo too:
 * a turn came back with our own preamble in it, and the loop parsed the example
 * tool calls out of that echo and ran them — `readFile src/Main.kt` and
 * `editFile src/Foo.kt`, neither of which is a file in this project.
 */
class SocketEchoTest {

    /** Shaped like the real preamble: contract, example calls, reminder, task. */
    private val prompt = """
        You are AI Relay (Copilot), an agentic coding assistant.

        ```tool
        {"tool": "readFile", "args": {"path": "src/Main.kt"}}
        ```
        ```tool
        {"tool": "editFile", "args": {"path": "src/Foo.kt", "find": "old line", "replace": "new line"}}
        ```
        editFile to change a file, writeFile for a new one, runCommand to verify.

        --- Task ---
        read build.gradle.kts and tell me the JDK toolchain version
    """.trimIndent()

    @Test
    fun `an echoed preamble yields no tool calls`() {
        val framed = """
            Tool results:<br>
            {"tool": "readFile", "args": {"path": "src/Main.kt"}}<br>
            {"tool": "editFile", "args": {"path": "src/Foo.kt", "find": "old line", "replace": "new line"}}<br>
            editFile to change a file, writeFile for a new one, runCommand to verify.
        """.trimIndent()

        val cleaned = CopilotBrowser.cleanReply(framed, prompt)

        assertTrue(
            CopilotProtocol.parseCalls(cleaned).isEmpty(),
            "our own example calls were parsed back as calls from Copilot: $cleaned",
        )
    }

    @Test
    fun `a real call in the same turn still reads as one`() {
        val framed = """
            {"tool": "editFile", "args": {"path": "src/Foo.kt", "find": "old line", "replace": "new line"}}<br>
            I'll read the build file now.
            ```tool
            {"tool": "readFile", "args": {"path": "build.gradle.kts"}}
            ```
        """.trimIndent()

        val calls = CopilotProtocol.parseCalls(CopilotBrowser.cleanReply(framed, prompt))

        assertEquals(1, calls.size, "expected only Copilot's own call to survive: ${calls.map { it.name }}")
        assertEquals("build.gradle.kts", calls.single().args.get("path")?.asString)
    }

    @Test
    fun `br tags in a frame are unwrapped before comparing`() {
        // The tags are why this went unnoticed: with them intact every line-wise
        // comparison misses, and the whole echo sails through as the answer.
        val framed = "--- Task ---<br>read build.gradle.kts and tell me the JDK toolchain version<br>It targets JDK 21."

        assertEquals("It targets JDK 21.", CopilotBrowser.cleanReply(framed, prompt))
    }

    @Test
    fun `the standing disclaimer is not an answer`() {
        assertEquals("", CopilotBrowser.cleanReply("AI-generated content may be incorrect", prompt))
        assertEquals("", CopilotBrowser.cleanReply("AI-generated content may be incorrect.", prompt))
    }

    @Test
    fun `lines of zero-width filler are not an answer`() {
        // What a scraped turn actually came back as: one span per rendered
        // element, each holding nothing but a zero-width space and non-joiner.
        val filler = List(40) { "​‌" }.joinToString("\n")
        assertEquals("", CopilotBrowser.cleanReply(filler, prompt))
    }

    @Test
    fun `zero-width filler inside the echo does not hide it`() {
        val framed = "--- Task ---\n​read build.gradle.kts and tell‌ me the JDK toolchain version\nIt targets JDK 21."
        assertEquals("It targets JDK 21.", CopilotBrowser.cleanReply(framed, prompt))
    }

    @Test
    fun `a reply that talks about being incorrect is kept`() {
        // Matched whole, never as a substring — a reply may discuss the caveat.
        val reply = "That assumption may be incorrect: the toolchain is set to 21, not 17."
        assertEquals(reply, CopilotBrowser.cleanReply(reply, prompt))
    }
}
