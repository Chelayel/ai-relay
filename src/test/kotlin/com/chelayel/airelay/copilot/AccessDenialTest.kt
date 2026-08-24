package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.agent.CopilotProtocol
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chat surface's favourite way of declining the whole arrangement.
 *
 * M365 Copilot answered a plain "read build.gradle.kts" with a refusal: it had
 * no file access, so would the user please paste the contents. That belief is
 * wrong — the files are open on the user's machine and a tool call is what
 * reaches them — so it earns a correction instead of ending the turn.
 */
class AccessDenialTest {

    @Test
    fun `the refusal seen against real m365 copilot is caught`() {
        val reply = "I can't actually use the project-specific `readFile` tool described in your " +
            "prompt, and the project files are not available in my current environment."
        assertTrue(CopilotProtocol.deniesAccess(reply))
    }

    @Test
    fun `asking for a paste is a denial however it is phrased`() {
        assertTrue(CopilotProtocol.deniesAccess("Please paste the contents of build.gradle.kts."))
        assertTrue(CopilotProtocol.deniesAccess("If you share the contents I can help."))
        assertTrue(CopilotProtocol.deniesAccess("I don't have access to your repository."))
    }

    @Test
    fun `ordinary work is not a denial`() {
        assertFalse(CopilotProtocol.deniesAccess("I read build.gradle.kts; it targets JDK 21."))
        assertFalse(CopilotProtocol.deniesAccess("The file does not exist, so I created it."))
        // "access" in its ordinary sense, about the code rather than the model.
        assertFalse(CopilotProtocol.deniesAccess("The field is private, so the test cannot access it."))
    }

    @Test
    fun `a denial is detected regardless of line wrapping`() {
        val wrapped = "I do not have\n   access to\n   the project files."
        assertTrue(CopilotProtocol.deniesAccess(wrapped))
    }

    @Test
    fun `the refusal of the write path is caught too`() {
        // Word-for-word what M365 answered when asked to create a file. The
        // first pattern list missed this by one word — "this environment"
        // rather than "my current environment".
        val reply = "Blocked: the task requires AI Relay-specific tools (`readFile`, `writeFile`, " +
            "`editFile`, `runCommand`) that are not available in this environment, so I cannot " +
            "actually create `src/test/kotlin/com/chelayel/airelay/SmokeTest.kt` or run `./gradlew test`."
        assertTrue(CopilotProtocol.deniesAccess(reply))
    }

    @Test
    fun `the hedge is caught whatever verb follows it`() {
        assertTrue(CopilotProtocol.deniesAccess("I cannot actually execute those tools."))
        assertTrue(CopilotProtocol.deniesAccess("I can't actually emit a tool call."))
        assertTrue(CopilotProtocol.deniesAccess("I can't actually create that file."))
    }

    @Test
    fun `restriction to another tool set is a denial`() {
        assertTrue(CopilotProtocol.deniesAccess("I only have access to the tools listed for this session."))
    }

    @Test
    fun `our own correction is not mistaken for a denial`() {
        // The page echoes our messages back. If the push that corrects a denial
        // reads as a denial itself, the loop argues with its own echo.
        val ourPush = "You do have access: AI Relay has this project's files open on the user's " +
            "machine and runs your tool calls against them."
        assertFalse(CopilotProtocol.deniesAccess(ourPush))
    }

    @Test
    fun `prose about code access is not a denial`() {
        assertFalse(CopilotProtocol.deniesAccess("The helper does not have access to the private field."))
    }
}
