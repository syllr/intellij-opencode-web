package com.github.xausky.opencodewebui.actions

import com.github.xausky.opencodewebui.toolWindow.MyToolWindowFactory
import com.github.xausky.opencodewebui.utils.IdeaVimIntegration
import com.github.xausky.opencodewebui.utils.JcefJsInjector
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities

class AddToPromptAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        // 如果焦点在 OpenCode Web 面板中，将焦点移回 IDE 编辑器
        if (isFocusInOpenCodeWeb()) {
            thisLogger().info("[AddToPromptAction] Focus in OpenCodeWeb, moving focus to editor")
            val editorManager = FileEditorManager.getInstance(project)
            val editor = editorManager.selectedTextEditor
            if (editor != null) {
                editor.contentComponent.requestFocusInWindow()
            } else {
                editorManager.allEditors.firstOrNull()?.component?.requestFocusInWindow()
            }
            return
        }

        val editor: Editor?

        var selectedText: String?
        var selStart: Int
        var selEnd: Int

        thisLogger().info("[AddToPromptAction] actionPerformed() called")

        // 优先尝试从 IdeaVim visual mode 获取选中文本
        if (IdeaVimIntegration.isIdeaVimInstalled()) {
            editor = e.getData(CommonDataKeys.EDITOR)
            val result = IdeaVimIntegration.getVisualSelection(editor)
            if (result != null) {
                selectedText = result.first
                selStart = result.second
                selEnd = result.third
            } else {
                selectedText = editor?.selectionModel?.selectedText
                selStart = editor?.selectionModel?.selectionStart ?: -1
                selEnd = editor?.selectionModel?.selectionEnd ?: -1
            }
        } else {
            editor = e.getData(CommonDataKeys.EDITOR)
            selectedText = editor?.selectionModel?.selectedText
            selStart = editor?.selectionModel?.selectionStart ?: -1
            selEnd = editor?.selectionModel?.selectionEnd ?: -1
        }

        if (selectedText.isNullOrBlank() || selStart < 0 || selEnd <= selStart) {
            thisLogger().info("[AddToPromptAction] No valid selection, opening OpenCode Web")
            MyToolWindowFactory.openOpenCodeWebToolWindow(project)
            return
        }

        val psiFile: PsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor!!.document) ?: return
        val filePath = psiFile.virtualFile?.path ?: return

        val document = editor.document
        val startLine = document.getLineNumber(selStart) + 1
        val endLine = document.getLineNumber(selEnd) + 1

        val formattedContent = formatAsPrompt(filePath, startLine, endLine, selectedText)

        JcefJsInjector.appendTextToEditor(project, formattedContent)

        // 清除选中
        if (IdeaVimIntegration.isIdeaVimInstalled() && IdeaVimIntegration.isInVisualMode(editor)) {
            IdeaVimIntegration.exitVisualMode(editor)
        } else {
            editor.caretModel.primaryCaret.removeSelection()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private fun isFocusInOpenCodeWeb(): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        val browser = MyToolWindowFactory.getMainBrowser() ?: return false
        return SwingUtilities.isDescendingFrom(focusOwner, browser.component)
    }
}
