package com.chelayel.airelay.gemini.api

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Resolves how a single outbound request authenticates to the active backend.
 * [QueryKey] is appended to the URL (Generative Language API); [Bearer] becomes
 * an `Authorization` header (both Vertex flavours).
 */
sealed interface Auth {
    data class QueryKey(val key: String) : Auth
    data class Bearer(val token: String) : Auth
}

/**
 * Produces the right credential for the current [ConnectionMode]:
 *  - Gemini API  → the user's API key as a query param.
 *  - Vertex      → a Google OAuth access token, obtained via `gcloud`.
 *  - Vertex+Apigee → an OAuth2 client-credentials token from the Apigee gateway.
 *
 * Bearer tokens are cached until shortly before they expire so we don't mint a
 * fresh one on every turn. Ported from Gemini Relay's AuthProvider with the
 * IntelliJ `ExecUtil`/`GeneralCommandLine` calls replaced by plain [ProcessBuilder].
 */
object AuthProvider {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private data class CachedToken(val token: String, val expiresAtMillis: Long)
    private val cache = HashMap<String, CachedToken>()

    @Synchronized
    fun resolve(config: GeminiConfig): Auth = when (config.connectionMode) {
        ConnectionMode.GEMINI_API -> {
            val key = config.geminiApiKey
            require(key.isNotBlank()) { "No Gemini API key set — set AIRELAY_GEMINI_API_KEY." }
            Auth.QueryKey(key)
        }

        ConnectionMode.VERTEX -> Auth.Bearer(gcloudToken(config))

        ConnectionMode.VERTEX_APIGEE -> Auth.Bearer(apigeeToken(config))
    }

    /** Force the next [resolve] to mint a fresh token (used after a 401). */
    @Synchronized
    fun invalidate() = cache.clear()

    // ---- standard Vertex: gcloud access token --------------------------------

    private fun gcloudToken(config: GeminiConfig): String {
        val exe = config.gcloudPath.ifBlank { "gcloud" }
        val cacheKey = "gcloud:$exe"
        cached(cacheKey)?.let { return it }

        val proc = runCatching {
            ProcessBuilder(exe, "auth", "print-access-token")
                .redirectErrorStream(false)
                .start()
        }.getOrElse {
            throw IllegalStateException("Could not run `$exe auth print-access-token`. Is the gcloud CLI installed and on PATH?")
        }

        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IllegalStateException("`$exe auth print-access-token` timed out.")
        }
        if (proc.exitValue() != 0) {
            throw IllegalStateException(
                "`gcloud auth print-access-token` failed: ${stderr.trim().ifBlank { "exit ${proc.exitValue()}" }}. " +
                    "Run `gcloud auth login` (or set gcloud.path).",
            )
        }
        val token = stdout.trim()
        check(token.isNotBlank()) { "gcloud returned an empty access token." }
        // Vertex tokens are ~60 min; refresh a little early.
        store(cacheKey, token, System.currentTimeMillis() + Duration.ofMinutes(50).toMillis())
        return token
    }

    // ---- Vertex via Apigee: OAuth2 client_credentials ------------------------

    private fun apigeeToken(config: GeminiConfig): String {
        val tokenUrl = config.apigeeTokenUrl
        val clientId = config.apigeeClientId
        val clientSecret = config.apigeeClientSecret
        require(tokenUrl.isNotBlank() && clientId.isNotBlank() && clientSecret.isNotBlank()) {
            "Apigee token URL, client id, and client secret must all be set."
        }

        val cacheKey = "apigee:$tokenUrl:$clientId"
        cached(cacheKey)?.let { return it }

        val uri = URI.create(appendQuery(tokenUrl, "grant_type=client_credentials"))
        val basic = Base64.getEncoder()
            .encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8))
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Basic $basic")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() / 100 != 2) {
            throw IllegalStateException(
                "Apigee token request failed (HTTP ${response.statusCode()}). Check the token URL and client credentials.",
            )
        }

        val json = JsonParser.parseString(response.body()).asJsonObject
        val token = json.get("access_token")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IllegalStateException("Apigee response had no access_token.")
        val expiresInSec = json.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong ?: 3600L
        store(cacheKey, token, System.currentTimeMillis() + (expiresInSec - 60).coerceAtLeast(30) * 1000)
        return token
    }

    // ---- token cache helpers -------------------------------------------------

    private fun cached(key: String): String? =
        cache[key]?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }?.token

    private fun store(key: String, token: String, expiresAtMillis: Long) {
        cache[key] = CachedToken(token, expiresAtMillis)
    }

    private fun appendQuery(url: String, query: String): String =
        if (url.contains("?")) "$url&$query" else "$url?$query"
}
