package com.chelayel.airelay.copilot.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A very small Chrome DevTools Protocol client — enough to attach to a browser
 * tab and watch the requests it makes.
 *
 * This is what lets `airelay copilot capture` get the session automatically:
 * instead of the user copying a request out of DevTools by hand (which a
 * terminal truncates at ~4 KB, and an M365 Copilot request is far larger than
 * that), we watch the real browser make the real request and read it off the
 * wire.
 *
 * CDP is JSON-RPC over a WebSocket, and the JDK has both a JSON-free HTTP client
 * and a WebSocket client built in, so this adds no dependency.
 */
internal class DevTools private constructor(private val socket: WebSocket) : AutoCloseable {

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val handlers = ConcurrentHashMap<String, MutableList<(JsonObject) -> Unit>>()

    /**
     * Events are handed to handlers here rather than on the socket's reader
     * thread. Handlers legitimately want to make CDP calls of their own — asking
     * for a request body or a response body — and a blocking call made on the
     * reader thread could never receive its own reply. One thread keeps events
     * in the order the browser sent them.
     */
    private val events = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "devtools-events").apply { isDaemon = true }
    }

    /** Call a CDP method and wait for its result. */
    fun call(method: String, params: JsonObject = JsonObject(), timeoutSeconds: Long = 20): JsonObject {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future

        val message = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        socket.sendText(message.toString(), true).join()

        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            pending.remove(id)
            throw DevToolsException("DevTools call `$method` failed: ${e.message ?: e.toString()}")
        }
    }

    /** Fire and forget — for methods whose reply we don't need. */
    fun notify(method: String, params: JsonObject = JsonObject()) {
        runCatching { call(method, params, timeoutSeconds = 5) }
    }

    /** Subscribe to a CDP event, e.g. `Network.requestWillBeSent`. */
    fun on(event: String, handler: (JsonObject) -> Unit) {
        handlers.computeIfAbsent(event) { mutableListOf() }.add(handler)
    }

    private fun dispatch(text: String) {
        val message = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return

        message.get("id")?.takeIf { it.isJsonPrimitive }?.asInt?.let { id ->
            val future = pending.remove(id) ?: return
            message.getAsJsonObject("error")?.let { err ->
                val detail = err.get("message")?.asString ?: err.toString()
                future.completeExceptionally(DevToolsException(detail))
                return
            }
            future.complete(message.getAsJsonObject("result") ?: JsonObject())
            return
        }

        val method = message.get("method")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val params = message.getAsJsonObject("params") ?: JsonObject()
        val listeners = handlers[method]?.toList() ?: return
        // Off the reader thread, so a handler may call back into CDP; and a
        // misbehaving handler must not tear down the connection.
        runCatching { events.execute { listeners.forEach { runCatching { it(params) } } } }
    }

    override fun close() {
        runCatching { events.shutdownNow() }
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done") }
        runCatching { socket.abort() }
    }

    /** Wait for queued event handlers to finish, so a capture sees every reply. */
    fun drainEvents(timeoutSeconds: Long) {
        val done = CompletableFuture<Unit>()
        runCatching { events.execute { done.complete(Unit) } }
        runCatching { done.get(timeoutSeconds, TimeUnit.SECONDS) }
    }

    companion object {
        private val HTTP: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // The debugger is on loopback; a corporate proxy must not intercept it.
            .proxy(java.net.ProxySelector.of(null))
            .build()

        /** Open a CDP connection to [webSocketUrl]. */
        fun connect(webSocketUrl: String): DevTools {
            lateinit var client: DevTools
            val listener = object : WebSocket.Listener {
                private val buffer = StringBuilder()

                override fun onOpen(webSocket: WebSocket) {
                    webSocket.request(1)
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    // CDP frames carrying a request body are large and arrive split.
                    buffer.append(data)
                    if (last) {
                        val text = buffer.toString()
                        buffer.setLength(0)
                        client.dispatch(text)
                    }
                    webSocket.request(1)
                    return null
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    client.pending.values.forEach { it.completeExceptionally(error) }
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    val e = DevToolsException("The browser closed the DevTools connection.")
                    client.pending.values.forEach { it.completeExceptionally(e) }
                    return null
                }
            }

            val socket = runCatching {
                HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(webSocketUrl), listener)
                    .get(15, TimeUnit.SECONDS)
            }.getOrElse { throw DevToolsException("Could not attach to the browser: ${it.message}") }

            client = DevTools(socket)
            return client
        }

        /** One page target the debugger is exposing. */
        data class Page(val id: String, val url: String, val webSocketDebuggerUrl: String)

        /** The debuggable pages at `http://127.0.0.1:[port]`, or null if it isn't up yet. */
        fun listPages(port: Int): List<Page>? {
            val body = get(port, "/json/list") ?: return null
            val array = runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull() ?: return null
            return array.mapNotNull { el ->
                val o = el.asJsonObject
                if (o.get("type")?.asString != "page") return@mapNotNull null
                val ws = o.get("webSocketDebuggerUrl")?.asString ?: return@mapNotNull null
                Page(o.get("id")?.asString.orEmpty(), o.get("url")?.asString.orEmpty(), ws)
            }
        }

        /** Wait for the debugger to accept connections; false if it never does. */
        fun awaitReady(port: Int, seconds: Int): Boolean {
            val deadline = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                if (get(port, "/json/version") != null) return true
                Thread.sleep(250)
            }
            return false
        }

        /** Open a new tab at [url] and return it. */
        fun newPage(port: Int, url: String): Page? {
            // Recent Chrome requires PUT for /json/new; older builds only allow GET.
            get(port, "/json/new?url=" + enc(url), method = "PUT")
                ?: get(port, "/json/new?url=" + enc(url))
            // Either way, find it in the list — the response shape has changed over time.
            return listPages(port)?.firstOrNull { it.url.startsWith(url.take(24)) }
                ?: listPages(port)?.lastOrNull()
        }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

        private fun get(port: Int, path: String, method: String = "GET"): String? = runCatching {
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
            val response = HTTP.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() / 100 == 2) response.body() else null
        }.getOrNull()
    }
}

internal class DevToolsException(message: String) : RuntimeException(message)
