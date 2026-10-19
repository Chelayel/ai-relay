package com.chelayel.airelay.serve

import com.chelayel.airelay.cli.Ansi
import com.chelayel.airelay.config.Config
import com.chelayel.airelay.gemini.api.ConnectionMode
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch

/**
 * `airelay serve [--port N] [--mode M] [--no-token] [--json]`.
 *
 * The token is made once and kept in the config file (`serve.token`), so a
 * client set up with it keeps working across restarts. `--no-token` (or
 * `serve.token=off`) is for Ollama clients, which send no key.
 *
 * `--json` is for the VS Code extension: one `serving` line on stdout with the
 * address and token, and the server stops when stdin closes, so it never
 * outlives the editor that started it.
 */
object Serve {
    const val DEFAULT_PORT = 11435

    fun run(args: List<String>): Int {
        if (args.any { it in setOf("-h", "--help") }) { printHelp(); return 0 }
        val config = Config.load()
        var port = config.getInt("serve.port", DEFAULT_PORT)
        var mode: ConnectionMode? = null
        var noToken = config.get("serve.token")?.equals("off", true) == true
        var json = false
        val it = args.iterator()
        while (it.hasNext()) {
            when (val a = it.next()) {
                "--port", "-p" -> port = it.nextOrNull()?.toIntOrNull() ?: return usage("--port needs a number")
                "--mode" -> mode = it.nextOrNull()?.let { ConnectionMode.from(it) } ?: return usage("--mode needs gemini-api, vertex or apigee")
                "--no-token" -> noToken = true
                "--json" -> json = true
                else -> return usage("Unknown option: $a")
            }
        }

        val token = if (noToken) null else config.get("serve.token")?.takeIf { it.isNotBlank() } ?: newToken().also { Config.writeAll(mapOf("serve.token" to it)) }
        val log: (String) -> Unit = if (json) { line -> System.err.println(line) } else { line -> println(Ansi.dim(line)) }
        val server = RelayServer(port, token, mode, log = log)
        val cfg = server.geminiConfig()
        cfg.missingCredentials()?.let { problem ->
            val text = "The Gemini connection is not set up: $problem Run `airelay gemini setup`."
            if (json) println(JsonObject().apply { addProperty("type", "error"); addProperty("text", text) }) else System.err.println(Ansi.red(text))
            return 1
        }
        val bound = try { server.start() } catch (e: java.net.BindException) {
            val text = "Port $port is in use. Pass --port with another, or stop what is listening there."
            if (json) println(JsonObject().apply { addProperty("type", "error"); addProperty("text", text) }) else System.err.println(Ansi.red(text))
            return 1
        }
        val url = "http://127.0.0.1:$bound"
        val models = runCatching { server.models() }.getOrDefault(emptyList())

        if (json) {
            println(JsonObject().apply {
                addProperty("type", "serving"); addProperty("url", url); addProperty("port", bound)
                token?.let { t -> addProperty("token", t) }
                add("models", JsonArray().apply { models.forEach { m -> add(m) } })
            })
            System.out.flush()
        } else {
            println(Ansi.bold("AI Relay") + " is serving Gemini (${cfg.connectionMode.label}) on " + Ansi.cyan(url))
            println("  OpenAI-compatible  " + Ansi.cyan("$url/v1") + if (token != null) "   API key: $token" else "   (no API key)")
            if (token == null) println("  Ollama-compatible  " + Ansi.cyan(url))
            else println(Ansi.dim("  Ollama-compatible  $url — needs --no-token: Ollama clients send no key"))
            println("  models             " + models.joinToString(", ").ifEmpty { Ansi.dim("none configured") })
            if (models.isNotEmpty()) printModelForm(url, token, models)
            println(Ansi.dim("Ctrl-C to stop. Set up a client: see docs/serve.md."))
        }

        val done = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(); done.countDown() })
        if (json) Thread {
            runCatching { while (System.`in`.read() >= 0) { /* the parent's end of the pipe */ } }
            server.stop(); done.countDown()
        }.apply { isDaemon = true; start() }
        done.await()
        return 0
    }

    /**
     * What Copilot's custom-model form asks for, one entry per model, ready to
     * copy: model id, deployment URL, key, capabilities and limits.
     */
    private fun printModelForm(url: String, token: String?, models: List<String>) {
        println()
        println(Ansi.bold("Add to Copilot") + Ansi.dim(" (Manage Models → Custom Endpoint / OpenAI-compatible) — one entry per model:"))
        println("  Deployment URL     " + Ansi.cyan("$url/v1/chat/completions"))
        println("  API key            " + (token ?: Ansi.dim("(none — leave blank)")))
        val width = models.maxOf { it.length }.coerceAtLeast(8)
        println(Ansi.dim("  " + "Model ID".padEnd(width) + "  Name                        Tools  Vision  Max input  Max output"))
        for (id in models) {
            val l = ModelInfo.limits(id)
            println("  " + Ansi.cyan(id.padEnd(width)) + "  " + ModelInfo.displayName(id).take(26).padEnd(26) + "  " +
                (if (l.tools) "yes" else "no").padEnd(5) + "  " + (if (l.images) "yes" else "no").padEnd(6) + "  " +
                l.inputTokens.toString().padStart(9) + "  " + l.outputTokens.toString().padStart(10))
        }
        println()
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

          --port N      port on 127.0.0.1 (default $DEFAULT_PORT, or serve.port)
          --mode M      gemini-api | vertex | apigee (default: the configured one)
          --no-token    accept requests without the API key — for Ollama clients (Copilot for JetBrains)
          --json        one JSON line with the address, for the IDE extension

        Endpoints: /v1/models, /v1/chat/completions (OpenAI); /api/tags, /api/show, /api/chat (Ollama).
        """.trimIndent(),
    )
}
