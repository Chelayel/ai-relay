package com.chelayel.airelay.cli

import com.chelayel.airelay.agent.PermissionDecision
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.PrintStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * `airelay <backend> --json`: the agent as a subprocess for an IDE.
 *
 * The IntelliJ plugin and the VS Code extension are shells around this same
 * process rather than ports of it, so the three backends behave identically in
 * every front-end and a fix lands in all of them at once. One JSON object per
 * line each way, on stdout and stdin; stderr is free text for the human.
 *
 * Out:  {"type":"ready", ...}  {"type":"text","text"}  {"type":"thinking","text"}
 *       {"type":"tool_use","name","summary"}  {"type":"tool_result","text","isError"}
 *       {"type":"info","text"}  {"type":"error","text"}  {"type":"turn_complete"}
 *       {"type":"permission","id","name","summary"}  {"type":"stopped","text"}
 *       {"type":"exit"}
 * In:   {"type":"send","text"}  {"type":"permission","id","decision":"allow|always|deny"}
 *       {"type":"cancel"}  {"type":"model","name"}  {"type":"exit"}
 */
private val GSON = com.google.gson.Gson()

class JsonSink(private val out: PrintStream = System.out) : InterruptibleSink {

    private val ids = AtomicInteger()
    private val answers = ConcurrentHashMap<Int, CompletableFuture<PermissionDecision>>()
    @Volatile private var muted = false
    @Volatile private var active = false
    private var turnStarted = 0L
    private val changedFiles = LinkedHashSet<String>()
    private var commandsRun = 0
    private var toolCalls = 0

    fun event(type: String, vararg fields: Pair<String, Any?>) {
        val obj = JsonObject().apply {
            addProperty("type", type)
            for ((k, v) in fields) when (v) {
                null -> {}
                is String -> addProperty(k, v)
                is Boolean -> addProperty(k, v)
                is Number -> addProperty(k, v)
                is com.google.gson.JsonElement -> add(k, v)
                // Lists of strings, and lists of maps (the ready event's skills), as real JSON.
                is Collection<*>, is Map<*, *> -> add(k, GSON.toJsonTree(v))
                else -> addProperty(k, v.toString())
            }
        }
        synchronized(out) {
            out.println(obj)
            out.flush()
        }
    }

    override fun beginTurn() {
        muted = false; active = true; turnStarted = System.currentTimeMillis()
        synchronized(changedFiles) { changedFiles.clear(); commandsRun = 0; toolCalls = 0 }
    }

    override fun stop(message: String) {
        if (muted) return
        muted = true
        // Anything still waiting on a permission answer is a turn that is over.
        answers.values.forEach { it.complete(PermissionDecision.DENY) }
        event("stopped", "text" to message)
        turnComplete()
    }

    override fun assistantText(text: String) { if (!muted) event("text", "text" to text) }
    override fun thinking(text: String) { if (!muted) event("thinking", "text" to text) }
    override fun toolUse(name: String, summary: String) {
        if (muted) return
        synchronized(changedFiles) { toolCalls++; if (name.lowercase() in setOf("runcommand", "bash")) commandsRun++ }
        event("tool_use", "name" to name, "summary" to summary)
    }
    override fun fileChanged(path: String, diff: String, revertId: String) {
        if (muted) return
        synchronized(changedFiles) { changedFiles.add(path) }
        event("file_changed", "path" to path, "diff" to diff, "revertId" to revertId)
    }
    override fun usage(contextTokens: Long, costUsd: Double?) {
        if (!muted) event("usage", "contextTokens" to contextTokens, "costUsd" to costUsd)
    }
    override fun toolResult(text: String, isError: Boolean) { if (!muted) event("tool_result", "text" to text, "isError" to isError) }
    override fun info(message: String) { if (!muted) event("info", "text" to message) }
    override fun error(message: String) { if (!muted) event("error", "text" to message) }
    /** Agents report completion and the runner reports it again as a backstop; one event. */
    override fun turnComplete() {
        if (!active) return
        active = false
        val (files, commands, tools) = synchronized(changedFiles) { Triple(changedFiles.toList(), commandsRun, toolCalls) }
        event("turn_complete", "elapsedMs" to (System.currentTimeMillis() - turnStarted), "files" to files, "commands" to commands, "tools" to tools)
    }

    /** Ask the front-end; blocks the agent's thread until it answers or the turn is stopped. */
    fun confirm(name: String, summary: String, detail: String = ""): PermissionDecision {
        if (muted) return PermissionDecision.DENY
        val id = ids.incrementAndGet()
        val future = answers.computeIfAbsent(id) { CompletableFuture() }
        event("permission", "id" to id, "name" to name, "summary" to summary, "detail" to detail.ifBlank { null })
        return try {
            future.get()
        } catch (_: InterruptedException) {
            PermissionDecision.DENY
        } finally {
            answers.remove(id)
        }
    }

    fun answer(id: Int, decision: PermissionDecision) {
        // An answer can land before the question is registered when input is
        // piped rather than typed; keep it rather than drop it.
        answers.computeIfAbsent(id) { CompletableFuture() }.complete(decision)
    }
}

/** The command loop: reads stdin, runs turns, until `exit` or end of input. */
class JsonRepl(
    private val agent: Agent,
    private val sink: JsonSink,
    private val turns: TurnRunner,
    private val skills: List<com.chelayel.airelay.agent.Skill> = emptyList(),
    private val sessions: Sessions? = null,
    private val recorder: InterruptibleSink? = null,
    private val personas: List<com.chelayel.airelay.agent.Persona> = emptyList(),
) {

    private val commands = LinkedBlockingQueue<JsonObject>()

    fun run() {
        // Commands are read on their own thread so `cancel` and permission
        // answers arrive while a turn is running on the main one.
        Thread({
            val reader = System.`in`.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val obj = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                if (obj == null) { sink.event("error", "text" to "Not a JSON command: ${line.take(120)}"); continue }
                when (obj.str("type")) {
                    "cancel" -> turns.interrupt()
                    "permission" -> sink.answer(
                        obj.get("id")?.asInt ?: -1,
                        when (obj.str("decision")) {
                            "allow" -> PermissionDecision.ALLOW_ONCE
                            "always" -> PermissionDecision.ALLOW_ALWAYS
                            else -> PermissionDecision.DENY
                        },
                    )
                    else -> commands.put(obj)
                }
            }
            commands.put(JsonObject().apply { addProperty("type", "exit") })
        }, "airelay-commands").apply { isDaemon = true; start() }

        while (true) {
            val command = commands.take()
            when (command.str("type")) {
                "exit" -> break
                "send" -> {
                    val text = command.str("text").orEmpty()
                    if (text.isBlank()) { sink.event("error", "text" to "Empty message."); continue }
                    // `skills`: names from the ready event; their instructions go in front of the message.
                    val names = command.getAsJsonArray("skills")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } ?: emptyList()
                    // `images`: [{name, mimeType, data}] with base64 data, from a paste or a drop.
                    val images = command.getAsJsonArray("images")?.mapNotNull { el ->
                        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        val data = o.str("data")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        Attachment(o.str("name") ?: "image", o.str("mimeType") ?: "image/png", data)
                    } ?: emptyList()
                    turns.run(com.chelayel.airelay.agent.Skills.attach(text, names, skills) { sink.event("error", "text" to "No skill named \"$it\".") }, images)
                }
                "model" -> sink.event("info", "text" to switchModel(command.str("name").orEmpty()))
                "agent" -> {
                    val name = command.str("name").orEmpty()
                    val p = personas.firstOrNull { it.name.equals(name, true) }
                    when {
                        name.isBlank() -> if (agent.usePersona(null)) sink.event("info", "text" to "Persona cleared.") else sink.event("error", "text" to "This backend cannot change persona mid-session.")
                        p == null -> sink.event("error", "text" to "No agent persona named \"$name\".")
                        agent.usePersona(p) -> sink.event("info", "text" to "Now acting as \"${p.name}\".")
                        else -> sink.event("error", "text" to "This backend cannot change persona mid-session.")
                    }
                }
                "sessions" -> sink.event("sessions", "list" to ((command.str("query")?.takeIf { it.isNotBlank() }?.let { sessions?.search(it) } ?: sessions?.list())?.map { e ->
                    mapOf("id" to e.id, "backend" to e.backend, "title" to e.title, "model" to e.model, "updatedAt" to e.updatedAt)
                } ?: emptyList<Any>()))
                "resume" -> {
                    val entry = sessions?.find(command.str("id").orEmpty())
                    if (entry == null) { sink.event("error", "text" to "No such conversation."); continue }
                    // The transcript goes back out as the events it was made of, bracketed so the
                    // front-end can clear first; then the agent takes the conversation over if it can.
                    sink.event("replay_start", "id" to entry.id, "title" to entry.title)
                    for (e in sessions.transcript(entry.id)) { synchronized(System.out) { println(e); System.out.flush() } }
                    val state = sessions.stateFile(entry.id).takeIf { it.isFile }?.let { runCatching { JsonParser.parseString(it.readText()) }.getOrNull() }
                    val live = agent.resume(entry.id, state)
                    sink.event("replay_end", "id" to entry.id, "resumed" to live)
                    sink.event("info", "text" to if (live) "Resumed; the next message continues this conversation." else "Replayed read-only: this backend keeps its conversation elsewhere, so a new message starts fresh.")
                }
                "mode" -> {
                    val mode = PermissionMode.from(command.str("name"), PermissionMode.ACCEPT_EDITS)
                    if (agent.setPermissionMode(mode)) sink.event("info", "text" to "Permission mode: ${mode.id}.")
                    else sink.event("error", "text" to "This agent cannot change its permission mode mid-session.")
                }
                "add_dir" -> {
                    val dir = java.io.File(command.str("path").orEmpty())
                    when {
                        !dir.isDirectory -> sink.event("error", "text" to "Not a directory: ${dir.path}")
                        agent.addDir(dir) -> sink.event("info", "text" to "Added ${dir.canonicalPath} to the workspace.")
                        else -> sink.event("error", "text" to "This agent cannot widen its workspace mid-session.")
                    }
                }
                "revert" -> com.chelayel.airelay.agent.Edits.revert(command.str("id"))
                    .onSuccess { c -> sink.fileChanged(c.path, c.diff, c.id); sink.event("info", "text" to "Reverted ${c.path}.") }
                    .onFailure { e -> sink.event("error", "text" to (e.message ?: "Could not revert.")) }
                else -> sink.event("error", "text" to "Unknown command: ${command.str("type")}")
            }
        }
        turns.close()
        agent.close()
        sink.event("exit")
    }

    private fun switchModel(name: String): String {
        if (name.isBlank()) return "Models: " + agent.models().joinToString(", ").ifBlank { "none offered" } + (agent.currentModel()?.let { " (now $it)" } ?: "")
        return if (agent.useModel(name)) "Now using ${agent.currentModel()}." else "This agent cannot switch models mid-session."
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
