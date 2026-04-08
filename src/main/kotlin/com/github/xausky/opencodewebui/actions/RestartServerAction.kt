package com.github.xausky.opencodewebui.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.github.xausky.opencodewebui.toolWindow.MyToolWindowFactory

/**
 * 重启 OpenCode Server
 *
 * 功能：kill 端口 10086 的进程，然后重新启动 server
 * 用途：当 opencode 版本更新或 server 出问题时手动重启
 */
class RestartServerAction : AnAction() {
    
    override fun actionPerformed(e: AnActionEvent) {
        MyToolWindowFactory.restartServer(e.project)
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = true
    }
}
