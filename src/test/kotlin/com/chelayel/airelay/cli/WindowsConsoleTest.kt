package com.chelayel.airelay.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsConsoleTest {
    @Test fun `every glyph the tool prints has an ASCII stand-in`() {
        assertEquals("OK saved - now using x  > next ...", WindowsConsole.transliterate("✓ saved — now using ✗  › next …"))
        assertEquals("| AI Relay   gemini - apigee", WindowsConsole.transliterate("▍ AI Relay   gemini · apigee"))
        assertEquals("plain ascii stays", WindowsConsole.transliterate("plain ascii stays"))
        assertEquals("café keeps letters", WindowsConsole.transliterate("café keeps letters"))
    }
}
