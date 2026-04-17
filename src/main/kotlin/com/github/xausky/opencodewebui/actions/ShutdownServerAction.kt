package com.github.xausky.opencodewebui.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.github.xausky.opencodewebui.toolWindow.MyToolWindowFactory

/**
 * 关闭 OpenCode Server
 *
 * 功能：通过 killProcessByPort 关闭 12396 端口的进程
 * 用途：当用户想要关闭服务器时手动关闭
 */
class ShutdownServerAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        MyToolWindowFactory.stopServer()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = true
    }
}