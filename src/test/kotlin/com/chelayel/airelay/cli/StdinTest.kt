package com.chelayel.airelay.cli

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Stdin] exists because the permission prompt was answering itself: input
 * queued before a question was asked came back as the answer to it. These pin
 * the two properties that stops — a read takes exactly one line, and what is
 * still queued can be thrown away.
 */
class StdinTest {

    private val realIn: InputStream = System.`in`

    @AfterTest
    fun restore() {
        System.setIn(realIn)
    }

    private fun feed(text: String) = System.setIn(ByteArrayInputStream(text.toByteArray()))

    @Test
    fun `reads one line at a time and leaves the rest on the stream`() {
        feed("first\nsecond\n")
        assertEquals("first", Stdin.readLine())
        // The point of the whole class: line two is still on the stream, not
        // held in a private buffer where the next prompt would inherit it.
        assertTrue(System.`in`.available() > 0)
        assertEquals("second", Stdin.readLine())
    }

    @Test
    fun `drains queued input so it cannot answer the next question`() {
        feed("do the thing\nand then this\n")
        assertEquals("do the thing", Stdin.readLine())
        Stdin.drain()
        // Nothing left to be mistaken for a y/n/a.
        assertNull(Stdin.readLine())
    }

    @Test
    fun `strips a carriage return and keeps an unterminated last line`() {
        feed("windows\r\ntail")
        assertEquals("windows", Stdin.readLine())
        assertEquals("tail", Stdin.readLine())
        assertNull(Stdin.readLine())
    }

    @Test
    fun `reports EOF once the stream is spent`() {
        feed("")
        assertNull(Stdin.readLine())
        assertTrue(Stdin.closed)
    }
}
