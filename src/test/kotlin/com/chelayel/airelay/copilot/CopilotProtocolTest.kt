package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.agent.CopilotProtocol
import com.chelayel.airelay.copilot.agent.ToolBlockFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CopilotProtocolTest {

    @Test
    fun `reads a tool call out of a tool fence`() {
        val calls = CopilotProtocol.parseCalls(
            """
            Let me look at that file.

            ```tool
            {"tool": "readFile", "args": {"path": "src/Main.kt"}}
            ```
            """.trimIndent(),
        )
        assertEquals(1, calls.size)
        assertEquals("readFile", calls[0].name)
        assertEquals("src/Main.kt", calls[0].args.get("path").asString)
    }

    @Test
    fun `reads several calls in one reply, in order`() {
        val calls = CopilotProtocol.parseCalls(
            """
            ```tool
            {"tool": "listFiles", "args": {"path": "src"}}
            ```
            ```tool
            {"tool": "searchFiles", "args": {"pattern": "TODO"}}
            ```
            """.trimIndent(),
        )
        assertEquals(listOf("listFiles", "searchFiles"), calls.map { it.name })
    }

    @Test
    fun `keeps braces inside a writeFile argument intact`() {
        val content = "fun main() {\n    println(\"{}\")\n}"
        val json = com.google.gson.JsonObject().apply {
            addProperty("tool", "writeFile")
            add("args", com.google.gson.JsonObject().apply {
                addProperty("path", "A.kt")
                addProperty("content", content)
            })
        }
        val calls = CopilotProtocol.parseCalls("```tool\n$json\n```")
        assertEquals(1, calls.size)
        assertEquals(content, calls[0].args.get("content").asString)
    }

    @Test
    fun `accepts a json fence and the alternate key names`() {
        val calls = CopilotProtocol.parseCalls(
            """
            ```json
            {"name": "runCommand", "arguments": {"command": "./gradlew test"}}
            ```
            """.trimIndent(),
        )
        assertEquals("runCommand", calls.single().name)
        assertEquals("./gradlew test", calls.single().args.get("command").asString)
    }

    @Test
    fun `finds a bare tool object when the model forgets the fence`() {
        val calls = CopilotProtocol.parseCalls("""Sure. {"tool":"readFile","args":{"path":"a.txt"}}""")
        assertEquals("readFile", calls.single().name)
    }

    @Test
    fun `ignores ordinary code fences`() {
        val calls = CopilotProtocol.parseCalls(
            """
            Here's the fix:

            ```kotlin
            val tool = "readFile"
            ```
            """.trimIndent(),
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `strips tool blocks but keeps shown code`() {
        val stripped = CopilotProtocol.stripToolBlocks(
            """
            Reading it now.

            ```tool
            {"tool":"readFile","args":{"path":"a.txt"}}
            ```

            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        assertTrue(!stripped.contains("readFile"))
        assertTrue(stripped.contains("val x = 1"))
    }

    // ---- streaming filter ----------------------------------------------------

    /** Feed [text] through the filter one character at a time, worst case for a state machine. */
    private fun filterCharByChar(text: String): String {
        val out = StringBuilder()
        val filter = ToolBlockFilter { out.append(it) }
        for (c in text) filter.accept(c.toString())
        filter.finish()
        return out.toString()
    }

    private fun filterInChunks(text: String, size: Int): String {
        val out = StringBuilder()
        val filter = ToolBlockFilter { out.append(it) }
        var i = 0
        while (i < text.length) {
            val end = minOf(i + size, text.length)
            filter.accept(text.substring(i, end))
            i = end
        }
        filter.finish()
        return out.toString()
    }

    @Test
    fun `filter hides a tool block however the deltas are split`() {
        val text = "Reading it.\n\n```tool\n{\"tool\":\"readFile\",\"args\":{\"path\":\"a.txt\"}}\n```\n"
        for (size in listOf(1, 2, 3, 7, 40, 500)) {
            val shown = filterInChunks(text, size)
            assertTrue(!shown.contains("readFile"), "leaked the tool call at chunk size $size: $shown")
            assertTrue(shown.contains("Reading it."), "lost prose at chunk size $size")
        }
    }

    @Test
    fun `filter passes ordinary fenced code straight through`() {
        val text = "Try this:\n\n```kotlin\nval x = 1\n```\n\nDone.\n"
        assertEquals(text, filterCharByChar(text))
    }

    @Test
    fun `filter emits everything when there is no fence at all`() {
        val text = "Just a plain answer with a stray backtick ` in it.\n"
        assertEquals(text, filterCharByChar(text))
    }

    @Test
    fun `filter drops an unterminated tool block at end of turn`() {
        val shown = filterCharByChar("Working.\n\n```tool\n{\"tool\":\"readFile\"")
        assertTrue(shown.contains("Working."))
        assertTrue(!shown.contains("readFile"))
    }

    @Test
    fun `filter keeps prose that follows a tool block`() {
        val text = "First.\n```tool\n{\"tool\":\"listFiles\",\"args\":{}}\n```\nSecond.\n"
        val shown = filterCharByChar(text)
        assertTrue(shown.contains("First."))
        assertTrue(shown.contains("Second."))
        assertTrue(!shown.contains("listFiles"))
    }
}
