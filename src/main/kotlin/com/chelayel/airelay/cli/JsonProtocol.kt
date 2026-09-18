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
class JsonSink(private val out: PrintStream = System.out) : InterruptibleSink {

    private val ids = AtomicInteger()
    private val answers = ConcurrentHashMap<Int, CompletableFuture<PermissionDecision>>()
    @Volatile private var muted = false
    @Volatile private var active = false

    fun event(type: String, vararg fields: Pair<String, Any?>) {
        val obj = JsonObject().apply {
            addProperty("type", type)
            for ((k, v) in fields) when (v) {
                null -> {}
                is String -> addProperty(k, v)
                is Boolean -> addProperty(k, v)
                is Number -> addProperty(k, v)
                is Collection<*> -> add(k, com.google.gson.JsonArray().apply { v.forEach { add(it.toString()) } })
                else -> addProperty(k, v.toString())
            }
        }
        synchronized(out) {
            out.println(obj)
            out.flush()
        }
    }

    override fun beginTurn() { muted = false; active = true }

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
    override fun toolUse(name: String, summary: String) { if (!muted) event("tool_use", "name" to name, "summary" to summary) }
    override fun toolResult(text: String, isError: Boolean) { if (!muted) event("tool_result", "text" to text, "isError" to isError) }
    override fun info(message: String) { if (!muted) event("info", "text" to message) }
    override fun error(message: String) { if (!muted) event("error", "text" to message) }
    /** Agents report completion and the runner reports it again as a backstop; one event. */
    override fun turnComplete() {
        if (!active) return
        active = false
        event("turn_complete")
    }

    /** Ask the front-end; blocks the agent's thread until it answers or the turn is stopped. */
    fun confirm(name: String, summary: String): PermissionDecision {
        if (muted) return PermissionDecision.DENY
        val id = ids.incrementAndGet()
        val future = answers.computeIfAbsent(id) { CompletableFuture() }
        event("permission", "id" to id, "name" to name, "summary" to summary)
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
class JsonRepl(private val agent: Agent, private val sink: JsonSink, private val turns: TurnRunner) {

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
                    turns.run(text)
                }
                "model" -> sink.event("info", "text" to switchModel(command.str("name").orEmpty()))
                else -> sink.event("error", "text" to "Unknown command: ${command.str("type")}")
            }
        }
        turns.close()
        agent.close()
        sink.event("exit")
    }

    private fun switchModel(name: String): String {
        val copilot = agent as? com.chelayel.airelay.copilot.agent.CopilotAgent
            ?: return "Only the copilot backend can switch models mid-session."
        if (!copilot.canChooseModel()) return "The captured request has no model field."
        copilot.useModel(name)
        return "Now using $name."
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
