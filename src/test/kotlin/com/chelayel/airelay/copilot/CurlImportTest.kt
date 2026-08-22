package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.api.CurlImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurlImportTest {

    @Test
    fun `parses a chrome bash capture`() {
        val captured = CurlImport.parse(
            """
            curl 'https://copilot.example.com/api/chat?api-version=2' \
              -H 'authorization: Bearer abc.def.ghi' \
              -H 'content-type: application/json' \
              -H 'accept: text/event-stream' \
              --data-raw '{"message":"hello there","modelId":"gpt-5.5"}' \
              --compressed
            """.trimIndent(),
        )

        assertEquals("https://copilot.example.com/api/chat?api-version=2", captured.url)
        assertEquals("POST", captured.method)
        assertEquals("Bearer abc.def.ghi", captured.headers["authorization"])
        assertEquals("application/json", captured.headers["content-type"])
        assertEquals("""{"message":"hello there","modelId":"gpt-5.5"}""", captured.body)
        assertEquals("authorization", captured.authHeaderName())
    }

    @Test
    fun `decodes ansi-c quoting used for bodies with newlines`() {
        val captured = CurlImport.parse(
            """curl 'https://x.test/c' --data-raw ${'$'}'{"m":"line1\nline2\ttabbed é"}'""",
        )
        assertEquals("""{"m":"line1${'\n'}line2${'\t'}tabbed é"}""", captured.body)
    }

    @Test
    fun `parses a cmd capture with caret continuations and escaped quotes`() {
        val captured = CurlImport.parse(
            "curl \"https://x.test/chat\" ^\n" +
                "  -H \"authorization: Bearer tok\" ^\n" +
                "  --data-raw \"{\\\"message\\\":\\\"hi\\\"}\"",
        )
        assertEquals("https://x.test/chat", captured.url)
        assertEquals("Bearer tok", captured.headers["authorization"])
        assertEquals("""{"message":"hi"}""", captured.body)
    }

    @Test
    fun `merges the cookie flag into a cookie header`() {
        val captured = CurlImport.parse(
            "curl 'https://x.test/c' -H 'cookie: a=1' -b 'b=2' -b 'c=3' --data-raw 'x'",
        )
        assertEquals("a=1; b=2; c=3", captured.headers["cookie"])
        assertEquals("cookie", captured.authHeaderName())
    }

    @Test
    fun `drops headers the jdk manages and http2 pseudo-headers`() {
        val captured = CurlImport.parse(
            """
            curl 'https://x.test/c' \
              -H ':authority: x.test' \
              -H 'Host: x.test' \
              -H 'Content-Length: 12' \
              -H 'accept-encoding: gzip, deflate, br' \
              -H 'x-keep: yes' \
              --data-raw 'x'
            """.trimIndent(),
        )
        assertEquals(setOf("x-keep"), captured.headers.keys)
        assertFalse(captured.headers.keys.any { it.startsWith(":") })
    }

    @Test
    fun `honours an explicit method and a bodyless request`() {
        val captured = CurlImport.parse("curl -X GET 'https://x.test/list' -H 'authorization: Bearer t'")
        assertEquals("GET", captured.method)
        assertEquals(null, captured.body)
    }

    @Test
    fun `does not mistake a flag argument for the url`() {
        val captured = CurlImport.parse("curl -o /tmp/out.json 'https://x.test/c' --data-raw 'x'")
        assertEquals("https://x.test/c", captured.url)
    }

    @Test
    fun `rejects a paste with no url`() {
        val e = assertFailsWith<CurlImport.ParseException> {
            CurlImport.parse("curl -H 'authorization: Bearer t'")
        }
        assertTrue(e.message!!.contains("No URL"))
    }

    @Test
    fun `rejects a non-http url`() {
        assertFailsWith<CurlImport.ParseException> { CurlImport.parse("curl 'ftp://x.test/c'") }
    }
}
