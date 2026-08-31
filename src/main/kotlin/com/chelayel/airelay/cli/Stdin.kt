package com.chelayel.airelay.cli

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * The one reader of standard input.
 *
 * Everything that asks the user something goes through here rather than calling
 * `readlnOrNull()` in each place, because the stdlib reader buffers ahead: it
 * pulls a chunk off the terminal and hands back the first line of it, keeping
 * the rest where nothing else can see it. Those held-back bytes then answer the
 * *next* question — which is how a permission prompt printed and was denied in
 * the same breath, with nothing between "[y]es / [n]o / [a]lways:" and "Denied
 * by user.": a multi-line paste at the `›` prompt had queued its second line,
 * and that line was neither y nor a.
 *
 * So lines are read a byte at a time — `System.in` is already buffered, so this
 * is not a syscall per byte — and nothing is retained between calls. What is
 * still queued stays queued, visibly, so [drain] can throw it away before a
 * question that has to be answered deliberately.
 */
object Stdin {

    // Read through the property rather than caching: tests swap System.in.
    private val stream: java.io.InputStream get() = System.`in`

    /** True once the stream has reported EOF — no later question can be answered. */
    @Volatile
    var closed = false
        private set

    /** One line without its terminator, or null at EOF. */
    @Synchronized
    fun readLine(): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = runCatching { stream.read() }.getOrDefault(-1)
            if (b == -1) {
                closed = true
                // A last line with no newline still counts; a bare EOF does not.
                return if (buffer.size() > 0) decode(buffer) else null
            }
            if (b == '\n'.code) return decode(buffer)
            buffer.write(b)
        }
    }

    /** Discard input already queued: type-ahead, or the tail of a paste. */
    @Synchronized
    fun drain() {
        runCatching {
            while (stream.available() > 0) {
                if (stream.read() == -1) { closed = true; return }
            }
        }
    }

    private fun decode(buffer: ByteArrayOutputStream): String =
        String(buffer.toByteArray(), StandardCharsets.UTF_8).removeSuffix("\r")
}
