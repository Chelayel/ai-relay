package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.api.CopilotBrowser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Browser mode reads the answer off the frames the page's own WebSocket
 * receives. SignalR — which is what M365 Copilot's socket looks like — packs
 * several messages into one frame, so a frame is not always one message.
 */
class BrowserFrameTest {

    private val sep = CopilotBrowser.RECORD_SEPARATOR

    @Test
    fun `splits a frame carrying several signalr messages`() {
        val payload = """{"type":6}""" + sep + """{"type":1,"text":"hello"}""" + sep
        assertEquals(
            listOf("""{"type":6}""", """{"type":1,"text":"hello"}"""),
            CopilotBrowser.splitFrames(payload),
        )
    }

    @Test
    fun `a frame with no separator comes back as itself`() {
        assertEquals(listOf("""{"text":"hi"}"""), CopilotBrowser.splitFrames("""{"text":"hi"}"""))
    }

    @Test
    fun `blank and separator-only frames yield nothing`() {
        assertEquals(emptyList(), CopilotBrowser.splitFrames(""))
        assertEquals(emptyList(), CopilotBrowser.splitFrames("$sep$sep"))
    }
}

/**
 * Verifying that a message actually landed in the composer.
 *
 * Turn one of a browser session worked and turn two did not: the tool-result
 * message is multi-line, a contenteditable reflows newlines into its own
 * markup, and a character-exact comparison therefore never matched — so it
 * retried until it gave up, reporting that it could not find the message box it
 * had in fact found and typed into.
 */
class ComposerVerifyTest {

    @Test
    fun `accepts text a contenteditable reflowed`() {
        val sent = "Tool results:\n\n[listFiles .]\na.txt\n"
        val readBack = "Tool results:\n[listFiles .] a.txt"
        assertTrue(CopilotBrowser.landed(sent, readBack))
    }

    @Test
    fun `accepts a plain single-line message`() {
        assertTrue(CopilotBrowser.landed("hello there", "hello there"))
    }

    @Test
    fun `accepts a box that reflowed a blank line into extra newlines`() {
        assertTrue(CopilotBrowser.landed("You are AI Relay.\n\nDo the thing.", "You are AI Relay.\n\n\nDo the thing."))
    }

    @Test
    fun `rejects an empty box`() {
        assertFalse(CopilotBrowser.landed("Tool results:\n\n[listFiles .]", ""))
    }

    @Test
    fun `rejects a box holding something else entirely`() {
        assertFalse(CopilotBrowser.landed("Tool results:\n\n[listFiles .]", "Ask me anything"))
    }
}

/**
 * Cleaning the page-text fallback, used when the answer can't be read off the
 * socket.
 *
 * A chat page prints the message it was just given and often renders the reply
 * twice — once in the thread and once in a live region. Left alone, that diff
 * came back as our own prompt, and the tool parser then executed the example
 * call out of the instructions it had just echoed.
 */
class PageTextTest {

    @Test
    fun `strips a verbatim echo and keeps the newlines after it`() {
        val prompt = "You are AI Relay.\n\n--- Task ---\nlist the files"
        val page = "$prompt\nLooking.\n\n```tool\n{\"tool\":\"listFiles\"}\n```"
        val cleaned = CopilotBrowser.cleanPageText(page, prompt)
        assertFalse(cleaned.contains("AI Relay"), "the echo of our own prompt must go")
        assertTrue(cleaned.contains("\n"), "newlines must survive — the tool fence needs them")
        assertTrue(cleaned.startsWith("Looking."))
    }

    @Test
    fun `strips an echo the page reflowed`() {
        val prompt = "You are AI Relay.\n\n--- Task ---\ntest"
        val page = "You are AI Relay. --- Task --- test Hello! Test received successfully."
        assertEquals("Hello! Test received successfully.", CopilotBrowser.cleanPageText(page, prompt))
    }

    @Test
    fun `collapses an answer the page rendered twice`() {
        val answer = "Hello! Test received successfully. How can I help you today?"
        assertEquals(answer, CopilotBrowser.dedupeHalves("$answer$answer"))
    }

    @Test
    fun `leaves a genuinely long answer alone`() {
        val answer = "First I read the file, then I changed the parser, then I ran the tests."
        assertEquals(answer, CopilotBrowser.dedupeHalves(answer))
    }

    @Test
    fun `an empty diff stays empty`() {
        assertEquals("", CopilotBrowser.cleanPageText("", "anything"))
    }
}
