package com.chelayel.airelay.agent

import com.chelayel.airelay.cli.Workspace
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The tools any agent backend may call, plus the executor that runs them.
 * Ported from Gemini Relay's Tools, with three deliberate CLI changes:
 *  - access is scoped to the whole [workspace] (repo root + any `--add-dir`
 *    folders) rather than a single confined project directory, and
 *  - shell commands run via plain [ProcessBuilder] instead of IntelliJ's ExecUtil, and
 *  - the declarations are backend-neutral [ToolSpec]s, so the Gemini and Copilot
 *    agents can share one set of tools despite wildly different transports.
 */
class Tools(
    private val workspace: Workspace,
    private val commandTimeoutSeconds: Int,
    private val onProcessStart: ((Process) -> Unit)? = null,
    private val onProcessEnd: (() -> Unit)? = null,
) {

    private val primary: File = workspace.primary

    /** The tools advertised to the model, in backend-neutral form. */
    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "readFile",
            description = "Read a text file, relative to the project root (or an absolute path within an allowed " +
                "directory). Give offset and limit to read part of a large file rather than all of it.",
            parameters = schema {
                prop("path", "string", "File path relative to the project root.")
                prop("offset", "integer", "First line to read, 1-based. Defaults to the start of the file.")
                prop("limit", "integer", "How many lines to read. Defaults to the rest of the file.")
                required("path")
            },
        ),
        ToolSpec(
            name = "editFile",
            description = "Change part of an existing file by replacing an exact snippet. This is the tool to use " +
                "for edits: it needs only the lines you are changing, not the whole file. The snippet must match " +
                "the file exactly, including indentation, and must appear exactly once unless all is true.",
            parameters = schema {
                prop("path", "string", "File path relative to the project root.")
                prop("find", "string", "The exact text to replace, copied from the file.")
                prop("replace", "string", "The text to put in its place. Empty string deletes the snippet.")
                prop("all", "boolean", "Replace every occurrence instead of requiring exactly one.")
                required("path", "find", "replace")
            },
        ),
        ToolSpec(
            name = "writeFile",
            description = "Create a new file, or replace an existing one entirely. For a change to an existing " +
                "file prefer editFile. Set append to add to the end instead, which is how to build a long file " +
                "across several calls.",
            parameters = schema {
                prop("path", "string", "File path relative to the project root.")
                prop("content", "string", "The full new content of the file, or the part to append.")
                prop("append", "boolean", "Add to the end of the file instead of replacing it.")
                required("path", "content")
            },
        ),
        ToolSpec(
            name = "listFiles",
            description = "List the files and directories directly inside a project directory (non-recursive).",
            parameters = schema {
                prop("path", "string", "Directory path relative to the project root. Defaults to the root.")
            },
        ),
        ToolSpec(
            name = "searchFiles",
            description = "Search file contents across the allowed directories for a regular expression and return matching lines with file:line.",
            parameters = schema {
                prop("pattern", "string", "Regular expression to search for.")
                prop("glob", "string", "Optional filename filter, e.g. '*.kt'.")
                required("pattern")
            },
        ),
        ToolSpec(
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
            "readFile" -> readFile(args.str("path"), args.optInt("offset"), args.optInt("limit"))
            "editFile" -> editFile(args.str("path"), args.str("find"), args.str("replace"), args.optBool("all"))
            "writeFile" -> writeFile(args.str("path"), args.str("content"), args.optBool("append"))
            "listFiles" -> listFiles(args.optStr("path") ?: ".")
            "searchFiles" -> searchFiles(args.str("pattern"), args.optStr("glob"))
            "runCommand" -> runCommand(args.str("command"))
            else -> error("Unknown tool: $name")
        }
    }.getOrElse { ok(error = it.message ?: "Tool '$name' failed.") }

    /** A short human-readable summary of a call, for the transcript. */
    fun summarize(name: String, args: JsonObject): String = when (name) {
        "readFile", "writeFile", "editFile" -> args.optStr("path").orEmpty()
        "listFiles" -> args.optStr("path") ?: "."
        "searchFiles" -> args.optStr("pattern").orEmpty()
        "runCommand" -> args.optStr("command").orEmpty()
        else -> ""
    }.lineSequence().firstOrNull()?.take(160).orEmpty()

    // ---- tool implementations ------------------------------------------------

    private fun readFile(path: String, offset: Int?, limit: Int?): JsonObject {
        val file = resolve(path)
        if (!file.isFile) return ok(error = "No such file: $path")
        if (offset != null || limit != null) return readRange(file, path, offset, limit)
        val text = file.readText()
        val clipped = if (text.length > MAX_READ) text.take(MAX_READ) + "\n… (truncated)" else text
        return ok(result = clipped)
    }

    private fun writeFile(path: String, content: String, append: Boolean?): JsonObject {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        val adding = append == true && file.isFile
        if (adding) file.appendText(content) else file.writeText(content)
        val verb = if (adding) "Appended" else "Wrote"
        return ok(result = "$verb ${content.length} chars to $path (now ${file.length()} bytes)")
    }

    /**
     * Replace an exact snippet. This exists because a whole-file write is the
     * wrong shape for editing: it costs the entire file in both directions, and
     * on a transport with a message-size limit that puts real files out of reach.
     *
     * A snippet that matches nothing, or matches several places, is an error
     * rather than a guess — silently editing the wrong occurrence is far worse
     * than being told to be more specific.
     */
    private fun editFile(path: String, find: String, replace: String, all: Boolean?): JsonObject {
        val file = resolve(path)
        if (!file.isFile) return ok(error = "No such file: $path")
        if (find.isEmpty()) return ok(error = "`find` was empty. Give the exact text to replace.")

        val text = file.readText()
        val hits = countOccurrences(text, find)
        if (hits == 0) {
            return ok(
                error = "That snippet is not in $path. It must match the file exactly, including " +
                    "indentation. Read the file and copy the lines you mean to change.",
            )
        }
        if (hits > 1 && all != true) {
            return ok(
                error = "That snippet appears $hits times in $path. Include more surrounding lines so it " +
                    "identifies one place, or set all=true to change every occurrence.",
            )
        }

        val updated = if (all == true) text.replace(find, replace) else {
            val at = text.indexOf(find)
            text.substring(0, at) + replace + text.substring(at + find.length)
        }
        file.writeText(updated)

        val where = if (all == true) "$hits occurrence(s)" else "1 occurrence"
        return ok(result = "Edited $path ($where)\n" + editPreview(find, replace))
    }

    /** A few lines of before/after, so the transcript shows what changed. */
    private fun editPreview(find: String, replace: String): String {
        fun side(marker: String, text: String): String {
            val lines = text.lines()
            val shown = lines.take(PREVIEW_LINES).joinToString("\n") { "$marker $it" }
            val more = lines.size - PREVIEW_LINES
            return if (more > 0) "$shown\n$marker … ($more more lines)" else shown
        }
        return side("-", find) + "\n" + side("+", replace)
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** Part of a file, so a large one can be read a piece at a time. */
    private fun readRange(file: File, path: String, offset: Int?, limit: Int?): JsonObject {
        val lines = file.readLines()
        val start = ((offset ?: 1) - 1).coerceIn(0, maxOf(lines.size - 1, 0))
        val end = if (limit == null) lines.size else (start + limit).coerceAtMost(lines.size)
        if (lines.isEmpty()) return ok(result = "($path is empty)")
        val slice = lines.subList(start, end).joinToString("\n")
        val header = "$path lines ${start + 1}-$end of ${lines.size}\n"
        val clipped = if (slice.length > MAX_READ) slice.take(MAX_READ) + "\n… (truncated)" else slice
        return ok(result = header + clipped)
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
        onProcessStart?.invoke(proc)
        val output = runCatching {
            proc.inputStream.bufferedReader().readText()
        }.getOrElse { "" }
        val finished = runCatching {
            proc.waitFor(commandTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        }.getOrElse { false }
        onProcessEnd?.invoke()
        if (!finished) {
            runCatching {
                proc.descendants().forEach { it.destroyForcibly() }
            }
            proc.destroyForcibly()
            return ok(error = "Command timed out after ${commandTimeoutSeconds}s.")
        }
        val combined = output.trim().ifBlank { "(no output)" }
        val clipped = if (combined.length > MAX_READ) combined.take(MAX_READ) + "\n… (truncated)" else combined
        return ok(result = "exit ${proc.exitValue()}\n$clipped")
    }

    // ---- path safety & helpers -----------------------------------------------

    private fun resolve(path: String): File {
        // An empty path is not a missing file, and saying "No such file: " with
        // nothing after the colon sends the model looking for a file that was
        // never named. It means the argument never arrived.
        require(path.isNotBlank()) {
            "The path argument was empty. Give the file's path relative to the working directory."
        }
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

    private fun JsonObject.optInt(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    /** Tolerant of a model that sends the flag as a string rather than a boolean. */
    private fun JsonObject.optBool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asBoolean }.getOrNull() ?: it.asString.equals("true", true)
        }

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
        private const val PREVIEW_LINES = 4
        private val BUILTIN_NAMES =
            setOf("readFile", "writeFile", "editFile", "listFiles", "searchFiles", "runCommand")
    }
}
