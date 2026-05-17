package com.github.xausky.opencodewebui.toolWindow

import com.github.xausky.opencodewebui.utils.OpenCodeNotificationRouter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefClient
import com.intellij.util.messages.MessageBusConnection
import java.util.concurrent.ConcurrentHashMap

@Suppress("DEPRECATION", "EXPERIMENTAL_API_USAGE")
class MyToolWindowFactory : ToolWindowFactory, DumbAware {

    init {
        // 项目打开时注册到 Router（双保险注册的第一道保险）
        val connection: MessageBusConnection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectOpened(project: Project) {
                OpenCodeNotificationRouter.register(project)
            }
            override fun projectClosing(project: Project) {
                OpenCodeNotificationRouter.unregister(project)
            }
        })
    }

    companion object {
        private const val OPCODE_WEB_TOOL_WINDOW_ID = "OpenCodeWeb"

        fun openOpenCodeWebToolWindow(project: Project) {
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(OPCODE_WEB_TOOL_WINDOW_ID) ?: return
            thisLogger().info("[Lifecycle] openOpenCodeWebToolWindow: activating tool window '$OPCODE_WEB_TOOL_WINDOW_ID', myToolWindowInstances.size=${myToolWindowInstances.size}")
            toolWindow.activate(null)
        }

        internal val sharedJBCefClient by lazy { JBCefApp.getInstance().createClient() }

        internal val myToolWindowInstances: ConcurrentHashMap<Project, MyToolWindow> = ConcurrentHashMap()

        /**
         * 获取当前项目中主浏览器实例，用于在其他组件中执行 JavaScript
         */
        fun getMainBrowser(project: Project): JBCefBrowser? {
            return myToolWindowInstances[project]?.getBrowser()
        }

        fun stopServer() {
            try {
                OpenCodeServerManager.stopServer()
            } catch (e: Exception) {
                thisLogger().error("Error stopping OpenCode server: ${e.message}")
            }
        }

    }

    // 【一】插件打开工具窗口时调用（调用时机：用户首次打开 OpenCode 工具窗口）
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        thisLogger().info("[Lifecycle] createToolWindowContent called, project=${project.name}")
        OpenCodeNotificationRouter.register(project)
        val contentManager = toolWindow.contentManager

        // 创建 MyToolWindow，触发 init 块设置 myToolWindowInstance
        val myToolWindow = MyToolWindow(toolWindow)
        thisLogger().info("[Lifecycle] MyToolWindow instance created, myToolWindowInstance set")
        ApplicationManager.getApplication().invokeLater {
            val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
            toolWindow.contentManager.addContent(content)

            toolWindow.contentManager.addContentManagerListener(object :
                com.intellij.ui.content.ContentManagerListener {
                override fun selectionChanged(event: com.intellij.ui.content.ContentManagerEvent) {
                    if (event.content === content) {
                        myToolWindow.requestBrowserFocus()
                    }
                }
            })

            myToolWindow.setupBrowserKeyboardHandling()
            myToolWindow.checkAndLoadContent()
        }
    }

    override suspend fun isApplicableAsync(project: Project) = true
}
