package com.chelayel.airelay.gemini.api

import com.chelayel.airelay.config.Config
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A gateway whose threat protection refuses any body with `<script>` in it, as
 * Apigee's RegularExpressionProtection does, in front of a Vertex that answers.
 */
class ThreatProtectionTest {

    private val bodies = java.util.Collections.synchronizedList(ArrayList<String>())
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/token") { ex ->
            val b = """{"access_token":"tok","expires_in":3600}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        createContext("/vertex") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            bodies.add(body)
            if ("/vertex/404" in ex.requestURI.path && ("<script>" in body || "/vertex/404/gone" in ex.requestURI.path)) {
                val fault = """{"error":{"status":"404","code":"404","message":"Resource not found/Invalid resource"}}""".toByteArray()
                ex.sendResponseHeaders(404, fault.size.toLong()); ex.responseBody.use { it.write(fault) }
                return@createContext
            }
            if ("<script>" in body) {
                val fault = """{"fault":{"faultstring":"Regular Expression Threat Detected in RE-Protect: regex: .*<script>.* input: <script>alert(1)</script>","detail":{"errorcode":"steps.regularexpressionprotection.ThreatDetected"}}}""".toByteArray()
                ex.sendResponseHeaders(403, fault.size.toLong()); ex.responseBody.use { it.write(fault) }
                return@createContext
            }
            val sse = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n".toByteArray()
            ex.responseHeaders.set("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, sse.size.toLong()); ex.responseBody.use { it.write(sse) }
        }
        start()
    }
    private val port = server.address.port

    @AfterTest fun stop() = server.stop(0)

    private fun client(encode: String? = null, path: String = "/vertex") = GeminiClient(GeminiConfig(Config.forTesting(buildMap {
        put("gemini.mode", "apigee"); put("vertex.project", "p"); put("vertex.base.url", "http://127.0.0.1:$port$path")
        put("apigee.token.url", "http://127.0.0.1:$port/token"); put("apigee.client.id", "id"); put("apigee.client.secret", "s")
        put("gemini.model", "gemini-3.8-flash"); encode?.let { put("apigee.encode.text", it) }
    })))

    private val scripted = listOf(Content("user", listOf(Part.Text("Why does <script>alert(1)</script> not run?"))))

    @Test
    fun `a refused request is sent again encoded, and later ones are encoded from the start`() {
        val turn = client(path = "/vertex/a").streamTurn(scripted, null, emptyList()) {}
        assertEquals("ok", turn.text)
        assertEquals(2, bodies.size)
        assertTrue("<script>" !in bodies[1] && "\"mimeType\":\"text/plain\"" in bodies[1], bodies[1])

        client(path = "/vertex/a").streamTurn(scripted, null, emptyList()) {}
        assertEquals(3, bodies.size, "the next request went encoded at once")
    }

    @Test
    fun `never says so and points at the setting, and the 403 is not taken for an expired token`() {
        val e = assertFailsWith<GeminiClient.GeminiException> { client("never", "/vertex/b").streamTurn(scripted, null, emptyList()) {} }
        assertEquals(403, e.status)
        assertTrue("threat protection" in e.message!! && "apigee.encode.text=auto" in e.message!!, e.message)
        assertTrue("alert(1)" !in e.message!!, "the matched input is not echoed")
        assertEquals(1, bodies.size, "no token-refresh retry")
    }

    @Test
    fun `the model's own words cannot be encoded, and the error says what to do`() {
        val history = listOf(
            Content("user", listOf(Part.Text("write a page"))),
            Content("model", listOf(Part.Text("Here: <script>go()</script>"))),
            Content("user", listOf(Part.Text("thanks"))),
        )
        val e = assertFailsWith<GeminiClient.GeminiException> { client(path = "/vertex/c").streamTurn(history, null, emptyList()) {} }
        assertTrue("/new" in e.message!!, e.message)
        assertEquals(2, bodies.size)
    }

    @Test
    fun `encoding carries text and tool output as documents and leaves the model's turns alone`() {
        val call = Part.FunctionCall("readFile", JsonObject().apply { addProperty("path", "a.html") }, "sig")
        val encoded = ThreatProtection.encode(listOf(
            Content("user", listOf(Part.Text("look at a.html"))),
            Content("model", listOf(call)),
            Content("user", listOf(Part.FunctionResponse("readFile", JsonObject().apply { addProperty("result", "<script>x()</script>") }))),
        ))
        assertEquals(call, encoded[1].parts.single())
        // Gemini 3 refuses (500) a tool-result turn holding anything else, so the documents follow in their own turn.
        val reply = encoded[2].parts.single()
        assertTrue(reply is Part.FunctionResponse && "<script>" !in reply.response.toString())
        assertEquals("user", encoded[3].role)
        val doc = encoded[3].parts.filterIsInstance<Part.InlineData>().single()
        assertEquals("text/plain", doc.mimeType)
        assertEquals("<script>x()</script>", String(Base64.getDecoder().decode(doc.dataBase64)))
        assertEquals("look at a.html", String(Base64.getDecoder().decode((encoded[0].parts.last() as Part.InlineData).dataBase64)))
    }

    @Test
    fun `a gateway that answers a blocked body with a 404 is sent it again encoded`() {
        val turn = client(path = "/vertex/404/a").streamTurn(scripted, null, emptyList()) {}
        assertEquals("ok", turn.text)
        assertEquals(2, bodies.size)
        assertTrue("<script>" !in bodies[1], bodies[1])
    }

    @Test
    fun `a 404 cured by encoding does not leave the endpoint encoding`() {
        client(path = "/vertex/404/b").streamTurn(scripted, null, emptyList()) {}
        val plain = listOf(Content("user", listOf(Part.Text("hello"))))
        client(path = "/vertex/404/b").streamTurn(plain, null, emptyList()) {}
        assertEquals(3, bodies.size)
        assertTrue("\"text\":\"hello\"" in bodies[2], "a stray 404 proves nothing; the next request goes plain: ${bodies[2]}")
    }

    @Test
    fun `a 404 that encoding does not cure is reported as a 404`() {
        val e = assertFailsWith<GeminiClient.GeminiException> { client(path = "/vertex/404/gone").streamTurn(scripted, null, emptyList()) {} }
        assertEquals(404, e.status)
        assertTrue("threat protection" !in e.message!! && "start a new conversation" in e.message!!, e.message)
        assertEquals(2, bodies.size, "one encoded retry, no more")
    }

    @Test
    fun `only threat-protection faults are recognised`() {
        assertNull(ThreatProtection.detect(400, """{"error":{"message":"Invalid argument"}}"""))
        assertNull(ThreatProtection.detect(500, """{"fault":{"faultstring":"Threat Detected"}}"""))
        assertEquals("steps.jsonthreatprotection.ExecutionFailed: JSONThreatProtection[JTP]: Execution failed",
            ThreatProtection.detect(400, """{"fault":{"faultstring":"JSONThreatProtection[JTP]: Execution failed","detail":{"errorcode":"steps.jsonthreatprotection.ExecutionFailed"}}}"""))
    }
}
