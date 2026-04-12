# 调研：JCEF 外部链接处理方案

## 问题描述
在 IntelliJ JCEF 浏览器中点击 OpenCode Web UI 的外部链接时，链接在 JCEF 内部打开，而不是在系统浏览器中打开。

## OpenCode 链接处理分析

### 1. Markdown 渲染
文件：`packages/web/src/components/share/content-markdown.tsx`

```tsx
link({ href, title, text }) {
  const titleAttr = title ? ` title="${title}"` : ""
  return `<a href="${href}"${titleAttr} target="_blank" rel="noopener noreferrer">${text}</a>`
}
```

**关键点**：OpenCode 的 markdown 渲染器**强制**为所有链接添加 `target="_blank"`。

### 2. 链接类型
- 内部链接：`http://127.0.0.1:12396/...` - 应在 JCEF 内打开
- 外部链接：如 GitHub、文档链接等 - 应在系统浏览器打开

## JCEF 链接处理机制

### Chromium 如何处理 target="_blank"
1. `<a target="_blank">` 触发 `window.open()` 调用
2. Chromium 的默认行为是在当前浏览器实例中打开新标签页
3. JCEF 继承了这个行为

### 已尝试的方案及问题

#### 方案 1：JavaScript 拦截 window.open
```javascript
window.open = function(url, target, features) {
    if (isExternal(url)) {
        window.openLink(url); // JBCefJSQuery → Kotlin
        return null;
    }
    return originalOpen.apply(window, arguments);
};
```
**问题**：可能时机太晚，Chromium 已有优化

#### 方案 2：JBCefJSQuery
- 通过 JavaScript 调用 Kotlin 代码
- 需要先劫持链接点击或 window.open

## 待研究方案

### 方案 A：CefRequestHandler.onBeforeNavigation
- 拦截所有导航事件
- 在导航发生前判断是否外部链接
- 如果是外部链接，阻止导航并用系统浏览器打开

### 方案 B：CefDisplayHandler.onAddressChange
- 监听地址变化
- 在变化后判断是否外部链接
- 如果是，停止加载并用系统浏览器打开

### 方案 C：修改 OpenCode 源码
- 移除 markdown 渲染器的 `target="_blank"`
- 但这需要维护一个 fork 版本

### 方案 D：JCEF 命令行参数
- 查找是否有启动参数控制此行为
- 例如 `--disable-popup-blocking` 等

## 参考资料

### OpenCode 源码
- `/Users/yutao/GolandProjects/opencode/packages/web/src/components/share/content-markdown.tsx`
- `/Users/yutao/GolandProjects/opencode/packages/web/src/components/share/part.tsx`

### IntelliJ JCEF 文档
- https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html

## 下一步行动
1. 完成代理调研
2. 确定最佳方案
3. 制定实施计划