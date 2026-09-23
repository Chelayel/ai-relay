package com.chelayel.airelay.gemini.agent

import com.chelayel.airelay.gemini.api.Content
import com.chelayel.airelay.gemini.api.Part
import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryCompactorTest {

    private fun result(text: String) = JsonObject().apply { addProperty("result", text) }
    private fun turn(i: Int) = listOf(
        Content("model", listOf(Part.FunctionCall("readFile", JsonObject()))),
        Content("user", listOf(Part.FunctionResponse("readFile", result("file $i\n" + "x".repeat(2000))))),
    )

    @Test fun `only tool results older than the last N are stubbed, and small ones never`() {
        val history = listOf(Content("user", listOf(Part.Text("task")))) + (1..5).flatMap(::turn) +
            listOf(Content("user", listOf(Part.FunctionResponse("listFiles", result("a.kt")))))
        val out = HistoryCompactor.compact(history, keepVerbatim = 2)
        val responses = out.flatMap { it.parts }.filterIsInstance<Part.FunctionResponse>()
        assertEquals(6, responses.size)
        for (r in responses.take(4)) assertTrue(r.response.has("summary"), r.response.toString())
        assertTrue(responses[4].response.has("result"))
        assertTrue(responses[5].response.has("result"))
        val stub = responses[0].response.get("summary").asString
        assertTrue(stub.startsWith("file 1 …"), stub)
        assertTrue(stub.contains("omitted from history"))
        assertTrue(history.flatMap { it.parts }.filterIsInstance<Part.FunctionResponse>().all { it.response.has("result") })
    }

    @Test fun `nothing changes when everything fits in the verbatim window`() {
        val history = (1..3).flatMap(::turn)
        assertEquals(history, HistoryCompactor.compact(history, keepVerbatim = 12))
    }
}
