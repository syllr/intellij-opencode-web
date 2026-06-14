package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
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

        // 防 resetToolWindow 内部 activate() 触发 stateChanged(..., ActivateToolWindow) 回调形成循环。
        // 用 AtomicLong 记录上次 reset 时间戳(非 @Volatile var,符合 AGENTS.md 静态全局状态 HARD RULE)。
        // 1.5s 窗口:hide→activate 内部回调会被挡掉;真实"从无焦点到有焦点"事件(>1.5s)才放行。
        internal val lastResetAtMs = java.util.concurrent.atomic.AtomicLong(0)
        private const val RESET_DEBOUNCE_MS = 1500L

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
         *
         * 自动调用方:
         * - 用户主动点击 OpenCodeWeb 标签 → stateChanged 事件 ActivateToolWindow → 自动 reset(80% 场景解决 JCEF 焦点卡死)
         * - 详见 createToolWindowContent 末尾的 focusListener(防循环用 1.5s 时间窗)
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

            myToolWindow.checkAndLoadContent()
        }

        // 焦点监听:用户切到 OpenCodeWeb 工具窗口时自动 resetToolWindow 恢复 JCEF 焦点
        val focusListener = object : ToolWindowManagerListener {
            override fun stateChanged(
                mgr: com.intellij.openapi.wm.ToolWindowManager,
                tw: ToolWindow,
                type: ToolWindowManagerEventType
            ) {
                if (tw.id != OPCODE_WEB_TOOL_WINDOW_ID) return
                if (type != ToolWindowManagerEventType.ActivateToolWindow) return
                val now = System.currentTimeMillis()
                val last = lastResetAtMs.get()
                if (now - last < RESET_DEBOUNCE_MS) return  // 防 reset 内部 activate 循环触发
                lastResetAtMs.set(now)
                thisLogger().debug("[Lifecycle] OpenCodeWeb tool window activated, triggering resetToolWindow")
                resetToolWindow(tw.project)
            }
        }
        // 订阅 project message bus,用 toolWindow.disposable 作为 parent,
        // toolWindow 销毁时 listener 自动反注册,无内存泄漏。
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            focusListener
        )
    }

    override suspend fun isApplicableAsync(project: Project) = true
}
