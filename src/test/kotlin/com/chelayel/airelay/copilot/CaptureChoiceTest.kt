package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.api.BrowserCapture
import com.chelayel.airelay.copilot.api.CurlImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Choosing which captured request is the one that answers.
 *
 * A Copilot page sends the typed message to several endpoints at once, and they
 * are near-identical from the request side. Picking the first one to fire
 * captured a page-state endpoint whose reply is the app's store with the message
 * echoed back as a conversation title — so every turn "connected" and returned
 * no text. The decision has to be made on what each endpoint replied.
 */
class CaptureChoiceTest {

    private fun observed(
        path: String,
        mime: String,
        response: String = "",
    ): BrowserCapture.Observed = BrowserCapture.Observed(
        captured = CurlImport.Captured(
            url = "https://m365.cloud.microsoft$path",
            method = "POST",
            headers = LinkedHashMap(),
            body = """{"messages":[{"content":"airelay-1234"}]}""",
        ),
        responseMime = mime,
        responseSample = response,
    )

    /** The exact shape that was being mistaken for a chat endpoint. */
    private val pageState = observed(
        "/api/conversation/pagestate",
        "application/json",
        """{"store":{"gptId":"","conversationPageHistoryList":{"chats":[{"chatName":"airelay-1234"}]}}}""",
    )

    @Test
    fun `a streamed reply beats a page-state reply that fired first`() {
        val streaming = observed("/api/chat/stream", "text/event-stream", """data: {"text":"Hello"}""")
        assertTrue(
            streaming.score > pageState.score,
            "the endpoint that streams prose must win over the one returning the app's store",
        )
    }

    @Test
    fun `a page-state reply is ranked below a bare json reply of the same size`() {
        val plain = observed("/api/chat/send", "application/json", """{"reply":"Hello there, how can I help?"}""")
        assertTrue(plain.score > pageState.score)
    }

    @Test
    fun `telemetry and history endpoints rank low`() {
        val chat = observed("/api/chat/stream", "text/event-stream")
        for (path in listOf("/api/telemetry/log", "/api/conversation/history", "/api/presence/sync")) {
            assertTrue(observed(path, "application/json").score < chat.score, "$path should rank below chat")
        }
    }

    @Test
    fun `an html reply is never preferred`() {
        val html = observed("/chat", "text/html", "<!doctype html><title>Copilot</title>")
        val json = observed("/api/chat", "application/json", """{"reply":"hi there friend"}""")
        assertTrue(json.score > html.score)
    }

    @Test
    fun `ordering picks the streaming endpoint out of a realistic fan-out`() {
        val all = listOf(
            pageState,
            observed("/api/telemetry/log", ""),
            observed("/api/chat/stream", "text/event-stream", """data: {"text":"I am Copilot."}"""),
        ).sortedByDescending { it.score }
        assertEquals("/api/chat/stream", all.first().path)
    }

    @Test
    fun `path and host are read off the captured url`() {
        val o = observed("/api/chat/stream", "text/event-stream")
        assertEquals("/api/chat/stream", o.path)
        assertEquals("m365.cloud.microsoft", o.host)
    }

    /**
     * Regression from a real M365 capture: the composer sends every keystroke to
     * search autocomplete, so `/search/api/v1/suggestions` carries the message
     * and answers promptly with JSON. It outranked the actual chat endpoint,
     * whose page-state reply had been penalised. Autocomplete must never win.
     */
    @Test
    fun `search autocomplete never outranks a chat endpoint`() {
        val suggestions = observed(
            "/search/api/v1/suggestions",
            "application/json",
            """{"Groups":[{"Suggestions":[{"Text":"airelay-1234"}]}]}""",
        )
        val chat = observed("/chat", "application/json", """{"store":{"conversationPageHistoryList":{}}}""")
        assertTrue(suggestions.score < chat.score, "autocomplete must rank below the chat endpoint")
        assertTrue(suggestions.looksInert, "an autocomplete reply answers nothing")
    }

    @Test
    fun `bookkeeping and page-state replies are both marked inert`() {
        assertTrue(pageState.looksInert)
        assertTrue(observed("/api/telemetry/log", "application/json").looksInert)
    }

    @Test
    fun `a streamed reply is never inert`() {
        assertTrue(!observed("/api/chat/stream", "text/event-stream", "data: hi").looksInert)
        // Even on a path that would otherwise read as bookkeeping.
        assertTrue(!observed("/api/history/stream", "text/event-stream", "data: hi").looksInert)
    }
}
