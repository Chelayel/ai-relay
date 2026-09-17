package com.chelayel.airelay.cli

/**
 * Markdown, styled a line at a time as it streams.
 *
 * Models answer in markdown, and printed raw that is a wall of `**`, `###` and
 * backticks. This is not a markdown parser and does not try to be: it handles
 * what a coding answer is made of — headings, lists, quotes, fenced code, and
 * bold / italic / code spans — and passes anything else through as written. A
 * construct it does not know is shown as the model typed it, never dropped.
 *
 * It works on whole lines because a span cannot be styled until it closes:
 * `**bo` might be bold or might be two asterisks. [feed] returns the lines a
 * chunk completed and keeps the unfinished tail, which [pending] exposes so the
 * caller can show that text is arriving.
 */
class MarkdownStream(private val style: Style = Style.Ansi) {

    /** The terminal vocabulary, injectable so tests assert on structure rather than escape codes. */
    interface Style {
        fun heading(s: String, level: Int): String
        fun bold(s: String): String
        fun italic(s: String): String
        fun code(s: String): String
        fun codeBlock(s: String): String
        fun dim(s: String): String

        object Ansi : Style {
            private const val E = "\u001b["
            override fun heading(s: String, level: Int) =
                if (level == 1) "${E}1;4m$s${E}0m" else "${E}1m$s${E}0m"
            override fun bold(s: String) = "${E}1m$s${E}22m"
            override fun italic(s: String) = "${E}3m$s${E}23m"
            override fun code(s: String) = "${E}36m$s${E}39m"
            override fun codeBlock(s: String) = "${E}36m$s${E}0m"
            override fun dim(s: String) = "${E}2m$s${E}22m"
        }
    }

    private val tail = StringBuilder()
    private var fence: String? = null

    /** The unfinished last line, unstyled. */
    val pending: String get() = tail.toString()

    /** Add streamed text; returns the lines it completed, styled, without terminators. */
    fun feed(text: String): List<String> {
        tail.append(text)
        val out = mutableListOf<String>()
        while (true) {
            val nl = tail.indexOf("\n")
            if (nl < 0) break
            out.add(line(tail.substring(0, nl).removeSuffix("\r")))
            tail.delete(0, nl + 1)
        }
        return out
    }

    /** The end of the text: whatever is left is a line, terminated or not. */
    fun finish(): List<String> {
        val rest = tail.toString()
        tail.setLength(0)
        // Render before forgetting the fence: a reply that ends on its closing
        // ``` with no newline would otherwise open a second block.
        val out = if (rest.isEmpty()) emptyList() else listOf(line(rest))
        fence = null
        return out
    }

    private fun line(raw: String): String {
        val trimmed = raw.trimStart()
        val open = fence
        if (open != null) {
            // Only a fence at least as long as the opener closes it, so a
            // ```` block can hold ``` lines.
            if (trimmed.startsWith(open) && trimmed.trimEnd().all { it == open[0] }) {
                fence = null
                return style.dim("  └")
            }
            return style.dim("  │ ") + style.codeBlock(raw)
        }
        FENCE.find(trimmed)?.let { m ->
            fence = m.groupValues[1]
            val lang = m.groupValues[2].trim()
            return style.dim(if (lang.isEmpty()) "  ┌" else "  ┌ $lang")
        }

        HEADING.find(raw)?.let { m ->
            return style.heading(inline(m.groupValues[2].trim()), m.groupValues[1].length)
        }
        if (RULE.matches(trimmed)) return style.dim("─".repeat(40))
        QUOTE.find(raw)?.let { m -> return style.dim("│ ") + style.italic(inline(m.groupValues[1])) }
        BULLET.find(raw)?.let { m -> return m.groupValues[1] + "• " + inline(m.groupValues[2]) }
        return inline(raw)
    }

    /** Code spans first: what is inside one is literal, asterisks and all. */
    private fun inline(text: String): String {
        if (text.none { it == '`' || it == '*' || it == '[' }) return text
        val out = StringBuilder()
        var last = 0
        for (m in CODE_SPAN.findAll(text)) {
            out.append(emphasis(text.substring(last, m.range.first)))
            out.append(style.code(m.groupValues[1]))
            last = m.range.last + 1
        }
        out.append(emphasis(text.substring(last)))
        return out.toString()
    }

    private fun emphasis(text: String): String {
        var s = LINK.replace(text) { m ->
            val (label, url) = m.destructured
            if (label == url) label else "$label ${style.dim("($url)")}"
        }
        s = BOLD.replace(s) { style.bold(it.groupValues[1]) }
        s = ITALIC.replace(s) { style.italic(it.groupValues[1]) }
        return s
    }

    private companion object {
        val FENCE = Regex("^(`{3,}|~{3,})([^`]*)$")
        val HEADING = Regex("^(#{1,6})\\s+(.*)$")
        val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$")
        val QUOTE = Regex("^\\s*>\\s?(.*)$")
        val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
        val CODE_SPAN = Regex("`([^`]+)`")
        val LINK = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
        val BOLD = Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*")
        // Asterisks only, and only hugging a word: `a * b * c` is arithmetic and
        // snake_case is an identifier, neither is emphasis.
        val ITALIC = Regex("(?<![*\\w])\\*(?=[^\\s*])([^*]+?)(?<=[^\\s*])\\*(?![*\\w])")
    }
}
