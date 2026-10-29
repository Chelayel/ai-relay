package com.chelayel.airelay.github

import com.chelayel.airelay.acp.AcpClient
import com.chelayel.airelay.agent.PermissionDecision
import com.chelayel.airelay.cli.Agent
import com.chelayel.airelay.cli.Attachment
import com.chelayel.airelay.cli.ModelInfo
import com.chelayel.airelay.cli.PermissionMode
import com.chelayel.airelay.cli.Quota
import com.chelayel.airelay.cli.Sink
import com.chelayel.airelay.cli.Workspace
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * GitHub Copilot as an agent, through GitHub's own Copilot CLI (`copilot --acp`).
 * The CLI signs in with the user's GitHub account and runs its own tools; this
 * side speaks ACP to it, relays what it does to the sink, and answers its
 * permission questions under the session's mode. Like the Claude backend, no
 * credential passes through here.
 *
 * The model list comes from the CLI each session — what the account can use
 * today, each with its premium-request multiplier — and the allowance left
 * from `gh api /copilot_internal/user`, through the GitHub CLI's own login.
 */
class GitHubCopilotAgent(
    private val workspace: Workspace,
    private var model: String?,
    @Volatile private var mode: PermissionMode,
    private val confirm: (String, String, String) -> PermissionDecision,
    private val executable: String = CopilotCli.detectExecutable(),
) : Agent {

    private val lock = Any()
    @Volatile private var client: AcpClient? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var restartPending = false
    @Volatile private var available: List<ModelInfo> = emptyList()
    @Volatile private var sink: Sink? = null
    @Volatile private var contextUsed = 0L
    @Volatile private var lastQuotaFetch = 0L

    private val thoughts = StringBuilder()
    /** Tool calls in flight: what kind they are, and for an edit the files as they were before. */
    private val calls = java.util.concurrent.ConcurrentHashMap<String, Call>()
    private class Call(val kind: String, val before: Map<File, String?>)

    override fun describe(): String = "GitHub Copilot · CLI (signed in with GitHub)" + (model?.let { " · $it" } ?: "")
    override fun sessionId(): String? = sessionId
    override fun currentModel(): String? = model
    override fun models(): List<String> = modelInfo().map { it.id }
    override fun modelInfo(): List<ModelInfo> = available.ifEmpty { listOfNotNull(model?.let { ModelInfo(it) }) }

    /** Starting the CLI opens the session, and its reply carries the account's models. */
    override fun discoverModels(): List<ModelInfo>? = runCatching { ensureStarted(); available }.getOrNull()

    override fun useModel(name: String): Boolean {
        val id = name.trim().ifBlank { return false }
        model = id
        val c = client; val s = sessionId
        if (c != null && s != null) runCatching {
            c.request("session/set_model", JsonObject().apply { addProperty("sessionId", s); addProperty("modelId", id) }, 30)
        }.onFailure { return false }
        return true
    }

    override fun setPermissionMode(mode: PermissionMode): Boolean { this.mode = mode; return true }

    override fun addDir(dir: File): Boolean {
        if (!workspace.add(dir)) return false
        restartPending = true
        return true
    }

    override fun resume(id: String, state: JsonElement?): Boolean {
        sessionId = id
        restartPending = true
        return true
    }

    override fun idle() { synchronized(lock) { if (sink == null) stop() } }
    override fun cancel() {
        val c = client; val s = sessionId
        if (c != null && s != null) runCatching { c.notify("session/cancel", JsonObject().apply { addProperty("sessionId", s) }) }
    }
    override fun close() = synchronized(lock) { stop() }

    private fun stop() { client?.close(); client = null }

    /** The CLI running with a session open: a new one, or the conversation being resumed. */
    private fun ensureStarted() {
        synchronized(lock) {
            if (restartPending) { stop(); restartPending = false }
            if (client?.isAlive == true && sessionId != null) return
            stop()
            val cmd = buildList {
                add(executable); add("--acp")
                for (dir in workspace.roots.drop(1)) { add("--add-dir"); add(dir.path) }
            }
            val env = mapOf("PATH" to (com.chelayel.airelay.claude.ClaudeCli.extraPathEntries().filter { File(it).isDirectory } +
                System.getenv("PATH").orEmpty()).filter { it.isNotBlank() }.joinToString(File.pathSeparator))
            val c = try {
                AcpClient(cmd, workspace.primary, env, ::onNotification, ::onRequest)
            } catch (e: java.io.IOException) {
                throw IllegalStateException("GitHub Copilot CLI not found (`$executable`). Install it — `brew install copilot-cli` or `npm install -g @github/copilot` — and sign in with `copilot login`.")
            }
            client = c
            try {
                c.request("initialize", JsonObject().apply {
                    addProperty("protocolVersion", 1)
                    add("clientCapabilities", JsonObject().apply {
                        add("fs", JsonObject().apply { addProperty("readTextFile", false); addProperty("writeTextFile", false) })
                        addProperty("terminal", false)
                    })
                    add("clientInfo", JsonObject().apply { addProperty("name", "airelay"); addProperty("version", com.chelayel.airelay.cli.Version.current) })
                }, 60)
                val params = JsonObject().apply {
                    addProperty("cwd", workspace.primary.path)
                    add("mcpServers", JsonArray())
                }
                val resumeId = sessionId
                val result = if (resumeId != null) {
                    params.addProperty("sessionId", resumeId)
                    runCatching { c.request("session/load", params, 120) }.getOrNull()?.also { sessionId = resumeId }
                } else null
                val opened = result ?: c.request("session/new", params.apply { remove("sessionId") }, 120).also {
                    sessionId = it.asJsonObject.str("sessionId")
                }
                readModels(opened)
                model?.let { m -> if (available.isEmpty() || available.any { it.id == m }) useModel(m) }
            } catch (e: AcpClient.AcpError) {
                stop()
                throw IllegalStateException(explain(e))
            }
        }
    }

    private fun explain(e: AcpClient.AcpError): String {
        val text = (e.message ?: "") + " " + (e.data?.toString() ?: "")
        return if (Regex("auth|login|sign.?in|unauthori|token", RegexOption.IGNORE_CASE).containsMatchIn(text))
            "GitHub Copilot is not signed in. Run `copilot login` in a terminal, then start a new conversation."
        else "GitHub Copilot CLI: ${e.message}"
    }

    private fun readModels(result: JsonElement?) {
        val models = result?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("models") ?: return
        models.str("currentModelId")?.let { if (model == null) model = it }
        available = models.getAsJsonArray("availableModels")?.mapNotNull { el ->
            val m = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val meta = m.get("_meta")?.takeIf { it.isJsonObject }?.asJsonObject
            if (meta?.str("copilotEnablement").let { it != null && it != "enabled" }) return@mapNotNull null
            ModelInfo(m.str("modelId") ?: return@mapNotNull null, m.str("name"), m.str("description")?.takeIf { it != m.str("name") }, meta?.str("copilotUsage"))
        } ?: available
    }

    override fun send(prompt: String, sink: Sink, attachments: List<Attachment>) {
        try {
            ensureStarted()
        } catch (e: Exception) {
            sink.error(e.message ?: "Could not start GitHub Copilot."); sink.turnComplete(); return
        }
        val c = client ?: return
        this.sink = sink
        try {
            val blocks = JsonArray().apply {
                for (a in attachments.filter { it.isImage }) add(JsonObject().apply {
                    addProperty("type", "image"); addProperty("mimeType", a.mimeType); addProperty("data", a.dataBase64)
                })
                add(JsonObject().apply { addProperty("type", "text"); addProperty("text", prompt) })
            }
            val result = c.request("session/prompt", JsonObject().apply { addProperty("sessionId", sessionId); add("prompt", blocks) })
            flushThoughts()
            val o = result.takeIf { it.isJsonObject }?.asJsonObject
            when (o?.str("stopReason")) {
                "max_tokens" -> sink.error("GitHub Copilot stopped: the reply reached its length limit.")
                "max_turn_requests" -> sink.error("GitHub Copilot stopped: too many requests in one turn.")
                "refusal" -> sink.error("GitHub Copilot declined to continue.")
            }
            if (contextUsed > 0) sink.usage(contextUsed, null)
        } catch (e: AcpClient.AcpError) {
            flushThoughts()
            sink.error(explain(e))
        } catch (e: Exception) {
            flushThoughts()
            sink.error(e.message ?: "GitHub Copilot failed.")
        } finally {
            this.sink = null
            calls.clear()
            sink.turnComplete()
        }
        // The allowance moves with every premium request; at most once a minute.
        if (System.currentTimeMillis() - lastQuotaFetch > 60_000) Thread({ quota()?.let { sink.quota(it) } }, "copilot-quota").apply { isDaemon = true; start() }
    }

    // ---- what the CLI tells us ---------------------------------------------------

    private fun onNotification(method: String, params: JsonObject) {
        if (method != "session/update") return
        val u = params.getAsJsonObject("update") ?: return
        val s = sink
        when (u.str("sessionUpdate")) {
            "agent_message_chunk" -> { flushThoughts(); u.getAsJsonObject("content")?.str("text")?.let { s?.assistantText(it) } }
            // Streamed a few words at a time; one thinking block per run of them, not one per chunk.
            "agent_thought_chunk" -> u.getAsJsonObject("content")?.str("text")?.let { synchronized(thoughts) { thoughts.append(it) } }
            "tool_call" -> { flushThoughts(); s?.let { toolCall(u, it) } }
            "tool_call_update" -> s?.let { toolCallUpdate(u, it) }
            "plan" -> {
                flushThoughts()
                val entries = u.getAsJsonArray("entries")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: return
                val done = entries.count { it.str("status") == "completed" }
                s?.toolUse("Update plan", "$done of ${entries.size} done")
                s?.toolResult(entries.joinToString("\n") { e ->
                    (when (e.str("status")) { "completed" -> "☑ "; "in_progress" -> "▸ "; else -> "☐ " }) + e.str("content").orEmpty()
                }, false)
            }
            "usage_update" -> contextUsed = u.get("used")?.takeIf { it.isJsonPrimitive }?.asLong ?: contextUsed
            "current_model_update", "config_option_update" -> {
                u.getAsJsonArray("configOptions")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                    ?.firstOrNull { it.str("id") == "model" }?.str("currentValue")?.let { model = it }
                u.str("currentModelId")?.let { model = it }
            }
        }
    }

    private fun flushThoughts() {
        val text = synchronized(thoughts) { thoughts.toString().also { thoughts.setLength(0) } }
        if (text.isNotBlank()) sink?.thinking(text.trim())
    }

    private fun toolCall(u: JsonObject, s: Sink) {
        val id = u.str("toolCallId") ?: return
        val kind = u.str("kind") ?: "other"
        val paths = locations(u)
        // An edit is diffed and made revertable like every other agent's: the files as they are now.
        val before = if (kind in setOf("edit", "delete", "move")) paths.associateWith { f -> if (f.isFile) runCatching { f.readText() }.getOrNull() else null } else emptyMap()
        calls[id] = Call(kind, before)
        val input = u.getAsJsonObject("rawInput")
        val summary = input?.str("command") ?: paths.firstOrNull()?.let(::label) ?: input?.str("path")?.let { label(File(it)) } ?: input?.str("pattern") ?: input?.str("url") ?: u.str("title").orEmpty()
        s.toolUse(TOOL_NAMES[kind] ?: (u.str("title") ?: "Tool"), summary.lineSequence().first().take(160))
    }

    private fun toolCallUpdate(u: JsonObject, s: Sink) {
        val id = u.str("toolCallId") ?: return
        val status = u.str("status") ?: return
        if (status != "completed" && status != "failed") return
        val call = calls.remove(id)
        s.toolResult(outputOf(u), status == "failed")
        if (status != "completed" || call == null) return
        for ((f, before) in call.before) {
            val after = if (f.isFile) runCatching { f.readText() }.getOrNull() else null
            // A deleted file has nothing to diff against, and reverting it is the CLI's business.
            if (after == null || after == before) continue
            val c = com.chelayel.airelay.agent.Edits.record(f, label(f), before, after)
            s.fileChanged(c.path, c.diff, c.id)
        }
    }

    private fun outputOf(u: JsonObject): String {
        val raw = u.get("rawOutput")
        raw?.takeIf { it.isJsonObject }?.asJsonObject?.str("content")?.let { return it.trim() }
        raw?.takeIf { it.isJsonPrimitive }?.asString?.let { return it.trim() }
        val parts = u.getAsJsonArray("content")?.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            o.getAsJsonObject("content")?.str("text") ?: o.str("path")?.let { "edited $it" }
        }.orEmpty()
        return parts.joinToString("\n").trim()
    }

    private fun locations(u: JsonObject): List<File> =
        u.getAsJsonArray("locations")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.str("path")?.let(::File) }.orEmpty()

    private fun label(f: File): String =
        workspace.roots.firstOrNull { f.path.startsWith(it.path) }?.let { f.relativeTo(it).path } ?: f.path

    /** The CLI asks before a write or a command; the answer follows the session's mode. */
    private fun onRequest(method: String, params: JsonObject): JsonElement? {
        if (method != "session/request_permission") return null
        val options = params.getAsJsonArray("options")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()
        val tool = params.getAsJsonObject("toolCall") ?: JsonObject()
        val kind = tool.str("kind") ?: calls[tool.str("toolCallId")]?.kind ?: "other"
        val harmless = kind in setOf("read", "search", "fetch", "think")
        val decision = when {
            mode == PermissionMode.BYPASS || harmless -> PermissionDecision.ALLOW_ONCE
            mode == PermissionMode.READ_ONLY -> PermissionDecision.DENY
            mode == PermissionMode.ACCEPT_EDITS && kind == "edit" -> PermissionDecision.ALLOW_ONCE
            else -> {
                val input = tool.getAsJsonObject("rawInput")
                flushThoughts()
                confirm(TOOL_NAMES[kind] ?: "Copilot tool", input?.str("command") ?: tool.str("title").orEmpty(), input?.str("command")?.let { "" } ?: "")
            }
        }
        val wanted = when (decision) {
            PermissionDecision.ALLOW_ALWAYS -> listOf("allow_always", "allow_once")
            PermissionDecision.ALLOW_ONCE -> listOf("allow_once", "allow_always")
            PermissionDecision.DENY -> listOf("reject_once", "reject_always")
        }
        val chosen = wanted.firstNotNullOfOrNull { k -> options.firstOrNull { it.str("kind") == k } }
        return JsonObject().apply {
            add("outcome", JsonObject().apply {
                if (chosen == null) addProperty("outcome", "cancelled")
                else { addProperty("outcome", "selected"); addProperty("optionId", chosen.str("optionId")) }
            })
        }
    }

    // ---- the account's allowance ---------------------------------------------------

    /**
     * Premium requests and chat left this month, from GitHub through the `gh` CLI's
     * own login. An internal endpoint, read defensively: anything unexpected is no
     * quota, never an error.
     */
    override fun quota(): Quota? {
        lastQuotaFetch = System.currentTimeMillis()
        val text = runCatching {
            val p = ProcessBuilder("gh", "api", "/copilot_internal/user").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = java.util.concurrent.CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readText() }
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            if (p.exitValue() != 0) return null
            out.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrNull() ?: return null
        return parseQuota(text)
    }

    companion object {
        /** ACP tool kinds, named as the panels' tool rows already name them (and pick their icons by). */
        private val TOOL_NAMES = mapOf(
            "read" to "Read", "edit" to "Edit", "delete" to "Delete", "move" to "Move", "search" to "Search",
            "execute" to "Run", "fetch" to "Fetch", "think" to "Think",
        )

        fun parseQuota(json: String): Quota? = runCatching {
            val o = JsonParser.parseString(json).asJsonObject
            val snapshots = o.getAsJsonObject("quota_snapshots") ?: return null
            val resets = o.str("quota_reset_date_utc")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            val windows = listOf("premium_interactions" to "premium", "chat" to "chat").mapNotNull { (key, label) ->
                val s = snapshots.getAsJsonObject(key) ?: return@mapNotNull null
                val unlimited = s.get("unlimited")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                val limit = s.get("entitlement")?.takeIf { it.isJsonPrimitive }?.asDouble
                val remaining = s.get("remaining")?.takeIf { it.isJsonPrimitive }?.asDouble
                // A plan without premium requests reports an entitlement of 0: nothing to show.
                if (!unlimited && (limit == null || limit <= 0)) return@mapNotNull null
                Quota.Window(label, usedPercent = s.get("percent_remaining")?.takeIf { it.isJsonPrimitive }?.asDouble?.let { 100 - it },
                    used = if (limit != null && remaining != null) limit - remaining else null, limit = limit, resetsAt = resets, unlimited = unlimited)
            }
            if (windows.isEmpty()) null else Quota(windows, o.str("copilot_plan")?.let { "plan: $it" })
        }.getOrNull()

        private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
