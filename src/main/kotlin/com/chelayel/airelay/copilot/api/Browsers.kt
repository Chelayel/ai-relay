package com.chelayel.airelay.copilot.api

import java.io.File
import java.net.ServerSocket

/**
 * Finding, launching and attaching to a Chrome/Chromium/Edge with its debugger
 * open. Shared by [BrowserCapture], which watches a page to learn the session,
 * and by the browser-driven backend, which keeps a page open and talks through
 * it. Both want the same profile — `~/.airelay/browser` — so signing in once
 * serves whichever is used.
 */
internal object Browsers {

    fun findOrOpenPage(port: Int, url: String): DevTools.Companion.Page? {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        repeat(20) {
            val pages = DevTools.listPages(port).orEmpty()
            pages.firstOrNull { page -> host != null && runCatching { java.net.URI(page.url).host }.getOrNull() == host }
                ?.let { return it }
            Thread.sleep(500)
        }
        return DevTools.newPage(port, url)
    }

    fun launch(exe: String, port: Int, url: String): Process {
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
    fun find(): String? {
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

    fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
