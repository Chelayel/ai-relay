package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.api.CopilotDiagnosis
import com.chelayel.airelay.copilot.api.ResponseReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The survey behind `airelay copilot diagnose`. It exists for the case the
 * extractor's guesses miss: it has to name the right field from the response
 * alone, with no knowledge of the tenant's shape.
 */
class ResponseSurveyTest {

    private fun survey(body: String, contentType: String = "text/event-stream"): CopilotDiagnosis =
        ResponseReader(emptyList()).survey(body.byteInputStream(), contentType)

    private fun sse(vararg payloads: String) = payloads.joinToString("\n\n", postfix = "\n\n") { "data: $it" }

    @Test
    fun `picks prose over ids, urls and enum labels`() {
        val diagnosis = survey(
            sse(
                """{"kind":"telemetry","correlationId":"8f14e45f-ceea-467a-9f37-9c2d9dbdbf1a"}""",
                """{"kind":"reference","url":"https://example.invalid/doc/1"}""",
                """{"kind":"turn","author":"copilot","spokenText":"I am Microsoft "}""",
                """{"kind":"turn","author":"copilot","spokenText":"I am Microsoft 365 Copilot, your assistant."}""",
            ),
        )
        assertEquals("spokenText", diagnosis.candidates.first().key)
    }

    @Test
    fun `keeps the longest cumulative frame rather than concatenating repeats`() {
        val diagnosis = survey(
            sse(
                """{"say":"Hel"}""",
                """{"say":"Hello"}""",
                """{"say":"Hello world"}""",
            ),
        )
        val best = diagnosis.candidates.first()
        assertEquals("say", best.key)
        assertEquals("Hello world", best.text)
        assertEquals(3, best.chunks)
    }

    @Test
    fun `reports where a nested field sits, with array indices collapsed`() {
        val diagnosis = survey(
            """{"result":{"messages":[{"body":"a sentence of prose here"}]}}""",
            "application/json",
        )
        val best = diagnosis.candidates.first()
        assertEquals("body", best.key, "the key is what goes into copilot.text.keys")
        assertEquals("result/messages[]/body", best.path)
    }

    @Test
    fun `keeps the raw response for writing to a file`() {
        val diagnosis = survey(sse("""{"say":"hi there"}"""))
        assertTrue(diagnosis.raw.contains("hi there"))
    }

    @Test
    fun `a plain text body is offered as the whole body`() {
        val diagnosis = survey("just the answer, in prose", "text/plain")
        assertEquals("(whole body)", diagnosis.candidates.first().key)
    }

    @Test
    fun `an empty response yields no candidates rather than throwing`() {
        assertTrue(survey("", "application/json").candidates.isEmpty())
    }

    /** A configured key must win even on a chunk the label filter would skip. */
    @Test
    fun `a configured key is read even from a chunk labelled as metadata`() {
        val turn = ResponseReader(listOf("spokenText")).read(
            sse("""{"kind":"metadata","spokenText":"the answer"}""").byteInputStream(),
            "text/event-stream",
            priorConversationId = null,
        ) {}
        assertEquals("the answer", turn.text)
    }
}
