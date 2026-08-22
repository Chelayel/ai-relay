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
