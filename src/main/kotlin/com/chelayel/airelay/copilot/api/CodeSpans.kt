package com.chelayel.airelay.copilot.api

/**
 * The parts of a message that must survive echo removal character for character.
 *
 * Reading an answer off the rendered page means subtracting our own prompt back
 * out of it, and that subtraction is deliberately blunt: any substantial line we
 * sent is cut wherever it turns up. It has to be, because the page reflows what
 * it echoes and a half-matched instruction leaves shards of our own preamble in
 * the answer.
 *
 * Blunt is wrong for one kind of text. The prompt carries a listing of the
 * project's files, and a tool call carries one of those paths as an argument —
 * so `{"tool": "readFile", "args": {"path": "src/main/kotlin/…/Main.kt"}}` had
 * its path subtracted as an echo, ran with an empty path, and came back "No such
 * file". Every read failed that way, and Copilot concluded, reasonably, that it
 * had no access to the project.
 *
 * So code spans — fenced blocks and balanced JSON objects — are lifted out
 * before the subtraction and put back after. The token that stands in for a span
 * is derived from its content, so the *same* span in the prompt and in the page
 * text masks to the same token: our own echoed example still matches, and is
 * still removed, while a call Copilot actually made has no counterpart in the
 * prompt and survives whole.
 */
internal class CodeSpans {

    private val originals = LinkedHashMap<String, String>()

    /** [text] with every code span replaced by a stand-in token. */
    fun mask(text: String): String {
        val spans = ranges(text)
        if (spans.isEmpty()) return text
        val out = StringBuilder()
        var at = 0
        for (span in spans) {
            out.append(text, at, span.first)
            val content = text.substring(span.first, span.last + 1)
            val token = token(content)
            originals.putIfAbsent(token, content)
            out.append(token)
            at = span.last + 1
        }
        out.append(text, at, text.length)
        return out.toString()
    }

    /** [text] with the surviving tokens turned back into the code they stand for. */
    fun restore(text: String): String {
        var out = text
        for ((token, content) in originals) {
            if (out.contains(token)) out = out.replace(token, content)
        }
        return out
    }

    companion object {

        /**
         * A stand-in for one span. Long enough to be subtracted as an echo in its
         * own right (see `ECHO_MIN_CHARS`), and made of characters no chat page
         * and no echo-removal rule will touch: no whitespace, no angle brackets,
         * no backticks, no braces.
         */
        fun token(content: String): String =
            "⟦code%08x%08x⟧".format(content.hashCode(), content.length)

        /**
         * Where the code spans are: fenced blocks first, then any balanced
         * `{…}` run outside them.
         *
         * Braces matter as much as fences. A page renders ```tool as a code
         * block, and reading that block back gives the JSON with no backticks
         * left around it — so a tool call read off the page usually arrives as a
         * bare object.
         */
        fun ranges(text: String): List<IntRange> {
            val out = mutableListOf<IntRange>()
            var i = 0
            while (i < text.length) {
                if (text.startsWith(FENCE, i)) {
                    val close = text.indexOf(FENCE, i + FENCE.length)
                    if (close < 0) return out          // unterminated: leave the rest alone
                    out.add(i..close + FENCE.length - 1)
                    i = close + FENCE.length
                    continue
                }
                if (text[i] == '{') {
                    val end = matchBrace(text, i)
                    if (end > i) {
                        out.add(i..end)
                        i = end + 1
                        continue
                    }
                }
                i++
            }
            return out
        }

        /** The index of the `}` closing the `{` at [start], or -1 if unbalanced. */
        private fun matchBrace(text: String, start: Int): Int {
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until text.length) {
                val c = text[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return -1
        }

        private const val FENCE = "```"
    }
}
