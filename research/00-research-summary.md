# OpenCode IntelliJ 插件调研总结

**调研时间**: 2026-05-30
**调研目标**: 全面分析 JCEF 失焦/IME 问题 + IDEA 插件功能联动机会

---

## 调研文档索引

| 序号 | 文档                                                                   | 内容                                       |
| ---- | ---------------------------------------------------------------------- | ------------------------------------------ |
| 01   | [JCEF 失焦与输入法卡顿问题调研](./01-jcef-focus-ime-issues.md)         | 问题描述、现有实现分析、根因分析、解决方案 |
| 02   | [IDEA 插件功能联动调研](./02-idea-plugin-integration-opportunities.md) | IDEA 能力与 opencode API 联动机会          |
| 03   | [JCEF 深入技术方案](./03-jcef-deep-dive-solutions.md)                  | JetBrains 源码分析、具体技术实现方案       |
| 04   | [OpenCode API 集成技术细节](./04-opencode-api-integration-details.md)  | API 架构、请求/响应格式、集成模式          |

---

## 核心发现

### 1. JCEF 失焦/IME 问题根因

**确认的根因**: 项目**缺少关键 JCEF 处理器**

| 缺失组件                | 功能                      | 影响       |
| ----------------------- | ------------------------- | ---------- |
| `CefFocusHandler`       | Swing ↔ Chromium 焦点同步 | 页面失焦   |
| `CefCompositionHandler` | IME 输入法组合事件处理    | 输入法卡顿 |
| `CefKeyboardHandler`    | 键盘事件拦截和路由        | 快捷键冲突 |

**证据**:

- 项目中 `CefFocusHandler` 相关代码：**0 处**
- 项目中 `CefCompositionHandler` 相关代码：**0 处**
- 项目中 `addFocusHandler/addCompositionHandler` 调用：**0 处**

**对比 JetBrains 官方实现**:

- `JBCefBrowser.java` 中有完整的 `CefFocusHandlerAdapter` 实现
- `JBCefInputMethodAdapter.java` 中有完整的 IME 处理

### 2. IDEA 插件功能联动机会

**已识别的 8 个高价值联动点**:

| 功能               | 复杂度 | 价值 | 依赖 API          |
| ------------------ | ------ | ---- | ----------------- |
| 代码上下文自动注入 | 低     | 高   | PsiElement        |
| 代码导航集成       | 中     | 高   | FileEditorManager |
| Git 工作流集成     | 中     | 高   | Git4Idea          |
| 自动化重构集成     | 高     | 高   | Refactoring API   |
| 调试信息集成       | 高     | 中   | XDebugger API     |
| 代码审查集成       | 中     | 中   | File API          |
| 终端命令集成       | 中     | 中   | Terminal API      |
| LSP 诊断集成       | 低     | 低   | LSP API           |

### 3. OpenCode API 能力

**API 架构**: REST + SSE + WebSocket
**核心 API 分组**: Session、Event、File、V2、Instance、PTY、MCP、Provider

**关键 API 端点**:

- `POST /session/:sessionID/message` - 发送消息（流式）
- `POST /session/:sessionID/prompt_async` - 异步发送消息
- `GET /event` - SSE 实时事件
- `GET /find` - 文本搜索 (ripgrep)
- `GET /file/content` - 读取文件

---

## 推荐实施路径

### 阶段一：JCEF 核心修复（3-5 天）

1. **实现 CefFocusHandler**
   - 在 `BrowserPanel.kt` 中注册 `addFocusHandler`
   - 处理 `onSetFocus`、`onGotFocus`、`onTakeFocus`
   - 测试焦点切换场景

2. **实现 CefCompositionHandler**
   - 在 `BrowserPanel.kt` 中注册 `addCompositionHandler`
   - 监控输入法组合状态
   - 测试中文输入法场景

### 阶段二：IDEA 联动功能（5-7 天）

1. **代码上下文注入**
   - 利用 PsiElement 获取代码上下文
   - 通过 API 发送到 opencode

2. **代码导航集成**
   - 监听 SSE 事件解析代码位置
   - 在 IDEA 中打开文件并导航

3. **Git 工作流集成**
   - AI 辅助生成 commit message
   - AI 代码审查

### 阶段三：稳定性增强（2-3 天）

1. **焦点状态监控**
   - 自动检测和恢复失焦状态

2. **IME 增强**
   - 支持多种输入法切换方式

---

## 技术实现要点

### JCEF 焦点修复示例

```kotlin
// 在 BrowserPanel.kt 中添加
sharedClient.addFocusHandler(object : CefFocusHandlerAdapter() {
    override fun onSetFocus(browser: CefBrowser?, focusSource: CefFocusHandler.FocusSource) {
        ApplicationManager.getApplication().invokeLater {
            browser?.uiComponent?.requestFocusInWindow()
        }
        return false
    }

    override fun onGotFocus(browser: CefBrowser?) {
        thisLogger().debug("[JCEF Focus] Got focus")
    }

    override fun onTakeFocus(browser: CefBrowser?, next: Boolean) {
        thisLogger().debug("[JCEF Focus] Take focus: next=$next")
    }
}, createdBrowser.cefBrowser)
```

### IDEA 代码上下文注入示例

```kotlin
class CodeContextAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = PsiDocumentManager.getInstance(project)
            .getPsiFile(editor.document) ?: return

        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return

        val context = buildString {
            appendLine("文件: ${psiFile.virtualFile?.path}")
            appendLine("行号: ${editor.document.getLineNumber(offset) + 1}")
            appendLine("符号: ${element.text}")
        }

        // 发送到 opencode
        apiClient.sendMessage(sessionID, context, project.basePath!!)
    }
}
```

---

## 风险评估

| 风险       | 影响               | 缓解措施               |
| ---------- | ------------------ | ---------------------- |
| 焦点循环   | 用户无法切换焦点   | 添加焦点请求频率限制   |
| IME 兼容性 | 某些输入法不工作   | 支持多种输入法切换方式 |
| 平台差异   | 不同 OS 行为不一致 | 平台特定处理           |
| 性能影响   | 焦点监控消耗资源   | 合理的监控间隔         |

---

## 待深入调研

- [ ] JetBrains 官方 JCEF 示例代码
- [ ] Chromium 的 NSTextInputClient 协议实现细节
- [ ] 其他 JCEF 插件的焦点处理最佳实践
- [ ] macOS Metal 渲染与 IME 的兼容性
- [ ] CefFocusHandler 的 `FocusSource` 枚举值完整列表
- [ ] 流式响应的完整处理逻辑
- [ ] WebSocket 连接的实现细节
- [ ] 认证机制的具体实现

---

## 参考资源

| 资源                         | URL                                                                                                            |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------- |
| JetBrains JCEF 官方文档      | https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html                                         |
| JBCefBrowser 源码            | https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/JBCefBrowser.java            |
| JBCefInputMethodAdapter 源码 | https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/JBCefInputMethodAdapter.java |
| CEF Focus Handler 文档       | https://bitbucket.org/chromiumembedded/cef/wiki/GeneralUsage.md                                                |
| Apple NSTextInputClient      | https://developer.apple.com/documentation/appkit/nstextinputclient                                             |
| OpenCode API 文档            | 运行 opencode 后访问 GET /doc                                                                                  |
| IntelliJ SDK Code Samples    | https://github.com/JetBrains/intellij-sdk-code-samples                                                         |
