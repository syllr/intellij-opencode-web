package com.github.xausky.opencodewebui.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 传递快捷键给 JCEF 的 Action
 * 
 * 原理：
 * - 当 OpenCodeWeb 工具窗口激活时，Action 什么都不做，让 KeyEvent 自然传播到 JCEF
 * - JCEF 使用 OSR (Off-Screen Rendering)，焦点由 Chromium 内部管理
 * - Java 焦点系统无法检测 JCEF 内部的焦点状态，所以用工具窗口状态来判断
 * 
 * 当 OpenCodeWeb 工具窗口未激活时，Action 被禁用，快捷键由 IDEA 原有 Action 处理
 */
class PassToJcefAction : AnAction() {
    
    companion object {
        private const val TOOL_WINDOW_ID = "OpenCodeWeb"
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        // 当 OpenCodeWeb 工具窗口激活时，什么都不做
        // 快捷键会通过 KeyEventDispatcher 传递给 JCEF
        val project = e.project
        if (project != null) {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val activeToolWindowId = toolWindowManager.activeToolWindowId
            println("[ACTION] OpenCodeWeb active: ${activeToolWindowId == TOOL_WINDOW_ID}, active window: $activeToolWindowId")
        }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val activeToolWindowId = toolWindowManager.activeToolWindowId
        
        // 只有当 OpenCodeWeb 工具窗口激活时才启用此 Action
        // 启用时，Action 会"吞掉"快捷键（什么都不做）
        // 禁用时，快捷键会被 IDEA 的其他 Action 处理
        val isToolWindowActive = activeToolWindowId == TOOL_WINDOW_ID
        e.presentation.isEnabled = isToolWindowActive
        
        if (isToolWindowActive) {
            // 可选：让 Action 在 UI 上不可见，这样不会影响菜单显示
            e.presentation.setVisible(false)
        }
    }
}
