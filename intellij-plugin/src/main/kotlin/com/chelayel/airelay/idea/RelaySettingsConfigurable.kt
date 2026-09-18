package com.chelayel.airelay.idea

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings → Tools → AI Relay. */
class RelaySettingsConfigurable : Configurable {

    private val backend = ComboBox(arrayOf("claude", "gemini", "copilot", "demo"))
    private val mode = ComboBox(arrayOf("ask", "acceptEdits", "bypass"))
    private val javaPath = JBTextField()
    private val extraArgs = JBTextField()
    private var panel: JPanel? = null

    override fun getDisplayName() = "AI Relay"

    override fun createComponent(): JComponent {
        val configDir = File(System.getProperty("user.home"), ".airelay")
        val copyGemini = JButton("Copy Gemini setup command").apply {
            addActionListener { copy(RelayProcess.setupCommand("gemini")) }
        }
        val copyCopilot = JButton("Copy Copilot setup command").apply {
            addActionListener { copy(RelayProcess.setupCommand("copilot")) }
        }
        val p = FormBuilder.createFormBuilder()
            .addLabeledComponent("Default agent:", backend)
            .addLabeledComponent("Tool permissions:", mode)
            .addComponent(JBLabel("<html><i>ask</i> confirms every tool, <i>acceptEdits</i> applies file changes without asking, <i>bypass</i> runs everything.</html>"))
            .addSeparator()
            .addComponent(JBLabel("<html><b>Connections</b> are the CLI's: keys, the Copilot capture and MCP servers live in <code>${configDir.path}</code>, shared with the <code>airelay</code> command. Claude needs nothing here — it uses the <code>claude</code> CLI's own login.</html>"))
            .addComponent(JBLabel("<html>To set up Gemini or Copilot, paste the copied command into a terminal:</html>"))
            .addComponent(JPanel().apply { add(copyGemini); add(copyCopilot) })
            .addSeparator()
            .addLabeledComponent("Java (optional):", javaPath)
            .addComponent(JBLabel("<html>Path to a JDK 21+ or its <code>java</code>. Empty: the IDE's own runtime.</html>"))
            .addLabeledComponent("Extra arguments:", extraArgs)
            .addComponent(JBLabel("<html>Appended to every launch, e.g. <code>--add-dir /path --model NAME --no-web</code>.</html>"))
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = p
        reset()
        return p
    }

    private fun copy(text: String) {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun isModified(): Boolean {
        val s = RelaySettings.get().state
        return backend.item != s.backend || mode.item != s.permissionMode ||
            javaPath.text != s.javaPath || extraArgs.text != s.extraArgs
    }

    override fun apply() {
        val s = RelaySettings.get().state
        s.backend = backend.item as String
        s.permissionMode = mode.item as String
        s.javaPath = javaPath.text.trim()
        s.extraArgs = extraArgs.text.trim()
    }

    override fun reset() {
        val s = RelaySettings.get().state
        backend.item = s.backend
        mode.item = s.permissionMode
        javaPath.text = s.javaPath
        extraArgs.text = s.extraArgs
    }
}
