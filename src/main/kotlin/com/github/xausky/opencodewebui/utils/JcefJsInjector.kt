package com.github.xausky.opencodewebui.utils

import com.github.xausky.opencodewebui.BROWSER_READY_MAX_RETRIES
import com.github.xausky.opencodewebui.BROWSER_READY_RETRY_DELAY_MS
import com.github.xausky.opencodewebui.toolWindow.MyToolWindowFactory
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * JCEF 浏览器 JavaScript 注入工具。
 * 将文本追加到 OpenCode Web 页面的输入框中。
 */
object JcefJsInjector {

    /**
     * 将文本追加到 OpenCode Web 页面的输入框。
     *
     * 快速路径——浏览器已就绪：EDT 上直接注入。
     * 慢速路径——浏览器未就绪：Alarm 非阻塞定时重试，不占用 BB 线程池。
     */
    fun appendTextToEditor(project: Project, text: String) {
        MyToolWindowFactory.openOpenCodeWebToolWindow(project)
        if (project.basePath == null) return

        // [O6] 快速路径：页面已加载完成且 browser 存在，直接注入
        if (MyToolWindowFactory.isBrowserPageLoaded(project)) {
            val browser = MyToolWindowFactory.getMainBrowser(project)
            if (browser != null) {
                executeAppend(browser.cefBrowser, text)
                return
            }
        }

        scheduleRetry(project, text, retryCount = 0)
    }

    private fun scheduleRetry(project: Project, text: String, retryCount: Int) {
        if (retryCount >= BROWSER_READY_MAX_RETRIES) {
            thisLogger().warn("[JcefJsInjector] browser not ready after $retryCount retries, giving up")
            return
        }

        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
        alarm.addRequest({
            if (project.isDisposed) return@addRequest

            // [O6] 检查 pageLoaded 标志，页面就绪时 browser 一定存在
            if (MyToolWindowFactory.isBrowserPageLoaded(project)) {
                val browser = MyToolWindowFactory.getMainBrowser(project)
                if (browser != null) {
                    executeAppend(browser.cefBrowser, text)
                    return@addRequest
                }
            }
            scheduleRetry(project, text, retryCount + 1)
        }, BROWSER_READY_RETRY_DELAY_MS)
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
