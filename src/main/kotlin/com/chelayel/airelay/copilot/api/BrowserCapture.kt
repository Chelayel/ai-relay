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

    /**
     * Open [url], wait for a request whose body contains [nonce], and return it.
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
    ): CurlImport.Captured {
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
        val headers = ConcurrentHashMap<String, String>()
    }

    private fun watch(
        cdp: DevTools,
        nonce: String,
        timeoutSeconds: Long,
        progress: Progress,
    ): CurlImport.Captured {
        val inFlight = ConcurrentHashMap<String, Partial>()
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        var sawWebSocket = false

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
            if (p.body?.contains(nonce) == true && found.compareAndSet(null, id)) latch.countDown()
        }

        // The plain event hides cookies; this one carries the real header set.
        cdp.on("Network.requestWillBeSentExtraInfo") { params ->
            val id = params.get("requestId")?.asString ?: return@on
            mergeHeaders(partial(id), params.getAsJsonObject("headers"))
        }

        cdp.on("Network.webSocketCreated") { sawWebSocket = true }

        cdp.call("Network.enable", JsonObject().apply {
            // Big enough to hold a chat request body without Chrome dropping it.
            addProperty("maxPostDataSize", 10 * 1024 * 1024)
        })
        cdp.notify("Page.enable")

        progress.status("Watching the browser. Waiting for your message…")
        val completed = latch.await(timeoutSeconds, TimeUnit.SECONDS)

        if (!completed) {
            if (sawWebSocket) {
                throw CaptureException(
                    "No matching request appeared, but the page did open a WebSocket. If Copilot sends " +
                        "chat over a WebSocket rather than a POST, this backend can't replay it yet.",
                )
            }
            throw CaptureException(
                "Timed out waiting for a request containing \"$nonce\". Make sure you sent exactly that " +
                    "text as a Copilot message, and try `--timeout` for longer.",
            )
        }

        val p = inFlight[found.get()] ?: throw CaptureException("The captured request vanished before it could be read.")
        // Give the cookie-bearing event a moment if it hasn't landed yet.
        if (p.headers.keys.none { it.equals("cookie", true) || it.equals("authorization", true) }) {
            Thread.sleep(1_500)
        }

        val headers = LinkedHashMap<String, String>()
        p.headers.entries
            .sortedBy { it.key.lowercase() }
            .forEach { (k, v) -> if (CurlImport.isReplayable(k)) headers[k] = v }

        return CurlImport.Captured(
            url = p.url ?: throw CaptureException("The captured request had no URL."),
            method = p.method,
            headers = headers,
            body = p.body,
        )
    }

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
}
