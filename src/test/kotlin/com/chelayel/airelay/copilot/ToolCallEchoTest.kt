package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.agent.CopilotProtocol
import com.chelayel.airelay.copilot.api.CopilotBrowser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tool call read back off the page must survive echo removal intact.
 *
 * Against real M365 Copilot every readFile came back "No such file", and Copilot
 * then said it had no access to the project — which was true. The prompt lists
 * the project's files; echo removal cuts any substantial line of ours wherever it
 * turns up; and the path in a readFile call *is* one of those lines. So the path
 * was subtracted out of the call and the tool ran with nothing.
 */
class ToolCallEchoTest {

    private val path = "src/main/kotlin/com/chelayel/airelay/Main.kt"

    /** Shaped like a real preamble: an outline of files, then the tool contract. */
    private val prompt = """
        You are AI Relay (Copilot), a coding agent working directly in the user's project.

        --- Project ---
        Working directory: /home/user/ai-relay
        Files (paths are relative to the working directory):
          src/main/kotlin/com/chelayel/airelay/
          $path
          src/main/kotlin/com/chelayel/airelay/agent/Tools.kt

        --- Tools ---
        ```tool
        {"tool": "readFile", "args": {"path": "src/Main.kt"}}
        ```

        --- Task ---
        write unit tests
    """.trimIndent()

    private fun call(text: String) = CopilotProtocol.parseCalls(text).single()

    @Test
    fun `a path that is also a line of the project outline survives`() {
        val reply = """I'll start by reading the entry point.

            ```tool
            {"tool": "readFile", "args": {"path": "$path"}}
            ```
        """.trimIndent()

        val cleaned = CopilotBrowser.cleanPageText(prompt.replace("\n", "<br>") + reply, prompt)
        assertTrue(cleaned.contains(path), "the path was subtracted as an echo: $cleaned")
        assertEquals(path, call(cleaned).args.get("path").asString)
    }

    /**
     * The usual shape on the page-read path: a rendered code block has no
     * backticks left in it, so the call arrives as a bare JSON object.
     */
    @Test
    fun `an unfenced call keeps its arguments too`() {
        val reply = """{"tool": "editFile", "args": {"path": "$path", "find": "old", "replace": "new"}}"""

        val cleaned = CopilotBrowser.cleanPageText(prompt.replace("\n", "<br>") + reply, prompt)
        val made = call(cleaned)
        assertEquals("editFile", made.name)
        assertEquals(path, made.args.get("path").asString)
    }

    /** The reason the subtraction is there at all: our own example must not run. */
    @Test
    fun `the example call we sent is still not read back as a call`() {
        val scraped = prompt.replace("\n", "<br>") + "Here is what I found in the file."

        val cleaned = CopilotBrowser.cleanPageText(scraped, prompt)
        assertTrue(
            CopilotProtocol.parseCalls(cleaned).isEmpty(),
            "our own example was returned as a call from Copilot: $cleaned",
        )
        assertTrue(cleaned.contains("Here is what I found"), "the answer was lost: $cleaned")
    }

    /** Two calls in one reply, one of them naming a file from the outline. */
    @Test
    fun `several calls in one reply all survive`() {
        val reply = """
            ```tool
            {"tool": "readFile", "args": {"path": "$path"}}
            ```
            ```tool
            {"tool": "listFiles", "args": {"path": "src/test"}}
            ```
        """.trimIndent()

        val cleaned = CopilotBrowser.cleanPageText(prompt.replace("\n", "<br>") + reply, prompt)
        val calls = CopilotProtocol.parseCalls(cleaned)
        assertEquals(listOf("readFile", "listFiles"), calls.map { it.name })
        assertEquals(path, calls[0].args.get("path").asString)
    }

    /** Code with angle brackets in it is not markup, and must not be stripped. */
    @Test
    fun `a call carrying angle brackets is left alone`() {
        val reply = """{"tool": "editFile", "args": {"path": "a.kt", "find": "List<String>", "replace": "Set<String>"}}"""

        val cleaned = CopilotBrowser.cleanPageText(reply, prompt)
        assertEquals("List<String>", call(cleaned).args.get("find").asString)
    }
}
