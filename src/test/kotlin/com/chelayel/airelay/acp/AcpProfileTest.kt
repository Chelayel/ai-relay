package com.chelayel.airelay.acp

import kotlin.test.Test
import kotlin.test.assertEquals

class AcpProfileTest {
    @Test
    fun `choices are read from a wrapped help entry`() {
        val help = """
          --connect[=sessionId]                 Connect directly to a remote session
          --context <tier>                      Set the context window tier (overrides
                                                persisted setting) (choices: "default",
                                                "long_context")
          --continue                            Resume the most recent session
          --effort, --reasoning-effort <level>  Set the reasoning effort level (choices:
                                                "none", "minimal", "low")
        """.trimIndent()
        assertEquals(listOf("default", "long_context"), AcpProfile.parseChoices(help, "--context"))
        assertEquals(listOf("none", "minimal", "low"), AcpProfile.parseChoices(help, "--reasoning-effort"))
        assertEquals(emptyList(), AcpProfile.parseChoices(help, "--continue"))
        assertEquals(emptyList(), AcpProfile.parseChoices(help, "--missing"))
    }
}
