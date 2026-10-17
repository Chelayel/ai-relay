package com.chelayel.airelay.gemini.api

import com.chelayel.airelay.config.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class GeminiClientSseTest {

    private val client = GeminiClient(GeminiConfig(Config.forTesting(emptyMap())))

    /** A stream the server never closes, as a gateway may hold it open. */
    private fun heldOpen(vararg lines: String): Pair<BufferedReader, PipedOutputStream> {
        val out = PipedOutputStream()
        val reader = BufferedReader(InputStreamReader(PipedInputStream(out, 1 shl 16), Charsets.UTF_8))
        lines.forEach { out.write("$it\n\n".toByteArray()) }
        out.flush()
        return reader to out
    }

    @Test
    fun `a turn ends at the finishReason chunk even if the stream stays open`() {
        val (reader, out) = heldOpen(
            """data: {"candidates":[{"content":{"parts":[{"text":"Done, "}]}}]}""",
            """data: {"candidates":[{"content":{"parts":[{"text":"fixed."}]},"finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":7}}""",
        )
        val turn = CompletableFuture.supplyAsync { client.parseSse(reader) {} }.get(5, TimeUnit.SECONDS)
        assertEquals("Done, fixed.", turn.text)
        assertEquals("STOP", turn.finishReason)
        out.close()
    }

    @Test
    fun `a finishReason chunk with no content still ends the turn`() {
        val (reader, out) = heldOpen(
            """data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"readFile","args":{"path":"a"}}}]}}]}""",
            """data: {"candidates":[{"finishReason":"STOP"}]}""",
        )
        val turn = CompletableFuture.supplyAsync { client.parseSse(reader) {} }.get(5, TimeUnit.SECONDS)
        assertEquals(listOf("readFile"), turn.functionCalls.map { it.name })
        out.close()
    }
}
