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
    /** A file the agent changed: shown as a diff, and revertable by [revertId] (see `agent/Edits`). */
    fun fileChanged(path: String, diff: String, revertId: String) {}
    /** Token and cost accounting after a turn, from backends that report it. */
    fun usage(contextTokens: Long, costUsd: Double?) {}
    /** The turn finished (successfully or not); the prompt may be shown again. */
    fun turnComplete() {}
}

/**
 * A sink a [TurnRunner] can drive: told when a turn starts, and told to stop —
 * acknowledge the interrupt at once and drop whatever the agent still says
 * while it unwinds.
 */
interface InterruptibleSink : Sink {
    fun beginTurn()
    fun stop(message: String)
    /** What the user sent, before the turn; the recorder keeps it, renderers ignore it. */
    fun userPrompt(text: String) {}
}

/** ANSI helpers; colours are suppressed when stdout is not a TTY or NO_COLOR is set. */
object Ansi {
    @Volatile var enabled: Boolean = isTerminal() && System.getenv("NO_COLOR").isNullOrEmpty()
        private set

    /** A console that would show the escape sequences instead of acting on them: plain text from here on. */
    fun disable() { enabled = false }

    /**
     * `System.console()` stopped meaning "a terminal" in JDK 22, where it is
     * non-null for redirected output too and `isTerminal()` is the real test.
     * That method does not exist on the 21 this compiles against, hence the
     * reflection; on 21 a non-null console is still the answer.
     */
    private fun isTerminal(): Boolean {
        val console = System.console() ?: return false
        return runCatching { console.javaClass.getMethod("isTerminal").invoke(console) as Boolean }
            .getOrDefault(true)
    }

    private fun wrap(code: String, s: String) = if (enabled) "\u001b[${code}m$s\u001b[0m" else s

    fun dim(s: String) = wrap("2", s)
    fun bold(s: String) = wrap("1", s)
    fun cyan(s: String) = wrap("36", s)
    fun green(s: String) = wrap("32", s)
    fun yellow(s: String) = wrap("33", s)
    fun red(s: String) = wrap("31", s)
    fun magenta(s: String) = wrap("35", s)
}

/**
 * Renders agent events to stdout.
 *
 * On a terminal, assistant text is styled as markdown and committed a line at a
 * time, and the bottom row is a status line: a spinner, what the agent is doing,
 * how long it has been, and the tail of the line still arriving. Committing
 * whole lines is what makes the status row safe — the cursor is always at
 * column 0 between writes, so the row can be erased and redrawn without ever
 * touching text, and nothing depends on knowing where a wrapped line ended.
 *
 * Off a terminal (piped, `NO_COLOR`, one-shot into a file) none of that
 * happens: deltas are written raw and immediately, exactly as they arrive.
 */
class ConsoleSink(
    private val styled: Boolean = Ansi.enabled,
    private val width: () -> Int = { System.getenv("COLUMNS")?.toIntOrNull() ?: 80 },
) : InterruptibleSink {
    private val out = System.out
    private val markdown = MarkdownStream()
    private var midLine = false
    private var lastToolName: String? = null

    // Status row. All of it is guarded by the instance lock, ticker included.
    private var statusShown = false
    private var active = false
    private var suspended = false
    private var label = THINKING
    private var turnStarted = 0L
    private var frame = 0
    // What the turn did, for the line at its end.
    private val changedFiles = LinkedHashSet<String>()
    private var commandsRun = 0
    private var toolCalls = 0

    /**
     * Set by [stop]: the turn was interrupted and whatever the agent still says
     * while it unwinds is no longer wanted. Cleared by the next [beginTurn].
     */
    @Volatile
    private var muted = false

    init {
        if (styled) {
            Thread({
                while (true) {
                    runCatching { Thread.sleep(TICK_MILLIS) }
                    synchronized(this) { if (active && !suspended && !muted) drawStatus() }
                }
            }, "airelay-status").apply { isDaemon = true; start() }
        }
    }

    /** A turn is starting: show that something is happening before the first byte arrives. */
    @Synchronized
    override fun beginTurn() {
        muted = false
        active = true
        label = THINKING
        turnStarted = System.currentTimeMillis()
        changedFiles.clear(); commandsRun = 0; toolCalls = 0
        if (styled) drawStatus()
    }

    /**
     * Ctrl-C. Says so at once and drops everything after it: the keypress is
     * acknowledged in the time it takes to print a line, however long the agent
     * then takes to let go of a socket or a subprocess.
     */
    @Synchronized
    override fun stop(message: String) {
        if (muted) return
        flushText()
        clearStatus()
        active = false
        out.println(Ansi.yellow("⏹ $message"))
        out.flush()
        muted = true
    }

    /** Run [block] with the status row out of the way — for a question on the terminal. */
    fun <T> suspended(block: () -> T): T {
        synchronized(this) {
            flushText()
            clearStatus()
            suspended = true
        }
        try {
            return block()
        } finally {
            synchronized(this) { suspended = false }
        }
    }

    @Synchronized
    override fun assistantText(text: String) {
        if (muted) return
        if (!styled) {
            out.print(text)
            out.flush()
            midLine = !text.endsWith("\n")
            return
        }
        val lines = markdown.feed(text)
        if (lines.isNotEmpty()) {
            clearStatus()
            lines.forEach(out::println)
            out.flush()
        }
        label = WRITING
    }

    @Synchronized
    override fun thinking(text: String) {
        if (muted) return
        line(Ansi.dim("✻ " + text.trim().lines().joinToString(" ").take(200)))
    }

    @Synchronized
    override fun toolUse(name: String, summary: String) {
        if (muted) return
        lastToolName = name
        label = name
        toolCalls++
        if (name.lowercase() in setOf("runcommand", "bash")) commandsRun++
        val title = Ansi.magenta("⏺ ") + Ansi.bold(name)
        line(if (summary.isBlank()) title else "$title ${Ansi.dim(summary)}")
    }

    @Synchronized
    override fun toolResult(text: String, isError: Boolean) {
        if (muted) return
        label = THINKING
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val isRead = lastToolName?.let { name ->
            val lower = name.lowercase()
            lower.contains("readfile") || lower.contains("viewfile")
        } ?: false

        if (isRead && !isError) {
            val linesCount = trimmed.lines().size
            line(Ansi.dim("  ⎿ read $linesCount line${if (linesCount == 1) "" else "s"} (${text.length} chars)"))
            return
        }

        val all = trimmed.lines()
        val shown = all.take(RESULT_PREVIEW_LINES).mapIndexed { i, l -> (if (i == 0) "  ⎿ " else "    ") + l }
        val more = all.size - shown.size
        val body = shown.joinToString("\n") + if (more > 0) "\n    … +$more lines" else ""
        line(if (isError) Ansi.red(body) else Ansi.dim(body))
    }

    @Synchronized
    override fun info(message: String) {
        if (muted) return
        line(Ansi.dim(message))
    }

    /** The diff, coloured, cut after a screenful: the transcript shows the change, `/revert` undoes it. */
    @Synchronized
    override fun fileChanged(path: String, diff: String, revertId: String) {
        if (muted) return
        changedFiles.add(path)
        val body = diff.lines().drop(2).filter { it.isNotEmpty() }
        val shown = body.take(DIFF_PREVIEW_LINES).map { l ->
            when (l.first()) {
                '+' -> Ansi.green("    $l")
                '-' -> Ansi.red("    $l")
                '@' -> Ansi.cyan("    $l")
                else -> Ansi.dim("    $l")
            }
        }
        val more = body.size - shown.size
        line(shown.joinToString("\n") + (if (more > 0) "\n" + Ansi.dim("    … +$more lines") else "") + Ansi.dim("    ($revertId · /revert to undo)"))
    }

    @Synchronized
    override fun usage(contextTokens: Long, costUsd: Double?) {
        if (muted) return
        line(Ansi.dim("context: $contextTokens tokens" + (costUsd?.let { " · $${"%.4f".format(it)}" } ?: "")))
    }

    @Synchronized
    override fun error(message: String) {
        if (muted) return
        line(Ansi.red("✗ $message"))
    }

    @Synchronized
    override fun turnComplete() {
        // Agents report completion themselves and the runner reports it again
        // as a backstop; only the first one ends the turn.
        val wasActive = active
        active = false
        if (muted || !wasActive) { flushText(); return }
        flushText()
        clearStatus()
        val seconds = (System.currentTimeMillis() - turnStarted) / 1000
        // One line on what the turn did; nothing when it was a quick answer.
        val parts = mutableListOf<String>()
        if (styled && turnStarted > 0 && seconds >= SHOW_ELAPSED_AFTER_SECONDS) parts.add(elapsed(seconds))
        if (changedFiles.isNotEmpty()) parts.add("${changedFiles.size} file${if (changedFiles.size == 1) "" else "s"} changed: " + changedFiles.joinToString(", "))
        if (commandsRun > 0) parts.add("$commandsRun command${if (commandsRun == 1) "" else "s"}")
        if (parts.isNotEmpty()) out.println(Ansi.dim("✓ " + parts.joinToString(" · ")))
        out.flush()
    }

    // ---- internals (call with the lock held) ---------------------------------

    /** A status line of our own: text first, so it lands after what was said before it. */
    private fun line(text: String) {
        flushText()
        clearStatus()
        out.println(text)
        out.flush()
    }

    /** Commit assistant text that has no newline yet; an event is about to follow it. */
    private fun flushText() {
        if (!styled) {
            if (midLine) { out.println(); midLine = false }
            return
        }
        val rest = markdown.finish()
        if (rest.isNotEmpty()) {
            clearStatus()
            rest.forEach(out::println)
        }
    }

    private fun clearStatus() {
        if (!statusShown) return
        out.print("\r\u001b[2K")
        statusShown = false
    }

    private fun drawStatus() {
        val spinner = FRAMES[frame++ % FRAMES.size]
        val seconds = (System.currentTimeMillis() - turnStarted) / 1000
        val columns = (width() - 1).coerceAtLeast(20)
        val pending = markdown.pending.trim()
        // While a line is arriving, the row shows it; otherwise what is going on.
        val text = if (pending.isNotEmpty()) {
            fit(pending, columns - 2)
        } else {
            fit("$label… ${elapsed(seconds)} · ctrl-c to interrupt", columns - 2)
        }
        out.print("\r\u001b[2K" + Ansi.cyan(spinner) + " " + Ansi.dim(text))
        out.flush()
        statusShown = true
    }

    /**
     * The end of [text] that fits in [columns], counting a wide character as
     * two. The status row must never wrap: `\r` returns to the start of the
     * last *visual* row, so a wrapped one would leave its first half behind.
     */
    private fun fit(text: String, columns: Int): String {
        var used = 0
        var start = text.length
        while (start > 0) {
            val w = if (text[start - 1].code >= 0x1100) 2 else 1
            if (used + w > columns) break
            used += w
            start--
        }
        // Never begin on the second half of a surrogate pair.
        if (start in 1 until text.length && text[start].isLowSurrogate()) start++
        return text.substring(start)
    }

    private fun elapsed(seconds: Long): String =
        if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

    private companion object {
        const val DIFF_PREVIEW_LINES = 40
        const val THINKING = "Thinking"
        const val WRITING = "Writing"
        const val TICK_MILLIS = 100L
        const val RESULT_PREVIEW_LINES = 8
        const val SHOW_ELAPSED_AFTER_SECONDS = 5
        // Braille spinner on macOS and Linux; the fonts a Windows console ships with have no braille block.
        val FRAMES = if (WindowsConsole.isWindows) listOf("|", "/", "-", "\\") else listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    }
}
