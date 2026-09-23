package com.chelayel.airelay.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Settings → Tools → AI Relay: every connection in one form, the way Gemini
 * Relay does it. Fields map one-to-one onto keys of the CLI's config file, so
 * saving here configures the command line and the VS Code extension as well.
 * The two *Test* buttons run the CLI's own probes (`gemini models`, `copilot
 * test`, `web`) as subprocesses and show what they print.
 */
class RelaySettingsConfigurable : Configurable {

    // Plugin
    private val backend = ComboBox(arrayOf("claude", "gemini", "copilot"))
    private val mode = ComboBox(arrayOf("ask", "acceptEdits", "bypass"))

    // Gemini
    private val geminiMode = ComboBox(arrayOf("gemini-api", "vertex", "apigee"))
    private val geminiModel = ComboBox(GEMINI_MODELS).apply { isEditable = true }
    private val geminiKey = JBPasswordField()
    private val vertexProject = JBTextField()
    private val vertexLocation = JBTextField()
    private val vertexEndpoint = JBTextField()
    private val gcloudPath = JBTextField()
    private val apigeeTokenUrl = JBTextField()
    private val apigeeClientId = JBTextField()
    private val apigeeClientSecret = JBPasswordField()
    private val apigeeAgents = JBTextField()
    private val geminiTest = JButton("Test Gemini")
    private val geminiResult = result()

    // Copilot
    private val copilotMode = ComboBox(arrayOf("browser", "replay"))
    private val copilotUrl = JBTextField()
    private val copilotHeadless = ComboBox(arrayOf("auto", "true", "false"))
    private val copilotTest = JButton("Test Copilot")
    private val copilotCapture = JButton("Capture a session (replay mode)…")
    private val copilotResult = result()

    // Web
    private val webEnabled = JBCheckBox("Let the agent read URLs, search the web and query Maven Central")
    private val searchProvider = ComboBox(arrayOf("", "brave", "tavily", "google"))
    private val searchKey = JBPasswordField()
    private val searchCx = JBTextField()
    private val webTest = JButton("Test web access")
    private val webResult = result()

    // Advanced
    private val mcpConfig = JBTextField()
    private val javaPath = JBTextField()
    private val extraArgs = JBTextField()

    private var panel: JComponent? = null

    override fun getDisplayName() = "AI Relay"

    override fun createComponent(): JComponent {
        geminiTest.addActionListener { probe(geminiResult, "gemini", "models") }
        copilotTest.addActionListener { probe(copilotResult, "copilot", "test") }
        webTest.addActionListener { probe(webResult, "web") }
        copilotCapture.addActionListener {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                java.awt.datatransfer.StringSelection(RelayProcess.cliCommand("copilot", "setup", "--replay")), null,
            )
            copilotResult.text = "Command copied. Paste it into a terminal: it opens a browser, you send one message, and the request is captured. Browser mode above needs none of this."
        }

        val form = FormBuilder.createFormBuilder()
            .addComponent(section("Plugin"))
            .addLabeledComponent("Default agent:", backend)
            .addLabeledComponent("Tool permissions:", mode)
            .addComponent(hint("ask confirms every tool · acceptEdits applies file changes without asking · bypass runs everything."))

            .addComponent(section("Claude"))
            .addComponent(hint("Nothing to configure: uses the `claude` CLI and whatever it is signed in with."))

            .addComponent(section("Gemini"))
            .addLabeledComponent("Mode:", geminiMode)
            .addLabeledComponent("Model:", geminiModel)
            .addComponent(hint("One id, or several separated by commas — the first is the default, the rest are offered in the panel's model picker (an Apigee gateway publishes its own ids)."))
            .addLabeledComponent("API key:", geminiKey)
            .addComponent(hint("From Google AI Studio — Gemini API mode only."))
            .addLabeledComponent("Project ID:", vertexProject)
            .addLabeledComponent("Location:", vertexLocation)
            .addLabeledComponent("API endpoint host:", vertexEndpoint)
            .addComponent(hint("Vertex / Apigee. Endpoint host is the Apigee gateway (my-gw.example.com); blank for standard Vertex."))
            .addLabeledComponent("gcloud path:", gcloudPath)
            .addComponent(hint("Standard Vertex takes a token from `gcloud auth print-access-token`. Blank = gcloud on PATH."))
            .addLabeledComponent("Apigee token URL:", apigeeTokenUrl)
            .addLabeledComponent("Apigee client ID:", apigeeClientId)
            .addLabeledComponent("Apigee client secret:", apigeeClientSecret)
            .addLabeledComponent("Apigee models:", apigeeAgents)
            .addComponent(hint("Apigee mode: the model ids the gateway publishes, comma-separated."))
            .addComponent(geminiTest)
            .addComponent(geminiResult)

            .addComponent(section("Copilot"))
            .addLabeledComponent("Mode:", copilotMode)
            .addLabeledComponent("Copilot URL:", copilotUrl)
            .addLabeledComponent("Browser window:", copilotHeadless)
            .addComponent(hint("Browser mode drives a real Chrome/Edge tab: sign in there on the first turn, pick the model there. " +
                "This is the mode for Microsoft 365 Copilot. 'auto' shows the window until you have signed in, then hides it."))
            .addComponent(JPanel().apply { add(copilotTest); add(copilotCapture) })
            .addComponent(copilotResult)

            .addComponent(section("Web access"))
            .addComponent(webEnabled)
            .addLabeledComponent("Search provider:", searchProvider)
            .addLabeledComponent("Search API key:", searchKey)
            .addLabeledComponent("Google engine id (cx):", searchCx)
            .addComponent(hint("Reading a URL and Maven Central need no key. Without a provider, webSearch is simply not offered to the model."))
            .addComponent(webTest)
            .addComponent(webResult)

            .addComponent(section("Advanced"))
            .addLabeledComponent("MCP config file:", mcpConfig)
            .addComponent(hint("An `mcpServers` JSON file (Claude Desktop shape). Blank: ~/.airelay/mcp.json, then .mcp.json in the project."))
            .addLabeledComponent("Java (optional):", javaPath)
            .addComponent(hint("A JDK 21+ or its `java`. Blank: the IDE's own runtime."))
            .addLabeledComponent("Extra arguments:", extraArgs)
            .addComponent(hint("Appended to every launch, e.g. --add-dir /path --no-web."))
            .addComponent(hint("Everything above is saved to ${RelayConfigFile.file().path}, readable only by you and shared with the `airelay` command."))
            .addComponentFillVertically(JPanel(), 0)
            .panel
        val scroll = JBScrollPane(form).apply { border = JBUI.Borders.empty() }
        panel = JPanel(BorderLayout()).apply { add(scroll, BorderLayout.CENTER) }
        reset()
        return panel!!
    }

    /** Run one of the CLI's probe subcommands with the values as currently typed (saved first). */
    private fun probe(into: JTextArea, vararg args: String) {
        apply()
        into.text = "Running…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val out = runCatching {
                val p = ProcessBuilder(RelayProcess.cliCommandLine(*args)).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor()
                text.replace(Regex("\u001b\\[[0-9;]*m"), "").trim()
            }.getOrElse { "Could not run the probe: ${it.message}" }
            ApplicationManager.getApplication().invokeLater { into.text = out.ifBlank { "(no output)" } }
        }
    }

    // ---- state ---------------------------------------------------------------

    private fun values(): Map<String, String?> = mapOf(
        "gemini.mode" to geminiMode.item, "gemini.model" to (geminiModel.editor.item?.toString() ?: ""),
        "gemini.api.key" to String(geminiKey.password),
        "vertex.project" to vertexProject.text, "vertex.location" to vertexLocation.text, "vertex.endpoint" to vertexEndpoint.text,
        "gcloud.path" to gcloudPath.text,
        "apigee.token.url" to apigeeTokenUrl.text, "apigee.client.id" to apigeeClientId.text,
        "apigee.client.secret" to String(apigeeClientSecret.password), "apigee.agents" to apigeeAgents.text,
        "copilot.mode" to copilotMode.item, "copilot.url" to copilotUrl.text, "copilot.headless" to copilotHeadless.item,
        "web.enabled" to if (webEnabled.isSelected) "" else "false",
        "search.provider" to searchProvider.item, "search.api.key" to String(searchKey.password), "search.cx" to searchCx.text,
        "mcp.config" to mcpConfig.text,
    )

    override fun isModified(): Boolean {
        val s = RelaySettings.get().state
        if (backend.item != s.backend || mode.item != s.permissionMode || javaPath.text != s.javaPath || extraArgs.text != s.extraArgs) return true
        val file = RelayConfigFile.read()
        return values().any { (k, v) -> (v ?: "").trim() != (file.getProperty(k) ?: defaultFor(k)).trim() }
    }

    override fun apply() {
        val s = RelaySettings.get().state
        s.backend = backend.item as String
        s.permissionMode = mode.item as String
        s.javaPath = javaPath.text.trim()
        s.extraArgs = extraArgs.text.trim()
        // Defaults are not written, so the file holds only what the user chose.
        RelayConfigFile.write(values().mapValues { (k, v) -> if ((v ?: "").trim() == defaultFor(k)) "" else v })
    }

    override fun reset() {
        val s = RelaySettings.get().state
        backend.item = s.backend
        mode.item = s.permissionMode
        javaPath.text = s.javaPath
        extraArgs.text = s.extraArgs
        val f = RelayConfigFile.read()
        fun get(k: String) = f.getProperty(k) ?: defaultFor(k)
        geminiMode.item = get("gemini.mode"); geminiModel.editor.item = get("gemini.model"); geminiKey.text = get("gemini.api.key")
        vertexProject.text = get("vertex.project"); vertexLocation.text = get("vertex.location"); vertexEndpoint.text = get("vertex.endpoint")
        gcloudPath.text = get("gcloud.path")
        apigeeTokenUrl.text = get("apigee.token.url"); apigeeClientId.text = get("apigee.client.id")
        apigeeClientSecret.text = get("apigee.client.secret"); apigeeAgents.text = get("apigee.agents")
        copilotMode.item = get("copilot.mode"); copilotUrl.text = get("copilot.url"); copilotHeadless.item = get("copilot.headless")
        webEnabled.isSelected = get("web.enabled") != "false"
        searchProvider.item = get("search.provider"); searchKey.text = get("search.api.key"); searchCx.text = get("search.cx")
        mcpConfig.text = get("mcp.config")
    }

    private fun defaultFor(key: String): String = when (key) {
        "gemini.mode" -> "gemini-api"
        "gemini.model" -> "gemini-3.7-flash"
        "vertex.location" -> "us-central1"
        "copilot.mode" -> "browser"
        "copilot.url" -> "https://m365.cloud.microsoft/chat"
        "copilot.headless" -> "auto"
        else -> ""
    }

    // ---- widgets -------------------------------------------------------------

    private fun section(title: String) = JBLabel("<html><b>$title</b></html>").apply { border = JBUI.Borders.emptyTop(12) }
    private fun hint(text: String) = JBLabel("<html>$text</html>").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }
    private fun result() = JTextArea(4, 60).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        font = JBUI.Fonts.smallFont(); foreground = UIUtil.getContextHelpForeground(); background = null
    }

    private companion object {
        val GEMINI_MODELS = arrayOf(
            "gemini-3.7-flash", "gemini-3.1-pro-preview", "gemini-3.6-flash", "gemini-3.5-flash",
            "gemini-3.5-flash-lite", "gemini-2.5-pro", "gemini-2.5-flash",
        )
        @Suppress("unused") val CONFIG_FILE: File = RelayConfigFile.file()
    }
}
