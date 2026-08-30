package com.chelayel.airelay.mcp

import com.chelayel.airelay.config.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reading MCP servers from the same `mcpServers` file shape Claude Desktop and
 * Claude Code use. Sharing the format is the point — a server configured once
 * should not have to be declared again for this CLI.
 */
class McpConfigTest {

    private val dir: File = Files.createTempDirectory("airelay-mcp").toFile()
    private val config = Config.forTesting(emptyMap())

    @AfterTest fun cleanUp() { dir.deleteRecursively() }

    private fun write(json: String): File = File(dir, ".mcp.json").apply { writeText(json) }

    @Test
    fun `reads the standard shape`() {
        write(
            """
            { "mcpServers": {
                "context7": { "command": "npx", "args": ["-y", "@upstash/context7-mcp"] }
            } }
            """.trimIndent(),
        )
        val servers = McpConfig.load(dir, config)
        assertEquals(1, servers.size)
        assertEquals("context7", servers[0].name)
        assertEquals("npx", servers[0].command)
        assertEquals(listOf("-y", "@upstash/context7-mcp"), servers[0].args)
        assertTrue(servers[0].enabled)
    }

    /** Gemini Relay stored args as one string; accept that rather than break on it. */
    @Test
    fun `accepts args given as a single string`() {
        write("""{ "mcpServers": { "s": { "command": "run", "args": "-a  -b" } } }""")
        assertEquals(listOf("-a", "-b"), McpConfig.load(dir, config)[0].args)
    }

    @Test
    fun `honours both disabled and enabled spellings`() {
        write(
            """
            { "mcpServers": {
                "a": { "command": "x", "disabled": true },
                "b": { "command": "x", "enabled": false },
                "c": { "command": "x" }
            } }
            """.trimIndent(),
        )
        val byName = McpConfig.load(dir, config).associateBy { it.name }
        assertEquals(false, byName["a"]?.enabled)
        assertEquals(false, byName["b"]?.enabled)
        assertEquals(true, byName["c"]?.enabled)
    }

    /** So a `.mcp.json` can be committed naming a token without holding one. */
    @Test
    fun `expands environment placeholders in env values`() {
        write("""{ "mcpServers": { "s": { "command": "x", "env": { "TOKEN": "${'$'}{HOME}" } } } }""")
        assertEquals(System.getenv("HOME"), McpConfig.load(dir, config)[0].env["TOKEN"])
    }

    @Test
    fun `a server with no command is skipped rather than started`() {
        write("""{ "mcpServers": { "broken": { "args": ["x"] } } }""")
        assertTrue(McpConfig.load(dir, config).isEmpty())
    }

    @Test
    fun `no config file means no servers, not an error`() {
        assertTrue(McpConfig.load(dir, config).isEmpty())
    }

    /** Silently ignoring a file the user wrote is worse than saying it is broken. */
    @Test
    fun `malformed json is reported`() {
        write("{ not json")
        assertFailsWith<IllegalArgumentException> { McpConfig.load(dir, config) }
    }

    @Test
    fun `a file without mcpServers is reported`() {
        write("""{ "servers": {} }""")
        val e = assertFailsWith<IllegalArgumentException> { McpConfig.load(dir, config) }
        assertTrue(e.message!!.contains("mcpServers"), e.message!!)
    }
}
