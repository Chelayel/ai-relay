package com.chelayel.airelay.cli

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/**
 * Conversations kept on disk, per workspace, so one can be listed and picked
 * up again: `/history` and `/resume` in the terminal, the history menu in the
 * panels. Under `~/.airelay/sessions/<hash of the primary dir>/`:
 *
 *  - `index.json`      one entry per session: id, backend, title, model, time
 *  - `<id>.jsonl`      the transcript, one sink event per line, for replay
 *  - `<id>.state.json` the backend's own resumable state, when it has one
 *                      (Gemini's history); Claude's lives in its CLI, keyed by
 *                      the same id; Copilot's on Copilot's side, so its
 *                      transcript replays read-only.
 */
class Sessions(primary: File, home: File = File(System.getProperty("user.home") ?: ".")) {

    class Entry(val id: String, val backend: String, val title: String, val model: String?, val updatedAt: Long) {
        fun toJson(): JsonObject = JsonObject().apply {
            addProperty("id", id); addProperty("backend", backend); addProperty("title", title)
            model?.let { addProperty("model", it) }; addProperty("updatedAt", updatedAt)
        }
        companion object {
            fun from(o: JsonObject): Entry? = runCatching {
                Entry(
                    o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null, o.get("backend")?.asString ?: "?", o.get("title")?.asString ?: "",
                    o.get("model")?.takeIf { it.isJsonPrimitive }?.asString, o.get("updatedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                )
            }.getOrNull()
        }
    }

    val dir: File = File(home, ".airelay/sessions/" + hash(primary.absoluteFile.canonicalPath))

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    @Synchronized
    fun list(): List<Entry> = readIndex().getOrDefault(emptyList()).sortedByDescending { it.updatedAt }

    /** The index exists but could not be read just now; not the same as corrupt. */
    private class Unreadable : Exception()

    private fun readIndex(): Result<List<Entry>> {
        val f = File(dir, "index.json")
        if (!f.isFile) return Result.success(emptyList())
        // A file that cannot be read right now (another airelay mid-write on Windows) is not
        // a corrupt one: say so distinctly, so upsert leaves it alone instead of rewriting it.
        val text = runCatching { f.readText() }.getOrElse { return Result.failure(Unreadable()) }
        return runCatching { JsonParser.parseString(text).asJsonArray.mapNotNull { el -> el.takeIf { it.isJsonObject }?.let { Entry.from(it.asJsonObject) } } }
    }

    @Synchronized
    fun upsert(entry: Entry) {
        dir.mkdirs()
        val f = File(dir, "index.json")
        // An index that cannot be parsed (a process killed mid-write, a full disk) is set
        // aside rather than replaced by a one-entry one; the transcripts it named remain.
        var read = readIndex()
        if (read.exceptionOrNull() is Unreadable) { runCatching { Thread.sleep(150) }; read = readIndex() }
        // Still unreadable: skip this write rather than replace the index with one entry.
        // The next turn records it.
        if (read.exceptionOrNull() is Unreadable) return
        val others = read.getOrElse {
            runCatching { f.renameTo(File(dir, "index.json.broken-" + System.currentTimeMillis())) }
            emptyList()
        }.filter { it.id != entry.id }
        val all = (listOf(entry) + others).take(KEEP)
        for (dropped in (listOf(entry) + others).drop(KEEP)) runCatching { transcriptFile(dropped.id).delete(); stateFile(dropped.id).delete() }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(JsonArray().apply { all.forEach { add(it.toJson()) } }.toString())
        runCatching { java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            .onFailure { java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    }

    /** Entries whose title or transcript text contains every word of [query], case-insensitively. */
    fun search(query: String): List<Entry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return list()
        return list().filter { e ->
            val hay = (e.title + "\n" + runCatching { transcriptFile(e.id).readText() }.getOrDefault("")).lowercase()
            words.all { hay.contains(it) }
        }
    }

    fun find(idOrPrefix: String): Entry? =
        list().firstOrNull { it.id == idOrPrefix } ?: list().singleOrNull { it.id.startsWith(idOrPrefix) }

    fun transcriptFile(id: String): File = File(dir, "$id.jsonl")
    fun stateFile(id: String): File = File(dir, "$id.state.json")

    @Synchronized
    fun append(id: String, event: JsonObject) {
        dir.mkdirs()
        transcriptFile(id).appendText(event.toString() + "\n")
    }

    fun transcript(id: String): List<JsonObject> {
        val f = transcriptFile(id)
        if (!f.isFile) return emptyList()
        return f.readLines().mapNotNull { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
    }

    private companion object { const val KEEP = 200 }
}

/**
 * Tees a turn's events into the session transcript and keeps the index
 * current. Wraps whichever sink is rendering the turn; the id comes from the
 * agent after the turn, because Claude only knows its session id once its
 * CLI has answered.
 */
class RecordingSink(
    private val inner: InterruptibleSink,
    private val sessions: Sessions,
    private val agent: Agent,
    private val backend: String,
) : InterruptibleSink {

    private var title: String? = null
    private val pending = mutableListOf<JsonObject>()
    // Streamed text arrives in tiny deltas; one transcript line per delta made a turn
    // thousands of lines. They are joined and written as one when something else happens.
    private val textRun = StringBuilder()

    private fun flushText() {
        if (textRun.isEmpty()) return
        val t = textRun.toString(); textRun.setLength(0)
        ev("text", "text" to t)
    }

    private fun ev(type: String, vararg fields: Pair<String, Any?>) = runCatching { record(type, *fields) }.let { }

    private fun record(type: String, vararg fields: Pair<String, Any?>) {
        val o = JsonObject().apply {
            addProperty("type", type)
            for ((k, v) in fields) when (v) {
                null -> {}
                is String -> addProperty(k, v)
                is Boolean -> addProperty(k, v)
                is Number -> addProperty(k, v)
                else -> addProperty(k, v.toString())
            }
        }
        // The id may not exist yet (first Claude turn): buffer until the turn ends.
        val id = agent.sessionId()
        if (id == null) synchronized(pending) { pending.add(o) } else flush(id, o)
    }

    private fun flush(id: String, last: JsonObject? = null) {
        val buffered = synchronized(pending) { val l = pending.toList(); pending.clear(); l }
        for (b in buffered) sessions.append(id, b)
        last?.let { sessions.append(id, it) }
    }

    private var sessionSeen: String? = null

    override fun userPrompt(text: String) {
        // A resumed conversation keeps its own title: the id changed under us.
        val id = agent.sessionId()
        if (id != sessionSeen) { sessionSeen = id; title = id?.let { sid -> sessions.list().firstOrNull { it.id == sid }?.title?.takeIf { it.isNotBlank() } } }
        if (title == null) title = text.lines().first().take(80)
        ev("user", "text" to text)
        inner.userPrompt(text)
    }

    override fun beginTurn() = inner.beginTurn()
    override fun stop(message: String) { flushText(); ev("stopped", "text" to message); inner.stop(message) }
    override fun assistantText(text: String) { textRun.append(text); inner.assistantText(text) }
    override fun thinking(text: String) { inner.thinking(text) }
    override fun toolUse(name: String, summary: String) { flushText(); ev("tool_use", "name" to name, "summary" to summary); inner.toolUse(name, summary) }
    override fun toolResult(text: String, isError: Boolean) { flushText(); ev("tool_result", "text" to text.take(4000), "isError" to isError); inner.toolResult(text, isError) }
    override fun info(message: String) { flushText(); ev("info", "text" to message); inner.info(message) }
    override fun error(message: String) { flushText(); ev("error", "text" to message); inner.error(message) }
    override fun fileChanged(path: String, diff: String, revertId: String) { flushText(); ev("file_changed", "path" to path, "diff" to diff.take(60_000), "revertId" to revertId); inner.fileChanged(path, diff, revertId) }
    override fun usage(contextTokens: Long, costUsd: Double?) { flushText(); ev("usage", "contextTokens" to contextTokens, "costUsd" to costUsd); inner.usage(contextTokens, costUsd) }

    /** Recording must never take the turn down: a full disk or an unwritable home is the recorder's problem, not the user's. */
    override fun turnComplete() {
        inner.turnComplete()
        runCatching {
            flushText()
            val id = agent.sessionId() ?: return
            flush(id)
            sessions.upsert(Sessions.Entry(id, backend, title ?: "", agent.currentModel(), System.currentTimeMillis()))
            agent.saveState()?.let { sessions.stateFile(id).writeText(it.toString()) }
        }
    }
}
