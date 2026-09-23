package com.chelayel.airelay.idea

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The tool window: the shared chat page in a JCEF view, and an airelay process
 * behind it. Everything visible is the page's; this class launches the process
 * for the chosen backend, forwards its events to the page, and turns the
 * page's commands into protocol lines — plus the two things only an IDE can
 * do: hand over the editor context, and refresh the file tree after a turn.
 *
 * The context is live, not read at send time: a selection listener and the
 * editor-switch listener push the current file and selected lines to the
 * page as a chip, so what will be attached is visible before Send.
 */
class RelayChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val gson = Gson()
    private val browser = JBCefBrowser.createBuilder().setOffScreenRendering(false).build()
    private val bridge = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val pending = ArrayDeque<String>()
    @Volatile private var pageReady = false

    private var process: RelayProcess? = null
    private var backend = RelaySettings.get().state.backend
    private var mode = RelaySettings.get().state.permissionMode
    @Volatile private var busy = false

    init {
        Disposer.register(this, browser)
        Disposer.register(this, bridge)
        bridge.addHandler { json -> handle(runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()); null }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                // The page posts through window.__hostPost; give it that function now.
                run("window.__hostPost = function(s){ ${bridge.inject("s")} };")
                synchronized(pending) { pageReady = true; pending.forEach(::run); pending.clear() }
            }
        }, browser.cefBrowser)
        add(browser.component, BorderLayout.CENTER)
        browser.loadHTML(page())
        trackEditor()
    }

    /** Push the editor context whenever the selection or the active file changes. */
    private fun trackEditor() {
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                if (e.editor.project == project) pushContext()
            }
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = pushContext()
        })
    }

    // ---- page → host -------------------------------------------------------

    private fun handle(msg: JsonObject?) {
        msg ?: return
        when (msg.str("cmd")) {
            "ready" -> ApplicationManager.getApplication().invokeLater { pushState(); pushContext(); ensureProcess() }
            "send" -> send(
                msg.str("text").orEmpty(), msg.get("attach")?.asBoolean ?: false, msg.strings("files"), msg.strings("skills"),
                inline = msg.objects("inline").map { (it.str("name") ?: "file") to it.str("text").orEmpty() },
                images = msg.objects("images").filter { !it.str("data").isNullOrBlank() },
            )
            "revert" -> process?.command(JsonObject().apply { addProperty("type", "revert"); msg.str("id")?.let { addProperty("id", it) } })
            "sessions" -> ensureProcess()?.command(JsonObject().apply { addProperty("type", "sessions"); msg.str("query")?.takeIf { it.isNotBlank() }?.let { addProperty("query", it) } })
            "resume" -> ensureProcess()?.command(JsonObject().apply { addProperty("type", "resume"); addProperty("id", msg.str("id").orEmpty()) })
            "model" -> process?.command(JsonObject().apply { addProperty("type", "model"); addProperty("name", msg.str("name").orEmpty()) })
            "agent" -> ensureProcess()?.command(JsonObject().apply { addProperty("type", "agent"); addProperty("name", msg.str("name").orEmpty()) })
            "addDir" -> process?.command(JsonObject().apply { addProperty("type", "add_dir"); addProperty("path", msg.str("path").orEmpty()) })
            "files" -> ApplicationManager.getApplication().executeOnPooledThread { page("files", matchFiles(msg.str("query").orEmpty())) }
            "open" -> ApplicationManager.getApplication().invokeLater { openInEditor(msg.str("path").orEmpty(), msg.get("line")?.takeIf { it.isJsonPrimitive }?.asInt) }
            "draft" -> com.intellij.ide.util.PropertiesComponent.getInstance(project).setValue(DRAFT_KEY, msg.str("text").orEmpty())
            "applyFence" -> ApplicationManager.getApplication().invokeLater { applyFence(msg.str("path").orEmpty(), msg.str("text").orEmpty()) }
            "attachUris" -> page("attached", msg.strings("uris").mapNotNull { uri ->
                runCatching { java.io.File(java.net.URI(uri)).path }.getOrNull()?.let { displayPath(it) }
            })
            "attach" -> ApplicationManager.getApplication().invokeLater { attachFiles() }
            "mcp" -> ApplicationManager.getApplication().invokeLater { openMcpConfig() }
            "cancel" -> process?.cancel()
            "permission" -> process?.permission(msg.get("id")?.asInt ?: -1, msg.str("decision") ?: "deny")
            "set" -> set(msg.str("key").orEmpty(), msg.str("value").orEmpty())
            "new" -> newConversation()
            "settings" -> ApplicationManager.getApplication().invokeLater {
                // The dialog is modal. The CLI reads its connection (Apigee credentials, the Copilot
                // capture, MCP config…) once at start, so a change to any of it restarts the agent;
                // the user used to have to press New to get the new credentials picked up.
                val before = runCatching { RelayConfigFile.read() }.getOrNull()
                ShowSettingsUtil.getInstance().showSettingsDialog(project, RelaySettingsConfigurable::class.java)
                val s = RelaySettings.get().state
                val after = runCatching { RelayConfigFile.read() }.getOrNull()
                if (s.backend != backend || s.permissionMode != mode || before != after) {
                    if (before != after) page("system", "Connection settings changed; restarting the agent.")
                    restart()
                }
            }
            "open" -> msg.str("url")?.let { BrowserUtil.browse(it) }
        }
    }

    private fun send(
        text: String, attach: Boolean, files: List<String>, skills: List<String>,
        inline: List<Pair<String, String>> = emptyList(), images: List<JsonObject> = emptyList(),
    ) {
        if (busy) return
        val full = ApplicationManager.getApplication().runReadAction<String> {
            val parts = mutableListOf<String>()
            if (attach) editorContext()?.asPrompt()?.let { parts.add(it) }
            if (files.isNotEmpty()) parts.add("Attached from the workspace (read them as needed):\n" + files.joinToString("\n") { "- `$it`" })
            for ((name, body) in inline) parts.add("Dropped file `$name`:\n```\n$body\n```")
            parts.add(text)
            parts.joinToString("\n\n")
        }
        page("user", text)
        busy = true
        page("busy", true)
        ensureProcess()?.send(full, skills, images)
    }

    private fun set(key: String, value: String) {
        val settings = RelaySettings.get().state
        when (key) {
            // Saved at once rather than on the IDE's own schedule: a quit before that
            // schedule fired came back on the old agent.
            "backend" -> { backend = value; settings.backend = value; saveSettings(); restart() }
            // A live change: the conversation is kept, the agent just runs tools differently from here on.
            "mode" -> { mode = value; settings.permissionMode = value; saveSettings(); process?.takeIf { it.isAlive }?.command(JsonObject().apply { addProperty("type", "mode"); addProperty("name", value) }) ?: restart() }
        }
    }

    private fun newConversation() {
        page("clear")
        restart()
    }

    /** `@query` in the composer: workspace files whose path contains every word of the query, best first. */
    private fun matchFiles(query: String): List<String> {
        val base = project.basePath ?: return emptyList()
        val words = query.lowercase().split(Regex("[\\s/]+")).filter { it.isNotBlank() }
        val out = mutableListOf<Pair<Int, String>>()
        val skip = setOf(".git", "node_modules", "build", "out", "target", ".gradle", ".idea", "dist")
        val root = java.io.File(base)
        root.walkTopDown().onEnter { it.name !in skip && !it.name.startsWith(".") || it == root }.forEach { f ->
            if (!f.isFile || out.size > 4000) return@forEach
            val rel = f.relativeTo(root).path
            val lower = rel.lowercase()
            if (words.all { lower.contains(it) }) {
                val name = f.name.lowercase()
                val score = (if (words.isNotEmpty() && name.startsWith(words.last())) 0 else if (words.isNotEmpty() && name.contains(words.last())) 1 else 2) * 1000 + rel.length
                out.add(score to rel)
            }
        }
        return out.sortedBy { it.first }.map { it.second }.take(40)
    }

    /** A path from the transcript (a diff header, a tool row): open it, at [line] when known. */
    private fun openInEditor(path: String, line: Int?) {
        val file = resolveInWorkspace(path) ?: run { page("error", "Not found: $path"); return }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: run { page("error", "Not found: $path"); return }
        if (line != null && line > 0) com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, line - 1, 0).navigate(true)
        else FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun resolveInWorkspace(path: String): java.io.File? {
        val clean = path.substringBefore(':').let { if (it.startsWith("~/")) System.getProperty("user.home") + it.drop(1) else it }
        val f = java.io.File(clean)
        if (f.isAbsolute) return f.takeIf { it.exists() }
        val roots = listOfNotNull(project.basePath) + ProjectRootManager.getInstance(project).contentRoots.map { it.path }
        return roots.map { java.io.File(it, clean) }.firstOrNull { it.exists() }
    }

    /** "Apply to file" on a code fence: write the block to that path, creating it if needed, and open it. */
    private fun applyFence(path: String, text: String) {
        val base = project.basePath ?: return
        val file = resolveInWorkspace(path) ?: java.io.File(base, path)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(if (text.endsWith("\n")) text else text + "\n")
        }.onFailure { page("error", "Could not write $path: ${it.message}"); return }
        refreshFiles()
        openInEditor(file.path, null)
        page("system", "Wrote ${file.relativeTo(java.io.File(base)).path}")
    }

    /** The "+" menu's file picker: paths go to the page as chips, and into the next message. */
    private fun attachFiles() {
        val descriptor = FileChooserDescriptor(true, true, false, false, false, true)
            .withTitle("Attach to the Next Message")
        FileChooser.chooseFiles(descriptor, project, null) { files ->
            page("attached", files.map { displayPath(it.path) })
        }
    }

    /**
     * Open the MCP config the CLI will read, creating an empty one when there is
     * none. Same search order as the CLI: `mcp.config`, `~/.airelay/mcp.json`,
     * `.mcp.json` in the project. Servers apply from the next conversation.
     */
    private fun openMcpConfig() {
        val home = System.getProperty("user.home") ?: "."
        val candidates = buildList {
            RelayConfigFile.read().getProperty("mcp.config")?.takeIf { it.isNotBlank() }?.let { add(java.io.File(it)) }
            add(java.io.File(home, ".airelay/mcp.json"))
            project.basePath?.let { add(java.io.File(it, ".mcp.json")) }
        }
        // The CLI merges every file it finds, project last, so the project one is what to edit.
        val file = candidates.lastOrNull { it.isFile } ?: candidates.first().also {
            it.parentFile?.mkdirs()
            it.writeText(MCP_TEMPLATE)
        }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        if (vf == null) { page("error", "Could not open ${file.path}."); return }
        FileEditorManager.getInstance(project).openFile(vf, true)
        page("system", "MCP servers: ${file.path} — the same \"mcpServers\" shape Claude Desktop uses. Saved servers apply to the next conversation (New).")
    }

    /** What the active editor offers: its file, and the selected lines if any. */
    private class EditorContext(val file: String, val start: Int?, val end: Int?, val selected: String?) {
        fun asPrompt(): String =
            if (selected != null) "Selected in `$file` (lines $start\u2013$end):\n```\n$selected\n```"
            else "Current file: `$file`"
    }

    private fun editorContext(): EditorContext? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val vf = editor.virtualFile ?: return null
        val file = displayPath(vf.path)
        val selected = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }
            ?: return EditorContext(file, null, null, null)
        val start = editor.document.getLineNumber(editor.selectionModel.selectionStart) + 1
        val end = editor.document.getLineNumber(editor.selectionModel.selectionEnd) + 1
        return EditorContext(file, start, end, selected)
    }

    /**
     * The path as the chip shows it: relative to the project or to whichever
     * content root holds the file, else with the home directory collapsed. A
     * project opened through a symlink, or a file from a second module root,
     * used to show its full absolute path here.
     */
    private fun displayPath(path: String): String {
        val roots = buildList {
            project.basePath?.let { add(it) }
            ProjectRootManager.getInstance(project).contentRoots.forEach { add(it.path) }
        }.sortedByDescending { it.length }
        for (root in roots) if (path.startsWith("$root/")) return path.removePrefix("$root/")
        val home = System.getProperty("user.home")
        return if (home != null && path.startsWith("$home/")) "~" + path.removePrefix(home) else path
    }

    private fun pushContext() {
        val c = editorContext()
        page("context", c?.let { mapOf("file" to it.file, "start" to it.start, "end" to it.end) })
    }

    // ---- process -------------------------------------------------------------

    private fun ensureProcess(): RelayProcess? {
        process?.takeIf { it.isAlive }?.let { return it }
        val dir = project.basePath ?: run { page("error", "This project has no folder on disk."); return null }
        // The settings page can change the default agent while this panel is
        // open; what is launched is what the settings say, and the dropdown is
        // told so it never shows one agent while another answers.
        val settings = RelaySettings.get().state
        backend = settings.backend; mode = settings.permissionMode
        lateinit var p: RelayProcess
        p = RelayProcess(
            backend = backend, projectDir = dir, permissionMode = mode,
            onEvent = ::onEvent,
            onStderr = { line -> stderrLines.add(line) },
            // A replaced process exits after its successor started; its exit
            // must not report the successor as "not running".
            onExit = { code -> if (process === p) onExit(code) },
        )
        stderrLines.clear()
        return runCatching { p.start(); p }
            .onSuccess { process = it; pushState(); page("state", mapOf("status" to "starting $backend…")) }
            .onFailure { page("error", "Could not start airelay: ${it.message}") }
            .getOrNull()
    }

    private val stderrLines = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun onEvent(e: JsonObject) {
        when (e.str("type")) {
            "sessions" -> page("sessions", e.get("list")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { x -> x.isJsonObject }?.asJsonObject }?.map { o ->
                mapOf("id" to o.str("id"), "backend" to o.str("backend"), "title" to o.str("title"), "model" to o.str("model"), "updatedAt" to o.get("updatedAt")?.asLong)
            } ?: emptyList<Any>())
            "replay_start" -> page("replayStart", e.str("id"), e.str("title"))
            "replay_end" -> page("replayEnd", e.str("id"), e.get("resumed")?.asBoolean ?: false)
            "user" -> page("user", e.str("text").orEmpty())
            "ready" -> page("state", mapOf(
                "status" to e.str("describe").orEmpty(), "version" to e.str("version"),
                "model" to e.str("model"), "models" to e.strings("models"),
                "agent" to e.str("agent"),
                "agents" to (e.get("agents")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { x -> x.isJsonObject }?.asJsonObject }?.map { o ->
                    mapOf("name" to o.str("name"), "description" to o.str("description"), "source" to o.str("source"))
                } ?: emptyList<Any>()),
                "workspace" to e.strings("workspace"), "mcp" to e.strings("mcp"),
                "skills" to (e.get("skills")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { x -> x.isJsonObject }?.asJsonObject }?.map { o ->
                    mapOf("name" to o.str("name"), "description" to o.str("description"), "source" to o.str("source"))
                } ?: emptyList<Any>()),
            ))
            "text" -> page("assistant", e.str("text").orEmpty())
            "thinking" -> page("thinking", e.str("text").orEmpty())
            "tool_use" -> page("tool", e.str("name").orEmpty(), e.str("summary").orEmpty())
            "tool_result" -> { page("toolResult", e.str("text").orEmpty(), e.get("isError")?.asBoolean ?: false); refreshFiles() }
            "file_changed" -> { page("fileChanged", e.str("path").orEmpty(), e.str("diff").orEmpty(), e.str("revertId").orEmpty()); refreshFiles() }
            "usage" -> page("usage", mapOf("contextTokens" to e.get("contextTokens")?.asLong, "costUsd" to e.get("costUsd")?.takeIf { it.isJsonPrimitive }?.asDouble))
            "info" -> page("system", e.str("text").orEmpty())
            "error" -> page("error", e.str("text").orEmpty())
            "stopped" -> page("system", e.str("text").orEmpty())
            "permission" -> page("permission", mapOf("id" to e.get("id")?.asInt, "name" to e.str("name"), "summary" to e.str("summary"), "detail" to e.str("detail")))
            "turn_complete" -> {
                busy = false; page("busy", false); refreshFiles()
                page("turnDone", mapOf("elapsedMs" to e.get("elapsedMs")?.asLong, "files" to e.strings("files"), "commands" to e.get("commands")?.asInt))
            }
        }
    }

    private fun onExit(code: Int) {
        busy = false
        page("busy", false)
        val said = synchronized(stderrLines) { stderrLines.joinToString("\n").trim() }
        if (code != 0 || said.isNotEmpty()) {
            val hint = if (backend == "gemini" || backend == "copilot") {
                "\n\nConfigure it under Settings → Tools → AI Relay (the ⚙ button above), then start a new conversation."
            } else ""
            page("error", (said.ifEmpty { "airelay exited with status $code." }) + hint)
        }
        page("state", mapOf("status" to "not running"))
    }

    private fun saveSettings() {
        ApplicationManager.getApplication().invokeLater { runCatching { ApplicationManager.getApplication().saveSettings() } }
    }

    private fun restart() {
        process?.stop()
        process = null
        busy = false
        page("busy", false)
        ensureProcess()
    }

    /** The agent writes files behind the IDE's back; make the editor notice. */
    private fun refreshFiles() {
        ApplicationManager.getApplication().invokeLater { VirtualFileManager.getInstance().asyncRefresh(null) }
    }

    private fun pushState() {
        val draft = com.intellij.ide.util.PropertiesComponent.getInstance(project).getValue(DRAFT_KEY).orEmpty()
        page("state", mapOf("backend" to backend, "mode" to mode, "draft" to draft))
    }

    // ---- host → page -------------------------------------------------------

    private fun page(fn: String, vararg args: Any?) {
        val call = "cc.$fn(" + args.joinToString(",") { gson.toJson(it) } + ")"
        synchronized(pending) { if (pageReady) run(call) else pending.add(call) }
    }

    private fun run(js: String) {
        val exec = { browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0) }
        if (SwingUtilities.isEventDispatchThread()) exec() else SwingUtilities.invokeLater(exec)
    }

    private fun page(): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val bg = scheme.defaultBackground
        val fg = UIUtil.getLabelForeground()
        val dark = ColorUtil.isDark(UIUtil.getPanelBackground())
        fun hex(c: Color) = "#" + ColorUtil.toHex(c)
        fun blend(a: Color, b: Color, t: Float) = Color(
            (a.red * (1 - t) + b.red * t).toInt(), (a.green * (1 - t) + b.green * t).toInt(), (a.blue * (1 - t) + b.blue * t).toInt(),
        )
        val theme = "--tone:${if (dark) "dark" else "light"};--bg:${hex(bg)};--fg:${hex(fg)};--dim:${hex(blend(fg, bg, 0.42f))};" +
            "--border:${hex(blend(bg, fg, if (dark) 0.22f else 0.16f))};" +
            "--abubble:${hex(blend(UIUtil.getPanelBackground(), if (dark) Color.WHITE else Color.BLACK, if (dark) 0.14f else 0.09f))};" +
            "--ububble:${hex(blend(bg, Color(0x35, 0x74, 0xF0), if (dark) 0.30f else 0.18f))};" +
            "--code:${hex(blend(bg, fg, if (dark) 0.16f else 0.08f))};" +
            "--font:${cssFamily(UIUtil.getLabelFont().family)};--codefont:${cssFamily(scheme.editorFontName)};--fs:${UIUtil.getLabelFont().size}px;"
        val html = javaClass.getResourceAsStream("/chat/chat.html")?.bufferedReader()?.readText()
            ?: error("chat.html is missing from the plugin")
        return html.replace("{{theme}}", theme).replace("{{cspSource}}", "*").replace("{{nonce}}", "idea")
    }

    /** A font family the page can use: macOS reports its UI font as a dot-prefixed hidden name the renderer cannot resolve. */
    private fun cssFamily(family: String): String =
        if (family.isBlank() || family.startsWith(".")) "system-ui" else "'${family.replace("'", "")}'"

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.objects(key: String): List<JsonObject> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: emptyList()
    private fun JsonObject.strings(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } ?: emptyList()

    override fun dispose() {
        process?.stop()
    }

    companion object {
        private const val MCP_TEMPLATE = "{\n  \"mcpServers\": {\n  }\n}\n"
        private const val DRAFT_KEY = "com.chelayel.airelay.draft"
    }
}
