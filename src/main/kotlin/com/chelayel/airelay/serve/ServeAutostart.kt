package com.chelayel.airelay.serve

import com.chelayel.airelay.config.Config
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Starting `airelay serve` as a process of its own: detached now
 * (`--background`), or at every login (`install` / `uninstall`) through what
 * the OS uses for that — a LaunchAgent on macOS, a systemd user service on
 * Linux, the Startup folder on Windows. Installing also starts it.
 *
 * **How this install starts itself.** A packaged install (Homebrew, .pkg, .msi,
 * .deb, Scoop) runs on a jlink runtime with no `java` command in it, so the
 * new process is the app's own launcher (`jpackage.app-path`); only a plain
 * `installDist` has a `java` to run. `--background` relaunching `java.home`'s
 * java failed on every Homebrew install with "No such file or directory".
 *
 * **What the login entry runs.** The `airelay` on PATH when there is one: the
 * packaged launcher's own path is versioned (a Homebrew Cellar directory) and
 * an upgrade would leave the entry pointing at a folder that is gone.
 *
 * On Windows both launchers are console programs, so they are started through
 * PowerShell with a hidden window, the command passed base64-encoded — the
 * only form whose quoting survives paths with spaces end to end.
 *
 * Restarting on a crash is deliberately not configured: `airelay serve stop`
 * ends the process with a signal, which a supervisor would read as a crash and
 * undo. A stopped server comes back at the next login.
 */
internal object ServeAutostart {
    const val LABEL = "com.chelayel.airelay.serve"
    private const val MAIN_CLASS = "com.chelayel.airelay.MainKt"

    private val os = System.getProperty("os.name").lowercase()
    private val mac = "mac" in os
    private val windows = "win" in os
    private val home = File(System.getProperty("user.home"))

    private val plist get() = File(home, "Library/LaunchAgents/$LABEL.plist")
    private val unit get() = File(home, ".config/systemd/user/airelay-serve.service")
    private val startupCmd get() = File(System.getenv("APPDATA") ?: home.path, "Microsoft/Windows/Start Menu/Programs/Startup/AI Relay serve.cmd")
    private val argFile get() = File(Config.file().absoluteFile.parentFile, "serve.args")

    // ---- this install, as a new process ---------------------------------------------

    /** `airelay serve <serveArgs>` from this install: the packaged launcher, else this runtime's java. */
    fun selfCommand(serveArgs: List<String>): List<String> {
        System.getProperty("jpackage.app-path")?.takeIf { File(it).canExecute() }?.let { return listOf(it, "serve") + serveArgs }
        val bin = File(System.getProperty("java.home"), "bin")
        val java = (if (windows) listOf("javaw.exe", "java.exe") else listOf("java")).map { File(bin, it) }.firstOrNull { it.isFile }
            ?: error("this install has no launcher to start another process from (no packaged launcher, no java in ${bin.path})")
        return listOf(java.path) + jvmArgs() + listOf("-cp", System.getProperty("java.class.path"), MAIN_CLASS, "serve") + serveArgs
    }

    /**
     * Starts [command] so that it outlives this terminal: under nohup (closing a
     * terminal hangs up its processes), or on Windows as javaw (no console) or
     * hidden through PowerShell. Returns the process when it can be watched; the
     * PowerShell route returns once the server is launched, so it cannot.
     */
    fun startDetached(command: List<String>, log: File): Process? {
        if (windows && !command[0].endsWith("javaw.exe", ignoreCase = true)) {
            ProcessBuilder(hiddenPowerShell(command)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
            return null
        }
        val nohup = listOf("/usr/bin/nohup", "/bin/nohup").firstOrNull { !windows && File(it).canExecute() }
        return ProcessBuilder(listOfNotNull(nohup) + command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .redirectInput(ProcessBuilder.Redirect.from(File(if (windows) "NUL" else "/dev/null")))
            .start()
    }

    private fun hiddenPowerShell(command: List<String>): List<String> {
        fun ps(s: String) = "'" + s.replace("'", "''") + "'"
        val argList = command.drop(1).joinToString(" ") { "\"" + it.replace("\"", "\\\"") + "\"" }
        val script = "Start-Process -WindowStyle Hidden -FilePath ${ps(command[0])}" + (if (argList.isEmpty()) "" else " -ArgumentList ${ps(argList)}")
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return listOf("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
    }

    // ---- at login ----------------------------------------------------------------

    /** Where it is registered, or null. */
    fun installed(): String? = when {
        mac && plist.isFile -> "launchd (${plist.path})"
        windows && startupCmd.isFile -> "the Startup folder (${startupCmd.path})"
        !mac && !windows && unit.isFile -> "systemd --user (${unit.path})"
        else -> null
    }

    /** Registers `airelay serve <serveArgs>` to start at login, and starts it. Returns where it was registered. */
    fun install(serveArgs: List<String>): Result<String> = runCatching {
        when {
            mac -> installLaunchd(serveArgs)
            windows -> installWindows(serveArgs)
            else -> installSystemd(serveArgs)
        }
    }

    /** Removes the registration (the running server is stopped by the caller). Returns what was removed, or null. */
    fun uninstall(): String? {
        val what = installed() ?: return null
        when {
            mac -> { exec("launchctl", "bootout", "gui/${uid()}/$LABEL"); plist.delete() }
            windows -> { startupCmd.delete(); argFile.delete() }
            else -> { exec("systemctl", "--user", "disable", "--now", unit.name); unit.delete(); exec("systemctl", "--user", "daemon-reload") }
        }
        return what
    }

    private fun installLaunchd(serveArgs: List<String>): String {
        val log = logOf(serveArgs)
        val env = buildMap {
            // launchd starts agents with a bare PATH; the start script resolves java from these.
            put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:/usr/local/bin")
            System.getenv("AIRELAY_CONFIG")?.let { put("AIRELAY_CONFIG", it) }
        }
        plist.parentFile.mkdirs()
        plist.writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |  <key>Label</key><string>$LABEL</string>
            |  <key>ProgramArguments</key>
            |  <array>
            |${loginCommand(serveArgs).joinToString("\n") { "    <string>${xml(it)}</string>" }}
            |  </array>
            |  <key>EnvironmentVariables</key>
            |  <dict>
            |${env.entries.joinToString("\n") { (k, v) -> "    <key>${xml(k)}</key><string>${xml(v)}</string>" }}
            |  </dict>
            |  <key>RunAtLoad</key><true/>
            |  <key>ProcessType</key><string>Background</string>
            |  <key>StandardOutPath</key><string>${xml(log)}</string>
            |  <key>StandardErrorPath</key><string>${xml(log)}</string>
            |</dict>
            |</plist>
            |""".trimMargin(),
        )
        val domain = "gui/${uid()}"
        exec("launchctl", "bootout", "$domain/$LABEL")
        val (code, out) = exec("launchctl", "bootstrap", domain, plist.path)
        if (code != 0) error("launchctl bootstrap failed ($code): $out")
        return "launchd (${plist.path})"
    }

    private fun installSystemd(serveArgs: List<String>): String {
        if (exec("systemctl", "--user", "--version").first != 0) {
            error("No systemd user session here. Add this to your desktop's autostart instead:\n  ${loginCommand(serveArgs).joinToString(" ") { quote(it) }}")
        }
        unit.parentFile.mkdirs()
        unit.writeText(
            """
            |[Unit]
            |Description=AI Relay model server (airelay serve)
            |After=network-online.target
            |
            |[Service]
            |ExecStart=${loginCommand(serveArgs).joinToString(" ") { systemdQuote(it) }}
            |${System.getenv("AIRELAY_CONFIG")?.let { "Environment=${systemdQuote("AIRELAY_CONFIG=$it")}" } ?: ""}
            |Restart=no
            |
            |[Install]
            |WantedBy=default.target
            |""".trimMargin(),
        )
        exec("systemctl", "--user", "daemon-reload")
        val (code, out) = exec("systemctl", "--user", "enable", "--now", unit.name)
        if (code != 0) error("systemctl --user enable failed ($code): $out")
        return "systemd --user (${unit.path})"
    }

    private fun installWindows(serveArgs: List<String>): String {
        val command = selfCommand(serveArgs)
        val line = if (command[0].endsWith("javaw.exe", ignoreCase = true)) {
            // A plain install's classpath alone can exceed what a command line holds: an argument file.
            argFile.writeText(command.drop(1).joinToString("\r\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
            "start \"\" \"${command[0]}\" @\"${argFile.path}\""
        } else {
            hiddenPowerShell(command).joinToString(" ")
        }
        startupCmd.parentFile.mkdirs()
        startupCmd.writeText(buildString {
            append("@echo off\r\n")
            System.getenv("AIRELAY_CONFIG")?.let { append("set \"AIRELAY_CONFIG=$it\"\r\n") }
            append(line).append("\r\n")
        })
        ProcessBuilder("cmd", "/c", startupCmd.path).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        return "the Startup folder (${startupCmd.path})"
    }

    /** The login entry's command: the `airelay` on PATH (stable across upgrades), else this install. */
    private fun loginCommand(serveArgs: List<String>): List<String> {
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "airelay") }.firstOrNull { it.isFile && it.canExecute() }
        return onPath?.let { listOf(it.absolutePath, "serve") + serveArgs } ?: selfCommand(serveArgs)
    }

    private fun jvmArgs(): List<String> = runCatching { ManagementFactory.getRuntimeMXBean().inputArguments }.getOrDefault(emptyList())
        .filter { !it.startsWith("-agentlib") && !it.startsWith("-javaagent") }

    private fun logOf(serveArgs: List<String>): String =
        serveArgs.getOrNull(serveArgs.indexOf("--log") + 1)?.takeIf { serveArgs.contains("--log") } ?: File(Config.file().absoluteFile.parentFile, "serve.log").path

    private fun uid(): String = exec("id", "-u").second.trim()

    private fun exec(vararg cmd: String): Pair<Int, String> = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); -1 to out } else p.exitValue() to out
    }.getOrElse { -1 to (it.message ?: "") }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun quote(s: String) = if (s.any { it.isWhitespace() || it == '"' }) "\"" + s.replace("\"", "\\\"") + "\"" else s
    private fun systemdQuote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%") + "\""
}
