package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.util.messages.MessageBusConnection
import java.util.concurrent.ConcurrentHashMap

class MyToolWindowFactory : ToolWindowFactory, DumbAware {
    private val log = thisLogger()

    init {
        val connection: MessageBusConnection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(AppLifecycleListener.TOPIC, object : AppLifecycleListener {
            override fun appWillBeClosed(isRestart: Boolean) {
                log.info("[Lifecycle] Application closing (isRestart=$isRestart), stopping server")
                OpenCodeServerManager.stopServer()
            }
        })
    }

    companion object {
        internal const val OPCODE_WEB_TOOL_WINDOW_ID = "OpenCodeWeb"

        internal val myToolWindowInstances: ConcurrentHashMap<Project, MyToolWindow> = ConcurrentHashMap()

        fun openOpenCodeWebToolWindow(project: Project) {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(OPCODE_WEB_TOOL_WINDOW_ID) ?: return
            thisLogger().debug("[Lifecycle] openOpenCodeWebToolWindow: activating '$OPCODE_WEB_TOOL_WINDOW_ID', myToolWindowInstances.size=${myToolWindowInstances.size}")
            toolWindow.activate(null)
        }

        /**
         * 重置 OpenCodeWeb 工具窗口：hide + activate 恢复焦点。
         * 仅操作 ToolWindow 状态机。
         *
         * 当前调用方：Dashboard 的 Reset 按钮（[MyToolWindow]）通过 [com.intellij.openapi.application.ApplicationManager.invokeLater] 异步触发。
         */
        fun resetToolWindow(project: Project) {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(OPCODE_WEB_TOOL_WINDOW_ID) ?: return
            thisLogger().debug("[Lifecycle] resetToolWindow: hiding + reactivating '$OPCODE_WEB_TOOL_WINDOW_ID'")
            ApplicationManager.getApplication().invokeLater {
                toolWindow.hide()
                ApplicationManager.getApplication().invokeLater {
                    toolWindow.activate(null)
                }
            }
        }

        fun stopServer() {
            try {
                OpenCodeServerManager.stopServer()
            } catch (e: Exception) {
                thisLogger().error("Error stopping OpenCode server: ${e.message}")
            }
        }

        fun shutdownServer() {
            try {
                OpenCodeServerManager.shutdownServer()
            } catch (e: Exception) {
                thisLogger().error("Error shutting down OpenCode server: ${e.message}")
            }
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        log.info("[Lifecycle] createToolWindowContent: MyToolWindow created for project=${project.name}")
        val myToolWindow = MyToolWindow(toolWindow, project)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
            toolWindow.contentManager.addContent(content)
        }
    }

    override suspend fun isApplicableAsync(project: Project) = true
}
