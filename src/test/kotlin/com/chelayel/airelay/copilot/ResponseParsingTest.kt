package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.api.TextAssembler
import com.chelayel.airelay.copilot.api.TextExtractor
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponseParsingTest {

    private fun extract(json: String, extraKeys: List<String> = emptyList()): String? =
        TextExtractor(extraKeys).extract(JsonParser.parseString(json))

    // ---- pulling text out of an unknown chunk shape ---------------------------

    @Test
    fun `reads an openai-shaped delta`() {
        assertEquals("Hel", extract("""{"choices":[{"delta":{"content":"Hel"}}]}"""))
    }

    @Test
    fun `reads an event-wrapped payload`() {
        assertEquals("lo", extract("""{"event":"appendText","data":{"text":"lo"}}"""))
    }

    @Test
    fun `reads a message with an array of content parts`() {
        assertEquals(
            "one two",
            extract("""{"message":{"role":"assistant","content":[{"text":"one "},{"text":"two"}]}}"""),
        )
    }

    @Test
    fun `skips a chunk labelled as something other than prose`() {
        assertNull(extract("""{"type":"Citation","text":"https://example.com"}"""))
        assertNull(extract("""{"messageType":"InternalSearchQuery","text":"weather today"}"""))
        assertNull(extract("""{"type":"suggestedResponses","text":"Tell me more"}"""))
    }

    @Test
    fun `skips a message echoed back with a non-assistant role`() {
        assertNull(extract("""{"message":{"role":"user","content":"my prompt"}}"""))
    }

    @Test
    fun `honours a configured extra key`() {
        assertNull(extract("""{"speak":"hello"}"""))
        assertEquals("hello", extract("""{"speak":"hello"}""", listOf("speak")))
    }

    @Test
    fun `skips empty strings and keeps looking`() {
        assertEquals("real", extract("""{"text":"","content":"real"}"""))
    }

    // ---- delta vs cumulative streams ------------------------------------------

    @Test
    fun `appends plain deltas`() {
        val out = StringBuilder()
        val a = TextAssembler { out.append(it) }
        listOf("Hel", "lo ", "world").forEach { a.offer(it) }
        assertEquals("Hello world", out.toString())
        assertEquals("Hello world", a.text())
    }

    @Test
    fun `emits only the tail of a cumulative stream`() {
        val out = StringBuilder()
        val a = TextAssembler { out.append(it) }
        listOf("Hel", "Hello", "Hello world").forEach { a.offer(it) }
        assertEquals("Hello world", out.toString())
        assertEquals("Hello world", a.text())
    }

    @Test
    fun `ignores an exactly repeated chunk`() {
        val out = StringBuilder()
        val a = TextAssembler { out.append(it) }
        listOf("Hello", "Hello").forEach { a.offer(it) }
        assertEquals("Hello", out.toString())
    }

    @Test
    fun `keeps a delta that repeats the preceding word`() {
        val out = StringBuilder()
        val a = TextAssembler { out.append(it) }
        listOf("that that ", "that ", "is").forEach { a.offer(it) }
        assertEquals("that that that is", out.toString())
    }

    @Test
    fun `keeps a delta that happens to repeat the tail`() {
        val out = StringBuilder()
        val a = TextAssembler { out.append(it) }
        listOf("go ", "go", "ing").forEach { a.offer(it) }
        assertEquals("go going", out.toString())
    }
}
