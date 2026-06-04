package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefClient
import com.intellij.util.messages.MessageBusConnection
import java.util.concurrent.ConcurrentHashMap

class MyToolWindowFactory : ToolWindowFactory, DumbAware {

    init {
        val connection: MessageBusConnection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(com.intellij.ide.AppLifecycleListener.TOPIC, object : com.intellij.ide.AppLifecycleListener {
            override fun appWillBeClosed(isRestart: Boolean) {
                thisLogger().info("[Lifecycle] Application closing (isRestart=$isRestart), stopping server")
                OpenCodeServerManager.stopServer()
            }
        })
    }

    companion object {
        internal const val OPCODE_WEB_TOOL_WINDOW_ID = "OpenCodeWeb"

        fun openOpenCodeWebToolWindow(project: Project) {
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(OPCODE_WEB_TOOL_WINDOW_ID) ?: return
            thisLogger().info("[Lifecycle] openOpenCodeWebToolWindow: activating tool window '$OPCODE_WEB_TOOL_WINDOW_ID', myToolWindowInstances.size=${myToolWindowInstances.size}")
            toolWindow.activate(null)
        }

        internal val sharedJBCefClient by lazy {
            Registry.get("ide.browser.jcef.gpu.disable").setValue(false)
            System.setProperty("ide.browser.jcef.extra.args", "--enable-gpu-compositing,--use-gl=angle,--use-angle=metal")
            JBCefApp.getInstance().createClient()
        }

        internal val myToolWindowInstances: ConcurrentHashMap<Project, MyToolWindow> = ConcurrentHashMap()

        internal val contentManagerListeners: ConcurrentHashMap<Project, com.intellij.ui.content.ContentManagerListener> = ConcurrentHashMap()

        fun getMainBrowser(project: Project): JBCefBrowser? {
            return myToolWindowInstances[project]?.getBrowser()
        }

        fun isBrowserPageLoaded(project: Project): Boolean {
            return myToolWindowInstances[project]?.isPageLoaded() ?: false
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

    // 【一】插件打开工具窗口时调用（调用时机：用户首次打开 OpenCode 工具窗口）
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        thisLogger().info("[Lifecycle] createToolWindowContent called, project=${project.name}")
        val contentManager = toolWindow.contentManager

        // 创建 MyToolWindow，触发 init 块设置 myToolWindowInstance
        val myToolWindow = MyToolWindow(toolWindow)
        thisLogger().info("[Lifecycle] MyToolWindow instance created, myToolWindowInstance set")
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
            toolWindow.contentManager.addContent(content)

            val listener = object : com.intellij.ui.content.ContentManagerListener {
                override fun selectionChanged(event: com.intellij.ui.content.ContentManagerEvent) {
                    if (event.content === content) {
                        myToolWindow.requestBrowserFocus()
                    }
                }
            }
            toolWindow.contentManager.addContentManagerListener(listener)
            contentManagerListeners[project] = listener

            myToolWindow.checkAndLoadContent()
        }
    }

    override suspend fun isApplicableAsync(project: Project) = true
}
