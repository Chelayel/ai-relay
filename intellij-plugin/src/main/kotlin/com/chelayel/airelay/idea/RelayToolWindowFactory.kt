package com.chelayel.airelay.idea

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import javax.swing.JComponent

class RelayToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val (component, disposable) = runCatching { jcefPanel(project) }.getOrElse {
            JBLabel(
                "<html><body style='padding:12px'><b>AI Relay needs the embedded browser (JCEF).</b><br>" +
                    "This IDE build does not provide it. Use the <code>airelay</code> command line instead.</body></html>",
            ) to null
        }
        val content = ContentFactory.getInstance().createContent(component, "", false)
        if (disposable != null) content.setDisposer(disposable)
        toolWindow.contentManager.addContent(content)
    }

    // In its own method so the JCEF classes are only touched when it's used; on an
    // IDE without them the reference throws here, and the fallback above shows.
    private fun jcefPanel(project: Project): Pair<JComponent, RelayChatPanel> {
        check(com.intellij.ui.jcef.JBCefApp.isSupported()) { "JCEF is not supported" }
        val panel = RelayChatPanel(project)
        return panel to panel
    }
}
