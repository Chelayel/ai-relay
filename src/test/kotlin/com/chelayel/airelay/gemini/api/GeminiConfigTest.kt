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
}
