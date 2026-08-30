package com.chelayel.airelay.agent

import com.chelayel.airelay.config.Config
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The agent's window onto anything outside the repo: a search over the open web
 * and a fetch of one page as readable text.
 *
 * This exists because the tools that came before it could only ever describe the
 * code already on disk, so every question about the world — which artifact a
 * framework ships under now, what a release renamed, whether an API survived a
 * major version — was answered from training memory alone. That is fine for
 * "explain this class" and wrong for "migrate this to the version that shipped
 * after your cutoff", which is exactly where the agent produced confident,
 * plausible, wrong coordinates.
 *
 * Both tools are declared through the backend-neutral [ToolSpec], so the Gemini
 * and Copilot loops get them from the same place. Deliberately *not* Gemini's
 * native `googleSearch` grounding: that is a Gemini-only wire feature, it can't
 * be combined with function declarations on every model, and an enterprise
 * gateway is free to strip it. A plain function tool works on every backend and
 * through every gateway.
 */
class Web(private val config: Config) {

    /**
     * Which search API [webSearch] talks to. Fetching a URL needs no provider,
     * and neither does [mavenSearch]; only general web search does.
     *
     * There is deliberately no keyless default. The obvious candidates were
     * tried and none of them work: DuckDuckGo's HTML and Lite endpoints both
     * answer an automated client with a 202 and a challenge page, and Mojeek
     * returns a captcha. A scraper that silently yields nothing is worse than no
     * search at all — the agent reads "no results" as "nothing exists" and goes
     * back to guessing, which is the failure this whole class was added to fix.
     * So an unconfigured search says so, loudly, and points at the setup.
     */
    enum class Provider(val id: String) {
        NONE("none"),
        GOOGLE("google"),
        BRAVE("brave"),
        TAVILY("tavily");

        companion object {
            fun from(id: String?): Provider =
                entries.firstOrNull { it.id.equals(id, true) } ?: NONE
        }
    }

    /** The configured provider, defaulting to Brave once a key is present. */
    val provider: Provider = Provider.from(config.get("search.provider"))
        .let { if (it == Provider.NONE && !config.get("search.api.key").isNullOrBlank()) Provider.BRAVE else it }

    private val apiKey: String get() = config.get("search.api.key").orEmpty()

    /** Google Programmable Search engine id, for [Provider.GOOGLE] only. */
    private val searchCx: String get() = config.get("search.cx").orEmpty()

    /** Master switch — set `web.enabled=false` to take the agent offline entirely. */
    val enabled: Boolean get() = config.getBool("web.enabled", true)

    /** Why [webSearch] is unavailable, or null when it will work. */
    fun searchUnavailable(): String? = when (provider) {
        Provider.NONE ->
            "no search provider is configured. Set search.provider to brave, tavily or google and " +
                "search.api.key to that provider's key (google also needs search.cx)."
        Provider.GOOGLE -> when {
            apiKey.isBlank() -> "search.api.key is not set (Google Custom Search API key)."
            searchCx.isBlank() -> "search.cx is not set (Programmable Search engine id)."
            else -> null
        }
        Provider.BRAVE, Provider.TAVILY ->
            if (apiKey.isBlank()) "search.api.key is not set for the ${provider.id} provider." else null
    }

    fun specs(): List<ToolSpec> {
        if (!enabled) return emptyList()
        return buildList {
            // Only advertised when it can actually run: a tool that answers every
            // call with "not configured" burns rounds and teaches the model to
            // stop trying, which leaves it guessing again.
            if (searchUnavailable() == null) {
                add(
                    ToolSpec(
                        name = "webSearch",
                        description = "Search the web and return ranked results as title, URL and snippet. Use " +
                            "this before answering anything that depends on a library, framework or language " +
                            "version — release notes, migration guides, renamed artifacts, removed APIs — rather " +
                            "than relying on memory, which is older than the current release. Follow up with " +
                            "fetchUrl to read a promising result in full.",
                        parameters = schema {
                            prop("query", "string", "The search query.")
                            prop("count", "integer", "How many results to return (1-10, default 5).")
                            required("query")
                        },
                    ),
                )
            }
            add(
                ToolSpec(
                    name = "fetchUrl",
                    description = "Fetch one web page or raw file and return it as readable text, with HTML " +
                        "markup stripped. Use it to read documentation, a migration guide, a release note or a " +
                        "file on a code host — including a URL you already know, without searching first.",
                    parameters = schema {
                        prop("url", "string", "The absolute http(s) URL to fetch.")
                        prop("maxChars", "integer", "Truncate the text at this many characters (default 40000).")
                        required("url")
                    },
                ),
            )
            add(
                ToolSpec(
                    name = "mavenSearch",
                    description = "Look up real artifacts on Maven Central: which group an artifact belongs to, " +
                        "whether it still exists under that name, and which versions have been published. Use " +
                        "this to confirm every dependency coordinate you write into a build file — never write " +
                        "a groupId, artifactId or version from memory.",
                    parameters = schema {
                        prop("artifactId", "string", "Exact artifactId, e.g. 'spring-boot-starter-web'.")
                        prop("groupId", "string", "Exact groupId, e.g. 'org.springframework.boot'.")
                        prop("query", "string", "Free-text search, when the exact coordinates aren't known.")
                        prop(
                            "versions", "boolean",
                            "List the published versions of the given groupId/artifactId, newest first, " +
                                "instead of one line per matching artifact.",
                        )
                        prop("limit", "integer", "How many rows to return (1-50, default 10).")
                    },
                ),
            )
        }
    }

    fun handles(name: String): Boolean = enabled && name in TOOL_NAMES

    fun summarize(name: String, args: JsonObject): String = when (name) {
        "webSearch" -> args.optStr("query").orEmpty()
        "fetchUrl" -> args.optStr("url").orEmpty()
        "mavenSearch" -> listOfNotNull(args.optStr("groupId"), args.optStr("artifactId"), args.optStr("query"))
            .joinToString(":")
        else -> ""
    }.take(160)

    fun execute(name: String, args: JsonObject): JsonObject = runCatching {
        when (name) {
            "webSearch" -> webSearch(args.reqStr("query"), args.optInt("count") ?: 5)
            "fetchUrl" -> fetchUrl(args.reqStr("url"), args.optInt("maxChars") ?: DEFAULT_MAX_CHARS)
            "mavenSearch" -> mavenSearch(
                groupId = args.optStr("groupId"),
                artifactId = args.optStr("artifactId"),
                query = args.optStr("query"),
                versions = args.optBool("versions"),
                limit = args.optInt("limit") ?: 10,
            )
            else -> error("Unknown web tool: $name")
        }
    }.getOrElse { err(it.message ?: "Web tool '$name' failed.") }

    // ---- Maven Central -------------------------------------------------------

    /**
     * Maven Central's Solr endpoint, which needs no key and no scraping. This is
     * here because "which artifact does this ship under now" is the single fact
     * a model gets confidently wrong on a framework upgrade, and it is cheap to
     * check against the index that actually holds the answer.
     */
    private fun mavenSearch(
        groupId: String?,
        artifactId: String?,
        query: String?,
        versions: Boolean?,
        limit: Int,
    ): JsonObject {
        val rows = limit.coerceIn(1, 50)
        val listVersions = versions == true
        if (listVersions && artifactId.isNullOrBlank()) {
            return err("versions=true needs artifactId (and preferably groupId).")
        }

        val terms = buildList {
            groupId?.let { add("g:${quote(it)}") }
            artifactId?.let { add("a:${quote(it)}") }
            query?.let { add(it) }
        }
        if (terms.isEmpty()) return err("Give artifactId, groupId or query.")

        val q = terms.joinToString(" AND ")
        val core = if (listVersions) "&core=gav" else ""
        val url = "https://search.maven.org/solrsearch/select?q=${enc(q)}$core&rows=$rows&wt=json"
        val json = JsonParser.parseString(httpGet(url, mapOf("Accept" to "application/json"))).asJsonObject
        val response = json.getAsJsonObject("response")
            ?: return err("Maven Central returned an unexpected response.")
        val docs = response.getAsJsonArray("docs").orEmpty()
        if (docs.isEmpty()) {
            return ok("No artifact on Maven Central matches $q. The coordinate is wrong, or it is published elsewhere.")
        }

        val found = response.get("numFound")?.asInt ?: docs.size
        val lines = docs.mapNotNull { el ->
            val o = el.asJsonObject
            val g = o.get("g")?.asString ?: return@mapNotNull null
            val a = o.get("a")?.asString ?: return@mapNotNull null
            val v = (o.get("v") ?: o.get("latestVersion"))?.asString.orEmpty()
            if (listVersions) "$g:$a:$v" else {
                val count = o.get("versionCount")?.asString.orEmpty()
                "$g:$a  latest $v" + if (count.isNotBlank()) "  ($count versions)" else ""
            }
        }
        val header = if (found > docs.size) "$found matches, showing ${docs.size}:\n" else ""
        return ok(header + lines.joinToString("\n"))
    }

    /** Solr treats a dotted groupId as several terms unless it is quoted. */
    private fun quote(term: String): String = "\"" + term.replace("\"", "") + "\""

    // ---- search --------------------------------------------------------------

    private fun webSearch(query: String, count: Int): JsonObject {
        searchUnavailable()?.let {
            return err("Web search is not configured: $it Set it with `airelay web setup`, or use fetchUrl with a known URL.")
        }
        val n = count.coerceIn(1, 10)
        val results = when (provider) {
            Provider.GOOGLE -> googleCse(query, n)
            Provider.BRAVE -> brave(query, n)
            Provider.TAVILY -> tavily(query, n)
            Provider.NONE -> emptyList()
        }
        if (results.isEmpty()) return ok("No results for \"$query\".")
        val rendered = results.joinToString("\n\n") { r ->
            buildString {
                append(r.title).append('\n').append(r.url)
                if (r.snippet.isNotBlank()) append('\n').append(r.snippet)
            }
        }
        return ok(rendered)
    }

    private class Result(val title: String, val url: String, val snippet: String)

    private fun googleCse(query: String, count: Int): List<Result> {
        val url = "https://www.googleapis.com/customsearch/v1?key=${enc(apiKey)}&cx=${enc(searchCx)}" +
            "&num=$count&q=${enc(query)}"
        val json = JsonParser.parseString(httpGet(url, emptyMap())).asJsonObject
        return json.getAsJsonArray("items").orEmpty().mapNotNull { el ->
            val o = el.asJsonObject
            val link = o.get("link")?.asString ?: return@mapNotNull null
            Result(o.get("title")?.asString.orEmpty(), link, o.get("snippet")?.asString.orEmpty())
        }
    }

    private fun brave(query: String, count: Int): List<Result> {
        val url = "https://api.search.brave.com/res/v1/web/search?count=$count&q=${enc(query)}"
        val headers = mapOf("Accept" to "application/json", "X-Subscription-Token" to apiKey)
        val json = JsonParser.parseString(httpGet(url, headers)).asJsonObject
        val results = json.getAsJsonObject("web")?.getAsJsonArray("results").orEmpty()
        return results.mapNotNull { el ->
            val o = el.asJsonObject
            val link = o.get("url")?.asString ?: return@mapNotNull null
            Result(o.get("title")?.asString.orEmpty(), link, stripHtml(o.get("description")?.asString.orEmpty()))
        }
    }

    private fun tavily(query: String, count: Int): List<Result> {
        val body = JsonObject().apply {
            addProperty("query", query)
            addProperty("max_results", count)
        }
        val json = JsonParser.parseString(
            httpPostJson("https://api.tavily.com/search", body.toString(), mapOf("Authorization" to "Bearer $apiKey")),
        ).asJsonObject
        return json.getAsJsonArray("results").orEmpty().mapNotNull { el ->
            val o = el.asJsonObject
            val link = o.get("url")?.asString ?: return@mapNotNull null
            Result(o.get("title")?.asString.orEmpty(), link, o.get("content")?.asString.orEmpty().take(400))
        }
    }

    // ---- fetch ---------------------------------------------------------------

    private fun fetchUrl(url: String, maxChars: Int): JsonObject {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return err("fetchUrl needs an absolute http(s) URL; got \"$url\".")
        }
        val (body, contentType) = httpGetTyped(url)
        val text = if (contentType.contains("html", true)) htmlToText(body) else body
        val cap = maxChars.coerceIn(1_000, HARD_MAX_CHARS)
        val clipped = if (text.length > cap) text.take(cap) + "\n… (truncated at $cap characters)" else text
        return ok(if (clipped.isBlank()) "(the page returned no readable text)" else clipped)
    }

    // ---- HTML → text ---------------------------------------------------------

    /**
     * Good enough to read documentation with: drop the parts that are never
     * prose, turn block boundaries into newlines so structure survives, then
     * strip what's left. Not a parser — a page that defeats it costs one wasted
     * tool round, which is cheaper than a dependency.
     */
    internal fun htmlToText(html: String): String {
        var s = html
        s = DROPPED_ELEMENTS.replace(s, " ")
        s = COMMENT.replace(s, " ")
        s = BLOCK_BOUNDARY.replace(s, "\n")
        s = TAG.replace(s, "")
        s = decodeEntities(s)
        // Collapse runs of blank lines, and trailing spaces on each line.
        return s.lineSequence()
            .map { it.replace(' ', ' ').trimEnd() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun stripHtml(s: String): String = decodeEntities(TAG.replace(s, "")).trim()

    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        var out = s
        for ((entity, ch) in ENTITIES) out = out.replace(entity, ch)
        out = NUMERIC_ENTITY.replace(out) { m ->
            val digits = m.groupValues[1]
            val code = if (digits.startsWith("x", true)) digits.drop(1).toIntOrNull(16) else digits.toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return out
    }

    // ---- HTTP ----------------------------------------------------------------

    private fun httpGet(url: String, headers: Map<String, String>): String = httpGetTyped(url, headers).first

    /**
     * A GET that follows redirects across protocols. `HttpURLConnection` refuses
     * an http→https hop silently (it stops and hands back the 30x body), and
     * documentation sites redirect exactly that way, so the hops are walked here.
     */
    private fun httpGetTyped(url: String, headers: Map<String, String> = emptyMap()): Pair<String, String> {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = open(current, headers)
            conn.requestMethod = "GET"
            val status = conn.responseCode
            if (status in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) error("HTTP $status from $current with no Location header.")
                current = URI(current).resolve(location).toString()
                return@repeat
            }
            return finish(conn, status, current)
        }
        error("Too many redirects fetching $url")
    }

    private fun httpPostJson(url: String, body: String, headers: Map<String, String>): String =
        post(url, body, "application/json", headers)

    private fun post(url: String, body: String, contentType: String, headers: Map<String, String>): String {
        val conn = open(url, headers)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", contentType)
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        return finish(conn, conn.responseCode, url).first
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        // Some documentation hosts serve a stub or a 403 to an unknown client.
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept-Language", "en")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    /** Reads the body and returns it with the response's content type. */
    private fun finish(conn: HttpURLConnection, status: Int, url: String): Pair<String, String> {
        val stream = if (status / 100 == 2) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }.orEmpty()
        val type = conn.contentType.orEmpty()
        conn.disconnect()
        if (status / 100 != 2) {
            error("HTTP $status from $url" + text.take(200).let { if (it.isBlank()) "" else ": $it" })
        }
        return text to type
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    // ---- small helpers -------------------------------------------------------

    private fun ok(result: String): JsonObject = JsonObject().apply { addProperty("result", result) }
    private fun err(message: String): JsonObject = JsonObject().apply { addProperty("error", message) }

    private fun JsonObject.reqStr(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: error("Missing required argument: $key")

    private fun JsonObject.optStr(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.optInt(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    /** Tolerant of a model that sends the flag as a string rather than a boolean. */
    private fun JsonObject.optBool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asBoolean }.getOrNull() ?: it.asString.equals("true", true)
        }

    private class SchemaBuilder {
        val props = JsonObject()
        val req = com.google.gson.JsonArray()
        fun prop(name: String, type: String, description: String) {
            props.add(name, JsonObject().apply {
                addProperty("type", type)
                addProperty("description", description)
            })
        }
        fun required(vararg names: String) = names.forEach { req.add(it) }
        fun build(): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            add("properties", props)
            if (req.size() > 0) add("required", req)
        }
    }

    private fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

    private fun com.google.gson.JsonArray?.orEmpty(): List<com.google.gson.JsonElement> =
        this?.toList() ?: emptyList()

    companion object {
        private const val DEFAULT_MAX_CHARS = 40_000
        private const val HARD_MAX_CHARS = 120_000
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val DROPPED_ELEMENTS = Regex(
            "<(script|style|noscript|svg|head|nav|footer|form)\\b[^>]*>.*?</\\1>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val BLOCK_BOUNDARY = Regex(
            "</?(p|div|br|li|tr|h[1-6]|pre|section|article|table|ul|ol|blockquote)\\b[^>]*>",
            RegexOption.IGNORE_CASE,
        )
        private val TAG = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)
        private val NUMERIC_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")
        private val ENTITIES = listOf(
            "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
            "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—", "&ndash;" to "–",
            "&hellip;" to "…", "&rsquo;" to "'", "&lsquo;" to "'",
            "&ldquo;" to "\"", "&rdquo;" to "\"", "&amp;" to "&",
        )

        private val TOOL_NAMES = setOf("webSearch", "fetchUrl", "mavenSearch")
    }
}
