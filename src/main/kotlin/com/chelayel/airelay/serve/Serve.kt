package com.chelayel.airelay.serve

import com.chelayel.airelay.cli.Ansi
import com.chelayel.airelay.config.Config
import com.chelayel.airelay.gemini.api.ConnectionMode
import com.chelayel.airelay.gemini.api.GeminiConfig
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `airelay serve [--port N] [--mode M] [--no-token] [--background | --json]`,
 * `airelay serve status`, `airelay serve stop`.
 *
 * The token is made once and kept in the config file (`serve.token`), so a
 * client set up with it keeps working across restarts. `--no-token` (or
 * `serve.token=off`) is for Ollama clients, which send no key.
 *
 * `--background` starts the server as a detached process and returns the
 * terminal: the Copilot clients set up by URL need it running, and a terminal
 * held open for it is one closed by accident. The running server records its
 * pid and port in `~/.airelay/serve.json` (the token stays in the config file
 * only), which is how `status` and `stop` find it — a foreground one too.
 *
 * `--json` is for the VS Code extension: one `serving` line on stdout with the
 * address and token, and the server stops when stdin closes, so it never
 * outlives the editor that started it.
 */
object Serve {
    const val DEFAULT_PORT = 11435

    fun run(args: List<String>): Int {
        if (args.any { it in setOf("-h", "--help") }) { printHelp(); return 0 }
        when (args.firstOrNull()) {
            "status" -> return status()
            "stop" -> return stop()
            "uninstall" -> {
                val removed = ServeAutostart.uninstall()
                stop()
                println(removed?.let { "No longer starts at login (removed from $it)." } ?: "It was not set to start at login.")
                return 0
            }
        }
        val install = args.firstOrNull() == "install"
        val options = if (install) args.drop(1) else args
        val config = Config.load()
        var port = config.getInt("serve.port", DEFAULT_PORT)
        var mode: ConnectionMode? = null
        var noToken = config.get("serve.token")?.equals("off", true) == true
        var json = false
        var background = false
        var logPath: String? = null
        val it = options.iterator()
        while (it.hasNext()) {
            when (val a = it.next()) {
                "--port", "-p" -> port = it.nextOrNull()?.toIntOrNull() ?: return usage("--port needs a number")
                "--mode" -> mode = it.nextOrNull()?.let { ConnectionMode.from(it) } ?: return usage("--mode needs gemini-api, vertex or apigee")
                "--no-token" -> noToken = true
                "--json" -> json = true
                "--background", "-b", "--detach" -> background = true
                "--log" -> logPath = it.nextOrNull() ?: return usage("--log needs a file")
                else -> return usage("Unknown option: $a")
            }
        }
        if (json && (background || install)) return usage("--json does not go with --background or install")
        // Detached (background, or started at login), the server writes its own log: a
        // login item has no terminal, and launchd/systemd/javaw would otherwise drop it.
        logPath?.let { path ->
            val out = java.io.PrintStream(java.io.FileOutputStream(path, true), true, Charsets.UTF_8)
            System.setOut(out); System.setErr(out)
        }

        // Resolved before any child is started, so parent and child agree on it.
        val token = if (noToken) null else config.get("serve.token")?.takeIf { it.isNotBlank() } ?: newToken().also { Config.writeAll(mapOf("serve.token" to it)) }
        val log: (String) -> Unit = if (json) { line -> System.err.println(line) } else { line -> println(Ansi.dim(line)) }
        val server = RelayServer(port, token, mode, log = log)
        val cfg = server.geminiConfig()
        cfg.missingCredentials()?.let { problem ->
            return fail("The Gemini connection is not set up: $problem Run `airelay gemini setup`.", json)
        }
        val childArgs = options.filter { a -> a !in setOf("--background", "-b", "--detach") }
        if (install) {
            running()?.let { stop() }
            return installAtLogin(childArgs, token, cfg, server)
        }
        // The extension's private server (--json, a free port) coexists with a background one.
        if (!json) running()?.let { r ->
            return fail("AI Relay is already serving on ${r.url} (pid ${r.pid}). `airelay serve stop` first, or `airelay serve status`.", json)
        }
        if (background) return startInBackground(childArgs, port, token, cfg, server)

        val bound = try { server.start() } catch (e: java.net.BindException) {
            return fail("Port $port is in use. Pass --port with another, or stop what is listening there.", json)
        }
        // People read `localhost` (the server listens on 127.0.0.1 and ::1 for it); the
        // extension gets 127.0.0.1, which needs no resolving.
        val url = if (json) "http://127.0.0.1:$bound" else "http://localhost:$bound"
        val models = runCatching { server.models() }.getOrDefault(emptyList())

        if (json) {
            println(JsonObject().apply {
                addProperty("type", "serving"); addProperty("url", url); addProperty("port", bound)
                token?.let { t -> addProperty("token", t) }
                add("models", JsonArray().apply { models.forEach { m -> add(m) } })
            })
            System.out.flush()
        } else {
            writeState(bound, url, logPath)
            // Detached, this goes to serve.log: the key stays in the config file, not in a log.
            printBanner(cfg, url, token, models, showKey = logPath == null)
            println(Ansi.dim(if (logPath != null) "Running in the background. Stop: airelay serve stop." else "Ctrl-C to stop. To keep it running: airelay serve install (starts at every login), or airelay serve --background. Clients: docs/serve.md."))
        }

        val done = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(); if (!json) clearState(); done.countDown() })
        if (json) Thread {
            runCatching { while (System.`in`.read() >= 0) { /* the parent's end of the pipe */ } }
            server.stop(); done.countDown()
        }.apply { isDaemon = true; start() }
        done.await()
        return 0
    }

    // ---- background --------------------------------------------------------------

    /**
     * The same command, minus `--background`, as a process of its own that
     * outlives the terminal (see [ServeAutostart.selfCommand] for how this
     * install starts itself). Output goes to `~/.airelay/serve.log`. Returns
     * once the server answers, with the same banner a foreground start prints.
     */
    private fun startInBackground(childArgs: List<String>, port: Int, token: String?, cfg: GeminiConfig, server: RelayServer): Int {
        runCatching { ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close() }.onFailure {
            return fail("Port $port is in use. Pass --port with another, or stop what is listening there.", false)
        }
        val logFile = logFile()
        val process = try {
            ServeAutostart.startDetached(ServeAutostart.selfCommand(childArgs + listOf("--log", logFile.path)), logFile)
        } catch (e: Exception) {
            return fail("Could not start the server in the background: ${e.message}. Run `airelay serve install` or `airelay serve` instead.", false)
        }

        return awaitServing(logFile, cfg, token, server, { process?.isAlive ?: true }, { if (process?.isAlive == true) process.destroy() }) { pid ->
            "Running in the background (pid $pid). " + Ansi.dim("Log: ${tilde(logFile)} · status: airelay serve status · stop: airelay serve stop")
        }
    }

    /** `airelay serve install`: registered to start at login, and started now. */
    private fun installAtLogin(childArgs: List<String>, token: String?, cfg: GeminiConfig, server: RelayServer): Int {
        val logFile = logFile()
        val where = ServeAutostart.install(childArgs + listOf("--log", logFile.path)).getOrElse {
            return fail("Could not set it to start at login: ${it.message}", false)
        }
        return awaitServing(logFile, cfg, token, server, { true }, {}) { pid ->
            "Running (pid $pid), and starts at login through $where.\n" +
                Ansi.dim("Log: ${tilde(logFile)} · status: airelay serve status · stop until next login: airelay serve stop · remove: airelay serve uninstall")
        }
    }

    /** Waits for a server just launched elsewhere to answer, then prints the banner; or the log's tail if it never does. */
    private fun awaitServing(
        logFile: File, cfg: GeminiConfig, token: String?, server: RelayServer,
        alive: () -> Boolean, abandon: () -> Unit, footer: (Long) -> String,
    ): Int {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            running()?.let { r ->
                val models = runCatching { server.models() }.getOrDefault(emptyList())
                printBanner(cfg, r.url, token, models)
                println(footer(r.pid))
                return 0
            }
            if (!alive()) break
            Thread.sleep(200)
        }
        val tail = runCatching { logFile.readLines().takeLast(15).joinToString("\n") }.getOrDefault("")
        abandon()
        return fail("The server did not come up. From ${tilde(logFile)}:\n$tail", false)
    }

    private fun logFile(): File = File(stateDir(), "serve.log").also { f ->
        if (f.length() > 5_000_000) f.renameTo(File(stateDir(), "serve.log.1"))
    }

    // ---- status / stop -----------------------------------------------------------

    private class Running(val pid: Long, val url: String, val log: String?, val startedAt: String?)

    private fun stateDir(): File = Config.file().absoluteFile.parentFile.also { it.mkdirs() }
    private fun stateFile() = File(stateDir(), "serve.json")

    private fun writeState(port: Int, url: String, log: String?) = runCatching {
        stateFile().writeText(JsonObject().apply {
            addProperty("pid", ProcessHandle.current().pid()); addProperty("port", port); addProperty("url", url)
            log?.let { addProperty("log", it) }
            addProperty("startedAt", Instant.now().toString())
        }.toString())
    }

    /** Only our own record: a second server must not erase the first one's. */
    private fun clearState() = runCatching {
        val f = stateFile()
        val pid = JsonParser.parseString(f.readText()).asJsonObject.get("pid")?.asLong
        if (pid == ProcessHandle.current().pid()) f.delete()
    }

    /**
     * The recorded server, if it is really there: its process alive and its port
     * answering as AI Relay. A record left by a crash or a reboot is removed.
     */
    private fun running(): Running? {
        val f = stateFile()
        if (!f.isFile) return null
        val o = runCatching { JsonParser.parseString(f.readText()).asJsonObject }.getOrNull()
        val pid = o?.get("pid")?.asLong
        val url = o?.get("url")?.asString
        val alive = pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        if (o == null || pid == null || url == null || !alive || !answers(url)) { f.delete(); return null }
        return Running(pid, url, o.get("log")?.asString, o.get("startedAt")?.asString)
    }

    private fun answers(url: String): Boolean = runCatching {
        val c = URI("$url/").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 1000; c.readTimeout = 1500
        c.inputStream.use { it.readBytes().decodeToString() }.startsWith("AI Relay")
    }.getOrDefault(false)

    private fun status(): Int {
        val atLogin = ServeAutostart.installed()
        val r = running() ?: run {
            println("AI Relay is not serving. Start it now and at every login: " + Ansi.bold("airelay serve install") + Ansi.dim("  (or just for now: airelay serve --background)"))
            atLogin?.let { println(Ansi.dim("It is set to start at login through $it.")) }
            return 1
        }
        val config = Config.load()
        val token = config.get("serve.token")?.takeIf { it.isNotBlank() && !it.equals("off", true) }
        val cfg = GeminiConfig(config)
        val models = runCatching { RelayServer(0, token).models() }.getOrDefault(emptyList())
        printBanner(cfg, r.url, token, models)
        println("Running (pid ${r.pid}" + (r.startedAt?.let { ", since $it" } ?: "") + ")." + (r.log?.let { Ansi.dim(" Log: ${tilde(File(it))}") } ?: ""))
        println(atLogin?.let { "Starts at login through $it." } ?: Ansi.dim("Not set to start at login (airelay serve install)."))
        return 0
    }

    private fun stop(): Int {
        val r = running() ?: run { println("AI Relay is not serving."); return 0 }
        val handle = ProcessHandle.of(r.pid).orElse(null) ?: run { stateFile().delete(); println("Stopped."); return 0 }
        handle.destroy()
        val exited = runCatching { handle.onExit().get(5, TimeUnit.SECONDS); true }.getOrDefault(false)
        if (!exited) handle.destroyForcibly()
        stateFile().delete()
        println("Stopped AI Relay (pid ${r.pid}) on ${r.url}.")
        return 0
    }

    // ---- output --------------------------------------------------------------------

    private fun printBanner(cfg: GeminiConfig, url: String, token: String?, models: List<String>, showKey: Boolean = true) {
        val key = token?.let { if (showKey) it else "(serve.token in ${tilde(Config.file())})" }
        println(Ansi.bold("AI Relay") + " is serving Gemini (${cfg.connectionMode.label}) on " + Ansi.cyan(url))
        println("  OpenAI-compatible  " + Ansi.cyan("$url/v1") + if (key != null) "   API key: $key" else "   (no API key)")
        if (token == null) println("  Ollama-compatible  " + Ansi.cyan(url))
        else println(Ansi.dim("  Ollama-compatible  $url — only with --no-token (Ollama clients send no key); Copilot uses the OpenAI one"))
        println("  models             " + models.joinToString(", ").ifEmpty { Ansi.dim("none configured") })
        if (models.isNotEmpty()) printModelForm(url, token, key, models)
    }

    /**
     * What Copilot's custom-model form asks for, one entry per model, ready to
     * copy: model id, deployment URL, key, capabilities and limits.
     */
    private fun printModelForm(url: String, token: String?, key: String?, models: List<String>) {
        println()
        println(Ansi.bold("Add to Copilot") + Ansi.dim(" (VS Code or JetBrains: Manage Models → Add Models → Custom Endpoint) — one entry per model:"))
        println("  Deployment URL     " + Ansi.cyan("$url/v1/chat/completions"))
        println("  API key            " + (key ?: Ansi.dim("(none — leave blank)")))
        val width = models.maxOf { it.length }.coerceAtLeast(8)
        println(Ansi.dim("  " + "Model ID".padEnd(width) + "  Name                        Tools  Vision  Max input  Max output"))
        for (id in models) {
            val l = ModelInfo.limits(id)
            println("  " + Ansi.cyan(id.padEnd(width)) + "  " + ModelInfo.displayName(id).take(26).padEnd(26) + "  " +
                (if (l.tools) "yes" else "no").padEnd(5) + "  " + (if (l.images) "yes" else "no").padEnd(6) + "  " +
                l.promptTokens.toString().padStart(9) + "  " + l.outputTokens.toString().padStart(10))
        }
        println()
    }

    private fun fail(text: String, json: Boolean): Int {
        if (json) println(JsonObject().apply { addProperty("type", "error"); addProperty("text", text) }) else System.err.println(Ansi.red(text))
        return 1
    }

    private fun tilde(f: File): String {
        val home = System.getProperty("user.home")
        return if (home != null && f.path.startsWith(home)) "~" + f.path.removePrefix(home) else f.path
    }

    private fun newToken(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return "air-" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun Iterator<String>.nextOrNull(): String? = if (hasNext()) next() else null

    private fun usage(problem: String): Int { System.err.println(Ansi.red(problem)); printHelp(); return 2 }

    private fun printHelp() = println(
        """
        ${Ansi.bold("airelay serve")} — the Gemini connection as a local model server, for GitHub Copilot and other clients.

          airelay serve install       recommended: start it now and at every login (launchd / systemd --user / Startup folder)
          airelay serve uninstall     stop starting it at login
          airelay serve --background  run it detached until stopped or logged out; output to ~/.airelay/serve.log
          airelay serve [options]     run it in this terminal (Ctrl-C to stop)
          airelay serve status        whether it is running, with what to enter in Copilot
          airelay serve stop          stop it

          --port N      port on localhost (default $DEFAULT_PORT, or serve.port)
          --mode M      gemini-api | vertex | apigee (default: the configured one)
          --no-token    accept requests without the API key — only for clients that connect as "Ollama"
          --json        one JSON line with the address, for the IDE extension

        Endpoints: /v1/models, /v1/chat/completions (OpenAI); /api/tags, /api/show, /api/chat (Ollama).
        """.trimIndent(),
    )
}
