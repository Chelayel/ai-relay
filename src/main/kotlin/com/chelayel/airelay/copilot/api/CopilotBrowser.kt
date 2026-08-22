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
            process = Browsers.launch(exe, port, url)
            if (!DevTools.awaitReady(port, seconds = 30)) {
                throw BrowserException("The browser started but its debugger never came up on port $port.")
            }
        } else if (!DevTools.awaitReady(port, seconds = 5)) {
            throw BrowserException("Nothing is listening on port $attachPort.")
        }

        val page = Browsers.findOrOpenPage(port, url)
            ?: throw BrowserException("Could not open a tab at $url.")
        val client = DevTools.connect(page.webSocketDebuggerUrl)
        cdp = client

        client.on("Network.webSocketFrameReceived") { params ->
            params.getAsJsonObject("response")?.get("payloadData")
                ?.takeIf { it.isJsonPrimitive }?.asString
                ?.let { frames.add(it) }
        }
        client.call("Network.enable")
        client.notify("Runtime.enable")
        client.notify("Page.enable")

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

    /** Read frames until the answer stops growing, then fall back to the DOM. */
    private fun collectAnswer(textBefore: String, onText: (String) -> Unit): String {
        val extractor = TextExtractor(emptyList())
        val assembler = TextAssembler(onText)
        val deadline = System.currentTimeMillis() + turnTimeoutSeconds * 1000L
        var lastGrowth = System.currentTimeMillis()
        var lastLength = 0

        while (System.currentTimeMillis() < deadline && !cancelled) {
            var sawFrame = false
            while (true) {
                val frame = frames.poll() ?: break
                sawFrame = true
                for (part in splitFrames(frame)) {
                    val json = runCatching { JsonParser.parseString(part) }.getOrNull() ?: continue
                    if (!json.isJsonObject && !json.isJsonArray) continue
                    extractor.extract(json)?.let { assembler.offer(it) }
                }
            }
            if (assembler.text().length > lastLength) {
                lastLength = assembler.text().length
                lastGrowth = System.currentTimeMillis()
            } else if (lastLength > 0 && System.currentTimeMillis() - lastGrowth > quietMillis) {
                return assembler.text()          // streamed, then went quiet: done
            } else if (lastLength == 0 && !sawFrame &&
                System.currentTimeMillis() - lastGrowth > quietMillis * 3
            ) {
                break                            // nothing on the socket; try the page
            }
            Thread.sleep(POLL_MILLIS)
        }

        if (assembler.text().isNotEmpty()) return assembler.text()

        // Nothing usable on the socket — read what the page rendered instead.
        val added = newVisibleText(textBefore)
        if (added.isNotBlank()) {
            onText(added)
            return added
        }
        return ""
    }

    private fun visibleText(): String =
        runCatching { evaluate("document.body.innerText").asString }.getOrDefault("")

    /** The text the page gained since [before] — the answer, when frames fail. */
    private fun newVisibleText(before: String): String {
        val now = visibleText()
        if (now.length <= before.length) return ""
        val prefix = before.commonPrefixWith(now)
        return now.substring(prefix.length).trim()
    }

    fun cancel() {
        cancelled = true
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
