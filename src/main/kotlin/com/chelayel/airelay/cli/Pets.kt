package com.chelayel.airelay.cli

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Tells Petabytes (github.com/Chelayel/petabytes) what the agent is doing, so a pet
 * in the IDE or on the Dock reacts to it: curious at a read, excited at an edit,
 * fingers crossed while tests run, happy when the turn is done.
 *
 * The channel is the one the Petabytes add-on for Claude Code already uses, so
 * every pet that understands Claude Code understands this with no change on its
 * side: the latest event in `~/.petabytes/claude-code.json`, which the VS Code
 * and IntelliJ pets watch, and the same event POSTed to the Mac app on
 * 127.0.0.1. Same keys too (`claudeStart`, `claudeRead`, …): they name the
 * reaction, not the agent.
 *
 * `pets` config: `auto` (default) speaks only when Petabytes is there — the
 * folder exists, or the IDE found its plugin and set `AIRELAY_PETS=on`; `on`
 * always; `off` never. Nothing here can fail a turn or slow one: every write is
 * on one background thread and every error is dropped.
 */
class Pets private constructor(private val file: File) {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "airelay-pets").apply { isDaemon = true } }
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build() }

    fun tell(key: String) {
        runCatching {
            worker.execute {
                runCatching {
                    file.parentFile.mkdirs()
                    file.writeText("{\"key\":\"$key\",\"at\":${System.currentTimeMillis()}}")
                }
                // The Mac app; not running is the usual case, and says nothing.
                runCatching {
                    http.send(
                        HttpRequest.newBuilder(URI.create(MAC_APP)).timeout(Duration.ofMillis(500))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"react\",\"key\":\"$key\"}")).build(),
                        HttpResponse.BodyHandlers.discarding(),
                    )
                }
            }
        }
    }

    companion object {
        private const val MAC_APP = "http://127.0.0.1:47365/"

        /** Null when the pets are off, or `auto` finds no sign of Petabytes. */
        fun from(setting: String?): Pets? {
            val dir = File(System.getProperty("user.home"), ".petabytes")
            val on = when (setting?.trim()?.lowercase()) {
                "off", "false", "no" -> false
                "on", "true", "yes" -> true
                else -> dir.isDirectory
            }
            return if (on) Pets(File(dir, "claude-code.json")) else null
        }

        private val DANGER = Regex("""\brm\s+-[a-z]*r[a-z]*f|\bgit\s+push\b.*--force|\bgit\s+reset\s+--hard\b""", RegexOption.IGNORE_CASE)
        private val PUSH = Regex("""\bgit\s+push\b""", RegexOption.IGNORE_CASE)
        private val COMMIT = Regex("""\bgit\s+commit\b""", RegexOption.IGNORE_CASE)
        private val INSTALL = Regex("""\b(npm|pnpm|yarn|bun)\s+(i|install|add|ci)\b|\bpip3?\s+install\b|\bbrew\s+install\b""", RegexOption.IGNORE_CASE)
        private val TEST = Regex("""\b(test|jest|vitest|pytest|mocha)\b|gradlew?\s+.*\btest\b|\bgo\s+test\b|\bcargo\s+test\b""", RegexOption.IGNORE_CASE)

        /** What a shell command is, as the Claude Code add-on classifies it. */
        fun commandKind(command: String): String? = when {
            DANGER.containsMatchIn(command) -> "danger"
            PUSH.containsMatchIn(command) -> "push"
            COMMIT.containsMatchIn(command) -> "commit"
            INSTALL.containsMatchIn(command) -> "install"
            TEST.containsMatchIn(command) -> "test"
            else -> null
        }

        /** The kind of thing a tool does, from the name any backend reports it under. */
        fun toolKind(name: String): String? = when (name.lowercase().substringAfterLast("__")) {
            "read", "readfile", "listfiles", "findfiles", "searchfiles", "search", "glob", "grep", "ls" -> "read"
            "edit", "editfile", "write", "writefile", "multiedit", "notebookedit", "delete", "move" -> "edit"
            "fetch", "fetchurl", "webfetch", "websearch", "mavensearch" -> "web"
            "task", "agent" -> "agent"
            "bash", "run", "runcommand", "execute", "shell" -> "shell"
            else -> null
        }
    }
}

/** Passes everything through to [inner], and tells the pet ([tell], normally [Pets.tell]) what it means. */
class PetSink(private val inner: InterruptibleSink, private val tell: (String) -> Unit) : InterruptibleSink by inner {
    // The shell command waiting for its result, as the add-on reacts to a test's outcome.
    private var running: String? = null

    override fun beginTurn() { running = null; tell("claudeStart"); inner.beginTurn() }

    override fun toolUse(name: String, summary: String) {
        when (Pets.toolKind(name)) {
            "read" -> tell("claudeRead")
            "edit" -> tell("claudeEdit")
            "web" -> tell("claudeWeb")
            "agent" -> tell("claudeAgent")
            "shell" -> {
                val kind = Pets.commandKind(summary)
                if (kind == "danger" || kind == "test" || kind == "install") tell(kind)
                running = kind ?: "command"
            }
        }
        inner.toolUse(name, summary)
    }

    override fun toolResult(text: String, isError: Boolean) {
        val kind = running.also { running = null }
        when {
            kind == null -> {}
            kind == "test" -> tell(if (isError) "claudeTestsFailed" else "claudeTestsOk")
            isError -> tell("commandFail")
            kind == "push" -> tell("gitPush")
            kind == "commit" -> tell("commit")
        }
        inner.toolResult(text, isError)
    }

    override fun turnComplete() { tell("claudeDone"); inner.turnComplete() }
}
