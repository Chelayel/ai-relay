package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.api.BodyTemplate
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyTemplateTest {

    @Test
    fun `finds the prompt in a nested chat envelope and rewrites it`() {
        val body = """
            {"conversationId":"c-1","modelId":"gpt-5.5",
             "messages":[{"role":"user","content":"summarise this repo"}]}
        """.trimIndent()

        val template = assertNotNull(BodyTemplate.from(body, "summarise this repo"))
        assertEquals("messages/0/content", template.promptPath)
        assertEquals("modelId", template.modelPath)
        assertEquals("conversationId", template.conversationPath)
        assertEquals("gpt-5.5", template.capturedModel())
        assertEquals("c-1", template.capturedConversationId())

        val rendered = JsonParser.parseString(
            template.render("run the tests", "claude-opus-5", "c-2"),
        ).asJsonObject
        assertEquals(
            "run the tests",
            rendered.getAsJsonArray("messages")[0].asJsonObject.get("content").asString,
        )
        assertEquals("claude-opus-5", rendered.get("modelId").asString)
        assertEquals("c-2", rendered.get("conversationId").asString)
    }

    @Test
    fun `prefers the shallowest exact match over a deeper one`() {
        val body = """{"text":"hi","meta":{"echo":{"text":"hi"}}}"""
        val template = assertNotNull(BodyTemplate.from(body, "hi"))
        assertEquals("text", template.promptPath)
    }

    @Test
    fun `falls back to a field that merely contains the typed text`() {
        val body = """{"prompt":"<p>hello world</p>"}"""
        val template = assertNotNull(BodyTemplate.from(body, "hello world"))
        assertEquals("prompt", template.promptPath)
    }

    @Test
    fun `leaves the captured model in place when no model is selected`() {
        val body = """{"message":"hi","model":"auto"}"""
        val template = assertNotNull(BodyTemplate.from(body, "hi"))
        val rendered = JsonParser.parseString(template.render("go", null, null)).asJsonObject
        assertEquals("auto", rendered.get("model").asString)
        assertEquals("go", rendered.get("message").asString)
    }

    @Test
    fun `escapes json metacharacters in the new prompt`() {
        val body = """{"message":"hi"}"""
        val template = assertNotNull(BodyTemplate.from(body, "hi"))
        val awkward = "say \"hello\"\nand a backslash \\ too"
        val rendered = JsonParser.parseString(template.render(awkward, null, null)).asJsonObject
        assertEquals(awkward, rendered.get("message").asString)
    }

    @Test
    fun `handles a form-encoded body by placeholder substitution`() {
        val body = "q=hello+world&model=auto"
        val template = assertNotNull(BodyTemplate.from(body, "hello world"))
        assertTrue(!template.isJson)
        assertTrue(template.raw.contains(BodyTemplate.PROMPT_PLACEHOLDER))
        assertEquals("q=a%2Bb&model=auto", template.render("a+b", null, null))
    }

    @Test
    fun `returns null when the typed text is not in the capture`() {
        assertNull(BodyTemplate.from("""{"message":"something else"}""", "hi there"))
    }

    @Test
    fun `ignores a model field that is not a plain string`() {
        val body = """{"message":"hi","model":{"id":"x"}}"""
        val template = assertNotNull(BodyTemplate.from(body, "hi"))
        assertNull(template.modelPath)
    }

    @Test
    fun `render survives a path that no longer exists`() {
        val template = BodyTemplate.restore(
            raw = """{"message":"hi"}""",
            isJson = true,
            promptPath = "messages/4/content",
            modelPath = null,
            conversationPath = null,
        )
        assertEquals("""{"message":"hi"}""", template.render("go", null, null))
    }
}
