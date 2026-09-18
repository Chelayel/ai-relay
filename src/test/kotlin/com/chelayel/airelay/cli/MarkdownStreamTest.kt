package com.chelayel.airelay.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The renderer sits between the model and the user's eyes, so the property that
 * matters most is that it never loses or mangles text it does not understand.
 * Styling is asserted through a tagging [MarkdownStream.Style] so these read as
 * structure, not escape codes.
 */
class MarkdownStreamTest {

    private object Tags : MarkdownStream.Style {
        override fun heading(s: String, level: Int) = "<h$level>$s</h$level>"
        override fun bold(s: String) = "<b>$s</b>"
        override fun italic(s: String) = "<i>$s</i>"
        override fun code(s: String) = "<c>$s</c>"
        override fun codeBlock(s: String) = "<pre>$s</pre>"
        override fun dim(s: String) = "<d>$s</d>"
    }

    private fun render(vararg chunks: String): List<String> {
        val md = MarkdownStream(Tags)
        return chunks.flatMap { md.feed(it) } + md.finish()
    }

    @Test
    fun `a line is held until it is complete, however it was chunked`() {
        val md = MarkdownStream(Tags)
        assertEquals(emptyList(), md.feed("some **bo"))
        assertEquals("some **bo", md.pending)
        assertEquals(listOf("some <b>bold</b> text"), md.feed("ld** text\nnext"))
        assertEquals("next", md.pending)
        assertEquals(listOf("next"), md.finish())
    }

    @Test
    fun `headings bullets and quotes`() {
        assertEquals(
            listOf("<h2>Plan</h2>", "• first", "  • nested <c>x</c>", "<d>│ </d><i>quoted</i>"),
            render("## Plan\n- first\n  * nested `x`\n> quoted\n"),
        )
    }

    @Test
    fun `nothing inside a fence is styled`() {
        assertEquals(
            listOf("<d>  ┌ kotlin</d>", "<d>  │ </d><pre>val a = **b** // # not a heading</pre>", "<d>  └</d>", "after"),
            render("```kotlin\nval a = **b** // # not a heading\n```\nafter"),
        )
    }

    @Test
    fun `a reply that ends on its closing fence closes it`() {
        assertEquals(listOf("<d>  ┌</d>", "<d>  │ </d><pre>x</pre>", "<d>  └</d>"), render("```\nx\n```"))
    }

    @Test
    fun `a longer fence holds a shorter one`() {
        val out = render("````md\n```\ninner\n```\n````\n")
        assertEquals("<d>  │ </d><pre>```</pre>", out[1])
        assertEquals("<d>  └</d>", out.last())
    }

    @Test
    fun `code spans are literal and identifiers are not emphasis`() {
        assertEquals(listOf("use <c>**kwargs</c> here"), render("use `**kwargs` here"))
        assertEquals(listOf("snake_case_name and 2 * 3 * 4"), render("snake_case_name and 2 * 3 * 4"))
        assertEquals(listOf("an <i>aside</i>."), render("an *aside*."))
    }

    @Test
    fun `unknown constructs pass through as written`() {
        val table = "| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals(table.lines(), render(table))
        assertEquals(listOf("**unclosed and [not a link](nowhere)"), render("**unclosed and [not a link](nowhere)"))
    }

    @Test
    fun `links keep their target visible`() {
        assertEquals(
            listOf("see docs <d>(https://example.com/x)</d>"),
            render("see [docs](https://example.com/x)"),
        )
    }

    @Test
    fun `windows line endings do not leak into the output`() {
        assertEquals(listOf("one", "two"), render("one\r\ntwo\r\n"))
    }
}
