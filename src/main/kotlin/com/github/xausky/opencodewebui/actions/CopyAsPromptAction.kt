package com.github.xausky.opencodewebui.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * 格式化选中文本为 prompt 格式
 * 输出格式：
 * location:<文件路径>:<行号>
 * content:
 * ```
 * <选中的代码>
 * ```
 */
fun formatAsPrompt(filePath: String, startLine: Int, endLine: Int, selectedText: String): String {
    val lineRange = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
    // 在结束 ``` 后加一个带 2 个空格的空白行。
    // 这是为了在 contenteditable 输入框中渲染出一个可见的空白行，
    // 视觉上分隔代码块与后续光标位置，避免末尾代码行被误处理。
    return "location:$filePath:$lineRange\ncontent:\n```\n$selectedText\n```\n  "
}

class CopyAsPromptAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: return
        if (selectedText.isBlank()) return

        val psiFile: PsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val filePath = psiFile.virtualFile?.path ?: return

        val document = editor.document
        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd

        val startLine = document.getLineNumber(startOffset) + 1
        val endLine = document.getLineNumber(endOffset) + 1

        val formattedContent = formatAsPrompt(filePath, startLine, endLine, selectedText)

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(formattedContent), null)
    }

    // 只有在编辑器有选中文本时才启用
    override fun update(e: AnActionEvent) {
        val editor: Editor? = e.getData(CommonDataKeys.EDITOR)
        if (editor == null) {
            e.presentation.isEnabled = false
            return
        }

        val selectionModel = editor.selectionModel
        val hasSelection = selectionModel.hasSelection() &&
                !selectionModel.selectedText.isNullOrBlank()

        e.presentation.isEnabled = hasSelection
    }
}
