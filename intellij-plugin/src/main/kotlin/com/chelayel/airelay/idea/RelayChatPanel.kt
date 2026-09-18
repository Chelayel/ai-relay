package com.chelayel.airelay.idea

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
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
 * do: hand over the editor selection, and refresh the file tree after a turn.
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
    }

    // ---- page → host -------------------------------------------------------

    private fun handle(msg: JsonObject?) {
        msg ?: return
        when (msg.str("cmd")) {
            "ready" -> ApplicationManager.getApplication().invokeLater { pushState(); ensureProcess() }
            "send" -> send(msg.str("text").orEmpty(), msg.get("includeSelection")?.asBoolean ?: false)
            "cancel" -> process?.cancel()
            "permission" -> process?.permission(msg.get("id")?.asInt ?: -1, msg.str("decision") ?: "deny")
            "set" -> set(msg.str("key").orEmpty(), msg.str("value").orEmpty())
            "new" -> newConversation()
            "settings" -> ApplicationManager.getApplication().invokeLater {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, RelaySettingsConfigurable::class.java)
            }
            "open" -> msg.str("url")?.let { BrowserUtil.browse(it) }
        }
    }

    private fun send(text: String, includeSelection: Boolean) {
        if (busy) return
        val full = ApplicationManager.getApplication().runReadAction<String> {
            val selection = if (includeSelection) selectionContext() else null
            if (selection == null) text else "$selection\n\n$text"
        }
        page("user", text)
        busy = true
        page("busy", true)
        ensureProcess()?.send(full)
    }

    private fun set(key: String, value: String) {
        val settings = RelaySettings.get().state
        when (key) {
            "backend" -> { backend = value; settings.backend = value; restart() }
            "mode" -> { mode = value; settings.permissionMode = value; restart() }
        }
    }

    private fun newConversation() {
        page("clear")
        restart()
    }

    /** The current editor selection as a fenced block the model can place. */
    private fun selectionContext(): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val selected = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() } ?: return null
        val file = editor.virtualFile?.let { project.basePath?.let { base -> it.path.removePrefix("$base/") } ?: it.path }
        val line = editor.document.getLineNumber(editor.selectionModel.selectionStart) + 1
        return "Selected in `${file ?: "the editor"}` (from line $line):\n```\n$selected\n```"
    }

    // ---- process -------------------------------------------------------------

    private fun ensureProcess(): RelayProcess? {
        process?.takeIf { it.isAlive }?.let { return it }
        val dir = project.basePath ?: run { page("error", "This project has no folder on disk."); return null }
        val p = RelayProcess(
            backend = backend, projectDir = dir, permissionMode = mode,
            onEvent = ::onEvent,
            onStderr = { line -> stderrLines.add(line) },
            onExit = ::onExit,
        )
        stderrLines.clear()
        return runCatching { p.start(); p }
            .onSuccess { process = it; page("state", mapOf("status" to "starting $backend…")) }
            .onFailure { page("error", "Could not start airelay: ${it.message}") }
            .getOrNull()
    }

    private val stderrLines = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun onEvent(e: JsonObject) {
        when (e.str("type")) {
            "ready" -> page("state", mapOf("status" to e.str("describe").orEmpty()))
            "text" -> page("assistant", e.str("text").orEmpty())
            "thinking" -> page("thinking", e.str("text").orEmpty())
            "tool_use" -> page("tool", e.str("name").orEmpty(), e.str("summary").orEmpty())
            "tool_result" -> { page("toolResult", e.str("text").orEmpty(), e.get("isError")?.asBoolean ?: false); refreshFiles() }
            "info" -> page("system", e.str("text").orEmpty())
            "error" -> page("error", e.str("text").orEmpty())
            "stopped" -> page("system", e.str("text").orEmpty())
            "permission" -> page("permission", mapOf("id" to e.get("id")?.asInt, "name" to e.str("name"), "summary" to e.str("summary")))
            "turn_complete" -> { busy = false; page("busy", false); refreshFiles() }
        }
    }

    private fun onExit(code: Int) {
        busy = false
        page("busy", false)
        val said = synchronized(stderrLines) { stderrLines.joinToString("\n").trim() }
        if (code != 0 || said.isNotEmpty()) {
            val hint = if (backend == "gemini" || backend == "copilot") {
                "\n\nConfigure it in a terminal with:\n`${RelayProcess.setupCommand(backend)}`\n(or `airelay $backend setup` if the CLI is installed), then start a new conversation."
            } else ""
            page("error", (said.ifEmpty { "airelay exited with status $code." }) + hint)
        }
        page("state", mapOf("status" to "not running"))
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
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        page("state", mapOf(
            "backend" to backend, "mode" to mode,
            "selectionAvailable" to (editor?.selectionModel?.hasSelection() == true),
        ))
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
        val theme = "--bg:${hex(bg)};--fg:${hex(fg)};--dim:${hex(blend(fg, bg, 0.42f))};" +
            "--border:${hex(blend(bg, fg, if (dark) 0.22f else 0.16f))};" +
            "--abubble:${hex(blend(UIUtil.getPanelBackground(), if (dark) Color.WHITE else Color.BLACK, if (dark) 0.14f else 0.09f))};" +
            "--ububble:${hex(blend(bg, Color(0x35, 0x74, 0xF0), if (dark) 0.30f else 0.18f))};" +
            "--code:${hex(blend(bg, fg, if (dark) 0.16f else 0.08f))};" +
            "--font:'${UIUtil.getLabelFont().family}';--codefont:'${scheme.editorFontName}';--fs:${UIUtil.getLabelFont().size}px;"
        val html = javaClass.getResourceAsStream("/chat/chat.html")?.bufferedReader()?.readText()
            ?: error("chat.html is missing from the plugin")
        return html.replace("{{theme}}", theme).replace("{{cspSource}}", "*").replace("{{nonce}}", "idea")
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    override fun dispose() {
        process?.stop()
    }
}
