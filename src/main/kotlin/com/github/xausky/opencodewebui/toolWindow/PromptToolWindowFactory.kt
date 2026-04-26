package com.github.xausky.opencodewebui.toolWindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class PromptToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        private val panelMap = mutableMapOf<Project, PromptToolWindowPanel>()

        fun getPanel(project: Project): PromptToolWindowPanel? = panelMap[project]
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val promptPanel = PromptToolWindowPanel(project) { openMainWindow(project) }
        panelMap[project] = promptPanel
        val content = ContentFactory.getInstance().createContent(promptPanel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    private fun openMainWindow(project: Project) {
        val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("OpenCodeWeb") ?: return
        tw.activate(null)
    }

    override suspend fun isApplicableAsync(project: Project) = true
}
