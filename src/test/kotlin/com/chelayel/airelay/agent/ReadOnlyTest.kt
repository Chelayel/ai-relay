package com.chelayel.airelay.agent

import com.chelayel.airelay.cli.PermissionMode
import com.chelayel.airelay.cli.Workspace
import com.chelayel.airelay.claude.ClaudeAgent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Read-only mode answers from reading and changes nothing. The promise rests on
 * two things: the mode is reachable under the names people type, and a
 * read-only turn is never offered a tool that can write.
 */
class ReadOnlyTest {

    @Test
    fun `every name for read-only parses to it`() {
        for (name in listOf("readOnly", "READ_ONLY", "search", "readonly", "read-only", "plan", " Search ")) {
            assertEquals(PermissionMode.READ_ONLY, PermissionMode.parse(name), name)
        }
    }

    @Test
    fun `the existing modes still parse, and unknown names do not`() {
        assertEquals(PermissionMode.ASK, PermissionMode.parse("ask"))
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.parse("acceptEdits"))
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.parse("accept"))
        assertEquals(PermissionMode.BYPASS, PermissionMode.parse("bypass"))
        assertNull(PermissionMode.parse("nonsense"))
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.from("nonsense", PermissionMode.ACCEPT_EDITS))
    }

    @Test
    fun `a read-only turn is offered the readers and nothing that writes`() {
        val dir = Files.createTempDirectory("ro").toFile()
        try {
            val tools = Tools(Workspace(dir, emptyList()), commandTimeoutSeconds = 5)
            val offered = tools.specs(PermissionMode.READ_ONLY).map { it.name }.toSet()
            assertTrue("readFile" in offered && "listFiles" in offered && "searchFiles" in offered, offered.toString())
            for (writer in listOf("writeFile", "editFile", "runCommand")) assertFalse(writer in offered, writer)
            // Every other mode keeps the full set.
            assertEquals(tools.specs().map { it.name }, tools.specs(PermissionMode.ACCEPT_EDITS).map { it.name })
            assertTrue(tools.mutates("writeFile") && tools.mutates("editFile") && tools.mutates("runCommand"))
            assertFalse(tools.mutates("readFile"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Claude read-only is its default mode, not plan mode`() {
        // plan mode writes a plan file and asks for an approval nobody can give in --print mode.
        assertEquals("default", ClaudeAgent.cliMode(PermissionMode.READ_ONLY))
        assertEquals("acceptEdits", ClaudeAgent.cliMode(PermissionMode.ACCEPT_EDITS))
        assertEquals("bypassPermissions", ClaudeAgent.cliMode(PermissionMode.BYPASS))
        assertTrue("Write" in ClaudeAgent.READ_ONLY_BLOCKED && "Edit" in ClaudeAgent.READ_ONLY_BLOCKED && "Bash" in ClaudeAgent.READ_ONLY_BLOCKED)
        assertFalse(ClaudeAgent.READ_ONLY_PROMPT.startsWith("-"), "a leading dash would be read as a flag after the variadic tool lists")
    }
}
