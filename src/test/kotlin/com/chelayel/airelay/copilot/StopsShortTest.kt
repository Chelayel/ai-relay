package com.chelayel.airelay.copilot

import com.chelayel.airelay.copilot.agent.CopilotProtocol
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Recognising a reply that hands control back instead of finishing.
 *
 * This is the difference between an assistant and an agent. An assistant checks
 * in — "would you like me to add tests?" — and a turn that ends there has left
 * the job half done. Each of these phrasings was a real turn that stopped short.
 */
class StopsShortTest {

    @Test
    fun `spots an offer to do the next piece`() {
        val offers = listOf(
            "I've updated the parser. Would you like me to add tests as well?",
            "Done. Let me know if you want me to wire it into the CLI.",
            "That's the model change. Shall I update the docs too?",
            "I can also refactor the duplicate block if that helps.",
            "The fix is in. Just say the word and I'll run the suite.",
            "Want me to handle the error case next?",
        )
        for (reply in offers) {
            assertTrue(CopilotProtocol.offersToContinue(reply), "should have been read as stopping short: $reply")
        }
    }

    @Test
    fun `a finished report is not an offer`() {
        val done = listOf(
            "Changed src/Greet.kt from \"hi\" to \"hello\" and ran the tests: 12 passed.",
            "Added GreetTest.kt with three cases. ./gradlew test passes.",
            "No change needed — the behaviour is already covered by ParserTest.",
        )
        for (reply in done) {
            assertFalse(CopilotProtocol.offersToContinue(reply), "should have been read as finished: $reply")
        }
    }

    @Test
    fun `a question inside a finished answer is not an offer`() {
        val reply = "Fixed. The old code assumed a trailing slash — was that intentional? " +
            "I kept the behaviour and added a test for it."
        assertFalse(CopilotProtocol.offersToContinue(reply))
    }

    @Test
    fun `an offer is still an offer when the page collapsed the newlines`() {
        val reply = "I've updated the parser. Would you like me to add tests as well?"
        assertTrue(CopilotProtocol.offersToContinue(reply.replace("\n", " ")))
    }
}
