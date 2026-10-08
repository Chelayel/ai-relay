package com.chelayel.airelay.gemini.api

import com.chelayel.airelay.config.Config
import kotlin.test.Test
import kotlin.test.assertEquals

class GeminiConfigTest {

    private fun config(vararg values: Pair<String, String>) = GeminiConfig(Config.forTesting(values.toMap()))

    @Test
    fun `apigee offers the gateway's models with the chosen default first`() {
        // What setup and both IDEs write: the published ids in apigee.agents,
        // the chosen one in gemini.model.
        val c = config(
            "gemini.mode" to "apigee",
            "gemini.model" to "gemini-2.5-pro",
            "apigee.agents" to "gemini-2.5-flash, gemini-2.5-pro,gemini-3-flash",
        )
        assertEquals("gemini-2.5-pro", c.model)
        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-3-flash"), c.models)
    }

    @Test
    fun `apigee with a comma-separated gemini model and no agents still offers each`() {
        val c = config("gemini.mode" to "apigee", "gemini.model" to "a-model,b-model")
        assertEquals(listOf("a-model", "b-model"), c.models)
    }

    @Test
    fun `apigee agents are not offered outside apigee mode`() {
        val c = config("gemini.mode" to "gemini-api", "gemini.model" to "gemini-3.7-flash", "apigee.agents" to "gw-only-model")
        assertEquals(listOf("gemini-3.7-flash"), c.models)
    }

    @Test
    fun `a model override is the default and the gateway's models are still offered`() {
        val c = GeminiConfig(
            Config.forTesting(mapOf("gemini.mode" to "apigee", "gemini.model" to "gemini-2.5-pro", "apigee.agents" to "gemini-2.5-pro,gemini-2.5-flash")),
            modelOverride = "gemini-2.5-flash",
        )
        assertEquals("gemini-2.5-flash", c.model)
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), c.models)
    }
}
