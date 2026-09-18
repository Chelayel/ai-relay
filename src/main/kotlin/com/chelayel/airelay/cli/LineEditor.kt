package com.chelayel.airelay.cli

import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.reader.Widget
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The prompt, once a real terminal is attached.
 *
 * [Stdin] reads a cooked-mode tty, where the kernel owns the line and the
 * program sees nothing until Enter: there is no key to bind a newline to, no
 * history, and a paste with a line break in it is several messages. This puts
 * the terminal in raw mode (JLine) for the length of each read and hands it
 * back in between, so tool subprocesses and the tty's own Ctrl-C keep working
 * during a turn.
 *
 * **Once this exists it is the only reader.** JLine pumps the terminal on its
 * own thread, and a timed peek (how it tells Esc from the start of an arrow
 * key) leaves that thread blocked in a read that outlives the call. Anything
 * reading `System.in` beside it loses the next byte to the pump — the first
 * letter of a permission answer. So [Stdin] routes every question here, and
 * nothing else touches the stream.
 */
class LineEditor private constructor(private val terminal: Terminal) : AutoCloseable {

    /** What a read at the message prompt came back with. */
    sealed interface Input {
        data class Message(val text: String) : Input
        /** Ctrl-C. [discarded] is true when there was text on the line to throw away. */
        data class Interrupted(val discarded: Boolean) : Input
        data object Eof : Input
    }

    /** Called when Ctrl-C lands while a question (not the message prompt) is being read. */
    @Volatile
    var onInterrupt: (() -> Unit)? = null

    val width: Int get() = terminal.width.takeIf { it > 0 } ?: 80

    private val messages: LineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .appName("airelay")
        .history(DefaultHistory())
        .variable(LineReader.HISTORY_FILE, historyFile().toPath())
        .variable(LineReader.HISTORY_SIZE, 1000)
        .variable(LineReader.SECONDARY_PROMPT_PATTERN, "  ")
        // A chat message is prose: `!` is punctuation, not history expansion,
        // and a backslash is a backslash.
        .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
        .option(LineReader.Option.HISTORY_IGNORE_SPACE, false)
        .option(LineReader.Option.INSERT_TAB, true)
        .build()
        .also(::bindNewline)
        .also(::bindInterrupt)

    /** Questions — y/n, a menu number, a path. No history: answers are not messages. */
    private val questions: LineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .appName("airelay")
        .variable(LineReader.HISTORY_SIZE, 0)
        .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
        .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
        .build()
        .also(::bindInterrupt)

    /**
     * Every way a terminal can say "newline, but don't send".
     *
     * There is no one key, because most terminals send the same byte for
     * Ctrl+Enter as for Enter and no program can tell them apart. What is
     * distinguishable:
     *  - Ctrl+J is a line feed where Enter is a carriage return — and Windows
     *    Terminal sends exactly that for Ctrl+Enter.
     *  - Alt/Option+Enter arrives as Esc then Enter.
     *  - Terminals asked for xterm's modifyOtherKeys, or configured for CSI-u,
     *    report Ctrl+Enter and Shift+Enter as their own sequences.
     *  - A line ending in a backslash continues, as in a shell. That one works
     *    everywhere, including macOS Terminal, which offers none of the above
     *    without "Use Option as Meta key".
     */
    private fun bindNewline(reader: LineReader) {
        val newline = Widget { reader.buffer.write("\n"); true }
        val acceptOrContinue = Widget {
            val buffer = reader.buffer
            if (buffer.cursor() == buffer.length() && buffer.length() > 0 && buffer.prevChar() == '\\'.code) {
                buffer.backspace()
                buffer.write("\n")
            } else {
                reader.callWidget(LineReader.ACCEPT_LINE)
            }
            true
        }
        reader.widgets[NEWLINE] = newline
        reader.widgets[ACCEPT_OR_CONTINUE] = acceptOrContinue
        val keys = reader.keyMaps[LineReader.MAIN] ?: return
        for (sequence in NEWLINE_KEYS) keys.bind(Reference(NEWLINE), sequence)
        keys.bind(Reference(ACCEPT_OR_CONTINUE), "\r")
    }

    /**
     * Ctrl-C as a key, not a signal, while a line is being read.
     *
     * Left to the tty, Ctrl-C raises SIGINT and JLine interrupts the reading
     * thread — but the tty also flushes its input queue, and on macOS the read
     * that was blocked on it returns empty. Whichever the reader saw first won:
     * sometimes an interrupt, sometimes end of input, and end of input at the
     * prompt means quit. So for the length of a read the tty is told not to make
     * signals (JLine puts the original settings back when the read ends, so a
     * turn still gets a real SIGINT), and 0x03 is bound like any other key.
     */
    private fun bindInterrupt(reader: LineReader) {
        reader.widgets[LineReader.CALLBACK_INIT] = Widget {
            runCatching {
                val attributes = terminal.attributes
                attributes.setLocalFlag(Attributes.LocalFlag.ISIG, false)
                terminal.attributes = attributes
            }
            true
        }
        reader.widgets[INTERRUPT] = Widget { throw UserInterruptException(reader.buffer.toString()) }
        for (map in listOf(LineReader.MAIN, LineReader.EMACS, LineReader.VIINS, LineReader.VICMD)) {
            reader.keyMaps[map]?.bind(Reference(INTERRUPT), "\u0003")
        }
    }

    /** Read one message. Multi-line; Enter sends. */
    fun readMessage(prompt: String): Input {
        // Ask for Ctrl+Enter / Shift+Enter as distinct sequences. Level 1 leaves
        // every key with a traditional encoding alone — Ctrl-C is still Ctrl-C.
        // Terminals that don't know the request ignore it.
        raw(MODIFY_OTHER_KEYS_ON)
        return try {
            Input.Message(messages.readLine(prompt))
        } catch (e: UserInterruptException) {
            Input.Interrupted(discarded = e.partialLine.isNotBlank())
        } catch (_: EndOfFileException) {
            Input.Eof
        } finally {
            raw(MODIFY_OTHER_KEYS_OFF)
        }
    }

    /** One line of answer, or null once input is closed. Ctrl-C answers nothing. */
    fun ask(prompt: String, mask: Char? = null): String? = try {
        questions.readLine(prompt, mask)
    } catch (_: UserInterruptException) {
        onInterrupt?.invoke()
        ""
    } catch (_: EndOfFileException) {
        null
    }

    /**
     * Drop what was typed before a question was asked. Between reads the tty is
     * back in canonical mode, where a half-typed line is held by the kernel and
     * invisible to a read — raw mode for the length of the sweep surfaces it.
     */
    fun drain() {
        runCatching {
            val previous = terminal.enterRawMode()
            try {
                val reader = terminal.reader()
                while (reader.peek(DRAIN_PEEK_MILLIS) >= 0) reader.read()
            } finally {
                terminal.attributes = previous
            }
        }
    }

    /**
     * Run [block] — a turn — with the tty holding type-ahead untouched.
     *
     * Between reads JLine hands the tty back in cooked mode, where the kernel
     * echoes what is typed into the middle of the streaming reply and rewrites
     * Enter as a line feed. The next read then sees that line feed, which here
     * means "new line, don't send": a message typed ahead and entered during a
     * turn sat in the box with the cursor on line two. So for the turn, echo
     * and translation are off and the bytes wait as typed; signals stay on,
     * because during a turn Ctrl-C *is* a signal.
     */
    fun <T> holdingTypeAhead(block: () -> T): T {
        val previous = runCatching {
            terminal.attributes.also {
                val quiet = Attributes(it)
                quiet.setLocalFlag(Attributes.LocalFlag.ICANON, false)
                quiet.setLocalFlag(Attributes.LocalFlag.ECHO, false)
                quiet.setInputFlag(Attributes.InputFlag.ICRNL, false)
                quiet.setControlChar(Attributes.ControlChar.VMIN, 1)
                quiet.setControlChar(Attributes.ControlChar.VTIME, 0)
                terminal.attributes = quiet
            }
        }.getOrNull()
        try {
            return block()
        } finally {
            previous?.let { runCatching { terminal.attributes = it } }
        }
    }

    /** Ctrl-C while no read is in progress — that is, during a turn. */
    fun handleInterrupt(handler: () -> Unit) {
        terminal.handle(Terminal.Signal.INT) { handler() }
    }

    private fun raw(sequence: String) {
        runCatching { terminal.writer().print(sequence); terminal.writer().flush() }
    }

    override fun close() {
        runCatching { messages.history.save() }
        raw(MODIFY_OTHER_KEYS_OFF)
        runCatching { terminal.close() }
    }

    companion object {
        private const val INTERRUPT = "airelay-interrupt"
        private const val NEWLINE = "airelay-newline"
        private const val ACCEPT_OR_CONTINUE = "airelay-accept-or-continue"
        private const val DRAIN_PEEK_MILLIS = 20L

        private const val ESC = "\u001b"
        private const val MODIFY_OTHER_KEYS_ON = "$ESC[>4;1m"
        private const val MODIFY_OTHER_KEYS_OFF = "$ESC[>4;0m"

        internal val NEWLINE_KEYS = listOf(
            "\n",                 // Ctrl+J; Ctrl+Enter in Windows Terminal
            "$ESC\r", "$ESC\n",   // Alt/Option+Enter
            "$ESC[13;5u",         // Ctrl+Enter, CSI-u (kitty protocol, iTerm2 "report modifiers")
            "$ESC[13;2u",         // Shift+Enter, CSI-u
            "$ESC[13;3u",         // Alt+Enter, CSI-u
            "$ESC[27;5;13~",      // Ctrl+Enter, xterm modifyOtherKeys
            "$ESC[27;2;13~",      // Shift+Enter, xterm modifyOtherKeys
            "$ESC[27;3;13~",      // Alt+Enter, xterm modifyOtherKeys
        )

        /**
         * The editor, or null when there is no terminal to edit on — piped
         * input, a dumb TERM, or a platform JLine has no native code for. Null
         * is not an error: [Stdin] carries on reading lines as it always has.
         */
        fun open(): LineEditor? {
            if (System.getenv("TERM") == "dumb") return null
            if (System.getenv("AIRELAY_PLAIN_INPUT")?.isNotBlank() == true) return null
            // JLine reports a missing provider as a WARNING on stderr before
            // falling back to another; that is its business, not the user's.
            Logger.getLogger("org.jline").level = Level.SEVERE
            return runCatching {
                val terminal = TerminalBuilder.builder()
                    .name("airelay")
                    .system(true)
                    .dumb(false)
                    .build()
                if (terminal.type == Terminal.TYPE_DUMB || terminal.type == Terminal.TYPE_DUMB_COLOR) {
                    terminal.close()
                    null
                } else {
                    LineEditor(terminal)
                }
            }.getOrNull()
        }

        private fun historyFile(): File {
            val dir = File(System.getProperty("user.home"), ".airelay")
            dir.mkdirs()
            return File(dir, "history")
        }
    }
}
