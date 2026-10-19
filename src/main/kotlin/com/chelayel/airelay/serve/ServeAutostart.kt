package com.chelayel.airelay.serve

import com.chelayel.airelay.config.Config
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * `airelay serve install` / `uninstall`: start the server at login, through
 * whatever the OS uses for that — a LaunchAgent on macOS, a systemd user
 * service on Linux, the Startup folder on Windows. Installing also starts it.
 *
 * What gets registered is the `airelay` launcher the user runs (the one on
 * PATH, or the packaged app's), not this process's java and classpath: a
 * Homebrew or Scoop upgrade moves those to a new versioned directory, and an
 * entry pointing at the old one would fail at the next login. Only Windows,
 * whose launcher is a console program that would open a window at every
 * login, runs javaw from an argument file — re-run `install` after upgrading
 * there.
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

    // ---- per OS ----------------------------------------------------------------

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
            |${command(serveArgs).joinToString("\n") { "    <string>${xml(it)}</string>" }}
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
            error("No systemd user session here. Add this to your desktop's autostart instead:\n  ${command(serveArgs).joinToString(" ") { quote(it) }}")
        }
        unit.parentFile.mkdirs()
        unit.writeText(
            """
            |[Unit]
            |Description=AI Relay model server (airelay serve)
            |After=network-online.target
            |
            |[Service]
            |ExecStart=${command(serveArgs).joinToString(" ") { systemdQuote(it) }}
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
        val bin = File(System.getProperty("java.home"), "bin")
        val javaw = File(bin, "javaw.exe").takeIf { it.isFile } ?: File(bin, "java.exe")
        // An argument file: the classpath alone can exceed what a command line holds.
        argFile.writeText((jvmArgs() + listOf("-cp", System.getProperty("java.class.path"), MAIN_CLASS, "serve") + serveArgs)
            .joinToString("\r\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
        startupCmd.parentFile.mkdirs()
        startupCmd.writeText(buildString {
            append("@echo off\r\n")
            System.getenv("AIRELAY_CONFIG")?.let { append("set \"AIRELAY_CONFIG=$it\"\r\n") }
            append("start \"\" \"${javaw.path}\" @\"${argFile.path}\"\r\n")
        })
        ProcessBuilder("cmd", "/c", startupCmd.path).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        return "the Startup folder (${startupCmd.path})"
    }

    // ---- the command -------------------------------------------------------------

    /** `airelay serve …` through the user's launcher when there is one, else this runtime directly. */
    private fun command(serveArgs: List<String>): List<String> {
        val launcher = System.getProperty("jpackage.app-path")?.takeIf { File(it).canExecute() }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "airelay") }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
        if (launcher != null) return listOf(launcher, "serve") + serveArgs
        val java = File(System.getProperty("java.home"), "bin/java").path
        return listOf(java) + jvmArgs() + listOf("-cp", System.getProperty("java.class.path"), MAIN_CLASS, "serve") + serveArgs
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
