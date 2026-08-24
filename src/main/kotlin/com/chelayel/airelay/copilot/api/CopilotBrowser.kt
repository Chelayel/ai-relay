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
    fun start(status: (String) -> Unit) {
        val port = attachPort ?: Browsers.freePort()
        if (attachPort == null) {
            val exe = Browsers.find() ?: throw BrowserException(
                "No Chrome, Chromium or Edge found. Install one, or set AIRELAY_BROWSER to its path.",
            )
            status("Opening ${java.io.File(exe).name} on $host…")
            // Start on a blank page: the socket must be created *after* we are
            // watching, or CDP reports none of its frames (see below).
            process = Browsers.launch(exe, port, "about:blank")
            if (!DevTools.awaitReady(port, seconds = 30)) {
                throw BrowserException("The browser started but its debugger never came up on port $port.")
            }
        } else if (!DevTools.awaitReady(port, seconds = 5)) {
            throw BrowserException("Nothing is listening on port $attachPort.")
        }

        val page = Browsers.anyPage(port)
            ?: throw BrowserException("The browser opened no debuggable tab.")
        val client = DevTools.connect(page.webSocketDebuggerUrl)
        cdp = client

        client.on("Network.webSocketCreated") { socketsSeen.incrementAndGet() }
        client.on("Network.webSocketFrameSent") { framesSent.incrementAndGet() }
        client.on("Network.webSocketFrameError") { framesSeen.addAndGet(0) }
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

        awaitComposer(status)
    }

    /** Wait for a usable message box, which also means the user has signed in. */
    private fun awaitComposer(status: (String) -> Unit) {
        var told = false
        val deadline = System.currentTimeMillis() + SIGN_IN_SECONDS * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (composerCount() > 0) {
                status("Copilot is ready.")
                return
            }
            if (!told) {
                status("Waiting for Copilot — sign in through the browser window if it asks.")
                told = true
            }
            Thread.sleep(1_000)
        }
        throw BrowserException(
            "No Copilot message box appeared within ${SIGN_IN_SECONDS}s. If you are signed in and it is " +
                "still not found, set copilot.selector.input to the message box's CSS selector.",
        )
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

                client.call("Input.insertText", JsonObject().apply { addProperty("text", prompt) })
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
        if (boxes == null || boxes.isEmpty()) {
            append("  No text box was visible on the page at all. If Copilot is showing one, it may sit\n")
            append("  in a cross-origin frame, which cannot be reached from here.")
        } else {
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
        val added = newVisibleText(textBefore)
        if (added.isNotBlank()) {
            onText(added)
            return added
        }
        return ""
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
        fun cleanPageText(added: String, prompt: String): String {
            // Best case: the echo appears verbatim, so the remainder keeps its
            // newlines — which the tool-fence protocol depends on.
            val marker = prompt.trim().takeLast(48)
            if (marker.isNotEmpty()) {
                val at = added.lastIndexOf(marker)
                if (at >= 0) return dedupeHalves(added.substring(at + marker.length).trim())
            }

            val flat = collapse(added)
            val echo = collapse(prompt)
            if (flat.isEmpty()) return ""
            if (echo.isEmpty()) return dedupeHalves(flat)

            // Match on the head of the echo: the page may have truncated or reflowed
            // it, so the whole thing rarely appears verbatim.
            val head = echo.take(60)
            val start = flat.indexOf(head)
            if (start < 0) return dedupeHalves(flat)

            val tail = echo.takeLast(40)
            val tailAt = if (tail.isEmpty()) -1 else flat.indexOf(tail, start)
            val end = if (tailAt >= 0) tailAt + tail.length else minOf(start + echo.length, flat.length)
            val remainder = (flat.take(start) + " " + flat.drop(end)).trim()
            return dedupeHalves(remainder)
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

        /** How much of the message to verify landed in the box. */
        const val VERIFY_CHARS = 20

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
            function (sel) {
              const out = [];
              for (const doc of ($DOCS)()) {
                let els = [];
                try { els = [...doc.querySelectorAll(sel)]; } catch (e) { continue; }
                for (const el of els) {
                  if (el.disabled || el.readOnly) continue;
                  const r = el.getBoundingClientRect();
                  if (r.width < 120 || r.height < 16) continue;
                  out.push({el: el, r: r});
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
              const found = ($BOXES)(sel);
              const out = {count: found.length, url: location.href, boxes: []};
              for (const f of found) out.boxes.push(($DESCRIBE)(f.el, f.r));
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
              const found = ($BOXES)(sel);
              if (!found.length) return false;
              const el = found[found.length - 1].el;
              el.scrollIntoView({block: 'center'});
              el.focus();
              return true;
            }
        """.trimIndent()

        /** What the composer currently holds, so a silent no-op can be detected. */
        val READ_BACK = """
            function (sel) {
              const found = ($BOXES)(sel);
              if (!found.length) return '';
              const el = found[found.length - 1].el;
              return (el.value !== undefined ? el.value : el.innerText) || '';
            }
        """.trimIndent()

        /** Empty the composer before retrying, so a partial attempt isn't sent. */
        val CLEAR = """
            function (sel) {
              const found = ($BOXES)(sel);
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
