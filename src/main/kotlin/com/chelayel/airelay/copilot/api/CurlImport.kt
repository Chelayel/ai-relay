package com.chelayel.airelay.copilot.api

/**
 * Parses a `curl` command line — the kind a browser's DevTools produces from
 * *Network → right-click a request → Copy → Copy as cURL* — into the pieces
 * needed to replay it.
 *
 * This is the whole trick behind the Copilot backend. The Copilot web app talks
 * to an undocumented endpoint whose host, path, headers and body shape differ
 * per tenant and change without notice, so AI Relay never hard-codes any of it:
 * the user hands over one real request captured from their own signed-in
 * browser session, and we replay its shape with a different prompt in it.
 *
 * Handles the three flavours DevTools emits:
 *  - **bash** — `'single quoted'`, `$'ansi quoted'`, `\` line continuations;
 *  - **cmd** — `"double quoted"` with `\"` escapes and `^` line continuations;
 *  - **PowerShell** — backtick line continuations.
 */
object CurlImport {

    /** One captured request: everything needed to send it again. */
    data class Captured(
        val url: String,
        val method: String,
        /** Header name → value, in capture order, already filtered for replay. */
        val headers: LinkedHashMap<String, String>,
        /** The raw request body, or null for a bodyless request. */
        val body: String?,
    ) {
        /** The header carrying the session, so setup can report what it found. */
        fun authHeaderName(): String? =
            headers.keys.firstOrNull { it.equals("authorization", true) }
                ?: headers.keys.firstOrNull { it.equals("cookie", true) }
    }

    class ParseException(message: String) : RuntimeException(message)

    /**
     * Headers we never replay. Some are hop-by-hop or recomputed per request
     * (`host`, `content-length`); `accept-encoding` is dropped so the response
     * arrives as plain text rather than gzip we would have to inflate ourselves.
     * HTTP/2 pseudo-headers (`:authority`) aren't valid over HttpURLConnection.
     */
    private val DROPPED = setOf(
        "host", "content-length", "connection", "accept-encoding",
        "transfer-encoding", "upgrade", "keep-alive", "proxy-connection", "expect",
    )

    private val DATA_FLAGS = setOf(
        "-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--data-urlencode",
    )

    /** Flags that take an argument we discard — so the argument isn't read as the URL. */
    private val IGNORED_WITH_VALUE = setOf(
        "-o", "--output", "-u", "--user", "-x", "--proxy", "--max-time", "--connect-timeout",
        "-w", "--write-out", "--retry", "-m", "--resolve", "--cert", "--key", "--cacert",
    )

    fun parse(input: String): Captured {
        val tokens = tokenize(unfold(input))
        if (tokens.isEmpty()) throw ParseException("Nothing to parse — paste the whole `curl ...` command.")

        var i = 0
        // Skip a leading `curl` / `curl.exe` (and any shell noise before it).
        val curlAt = tokens.indexOfFirst { it.equals("curl", true) || it.equals("curl.exe", true) }
        if (curlAt >= 0) i = curlAt + 1

        var url: String? = null
        var method: String? = null
        var body: String? = null
        val headers = LinkedHashMap<String, String>()
        val cookies = mutableListOf<String>()

        fun value(flag: String): String {
            if (i + 1 >= tokens.size) throw ParseException("`$flag` had no value — the paste looks truncated.")
            return tokens[++i]
        }

        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "-H" || t == "--header" -> addHeader(headers, value(t))
                t == "-b" || t == "--cookie" -> cookies.add(value(t))
                t == "-X" || t == "--request" -> method = value(t).uppercase()
                t == "--url" -> url = value(t)
                t in DATA_FLAGS -> body = body?.let { prev -> prev + "&" + value(t) } ?: value(t)
                t == "-A" || t == "--user-agent" -> addHeader(headers, "user-agent: " + value(t))
                t == "-e" || t == "--referer" -> addHeader(headers, "referer: " + value(t))
                t in IGNORED_WITH_VALUE -> value(t)
                t.startsWith("-") -> Unit // valueless switch: --compressed, -s, -k, --location, ...
                url == null -> url = t
                else -> Unit // stray token (e.g. a trailing shell operator)
            }
            i++
        }

        if (cookies.isNotEmpty()) {
            val existing = headers.entries.firstOrNull { it.key.equals("cookie", true) }?.value
            val merged = (listOfNotNull(existing) + cookies).joinToString("; ")
            headers.entries.removeIf { it.key.equals("cookie", true) }
            headers["cookie"] = merged
        }

        val finalUrl = url?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ParseException("No URL found in the pasted command.")
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            throw ParseException("The URL '$finalUrl' does not look like an http(s) address.")
        }

        return Captured(
            url = finalUrl,
            method = method ?: if (body != null) "POST" else "GET",
            headers = headers,
            body = body,
        )
    }

    private fun addHeader(into: LinkedHashMap<String, String>, raw: String) {
        val idx = raw.indexOf(':')
        if (idx <= 0) return
        val name = raw.substring(0, idx).trim()
        val value = raw.substring(idx + 1).trim()
        if (name.isEmpty() || name.startsWith(":")) return // HTTP/2 pseudo-header
        if (name.lowercase() in DROPPED) return
        into[name] = value
    }

    // ---- shell-ish lexing ----------------------------------------------------

    /** Join the line continuations the three shells use into one logical line. */
    private fun unfold(input: String): String = input
        .replace("\r\n", "\n")
        .replace("\\\n", " ")   // bash
        .replace("^\n", " ")    // cmd
        .replace("`\n", " ")    // PowerShell

    /**
     * Splits on unquoted whitespace, honouring `'...'`, `"..."` and `$'...'`.
     * Deliberately forgiving: an unterminated quote yields the rest of the input
     * as one token rather than throwing, so a truncated paste fails with a
     * message about the missing URL instead of a lexer error.
     */
    private fun tokenize(s: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var started = false
        var i = 0

        fun flush() {
            if (started) { tokens.add(cur.toString()); cur.setLength(0); started = false }
        }

        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> { flush(); i++ }

                c == '$' && i + 1 < s.length && s[i + 1] == '\'' -> {
                    started = true
                    i += 2
                    while (i < s.length && s[i] != '\'') {
                        i = if (s[i] == '\\' && i + 1 < s.length) {
                            appendAnsiEscape(s, i + 1, cur)
                        } else {
                            cur.append(s[i]); i + 1
                        }
                    }
                    i++ // closing quote
                }

                c == '\'' -> {
                    started = true
                    i++
                    while (i < s.length && s[i] != '\'') { cur.append(s[i]); i++ }
                    i++
                }

                c == '"' -> {
                    started = true
                    i++
                    while (i < s.length && s[i] != '"') {
                        if (s[i] == '\\' && i + 1 < s.length && s[i + 1] in "\\\"$`") {
                            cur.append(s[i + 1]); i += 2
                        } else {
                            cur.append(s[i]); i++
                        }
                    }
                    i++
                }

                c == '\\' && i + 1 < s.length -> { started = true; cur.append(s[i + 1]); i += 2 }

                else -> { started = true; cur.append(c); i++ }
            }
        }
        flush()
        return tokens
    }

    /** Decode one `$'...'` escape starting at [at] (just past the backslash); returns the new index. */
    private fun appendAnsiEscape(s: String, at: Int, out: StringBuilder): Int {
        val c = s[at]
        return when (c) {
            'n' -> { out.append('\n'); at + 1 }
            't' -> { out.append('\t'); at + 1 }
            'r' -> { out.append('\r'); at + 1 }
            'b' -> { out.append('\b'); at + 1 }
            'f' -> { out.append('\u000C'); at + 1 }
            'x' -> hex(s, at + 1, 2, out) ?: run { out.append(c); at + 1 }
            'u' -> hex(s, at + 1, 4, out) ?: run { out.append(c); at + 1 }
            'U' -> hex(s, at + 1, 8, out) ?: run { out.append(c); at + 1 }
            else -> { out.append(c); at + 1 }
        }
    }

    /** Append up to [len] hex digits at [from] as a code point; null when they aren't hex. */
    private fun hex(s: String, from: Int, len: Int, out: StringBuilder): Int? {
        var n = 0
        while (n < len && from + n < s.length && s[from + n].isHexDigit()) n++
        if (n == 0) return null
        out.appendCodePoint(s.substring(from, from + n).toInt(16))
        return from + n
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
