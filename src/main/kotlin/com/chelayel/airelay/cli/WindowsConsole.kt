package com.chelayel.airelay.cli

import java.io.OutputStream
import java.io.PrintStream

/**
 * Windows consoles decode our UTF-8 output in whatever code page they were
 * started with — 437 or 1252 for most people — so `✓` came out as `âœ"` and
 * `—` as `â€"` in the setup wizard. The JVM already emits UTF-8 (the launcher
 * sets `stdout.encoding`); what was missing was telling the console to read
 * it as such. `SetConsoleOutputCP(65001)` does that, through the Win32 binding
 * JLine's native library already carries, so no new dependency.
 *
 * When that call is not available or refused (an old console host, a native
 * library that would not load), the output stream is wrapped so every glyph
 * this tool prints is swapped for an ASCII stand-in. Ugly beats unreadable.
 */
object WindowsConsole {

    val isWindows: Boolean = System.getProperty("os.name", "").startsWith("Windows")

    /** True when the console will render UTF-8 as written. */
    @Volatile var utf8: Boolean = true
        private set

    /** Call first thing in `main`, before anything is printed. */
    fun setup() {
        if (!isWindows) return
        val ok = runCatching {
            org.jline.nativ.Kernel32.SetConsoleOutputCP(UTF8_CODE_PAGE) != 0 &&
                org.jline.nativ.Kernel32.GetConsoleOutputCP() == UTF8_CODE_PAGE
        }.getOrDefault(false)
        if (ok) return
        utf8 = false
        System.setOut(AsciiFallback(System.out))
        System.setErr(AsciiFallback(System.err))
    }

    /** The glyphs this tool prints, and what to show when the console cannot. */
    val FALLBACK: Map<Char, String> = mapOf(
        '▍' to "|", '›' to ">", '—' to "-", '–' to "-", '·' to "-", '…' to "...",
        '✓' to "OK", '✗' to "x", '⏺' to "*", '⎿' to " ", '⏹' to "[stop]", '✻' to "~",
        '±' to "+-", '⎇' to "branch", '↑' to "^", '↓' to "v", '→' to "->", '←' to "<-",
        '⠋' to "|", '⠙' to "/", '⠹' to "-", '⠸' to "\\", '⠼' to "|", '⠴' to "/", '⠦' to "-", '⠧' to "\\", '⠇' to "|", '⠏' to "/",
        '◆' to "*", '●' to "o", '▸' to ">", '▾' to "v", '⟲' to "@", '✂' to "%", ' ' to " ",
    )

    fun transliterate(s: String): String {
        if (s.none { it.code > 127 }) return s
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(FALLBACK[ch] ?: if (ch.code > 127 && !ch.isLetterOrDigit()) "?" else ch.toString())
        return sb.toString()
    }

    private class AsciiFallback(out: OutputStream) : PrintStream(out, true, "UTF-8") {
        override fun print(s: String?) = super.print(s?.let(::transliterate))
        override fun println(s: String?) = super.println(s?.let(::transliterate))
        override fun print(c: Char) = super.print(transliterate(c.toString()))
        override fun println(c: Char) = super.println(transliterate(c.toString()))
        override fun print(obj: Any?) = super.print(transliterate(obj.toString()))
        override fun println(obj: Any?) = super.println(transliterate(obj.toString()))
    }

    private const val UTF8_CODE_PAGE = 65001
}
