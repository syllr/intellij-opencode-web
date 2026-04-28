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

        // 确保 panel 被创建，即使工具窗口还没打开过
        fun getOrCreatePanel(project: Project): PromptToolWindowPanel {
            return panelMap[project] ?: run {
                val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID) ?: throw IllegalStateException("ToolWindow $TOOL_WINDOW_ID not found")
                // 触发内容创建
                if (toolWindow.contentManager.contents.isEmpty()) {
                    val contentFactory = ContentFactory.getInstance()
                    val panel = PromptToolWindowPanel(project)
                    panelMap[project] = panel
                    val content = contentFactory.createContent(panel, null, false)
                    toolWindow.contentManager.addContent(content)
                }
                panelMap[project]!!
            }
        }

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
        val promptPanel = PromptToolWindowPanel(project)
        panelMap[project] = promptPanel
        val content = ContentFactory.getInstance().createContent(promptPanel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override suspend fun isApplicableAsync(project: Project) = true
}
