package com.chelayel.airelay.copilot

import com.chelayel.airelay.cli.Ansi
import com.chelayel.airelay.cli.Prompt
import com.chelayel.airelay.config.Config
import com.chelayel.airelay.copilot.api.BodyTemplate
import com.chelayel.airelay.copilot.api.CopilotClient
import com.chelayel.airelay.copilot.api.CopilotConfig
import com.chelayel.airelay.copilot.api.CurlImport

/**
 * The interactive wizard that teaches AI Relay how to talk to Copilot.
 *
 * There is no API key to paste and no documented endpoint to configure: the
 * Copilot web app is what the user is signed into, through their organisation's
 * SSO, and this backend works by replaying one real request from that session.
 * So setup asks for exactly two things — a request copied out of the browser's
 * DevTools, and the message the user typed to produce it — and derives
 * everything else: the endpoint, the session headers, where the prompt goes in
 * the body, and which field the model picker writes to.
 *
 * The capture contains a live session token or cookie. It is written to
 * `~/.airelay/config.properties`, owner-only, and it expires exactly when the
 * browser session does — at which point `airelay copilot login` re-captures it.
 */
object CopilotSetup {

    /** Run the wizard. [relogin] keeps model settings and refreshes only the session. */
    fun run(relogin: Boolean = false): Boolean {
        val existing = Config.load()
        println()
        if (relogin) {
            println(Ansi.bold("Copilot login") + Ansi.dim(" — refresh the captured browser session"))
        } else {
            println(Ansi.bold("Copilot setup") + Ansi.dim(" — teach AI Relay to reuse your Copilot session"))
        }
        printCaptureInstructions()

        val captured = readCapture() ?: return false
        println()
        println(Ansi.green("✓ Parsed ") + Ansi.dim("${captured.method} ${hostOf(captured.url)}"))
        println(Ansi.dim("  ${captured.headers.size} header(s); session carried by ") +
            (captured.authHeaderName()?.let { Ansi.dim(it) } ?: Ansi.red("nothing — you may not be signed in")))

        val body = captured.body
        if (body.isNullOrBlank()) {
            println(Ansi.red("That request had no body, so it isn't the chat request."))
            println(Ansi.dim("Look for the request that fires when you press Enter — usually the largest POST."))
            return false
        }

        val template = resolveTemplate(body) ?: return false
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
        )

        val capturedModel = template.capturedModel()
        if (template.modelPath == null) {
            println(Ansi.yellow("• No model field found in that request."))
            println(Ansi.dim("  AI Relay will use whatever model the conversation is already set to."))
            println(Ansi.dim("  To enable --model, pick a model in the web picker, send a message, and capture that request."))
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

        val serverHistory = Prompt.confirm(
            "Does that conversation keep its own history (as the website does)?",
            default = true,
        )
        updates["copilot.history"] = if (serverHistory) "server" else "local"

        val file = Config.writeAll(updates)
        println()
        println(Ansi.green("✓ Saved ") + Ansi.dim(file.path) + Ansi.dim(" (owner-only)"))
        println(Ansi.dim("  The capture includes your session token — treat that file as a credential."))

        if (Prompt.confirm("Test the connection now?", default = true)) test()
        return true
    }

    private fun printCaptureInstructions() {
        println(
            """
            ${Ansi.dim("AI Relay talks to Copilot by replaying one request from your own")}
            ${Ansi.dim("signed-in browser session — there is no API key and no separate login.")}

            ${Ansi.bold("In your browser:")}
              1. Open Copilot and sign in as usual (SSO).
              2. Open DevTools ${Ansi.dim("(F12)")} → ${Ansi.bold("Network")}, and clear the list.
              3. Send one short message — remember ${Ansi.bold("exactly")} what you type.
              4. Find the request that carries it ${Ansi.dim("(the big POST that appears on Enter)")}.
              5. Right-click it → ${Ansi.bold("Copy")} → ${Ansi.bold("Copy as cURL")}.
            """.trimIndent(),
        )
        println()
        println(Ansi.yellow("Note: ") + Ansi.dim("this uses an undocumented endpoint and your own session, so it can"))
        println(Ansi.dim("break whenever the site changes, and may not be permitted by your terms of use."))
        println()
    }

    /** Read the pasted cURL, re-prompting once on a parse failure. */
    private fun readCapture(): CurlImport.Captured? {
        repeat(2) { attempt ->
            val pasted = readCurlBlock()
            if (pasted.isBlank()) {
                println(Ansi.dim("Nothing pasted — cancelled."))
                return null
            }
            runCatching { CurlImport.parse(pasted) }
                .onSuccess { return it }
                .onFailure { e ->
                    println(Ansi.red("Could not read that: ${e.message}"))
                    if (attempt == 0) println(Ansi.dim("Try again — paste the whole command, including `curl`."))
                }
        }
        return null
    }

    /**
     * Reads a pasted command. DevTools emits one very long line, so a blank line
     * ends the paste; `END` on its own line is the escape hatch for a command
     * that genuinely contains blank lines.
     */
    private fun readCurlBlock(): String {
        println(Ansi.bold("Paste the cURL command") +
            Ansi.dim("  (then a blank line to finish, or type END)"))
        val sb = StringBuilder()
        while (true) {
            print(Ansi.cyan("• "))
            System.out.flush()
            val line = readlnOrNull() ?: break
            if (line.trim() == "END") break
            if (line.isBlank() && sb.contains("curl") && sb.contains("http")) break
            sb.append(line).append('\n')
        }
        return sb.toString().trim()
    }

    /**
     * Locate the typed prompt inside the captured body. Re-asks on a miss —
     * usually the remembered text and the sent text simply differ — and after a
     * couple of tries falls back to naming the field by hand.
     */
    private fun resolveTemplate(body: String): BodyTemplate? {
        repeat(3) { attempt ->
            val typed = Prompt.required(
                "The message you typed in Copilot",
                hint = "Exactly as sent — this is how setup finds the prompt field in the request.",
            )
            BodyTemplate.from(body, typed)?.let { return it }

            println(Ansi.red("Couldn't find that text in the captured request."))
            if (attempt == 0 && Prompt.confirm("Show the request body so you can check?", default = true)) {
                println(Ansi.dim(body.take(1200) + if (body.length > 1200) "\n… (truncated)" else ""))
            }
        }

        println(Ansi.dim("Falling back to naming the field directly."))
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

    /** Live check: replay the capture with a trivial prompt. */
    fun test() {
        val cfg = CopilotConfig(Config.load())
        cfg.missingCredentials()?.let {
            println(Ansi.red("Not configured: $it"))
            return
        }
        print(Ansi.dim("Testing connection… "))
        System.out.flush()

        val result = runCatching {
            val sb = StringBuilder()
            CopilotClient(cfg).send(
                prompt = "Reply with exactly: OK",
                model = cfg.model.takeIf { it.isNotBlank() },
                conversationId = cfg.conversationId,
            ) { sb.append(it) }
        }
        result.onSuccess { turn ->
            if (turn.text.isBlank()) {
                println(Ansi.yellow("connected, but no text was found in the reply"))
                if (turn.rawSample.isNotBlank()) {
                    println(Ansi.dim("  response started: " + turn.rawSample.take(300).replace("\n", " ⏎ ")))
                    println(Ansi.dim("  set copilot.text.keys to the field holding the text, then retry."))
                }
            } else {
                println(Ansi.green("connected ") +
                    Ansi.dim("(${cfg.hostLabel()} · replied \"${turn.text.trim().take(20)}\")"))
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
            val marker = if (m == cfg.model) Ansi.green("›") else " "
            println("  $marker $m")
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
        println(Ansi.yellow("This clears the captured Copilot request and its session token") +
            Ansi.dim(" (from ${Config.file().path})."))
        if (!Prompt.confirm("Continue?", default = false)) {
            println(Ansi.dim("Cancelled."))
            return
        }
        val removed = Config.clearKeys(CopilotConfig.ALL_KEYS)
        println(Ansi.green("✓ Cleared ${removed.size} setting(s)."))
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
}
