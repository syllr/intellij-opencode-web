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
            thisLogger().debug("[Lifecycle] openOpenCodeWebToolWindow: activating tool window '$OPCODE_WEB_TOOL_WINDOW_ID', myToolWindowInstances.size=${myToolWindowInstances.size}")
            toolWindow.activate(null)
        }

        /**
         * 模拟快捷键连按两次：先 hide 触发 JCEF dispose + focus 链清空,
         * 再 invokeLater 重启。下一轮 EDT 消息本身就是延迟,IDE 有时间完成清理,
         * 不需要显式 sleep(在 EDT 上 sleep 会冻结 IDE UI)。
         *
         * 必须在 EDT 调用 ToolWindow API。JCEF 的 ContextMenu 回调跑在 JCEF 自己的
         * TThreadPoolServer 线程(非 EDT),所以最外层也必须 invokeLater。
         * 内层 invokeLater 让 activate 推后到 hide 完成 HIDE 事件派发之后。
         */
        fun toggleOpenCodeWebToolWindow(project: Project) {
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(OPCODE_WEB_TOOL_WINDOW_ID) ?: return
            thisLogger().debug("[Lifecycle] toggleOpenCodeWebToolWindow: hiding + reactivating '$OPCODE_WEB_TOOL_WINDOW_ID'")
            ApplicationManager.getApplication().invokeLater {
                toolWindow.hide()
                ApplicationManager.getApplication().invokeLater {
                    toolWindow.activate(null)
                }
            }
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
        thisLogger().info("[Lifecycle] createToolWindowContent: MyToolWindow created for project=${project.name}")
        val contentManager = toolWindow.contentManager

        // 创建 MyToolWindow，触发 init 块设置 myToolWindowInstance
        val myToolWindow = MyToolWindow(toolWindow)
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
