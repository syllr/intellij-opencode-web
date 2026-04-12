# JCEF 外部链接处理 - 学习笔记

## 问题
在 IntelliJ JCEF 中点击 OpenCode 的外部链接时，链接在 JCEF 内部打开，而不是系统浏览器。

## 根因
1. OpenCode markdown 渲染器强制为所有链接添加 `target="_blank"`
2. `target="_blank"` 在 JavaScript 点击事件**之前**触发弹出窗口创建
3. JavaScript 拦截 `window.open` 时机太晚

## 解决方案
使用 **CefLifeSpanHandler.onBeforePopup()** 拦截弹出窗口创建：
- 在弹出窗口创建**之前**被调用
- 可以完全取消创建
- 然后用 `BrowserUtil.browse()` 在系统浏览器打开

## 关键代码
```kotlin
private inner class ExternalLinkLifeSpanHandler : CefLifeSpanHandlerAdapter() {
    override fun onBeforePopup(...): Boolean {
        if (isExternalUrl(target_url)) {
            BrowserUtil.browse(URI(target_url))
            return true  // 取消内部弹窗
        }
        return false
    }
}

// 注册到 sharedClient
sharedClient.addLifeSpanHandler(ExternalLinkLifeSpanHandler(), browser.cefBrowser)
```

## 外部链接判断
```kotlin
private fun isExternalUrl(url: String?): Boolean {
    val host = URI(url).host ?: return false
    val scheme = URI(url).scheme ?: return false
    if (scheme !in listOf("http", "https")) return false
    if (host == "localhost" || host == "127.0.0.1" || 
        host.startsWith("192.168.") || host.startsWith("10.") ||
        host == "0.0.0.0" || host == HOST) return false
    return true
}
```

## 参考
- IntelliJ JCEF 文档: https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html
- CefLifeSpanHandler: https://github.com/JetBrains/jcef/blob/master/java/org/cef/handler/CefLifeSpanHandler.java
- 官方 Markdown 示例: MarkdownJCEFHtmlPanel.kt