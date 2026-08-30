package com.chelayel.airelay

import com.chelayel.airelay.agent.PermissionDecision
import com.chelayel.airelay.agent.Web
import com.chelayel.airelay.claude.ClaudeAgent
import com.chelayel.airelay.cli.Agent
import com.chelayel.airelay.cli.Ansi
import com.chelayel.airelay.cli.ConsoleSink
import com.chelayel.airelay.cli.PermissionMode
import com.chelayel.airelay.cli.Workspace
import com.chelayel.airelay.config.Config
import com.chelayel.airelay.copilot.CopilotSetup
import com.chelayel.airelay.copilot.agent.CopilotAgent
import com.chelayel.airelay.copilot.api.CopilotConfig
import com.chelayel.airelay.gemini.GeminiSetup
import com.chelayel.airelay.gemini.agent.GeminiAgent
import com.chelayel.airelay.gemini.api.ConnectionMode
import com.chelayel.airelay.gemini.api.GeminiConfig
import com.chelayel.airelay.mcp.McpConfig
import com.chelayel.airelay.mcp.McpManager
import com.google.gson.JsonObject
import kotlin.system.exitProcess

/**
 * AI Relay — one CLI, three agent backends that differ only in how they connect:
 *
 *   airelay claude   → drives the local `claude` CLI (automatic auth, like Claude Relay)
 *   airelay gemini   → talks REST via Gemini API / Vertex / Apigee (like Gemini Relay)
 *   airelay copilot  → replays your signed-in Copilot web session, model picker and all
 *
 * All three run as coding agents over the current repo (plus any `--add-dir`
 * folders), one-shot when given a prompt or interactive otherwise.
 */
@Volatile
private var turnActive = false

/** Subcommands of `airelay copilot` that manage the capture instead of chatting. */
private val COPILOT_SUBCOMMANDS =
    listOf("setup", "config", "capture", "login", "relogin", "refresh", "models", "test", "diagnose", "reset")

/** Flags for `airelay copilot setup|login`, which capture the browser session. */
private fun captureOptions(args: List<String>): CopilotSetup.Options {
    var mode: String? = null
    var file: String? = null
    var attach: Int? = null
    var timeout = 300L
    var url: String? = null
    var i = 0
    fun next(flag: String): String {
        if (i + 1 >= args.size) { System.err.println("Missing value for $flag"); exitProcess(2) }
        return args[++i]
    }
    while (i < args.size) {
        when (val a = args[i]) {
            "--browser" -> mode = "browser"
            "--replay", "--capture" -> mode = "replay"
            "--file", "--curl", "--from-file" -> file = next(a)
            "--attach", "--port" -> attach = next(a).toIntOrNull()
                ?: run { System.err.println("--attach needs a port number"); exitProcess(2) }
            "--timeout" -> timeout = next(a).toLongOrNull()?.coerceIn(10, 3600)
                ?: run { System.err.println("--timeout needs a number of seconds"); exitProcess(2) }
            "--url" -> url = next(a)
            else -> System.err.println(Ansi.dim("Ignoring unknown option '$a' — see `airelay copilot setup --help`."))
        }
        i++
    }
    return CopilotSetup.Options(
        mode = mode, curlFile = file, attachPort = attach, timeoutSeconds = timeout, url = url,
    )
}

fun main(rawArgs: Array<String>) {
    val args = rawArgs.toMutableList()
    if (args.isEmpty() || args[0] in listOf("-h", "--help", "help")) {
        printUsage()
        return
    }

    // Top-level config subcommands stay Gemini's, for compatibility; Claude needs
    // no setup, and Copilot's lives under `airelay copilot setup`.
    when (args[0].lowercase()) {
        "setup", "config" -> { GeminiSetup.run(); return }
        "reset" -> { GeminiSetup.reset(); return }
        "mcp" -> { printMcp(args.drop(1)); return }
        "web" -> { printWeb(); return }
    }

    val backend = args.removeAt(0).lowercase()
    if (backend !in listOf("claude", "gemini", "copilot")) {
        System.err.println(
            "Unknown agent '$backend'. Expected 'claude', 'gemini' or 'copilot' (or 'setup' / 'reset').\n",
        )
        printUsage()
        exitProcess(2)
    }

    // `airelay gemini setup|models|reset` — manage the connection.
    if (backend == "gemini" && args.firstOrNull()?.lowercase() in listOf("setup", "config", "models", "reset")) {
        when (args.first().lowercase()) {
            "reset" -> GeminiSetup.reset()
            "models" -> GeminiSetup.models()
            else -> GeminiSetup.run()
        }
        return
    }

    // `airelay copilot setup|login|models|reset` — manage the captured session.
    if (backend == "copilot" && args.firstOrNull()?.lowercase() in COPILOT_SUBCOMMANDS) {
        val sub = args.removeAt(0).lowercase()
        if (args.firstOrNull() in listOf("-h", "--help")) { CopilotSetup.printSetupHelp(); return }
        when (sub) {
            "reset" -> CopilotSetup.reset()
            "models" -> CopilotSetup.models()
            "test" -> CopilotSetup.test()
            "diagnose" -> CopilotSetup.diagnose()
            "login", "relogin", "refresh" -> CopilotSetup.run(relogin = true, options = captureOptions(args))
            else -> CopilotSetup.run(options = captureOptions(args))
        }
        return
    }

    val opts = parseOptions(args)
    val workspace = Workspace.of(opts.dir, opts.addDirs)
    val prompt = opts.positional.joinToString(" ").trim()
    val oneShot = prompt.isNotEmpty()
    val config = Config.load()

    // Web access and MCP servers are backend-neutral, so they are resolved once
    // here and handed to whichever agent is built. Claude is the exception: its
    // CLI brings its own web search and reads its own MCP config, and giving it
    // a second set would just duplicate every tool.
    val web = buildWeb(config, opts)
    val mcp = if (backend == "claude") McpManager.EMPTY else buildMcp(workspace, config)

    val agent: Agent = when (backend) {
        "claude" -> buildClaude(workspace, opts)
        "copilot" -> buildCopilot(workspace, opts, config, oneShot, mcp, web) ?: exitProcess(1)
        else -> buildGemini(workspace, opts, config, oneShot, mcp, web) ?: exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { mcp.close() } })

    // Never leak the Claude subprocess.
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { agent.close() } })

    // Register Ctrl-C handler
    try {
        sun.misc.Signal.handle(sun.misc.Signal("INT")) {
            if (turnActive) {
                agent.cancel()
            } else {
                exitProcess(0)
            }
        }
    } catch (e: Throwable) {
        // Fallback for JVMs without sun.misc.Signal
    }

    val sink = ConsoleSink()
    printBanner(agent, workspace, oneShot)

    if (oneShot) {
        turnActive = true
        try {
            agent.send(prompt, sink)
        } finally {
            turnActive = false
            agent.close()
        }
        return
    }
    repl(agent, sink, backend)
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

/**
 * Web access for the agent, or null when it is switched off. Unconfigured is not
 * the same as off: `fetchUrl` needs no provider at all, and search falls back to
 * a keyless provider, so the default is on.
 */
private fun buildWeb(config: Config, opts: Options): Web? {
    if (opts.noWeb) return null
    val web = Web(config)
    return web.takeIf { it.enabled }
}

/**
 * The configured MCP servers. A malformed config file is reported and then
 * ignored — the user asked for an agent, not for a config validator, and losing
 * MCP is not a reason to refuse to start.
 */
private fun buildMcp(workspace: Workspace, config: Config): McpManager =
    runCatching { McpManager(McpConfig.load(workspace.primary, config)) }
        .getOrElse {
            System.err.println(Ansi.yellow("MCP config ignored: ${it.message}"))
            McpManager.EMPTY
        }

/**
 * `airelay web` — what the agent can reach, proven rather than described. Each
 * tool is actually called once: the useful question is not "is a key set" but
 * "does a request from this machine, through whatever proxy is in the way, come
 * back with an answer".
 */
private fun printWeb() {
    val web = Web(Config.load())
    println()
    println(Ansi.bold("Web access"))
    if (!web.enabled) {
        println(Ansi.yellow("  disabled") + Ansi.dim("  (web.enabled=false)"))
        return
    }

    val unavailable = web.searchUnavailable()
    if (unavailable == null) {
        println("  ${Ansi.dim("search  ")} ${Ansi.cyan(web.provider.id)}")
    } else {
        println("  ${Ansi.dim("search  ")} ${Ansi.yellow("unavailable")} ${Ansi.dim(unavailable)}")
    }

    fun probe(label: String, tool: String, args: JsonObject) {
        val out = runCatching { web.execute(tool, args) }.getOrElse {
            println("  ${Ansi.red("✗")} $label ${Ansi.dim(it.message.orEmpty())}"); return
        }
        val error = out.get("error")?.asString
        if (error != null) {
            println("  ${Ansi.red("✗")} $label ${Ansi.dim(error.lineSequence().first().take(120))}")
        } else {
            val first = out.get("result")?.asString.orEmpty().lineSequence()
                .firstOrNull { it.isNotBlank() }.orEmpty().take(90)
            println("  ${Ansi.green("✓")} $label ${Ansi.dim(first)}")
        }
    }

    println()
    probe("fetchUrl   ", "fetchUrl", JsonObject().apply { addProperty("url", "https://example.com") })
    probe("mavenSearch", "mavenSearch", JsonObject().apply { addProperty("artifactId", "spring-boot-starter-web") })
    if (unavailable == null) {
        probe("webSearch  ", "webSearch", JsonObject().apply { addProperty("query", "spring boot 4 migration guide") })
    }
}

/** `airelay mcp [list]` — what MCP servers are configured, and do they start. */
private fun printMcp(args: List<String>) {
    val config = Config.load()
    val root = java.io.File(".").canonicalFile
    val file = McpConfig.file(root, config)
    println()
    if (file == null) {
        println(Ansi.yellow("No MCP config found."))
        println(Ansi.dim("Searched: " + McpConfig.candidates(root, config).joinToString(", ") { tilde(it.path) }))
        println(Ansi.dim("Create one in the same \"mcpServers\" shape Claude Desktop and Claude Code use."))
        return
    }
    println(Ansi.bold("MCP servers") + Ansi.dim("  ${tilde(file.path)}"))
    val servers = runCatching { McpConfig.load(root, config) }.getOrElse {
        System.err.println(Ansi.red(it.message ?: "Could not read the MCP config."))
        return
    }
    if (servers.isEmpty()) {
        println(Ansi.dim("  (none declared)"))
        return
    }
    // `list` alone reads the file; connecting is what proves a server actually works.
    val probe = args.firstOrNull()?.lowercase() != "list"
    for (s in servers) {
        val state = if (!s.enabled) Ansi.dim("disabled") else Ansi.dim("${s.command} ${s.args.joinToString(" ")}".trim())
        println("  ${Ansi.cyan(s.name)}  $state")
    }
    if (!probe) return
    println()
    val manager = McpManager(servers)
    try {
        val specs = manager.specs()
        manager.lastErrors().forEach { println(Ansi.red("  ✗ $it")) }
        if (specs.isEmpty()) println(Ansi.yellow("  no tools available"))
        else specs.forEach { println("  ${Ansi.green("✓")} ${it.name}") }
    } finally {
        manager.close()
    }
}

private fun buildGemini(
    workspace: Workspace,
    opts: Options,
    config: Config,
    oneShot: Boolean,
    mcp: McpManager,
    web: Web?,
): GeminiAgent? {
    val modeOverride = opts.geminiMode?.let { ConnectionMode.from(it) }
    var gcfg = GeminiConfig(config, modeOverride, opts.model)
    gcfg.missingCredentials()?.let { missing ->
        // Not configured. Offer the wizard when we have an interactive terminal.
        if (!oneShot && System.console() != null) {
            println(Ansi.yellow("Gemini isn't configured yet") + Ansi.dim(" (${gcfg.connectionMode.label}: $missing)"))
            if (com.chelayel.airelay.cli.Prompt.confirm("Run setup now?", default = true)) {
                GeminiSetup.run()
                gcfg = GeminiConfig(Config.load(), modeOverride, opts.model)
            }
        }
        gcfg.missingCredentials()?.let { still ->
            System.err.println(Ansi.red("Gemini is not configured for ${gcfg.connectionMode.label}: $still"))
            System.err.println(Ansi.dim("Run `airelay gemini setup`, or set env vars (see README)."))
            return null
        }
    }
    // One-shot runs can't prompt for each tool, so default them to bypass.
    val default = if (oneShot) PermissionMode.BYPASS else PermissionMode.ACCEPT_EDITS
    val permission = PermissionMode.from(opts.permissionMode, default)
    return GeminiAgent(
        workspace = workspace,
        config = gcfg,
        permission = permission,
        askMode = opts.ask,
        mcp = mcp,
        web = web?.takeIf { gcfg.webEnabled },
        confirm = ::confirmOnConsole,
    )
}

private fun buildCopilot(
    workspace: Workspace,
    opts: Options,
    config: Config,
    oneShot: Boolean,
    mcp: McpManager,
    web: Web?,
): CopilotAgent? {
    var ccfg = CopilotConfig(config, opts.model)
    ccfg.missingCredentials()?.let {
        // Not captured yet. Offer the wizard when we have an interactive terminal.
        if (!oneShot && System.console() != null) {
            println(Ansi.yellow("Copilot isn't set up yet") + Ansi.dim(" ($it)"))
            if (com.chelayel.airelay.cli.Prompt.confirm("Run setup now?", default = true)) {
                CopilotSetup.run()
                ccfg = CopilotConfig(Config.load(), opts.model)
            }
        }
        ccfg.missingCredentials()?.let { still ->
            System.err.println(Ansi.red("Copilot is not set up: $still"))
            System.err.println(Ansi.dim("Run `airelay copilot setup` — it walks you through capturing your session."))
            return null
        }
    }
    if (!opts.model.isNullOrBlank() && !ccfg.canChooseModel) {
        System.err.println(Ansi.yellow("--model is ignored: the captured request has no model field."))
        System.err.println(Ansi.dim("Re-capture after choosing a model in the web picker to enable it."))
    }
    // One-shot runs can't prompt for each tool, so default them to bypass.
    val default = if (oneShot) PermissionMode.BYPASS else PermissionMode.ACCEPT_EDITS
    return CopilotAgent(
        workspace = workspace,
        config = ccfg,
        permission = PermissionMode.from(opts.permissionMode, default),
        askMode = opts.ask,
        mcp = mcp,
        web = web,
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

private fun repl(agent: Agent, sink: ConsoleSink, backend: String) {
    while (true) {
        print(Ansi.green("\n› "))
        System.out.flush()
        val line = runCatching { readlnOrNull() }.getOrNull() ?: break
        val trimmed = line.trim()
        val command = trimmed.substringBefore(' ')
        val argument = trimmed.substringAfter(' ', "").trim()
        when {
            trimmed.isEmpty() -> continue
            command == "/exit" || command == "/quit" -> break
            command == "/help" -> { printReplHelp(backend); continue }
            command == "/model" -> { switchModel(agent, argument); continue }
            command == "/reset" -> {
                if (backend == "copilot") CopilotSetup.reset() else GeminiSetup.reset()
                continue
            }
            command == "/setup" -> {
                println(Ansi.dim("Changes apply on next launch."))
                if (backend == "copilot") CopilotSetup.run() else GeminiSetup.run()
                continue
            }
        }
        println()
        turnActive = true
        try {
            agent.send(trimmed, sink)
        } finally {
            turnActive = false
        }
    }
    agent.close()
    println(Ansi.dim("\nbye"))
}

/**
 * `/model` — switch models mid-session, the way the Copilot web picker does.
 * Only Copilot can do this live: the Claude subprocess and the Gemini client
 * both bind their model when the session starts.
 */
private fun switchModel(agent: Agent, argument: String) {
    if (agent !is CopilotAgent) {
        println(Ansi.dim("/model only works with the copilot backend; use -m NAME at launch."))
        return
    }
    if (!agent.canChooseModel()) {
        println(Ansi.yellow("The captured request has no model field, so the model can't be switched."))
        return
    }
    val models = agent.availableModels()
    if (argument.isBlank()) {
        println(Ansi.bold("Models"))
        if (models.isEmpty()) println(Ansi.dim("  none saved — add them with `airelay copilot setup`"))
        for (m in models) println("  ${if (m == agent.currentModel()) Ansi.green("›") else " "} $m")
        println(Ansi.dim("Switch with /model NAME."))
        return
    }
    // An unlisted id is still allowed — the picker gains models faster than any saved list.
    val chosen = models.firstOrNull { it.equals(argument, true) } ?: argument
    agent.useModel(chosen)
    println(Ansi.green("✓ ") + Ansi.dim("now using $chosen"))
}

// ---- option parsing ---------------------------------------------------------

private class Options {
    var model: String? = null
    var dir: String? = null
    val addDirs = mutableListOf<String>()
    var permissionMode: String? = null
    var ask = false
    var geminiMode: String? = null
    var noWeb = false
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
            "--no-web" -> o.noWeb = true
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
    val bar = Ansi.cyan("▍")
    val ctx = workspace.roots.joinToString(Ansi.dim(", ")) { tilde(it.path) }
    println()
    println("$bar ${Ansi.bold("AI Relay")}   ${agent.describe()}")
    println("$bar ${Ansi.dim("context")}   $ctx")
    if (!oneShot) println("$bar ${Ansi.dim("commands")}  ${Ansi.dim("/help  /setup  /reset  /exit")}")
    println(Ansi.dim(rule()))
}

/** Collapse the home dir to ~ for tidier paths. */
private fun tilde(path: String): String {
    val home = System.getProperty("user.home") ?: return path
    return if (path == home) "~" else if (path.startsWith("$home/")) "~" + path.removePrefix(home) else path
}

private fun rule(): String {
    val width = System.getenv("COLUMNS")?.toIntOrNull()?.coerceIn(20, 100) ?: 52
    return "─".repeat(width)
}

private fun printReplHelp(backend: String) {
    val what = if (backend == "copilot") "Copilot capture" else "Gemini connection"
    val cleared = if (backend == "copilot") "captured Copilot session" else "saved Gemini credentials"
    println(
        """
        ${Ansi.bold("Commands")}
          ${Ansi.cyan("/help")}            show this help
          ${Ansi.cyan("/setup")}           reconfigure the $what
          ${Ansi.cyan("/reset")}           clear the $cleared
          ${Ansi.cyan("/model")} [NAME]    show or switch models ${Ansi.dim("(copilot)")}
          ${Ansi.cyan("/exit")}, ${Ansi.cyan("/quit")}     leave
        Anything else is sent to the agent as a message.
        """.trimIndent(),
    )
}

private fun printUsage() {
    println(
        """
        ${Ansi.bold("AI Relay")} — Claude, Gemini & Copilot as CLI coding agents.

        ${Ansi.bold("Usage")}
          airelay claude [options] [prompt]
          airelay gemini [options] [prompt]
          airelay copilot [options] [prompt]
          airelay gemini setup       configure the Gemini connection (interactive)
          airelay gemini models      list the models this connection can call
          airelay gemini reset       clear saved Gemini credentials
          airelay copilot setup      capture your signed-in Copilot session (opens a browser)
          airelay copilot setup --browser   drive the Copilot page instead of replaying
          airelay copilot login      re-capture it after the browser session expires
          airelay copilot models     list the models the capture can switch between
          airelay copilot diagnose   find which field of the reply holds the answer
          airelay copilot reset      clear the captured Copilot session
          airelay mcp                list the configured MCP servers and their tools
          airelay mcp list           list them without starting the servers

        With a prompt: run it once and exit. Without: start an interactive session.

        ${Ansi.bold("Common options")}
          -C, --dir PATH          working directory / repo root (default: cwd)
              --add-dir PATH      extra directory the agent may read/search (repeatable)
          -m, --model NAME        model id ${Ansi.dim("(gemini default: ${GeminiConfig.DEFAULT_MODEL})")}
              --permission-mode M  ask | acceptEdits | bypass
              --yolo              alias for --permission-mode bypass
              --ask               read-only Q&A, no tools (gemini, copilot)
              --no-web            no webSearch / fetchUrl this run (gemini, copilot)

        ${Ansi.bold("gemini options")}
              --mode M            gemini-api | vertex | apigee  (default: gemini-api or AIRELAY_GEMINI_MODE)

        ${Ansi.bold("claude options")}
              --agent NAME        run as a named claude sub-agent
              --disallow TOOL     disallow a tool (repeatable)

        ${Ansi.bold("copilot setup options")}  ${Ansi.dim("(see `airelay copilot setup --help`)")}
              --browser           drive the page instead of replaying a request
              --url URL           page to open      --attach PORT   use your own browser
              --timeout SECONDS   how long to wait  --file PATH     read a saved cURL instead

        ${Ansi.bold("Connection")}
          claude   → local `claude` CLI, using whatever it is already logged in with.
          gemini   → Gemini API key, Vertex AI (gcloud), or Vertex-via-Apigee.
                     Configure via env vars or ~/.airelay/config.properties (see README).
          copilot  → replays one request captured from your own signed-in Copilot
                     web session (SSO and all), so `-m` picks the same models the
                     site's model picker offers. `airelay copilot setup` opens a
                     browser, you sign in, and it captures the request itself.
        """.trimIndent(),
    )
}
