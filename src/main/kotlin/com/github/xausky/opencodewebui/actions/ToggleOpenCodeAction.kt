package com.github.xausky.opencodewebui.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.wm.ToolWindowManager

class ToggleOpenCodeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb") ?: return
        
        if (toolWindow.isVisible) {
            toolWindow.hide()
        } else {
            toolWindow.show()
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}
