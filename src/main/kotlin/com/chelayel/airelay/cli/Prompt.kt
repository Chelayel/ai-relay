package com.chelayel.airelay.cli

/**
 * Small terminal-input helpers for the interactive setup wizard. Reads go
 * through [Stdin] so the wizard cannot leave buffered input behind for the
 * agent's permission prompts to pick up as answers.
 */
object Prompt {

    /** A free-text field with an optional [default] shown in brackets. */
    fun text(label: String, default: String? = null, hint: String? = null): String {
        hint?.let { println(Ansi.dim("  $it")) }
        val suffix = default?.takeIf { it.isNotBlank() }?.let { " ${Ansi.dim("[$it]")}" } ?: ""
        print(Ansi.cyan("• ") + label + suffix + ": ")
        System.out.flush()
        val line = Stdin.readLine()?.trim().orEmpty()
        return line.ifBlank { default.orEmpty() }
    }

    /** A required field; re-asks until non-blank. */
    fun required(label: String, default: String? = null, hint: String? = null): String {
        while (true) {
            val v = text(label, default, hint)
            if (v.isNotBlank()) return v
            println(Ansi.red("  required."))
        }
    }

    /** A secret field: no echo when a real console is attached. */
    fun secret(label: String, hint: String? = null): String {
        hint?.let { println(Ansi.dim("  $it")) }
        val console = System.console()
        if (console != null) {
            val chars = console.readPassword(Ansi.cyan("• ") + label + ": ")
            return chars?.let { String(it).trim() }.orEmpty()
        }
        // No console (piped): fall back to a visible read.
        print(Ansi.cyan("• ") + label + ": ")
        System.out.flush()
        return Stdin.readLine()?.trim().orEmpty()
    }

    /** A yes/no question. */
    fun confirm(label: String, default: Boolean = true): Boolean {
        val hint = if (default) "[Y/n]" else "[y/N]"
        print(Ansi.cyan("• ") + label + " ${Ansi.dim(hint)}: ")
        System.out.flush()
        return when (Stdin.readLine()?.trim()?.lowercase()) {
            "y", "yes" -> true
            "n", "no" -> false
            else -> default
        }
    }

    /** A numbered single-choice menu. Returns the chosen index. */
    fun choose(label: String, options: List<Pair<String, String>>, default: Int = 0): Int {
        println(Ansi.bold(label))
        options.forEachIndexed { i, (name, blurb) ->
            val marker = if (i == default) Ansi.green("›") else " "
            println("  $marker ${i + 1}) ${Ansi.bold(name)}${if (blurb.isNotBlank()) "  ${Ansi.dim("— $blurb")}" else ""}")
        }
        while (true) {
            print(Ansi.cyan("• ") + "choice ${Ansi.dim("[${default + 1}]")}: ")
            System.out.flush()
            val line = Stdin.readLine()?.trim().orEmpty()
            if (line.isBlank()) return default
            val n = line.toIntOrNull()
            if (n != null && n in 1..options.size) return n - 1
            println(Ansi.red("  enter 1–${options.size}."))
        }
    }

    /** Multi-line input, one item per line, terminated by a blank line. */
    fun lines(label: String, hint: String? = null): List<String> {
        println(Ansi.bold(label) + Ansi.dim("  (one per line, blank line to finish)"))
        hint?.let { println(Ansi.dim("  $it")) }
        val out = mutableListOf<String>()
        while (true) {
            print(Ansi.cyan("• "))
            System.out.flush()
            val line = Stdin.readLine() ?: break
            if (line.isBlank()) break
            out.add(line.trim())
        }
        return out
    }
}
