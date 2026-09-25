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

    /** True when the console interprets ANSI escape sequences (colours, cursor moves). */
    @Volatile var ansi: Boolean = true
        private set

    /**
     * Call first thing in `main`, before anything is printed. Two things a
     * Windows console needs telling: that the bytes are UTF-8, and that the
     * escape sequences are to be interpreted rather than shown — the legacy
     * console host prints `←[36m` for a colour unless virtual-terminal
     * processing is switched on. Windows Terminal has it on already.
     */
    fun setup() {
        if (!isWindows) return
        val ok = runCatching {
            org.jline.nativ.Kernel32.SetConsoleOutputCP(UTF8_CODE_PAGE) != 0 &&
                org.jline.nativ.Kernel32.GetConsoleOutputCP() == UTF8_CODE_PAGE
        }.getOrDefault(false)
        if (!ok) {
            utf8 = false
            System.setOut(AsciiFallback(System.out))
            System.setErr(AsciiFallback(System.err))
        }
        ansi = runCatching {
            val handle = org.jline.nativ.Kernel32.GetStdHandle(org.jline.nativ.Kernel32.STD_OUTPUT_HANDLE)
            val mode = IntArray(1)
            if (org.jline.nativ.Kernel32.GetConsoleMode(handle, mode) == 0) return@runCatching false
            if (mode[0] and ENABLE_VIRTUAL_TERMINAL_PROCESSING != 0) return@runCatching true
            org.jline.nativ.Kernel32.SetConsoleMode(handle, mode[0] or ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0
        }.getOrDefault(false)
        if (!ansi) Ansi.disable()
    }

    /**
     * Output is a pipe an IDE reads as UTF-8: force the JVM's stdout/stderr to
     * UTF-8 regardless of the console code page, and never transliterate.
     */
    fun setupPiped() {
        if (!isWindows) return
        runCatching {
            System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
            System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"))
        }
        utf8 = true
        ansi = false
        Ansi.disable()
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
    private const val ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004
}
