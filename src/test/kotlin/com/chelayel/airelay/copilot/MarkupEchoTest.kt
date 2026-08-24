package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.api.CopilotBrowser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Echo removal when the page renders a multi-line message with `<br>`.
 *
 * Against real M365 Copilot the whole prompt came back as the answer — the tool
 * catalogue, `--- Task ---`, and our own "hello" run into "Hello! How can I help
 * you with the AI Relay project?". The reason was mundane: the page separates
 * the lines of an echoed message with `<br>`, reading it back yields those tags
 * as literal text, and every line-wise comparison then misses.
 */
class MarkupEchoTest {

    private val prompt = """
        You are AI Relay (Copilot), a coding agent working directly in the user's project.

        - listFiles: List the files and directories directly inside a project directory.
        - runCommand: Run a shell command in the project directory and return its output.

        --- Task ---
        hello
    """.trimIndent()

    /** Shaped like the real page read: our message, tags and all, then the reply. */
    private val scraped = prompt.replace("\n", "<br>") + "Hello! How can I help you with the AI Relay project?"

    @Test
    fun `br tags become the newlines they stand for`() {
        assertEquals("one\ntwo", CopilotBrowser.unwrapMarkup("one<br>two"))
        assertEquals("one\ntwo", CopilotBrowser.unwrapMarkup("one<br/>two"))
        assertEquals("one\ntwo", CopilotBrowser.unwrapMarkup("one<BR />two"))
    }

    @Test
    fun `the echoed prompt no longer survives its markup`() {
        val cleaned = CopilotBrowser.cleanPageText(scraped, prompt)
        assertFalse(cleaned.contains("listFiles"), "the tool catalogue came back: $cleaned")
        assertFalse(cleaned.contains("--- Task ---"), "our task marker came back: $cleaned")
        assertFalse(cleaned.contains("<br>"), "markup came back: $cleaned")
    }

    @Test
    fun `the answer itself survives`() {
        val cleaned = CopilotBrowser.cleanPageText(scraped, prompt)
        assertTrue(
            cleaned.contains("How can I help you with the AI Relay project?"),
            "the answer was lost: $cleaned",
        )
    }

    /** "hello" ran straight into "Hello!" with no separator at all. */
    @Test
    fun `our own message does not stay glued to the front of the answer`() {
        val cleaned = CopilotBrowser.cleanPageText(scraped, prompt)
        assertFalse(cleaned.startsWith("hello"), "our prompt is still on the front: $cleaned")
    }

    @Test
    fun `text with no markup is left alone`() {
        assertEquals("plain answer", CopilotBrowser.unwrapMarkup("plain answer"))
    }
}

/**
 * Peeling our own message off the front of a reply.
 *
 * The page puts the message it was just given immediately before the answer,
 * frequently with nothing between them, so a one-word prompt arrives fused to
 * the first word of the reply.
 */
class EchoPrefixTest {

    private fun strip(line: String, vararg prompt: String) =
        CopilotBrowser.stripEchoPrefix(line, prompt.sortedByDescending { it.length })

    @Test
    fun `peels a short prompt off a reply it was fused to`() {
        assertEquals("Hello! How can I help?", strip("helloHello! How can I help?", "hello"))
    }

    @Test
    fun `peels a prompt followed by a space`() {
        assertEquals("Done.", strip("list the files Done.", "list the files"))
    }

    @Test
    fun `peels a prompt followed by punctuation`() {
        assertEquals(": nothing to change", strip("test: nothing to change", "test"))
    }

    /** The case that makes a blind prefix strip dangerous. */
    @Test
    fun `leaves a word that merely begins with the prompt`() {
        assertEquals("testing shows it passes", strip("testing shows it passes", "test"))
    }

    @Test
    fun `leaves a reply that never echoed anything`() {
        assertEquals("Nothing needed changing.", strip("Nothing needed changing.", "hello"))
    }

    @Test
    fun `prefers the longest matching echo`() {
        assertEquals("Done.", strip("write a testDone.", "test", "write a test"))
    }
}
