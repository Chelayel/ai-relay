package com.chelayel.airelay.gemini

import com.chelayel.airelay.cli.Ansi
import com.chelayel.airelay.cli.Prompt
import com.chelayel.airelay.config.Config
import com.chelayel.airelay.gemini.api.Content
import com.chelayel.airelay.gemini.api.ConnectionMode
import com.chelayel.airelay.gemini.api.GeminiClient
import com.chelayel.airelay.gemini.api.GeminiConfig
import com.chelayel.airelay.gemini.api.Part

/**
 * The terminal equivalent of Gemini Relay's Settings → Tools form: an interactive
 * wizard that picks a connection mode and collects exactly the fields that mode
 * needs, writes them to `~/.airelay/config.properties` (owner-only), and offers a
 * live connection test. Also handles `reset` (clearing stored credentials).
 */
object GeminiSetup {

    /** The escape hatch in the model menu. */
    private const val OTHER_MODEL = "Other…"

    private val ALL_KEYS = listOf(
        "gemini.mode", "gemini.model", "gemini.api.key",
        "vertex.project", "vertex.location", "vertex.endpoint", "gcloud.path",
        "apigee.token.url", "apigee.client.id", "apigee.client.secret", "apigee.agents",
        "command.timeout.seconds",
    )

    /** Run the wizard. Returns true if a configuration was saved. */
    fun run(): Boolean {
        println()
        println(Ansi.bold("Gemini setup") + Ansi.dim(" — configure how AI Relay reaches Gemini"))
        println(Ansi.dim("Claude needs no setup (it reuses the claude CLI login); this is Gemini only."))
        println()

        val existing = Config.load()
        val current = existing.get("gemini.mode")

        val modeIdx = Prompt.choose(
            "Connection mode:",
            listOf(
                ConnectionMode.GEMINI_API.label to "public Generative Language API with an API key",
                ConnectionMode.VERTEX.label to "standard Vertex AI project (gcloud access token)",
                ConnectionMode.VERTEX_APIGEE.label to "Vertex behind a custom Apigee OAuth gateway",
            ),
            default = ConnectionMode.entries.indexOf(ConnectionMode.from(current)).coerceAtLeast(0),
        )
        val mode = ConnectionMode.entries[modeIdx]

        val updates = mutableMapOf<String, String?>("gemini.mode" to mode.id)

        when (mode) {
            ConnectionMode.GEMINI_API -> {
                updates["gemini.api.key"] = Prompt.secret(
                    "API key",
                    hint = "From Google AI Studio (aistudio.google.com/apikey).",
                )
                updates["gemini.model"] = chooseModel(mode, existing.get("gemini.model"))
            }

            ConnectionMode.VERTEX -> {
                updates["vertex.project"] = Prompt.required("Project ID", existing.get("vertex.project"))
                updates["vertex.location"] = Prompt.text("Location", existing.get("vertex.location") ?: "us-central1")
                updates["gcloud.path"] = Prompt.text(
                    "gcloud path", existing.get("gcloud.path"),
                    hint = "Blank = use `gcloud` on PATH (token via `gcloud auth print-access-token`).",
                )
                updates["gemini.model"] = chooseModel(mode, existing.get("gemini.model"))
            }

            ConnectionMode.VERTEX_APIGEE -> {
                updates["vertex.project"] = Prompt.required("Project ID", existing.get("vertex.project"))
                updates["vertex.location"] = Prompt.text("Location", existing.get("vertex.location") ?: "us-central1")
                updates["vertex.endpoint"] = Prompt.required(
                    "API endpoint host", existing.get("vertex.endpoint"),
                    hint = "Apigee gateway host, e.g. my-gw.example.com",
                )
                updates["apigee.token.url"] = Prompt.required("OAuth token URL", existing.get("apigee.token.url"))
                updates["apigee.client.id"] = Prompt.required("Client ID", existing.get("apigee.client.id"))
                updates["apigee.client.secret"] = Prompt.secret("Client secret")
                val agents = Prompt.lines(
                    "Accessible models",
                    hint = "The model ids this gateway exposes (required).",
                )
                updates["apigee.agents"] = agents.joinToString(",")
                val model = when {
                    agents.isEmpty() -> Prompt.required("Model", existing.get("gemini.model"))
                    agents.size == 1 -> agents.first()
                    else -> agents[Prompt.choose("Default model:", agents.map { it to "" })]
                }
                updates["gemini.model"] = model
            }
        }

        val timeout = Prompt.text("Command timeout (seconds)", existing.get("command.timeout.seconds") ?: "300")
        if (timeout.toIntOrNull() != null) updates["command.timeout.seconds"] = timeout

        val file = Config.writeAll(updates)
        println()
        println(Ansi.green("✓ Saved ") + Ansi.dim(file.path) + Ansi.dim(" (owner-only)"))

        if (Prompt.confirm("Test the connection now?", default = true)) test()
        return true
    }

    /**
     * Pick from the current shortlist, with the saved model preselected. There is
     * always a free-text way out: Google ships models faster than any list baked
     * into a release, and `-m` accepts any id too.
     */
    private fun chooseModel(mode: ConnectionMode, saved: String?): String {
        val current = GeminiConfig.canonicalModel(saved ?: GeminiConfig.DEFAULT_MODEL, mode)
        val suggested = GeminiConfig.modelChoices(mode)
        val options = suggested +
            (if (suggested.none { it.first == current }) listOf(current to "your current setting") else emptyList()) +
            listOf(OTHER_MODEL to "type any model id")
        val chosen = Prompt.choose(
            "Model:", options,
            default = options.indexOfFirst { it.first == current }.coerceAtLeast(0),
        )
        return if (options[chosen].first == OTHER_MODEL) Prompt.required("Model id", current)
        else options[chosen].first
    }

    /**
     * `airelay gemini models` — what the current connection can actually call.
     * In Gemini API mode that comes from the live ListModels response, so it is
     * right on the day it's asked; the other two modes have no equivalent
     * endpoint (Apigee publishes whatever the gateway was told to), so they get
     * the shortlist and their configured ids.
     */
    fun models() {
        val cfg = GeminiConfig(Config.load())
        println()
        cfg.missingCredentials()?.let {
            println(Ansi.yellow("Not configured: $it"))
            println(Ansi.dim("Run `airelay gemini setup` first."))
            return
        }

        val live = if (cfg.connectionMode == ConnectionMode.GEMINI_API) {
            print(Ansi.dim("Fetching… "))
            System.out.flush()
            val result = runCatching { GeminiClient(cfg).listModels() }
            print("\r")
            result.onFailure { println(Ansi.yellow("Live list unavailable: ${it.message}")) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val listed = live.ifEmpty { GeminiConfig.modelChoices(cfg.connectionMode).map { it.first } }
        println(Ansi.bold("Models") + Ansi.dim("  (${cfg.connectionMode.label}${if (live.isEmpty()) " · suggested" else " · live"})"))
        for (id in listed) println("  ${if (id == cfg.model) Ansi.green("›") else " "} $id")
        println()
        println(Ansi.dim("Use one for a single run with `-m ID`, or make it the default with `airelay gemini setup`."))
    }

    /** Live check: resolve the credential and make one tiny request. */
    fun test() {
        print(Ansi.dim("Testing connection… "))
        System.out.flush()
        val cfg = GeminiConfig(Config.load())
        cfg.missingCredentials()?.let {
            println(Ansi.red("not configured: $it"))
            return
        }
        val result = runCatching {
            val sb = StringBuilder()
            GeminiClient(cfg).streamTurn(
                listOf(Content("user", listOf(Part.Text("Reply with exactly: OK")))),
                systemPrompt = null,
                tools = emptyList(),
            ) { sb.append(it) }
            sb.toString().trim()
        }
        result.onSuccess {
            println(Ansi.green("connected ") + Ansi.dim("(${cfg.connectionMode.label} · ${cfg.model} · replied \"${it.take(20)}\")"))
        }.onFailure {
            println(Ansi.red("failed"))
            println(Ansi.red("  " + (it.message ?: it.toString())))
        }
    }

    /** Clear stored Gemini credentials/config. */
    fun reset() {
        val cfg = Config.load()
        val mode = cfg.get("gemini.mode")
        println()
        if (mode == null && !Config.file().isFile) {
            println(Ansi.dim("Nothing to reset — no saved Gemini configuration."))
            return
        }
        println(Ansi.yellow("This clears all saved Gemini connection settings and secrets") +
            Ansi.dim(" (from ${Config.file().path})."))
        if (!Prompt.confirm("Continue?", default = false)) {
            println(Ansi.dim("Cancelled."))
            return
        }
        val removed = Config.clearKeys(ALL_KEYS.map { it })
        println(Ansi.green("✓ Cleared ${removed.size} setting(s)."))
        println(Ansi.dim("Note: environment variables (AIRELAY_*, GEMINI_API_KEY) are not affected."))
    }
}
