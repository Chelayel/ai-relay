package com.chelayel.airelay.copilot.api

import com.google.gson.JsonObject
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Gets the Copilot session automatically, by watching a real browser.
 *
 * The manual route — DevTools → Copy as cURL → paste — fails on M365 Copilot for
 * a mundane reason: the command is tens of kilobytes on a single line, and a
 * terminal in canonical mode truncates any one line at about 4 KB. So the paste
 * arrives cut in half, or silently short.
 *
 * This avoids the terminal entirely. It drives a Chrome/Edge instance over the
 * DevTools Protocol, waits for the user to sign in through their normal SSO and
 * send one message, and reads the resulting request straight off the wire —
 * URL, headers (cookies included) and body, at any size.
 *
 * Crucially it does *not* stop at the first request carrying the message. A
 * Copilot page sends the same text to several endpoints — one creates or names
 * the conversation, one records history, one actually asks the model — and they
 * look alike from the request side. Taking the first match captured a page-state
 * endpoint whose reply is the app's Redux store, not an answer. So this collects
 * every match, looks at what each one *replied*, and prefers the one that
 * streams prose back.
 *
 * The browser runs against a profile under `~/.airelay/browser`, so the SSO
 * login persists and re-capturing later usually needs no sign-in at all.
 */
internal object BrowserCapture {

    /** What the caller needs to tell the user while we wait. */
    interface Progress {
        fun status(message: String)
        fun hint(message: String)
    }

    class CaptureException(message: String) : RuntimeException(message)

    /** One request that carried the user's message, plus how the server answered. */
    class Observed(
        val captured: CurlImport.Captured,
        val responseMime: String,
        val responseSample: String,
    ) {
        /** Higher means more likely to be the endpoint that answers, not bookkeeping. */
        val score: Int
            get() {
                var s = 0
                val path = runCatching { java.net.URI(captured.url).path.lowercase() }.getOrDefault("")

                // A streamed reply is the strongest signal there is.
                if (responseMime.contains("event-stream")) s += 1_000
                if (JSON_STREAM_HINTS.any { responseMime.contains(it) }) s += 600

                if (CHAT_PATH_HINTS.any { path.contains(it) }) s += 250
                // Deliberately larger than any positive a non-streaming endpoint
                // can earn: an autocomplete reply must never outrank a chat one.
                if (BOOKKEEPING_PATH_HINTS.any { path.contains(it) }) s -= 2_000

                // A page-state document is the thing we must not pick: it echoes
                // the message back as a conversation title and answers nothing.
                if (STORE_MARKERS.any { responseSample.contains(it) }) s -= 800
                if (responseMime.contains("text/html")) s -= 400

                s += minOf(responseSample.length, 2_000) / 10
                return s
            }

        val host: String get() = runCatching { java.net.URI(captured.url).host }.getOrDefault(captured.url).orEmpty()
        val path: String get() = runCatching { java.net.URI(captured.url).path }.getOrDefault("").orEmpty()

        /** True when nothing about this reply suggests it answers anything. */
        val looksInert: Boolean
            get() = !responseMime.contains("event-stream") &&
                JSON_STREAM_HINTS.none { responseMime.contains(it) } &&
                (BOOKKEEPING_PATH_HINTS.any { path.lowercase().contains(it) } ||
                    STORE_MARKERS.any { responseSample.contains(it) })
    }

    /** Everything one capture run saw. */
    class Result(
        /** Requests carrying the message, best candidate first. */
        val observed: List<Observed>,
        /** WebSocket URLs that carried the message — chat this backend can't replay. */
        val webSockets: List<String>,
    )

    private val CHAT_PATH_HINTS = listOf("chat", "completion", "message", "send", "turn", "ask", "invoke", "stream")

    /**
     * Endpoints that see the message but never answer it. `suggestions` matters
     * most: a Copilot composer sends every keystroke to search autocomplete, so
     * it carries the message and replies promptly with JSON — and would happily
     * be mistaken for the chat endpoint.
     */
    private val BOOKKEEPING_PATH_HINTS = listOf(
        "history", "pagestate", "page-state", "telemetry", "log", "beacon", "analytics",
        "presence", "sync", "suggest", "autocomplete", "typeahead", "spell", "instrument",
        "diagnostic", "heartbeat",
    )
    private val JSON_STREAM_HINTS = listOf("ndjson", "json-seq", "jsonl")
    private val STORE_MARKERS =
        listOf("\"store\"", "conversationPageHistoryList", "\"chats\":[", "__INITIAL_STATE__")

    /**
     * Open [url], wait for requests whose body contains [nonce], and report them.
     *
     * [attachPort] attaches to a browser the user already started with
     * `--remote-debugging-port=PORT` instead of launching one — the way to reuse
     * an existing signed-in window.
     */
    fun capture(
        url: String,
        nonce: String,
        timeoutSeconds: Long,
        attachPort: Int? = null,
        progress: Progress,
    ): Result {
        val port = attachPort ?: freePort()
        var browser: Process? = null

        try {
            if (attachPort == null) {
                val exe = findBrowser() ?: throw CaptureException(
                    "No Chrome, Chromium or Edge found. Install one, set AIRELAY_BROWSER to its path, " +
                        "or start your own with --remote-debugging-port=9222 and pass --attach 9222.",
                )
                progress.status("Launching ${File(exe).name}…")
                browser = launch(exe, port, url)
                if (!DevTools.awaitReady(port, seconds = 30)) {
                    throw CaptureException("The browser started but its debugger never came up on port $port.")
                }
            } else {
                if (!DevTools.awaitReady(port, seconds = 5)) {
                    throw CaptureException(
                        "Nothing is listening on port $port. Start your browser with " +
                            "--remote-debugging-port=$port (and a non-default --user-data-dir).",
                    )
                }
                progress.status("Attached to the browser on port $port.")
            }

            val page = findOrOpenPage(port, url)
                ?: throw CaptureException("Could not open a debuggable tab at $url.")

            DevTools.connect(page.webSocketDebuggerUrl).use { cdp ->
                return watch(cdp, nonce, timeoutSeconds, progress)
            }
        } finally {
            // Leave a browser the user started; only clean up one we launched.
            browser?.let { p -> runCatching { p.destroy() } }
        }
    }

    // ---- watching the network ------------------------------------------------

    /** Everything we learn about one in-flight request, across several CDP events. */
    private class Partial {
        @Volatile var url: String? = null
        @Volatile var method: String = "POST"
        @Volatile var body: String? = null
        @Volatile var mime: String = ""
        @Volatile var responseSample: String = ""
        val headers = ConcurrentHashMap<String, String>()
    }

    private fun watch(
        cdp: DevTools,
        nonce: String,
        timeoutSeconds: Long,
        progress: Progress,
    ): Result {
        val inFlight = ConcurrentHashMap<String, Partial>()
        val matched = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val socketUrls = ConcurrentHashMap<String, String>()
        val socketsCarryingNonce = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val first = CountDownLatch(1)

        fun partial(id: String) = inFlight.computeIfAbsent(id) { Partial() }

        cdp.on("Network.requestWillBeSent") { params ->
            val id = params.get("requestId")?.asString ?: return@on
            val request = params.getAsJsonObject("request") ?: return@on
            val p = partial(id)
            p.url = request.get("url")?.asString
            p.method = request.get("method")?.asString ?: "POST"
            mergeHeaders(p, request.getAsJsonObject("headers"))

            val inline = request.get("postData")?.takeIf { it.isJsonPrimitive }?.asString
            if (inline != null) {
                p.body = inline
            } else if (request.get("hasPostData")?.asBoolean == true) {
                // Chrome omits large bodies from the event; ask for it explicitly.
                runCatching {
                    val result = cdp.call(
                        "Network.getRequestPostData",
                        JsonObject().apply { addProperty("requestId", id) },
                        timeoutSeconds = 10,
                    )
                    p.body = result.get("postData")?.asString
                }
            }
            if (p.body?.contains(nonce) == true && !matched.contains(id)) {
                matched.add(id)
                progress.status("Saw ${shortPath(p.url)} carry the message.")
                first.countDown()
            }
        }

        // The plain event hides cookies; this one carries the real header set.
        cdp.on("Network.requestWillBeSentExtraInfo") { params ->
            val id = params.get("requestId")?.asString ?: return@on
            mergeHeaders(partial(id), params.getAsJsonObject("headers"))
        }

        cdp.on("Network.responseReceived") { params ->
            val id = params.get("requestId")?.asString ?: return@on
            if (!matched.contains(id)) return@on
            partial(id).mime = params.getAsJsonObject("response")
                ?.get("mimeType")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().lowercase()
        }

        cdp.on("Network.loadingFinished") { params ->
            val id = params.get("requestId")?.asString ?: return@on
            if (!matched.contains(id)) return@on
            runCatching {
                val result = cdp.call(
                    "Network.getResponseBody",
                    JsonObject().apply { addProperty("requestId", id) },
                    timeoutSeconds = 10,
                )
                partial(id).responseSample = result.get("body")?.asString.orEmpty().take(RESPONSE_SAMPLE)
            }
        }

        cdp.on("Network.webSocketCreated") { params ->
            val id = params.get("requestId")?.asString ?: return@on
            socketUrls[id] = params.get("url")?.asString.orEmpty()
        }
        cdp.on("Network.webSocketFrameSent") { params ->
            val id = params.get("requestId")?.asString ?: return@on
            val payload = params.getAsJsonObject("response")?.get("payloadData")?.asString ?: return@on
            if (payload.contains(nonce)) {
                socketsCarryingNonce.add(socketUrls[id] ?: "(unknown socket)")
                first.countDown()
            }
        }

        cdp.call("Network.enable", JsonObject().apply {
            // Big enough to hold a chat request body without Chrome dropping it.
            addProperty("maxPostDataSize", 10 * 1024 * 1024)
        })
        cdp.notify("Page.enable")

        progress.status("Watching the browser. Waiting for your message…")
        val sawSomething = first.await(timeoutSeconds, TimeUnit.SECONDS)

        if (!sawSomething) {
            throw CaptureException(
                "Timed out waiting for a request containing \"$nonce\". Make sure you sent exactly that " +
                    "text as a Copilot message, and try --timeout for longer.",
            )
        }

        // A Copilot page fans the message out to several endpoints and their
        // replies land at different times. Keep listening so the choice is made
        // across all of them rather than whichever fired first.
        progress.status("Collecting the rest of the exchange…")
        Thread.sleep(SETTLE_MILLIS)
        // Response bodies are fetched from the event thread; let it catch up.
        cdp.drainEvents(15)

        val observed = matched.mapNotNull { id ->
            val p = inFlight[id] ?: return@mapNotNull null
            val requestUrl = p.url ?: return@mapNotNull null
            val headers = LinkedHashMap<String, String>()
            p.headers.entries
                .sortedBy { it.key.lowercase() }
                .forEach { (k, v) -> if (CurlImport.isReplayable(k)) headers[k] = v }
            Observed(
                captured = CurlImport.Captured(requestUrl, p.method, headers, p.body),
                responseMime = p.mime,
                responseSample = p.responseSample,
            )
        }.sortedByDescending { it.score }

        return Result(observed, socketsCarryingNonce.toList())
    }

    private fun shortPath(url: String?): String =
        runCatching { java.net.URI(url!!).path.takeLast(48) }.getOrDefault(url ?: "a request").orEmpty()

    private fun mergeHeaders(p: Partial, headers: JsonObject?) {
        headers ?: return
        for ((name, value) in headers.entrySet()) {
            if (value.isJsonPrimitive) p.headers[name] = value.asString
        }
    }

    // ---- browser discovery & launch ------------------------------------------

    private fun findOrOpenPage(port: Int, url: String): DevTools.Companion.Page? {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        repeat(20) {
            val pages = DevTools.listPages(port).orEmpty()
            pages.firstOrNull { page -> host != null && runCatching { java.net.URI(page.url).host }.getOrNull() == host }
                ?.let { return it }
            Thread.sleep(500)
        }
        return DevTools.newPage(port, url)
    }

    private fun launch(exe: String, port: Int, url: String): Process {
        val profile = File(System.getProperty("user.home") ?: ".", ".airelay/browser")
        profile.mkdirs()

        val args = mutableListOf(
            exe,
            "--remote-debugging-port=$port",
            "--user-data-dir=${profile.absolutePath}",
            "--no-first-run",
            "--no-default-browser-check",
        )
        // Chrome refuses to run as root with its sandbox on, which is the case
        // in containers; on a normal desktop account the sandbox stays enabled.
        if (isRoot()) args.add("--no-sandbox")
        System.getenv("AIRELAY_BROWSER_ARGS")?.takeIf { it.isNotBlank() }
            ?.split(" ")?.filter { it.isNotBlank() }?.let { args.addAll(it) }
        args.add(url)

        return ProcessBuilder(args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private fun isRoot(): Boolean =
        runCatching { System.getProperty("user.name") == "root" }.getOrDefault(false)

    /** The first Chrome/Chromium/Edge we can find, or null. */
    fun findBrowser(): String? {
        System.getenv("AIRELAY_BROWSER")?.takeIf { File(it).canExecute() }?.let { return it }

        val os = System.getProperty("os.name").lowercase()
        val candidates = when {
            os.contains("mac") -> listOf(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
            )
            os.contains("win") -> listOf(
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            )
            else -> listOf(
                "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium", "/usr/bin/chromium-browser",
                "/usr/bin/microsoft-edge", "/usr/bin/microsoft-edge-stable",
                "/snap/bin/chromium",
            )
        }
        candidates.firstOrNull { File(it).canExecute() }?.let { return it }

        // Anything on PATH, then a Playwright-managed Chromium if one is around.
        for (name in listOf("google-chrome", "chromium", "chromium-browser", "microsoft-edge")) {
            which(name)?.let { return it }
        }
        return playwrightChromium()
    }

    private fun which(name: String): String? = System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.map { File(it, name) }
        ?.firstOrNull { it.canExecute() }
        ?.absolutePath

    private fun playwrightChromium(): String? {
        val root = File(System.getenv("PLAYWRIGHT_BROWSERS_PATH") ?: return null)
        if (!root.isDirectory) return null
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("chromium-") }
            .sortedByDescending { it.name }
            .firstNotNullOfOrNull { dir ->
                listOf("chrome-linux/chrome", "chrome-mac/Chromium.app/Contents/MacOS/Chromium", "chrome-win/chrome.exe")
                    .map { File(dir, it) }
                    .firstOrNull { it.canExecute() }
                    ?.absolutePath
            }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** How long to keep listening after the first match, to see the siblings. */
    private const val SETTLE_MILLIS = 12_000L
    private const val RESPONSE_SAMPLE = 4_000
}
