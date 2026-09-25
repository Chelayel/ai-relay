package com.chelayel.airelay.cli

/**
 * Small terminal-input helpers for the interactive setup wizard. Reads go
 * through [Stdin] so the wizard cannot leave buffered input behind for the
 * agent's permission prompts to pick up as answers.
 */
object Prompt {

    /** Input closed (Ctrl-D) or interrupted (Ctrl-C) in the middle of a wizard: the wizard ends. */
    class Aborted : RuntimeException("Input closed.")

    private fun read(prompt: String): String {
        val line = Stdin.readLine(prompt) ?: throw Aborted()
        // The line editor answers Ctrl-C with "" and a pending interrupt.
        if (Stdin.editor?.takeInterrupt() == true) throw Aborted()
        return line
    }

    /** A free-text field with an optional [default] shown in brackets. */
    fun text(label: String, default: String? = null, hint: String? = null): String {
        hint?.let { println(Ansi.dim("  $it")) }
        val suffix = default?.takeIf { it.isNotBlank() }?.let { " ${Ansi.dim("[$it]")}" } ?: ""
        val line = read(Ansi.cyan("• ") + label + suffix + ": ").trim()
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
        return Stdin.readSecret(Ansi.cyan("• ") + label + ": ")?.trim().orEmpty()
    }

    /** A yes/no question. */
    fun confirm(label: String, default: Boolean = true): Boolean {
        val hint = if (default) "[Y/n]" else "[y/N]"
        return when (read(Ansi.cyan("• ") + label + " ${Ansi.dim(hint)}: ").trim().lowercase()) {
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
            val line = read(Ansi.cyan("• ") + "choice ${Ansi.dim("[${default + 1}]")}: ").trim()
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
            val line = Stdin.readLine(Ansi.cyan("• ")) ?: break
            if (line.isBlank()) break
            out.add(line.trim())
        }
        return out
    }
}
