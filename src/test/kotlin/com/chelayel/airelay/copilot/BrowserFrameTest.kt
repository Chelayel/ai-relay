package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.api.CopilotBrowser
import kotlin.test.Test
import kotlin.test.assertEquals

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
