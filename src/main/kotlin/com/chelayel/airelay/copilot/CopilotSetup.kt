package com.chelayel.airelay.copilot

import com.chelayel.airelay.cli.Ansi
import com.chelayel.airelay.cli.Prompt
import com.chelayel.airelay.config.Config
import com.chelayel.airelay.copilot.api.BodyTemplate
import com.chelayel.airelay.copilot.api.BrowserCapture
import com.chelayel.airelay.copilot.api.CopilotClient
import com.chelayel.airelay.copilot.api.CopilotConfig
import com.chelayel.airelay.copilot.api.CurlImport
import java.io.File
import kotlin.random.Random

/**
 * The wizard that teaches AI Relay how to talk to Copilot.
 *
 * There is no API key and no documented endpoint: the Copilot web app is what
 * the user is signed into, through their organisation's SSO, and this backend
 * works by replaying one real request from that session. Setup's whole job is
 * getting hold of that request.
 *
 * The default route is [BrowserCapture] — drive a real browser, let the user
 * sign in normally, and read the request off the wire. The manual route (copy
 * the request out of DevTools) still exists as a fallback, but it reads the
 * command from a *file*: an M365 Copilot request is tens of kilobytes on one
 * line, and a terminal truncates any single line at about 4 KB, so pasting one
 * at a prompt silently loses most of it.
 *
 * Either way the capture holds a live session token. It is written to
 * `~/.airelay/config.properties`, owner-only, and expires when the browser
 * session does — at which point `airelay copilot login` re-captures it.
 */
object CopilotSetup {

    /** Where the M365 Copilot chat lives, unless the user says otherwise. */
    const val DEFAULT_URL = "https://m365.cloud.microsoft/chat"

    /** Options parsed from `airelay copilot setup|login` flags. */
    class Options(
        /** Read a saved `Copy as cURL` from this file instead of driving a browser. */
        val curlFile: String? = null,
        /** Attach to a browser already running with `--remote-debugging-port=PORT`. */
        val attachPort: Int? = null,
        /** How long to wait for the user to sign in and send the message. */
        val timeoutSeconds: Long = 300,
        /** The page to open. */
        val url: String? = null,
    )

    /** Run the wizard. [relogin] keeps model settings and refreshes only the session. */
    fun run(relogin: Boolean = false, options: Options = Options()): Boolean {
        val existing = Config.load()
        println()
        println(
            if (relogin) Ansi.bold("Copilot login") + Ansi.dim(" — refresh the captured browser session")
            else Ansi.bold("Copilot setup") + Ansi.dim(" — teach AI Relay to reuse your Copilot session"),
        )

        val captured = obtainCapture(options, existing) ?: return false
        println()
        println(Ansi.green("✓ Captured ") + Ansi.dim("${captured.method} ${hostOf(captured.url)}"))
        println(
            Ansi.dim("  ${captured.headers.size} header(s); session carried by ") +
                (captured.authHeaderName()?.let { Ansi.dim(it) }
                    ?: Ansi.red("nothing — you may not have been signed in")),
        )

        val body = captured.body
        if (body.isNullOrBlank()) {
            println(Ansi.red("That request had no body, so it isn't the chat request."))
            return false
        }

        val template = resolveTemplate(body, lastNonce) ?: return false
        println(Ansi.green("✓ Prompt field ") + Ansi.dim(template.promptPath ?: "literal substitution"))

        val updates = mutableMapOf<String, String?>(
            "copilot.endpoint" to captured.url,
            "copilot.method" to captured.method,
            "copilot.headers" to CopilotConfig.headersToJson(captured.headers),
            "copilot.body" to template.raw,
            "copilot.body.json" to template.isJson.toString(),
            "copilot.prompt.path" to template.promptPath,
            "copilot.model.path" to template.modelPath,
            "copilot.conversation.path" to template.conversationPath,
            "copilot.conversation.id" to template.capturedConversationId(),
            "copilot.url" to (options.url ?: existing.get("copilot.url") ?: DEFAULT_URL),
        )

        val capturedModel = template.capturedModel()
        if (template.modelPath == null) {
            println(Ansi.yellow("• No model field found in that request."))
            println(Ansi.dim("  Copilot will use whatever model the conversation is already set to."))
            println(Ansi.dim("  For --model, pick a model in the web picker, send a message, and capture that one."))
        } else {
            println(Ansi.green("✓ Model field ") + Ansi.dim(template.modelPath + (capturedModel?.let { " = $it" } ?: "")))
        }

        if (!relogin || existing.get("copilot.models").isNullOrBlank()) {
            val listed = if (template.modelPath == null) emptyList() else Prompt.lines(
                "Model ids from the picker",
                hint = "The ids as they appear in requests (e.g. the value above). Blank to skip.",
            )
            val models = (listed + listOfNotNull(capturedModel)).distinct().filter { it.isNotBlank() }
            updates["copilot.models"] = models.joinToString(",")
            updates["copilot.model"] = when {
                models.isEmpty() -> capturedModel.orEmpty()
                models.size == 1 -> models.first()
                else -> models[Prompt.choose("Default model:", models.map { it to "" })]
            }
        }

        val file = Config.writeAll(updates)
        println()
        println(Ansi.green("✓ Saved ") + Ansi.dim(file.path) + Ansi.dim(" (owner-only)"))
        println(Ansi.dim("  The capture includes your session token — treat that file as a credential."))

        if (Prompt.confirm("Test the connection now?", default = true)) test()
        return true
    }

    /** The nonce the last capture asked the user to send, for prompt-field detection. */
    private var lastNonce: String = ""

    private fun obtainCapture(options: Options, existing: Config): CurlImport.Captured? {
        options.curlFile?.let { return fromFile(it) }

        val url = options.url ?: existing.get("copilot.url") ?: DEFAULT_URL
        if (options.attachPort == null && BrowserCapture.findBrowser() == null) {
            println()
            println(Ansi.yellow("No Chrome, Chromium or Edge found, so the browser capture can't run."))
            println(Ansi.dim("Use the manual route instead — see `airelay copilot setup --help`."))
            return null
        }
        return fromBrowser(url, options)
    }

    // ---- automatic capture ---------------------------------------------------

    private fun fromBrowser(url: String, options: Options): CurlImport.Captured? {
        val nonce = "airelay-" + Random.nextInt(0x1000, 0xffff).toString(16)
        lastNonce = nonce

        println(
            """

            ${Ansi.dim("A browser window will open on Copilot. AI Relay watches it and reads the")}
            ${Ansi.dim("request your own session makes — nothing is copied or pasted by hand.")}

            ${Ansi.bold("In the window that opens:")}
              1. Sign in as usual ${Ansi.dim("(SSO — only needed the first time)")}.
              2. If you want to choose models later, pick one in the model picker.
              3. Send exactly this message:

                   ${Ansi.bold(Ansi.cyan(nonce))}
            """.trimIndent(),
        )
        println()

        val progress = object : BrowserCapture.Progress {
            override fun status(message: String) = println(Ansi.dim("  $message"))
            override fun hint(message: String) = println(Ansi.dim("  $message"))
        }

        return runCatching {
            BrowserCapture.capture(url, nonce, options.timeoutSeconds, options.attachPort, progress)
        }.getOrElse { e ->
            println(Ansi.red("✗ " + (e.message ?: e.toString())))
            null
        }
    }

    // ---- manual fallback -----------------------------------------------------

    /**
     * Read a `Copy as cURL` saved to a file. Deliberately file-only: the same
     * text pasted at a prompt would be truncated by the terminal long before it
     * reached us.
     */
    private fun fromFile(path: String): CurlImport.Captured? {
        val file = File(path)
        if (!file.isFile) {
            println(Ansi.red("No such file: $path"))
            return null
        }
        val text = runCatching { file.readText() }.getOrElse {
            println(Ansi.red("Could not read $path: ${it.message}"))
            return null
        }
        println(Ansi.dim("Read ${text.length} characters from ${file.name}."))

        val captured = runCatching { CurlImport.parse(text) }.getOrElse { e ->
            println(Ansi.red("Could not read that: ${e.message}"))
            warnTruncated()
            return null
        }
        if (captured.truncated) {
            warnTruncated()
            return null
        }

        lastNonce = Prompt.required(
            "The message you typed in Copilot",
            hint = "Exactly as sent — this is how setup finds the prompt field in the request.",
        )
        return captured
    }

    /**
     * A capture cut off part-way still parses — the tail just becomes one long
     * unterminated token — so it would otherwise be accepted with half its body
     * missing and fail later in a way that looks like a server problem.
     */
    private fun warnTruncated() {
        println(Ansi.yellow("That capture is cut off — its last quoted value never ends."))
        println(Ansi.dim("  If you pasted it into the file by hand, the paste itself was probably"))
        println(Ansi.dim("  truncated: a Copilot request is tens of KB on one line, and terminals"))
        println(Ansi.dim("  cut a single line at ~4 KB. Get the whole thing instead:"))
        println(Ansi.dim("    • easiest — let AI Relay capture it: `airelay copilot setup`"))
        println(Ansi.dim("    • or in DevTools use Copy as cURL, paste into an editor, save the"))
        println(Ansi.dim("      file, and pass that path to --file"))
    }

    /**
     * Locate the typed prompt inside the captured body. With a browser capture
     * the nonce is known, so this normally succeeds silently.
     */
    private fun resolveTemplate(body: String, knownPrompt: String): BodyTemplate? {
        if (knownPrompt.isNotBlank()) {
            BodyTemplate.from(body, knownPrompt)?.let { return it }
            println(Ansi.red("Couldn't find \"$knownPrompt\" in the captured request."))
        }
        repeat(2) {
            val typed = Prompt.text(
                "The message text as it was sent",
                hint = "Blank to give the field path instead.",
            )
            if (typed.isBlank()) return byPath(body)
            BodyTemplate.from(body, typed)?.let { return it }
            println(Ansi.red("Still couldn't find that text."))
            println(Ansi.dim(body.take(800) + if (body.length > 800) "\n… (truncated)" else ""))
        }
        return byPath(body)
    }

    private fun byPath(body: String): BodyTemplate? {
        val path = Prompt.text(
            "Path to the prompt field",
            hint = "Slash-separated, e.g. messages/0/content. Blank to cancel.",
        )
        if (path.isBlank()) return null
        return BodyTemplate.restore(
            raw = body,
            isJson = runCatching { com.google.gson.JsonParser.parseString(body).isJsonObject }.getOrDefault(false),
            promptPath = path,
            modelPath = null,
            conversationPath = null,
        )
    }

    // ---- the rest ------------------------------------------------------------

    /** Live check: replay the capture with a trivial prompt. */
    fun test() {
        val cfg = CopilotConfig(Config.load())
        cfg.missingCredentials()?.let {
            println(Ansi.red("Not configured: $it"))
            return
        }
        print(Ansi.dim("Testing connection… "))
        System.out.flush()

        runCatching {
            val sb = StringBuilder()
            CopilotClient(cfg).send(
                prompt = "Reply with exactly: OK",
                model = cfg.model.takeIf { it.isNotBlank() },
                conversationId = cfg.conversationId,
            ) { sb.append(it) }
        }.onSuccess { turn ->
            if (turn.text.isBlank()) {
                println(Ansi.yellow("connected, but no text was found in the reply"))
                if (turn.rawSample.isNotBlank()) {
                    println(Ansi.dim("  response started: " + turn.rawSample.take(300).replace("\n", " ⏎ ")))
                    println(Ansi.dim("  set copilot.text.keys to the field holding the text, then retry."))
                }
            } else {
                println(
                    Ansi.green("connected ") +
                        Ansi.dim("(${cfg.hostLabel()} · replied \"${turn.text.trim().take(20)}\")"),
                )
            }
        }.onFailure {
            println(Ansi.red("failed"))
            println(Ansi.red("  " + (it.message ?: it.toString())))
        }
    }

    /** Show the model picker's contents and which one is the default. */
    fun models() {
        val cfg = CopilotConfig(Config.load())
        println()
        if (!cfg.canChooseModel) {
            println(Ansi.yellow("The captured request has no model field, so the model can't be switched."))
            println(Ansi.dim("Pick a model in the web picker, send a message, and re-run `airelay copilot setup`."))
            return
        }
        val models = cfg.models
        if (models.isEmpty()) {
            println(Ansi.dim("No model ids saved. Add them with `airelay copilot setup`."))
            return
        }
        println(Ansi.bold("Copilot models"))
        for (m in models) {
            println("  ${if (m == cfg.model) Ansi.green("›") else " "} $m")
        }
        println(Ansi.dim("Use one for a run with -m NAME, or switch mid-session with /model NAME."))
    }

    /** Clear the stored capture — including the session token. */
    fun reset() {
        println()
        if (Config.load().get("copilot.endpoint") == null) {
            println(Ansi.dim("Nothing to reset — no saved Copilot capture."))
            return
        }
        println(
            Ansi.yellow("This clears the captured Copilot request and its session token") +
                Ansi.dim(" (from ${Config.file().path})."),
        )
        if (!Prompt.confirm("Continue?", default = false)) {
            println(Ansi.dim("Cancelled."))
            return
        }
        val removed = Config.clearKeys(CopilotConfig.ALL_KEYS)
        println(Ansi.green("✓ Cleared ${removed.size} setting(s)."))
        println(Ansi.dim("The browser profile in ~/.airelay/browser is kept; delete it to sign out too."))
    }

    fun printSetupHelp() {
        println(
            """
            ${Ansi.bold("airelay copilot setup")} — capture your signed-in Copilot session.

            ${Ansi.bold("Options")}
              --url URL          page to open (default: $DEFAULT_URL)
              --attach PORT      use a browser you started yourself with
                                 --remote-debugging-port=PORT
              --timeout SECONDS  how long to wait for you to sign in (default 300)
              --file PATH        skip the browser: read a saved `Copy as cURL` from
                                 a file. Never paste one at a prompt — a terminal
                                 truncates a single line at ~4 KB and a Copilot
                                 request is far bigger.

            ${Ansi.bold("Environment")}
              AIRELAY_BROWSER       path to a Chrome/Chromium/Edge binary
              AIRELAY_BROWSER_ARGS  extra flags for the launched browser
            """.trimIndent(),
        )
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
}
