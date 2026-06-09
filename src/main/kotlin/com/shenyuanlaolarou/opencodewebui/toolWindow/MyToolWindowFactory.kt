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
         * 重置 OpenCodeWeb 工具窗口：hide + activate 重新走 show/hide 状态机。
         * 用于恢复 JCEF 焦点卡死(键盘事件不响应、textarea 输入丢失)—
         * 实测能恢复焦点,但底层机制(Swing focus 重派发 / CEF visibility
         * 回调 / 其他)尚未确证。
         *
         * 注意:ToolWindow.hide() 不会 dispose JCEF 浏览器实例,也不销毁
         * ContentManager 的 Content — 这只是 UI 状态归位,不是对象重建。
         *
         * 必须在 EDT 调用 ToolWindow API。最外层 invokeLater 把 JCEF 的
         * ContextMenu 回调线程(非 EDT)切到 EDT;内层 invokeLater 让 activate
         * 推迟到 hide 完成 HIDE 事件派发之后。不能在 EDT 上 sleep 制造延迟,
         * 会冻结整个 IDE UI。
         */
        fun resetToolWindow(project: Project) {
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(OPCODE_WEB_TOOL_WINDOW_ID) ?: return
            thisLogger().debug("[Lifecycle] resetToolWindow: hiding + reactivating '$OPCODE_WEB_TOOL_WINDOW_ID'")
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
