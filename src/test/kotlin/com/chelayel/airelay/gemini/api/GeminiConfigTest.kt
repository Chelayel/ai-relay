package com.chelayel.airelay.gemini.api

import com.chelayel.airelay.config.Config
import kotlin.test.Test
import kotlin.test.assertEquals

class GeminiConfigTest {

    private fun config(vararg values: Pair<String, String>) = GeminiConfig(Config.forTesting(values.toMap()))

    @Test
    fun `apigee offers exactly the gateway's models`() {
        // What setup and both IDEs write: the published ids in apigee.agents,
        // the chosen one in gemini.model.
        val c = config(
            "gemini.mode" to "apigee",
            "gemini.model" to "gemini-2.5-pro",
            "apigee.agents" to "gemini-2.5-flash, gemini-2.5-pro,gemini-3-flash",
        )
        assertEquals("gemini-2.5-pro", c.model)
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-3-flash"), c.models)
    }

    @Test
    fun `apigee drops a saved model the gateway does not publish`() {
        // The IntelliJ settings page fills an empty model field with the public
        // default; it must neither be offered nor be used.
        val c = config(
            "gemini.mode" to "apigee",
            "gemini.model" to "gemini-3.8-flash",
            "apigee.agents" to "gw-pro,gw-flash",
        )
        assertEquals("gw-pro", c.model)
        assertEquals(listOf("gw-pro", "gw-flash"), c.models)
    }

    @Test
    fun `apigee with a comma-separated gemini model and no agents still offers each`() {
        val c = config("gemini.mode" to "apigee", "gemini.model" to "a-model,b-model")
        assertEquals(listOf("a-model", "b-model"), c.models)
    }

    @Test
    fun `apigee agents are not offered outside apigee mode`() {
        val c = config("gemini.mode" to "gemini-api", "gemini.model" to "gemini-3.8-flash", "apigee.agents" to "gw-only-model")
        assertEquals(listOf("gemini-3.8-flash"), c.models)
    }

    @Test
    fun `a model override is the default and only the gateway's models are offered`() {
        val c = GeminiConfig(
            Config.forTesting(mapOf("gemini.mode" to "apigee", "gemini.model" to "gemini-2.5-pro", "apigee.agents" to "gemini-2.5-pro,gemini-2.5-flash")),
            modelOverride = "gemini-2.5-flash",
        )
        assertEquals("gemini-2.5-flash", c.model)
        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash"), c.models)
    }

    @Test
    fun `an unset model is the current default on every surface`() {
        assertEquals("gemini-3.8-flash", config("gemini.mode" to "gemini-api").model)
        assertEquals("gemini-3.8-flash", config("gemini.mode" to "vertex").model)
    }

    @Test
    fun `prices parse per model and skip malformed entries`() {
        val c = config(
            "gemini.mode" to "apigee",
            "gemini.prices" to "gemini-3.8-flash=0.75/3.75; gemini-3.1-pro=\$2/\$12, broken=1, also-broken=a/b",
        )
        assertEquals(GeminiConfig.Price(0.75, 3.75), c.priceFor("gemini-3.8-flash"))
        assertEquals(GeminiConfig.Price(2.0, 12.0), c.priceFor("gemini-3.1-pro"))
        assertEquals(null, c.priceFor("broken"))
        assertEquals(null, c.priceFor("unpriced-model"))
        // 1M prompt + 200k output at 0.75 / 3.75
        assertEquals(1.5, GeminiConfig.Price(0.75, 3.75).cost(1_000_000, 200_000), 1e-9)
    }

    @Test
    fun `a price named for one surface applies to the other`() {
        val c = config("gemini.mode" to "vertex", "gemini.prices" to "gemini-3.1-pro-preview=2/12")
        assertEquals(GeminiConfig.Price(2.0, 12.0), c.priceFor("gemini-3.1-pro"))
    }

    @Test
    fun `a price for a retired model does not price the default`() {
        val c = config("gemini.mode" to "gemini-api", "gemini.prices" to "gemini-2.0-flash=0.1/0.4")
        assertEquals(null, c.priceFor("gemini-3.8-flash"))
    }

    private val apigee = mapOf(
        "gemini.mode" to "apigee", "vertex.project" to "proj", "vertex.location" to "europe-west4", "vertex.endpoint" to "https://gw.example.com/",
        "apigee.token.url" to "https://auth/t", "apigee.client.id" to "id", "apigee.client.secret" to "s",
    )

    @Test
    fun `the Vertex path is the standard one by default, on the version configured`() {
        assertEquals("https://gw.example.com/v1/projects/proj/locations/europe-west4/publishers/google", config(*apigee.toList().toTypedArray()).vertexBase())
        assertEquals(
            "https://gw.example.com/v1beta1/projects/proj/locations/europe-west4/publishers/google",
            config(*(apigee + ("vertex.api.version" to "/v1beta1/")).toList().toTypedArray()).vertexBase(),
        )
        assertEquals(
            "https://europe-west4-aiplatform.googleapis.com/v1/projects/proj/locations/europe-west4/publishers/google",
            config("gemini.mode" to "vertex", "vertex.project" to "proj", "vertex.location" to "europe-west4").vertexBase(),
        )
    }

    @Test
    fun `a full base URL replaces the path and fills its placeholders`() {
        val c = config(*(apigee + ("vertex.base.url" to "gw.example.com/ai/{version}/vertex/{project}/{location}/") + ("vertex.api.version" to "v1beta1")).toList().toTypedArray())
        assertEquals("https://gw.example.com/ai/v1beta1/vertex/proj/europe-west4", c.vertexBase())
    }

    @Test
    fun `with a base URL the gateway host is not required, and the project only if the URL names it`() {
        val base = apigee - "vertex.endpoint" - "vertex.project"
        assertEquals(null, config(*(base + ("vertex.base.url" to "https://gw/genai")).toList().toTypedArray()).missingCredentials())
        assertEquals("Set AIRELAY_VERTEX_PROJECT.", config(*(base + ("vertex.base.url" to "https://gw/{project}")).toList().toTypedArray()).missingCredentials())
        assertEquals(true, config(*base.toList().toTypedArray()).missingCredentials()?.contains("PROJECT"))
    }
}
