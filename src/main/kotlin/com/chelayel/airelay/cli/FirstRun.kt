package com.chelayel.airelay.cli

import java.io.File

/**
 * What a bare `airelay` does on a terminal, for someone who was handed an
 * installer rather than a README.
 *
 * Printing usage is the right answer to a developer and no answer at all to
 * someone who double-clicked a Start-menu entry: the window shows a page of
 * flags and closes. So with no arguments and a person at the keyboard, ask the
 * two things the command line would have said — which agent, over which folder
 * — and carry on as if they had been typed.
 */
object FirstRun {

    /** The arguments the answers amount to, e.g. `[claude, --dir, /path]`. */
    fun chooseArguments(): List<String> {
        println()
        println(Ansi.bold("AI Relay") + Ansi.dim("  — run `airelay --help` for the command-line form"))
        println()
        val backends = listOf(
            "claude" to "uses the Claude Code CLI you are already signed in to",
            "gemini" to "Gemini API key, Vertex AI, or Apigee",
            "copilot" to "your signed-in Copilot web session",
            "demo" to "no account needed: try the prompt and the display",
        )
        val backend = backends[Prompt.choose("Which agent?", backends)].first

        val here = File(".").canonicalFile
        // Started from a shortcut, "here" is the install folder or System32 —
        // never the project. From a shell it usually is, so it stays the default.
        val default = if (looksLikeAProject(here)) here.path else ""
        while (true) {
            val answer = Prompt.text("Project folder", default.ifEmpty { null }, hint = "the code the agent will work on")
            val dir = File(expandHome(answer.trim().removeSurrounding("\"")))
            if (answer.isNotBlank() && dir.isDirectory) return listOf(backend, "--dir", dir.canonicalPath)
            println(Ansi.red("  not a folder: ${answer.ifBlank { "(nothing entered)" }}"))
        }
    }

    private fun looksLikeAProject(dir: File): Boolean {
        val home = File(System.getProperty("user.home")).canonicalFile
        if (dir == home || dir.parentFile == null) return false
        val installed = System.getProperty(APP_PATH)?.let { File(it).canonicalFile.parentFile }
        if (installed != null && dir.path.startsWith(installed.path)) return false
        return !dir.path.contains("system32", ignoreCase = true)
    }

    private fun expandHome(path: String): String =
        if (path == "~" || path.startsWith("~/") || path.startsWith("~\\")) {
            System.getProperty("user.home") + path.substring(1)
        } else {
            path
        }

    /**
     * Windows, installed from the .msi: offer to put `airelay` on the PATH.
     *
     * jpackage's .msi installs the program and tells nothing where it went, so
     * `airelay` in a terminal is "not recognized" straight after a successful
     * install. The launcher knows its own location (`jpackage.app-path`), which
     * is everything needed to fix that — once, with consent, in the *user*
     * PATH so no administrator prompt is involved. Asked one time only: a
     * refusal is an answer, and is remembered.
     */
    fun offerWindowsPath() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        val launcher = System.getProperty(APP_PATH)?.let(::File) ?: return
        val dir = launcher.parentFile?.path ?: return
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .any { it.trimEnd('\\').equals(dir.trimEnd('\\'), ignoreCase = true) }
        if (onPath) return
        val marker = File(File(System.getProperty("user.home"), ".airelay"), "path-offered")
        if (marker.exists()) return
        runCatching { marker.parentFile.mkdirs(); marker.writeText(dir) }

        println(Ansi.yellow("`airelay` is not on your PATH") + Ansi.dim(" — other terminals won't find it."))
        if (!Prompt.confirm("Add it to your PATH?", default = true)) return
        // Read-modify-write the user PATH in PowerShell rather than `setx`,
        // which truncates at 1024 characters and would merge in the machine PATH.
        val script = "\$d='${dir.replace("'", "''")}';" +
            "\$p=[Environment]::GetEnvironmentVariable('Path','User');" +
            "if((\$p -split ';') -notcontains \$d){" +
            "[Environment]::SetEnvironmentVariable('Path',((\$p.TrimEnd(';')+';'+\$d).TrimStart(';')),'User')}"
        val ok = runCatching {
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start()
                .also { it.inputStream.readAllBytes() }
                .waitFor() == 0
        }.getOrDefault(false)
        if (ok) println(Ansi.green("✓ ") + Ansi.dim("added. Open a new terminal and run `airelay` from any project folder."))
        else println(Ansi.red("Could not change the PATH.") + Ansi.dim(" Add this folder to it by hand: $dir"))
    }

    private const val APP_PATH = "jpackage.app-path"
}
