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
        runCatching { evaluate("(${findComposer(selector)})().length").asInt }.getOrDefault(0)

    /**
     * Put [prompt] in the composer, send it, and return the answer.
     * [onText] receives the answer as it streams.
     */
    fun ask(prompt: String, onText: (String) -> Unit): String {
        cancelled = false
        val client = cdp ?: throw BrowserException("The browser session is not open.")

        val before = visibleText()
        frames.clear()

        val focused = runCatching { evaluate("(${focusComposer(selector)})()").asBoolean }.getOrDefault(false)
        if (!focused) throw BrowserException("Could not find the Copilot message box to type into.")

        // insertText rather than key events: the prompt is long and contains
        // newlines, and a synthesised Enter mid-text would send it early.
        client.call("Input.insertText", JsonObject().apply { addProperty("text", prompt) })
        Thread.sleep(250)
        pressEnter(client)

        return collectAnswer(before, onText)
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

        /**
         * Every visible box a message could be typed into. A Copilot composer is
         * a textarea or a contenteditable with a textbox role; picking by size
         * and position avoids depending on class names that change weekly.
         */
        fun findComposer(selector: String): String = """
            function () {
              return [...document.querySelectorAll(${quote(selector)})].filter(el => {
                if (el.disabled || el.readOnly) return false;
                const r = el.getBoundingClientRect();
                return r.width > 120 && r.height > 16 && r.bottom > 0;
              });
            }
        """.trimIndent()

        /** Focus the composer — the last one on the page, which is the one at the bottom. */
        fun focusComposer(selector: String): String = """
            function () {
              const els = (${findComposer(selector)})();
              if (!els.length) return false;
              const el = els[els.length - 1];
              el.scrollIntoView({block: 'center'});
              el.focus();
              return document.activeElement === el;
            }
        """.trimIndent()

        /** A JS string literal, so a selector with quotes in it can't break the script. */
        private fun quote(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
