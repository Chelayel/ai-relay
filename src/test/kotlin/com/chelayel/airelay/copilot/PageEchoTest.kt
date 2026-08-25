package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.agent.CopilotProtocol
import com.chelayel.airelay.copilot.api.CopilotBrowser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cleaning the page-text fallback, used when the answer can't be read off the
 * socket.
 *
 * Against real M365 Copilot this came back as our own prompt plus the reply
 * twice: `--- Task ---`, the tail of the tool reminder, a raw `<br>`, and the
 * answer repeated. The echo has to go — not only because it reads as nonsense,
 * but because the prompt contains an example tool call, and leaving it in means
 * parsing our own instructions back as a call from Copilot.
 */
class PageEchoTest {

    /** A preamble shaped like the real one: instructions, reminder, task. */
    private val prompt = """
        You are AI Relay (Copilot), an agentic coding assistant.

        ```tool
        {"tool": "readFile", "args": {"path": "src/Main.kt"}}
        ```
        Code written in prose is never saved to the project. Reply with prose only
        when the work is finished.]

        --- Task ---
        write unit test for this code
    """.trimIndent()

    /** What the page actually showed: echo, answer, echo again, answer again. */
    private val scraped = """
        I'd be happy to write unit tests, but I don't have the code snippet yet.
        when the work is finished.]
        --- Task ---
        write unit test for this code
        <br>I'd be happy to write unit tests, but I don't have the code snippet yet.
    """.trimIndent()

    @Test
    fun `removes the echoed task marker and reminder tail`() {
        val cleaned = CopilotBrowser.cleanReply(scraped, prompt)
        assertFalse(cleaned.contains("--- Task ---"), "our own task marker came back: $cleaned")
        assertFalse(cleaned.contains("when the work is finished"), "the reminder tail came back: $cleaned")
        assertFalse(cleaned.contains("write unit test for this code"), "our own request came back: $cleaned")
    }

    @Test
    fun `keeps the answer itself`() {
        assertTrue(CopilotBrowser.cleanReply(scraped, prompt).contains("I'd be happy to write unit tests"))
    }

    /**
     * The dangerous case: the prompt carries an example `readFile` call, and a
     * page that echoes it back would have it executed as if Copilot had asked.
     */
    @Test
    fun `an echoed example tool call is never parsed as a real one`() {
        val page = """
            Sure, here's how it works.

            ```tool
            {"tool": "readFile", "args": {"path": "src/Main.kt"}}
            ```
        """.trimIndent()
        val cleaned = CopilotBrowser.cleanReply(page, prompt)
        assertTrue(
            CopilotProtocol.parseCalls(cleaned).isEmpty(),
            "the example from our own prompt must not become a tool call: $cleaned",
        )
    }

    @Test
    fun `a genuine tool call from copilot survives, newlines and all`() {
        val page = """
            Looking at it now.

            ```tool
            {"tool": "listFiles", "args": {"path": "src"}}
            ```
        """.trimIndent()
        val cleaned = CopilotBrowser.cleanReply(page, prompt)
        assertEquals("listFiles", CopilotProtocol.parseCalls(cleaned).single().name)
    }

    @Test
    fun `collapses an answer the page rendered twice`() {
        val answer = "Hello! Test received successfully. How can I help you today?"
        assertEquals(answer, CopilotBrowser.dedupeHalves(answer + answer))
    }

    @Test
    fun `leaves a genuinely long answer alone`() {
        val answer = "First I read the file, then I changed the parser, then I ran the tests."
        assertEquals(answer, CopilotBrowser.dedupeHalves(answer))
    }

    @Test
    fun `an empty diff stays empty`() {
        assertEquals("", CopilotBrowser.cleanReply("", prompt))
    }
}

/**
 * A reply rendered twice must not act twice.
 *
 * A chat page shows the answer in the thread and again in a live region, so a
 * page-read reply can carry the same tool call two or three times — and writing
 * a file twice, or running a build twice, is not harmless.
 */
class DuplicateCallTest {

    @Test
    fun `the same call repeated in one reply runs once`() {
        val block = "```tool\n{\"tool\":\"listFiles\",\"args\":{\"path\":\".\"}}\n```"
        val calls = CopilotProtocol.parseCalls("Looking.\n$block\nLooking.\n$block")
        assertEquals(1, calls.size)
        assertEquals("listFiles", calls.single().name)
    }

    @Test
    fun `two genuinely different calls both run`() {
        val calls = CopilotProtocol.parseCalls(
            "```tool\n{\"tool\":\"readFile\",\"args\":{\"path\":\"a.kt\"}}\n```\n" +
                "```tool\n{\"tool\":\"readFile\",\"args\":{\"path\":\"b.kt\"}}\n```",
        )
        assertEquals(2, calls.size)
    }
}

/**
 * A reply read back off the rendered page has had its newlines collapsed, so a
 * tool fence arrives on a single line. It still has to be recognised — parsed
 * as a call, and kept out of the transcript.
 */
class SingleLineFenceTest {

    private val reply = """Looking at the project. ```tool {"tool": "listFiles", "args": {"path": "."}} ```"""

    @Test
    fun `a fence on one line is still a tool call`() {
        assertEquals("listFiles", CopilotProtocol.parseCalls(reply).single().name)
    }

    @Test
    fun `a fence on one line is hidden from the transcript`() {
        val out = StringBuilder()
        val filter = com.chelayel.airelay.copilot.agent.ToolBlockFilter { out.append(it) }
        filter.accept(reply)
        filter.finish()
        assertFalse(out.contains("listFiles"), "the tool call leaked into the transcript: $out")
        assertTrue(out.contains("Looking at the project."))
    }

    @Test
    fun `an ordinary one-line code fence still shows`() {
        val out = StringBuilder()
        val filter = com.chelayel.airelay.copilot.agent.ToolBlockFilter { out.append(it) }
        filter.accept("Try this: ```kotlin val x = 1 ``` done.")
        filter.finish()
        assertTrue(out.contains("val x = 1"))
    }
}
