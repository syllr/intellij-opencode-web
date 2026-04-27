package com.github.xausky.opencodewebui.toolWindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class PromptToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        private val panelMap = mutableMapOf<Project, PromptToolWindowPanel>()
        private const val TOOL_WINDOW_ID = "PromptEditor"

        fun getPanel(project: Project): PromptToolWindowPanel? = panelMap[project]

        // 激活工具窗口
        fun getOrActivateToolWindow(project: Project) {
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.activate(null)
        }

        // 激活工具窗口并将焦点移到文本框
        fun getOrActivateToolWindowWithFocus(project: Project) {
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.activate(null)
            panelMap[project]?.requestTextAreaFocus()
        }

        // 切换工具窗口显示状态
        fun toggleToolWindowVisibility(project: Project) {
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID) ?: return
            if (toolWindow.isVisible) {
                toolWindow.hide(null)
            } else {
                toolWindow.show()
            }
        }

        // 切换工具窗口显示状态，打开时同时聚焦文本框
        fun toggleToolWindowAndFocus(project: Project) {
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID) ?: return
            if (toolWindow.isVisible) {
                toolWindow.hide(null)
            } else {
                toolWindow.show()
                panelMap[project]?.requestTextAreaFocus()
            }
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val promptPanel = PromptToolWindowPanel(project) { openMainWindow(project) }
        panelMap[project] = promptPanel
        val content = ContentFactory.getInstance().createContent(promptPanel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    // 发送成功后打开主窗口
    private fun openMainWindow(project: Project) {
        val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("OpenCodeWeb") ?: return
        tw.activate(null)
    }

    override suspend fun isApplicableAsync(project: Project) = true
}
