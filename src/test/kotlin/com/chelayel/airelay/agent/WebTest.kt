package com.chelayel.airelay.agent

import com.chelayel.airelay.config.Config
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent's web access. Everything here runs against a throwaway local server
 * rather than the open internet, so the tests say something about this code and
 * not about whoever is up today.
 */
class WebTest {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/page") { ex ->
            val body = """
                <html><head><title>T</title><style>.a{color:red}</style></head>
                <body><script>var x = 1;</script>
                <h1>Spring Boot 4</h1>
                <p>Use <code>spring-boot-starter-web</code> &amp; friends.</p>
                <p>Second&nbsp;paragraph.</p>
                </body></html>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/plain") { ex ->
            val body = "line one\nline two\n".toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/plain")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/moved") { ex ->
            ex.responseHeaders.add("Location", "/plain")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        createContext("/gone") { ex ->
            val body = "nothing here".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(404, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }

    private val base = "http://127.0.0.1:${server.address.port}"

    // Never Config.load() here: it would read the developer's own config file
    // and let a local setting decide whether the test passes.
    private val web = Web(configOf())

    @AfterTest fun stop() = server.stop(0)

    private fun call(tool: String, vararg pairs: Pair<String, Any>): JsonObject =
        web.execute(
            tool,
            JsonObject().apply {
                pairs.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> addProperty(k, v)
                        is Int -> addProperty(k, v)
                        else -> addProperty(k, v.toString())
                    }
                }
            },
        )

    private fun JsonObject.result(): String = get("result")?.asString.orEmpty()
    private fun JsonObject.error(): String = get("error")?.asString.orEmpty()

    // ---- fetchUrl -------------------------------------------------------------

    @Test
    fun `fetches a page as readable text with markup and scripts gone`() {
        val out = call("fetchUrl", "url" to "$base/page")
        assertTrue(out.error().isEmpty(), out.error())
        val text = out.result()
        assertTrue(text.contains("Spring Boot 4"), text)
        assertTrue(text.contains("spring-boot-starter-web"), text)
        assertFalse(text.contains("var x = 1"), "script bodies must not reach the model")
        assertFalse(text.contains("color:red"), "stylesheet bodies must not reach the model")
        assertFalse(text.contains("<p>"), "tags must be stripped")
    }

    @Test
    fun `decodes entities rather than showing them raw`() {
        val text = call("fetchUrl", "url" to "$base/page").result()
        assertTrue(text.contains("&"), text)
        assertFalse(text.contains("&amp;"), text)
        assertFalse(text.contains("&nbsp;"), text)
    }

    @Test
    fun `keeps block structure so a document does not collapse into one line`() {
        val text = call("fetchUrl", "url" to "$base/page").result()
        assertTrue(text.lines().size > 1, "expected several lines, got: $text")
    }

    @Test
    fun `non-html is returned as-is`() {
        assertEquals("line one\nline two", call("fetchUrl", "url" to "$base/plain").result().trim())
    }

    /** `HttpURLConnection` stops at a cross-protocol redirect, so the hops are walked by hand. */
    @Test
    fun `follows a redirect`() {
        assertTrue(call("fetchUrl", "url" to "$base/moved").result().contains("line one"))
    }

    @Test
    fun `truncates at maxChars instead of flooding the context`() {
        val out = call("fetchUrl", "url" to "$base/page", "maxChars" to 1_000).result()
        assertTrue(out.length < 1_200, "expected a truncated body, got ${out.length} chars")
    }

    @Test
    fun `an http error is reported, not returned as page content`() {
        val out = call("fetchUrl", "url" to "$base/gone")
        assertTrue(out.error().contains("404"), out.error())
    }

    @Test
    fun `a relative url is refused with a usable message`() {
        assertTrue(call("fetchUrl", "url" to "/page").error().contains("absolute"))
    }

    // ---- configuration --------------------------------------------------------

    /**
     * Every keyless search endpoint tried (DuckDuckGo html and lite, Mojeek)
     * answers an automated client with a challenge page, so there is no default
     * provider. The tool is then not advertised at all: a tool that always
     * answers "not configured" teaches the model to stop trying and go back to
     * guessing, which is the failure web access exists to prevent.
     */
    @Test
    fun `webSearch is not advertised until a provider is configured`() {
        val names = Web(configOf()).specs().map { it.name }
        assertFalse("webSearch" in names, "unconfigured search must not be offered: $names")
        assertTrue("fetchUrl" in names, "fetchUrl needs no provider: $names")
        assertTrue("mavenSearch" in names, "mavenSearch needs no provider: $names")
    }

    @Test
    fun `an unconfigured search says what to set`() {
        val out = Web(configOf()).execute("webSearch", JsonObject().apply { addProperty("query", "x") })
        val message = out.get("error")?.asString.orEmpty()
        assertTrue(message.contains("search.provider"), message)
        assertTrue(message.contains("search.api.key"), message)
    }

    @Test
    fun `a configured provider advertises search`() {
        val configured = configOf("search.provider" to "brave", "search.api.key" to "k")
        assertTrue("webSearch" in Web(configured).specs().map { it.name })
    }

    @Test
    fun `a key alone is enough, defaulting the provider`() {
        assertEquals(Web.Provider.BRAVE, Web(configOf("search.api.key" to "k")).provider)
    }

    @Test
    fun `google needs an engine id as well as a key`() {
        val partial = Web(configOf("search.provider" to "google", "search.api.key" to "k"))
        assertTrue(partial.searchUnavailable()?.contains("search.cx") == true)
    }

    @Test
    fun `web can be switched off entirely`() {
        val off = Web(configOf("web.enabled" to "false"))
        assertTrue(off.specs().isEmpty())
        assertFalse(off.handles("fetchUrl"))
    }

    /** A Config backed only by the given keys, so a developer's own file can't sway the test. */
    private fun configOf(vararg pairs: Pair<String, String>): Config =
        Config.forTesting(pairs.toMap())
}
