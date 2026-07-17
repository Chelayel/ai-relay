package com.chelayel.airelay.cli

/**
 * The stream of events an agent turn produces, rendered to the terminal. Both the
 * Claude and Gemini agents drive this same interface, so the two backends look
 * and feel identical at the prompt despite very different transports underneath.
 */
interface Sink {
    /** Incremental assistant text (may arrive as many small deltas). */
    fun assistantText(text: String)
    fun thinking(text: String) {}
    fun toolUse(name: String, summary: String) {}
    fun toolResult(text: String, isError: Boolean) {}
    fun info(message: String) {}
    fun error(message: String) {}
    /** The turn finished (successfully or not); the prompt may be shown again. */
    fun turnComplete() {}
}

/** ANSI helpers; colours are suppressed when stdout is not a TTY or NO_COLOR is set. */
object Ansi {
    private val enabled: Boolean =
        System.console() != null && System.getenv("NO_COLOR").isNullOrEmpty()

    private fun wrap(code: String, s: String) = if (enabled) "[${code}m$s[0m" else s

    fun dim(s: String) = wrap("2", s)
    fun bold(s: String) = wrap("1", s)
    fun cyan(s: String) = wrap("36", s)
    fun green(s: String) = wrap("32", s)
    fun yellow(s: String) = wrap("33", s)
    fun red(s: String) = wrap("31", s)
    fun magenta(s: String) = wrap("35", s)
}

/**
 * Renders agent events to stdout. Text deltas are written raw (so streaming looks
 * live); everything else is a dim, prefixed status line. Tracks whether the
 * current line ended so status lines don't glue onto streamed text.
 */
class ConsoleSink : Sink {
    private var midLine = false

    private fun newlineIfNeeded() {
        if (midLine) { println(); midLine = false }
    }

    @Synchronized
    override fun assistantText(text: String) {
        print(text)
        System.out.flush()
        midLine = !text.endsWith("\n")
    }

    @Synchronized
    override fun thinking(text: String) {
        newlineIfNeeded()
        println(Ansi.dim("💭 " + text.trim().lines().joinToString(" ").take(200)))
    }

    @Synchronized
    override fun toolUse(name: String, summary: String) {
        newlineIfNeeded()
        val label = Ansi.magenta("⚙ $name")
        println(if (summary.isBlank()) label else "$label ${Ansi.dim(summary)}")
    }

    @Synchronized
    override fun toolResult(text: String, isError: Boolean) {
        newlineIfNeeded()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val preview = trimmed.lines().take(12).joinToString("\n")
        val more = trimmed.lines().size - 12
        val body = if (more > 0) "$preview\n${Ansi.dim("… (+$more lines)")}" else preview
        println(if (isError) Ansi.red(body) else Ansi.dim(body))
    }

    @Synchronized
    override fun info(message: String) {
        newlineIfNeeded()
        println(Ansi.dim(message))
    }

    @Synchronized
    override fun error(message: String) {
        newlineIfNeeded()
        println(Ansi.red("✗ $message"))
    }

    @Synchronized
    override fun turnComplete() {
        newlineIfNeeded()
    }
}
