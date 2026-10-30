package com.chelayel.airelay.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class PetsTest {
    private class Quiet : InterruptibleSink {
        override fun assistantText(text: String) {}
        override fun beginTurn() {}
        override fun stop(message: String) {}
    }

    private fun run(block: PetSink.() -> Unit): List<String> {
        val told = mutableListOf<String>()
        PetSink(Quiet()) { told += it }.block()
        return told
    }

    @Test
    fun `a turn starts and ends`() {
        assertEquals(listOf("claudeStart", "claudeDone"), run { beginTurn(); turnComplete() })
    }

    @Test
    fun `tools from every backend map to the same reactions`() {
        val told = run {
            toolUse("readFile", "a.kt"); toolUse("Read", "a.kt")
            toolUse("editFile", "a.kt"); toolUse("Write", "b.kt")
            toolUse("fetchUrl", "https://x"); toolUse("Task", "explore")
            toolUse("mcp__srv__unknown", "")
        }
        assertEquals(listOf("claudeRead", "claudeRead", "claudeEdit", "claudeEdit", "claudeWeb", "claudeAgent"), told)
    }

    @Test
    fun `a test run reacts to its outcome`() {
        assertEquals(listOf("test", "claudeTestsFailed"), run { toolUse("Run", "./gradlew test"); toolResult("FAILED", true) })
        assertEquals(listOf("test", "claudeTestsOk"), run { toolUse("Bash", "npm test"); toolResult("ok", false) })
    }

    @Test
    fun `commands are classified as the Claude Code add-on does`() {
        assertEquals(listOf("gitPush"), run { toolUse("runCommand", "git push origin main"); toolResult("", false) })
        assertEquals(listOf("danger"), run { toolUse("Bash", "rm -rf build"); toolResult("", false) })
        assertEquals(listOf("commandFail"), run { toolUse("Bash", "ls nope"); toolResult("no such file", true) })
        assertEquals(emptyList(), run { toolUse("Bash", "ls"); toolResult("a b", false) })
    }
}
