package com.github.xausky.opencodewebui.utils

import com.github.xausky.opencodewebui.BROWSER_READY_MAX_RETRIES
import com.github.xausky.opencodewebui.BROWSER_READY_RETRY_DELAY_MS
import com.github.xausky.opencodewebui.toolWindow.MyToolWindowFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * JCEF 浏览器 JavaScript 注入工具。
 * 将文本追加到 OpenCode Web 页面的输入框中。
 */
object JcefJsInjector {
    private const val MAX_RETRY_DEPTH = 3

    /**
     * 将文本追加到 OpenCode Web 页面的输入框。
     *
     * 快速路径——浏览器已就绪：EDT 上直接注入。
     * 慢速路径——浏览器未就绪：后台线程轮询等待，找到后 invokeLater 回 EDT 注入。
     * 绝不阻塞 EDT。
     *
     * @param depth 内部递归重试深度，调用方无需传此参数
     */
    fun appendTextToEditor(project: Project, text: String, depth: Int = 0) {
        if (depth > MAX_RETRY_DEPTH) {
            thisLogger().warn("[JcefJsInjector] max retry depth ($MAX_RETRY_DEPTH) reached, giving up")
            return
        }

        MyToolWindowFactory.openOpenCodeWebToolWindow(project)

        val projectPath = project.basePath ?: return

        // 快速路径：浏览器已就绪 → EDT 直接注入
        val browser = MyToolWindowFactory.getMainBrowser(project)
        if (browser != null) {
            executeAppend(browser.cefBrowser, text)
            return
        }

        // 慢速路径：浏览器未就绪 → 后台线程轮询，不阻塞 EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            for (i in 1..BROWSER_READY_MAX_RETRIES) {
                val b = MyToolWindowFactory.getMainBrowser(project)
                if (b != null) {
                    val cefBrowser = b.cefBrowser
                    ApplicationManager.getApplication().invokeLater({
                                            // 注：cefBrowser 在后台线程捕获，invokeLater 到 EDT 时可能已过时，
                                            // 此处二次校验确保浏览器引用仍然可用
                                            val current = MyToolWindowFactory.getMainBrowser(project)
                                            if (current?.cefBrowser !== cefBrowser) {
                                                if (depth > MAX_RETRY_DEPTH) {
                                                    thisLogger().warn("[JcefJsInjector] max retry depth ($MAX_RETRY_DEPTH) reached, giving up")
                                                    return@invokeLater
                                                }
                                                thisLogger().warn("[JcefJsInjector] browser changed between capture and inject, re-trying (depth=${depth + 1})")
                                                appendTextToEditor(project, text, depth + 1)
                                                return@invokeLater
                                            }
                                            executeAppend(cefBrowser, text)
                                        }, ModalityState.any())
                    return@executeOnPooledThread
                }
                Thread.sleep(BROWSER_READY_RETRY_DELAY_MS)
            }
            thisLogger().warn("[JcefJsInjector] browser not ready after retries")
        }
    }

    private fun executeAppend(cefBrowser: org.cef.browser.CefBrowser, text: String) {
        val escapedText = text
    .replace("\\", "\\\\")
    .replace("'", "\\'")
    .replace("`", "\\`")
    .replace("\n", "\\n")
    .replace("\r", "")
        val js = """
            (function() {
                var attempt = 0;
                function tryAppend() {
                    var editor = document.querySelector('[role="textbox"][contenteditable="true"]');
                    if (!editor) {
                        if (attempt < 100) {
                            attempt++;
                            setTimeout(tryAppend, 100);
                        }
                        return;
                    }
                    var text = '$escapedText';
                    var hasContent = editor.innerText.trim().length > 0;
                    if (hasContent) {
                        // 已有内容，在新行追加
                        var textNode = document.createTextNode('\n' + text);
                        editor.appendChild(textNode);
                    } else {
                        // 空编辑器，先清除占位 <br> 再设置内容
                        editor.innerHTML = '';
                        editor.innerText = text;
                    }
                    // 注意：不追加 <br> 元素。
                    // formatAsPrompt 输出的末尾已自带 \n  （换行 + 2 个空格），
                    // 它在 contenteditable 中渲染为一个可见空白行。
                    // 追加 <br> 会产生额外空行，导致末尾多出空白（历史教训）。
                    // 直接将光标移到编辑器内容末尾即可。
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
            thisLogger().info("[JcefJsInjector] JS injection executed")
        } catch (e: Exception) {
            thisLogger().warn("[JcefJsInjector] JS injection failed: ${e.message}")
        }
    }
}
