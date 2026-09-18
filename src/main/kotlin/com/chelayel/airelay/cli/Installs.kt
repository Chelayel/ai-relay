package com.chelayel.airelay.cli

import java.io.File

/**
 * Notices when `airelay` is installed more than once and says which to remove.
 *
 * Every install route puts the command somewhere different — the script in
 * `~/.local/bin`, Homebrew under its prefix, the .pkg in `/usr/local/bin`, the
 * .deb in `/opt`, Scoop in its shims — and none of them knows about the
 * others. Someone who installed with the script and later switched to Homebrew
 * ends up with two, PATH order picks one, and it is usually the old one: the
 * new install "does nothing" and every upgrade after it is invisible. A package
 * manager cannot delete what another one installed, so the only fix is to tell
 * the user, once per run, with the exact command. This runs from whichever
 * copy won, which is what makes it reach the case no installer script sees.
 */
object Installs {

    enum class Route(val label: String) {
        SCRIPT("install.sh"),
        SCRIPT_PS("install.ps1"),
        HOMEBREW("Homebrew"),
        PKG("macOS .pkg"),
        DEB(".deb"),
        MSI("Windows installer"),
        SCOOP("Scoop"),
        DEV("Gradle installDist"),
        OTHER("unknown"),
    }

    /** One `airelay` on the PATH: where PATH found it, what it really is. */
    data class Found(val onPath: File, val target: File, val route: Route)

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private var warned = false

    /** Print a warning to stderr if there is more than one install. Never throws. */
    fun warnIfDuplicated() {
        if (warned) return
        warned = true
        val found = runCatching { onPath() }.getOrDefault(emptyList())
        if (found.size < 2) return
        System.err.println(Ansi.yellow(message(found)))
    }

    /** Distinct installs reachable through PATH, in PATH order (the first wins). */
    fun onPath(
        path: String = System.getenv("PATH").orEmpty(),
        home: String = System.getProperty("user.home"),
        localAppData: String? = System.getenv("LOCALAPPDATA"),
        windows: Boolean = this.windows,
    ): List<Found> {
        val names = if (windows) listOf("airelay.exe", "airelay.cmd", "airelay.bat") else listOf("airelay")
        val seen = HashSet<String>()
        return path.split(File.pathSeparator).filter { it.isNotBlank() }.flatMap { dir ->
            names.mapNotNull { name ->
                val file = File(dir, name)
                if (!file.isFile) return@mapNotNull null
                val target = runCatching { file.canonicalFile }.getOrDefault(file)
                // A wrapper script (Homebrew's, or a Scoop shim) is not a symlink,
                // so canonicalising stops at it; classify what it stands in for.
                if (!seen.add(target.path)) null else Found(file, target, classify(file, target, home, localAppData, windows))
            }
        }
    }

    fun message(found: List<Found>): String {
        val oldest = found.minByOrNull { installedAt(it) }
        val lines = mutableListOf("airelay is installed more than once, and the first on your PATH is the one that runs:")
        found.forEachIndexed { i, f ->
            val note = when {
                i == 0 && f === oldest -> "runs, but is the older one"
                i == 0 -> "runs"
                f === oldest -> "older"
                else -> ""
            }
            lines += "  ${f.onPath.path}  (${f.route.label})${if (note.isEmpty()) "" else "  <- $note"}"
        }
        if (oldest != null) {
            lines += "Keep the one you installed last. To remove the older one (${oldest.route.label}):"
            uninstall(oldest).forEach { lines += "  $it" }
        }
        return lines.joinToString("\n")
    }

    /** When it was installed: the launcher's mtime, or the PATH entry's when that is all there is. */
    private fun installedAt(f: Found): Long =
        f.target.lastModified().takeIf { it > 0 } ?: f.onPath.lastModified()

    /** The command(s) that remove this install; a description when there is no command. */
    fun uninstall(f: Found): List<String> = when (f.route) {
        Route.SCRIPT -> listOf("rm ~/.local/bin/airelay && rm -rf ~/.local/share/airelay")
        Route.SCRIPT_PS -> listOf(
            "Remove-Item -Recurse -Force \"\$env:LOCALAPPDATA\\Programs\\airelay\"",
            "then take that folder out of your user PATH (Settings > System > About > Advanced system settings > Environment Variables)",
        )
        Route.HOMEBREW -> listOf("brew uninstall airelay")
        Route.PKG -> listOf("sudo rm -rf /Applications/airelay.app /usr/local/bin/airelay && sudo pkgutil --forget com.chelayel.airelay")
        Route.DEB -> listOf("sudo apt remove airelay")
        Route.MSI -> listOf("Settings > Apps > Installed apps > airelay > Uninstall")
        Route.SCOOP -> listOf("scoop uninstall airelay")
        Route.DEV -> listOf("rm ${f.onPath.path}  (a link to a Gradle build in ${generateSequence(f.target.parentFile) { it.parentFile }.drop(4).firstOrNull()?.path ?: "a checkout"})")
        Route.OTHER -> listOf("remove ${f.onPath.path}" + if (f.target != f.onPath) " (it runs ${f.target.path})" else "")
    }

    fun classify(onPath: File, target: File, home: String, localAppData: String?, windows: Boolean): Route {
        val t = target.path.replace('\\', '/').lowercase()
        val p = onPath.path.replace('\\', '/').lowercase()
        val h = home.replace('\\', '/').lowercase().trimEnd('/')
        if (windows) {
            val lad = localAppData?.replace('\\', '/')?.lowercase()?.trimEnd('/')
            return when {
                "/scoop/" in t || "/scoop/" in p -> Route.SCOOP
                lad != null && t.startsWith("$lad/programs/airelay/") -> Route.SCRIPT_PS
                lad != null && t.startsWith("$lad/airelay/") -> Route.MSI
                "/build/install/airelay/" in t -> Route.DEV
                else -> Route.OTHER
            }
        }
        // Homebrew's `airelay` is a wrapper script, not a symlink, so on an Intel
        // Mac it sits in /usr/local/bin looking exactly like the .pkg's link.
        // The script names the Cellar; a few hundred bytes tell them apart.
        val wrapper = if (target.length() in 1..4096) runCatching { target.readText().lowercase() }.getOrDefault("") else ""
        return when {
            "/cellar/airelay/" in t || "/cellar/airelay/" in wrapper || p.startsWith("/opt/homebrew/bin/") || "/.linuxbrew/" in p -> Route.HOMEBREW
            t.startsWith("$h/.local/share/airelay/") -> Route.SCRIPT
            "/applications/airelay.app/" in t -> Route.PKG
            t.startsWith("/opt/airelay/") -> Route.DEB
            "/build/install/airelay/" in t -> Route.DEV
            else -> Route.OTHER
        }
    }
}
