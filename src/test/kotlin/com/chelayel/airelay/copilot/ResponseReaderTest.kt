package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.api.CopilotException
import com.chelayel.airelay.copilot.api.CopilotTurn
import com.chelayel.airelay.copilot.api.ResponseReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResponseReaderTest {

    private fun read(
        body: String,
        contentType: String,
        streamed: StringBuilder = StringBuilder(),
    ): CopilotTurn = ResponseReader(emptyList()).read(
        body.byteInputStream(),
        contentType,
        priorConversationId = null,
    ) { streamed.append(it) }

    @Test
    fun `reads an sse stream of cumulative frames`() {
        val streamed = StringBuilder()
        val turn = read(
            listOf(
                """data: {"conversationId":"c-7"}""",
                """data: {"text":"Hel"}""",
                """data: {"text":"Hello"}""",
                """data: {"text":"Hello world"}""",
                "data: [DONE]",
            ).joinToString("\n\n", postfix = "\n\n"),
            "text/event-stream; charset=utf-8",
            streamed,
        )
        assertEquals("Hello world", turn.text)
        assertEquals("Hello world", streamed.toString(), "text should stream out as it arrives")
        assertEquals("c-7", turn.conversationId)
    }

    @Test
    fun `skips sse events that are not assistant prose`() {
        val turn = read(
            listOf(
                """data: {"type":"Citation","text":"https://example.invalid"}""",
                """data: {"text":"the answer"}""",
            ).joinToString("\n\n", postfix = "\n\n"),
            "text/event-stream",
        )
        assertEquals("the answer", turn.text)
    }

    @Test
    fun `reads newline-delimited json`() {
        val turn = read(
            """{"delta":{"content":"one "}}""" + "\n" + """{"delta":{"content":"two"}}""",
            "application/x-ndjson",
        )
        assertEquals("one two", turn.text)
    }

    @Test
    fun `reads a single json document`() {
        val turn = read(
            """{"conversationId":"c-9","message":{"role":"assistant","content":"the answer"}}""",
            "application/json",
        )
        assertEquals("the answer", turn.text)
        assertEquals("c-9", turn.conversationId)
    }

    @Test
    fun `reads a plain text body as the answer`() {
        val body = "just prose\nover two lines"
        assertEquals(body, read(body, "text/plain").text)
    }

    /**
     * Regression: prose containing a JSON object on its own line is an answer
     * with a code block in it, not a JSON stream. Treating it as a stream made
     * the whole reply vanish — and a tool call is exactly that shape, so every
     * agent turn over a text/plain endpoint was lost.
     */
    @Test
    fun `keeps plain text that contains a json line`() {
        val call = """{"tool": "writeFile", "args": {"path": "a.txt"}}"""
        val body = "Writing it.\n\n```tool\n$call\n```\n"
        assertEquals(body, read(body, "text/plain").text)
    }

    @Test
    fun `still reads an undeclared json stream where every line is json`() {
        val turn = read("""{"text":"a"}""" + "\n" + """{"text":"ab"}""", "text/plain")
        assertEquals("ab", turn.text)
    }

    @Test
    fun `surfaces an error object rather than returning empty`() {
        val e = assertFailsWith<CopilotException> {
            read("""{"error":{"message":"quota exceeded"}}""", "application/json")
        }
        assertTrue(e.message!!.contains("quota exceeded"))
    }

    @Test
    fun `keeps a raw sample when nothing parses as text`() {
        val turn = read("""{"unknownField":{"nested":123}}""", "application/json")
        assertEquals("", turn.text)
        assertTrue(turn.rawSample.contains("unknownField"), "the sample is what tells the user which key to configure")
    }

    @Test
    fun `an empty response yields empty text rather than throwing`() {
        assertEquals("", read("", "application/json").text)
    }
}
