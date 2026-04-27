package com.github.xausky.opencodewebui.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

class AddToPromptAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor? = e.getData(CommonDataKeys.EDITOR)

        val selectedText = editor?.selectionModel?.selectedText

        if (selectedText.isNullOrBlank()) {
            // 无选中文本：切换工具窗口显示状态
            com.github.xausky.opencodewebui.toolWindow.PromptToolWindowFactory.toggleToolWindowAndFocus(project)
            return
        }

        // 有选中文本：格式化并追加到 PromptEditor
        val psiFile: PsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val filePath = psiFile.virtualFile?.path ?: return

        val document = editor.document
        val startOffset = editor.selectionModel.selectionStart
        val endOffset = editor.selectionModel.selectionEnd
        val startLine = document.getLineNumber(startOffset) + 1
        val endLine = document.getLineNumber(endOffset) + 1

        val formattedContent = formatAsPrompt(filePath, startLine, endLine, selectedText)
        val contentWithBlankLine = "\n$formattedContent"

        val panel = com.github.xausky.opencodewebui.toolWindow.PromptToolWindowFactory.getPanel(project)
        if (panel != null) {
            panel.appendText(contentWithBlankLine)
            com.github.xausky.opencodewebui.toolWindow.PromptToolWindowFactory.getOrActivateToolWindowWithFocus(project)
        }
    }

    // 只要有项目就启用，因为快捷键可能在 PromptEditor 内部文本框有焦点时触发
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}