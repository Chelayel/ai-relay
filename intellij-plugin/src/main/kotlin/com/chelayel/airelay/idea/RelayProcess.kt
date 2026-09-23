package com.chelayel.airelay.idea

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import kotlin.io.path.listDirectoryEntries

/**
 * One `airelay <backend> --json` process, run from the jars bundled in this
 * plugin on the IDE's own Java (or the one configured). The IDE never talks to
 * a model directly: it launches the CLI and relays lines, which is why every
 * front-end behaves the same. See docs/protocol.md in the repository.
 */
class RelayProcess(
    private val backend: String,
    private val projectDir: String,
    private val permissionMode: String,
    private val onEvent: (JsonObject) -> Unit,
    private val onStderr: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    fun start() {
        val command = buildList {
            add(javaExecutable())
            add("-cp"); add(classpath().joinToString(File.pathSeparator))
            add(MAIN_CLASS)
            add(backend)
            add("--json")
            add("--dir"); add(projectDir)
            add("--permission-mode"); add(permissionMode)
            RelaySettings.get().state.extraArgs.split(" ").filter { it.isNotBlank() }.forEach { add(it) }
        }
        LOG.info("Starting: " + command.joinToString(" "))
        val pb = ProcessBuilder(command).directory(File(projectDir))
        // A GUI-launched IDE has a bare PATH; the agent shells out to git, gradle, claude…
        pb.environment()["PATH"] = extraPath() + File.pathSeparator + (pb.environment()["PATH"] ?: "")
        val p = pb.start()
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
        Thread({
            p.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val obj = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                    onEvent(obj)
                }
            }
        }, "airelay-stdout").apply { isDaemon = true; start() }
        Thread({
            p.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines -> lines.forEach(onStderr) }
        }, "airelay-stderr").apply { isDaemon = true; start() }
        Thread({ onExit(p.waitFor()) }, "airelay-exit").apply { isDaemon = true; start() }
    }

    val isAlive: Boolean get() = process?.isAlive == true

    @Synchronized
    fun command(obj: JsonObject) {
        val w = writer ?: return
        runCatching { w.write(obj.toString()); w.write("\n"); w.flush() }
            .onFailure { LOG.warn("Could not write to airelay", it) }
    }

    fun send(text: String, skills: List<String> = emptyList(), images: List<JsonObject> = emptyList()) = command(JsonObject().apply {
        addProperty("type", "send"); addProperty("text", text)
        if (skills.isNotEmpty()) add("skills", com.google.gson.JsonArray().apply { skills.forEach { add(it) } })
        if (images.isNotEmpty()) add("images", com.google.gson.JsonArray().apply { images.forEach { add(it) } })
    })
    fun cancel() = command(JsonObject().apply { addProperty("type", "cancel") })
    fun permission(id: Int, decision: String) = command(JsonObject().apply {
        addProperty("type", "permission"); addProperty("id", id); addProperty("decision", decision)
    })

    fun stop() {
        runCatching { command(JsonObject().apply { addProperty("type", "exit") }) }
        val p = process ?: return
        Thread({
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.descendants().forEach { runCatching { it.destroyForcibly() } }
                p.destroyForcibly()
            }
        }, "airelay-stop").apply { isDaemon = true; start() }
    }

    companion object {
        private val LOG = Logger.getInstance(RelayProcess::class.java)
        const val MAIN_CLASS = "com.chelayel.airelay.MainKt"
        private val CLI_JAR = Regex("ai-relay-\\d")

        /** The configured Java, else the JVM the IDE itself runs on (always current). */
        fun javaExecutable(): String {
            val configured = RelaySettings.get().state.javaPath.trim()
            if (configured.isNotEmpty()) {
                val f = File(configured)
                return if (f.isDirectory) File(f, "bin/java").path else f.path
            }
            val home = System.getProperty("java.home")
            val exe = if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java"
            return File(home, exe).path
        }

        /**
         * The CLI's jars, kept in lib/airelay/ where the IDE's classloader does
         * not look. Found relative to this plugin's own jar (lib/<plugin>.jar).
         */
        fun classpath(): List<String> {
            val own = PathManager.getJarPathForClass(RelayProcess::class.java)
                ?: error("Cannot locate the AI Relay plugin jar")
            val lib = File(own).parentFile.toPath().resolve("airelay")
            return lib.listDirectoryEntries("*.jar").map { it.toString() }.sorted()
                .also { require(it.any { j -> CLI_JAR.containsMatchIn(j) }) { "The airelay jar is missing from $lib" } }
        }

        /** The CLI-side list, plus where a shell would find things on this machine. */
        private fun extraPath(): String {
            val home = System.getProperty("user.home")
            return listOf(
                "$home/.local/bin", "$home/.claude/local", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin",
            ).joinToString(File.pathSeparator)
        }

        /** The bundled CLI with [args], as a process command line. */
        fun cliCommandLine(vararg args: String): List<String> =
            listOf(javaExecutable(), "-cp", classpath().joinToString(File.pathSeparator), MAIN_CLASS) + args

        /** The same, as one line to paste into a terminal. */
        fun cliCommand(vararg args: String): String {
            val q = { s: String -> if (s.any { it.isWhitespace() }) "\"$s\"" else s }
            return runCatching { cliCommandLine(*args).joinToString(" ", transform = q) }
                .getOrElse { "airelay " + args.joinToString(" ") }
        }
    }
}
