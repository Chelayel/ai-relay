package com.chelayel.airelay.gemini.api

import com.chelayel.airelay.config.Config
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A gateway that answers every model call with a 404, as Apigee does for a request it will not take. */
class ModelNotFoundHintTest {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/token") { ex ->
            val b = """{"access_token":"tok","expires_in":3600}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        createContext("/vertex") { ex ->
            ex.requestBody.readBytes()
            val b = """{"error":{"code":404,"message":"Publisher Model was not found","status":"NOT_FOUND"}}""".toByteArray()
            ex.sendResponseHeaders(404, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        start()
    }
    private val port = server.address.port

    @AfterTest fun stop() = server.stop(0)

    private fun fail(settings: Map<String, String>): String {
        val client = GeminiClient(GeminiConfig(Config.forTesting(settings)))
        val e = assertFailsWith<GeminiClient.GeminiException> {
            client.streamTurn(listOf(Content("user", listOf(Part.Text("hi")))), null, emptyList()) {}
        }
        return e.message!!
    }

    private fun apigee(models: String) = mapOf(
        "gemini.mode" to "apigee", "vertex.project" to "p", "vertex.base.url" to "http://127.0.0.1:$port/vertex",
        "apigee.token.url" to "http://127.0.0.1:$port/token", "apigee.client.id" to "id", "apigee.client.secret" to "s",
        "apigee.agents" to models,
    )

    @Test
    fun `on Apigee the failed model is not recommended, and another published one is`() {
        val message = fail(apigee("gemini-3.8-flash,gemini-3.1-pro"))
        assertTrue("start a new conversation" in message, message)
        assertTrue("(gemini-3.1-pro is a safe default)" in message, message)
        assertTrue("(gemini-3.8-flash is a safe default)" !in message, message)
    }

    @Test
    fun `on Apigee a model the gateway does not publish is never offered as the default`() {
        val message = fail(apigee("acme-gemini-pro"))
        assertTrue("safe default" !in message, message)
    }
}
