package com.github.xausky.opencodewebui.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefClient

/**
 * OpenCode Web UI 工具窗口工厂类
 *
 * 【重要】实现 DumbAware 接口：
 * IntelliJ 在 "dumb mode"（索引更新期间）会禁用需要智能功能的组件，默认情况下
 * 所有 ToolWindow 都会受到影响，显示 "This view is not available until indexes are built"。
 *
 * 实现 DumbAware 接口可以告知 IntelliJ：这个工具窗口不依赖于项目索引，
 * 在索引更新期间也可以正常使用。OpenCode 是纯浏览器 UI，不需要索引，
 * 因此实现此接口可以避免索引期间的覆盖层提示。
 *
 * 【API 警告说明】：
 * @Suppress("DEPRECATION", "EXPERIMENTAL_API_USAGE") 用于抑制 ToolWindowFactory
 * 接口的 deprecated/experimental 方法警告。这些警告来自 IntelliJ 框架本身，
 * 不是我们代码的问题 - 官方 SDK 示例同样使用相同模式。
 */
@Suppress("DEPRECATION", "EXPERIMENTAL_API_USAGE")
class MyToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        private const val OPCODE_WEB_TOOL_WINDOW_ID = "OpenCodeWeb"

        fun openOpenCodeWebToolWindow(project: Project) {
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(OPCODE_WEB_TOOL_WINDOW_ID) ?: return
            thisLogger().info("[Lifecycle] openOpenCodeWebToolWindow: activating tool window '$OPCODE_WEB_TOOL_WINDOW_ID', myToolWindowInstance=${myToolWindowInstance != null}")
            toolWindow.activate(null)
        }

        internal val sharedJBCefClient by lazy { JBCefApp.getInstance().createClient() }

        internal var myToolWindowInstance: MyToolWindow? = null

        /**
         * 获取主浏览器实例，用于在其他组件中执行 JavaScript
         */
        fun getMainBrowser(): JBCefBrowser? {
            return myToolWindowInstance?.getBrowser()
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
    // 调用此函数 → 创建 MyToolWindow → setupBrowserKeyboardHandling → checkAndLoadContent → startPeriodicCheck → startPeriodicUpdateCheck
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        thisLogger().info("[Lifecycle] createToolWindowContent called, project=${project.name}")
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
