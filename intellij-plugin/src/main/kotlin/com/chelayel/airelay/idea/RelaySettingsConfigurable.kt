package com.chelayel.airelay.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Settings → Tools → AI Relay: one tab per agent, and inside Gemini only the
 * fields of the chosen connection (an Apigee gateway, Vertex AI or an API key)
 * — the single long form put all three side by side and read as if every field
 * were needed. Fields still map one-to-one onto keys of the CLI's config file,
 * so saving here configures the command line and the VS Code extension as well.
 * The *Test* buttons run the CLI's own probes (`gemini models`, `copilot test`,
 * `web`), and the CLI agents' *Check* buttons their own `--version`.
 */
class RelaySettingsConfigurable : Configurable {

    // General
    private val backend = ComboBox(arrayOf("gemini", "copilot", "github", "claude")).labelled(AGENT_NAMES)
    private val mode = ComboBox(arrayOf("readOnly", "ask", "acceptEdits", "bypass")).labelled(MODE_NAMES)
    private val modeHint = hint("")

    // Gemini
    private val viaApigee = JBRadioButton("Apigee gateway")
    private val viaVertex = JBRadioButton("Vertex AI")
    private val viaKey = JBRadioButton("Gemini API key")
    private val geminiCards = JPanel(CardLayout())
    private val geminiModel = ComboBox(GEMINI_MODELS).apply { isEditable = true }
    private val geminiKey = JBPasswordField()
    private val vertexProject = JBTextField()
    private val vertexLocation = JBTextField()
    private val apigeeProject = JBTextField()
    private val apigeeLocation = JBTextField()
    private val vertexEndpoint = JBTextField()
    private val vertexApiVersion = JBTextField()
    private val vertexBaseUrl = JBTextField()
    private val gcloudPath = JBTextField()
    private val apigeeTokenUrl = JBTextField()
    private val apigeeClientId = JBTextField()
    private val apigeeClientSecret = JBPasswordField()
    private val apigeeAgents = JBTextField()
    private val geminiPrices = JBTextField()
    private val geminiTest = JButton("Test connection")
    private val geminiResult = result()

    // Microsoft 365 Copilot
    private val copilotMode = ComboBox(arrayOf("browser", "replay")).labelled(mapOf("browser" to "Drive a browser tab (Microsoft 365 Copilot)", "replay" to "Replay a captured request"))
    private val copilotUrl = JBTextField()
    private val copilotHeadless = ComboBox(arrayOf("auto", "true", "false")).labelled(mapOf("auto" to "Shown until you have signed in", "true" to "Always hidden", "false" to "Always shown"))
    private val copilotTest = JButton("Test Copilot")
    private val copilotCapture = JButton("Capture a session…")
    private val copilotResult = result()

    // GitHub Copilot and Claude: nothing to configure, but say whether the CLI is there.
    private val githubCheck = JButton("Check")
    private val githubResult = result()
    private val claudeCheck = JButton("Check")
    private val claudeResult = result()

    // Web
    private val webEnabled = JBCheckBox("Let the agent read URLs, search the web and query Maven Central")
    private val searchProvider = ComboBox(arrayOf("", "brave", "tavily", "google")).labelled(mapOf("" to "None (no web search)", "brave" to "Brave Search", "tavily" to "Tavily", "google" to "Google Programmable Search"))
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
        githubCheck.addActionListener { checkCli(githubResult, "copilot", "Not found. Install GitHub's Copilot CLI — brew install copilot-cli, or npm install -g @github/copilot — then sign in once with `copilot login`.") }
        claudeCheck.addActionListener { checkCli(claudeResult, "claude", "Not found. Install Claude Code (https://claude.com/claude-code), then sign in once by running `claude`.") }
        copilotCapture.addActionListener {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                java.awt.datatransfer.StringSelection(RelayProcess.cliCommand("copilot", "setup", "--replay")), null,
            )
            copilotResult.text = "Command copied. Paste it into a terminal: it opens a browser, you send one message, and the request is captured. Browser mode needs none of this."
        }
        mode.addActionListener { modeHint.text = "<html>${MODE_HINTS[mode.item] ?: ""}</html>" }

        val tabs = JBTabbedPane().apply {
            addTab("General", page(general()))
            addTab("Gemini", page(gemini()))
            addTab("Microsoft 365 Copilot", page(copilot()))
            addTab("GitHub Copilot", page(github()))
            addTab("Claude", page(claude()))
            addTab("Web search", page(web()))
        }
        val footer = hint("Saved to ${RelayConfigFile.file().path}, readable only by you and shared with the `airelay` command and the VS Code extension.")
            .apply { border = JBUI.Borders.empty(6, 2, 0, 2) }
        panel = JPanel(BorderLayout()).apply { add(tabs, BorderLayout.CENTER); add(footer, BorderLayout.SOUTH) }
        reset()
        return panel!!
    }

    // ---- tabs ----------------------------------------------------------------

    private fun general(): JComponent {
        val advanced = FormBuilder.createFormBuilder()
            .addLabeledComponent("MCP config file:", mcpConfig)
            .addComponent(hint("An `mcpServers` JSON file (Claude Desktop shape). Blank: ~/.airelay/mcp.json, then .mcp.json in the project."))
            .addLabeledComponent("Java:", javaPath)
            .addComponent(hint("A JDK 21+ or its `java`. Blank: the IDE's own runtime."))
            .addLabeledComponent("Extra arguments:", extraArgs)
            .addComponent(hint("Added to every launch, e.g. --add-dir /path --no-web."))
            .panel
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Default agent:", backend)
            .addComponent(hint("The agent a new panel starts with; the picker in the panel switches it at any time."))
            .addLabeledComponent("Permissions:", mode)
            .addComponent(modeHint)
            .addComponent(collapsible("Advanced", advanced))
            .panel
    }

    private fun gemini(): JComponent {
        ButtonGroup().apply { add(viaApigee); add(viaVertex); add(viaKey) }
        val apigee = FormBuilder.createFormBuilder()
            .addLabeledComponent("Gateway host:", vertexEndpoint)
            .addComponent(hint("The gateway in front of Vertex, e.g. my-gateway.example.com."))
            .addLabeledComponent("Project ID:", apigeeProject)
            .addLabeledComponent("Location:", apigeeLocation)
            .addLabeledComponent("Token URL:", apigeeTokenUrl)
            .addLabeledComponent("Client ID:", apigeeClientId)
            .addLabeledComponent("Client secret:", apigeeClientSecret)
            .addLabeledComponent("Models:", apigeeAgents)
            .addComponent(hint("The model ids the gateway publishes, comma-separated. The panel offers exactly these."))
            .addComponent(collapsible("Advanced", FormBuilder.createFormBuilder()
                .addLabeledComponent("API version:", vertexApiVersion)
                .addComponent(hint("In the request path; v1 unless the gateway says otherwise (some use v1beta1)."))
                .addLabeledComponent("Full base URL:", vertexBaseUrl)
                .addComponent(hint("Only for a gateway that mounts Vertex elsewhere: everything before /models. May use {project}, {location} and {version}; then the gateway host is not needed."))
                .panel))
            .panel
        val vertex = FormBuilder.createFormBuilder()
            .addLabeledComponent("Project ID:", vertexProject)
            .addLabeledComponent("Location:", vertexLocation)
            .addLabeledComponent("gcloud path:", gcloudPath)
            .addComponent(hint("Signs in with `gcloud auth print-access-token`. Blank: gcloud on PATH."))
            .panel
        val key = FormBuilder.createFormBuilder()
            .addLabeledComponent("API key:", geminiKey)
            .addComponent(hint("From Google AI Studio (aistudio.google.com)."))
            .panel
        geminiCards.add(apigee, "apigee"); geminiCards.add(vertex, "vertex"); geminiCards.add(key, "gemini-api")
        listOf(viaApigee, viaVertex, viaKey).forEach { it.addActionListener { showGeminiCard() } }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Connect through:", JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(viaApigee); add(javax.swing.Box.createHorizontalStrut(JBUI.scale(12))); add(viaVertex)
                add(javax.swing.Box.createHorizontalStrut(JBUI.scale(12))); add(viaKey)
            })
            .addComponent(geminiCards)
            .addSeparator(JBUI.scale(8))
            .addLabeledComponent("Default model:", geminiModel)
            .addComponent(hint("With an API key the panel lists your models live; this is the one a new conversation starts with. Several, comma-separated, are all offered."))
            .addLabeledComponent("Prices (optional):", geminiPrices)
            .addComponent(hint("USD per 1M tokens as model=prompt/output, comma-separated — gemini-3.8-flash=0.75/3.75. Each turn then shows its cost."))
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(geminiTest) })
            .addComponent(geminiResult)
            .panel
    }

    private fun copilot(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(hint("Your signed-in Microsoft 365 Copilot, with your company's sign-in — no API key. Sign in in the browser window on the first turn; pick the model there."))
        .addLabeledComponent("How:", copilotMode)
        .addLabeledComponent("Copilot page:", copilotUrl)
        .addLabeledComponent("Browser window:", copilotHeadless)
        .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(copilotTest); add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8))); add(copilotCapture) })
        .addComponent(hint("Capture is only for replay mode; browser mode, the one Microsoft 365 Copilot needs, works without it."))
        .addComponent(copilotResult)
        .panel

    private fun github(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(hint("Nothing to configure here. GitHub Copilot runs through GitHub's own Copilot CLI with your GitHub login — its tools, its models, and the same premium requests as in the CLI itself. The panel lists your models with their premium cost, and shows your monthly allowance (read through the `gh` CLI, when it is installed)."))
        .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(githubCheck) })
        .addComponent(githubResult)
        .panel

    private fun claude(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(hint("Nothing to configure here. Claude runs through Claude Code (the `claude` CLI) with whatever it is signed in with. The panel lists the CLI's models and shows your 5-hour and weekly limits."))
        .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(claudeCheck) })
        .addComponent(claudeResult)
        .panel

    private fun web(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(webEnabled)
        .addComponent(hint("Reading a URL and Maven Central need no key. For the Gemini and Microsoft 365 Copilot agents; Claude and GitHub Copilot have their own."))
        .addLabeledComponent("Search provider:", searchProvider)
        .addLabeledComponent("API key:", searchKey)
        .addLabeledComponent("Google engine id (cx):", searchCx)
        .addComponent(hint("Without a provider, web search is simply not offered to the model."))
        .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(webTest) })
        .addComponent(webResult)
        .panel

    private fun showGeminiCard() {
        (geminiCards.layout as CardLayout).show(geminiCards, geminiMode())
    }

    private fun geminiMode() = when {
        viaApigee.isSelected -> "apigee"
        viaVertex.isSelected -> "vertex"
        else -> "gemini-api"
    }

    // ---- actions -------------------------------------------------------------

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

    /** `<name> --version`, found the way the agent will find it (an IDE from the Dock has a short PATH). */
    private fun checkCli(into: JTextArea, name: String, missing: String) {
        into.text = "Checking…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val home = System.getProperty("user.home")
            val exe = (System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin", "$home/.local/bin", "$home/.npm-global/bin"))
                .filter { it.isNotBlank() }.map { File(it, name) }.firstOrNull { it.canExecute() }
            val out = if (exe == null) missing else runCatching {
                val p = ProcessBuilder(exe.path, "--version").redirectErrorStream(true).start()
                val v = p.inputStream.bufferedReader().readText().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
                p.waitFor()
                "Found ${exe.path}" + (if (v.isNotBlank()) " — $v" else "")
            }.getOrElse { "Found ${exe.path}, but it did not run: ${it.message}" }
            ApplicationManager.getApplication().invokeLater { into.text = out }
        }
    }

    // ---- state ---------------------------------------------------------------

    /** Vertex and Apigee both read vertex.project / vertex.location; the field shown is the one that counts. */
    private fun values(): Map<String, String?> {
        val apigee = geminiMode() == "apigee"
        return mapOf(
            "gemini.mode" to geminiMode(), "gemini.model" to (geminiModel.editor.item?.toString() ?: ""),
            "gemini.api.key" to String(geminiKey.password),
            "vertex.project" to (if (apigee) apigeeProject else vertexProject).text,
            "vertex.location" to (if (apigee) apigeeLocation else vertexLocation).text,
            "vertex.endpoint" to vertexEndpoint.text,
            "vertex.api.version" to vertexApiVersion.text, "vertex.base.url" to vertexBaseUrl.text,
            "gcloud.path" to gcloudPath.text,
            "apigee.token.url" to apigeeTokenUrl.text, "apigee.client.id" to apigeeClientId.text,
            "apigee.client.secret" to String(apigeeClientSecret.password), "apigee.agents" to apigeeAgents.text, "gemini.prices" to geminiPrices.text,
            "copilot.mode" to copilotMode.item, "copilot.url" to copilotUrl.text, "copilot.headless" to copilotHeadless.item,
            "web.enabled" to if (webEnabled.isSelected) "" else "false",
            "search.provider" to searchProvider.item, "search.api.key" to String(searchKey.password), "search.cx" to searchCx.text,
            "mcp.config" to mcpConfig.text,
        )
    }

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
        modeHint.text = "<html>${MODE_HINTS[s.permissionMode] ?: ""}</html>"
        javaPath.text = s.javaPath
        extraArgs.text = s.extraArgs
        val f = RelayConfigFile.read()
        fun get(k: String) = f.getProperty(k) ?: defaultFor(k)
        when (get("gemini.mode")) { "apigee" -> viaApigee.isSelected = true; "vertex" -> viaVertex.isSelected = true; else -> viaKey.isSelected = true }
        showGeminiCard()
        geminiModel.editor.item = get("gemini.model"); geminiKey.text = get("gemini.api.key")
        vertexProject.text = get("vertex.project"); vertexLocation.text = get("vertex.location")
        apigeeProject.text = get("vertex.project"); apigeeLocation.text = get("vertex.location")
        vertexEndpoint.text = get("vertex.endpoint")
        vertexApiVersion.text = get("vertex.api.version"); vertexBaseUrl.text = get("vertex.base.url")
        gcloudPath.text = get("gcloud.path")
        apigeeTokenUrl.text = get("apigee.token.url"); apigeeClientId.text = get("apigee.client.id")
        apigeeClientSecret.text = get("apigee.client.secret"); apigeeAgents.text = get("apigee.agents"); geminiPrices.text = get("gemini.prices")
        copilotMode.item = get("copilot.mode"); copilotUrl.text = get("copilot.url"); copilotHeadless.item = get("copilot.headless")
        webEnabled.isSelected = get("web.enabled") != "false"
        searchProvider.item = get("search.provider"); searchKey.text = get("search.api.key"); searchCx.text = get("search.cx")
        mcpConfig.text = get("mcp.config")
    }

    private fun defaultFor(key: String): String = when (key) {
        "gemini.mode" -> "gemini-api"
        "gemini.model" -> "gemini-3.8-flash"
        "vertex.location" -> "us-central1"
        "vertex.api.version" -> "v1"
        "copilot.mode" -> "browser"
        "copilot.url" -> "https://m365.cloud.microsoft/chat"
        "copilot.headless" -> "auto"
        else -> ""
    }

    // ---- widgets -------------------------------------------------------------

    /** A tab's form, scrollable, with breathing room. */
    private fun page(form: JComponent): JComponent {
        val inner = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(10, 12); add(form, BorderLayout.NORTH) }
        return JBScrollPane(inner).apply { border = JBUI.Borders.empty() }
    }

    /** A section kept out of the way until asked for. */
    private fun collapsible(title: String, body: JComponent): JComponent {
        body.isVisible = false
        val link = ActionLink("$title ▸") {}
        link.addActionListener { body.isVisible = !body.isVisible; link.text = title + if (body.isVisible) " ▾" else " ▸"; body.parent?.revalidate() }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(10)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(link) }, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    private fun hint(text: String) = JBLabel("<html>$text</html>").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.emptyBottom(4)
    }
    private fun result() = JTextArea(3, 60).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        font = JBUI.Fonts.smallFont(); foreground = UIUtil.getContextHelpForeground(); background = null
    }

    private fun <T> ComboBox<T>.labelled(names: Map<String, String>): ComboBox<T> = apply {
        renderer = SimpleListCellRenderer.create("") { v -> names[v?.toString()] ?: v?.toString().orEmpty() }
    }

    private companion object {
        val GEMINI_MODELS = arrayOf(
            "gemini-3.8-flash", "gemini-4-argon", "gemini-3.1-pro-preview", "gemini-3.7-flash",
            "gemini-3.5-flash-lite",
        )
        val AGENT_NAMES = mapOf("gemini" to "Gemini", "copilot" to "Microsoft 365 Copilot", "github" to "GitHub Copilot", "claude" to "Claude")
        val MODE_NAMES = mapOf("readOnly" to "Read only", "ask" to "Ask first", "acceptEdits" to "Auto-edit", "bypass" to "Full auto")
        val MODE_HINTS = mapOf(
            "readOnly" to "Reads and searches the project; never edits a file or runs a command.",
            "ask" to "Asks before every edit and command.",
            "acceptEdits" to "Edits files without asking; asks before running a command.",
            "bypass" to "Runs everything without asking. Use it in a worktree or a disposable checkout.",
        )
    }
}
