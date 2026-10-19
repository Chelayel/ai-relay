package com.chelayel.airelay.serve

import com.chelayel.airelay.config.Config
import com.chelayel.airelay.gemini.api.GeminiClient.GeminiException
import com.chelayel.airelay.gemini.api.ModelTurn
import com.chelayel.airelay.gemini.api.Part
import com.chelayel.airelay.gemini.api.Usage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The server end to end over real HTTP, with a scripted model in Gemini's place. */
class RelayServerTest {

    private val config = Config.forTesting(mapOf(
        "gemini.mode" to "apigee", "vertex.project" to "p", "vertex.endpoint" to "gw.example.com",
        "apigee.token.url" to "https://auth.example.com/token", "apigee.client.id" to "id", "apigee.client.secret" to "secret",
        "apigee.agents" to "gemini-3.8-flash,gemini-3.1-pro", "gemini.model" to "gemini-3.8-flash",
    ))
    private val seen = ArrayList<GeminiRequest>()
    private var script: ArrayDeque<(GeminiRequest, (String) -> Unit) -> ModelTurn> = ArrayDeque()

    private val server = RelayServer(0, TOKEN, loadConfig = { config }, log = {}, backend = { _, req, onText ->
        seen.add(req); script.removeFirst()(req, onText)
    })
    private val port = server.start()
    private val http = HttpClient.newHttpClient()

    @AfterTest fun stop() = server.stop()

    private fun get(path: String, token: String? = TOKEN, origin: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET()
        token?.let { b.header("Authorization", "Bearer $it") }
        origin?.let { b.header("Origin", it) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String, token: String? = TOKEN): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json")
        token?.let { b.header("Authorization", "Bearer $it") }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun sseEvents(body: String) = body.lines().filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }

    @Test
    fun `models need the token and are exactly the gateway's`() {
        assertEquals(200, get("/", token = null).statusCode())
        assertEquals(401, get("/v1/models", token = null).statusCode())
        assertEquals(401, get("/v1/models", token = "wrong").statusCode())
        val r = get("/v1/models")
        assertEquals(200, r.statusCode())
        val ids = JsonParser.parseString(r.body()).asJsonObject.getAsJsonArray("data").map { it.asJsonObject.get("id").asString }
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.1-pro"), ids)
    }

    @Test
    fun `a web page is refused, by origin and by a rebound host name`() {
        assertEquals(403, get("/v1/models", origin = "https://evil.example").statusCode())
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("GET /v1/models HTTP/1.1\r\nHost: evil.example:$port\r\nAuthorization: Bearer $TOKEN\r\nConnection: close\r\n\r\n".toByteArray())
            val status = s.getInputStream().bufferedReader().readLine()
            assertTrue(status.contains("403"), status)
        }
    }

    @Test
    fun `a streamed turn with a tool call, then the call comes back with its signature`() {
        script.add { _, onText ->
            onText("Hel"); onText("lo")
            ModelTurn(listOf(Part.Text("Hello"), Part.FunctionCall("read_file", JsonObject().apply { addProperty("path", "a.kt") }, "sig-1")), "STOP", Usage(10, 5, 15))
        }
        val first = post("/v1/chat/completions", """
            {"model":"gemini-3.8-flash","stream":true,"stream_options":{"include_usage":true},
             "messages":[{"role":"user","content":"read a.kt"}],
             "tools":[{"type":"function","function":{"name":"read_file","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}}]}
        """)
        assertEquals(200, first.statusCode())
        assertTrue(first.headers().firstValue("Content-Type").get().startsWith("text/event-stream"))
        val events = sseEvents(first.body())
        assertEquals("[DONE]", events.last())
        val chunks = events.dropLast(1).map { JsonParser.parseString(it).asJsonObject }
        assertEquals("assistant", chunks[0].getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta").get("role").asString)
        val text = chunks.mapNotNull { c -> c.getAsJsonArray("choices").firstOrNull()?.asJsonObject?.getAsJsonObject("delta")?.get("content")?.asString }.joinToString("")
        assertEquals("Hello", text)
        val call = chunks.firstNotNullOf { c -> c.getAsJsonArray("choices").firstOrNull()?.asJsonObject?.getAsJsonObject("delta")?.getAsJsonArray("tool_calls") }[0].asJsonObject
        val callId = call.get("id").asString
        assertEquals("read_file", call.getAsJsonObject("function").get("name").asString)
        assertEquals("tool_calls", chunks.firstNotNullOf { c -> c.getAsJsonArray("choices").firstOrNull()?.asJsonObject?.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString })
        assertEquals(15, chunks.last().getAsJsonObject("usage").get("total_tokens").asInt)

        script.add { _, _ -> ModelTurn(listOf(Part.Text("It is empty.")), "STOP", null) }
        val second = post("/v1/chat/completions", """
            {"model":"gemini-3.8-flash","messages":[{"role":"user","content":"read a.kt"},
             {"role":"assistant","content":"Hello","tool_calls":[{"id":"$callId","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.kt\"}"}}]},
             {"role":"tool","tool_call_id":"$callId","content":""}]}
        """)
        assertEquals(200, second.statusCode())
        assertEquals("It is empty.", JsonParser.parseString(second.body()).asJsonObject.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString)
        val echoed = seen.last().contents[1].parts.filterIsInstance<Part.FunctionCall>().single()
        assertEquals("sig-1", echoed.thoughtSignature)
        assertEquals("read_file", (seen.last().contents[2].parts.single() as Part.FunctionResponse).name)
    }

    @Test
    fun `a deployment URL works whether or not the client appends the path, and Azure-style`() {
        repeat(3) { script.add { _, _ -> ModelTurn(listOf(Part.Text("ok")), "STOP", null) } }
        val body = """{"model":"gemini-3.8-flash","messages":[{"role":"user","content":"x"}]}"""
        assertEquals(200, post("/v1/chat/completions/chat/completions", body).statusCode())
        assertEquals(200, post("/chat/completions", body).statusCode())
        val azure = post("/openai/deployments/gemini-3.1-pro/chat/completions?api-version=2024-10-21", """{"messages":[{"role":"user","content":"x"}]}""")
        assertEquals(200, azure.statusCode())
        assertEquals("gemini-3.1-pro", seen.last().model)
    }

    @Test
    fun `a model the gateway does not publish is a 404 naming the ones it does`() {
        val r = post("/v1/chat/completions", """{"model":"gpt-9","messages":[{"role":"user","content":"x"}]}""")
        assertEquals(404, r.statusCode())
        assertTrue(r.body().contains("gemini-3.8-flash"))
    }

    @Test
    fun `an upstream failure before any output keeps its status, streamed or not`() {
        script.add { _, _ -> throw GeminiException("Gemini request failed (HTTP 429): quota", 429) }
        assertEquals(429, post("/v1/chat/completions", """{"model":"gemini-3.8-flash","messages":[{"role":"user","content":"x"}]}""").statusCode())
        script.add { _, _ -> throw GeminiException("Gemini request failed (HTTP 500): boom", 500) }
        val streamed = post("/v1/chat/completions", """{"model":"gemini-3.8-flash","stream":true,"messages":[{"role":"user","content":"x"}]}""")
        assertEquals(502, streamed.statusCode())
        assertTrue(streamed.body().contains("boom"))
    }

    @Test
    fun `an unreachable gateway is a 502 that says so, not a dropped connection`() {
        script.add { _, _ -> throw java.net.UnknownHostException("auth.invalid") }
        val r = post("/v1/chat/completions", """{"model":"gemini-3.8-flash","stream":true,"messages":[{"role":"user","content":"x"}]}""")
        assertEquals(502, r.statusCode())
        assertTrue(r.body().contains("UnknownHostException") && r.body().contains("auth.invalid"), r.body())
    }

    @Test
    fun `a refused tool schema is retried as JSON Schema`() {
        script.add { _, _ -> throw GeminiException("Gemini request failed (HTTP 400): Invalid JSON payload at function_declarations[0].parameters", 400) }
        script.add { _, _ -> ModelTurn(listOf(Part.Text("ok")), "STOP", null) }
        val r = post("/v1/chat/completions", """
            {"model":"gemini-3.8-flash","messages":[{"role":"user","content":"x"}],
             "tools":[{"type":"function","function":{"name":"f","parameters":{"${'$'}schema":"x","type":"object","properties":{"a":{"type":"string"}}}}}]}
        """)
        assertEquals(200, r.statusCode())
        val retried = seen.last().tools.single()
        val schema = assertNotNull(retried.jsonSchema)
        assertTrue(!schema.has("\$schema") && schema.has("properties"))
    }

    @Test
    fun `an empty turn is asked again`() {
        script.add { _, _ -> ModelTurn(emptyList(), "STOP", null) }
        script.add { _, _ -> ModelTurn(listOf(Part.Text("here")), "STOP", null) }
        val r = post("/v1/chat/completions", """{"model":"gemini-3.8-flash","messages":[{"role":"user","content":"x"}]}""")
        assertTrue(r.body().contains("here"))
        assertEquals(2, seen.size)
    }

    @Test
    fun `ollama endpoints list models and stream chat as json lines`() {
        val tags = JsonParser.parseString(get("/api/tags").body()).asJsonObject.getAsJsonArray("models").map { it.asJsonObject.get("name").asString }
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.1-pro"), tags)
        val show = JsonParser.parseString(post("/api/show", """{"model":"gemini-3.8-flash"}""").body()).asJsonObject
        assertTrue(show.getAsJsonArray("capabilities").map { it.asString }.contains("tools"))

        script.add { _, onText -> onText("Hi"); ModelTurn(listOf(Part.Text("Hi"), Part.FunctionCall("ls", JsonObject())), "STOP", Usage(3, 1, 4)) }
        val lines = post("/api/chat", """{"model":"gemini-3.8-flash","messages":[{"role":"user","content":"x"}]}""").body().lines().filter { it.isNotBlank() }
            .map { JsonParser.parseString(it).asJsonObject }
        assertEquals("Hi", lines.first().getAsJsonObject("message").get("content").asString)
        val last = lines.last()
        assertTrue(last.get("done").asBoolean)
        assertEquals("ls", last.getAsJsonObject("message").getAsJsonArray("tool_calls")[0].asJsonObject.getAsJsonObject("function").get("name").asString)
    }

    private companion object { const val TOKEN = "air-test" }
}
