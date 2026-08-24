package com.chelayel.airelay.copilot.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Drives the Copilot web app directly, instead of replaying an HTTP request.
 *
 * M365 Copilot streams its chat over a WebSocket, so there is no request that
 * can be replayed: the reply simply never travels over one. What *does* work is
 * using the page the way a person does — type into the composer, press Enter,
 * and read the answer back.
 *
 * Reading is deliberately not done from the DOM. The answer is taken from the
 * frames the app's own WebSocket receives, observed through the DevTools
 * Protocol and run through the same [TextExtractor] the HTTP path uses. That
 * keeps the fragile part down to one thing — finding the composer — and makes
 * the answer independent of how the page happens to render it. If no text turns
 * up in the frames, it falls back to diffing the page's visible text.
 *
 * The browser stays open for the session and uses the `~/.airelay/browser`
 * profile, so SSO is a once-only step.
 */
internal class CopilotBrowser(
    private val url: String,
    private val attachPort: Int? = null,
    /** CSS for the message box, when the automatic guess picks the wrong one. */
    private val inputSelector: String? = null,
    /** Silence that marks the end of an answer. */
    private val quietMillis: Long = 2_500,
    private val turnTimeoutSeconds: Long = 180,
    /** `auto`, `true` or `false` — see [CopilotConfig.headless]. */
    private val headless: String = "auto",
) : AutoCloseable {

    class BrowserException(message: String) : RuntimeException(message)

    private var process: Process? = null
    private var cdp: DevTools? = null
    private val frames = ConcurrentLinkedQueue<String>()
    private val socketsSeen = java.util.concurrent.atomic.AtomicInteger()
    private val framesSent = java.util.concurrent.atomic.AtomicInteger()
    private val framesSeen = java.util.concurrent.atomic.AtomicInteger()
    private val framesParsed = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var fromFrames = false
    @Volatile private var lastPrompt = ""
    @Volatile private var cancelled = false

    val host: String get() = runCatching { java.net.URI(url).host }.getOrDefault(url).orEmpty()

    /** Launch or attach, open Copilot, and wait until a message box exists. */
    /**
     * Launch or attach, open Copilot, and wait until a message box exists.
     *
     * Headless is the normal way to run this once you are signed in — there is
     * no reason to have a browser window in the way of your terminal. But
     * signing in needs a window, and a session that has quietly expired looks
     * exactly like a page that never finishes loading. So `auto` hides the
     * window when a profile already exists, and if no message box turns up,
     * reopens visibly rather than timing out at you.
     */
    fun start(status: (String) -> Unit) {
        if (attachPort != null) {
            connect(attachPort, status)
            awaitComposer(status, SIGN_IN_SECONDS)
            return
        }

        val hidden = when (headless) {
            "true", "yes" -> true
            "false", "no" -> false
            else -> Browsers.profileExists()
        }

        launchAndConnect(hidden, status)
        val patience = if (hidden) HEADLESS_SECONDS else SIGN_IN_SECONDS
        if (awaitComposer(status, patience)) return

        if (!hidden) {
            throw BrowserException(
                "No Copilot message box appeared within ${patience}s. If you are signed in and it is " +
                    "still not found, set copilot.selector.input to the message box's CSS selector.",
            )
        }

        // Hidden and nothing appeared: almost always a sign-in that has lapsed.
        status("Copilot needs signing in again — opening a window.")
        closeBrowser()
        launchAndConnect(headless = false, status = status)
        if (!awaitComposer(status, SIGN_IN_SECONDS)) {
            throw BrowserException(
                "No Copilot message box appeared. Sign in through the window, or set " +
                    "copilot.selector.input to the message box's CSS selector.",
            )
        }
    }

    private fun launchAndConnect(headless: Boolean, status: (String) -> Unit) {
        val exe = Browsers.find() ?: throw BrowserException(
            "No Chrome, Chromium or Edge found. Install one, or set AIRELAY_BROWSER to its path.",
        )
        val port = Browsers.freePort()
        status(
            (if (headless) "Starting " else "Opening ") + java.io.File(exe).name +
                " on $host" + (if (headless) " (no window)" else "") + "…",
        )
        // Start on a blank page: the socket must be created *after* we are
        // watching, or CDP reports none of its frames (see below).
        process = Browsers.launch(exe, port, "about:blank", headless)
        if (!DevTools.awaitReady(port, seconds = 30)) {
            throw BrowserException("The browser started but its debugger never came up on port $port.")
        }
        connect(port, status)
    }

    private fun connect(port: Int, status: (String) -> Unit) {
        if (!DevTools.awaitReady(port, seconds = 5)) {
            throw BrowserException("Nothing is listening on port $port.")
        }
        val page = Browsers.anyPage(port)
            ?: throw BrowserException("The browser opened no debuggable tab.")
        val client = DevTools.connect(page.webSocketDebuggerUrl)
        cdp = client

        client.on("Network.webSocketCreated") { socketsSeen.incrementAndGet() }
        client.on("Network.webSocketFrameSent") { framesSent.incrementAndGet() }
        client.on("Network.webSocketFrameReceived") { params ->
            val response = params.getAsJsonObject("response") ?: return@on
            val payload = response.get("payloadData")?.takeIf { it.isJsonPrimitive }?.asString ?: return@on
            framesSeen.incrementAndGet()
            // Opcode 2 is a binary frame, which CDP hands over base64-encoded.
            val text = if (response.get("opcode")?.asInt == 2) decodeBase64(payload) else payload
            text?.let { frames.add(it) }
        }
        client.call("Network.enable")
        client.notify("Runtime.enable")
        client.notify("Page.enable")

        // Only now open Copilot. `Network.webSocketFrameReceived` is reported
        // solely for sockets created while the Network domain is enabled, so a
        // page loaded before we attached streams its whole conversation past us
        // unseen — which is what made every answer fall back to scraping the
        // page, and with it the echo of our own prompt.
        client.call("Page.navigate", JsonObject().apply { addProperty("url", url) })
    }

    private fun closeBrowser() {
        runCatching { cdp?.close() }
        cdp = null
        process?.let { p -> runCatching { p.destroy() } }
        process = null
    }

    /**
     * Wait for a usable message box, which also means the user is signed in.
     * Returns false on timeout rather than throwing: whether that is a failure
     * depends on whether a window was showing, which the caller knows.
     */
    private fun awaitComposer(status: (String) -> Unit, seconds: Long): Boolean {
        var told = false
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline && !cancelled) {
            if (composerCount() > 0) {
                status("Copilot is ready.")
                return true
            }
            if (!told) {
                status("Waiting for Copilot — sign in through the browser window if it asks.")
                told = true
            }
            Thread.sleep(1_000)
        }
        return false
    }

    /** The selector to hunt the message box with: configured, or the default guess. */
    private val selector: String = inputSelector?.takeIf { it.isNotBlank() } ?: DEFAULT_SELECTOR

    private fun composerCount(): Int =
        runCatching { locate().get("count")?.asInt }.getOrNull() ?: 0

    /**
     * Where the message box is, plus what else was on the page if it wasn't
     * found. Returned as one object so a failure can say what it actually saw
     * rather than "could not find it".
     */
    private fun locate(): JsonObject =
        runCatching { evaluate("(${LOCATE})(${jsString(selector)})").asJsonObject }
            .getOrElse { JsonObject().apply { addProperty("count", 0) } }

    /**
     * Put [prompt] in the composer, send it, and return the answer.
     * [onText] receives the answer as it streams.
     */
    fun ask(prompt: String, onText: (String) -> Unit): String {
        cancelled = false
        val client = cdp ?: throw BrowserException("The browser session is not open.")

        val before = visibleText()
        frames.clear()
        framesSeen.set(0)
        framesParsed.set(0)
        lastPrompt = prompt

        typeIntoComposer(client, prompt)
        pressEnter(client)

        return collectAnswer(before, onText)
    }

    /**
     * Get [prompt] into the message box, and prove it landed.
     *
     * The composer is re-rendered while a turn is in flight, so the one found at
     * the start of a session is gone by the second turn — hence the retry rather
     * than a single look. Focus goes through a real click as well as `focus()`,
     * because a rich composer usually installs click handlers and ignores a bare
     * focus. Finally the text is read back: silently typing into nothing is the
     * failure that is hardest to diagnose from the outside.
     */
    private fun typeIntoComposer(client: DevTools, prompt: String) {
        val deadline = System.currentTimeMillis() + COMPOSER_WAIT_SECONDS * 1000L
        var last: JsonObject = JsonObject()
        var lastTyped = ""

        while (System.currentTimeMillis() < deadline && !cancelled) {
            val found = locate().also { last = it }
            if (found.get("count")?.asInt ?: 0 > 0) {
                clickAt(client, found)
                runCatching { evaluate("(${FOCUS})(${jsString(selector)})") }

                typeMessage(client, prompt)
                Thread.sleep(200)

                lastTyped = runCatching { evaluate("(${READ_BACK})(${jsString(selector)})").asString }
                    .getOrDefault("")
                // A contenteditable reflows newlines into its own markup, so the
                // text read back is never character-identical to what was sent.
                // Compare with whitespace collapsed, on a prefix.
                if (landed(prompt, lastTyped)) return
                runCatching { evaluate("(${CLEAR})(${jsString(selector)})") }
            }
            Thread.sleep(500)
        }
        throw BrowserException(describeFailure(last, lastTyped))
    }

    /**
     * Type [text] into the focused composer, a line at a time.
     *
     * `Input.insertText` with the whole message looks right and is not: a chat
     * composer binds Enter to send, and every newline in the inserted text acts
     * as one. The message goes out in fragments and the box is left holding
     * whatever followed the last newline — which is exactly what happened, and
     * read as "the box was typed into but held the tail of the reminder".
     *
     * So each line is inserted on its own, with shift+Enter between them: the
     * key combination a person uses to add a line without sending.
     */
    private fun typeMessage(client: DevTools, text: String) {
        val lines = text.split("\n")
        lines.forEachIndexed { index, line ->
            if (cancelled) return
            if (index > 0) newlineKey(client)
            if (line.isNotEmpty()) {
                client.call("Input.insertText", JsonObject().apply { addProperty("text", line) })
            }
        }
    }

    /** Shift+Enter: a newline in the message rather than a send. */
    private fun newlineKey(client: DevTools) {
        for (type in listOf("keyDown", "keyUp")) {
            client.notify("Input.dispatchKeyEvent", JsonObject().apply {
                addProperty("type", type)
                addProperty("key", "Enter")
                addProperty("code", "Enter")
                addProperty("windowsVirtualKeyCode", 13)
                addProperty("nativeVirtualKeyCode", 13)
                addProperty("modifiers", SHIFT)
            })
        }
    }

    /** Click the middle of the composer, so click-driven editors take focus. */
    private fun clickAt(client: DevTools, found: JsonObject) {
        val x = found.get("x")?.asDouble ?: return
        val y = found.get("y")?.asDouble ?: return
        for (type in listOf("mousePressed", "mouseReleased")) {
            runCatching {
                client.call("Input.dispatchMouseEvent", JsonObject().apply {
                    addProperty("type", type)
                    addProperty("x", x)
                    addProperty("y", y)
                    addProperty("button", "left")
                    addProperty("clickCount", 1)
                })
            }
        }
    }

    /** Say what was on the page, so the user can name a selector that works. */
    private fun describeFailure(last: JsonObject, typed: String): String = buildString {
        append("Could not type into the Copilot message box")
        last.get("url")?.takeIf { it.isJsonPrimitive }?.let { append(" on ").append(it.asString) }
        append(".\n")

        val boxes = last.getAsJsonArray("boxes")
        val disabled = last.get("disabled")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val usable = last.get("count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        when {
            boxes == null || boxes.isEmpty() -> {
                append("  No text box was visible on the page at all. If Copilot is showing one, it may sit\n")
                append("  in a cross-origin frame, which cannot be reached from here.")
            }
            // The distinction that matters: a box that is there but switched off
            // is Copilot still working, not a selector that needs fixing.
            usable == 0 && disabled > 0 -> {
                append("  The message box is there but disabled — Copilot is probably still replying,\n")
                append("  or the last turn was interrupted while it was. Give it a moment and try again.\n")
                boxes.take(4).forEach { append("    ").append(it.asString).append("\n") }
            }
            else -> {
                append("  Text boxes on the page were:\n")
                boxes.take(6).forEach { append("    ").append(it.asString).append("\n") }
                if (typed.isNotBlank()) {
                    append("  The box was found and typed into, but held: \"")
                    append(collapse(typed).take(60))
                    append("\"\n")
                }
                append("  Set copilot.selector.input to a CSS selector for the right one.")
            }
        }
    }

    private fun pressEnter(client: DevTools) {
        for (type in listOf("keyDown", "char", "keyUp")) {
            client.notify("Input.dispatchKeyEvent", JsonObject().apply {
                addProperty("type", type)
                addProperty("key", "Enter")
                addProperty("code", "Enter")
                addProperty("windowsVirtualKeyCode", 13)
                addProperty("nativeVirtualKeyCode", 13)
                if (type == "char") addProperty("text", "\r")
            })
        }
    }

    /**
     * Wait for the answer, watching the socket and the page at the same time.
     *
     * Either can be the one that shows progress: the frames carry the text on a
     * socket we can read, and the rendered page grows even when we can't. So the
     * turn is finished when *whichever* is moving has stopped moving, and it is
     * only given up on after a real wait — a long prompt takes Copilot a while
     * to even begin answering, and treating a few quiet seconds as failure was
     * ending turns before they started.
     */
    private fun collectAnswer(textBefore: String, onText: (String) -> Unit): String {
        val extractor = TextExtractor(emptyList())
        val assembler = TextAssembler(onText)
        val deadline = System.currentTimeMillis() + turnTimeoutSeconds * 1000L
        val started = System.currentTimeMillis()
        var lastChange = System.currentTimeMillis()
        var frameChars = 0
        var pageChars = 0
        val hasSocket = socketsSeen.get() > 0

        while (System.currentTimeMillis() < deadline && !cancelled) {
            while (true) {
                val frame = frames.poll() ?: break
                for (part in splitFrames(frame)) {
                    val json = runCatching { JsonParser.parseString(part) }.getOrNull() ?: continue
                    if (!json.isJsonObject && !json.isJsonArray) continue
                    framesParsed.incrementAndGet()
                    extractor.extract(json)?.let { assembler.offer(it) }
                }
            }

            val nowFrames = assembler.text().length
            // When the app has a socket, the answer comes over it, so only the
            // socket votes on whether the turn is progressing. The page is a poor
            // judge: it prints the message it was just given, and counting that
            // echo as the reply arriving ended turns about two seconds after
            // Enter — long before Copilot had said anything — and handed back our
            // own prompt as the answer.
            val nowPage = if (hasSocket) 0 else newVisibleText(textBefore).length
            if (nowFrames > frameChars || nowPage > pageChars) {
                frameChars = nowFrames
                pageChars = nowPage
                lastChange = System.currentTimeMillis()
            }

            val answering = frameChars > 0 || pageChars > 0
            val quietFor = System.currentTimeMillis() - lastChange
            if (answering && quietFor > quietMillis) break
            if (!answering && System.currentTimeMillis() - started > START_PATIENCE_MILLIS) break

            Thread.sleep(POLL_MILLIS)
        }

        if (assembler.text().isNotBlank()) {
            fromFrames = true
            return assembler.text()
        }

        // Nothing readable on the socket — read what the page rendered instead.
        fromFrames = false
        val answer = lastMessageText().ifBlank { newVisibleText(textBefore) }
        if (answer.isNotBlank()) {
            onText(answer)
            return answer
        }
        return ""
    }

    /** The newest message block on the page, with any echo of ours removed. */
    private fun lastMessageText(): String {
        val raw = runCatching { evaluate("(${lastMessage(selector)})()").asString }.getOrDefault("")
        return cleanPageText(raw, lastPrompt)
    }

    private fun decodeBase64(s: String): String? = runCatching {
        String(java.util.Base64.getDecoder().decode(s), Charsets.UTF_8)
    }.getOrNull()?.takeIf { text -> text.none { it.code in 1..8 } }

    private fun visibleText(): String =
        runCatching { evaluate("document.body.innerText").asString }.getOrDefault("")

    /** The text the page gained since [before] — the answer, when frames fail. */
    private fun newVisibleText(before: String): String {
        val now = visibleText()
        if (now.length <= before.length) return ""
        val prefix = before.commonPrefixWith(now)
        return cleanPageText(now.substring(prefix.length), lastPrompt)
    }

    fun cancel() {
        cancelled = true
    }

    /** What the last turn did, for the diagnostic shown when it produced nothing. */
    fun diagnostics(): String = buildString {
        append("browser: ").append(if (fromFrames) "answer read from the socket" else "answer read from the page")
        append("; sockets ").append(socketsSeen.get())
        append(", frames out ").append(framesSent.get())
        append(", frames in ").append(framesSeen.get())
        append(", parsed ").append(framesParsed.get())
        runCatching { evaluate("location.href").asString }.getOrNull()?.let { append("; at ").append(it) }
    }

    override fun close() {
        cancelled = true
        runCatching { cdp?.close() }
        // Leave a browser the user started; only close one we opened.
        if (attachPort == null) process?.let { p -> runCatching { p.destroy() } }
    }

    /** Evaluate JS in the page and return the value. */
    private fun evaluate(expression: String): com.google.gson.JsonElement {
        val client = cdp ?: throw BrowserException("The browser session is not open.")
        val result = client.call("Runtime.evaluate", JsonObject().apply {
            addProperty("expression", expression)
            addProperty("returnByValue", true)
            addProperty("awaitPromise", true)
        })
        result.getAsJsonObject("exceptionDetails")?.let {
            throw BrowserException("The page rejected a script: ${it.get("text")?.asString}")
        }
        return result.getAsJsonObject("result")?.get("value")
            ?: throw BrowserException("The page returned nothing.")
    }

    internal companion object {
        const val POLL_MILLIS = 200L
        const val SIGN_IN_SECONDS = 300L

        /**
         * How long a hidden browser gets before we assume the sign-in has
         * lapsed and show a window. Short, because nobody is watching it: the
         * only thing that can happen without a window is loading.
         */
        const val HEADLESS_SECONDS = 45L

        /**
         * How long to wait for a turn to show any sign of life before giving up.
         * Copilot takes a while to begin answering a long prompt, and the system
         * preamble is the longest message of a session — treating a few quiet
         * seconds as failure ended the first turn before it had started.
         */
        const val START_PATIENCE_MILLIS = 45_000L

        /** SignalR packs several messages per frame, separated by this. */
        const val RECORD_SEPARATOR = '\u001E'

        /**
         * SignalR and friends pack several messages into one frame, separated by
         * a record separator; splitting on it keeps each one parseable. A frame
         * that uses no separator comes back as itself.
         */
        fun splitFrames(payload: String): List<String> =
            payload.split(RECORD_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

        const val DEFAULT_SELECTOR = "textarea, [contenteditable=\"true\"], [role=\"textbox\"]"

        /** How long a re-rendering page gets to show its composer again. */
        const val COMPOSER_WAIT_SECONDS = 20L

        /**
         * Tidy a page-text diff into something that reads as the answer.
         *
         * A chat page echoes the message that was just sent, and often renders the
         * reply twice — once in the thread and once in a live region for screen
         * readers — so the raw diff arrives as prompt-plus-answer-plus-answer.
         */
        fun cleanPageText(rawAdded: String, prompt: String): String {
            // A page that renders a multi-line message puts <br> between the
            // lines, and reading it back gives those tags as literal text. Every
            // line-wise comparison below then misses, and the whole echoed
            // prompt sails through as if it were the answer. Turn them into the
            // newlines they stand for first.
            val added = unwrapMarkup(rawAdded)
            // Line-wise, not substring-wise. The page reflows what it echoes, so
            // the prompt never reappears verbatim: matching on a head or a tail
            // half-hits and leaves shards of our own instructions in the answer.
            //
            // Two rules, because short lines and long ones need opposite
            // treatment. A line that *is* one of ours is dropped whole — that
            // covers `--- Task ---` and a one-word request. Longer spans of ours
            // are subtracted from inside a line, which is what rescues the answer
            // when the page runs our message and the reply together. Short lines
            // are never subtracted from inside: the request "test" appears inside
            // the word "tests" in the reply, and cutting it would corrupt the
            // answer to fix the echo.
            //
            // Subtracting our substantial lines also takes back the example tool
            // call we sent, which would otherwise be parsed as a call from
            // Copilot. Fence markers are too short to qualify, so a real call
            // still reads as one.
            val lines = prompt.lines().map { collapse(it) }.filter { it.isNotEmpty() }
            val exact = lines.toSet()

            // Runs of consecutive lines, longest first: the page usually flattens
            // a stretch of the prompt rather than one line of it.
            val runs = lines.indices
                .drop((lines.size - RUN_LOOKBACK).coerceAtLeast(0))
                .map { i -> lines.subList(i, lines.size).joinToString(" ") }
            val singles = lines.filter { it.any(Char::isLetterOrDigit) }
            val subtract = (runs + singles)
                .filter { it.length > ECHO_MIN_CHARS }
                .distinct()
                .sortedByDescending { it.length }

            // Longest first, so the most specific echo is peeled off.
            val prefixes = lines.filter { it.length >= 2 }.sortedByDescending { it.length }

            val kept = added.lines().mapNotNull { line ->
                val flat = collapse(line)
                if (flat.isEmpty() || flat in exact) return@mapNotNull null
                var rest = flat
                for (echo in subtract) if (rest.contains(echo)) rest = rest.replace(echo, " ")
                rest = collapse(rest)
                // Last, not first: subtracting whole spans needs the line intact
                // to match against. Only what survives that can still be carrying
                // a short echo fused to its front.
                rest = stripEchoPrefix(rest, prefixes)
                when {
                    rest.isEmpty() -> null       // the line was ours entirely
                    rest == flat -> line         // nothing of ours in it: keep it verbatim
                    else -> rest                 // ours and theirs ran together: keep theirs
                }
            }.joinToString("\n")

            return dedupeHalves(kept.trim())
        }

        /** A block rendered twice back to back becomes one copy of it. */
        fun dedupeHalves(text: String): String {
            val t = text.trim()
            if (t.length < 40) return t
            val half = t.length / 2
            // Allow the two copies to differ by a separator or two in the middle.
            for (split in half - 4..half + 4) {
                if (split <= 0 || split >= t.length) continue
                val a = collapse(t.substring(0, split))
                val b = collapse(t.substring(split))
                if (a.isNotEmpty() && a == b) return t.substring(0, split).trim()
            }
            return t
        }

        /**
         * How long a page block must be to read as an answer rather than a
         * control label. A real reply is a sentence; "Choose model" is not.
         */
        const val MIN_ANSWER_CHARS = 25

        /** CDP's modifier bitmask for Shift. */
        const val SHIFT = 8

        /** How much of the message to verify landed in the box. */
        const val VERIFY_CHARS = 20

        /**
         * Peel our own message off the front of a line.
         *
         * The page renders the message it was just given immediately before the
         * reply, and often with nothing between them — "hello" and "Hello! How
         * can I help?" arrive as `helloHello! How can I help?`. Subtracting a
         * line that short from anywhere would be reckless, but stripping it from
         * the *front* is safe when what follows starts a new word: "test" comes
         * off `testHello` and stays on in `testing`.
         */
        fun stripEchoPrefix(line: String, prefixes: List<String>): String {
            for (echo in prefixes) {
                if (line.length <= echo.length || !line.startsWith(echo)) continue
                val next = line[echo.length]
                if (next.isUpperCase() || next.isWhitespace() || !next.isLetterOrDigit()) {
                    return line.substring(echo.length).trim()
                }
            }
            return line
        }

        /** Markup that leaked into text, as the tags a page uses for line breaks. */
        fun unwrapMarkup(text: String): String = text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|li)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>\n]{1,40}>"), " ")

        /**
         * The shortest span worth subtracting from inside a line of page text.
         * Long enough to spare fence markers like ```tool, which a genuine tool
         * call needs, and short words that also occur in ordinary prose.
         */
        const val ECHO_MIN_CHARS = 20

        /** How far back to build flattened runs of the prompt from. */
        const val RUN_LOOKBACK = 40

        /**
         * True when [typed] is the message we meant to send.
         *
         * A contenteditable reflows what it is given into its own markup — a
         * blank line becomes a div, newlines come back doubled or collapsed — so
         * the text read back is never character-identical to what was sent.
         * Comparing with whitespace collapsed is what makes a multi-line message
         * (every tool-result message is one) verifiable at all.
         */
        fun landed(prompt: String, typed: String): Boolean {
            if (typed.isBlank()) return false
            val want = collapse(prompt).take(VERIFY_CHARS)
            return want.isNotEmpty() && collapse(typed).contains(want)
        }

        fun collapse(s: String): String = s.replace(Regex("\\s+"), " ").trim()

        /**
         * The documents to search: the page, plus any same-origin frame. Copilot
         * renders its composer in a frame on some surfaces, and a frame we may
         * not touch throws, so each is tried and skipped on failure.
         */
        private val DOCS = """
            function () {
              const out = [document];
              for (const f of document.querySelectorAll('iframe, frame')) {
                try { if (f.contentDocument) out.push(f.contentDocument); } catch (e) {}
              }
              return out;
            }
        """.trimIndent()

        /** Visible, editable boxes matching the selector, across those documents. */
        private val BOXES = """
            function (sel, includeDisabled) {
              const out = [];
              for (const doc of ($DOCS)()) {
                let els = [];
                try { els = [...doc.querySelectorAll(sel)]; } catch (e) { continue; }
                for (const el of els) {
                  const off = !!(el.disabled || el.readOnly ||
                                 el.getAttribute('aria-disabled') === 'true');
                  if (off && !includeDisabled) continue;
                  const r = el.getBoundingClientRect();
                  if (r.width < 120 || r.height < 16) continue;
                  out.push({el: el, r: r, off: off});
                }
              }
              return out;
            }
        """.trimIndent()

        /** A short description of a box, for a failure message. */
        private val DESCRIBE = """
            function (el, r) {
              const id = el.id ? '#' + el.id : '';
              const cls = (typeof el.className === 'string' && el.className)
                ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
              const role = el.getAttribute('role') ? '[role=' + el.getAttribute('role') + ']' : '';
              const label = el.getAttribute('aria-label');
              return el.tagName.toLowerCase() + id + cls + role +
                (label ? ' aria-label="' + label.slice(0, 40) + '"' : '') +
                '  ' + Math.round(r.width) + 'x' + Math.round(r.height);
            }
        """.trimIndent()

        /**
         * Where the composer is — the last matching box, which is the one at the
         * bottom of a chat — plus a description of every candidate for when that
         * guess is wrong.
         */
        val LOCATE = """
            function (sel) {
              const found = ($BOXES)(sel, false);
              const all = ($BOXES)(sel, true);
              const out = {
                count: found.length,
                disabled: all.length - found.length,
                url: location.href,
                boxes: [],
              };
              for (const f of all) {
                out.boxes.push(($DESCRIBE)(f.el, f.r) + (f.off ? '  [disabled]' : ''));
              }
              if (found.length) {
                const r = found[found.length - 1].r;
                out.x = r.left + r.width / 2;
                out.y = r.top + r.height / 2;
              }
              return out;
            }
        """.trimIndent()

        /** Focus the composer, scrolling it into view first. */
        val FOCUS = """
            function (sel) {
              const found = ($BOXES)(sel, false);
              if (!found.length) return false;
              const el = found[found.length - 1].el;
              el.scrollIntoView({block: 'center'});
              el.focus();
              return true;
            }
        """.trimIndent()

        /**
         * The text of the newest message block on the page.
         *
         * Diffing the whole body was the wrong instrument: a chat page echoes the
         * message it was just given and often renders the reply twice, so the
         * diff came back as our own prompt plus the answer twice. Reading the
         * last block instead sidesteps all of that. "Block" means the innermost
         * element holding the text — anything whose children don't already carry
         * it — and the composer's own subtree is excluded so a half-typed message
         * can never be read back as an answer.
         */
        fun lastMessage(selector: String): String = """
            function () {
              const skip = new Set();
              for (const f of ($BOXES)(${jsString(selector)}, false)) {
                let e = f.el;
                while (e) { skip.add(e); e = e.parentElement; }
              }
              const chrome = new Set([
                'button', 'menuitem', 'menuitemradio', 'menuitemcheckbox', 'tab', 'link',
                'option', 'listbox', 'combobox', 'toolbar', 'navigation', 'banner',
                'contentinfo', 'search', 'switch', 'slider', 'progressbar', 'status',
              ]);
              const blocks = [...document.querySelectorAll('div, article, section, li, p, span')]
                .filter(e => {
                  if (skip.has(e)) return false;
                  const role = e.getAttribute('role');
                  if (role && chrome.has(role)) return false;
                  // A control's label is not a message, however it is marked up.
                  if (e.closest('button, [role="button"], [role="menu"], [role="menubar"], nav, header')) {
                    return false;
                  }
                  const t = (e.innerText || '').trim();
                  if (t.length < 2) return false;
                  return ![...e.children].some(c => (c.innerText || '').trim().length >= t.length * 0.9);
                });
              if (!blocks.length) return '';
              // Prefer the last block with something to say. A chat page is full
              // of short labels — "Choose model", "Copy", "Stop" — and the last
              // element on the page is usually one of them rather than the answer.
              const wordy = blocks.filter(e => (e.innerText || '').trim().length >= $MIN_ANSWER_CHARS);
              const pick = wordy.length ? wordy[wordy.length - 1] : blocks[blocks.length - 1];
              return pick.innerText || '';
            }
        """.trimIndent()

        /** What the composer currently holds, so a silent no-op can be detected. */
        val READ_BACK = """
            function (sel) {
              const found = ($BOXES)(sel, false);
              if (!found.length) return '';
              const el = found[found.length - 1].el;
              return (el.value !== undefined ? el.value : el.innerText) || '';
            }
        """.trimIndent()

        /** Empty the composer before retrying, so a partial attempt isn't sent. */
        val CLEAR = """
            function (sel) {
              const found = ($BOXES)(sel, false);
              if (!found.length) return false;
              const el = found[found.length - 1].el;
              if (el.value !== undefined) el.value = ''; else el.innerText = '';
              return true;
            }
        """.trimIndent()

        /** A JS string literal, so a selector with quotes in it can't break the script. */
        fun jsString(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
