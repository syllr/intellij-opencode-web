# JCEF 外部链接处理修复计划

## 问题描述
在 IntelliJ JCEF 浏览器中点击 OpenCode Web UI 的外部链接时，链接在 JCEF 内部打开，而不是在系统浏览器中打开。

## 根因分析

### OpenCode 链接渲染
- 文件：`packages/web/src/components/share/content-markdown.tsx`
- 所有 Markdown 链接**无条件**添加 `target="_blank"`
- 没有区分外部链接和内部链接

### JCEF 处理机制
1. `target="_blank"` 触发 Chromium 创建新弹出窗口
2. 这个过程发生在 JavaScript 点击事件**之前**
3. 因此 JavaScript 拦截 `window.open` **时机太晚**

### 解决方案
使用 **CefLifeSpanHandler.onBeforePopup()** 拦截弹出窗口创建，在窗口打开前判断是否为外部链接，如果是则取消并在系统浏览器打开。

---

## 工作计划

### 任务 1：实现 CefLifeSpanHandler 拦截器

**文件**: `MyToolWindowFactory.kt`

**修改内容**:
1. 添加 import：
   - `org.cef.browser.CefBrowser`
   - `org.cef.browser.CefFrame`
   - `org.cef.handler.CefLifeSpanHandlerAdapter`

2. 在 `TabbedBrowserPanel` 类中添加内部类：
```kotlin
private inner class ExternalLinkLifeSpanHandler : CefLifeSpanHandlerAdapter() {
    override fun onBeforePopup(
        browser: CefBrowser,
        frame: CefFrame,
        target_url: String,
        target_frame_name: String
    ): Boolean {
        if (isExternalUrl(target_url)) {
            ApplicationManager.getApplication().invokeLater {
                BrowserUtil.browse(java.net.URI(target_url))
            }
            return true  // 取消 JCEF 内部弹窗
        }
        return false  // 允许内部弹窗
    }
    
    private fun isExternalUrl(url: String?): Boolean {
        if (url == null) return false
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return false
            val scheme = uri.scheme ?: return false
            
            // 只处理 http/https 链接
            if (scheme !in listOf("http", "https")) return false
            
            // 检查是否为本地地址
            if (host == "localhost" || host == "127.0.0.1" || 
                host.startsWith("192.168.") || host.startsWith("10.") ||
                host == "0.0.0.0" || host == HOST) {
                return false
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
}
```

3. 在 `TabbedBrowserPanel.init` 中注册处理器：
```kotlin
init {
    layout = java.awt.BorderLayout()
    add(tabbedPane, java.awt.BorderLayout.CENTER)
    
    // 注册生命周期处理器以拦截外部链接
    sharedClient.addLifeSpanHandler(ExternalLinkLifeSpanHandler(), browser.cefBrowser)
}
```

**验收标准**:
- 构建成功
- 点击 GitHub 等外部链接时在系统浏览器打开
- 点击 OpenCode 内部链接时在 JCEF 内正常跳转

---

### 任务 2：简化 JavaScript 拦截代码

**原因**: CEF 处理器接管后，JavaScript 拦截变得不必要，可以移除。

**修改**: 将 `injectLinkInterceptor` 中的 `window.open` 劫持代码简化为只保留点击事件拦截（用于内部链接的日志等）。

---

### 任务 3：验证和测试

**测试场景**:
1. 点击 OpenCode 内部导航链接 → 应在 JCEF 内跳转
2. 点击 AI 回复中的 GitHub 链接 → 应在系统 Chrome 打开
3. 点击文档链接 → 应在系统浏览器打开
4. 点击邮件链接 (mailto:) → 应打开邮件客户端

---

## 技术细节

### CefLifeSpanHandler vs CefRequestHandler

| Handler | 触发时机 | 适用场景 |
|---------|---------|---------|
| `CefLifeSpanHandler.onBeforePopup()` | **弹出窗口创建前** | `target="_blank"`, `window.open()` ✅ 推荐 |
| `CefRequestHandler.onOpenURLFromTab()` | 从标签页打开 URL | 备选方案 |
| `CefRequestHandler.onBeforeBrowse()` | 导航发生前 | 更早期拦截 |

### 为什么用 onBeforePopup
- `target="_blank"` 直接触发此方法
- 时机早于 JavaScript 事件
- 可以完全取消弹出窗口创建

---

## 备选方案（如果主方案失败）

### 备选 1：onOpenURLFromTab
```kotlin
private inner class ExternalLinkRequestHandler : CefRequestHandlerAdapter() {
    override fun onOpenURLFromTab(
        browser: CefBrowser,
        frame: CefFrame,
        target_url: String,
        user_gesture: Boolean
    ): Boolean {
        if (isExternalUrl(target_url)) {
            BrowserUtil.browse(java.net.URI(target_url))
            return true  // 取消导航
        }
        return false
    }
}
```

### 备选 2：同时注册两个处理器
注册 `CefLifeSpanHandler` 和 `CefRequestHandler`，双重保险。

---

## 参考资料

- **IntelliJ JCEF 文档**: https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html
- **JCEF GitHub**: https://github.com/JetBrains/jcef
- **CefLifeSpanHandler 接口**: https://github.com/JetBrains/jcef/blob/master/java/org/cef/handler/CefLifeSpanHandler.java
- **官方示例 (Markdown)**: `intellij-community/plugins/markdown/core/src/org/intellij/plugins/markdown/ui/preview/jcef/MarkdownJCEFHtmlPanel.kt`