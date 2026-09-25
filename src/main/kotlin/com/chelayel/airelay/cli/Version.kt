package com.chelayel.airelay.cli

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Which build this is, and whether a newer one exists. The version comes from
 * the jar manifest the release build stamps; a Gradle run says "dev". The
 * newer-version check asks GitHub for the latest release at most once a day
 * (`~/.airelay/update-check`), on a background thread, and the hint names the
 * upgrade command for the route this copy was installed by — the tool can see
 * that from where its launcher lives, which is more than a "new version
 * available" banner usually manages.
 */
object Version {

    val current: String = Version::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() } ?: "dev"

    /** Set by [checkInBackground] once an answer is in; null until then or when up to date. */
    @Volatile var updateHint: String? = null
        private set

    fun checkInBackground() {
        if (current == "dev") return
        Thread({
            runCatching {
                val latest = latestRelease() ?: return@runCatching
                if (isNewer(latest, current)) updateHint = "airelay $latest is out (this is $current): " + upgradeCommand()
            }
        }, "airelay-update-check").apply { isDaemon = true; start() }
    }

    /** The latest release tag without its `v`, from a day-old cache or GitHub. */
    fun latestRelease(): String? {
        val cache = File(System.getProperty("user.home") ?: ".", ".airelay/update-check")
        runCatching {
            if (cache.isFile) {
                val (tag, at) = cache.readText().trim().split(' ')
                if (System.currentTimeMillis() - at.toLong() < CACHE_MILLIS) return tag
            }
        }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NORMAL).build()
        val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/Chelayel/ai-relay/releases/latest"))
            .timeout(Duration.ofSeconds(4)).header("Accept", "application/vnd.github+json").header("User-Agent", "airelay/$current").GET().build()
        val body = client.send(request, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() == 200 }?.body() ?: return null
        val tag = Regex("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return null
        runCatching { cache.parentFile.mkdirs(); cache.writeText("$tag ${System.currentTimeMillis()}") }
        return tag
    }

    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** How to upgrade the copy that is running, by the route it was installed through. */
    fun upgradeCommand(): String {
        // The copy that is running, not the first on PATH: with two installs they differ,
        // and the hint would upgrade the other one.
        val route = runCatching { Installs.running() }.getOrNull() ?: runCatching { Installs.onPath().firstOrNull()?.route }.getOrNull()
        return when (route) {
            Installs.Route.HOMEBREW -> "brew update && brew upgrade airelay"
            Installs.Route.SCOOP -> "scoop update airelay"
            Installs.Route.SCRIPT -> "curl -fsSL https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.sh | sh"
            Installs.Route.SCRIPT_PS -> "irm https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.ps1 | iex"
            Installs.Route.PKG, Installs.Route.MSI, Installs.Route.DEB -> "download the new installer from https://github.com/Chelayel/ai-relay/releases/latest"
            else -> "https://github.com/Chelayel/ai-relay/releases/latest"
        }
    }

    private const val CACHE_MILLIS = 24L * 60 * 60 * 1000
}
