package com.github.xausky.opencodewebui.actions

import com.github.xausky.opencodewebui.toolWindow.MyToolWindowFactory
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

class AddToPromptAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor?

        var selectedText: String?
        var selStart: Int
        var selEnd: Int

        thisLogger().info("[AddToPromptAction] actionPerformed() called")

        // 优先尝试从 IdeaVim visual mode 获取选中文本
        if (isIdeaVimInstalled()) {
            editor = e.getData(CommonDataKeys.EDITOR)
            val result = getIdeaVimVisualSelection(editor)
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

        val formattedContent = formatAsPrompt(filePath, startLine, endLine, selectedText) + "\n"

        // 直接追加到 OpenCode Web 输入框
        appendToOpenCodeWeb(project, formattedContent)

        // 清除选中
        if (isIdeaVimInstalled() && isInVisualMode(editor)) {
            exitVisualMode(editor)
        } else {
            editor.caretModel.primaryCaret.removeSelection()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    /**
     * 将文本追加到 OpenCode Web 页面的输入框
     */
    private fun appendToOpenCodeWeb(project: Project, text: String) {
        MyToolWindowFactory.openOpenCodeWebToolWindow(project)

        for (i in 1..20) {
            val browser = MyToolWindowFactory.getMainBrowser()
            if (browser != null && browser.cefBrowser != null) {
                thisLogger().info("[AddToPromptAction] browser ready after ${i * 500}ms")
                executeAppend(browser.cefBrowser!!, text)
                return
            }
            Thread.sleep(500)
        }

        thisLogger().warn("[AddToPromptAction] browser not ready after 10s")
    }

    private fun executeAppend(cefBrowser: org.cef.browser.CefBrowser, text: String) {
        val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        val js = """
            (function() {
                var attempt = 0;
                function tryAppend() {
                    var editor = document.querySelector('[role="textbox"][contenteditable="true"]');
                    if (!editor) {
                        if (attempt < 30) {
                            attempt++;
                            setTimeout(tryAppend, 100);
                        }
                        return;
                    }
                    var text = '$escapedText';
                    var current = editor.innerText || '';
                    if (current.trim().length > 0) {
                        // 追加一个新行文本节点
                        var br = document.createElement('br');
                        editor.appendChild(br);
                        var textNode = document.createTextNode(text);
                        editor.appendChild(textNode);
                    } else {
                        editor.innerText = text;
                    }
                    // 将光标移到末尾
                    var range = document.createRange();
                    range.selectNodeContents(editor);
                    range.collapse(false);
                    var sel = window.getSelection();
                    sel.removeAllRanges();
                    sel.addRange(range);
                    ['input', 'change', 'keyup', 'keydown'].forEach(function(eventType) {
                        editor.dispatchEvent(new Event(eventType, { bubbles: true, cancelable: true }));
                    });
                }
                tryAppend();
            })()
        """.trimIndent()

        try {
            cefBrowser.executeJavaScript(js, "", 0)
            thisLogger().info("[AddToPromptAction] JS injection executed")
        } catch (e: Exception) {
            thisLogger().warn("[AddToPromptAction] JS injection failed: ${e.message}")
        }
    }

    // === IdeaVim 兼容代码 ===

    private fun isIdeaVimInstalled(): Boolean {
        return try {
            PluginManagerCore.getPlugin(PluginId.getId("IdeaVIM")) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通过反射调用 IdeaVim 的内部 API，获取 visual mode 下的选中文本。
     *
     * 调用链: VimPlugin.getInstance() → injector → vimState → mode
     * 如果当前处于 VISUAL 模式，通过 VimEditor 获取选中范围。
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun getIdeaVimVisualSelection(editor: Editor?): Triple<String, Int, Int>? {
        if (editor == null) return null
        return try {
            val vimPluginClass = Class.forName("com.maddyhome.idea.vim.VimPlugin")
            val isEnabled = vimPluginClass.getMethod("isEnabled").invoke(null) as Boolean
            if (!isEnabled) return null

            val instance = vimPluginClass.getMethod("getInstance").invoke(null)

            val injector = try {
                instance::class.java.getMethod("getInjector").invoke(instance)
            } catch (_: NoSuchMethodException) {
                val field = instance::class.java.getDeclaredField("injector")
                field.isAccessible = true
                field.get(instance)
            }

            if (injector == null) return null

            val vimState = injector::class.java.getMethod("getVimState").invoke(injector)
            val mode = vimState::class.java.getMethod("getMode").invoke(vimState)
            val modeClassName = mode::class.java.name ?: ""

            if (!modeClassName.contains("VISUAL", ignoreCase = true)) return null

            val ijVimEditorClass = Class.forName("com.maddyhome.idea.vim.vimscript.model.IjVimEditor")
            val vimEditor = ijVimEditorClass.getConstructor(Editor::class.java).newInstance(editor)

            val textRangeClass = Class.forName("com.maddyhome.idea.vim.api.VimTextRange")
            val getSelectionMethod = vimEditor::class.java.getMethod("getSelection")
            val textRange = getSelectionMethod.invoke(vimEditor)

            if (textRange == null) return null

            val startOffset = textRange::class.java.getMethod("getStartOffset").invoke(textRange) as Int
            val endOffset = textRange::class.java.getMethod("getEndOffset").invoke(textRange) as Int

            if (startOffset < 0 || endOffset <= startOffset || endOffset > editor.document.textLength) return null

            val text = editor.document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
            if (text.isBlank()) return null

            Triple(text, startOffset, endOffset)
        } catch (e: Exception) {
            thisLogger().warn("[AddToPromptAction] IdeaVim reflection failed: ${e.message}")
            null
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun isInVisualMode(editor: Editor?): Boolean {
        if (editor == null) return false
        return try {
            val vimPluginClass = Class.forName("com.maddyhome.idea.vim.VimPlugin")
            val isEnabled = vimPluginClass.getMethod("isEnabled").invoke(null) as Boolean
            if (!isEnabled) return false

            val instance = vimPluginClass.getMethod("getInstance").invoke(null)
            val injector = try {
                instance::class.java.getMethod("getInjector").invoke(instance)
            } catch (_: NoSuchMethodException) {
                val field = instance::class.java.getDeclaredField("injector")
                field.isAccessible = true
                field.get(instance)
            } ?: return false

            val vimState = injector::class.java.getMethod("getVimState").invoke(injector)
            val mode = vimState::class.java.getMethod("getMode").invoke(vimState)
            mode::class.java.name.contains("VISUAL", ignoreCase = true)
        } catch (e: Exception) {
            thisLogger().warn("[AddToPromptAction] isInVisualMode failed: ${e.message}")
            false
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun exitVisualMode(editor: Editor?) {
        if (editor == null) return
        try {
            val vimPluginClass = Class.forName("com.maddyhome.idea.vim.VimPlugin")
            val instance = vimPluginClass.getMethod("getInstance").invoke(null)
            val injector = try {
                instance::class.java.getMethod("getInjector").invoke(instance)
            } catch (_: NoSuchMethodException) {
                val field = instance::class.java.getDeclaredField("injector")
                field.isAccessible = true
                field.get(instance)
            } ?: return

            val vimState = injector::class.java.getMethod("getVimState").invoke(injector)
            val exitMethod = vimState::class.java.getMethod("exitVisualMode")
            exitMethod.invoke(vimState)
            thisLogger().info("[AddToPromptAction] Exited visual mode via reflection")
        } catch (e: Exception) {
            thisLogger().warn("[AddToPromptAction] exitVisualMode failed: ${e.message}")
        }
    }
}