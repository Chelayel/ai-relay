package com.chelayel.airelay

import com.chelayel.airelay.claude.ClaudeAgent
import com.chelayel.airelay.cli.Agent
import com.chelayel.airelay.cli.Ansi
import com.chelayel.airelay.cli.ConsoleSink
import com.chelayel.airelay.cli.PermissionMode
import com.chelayel.airelay.cli.Workspace
import com.chelayel.airelay.config.Config
import com.chelayel.airelay.gemini.agent.GeminiAgent
import com.chelayel.airelay.gemini.agent.PermissionDecision
import com.chelayel.airelay.gemini.api.ConnectionMode
import com.chelayel.airelay.gemini.api.GeminiConfig
import kotlin.system.exitProcess

/**
 * AI Relay — one CLI, two agent backends that differ only in how they connect:
 *
 *   airelay claude  → drives the local `claude` CLI (automatic auth, like Claude Relay)
 *   airelay gemini  → talks REST via Gemini API / Vertex / Apigee (like Gemini Relay)
 *
 * Both run as coding agents over the current repo (plus any `--add-dir` folders),
 * one-shot when given a prompt or interactive otherwise.
 */
fun main(rawArgs: Array<String>) {
    val args = rawArgs.toMutableList()
    if (args.isEmpty() || args[0] in listOf("-h", "--help", "help")) {
        printUsage()
        return
    }

    val backend = args.removeAt(0).lowercase()
    if (backend !in listOf("claude", "gemini")) {
        System.err.println("Unknown agent '$backend'. Expected 'claude' or 'gemini'.\n")
        printUsage()
        exitProcess(2)
    }

    val opts = parseOptions(args)
    val workspace = Workspace.of(opts.dir, opts.addDirs)
    val prompt = opts.positional.joinToString(" ").trim()
    val oneShot = prompt.isNotEmpty()
    val config = Config.load()

    val agent: Agent = when (backend) {
        "claude" -> buildClaude(workspace, opts)
        else -> buildGemini(workspace, opts, config, oneShot) ?: exitProcess(1)
    }

    // Never leak the Claude subprocess.
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { agent.close() } })

    val sink = ConsoleSink()
    printBanner(agent, workspace, oneShot)

    if (oneShot) {
        agent.send(prompt, sink)
        agent.close()
        return
    }
    repl(agent, sink)
}

// ---- backends ---------------------------------------------------------------

private fun buildClaude(workspace: Workspace, opts: Options): ClaudeAgent {
    // Map the shared permission vocabulary onto the claude CLI's flag values.
    val mode = when (PermissionMode.from(opts.permissionMode, PermissionMode.ACCEPT_EDITS)) {
        PermissionMode.ASK -> "default"
        PermissionMode.ACCEPT_EDITS -> "acceptEdits"
        PermissionMode.BYPASS -> "bypassPermissions"
    }
    return ClaudeAgent(
        workspace = workspace,
        model = opts.model,
        permissionMode = mode,
        agent = opts.claudeAgent,
        disallowedTools = opts.disallow,
    )
}

private fun buildGemini(workspace: Workspace, opts: Options, config: Config, oneShot: Boolean): GeminiAgent? {
    val modeOverride = opts.geminiMode?.let { ConnectionMode.from(it) }
    val gcfg = GeminiConfig(config, modeOverride, opts.model)
    gcfg.missingCredentials()?.let {
        System.err.println(Ansi.red("Gemini is not configured for ${gcfg.connectionMode.label}: $it"))
        System.err.println(Ansi.dim("See the README for env vars / ~/.airelay/config.properties keys."))
        return null
    }
    // One-shot runs can't prompt for each tool, so default them to bypass.
    val default = if (oneShot) PermissionMode.BYPASS else PermissionMode.ACCEPT_EDITS
    val permission = PermissionMode.from(opts.permissionMode, default)
    return GeminiAgent(
        workspace = workspace,
        config = gcfg,
        permission = permission,
        askMode = opts.ask,
        confirm = ::confirmOnConsole,
    )
}

/** Ask the user to approve a tool call at the terminal. EOF / no TTY → deny. */
private fun confirmOnConsole(name: String, summary: String): PermissionDecision {
    print(Ansi.yellow("Allow $name") + (if (summary.isNotBlank()) " ${Ansi.dim(summary)}" else "") +
        "? [y]es / [n]o / [a]lways: ")
    System.out.flush()
    return when (readlnOrNull()?.trim()?.lowercase()) {
        "y", "yes" -> PermissionDecision.ALLOW_ONCE
        "a", "always" -> PermissionDecision.ALLOW_ALWAYS
        else -> PermissionDecision.DENY
    }
}

// ---- REPL -------------------------------------------------------------------

private fun repl(agent: Agent, sink: ConsoleSink) {
    while (true) {
        print(Ansi.green("\n› "))
        System.out.flush()
        val line = readlnOrNull() ?: break
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> continue
            trimmed == "/exit" || trimmed == "/quit" -> break
            trimmed == "/help" -> { printReplHelp(); continue }
        }
        agent.send(trimmed, sink)
    }
    agent.close()
    println(Ansi.dim("bye"))
}

// ---- option parsing ---------------------------------------------------------

private class Options {
    var model: String? = null
    var dir: String? = null
    val addDirs = mutableListOf<String>()
    var permissionMode: String? = null
    var ask = false
    var geminiMode: String? = null
    var claudeAgent: String? = null
    val disallow = mutableListOf<String>()
    val positional = mutableListOf<String>()
}

private fun parseOptions(args: List<String>): Options {
    val o = Options()
    var i = 0
    fun next(flag: String): String {
        if (i + 1 >= args.size) { System.err.println("Missing value for $flag"); exitProcess(2) }
        return args[++i]
    }
    while (i < args.size) {
        when (val a = args[i]) {
            "--model", "-m" -> o.model = next(a)
            "--dir", "-C" -> o.dir = next(a)
            "--add-dir" -> o.addDirs.add(next(a))
            "--permission-mode" -> o.permissionMode = next(a)
            "--yolo" -> o.permissionMode = "bypass"
            "--ask" -> o.ask = true
            "--mode" -> o.geminiMode = next(a)
            "--agent" -> o.claudeAgent = next(a)
            "--disallow" -> o.disallow.add(next(a))
            "--" -> { i++; while (i < args.size) { o.positional.add(args[i]); i++ }; return o }
            else -> o.positional.add(a)
        }
        i++
    }
    return o
}

// ---- help / banner ----------------------------------------------------------

private fun printBanner(agent: Agent, workspace: Workspace, oneShot: Boolean) {
    println(Ansi.bold("AI Relay") + Ansi.dim(" — ${agent.describe()}"))
    println(Ansi.dim("context: ${workspace.roots.joinToString(", ") { it.path }}"))
    if (!oneShot) println(Ansi.dim("Type a message, /help for commands, /exit to quit."))
}

private fun printReplHelp() {
    println(
        """
        ${Ansi.bold("Commands")}
          /help            show this help
          /exit, /quit     leave
        Anything else is sent to the agent as a message.
        """.trimIndent(),
    )
}

private fun printUsage() {
    println(
        """
        ${Ansi.bold("AI Relay")} — Claude & Gemini as CLI coding agents.

        ${Ansi.bold("Usage")}
          airelay claude [options] [prompt]
          airelay gemini [options] [prompt]

        With a prompt: run it once and exit. Without: start an interactive session.

        ${Ansi.bold("Common options")}
          -C, --dir PATH          working directory / repo root (default: cwd)
              --add-dir PATH      extra directory the agent may read/search (repeatable)
          -m, --model NAME        model id
              --permission-mode M  ask | acceptEdits | bypass
              --yolo              alias for --permission-mode bypass
              --ask               read-only Q&A, no tools (gemini)

        ${Ansi.bold("gemini options")}
              --mode M            gemini-api | vertex | apigee  (default: gemini-api or AIRELAY_GEMINI_MODE)

        ${Ansi.bold("claude options")}
              --agent NAME        run as a named claude sub-agent
              --disallow TOOL     disallow a tool (repeatable)

        ${Ansi.bold("Connection")}
          claude  → local `claude` CLI, using whatever it is already logged in with.
          gemini  → Gemini API key, Vertex AI (gcloud), or Vertex-via-Apigee.
                    Configure via env vars or ~/.airelay/config.properties (see README).
        """.trimIndent(),
    )
}
