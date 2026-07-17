package com.chelayel.airelay.gemini.agent

import com.chelayel.airelay.cli.Workspace
import com.chelayel.airelay.gemini.api.FunctionDecl
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The tools the model may call in agent mode, plus the executor that runs them.
 * Ported from Gemini Relay's Tools, with two deliberate CLI changes:
 *  - access is scoped to the whole [workspace] (repo root + any `--add-dir`
 *    folders) rather than a single confined project directory, and
 *  - shell commands run via plain [ProcessBuilder] instead of IntelliJ's ExecUtil.
 */
class Tools(private val workspace: Workspace, private val commandTimeoutSeconds: Int) {

    private val primary: File = workspace.primary

    /** Function declarations advertised to the model. */
    fun declarations(): List<FunctionDecl> = listOf(
        FunctionDecl(
            name = "readFile",
            description = "Read the contents of a text file, relative to the project root (or an absolute path within an allowed directory).",
            parameters = schema {
                prop("path", "string", "File path relative to the project root.")
                required("path")
            },
        ),
        FunctionDecl(
            name = "writeFile",
            description = "Create or overwrite a text file with the given content.",
            parameters = schema {
                prop("path", "string", "File path relative to the project root.")
                prop("content", "string", "The full new content of the file.")
                required("path", "content")
            },
        ),
        FunctionDecl(
            name = "listFiles",
            description = "List the files and directories directly inside a project directory (non-recursive).",
            parameters = schema {
                prop("path", "string", "Directory path relative to the project root. Defaults to the root.")
            },
        ),
        FunctionDecl(
            name = "searchFiles",
            description = "Search file contents across the allowed directories for a regular expression and return matching lines with file:line.",
            parameters = schema {
                prop("pattern", "string", "Regular expression to search for.")
                prop("glob", "string", "Optional filename filter, e.g. '*.kt'.")
                required("pattern")
            },
        ),
        FunctionDecl(
            name = "runCommand",
            description = "Run a shell command in the project directory and return its combined output.",
            parameters = schema {
                prop("command", "string", "The shell command line to execute.")
                required("command")
            },
        ),
    )

    /** True when [name] is one of the built-in tools. */
    fun handles(name: String): Boolean = name in BUILTIN_NAMES

    /** Execute one call and return the `response` object to feed back to the model. */
    fun execute(name: String, args: JsonObject): JsonObject = runCatching {
        when (name) {
            "readFile" -> readFile(args.str("path"))
            "writeFile" -> writeFile(args.str("path"), args.str("content"))
            "listFiles" -> listFiles(args.optStr("path") ?: ".")
            "searchFiles" -> searchFiles(args.str("pattern"), args.optStr("glob"))
            "runCommand" -> runCommand(args.str("command"))
            else -> error("Unknown tool: $name")
        }
    }.getOrElse { ok(error = it.message ?: "Tool '$name' failed.") }

    /** A short human-readable summary of a call, for the transcript. */
    fun summarize(name: String, args: JsonObject): String = when (name) {
        "readFile", "writeFile" -> args.optStr("path").orEmpty()
        "listFiles" -> args.optStr("path") ?: "."
        "searchFiles" -> args.optStr("pattern").orEmpty()
        "runCommand" -> args.optStr("command").orEmpty()
        else -> ""
    }.lineSequence().firstOrNull()?.take(160).orEmpty()

    // ---- tool implementations ------------------------------------------------

    private fun readFile(path: String): JsonObject {
        val file = resolve(path)
        if (!file.isFile) return ok(error = "No such file: $path")
        val text = file.readText()
        val clipped = if (text.length > MAX_READ) text.take(MAX_READ) + "\n… (truncated)" else text
        return ok(result = clipped)
    }

    private fun writeFile(path: String, content: String): JsonObject {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return ok(result = "Wrote ${content.length} chars to $path")
    }

    private fun listFiles(path: String): JsonObject {
        val dir = resolve(path)
        if (!dir.isDirectory) return ok(error = "Not a directory: $path")
        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.joinToString("\n") { if (it.isDirectory) "${it.name}/" else it.name }
            ?: ""
        return ok(result = entries.ifBlank { "(empty)" })
    }

    private fun searchFiles(pattern: String, glob: String?): JsonObject {
        val regex = runCatching { Regex(pattern) }.getOrElse { return ok(error = "Invalid regex: ${it.message}") }
        val globRegex = glob?.let { globToRegex(it) }
        val hits = StringBuilder()
        var count = 0
        for (root in workspace.roots) {
            root.walkTopDown()
                .onEnter { it.name != ".git" && it.name != "build" && it.name != "node_modules" && it.name != ".gradle" }
                .filter { it.isFile && (globRegex == null || globRegex.matches(it.name)) }
                .forEach { file ->
                    if (count >= MAX_HITS) return@forEach
                    runCatching {
                        file.useLines { lines ->
                            lines.forEachIndexed { i, line ->
                                if (count < MAX_HITS && regex.containsMatchIn(line)) {
                                    hits.append("${relativeLabel(file)}:${i + 1}: ${line.trim().take(200)}\n")
                                    count++
                                }
                            }
                        }
                    }
                }
            if (count >= MAX_HITS) break
        }
        return ok(result = if (count == 0) "No matches." else hits.toString())
    }

    private fun runCommand(command: String): JsonObject {
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val pb = if (isWin) ProcessBuilder("cmd.exe", "/c", command)
        else ProcessBuilder("/bin/sh", "-c", command)
        pb.directory(primary)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(commandTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return ok(error = "Command timed out after ${commandTimeoutSeconds}s.")
        }
        val combined = output.trim().ifBlank { "(no output)" }
        val clipped = if (combined.length > MAX_READ) combined.take(MAX_READ) + "\n… (truncated)" else combined
        return ok(result = "exit ${proc.exitValue()}\n$clipped")
    }

    // ---- path safety & helpers -----------------------------------------------

    private fun resolve(path: String): File {
        val file = File(path).let { if (it.isAbsolute) it else File(primary, path) }.canonicalFile
        require(workspace.contains(file)) { "Path escapes the allowed directories: $path" }
        return file
    }

    /** Shortest label for a file: relative to whichever root contains it. */
    private fun relativeLabel(file: File): String {
        val root = workspace.roots.firstOrNull { file.path.startsWith(it.path) } ?: primary
        return root.toPath().relativize(file.toPath()).toString()
    }

    private fun ok(result: String? = null, error: String? = null): JsonObject = JsonObject().apply {
        if (error != null) addProperty("error", error) else addProperty("result", result ?: "")
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) when (c) {
            '*' -> sb.append("[^/]*")
            '?' -> sb.append('.')
            '.' -> sb.append("\\.")
            else -> sb.append(Regex.escape(c.toString()))
        }
        return Regex(sb.toString())
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: error("Missing required argument: $key")

    private fun JsonObject.optStr(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private class SchemaBuilder {
        val props = JsonObject()
        val req = JsonArray()
        fun prop(name: String, type: String, description: String) {
            props.add(name, JsonObject().apply {
                addProperty("type", type)
                addProperty("description", description)
            })
        }
        fun required(vararg names: String) = names.forEach { req.add(it) }
        fun build(): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            add("properties", props)
            if (req.size() > 0) add("required", req)
        }
    }

    private fun schema(block: SchemaBuilder.() -> Unit): JsonObject =
        SchemaBuilder().apply(block).build()

    companion object {
        private const val MAX_READ = 60_000
        private const val MAX_HITS = 200
        private val BUILTIN_NAMES = setOf("readFile", "writeFile", "listFiles", "searchFiles", "runCommand")
    }
}
