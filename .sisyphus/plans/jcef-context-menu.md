# JCEF 右键菜单添加"复制链接"计划

## 问题描述
在 IntelliJ JCEF 浏览器中点击外部链接时，链接在 JCEF 内部打开。需要添加右键菜单"复制链接"选项，让用户可以复制链接后手动粘贴到系统浏览器。

## 解决方案

使用 **CefContextMenuHandler** 在 JCEF 右键菜单中添加"复制链接"选项。

### 技术方案

#### 1. CefContextMenuHandler 实现
```kotlin
private inner class LinkContextMenuHandler : CefContextMenuHandlerAdapter() {
    override fun onBeforeContextMenu(
        browser: CefBrowser,
        frame: CefFrame,
        params: CefContextMenuParams
    ) {
        // 获取链接 URL
        val linkUrl = params.linkUrl
        
        // 如果有链接 URL，添加"复制链接"菜单项
        if (!linkUrl.isNullOrEmpty()) {
            // menu 是 CefMenuModel
            // menu.addItem(id, "复制链接")
        }
    }
    
    override fun onContextMenuCommand(
        browser: CefBrowser,
        frame: CefFrame,
        params: CefContextMenuParams,
        commandId: Int,
        eventFlags: Int
    ): Boolean {
        if (commandId == COPY_LINK_COMMAND_ID) {
            // 复制链接到剪贴板
            val linkUrl = params.linkUrl
            if (!linkUrl.isNullOrEmpty()) {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val selection = StringSelection(linkUrl)
                clipboard.setContents(selection, selection)
            }
            return true
        }
        return false
    }
}
```

#### 2. 注册处理器
```kotlin
// 在创建浏览器时注册
sharedClient.addContextMenuHandler(LinkContextMenuHandler(), browser.cefBrowser)
```

---

## 工作计划

### 任务 1：添加必要的 import

**文件**: `MyToolWindowFactory.kt`

添加 import：
- `org.cef.handler.CefContextMenuHandlerAdapter`
- `org.cef.handler.CefContextMenuParams`
- `org.cef.menu.CefMenuModel`
- `java.awt.Toolkit`
- `java.awt.datatransfer.StringSelection`
- `java.awt.datatransfer.Clipboard`

### 任务 2：定义菜单命令 ID

**文件**: `MyToolWindowFactory.kt`

在 `TabbedBrowserPanel` 类中添加：
```kotlin
companion object {
    private const val COPY_LINK_COMMAND_ID = 26500  // 自定义命令 ID
}
```

### 任务 3：实现 LinkContextMenuHandler 类

**文件**: `MyToolWindowFactory.kt`

在 `TabbedBrowserPanel` 类中添加内部类：
```kotlin
private inner class LinkContextMenuHandler : CefContextMenuHandlerAdapter() {
    override fun onBeforeContextMenu(
        browser: CefBrowser,
        frame: CefFrame,
        params: CefContextMenuParams
    ) {
        // 不需要修改默认菜单
    }
    
    override fun onContextMenuCommand(
        browser: CefBrowser,
        frame: CefFrame,
        params: CefContextMenuParams,
        commandId: Int,
        eventFlags: Int
    ): Boolean {
        if (commandId == COPY_LINK_COMMAND_ID) {
            val linkUrl = params.linkUrl
            if (!linkUrl.isNullOrEmpty()) {
                copyToClipboard(linkUrl)
            }
            return true
        }
        return false
    }
    
    private fun copyToClipboard(text: String) {
        try {
            val selection = StringSelection(text)
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(selection, selection)
        } catch (e: Exception) {
            thisLogger().warn("Failed to copy to clipboard", e)
        }
    }
}
```

### 任务 4：在创建浏览器时注册处理器

**文件**: `MyToolWindowFactory.kt`

修改 `createMainTab` 和 `addNewTab` 方法：
```kotlin
fun createMainTab(title: String, url: String): JBCefBrowser {
    val browser = JBCefBrowserBuilder().setClient(sharedClient).setUrl(url).build()
    browsers.add(browser)
    tabbedPane.addTab(title, browser.component)
    sharedClient.addLifeSpanHandler(ExternalLinkLifeSpanHandler(), browser.cefBrowser)
    sharedClient.addContextMenuHandler(LinkContextMenuHandler(), browser.cefBrowser)
    injectLinkInterceptor(browser)
    return browser
}
```

### 任务 5：简化 JavaScript 注入

**原因**: `CefContextMenuHandler` 已经可以获取链接 URL，JavaScript 拦截不再需要获取链接。

简化 `injectLinkInterceptor`，移除 JavaScript 点击监听器。

### 任务 6：验证构建和测试

**验证**:
- `./gradlew buildPlugin` 成功
- `./gradlew check` 通过
- 手动测试：右键点击链接 → 应看到"复制链接"选项

---

## 参考资料

### CefContextMenuHandler
- `onBeforeContextMenu`: 在显示右键菜单前调用
- `onContextMenuCommand`: 当用户点击菜单项时调用
- `CefContextMenuParams.linkUrl`: 获取链接 URL

### CSDN 参考
- JCEF 禁用默认菜单项：`model.clear()`
- 添加自定义菜单项：`model.addItem(commandId, label)`

---

## 备选方案

如果 `CefContextMenuHandler` 在 IntelliJ JCEF 中不可用，可以：
1. 使用 IntelliJ 的 Action 系统添加"复制链接"到 EditorPopupMenu
2. 但这不会局限于 JCEF 浏览器内

---

## 测试场景

1. 在 JCEF 中右键点击链接 → 出现"复制链接"选项
2. 点击"复制链接" → 链接 URL 复制到剪贴板
3. 粘贴到系统浏览器 → 成功打开页面