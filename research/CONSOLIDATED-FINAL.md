# OpenCode IntelliJ 插件调研整合报告（权威版）

**日期**: 2026-05-30
**状态**: 调研完成，准备实施

---

## 一、JCEF 失焦与输入法问题

### 1.1 根因确认

项目缺少三个关键 JCEF 处理器：

| 缺失组件                  | 功能                                            | 影响             |
| ------------------------- | ----------------------------------------------- | ---------------- |
| **FocusAdapter**          | Swing ↔ Chromium 焦点同步（OSR 模式的真正机制） | 页面失焦         |
| **CefFocusHandler**       | 焦点事件回调（辅助日志/监控）                   | 无法监控焦点状态 |
| **CefCompositionHandler** | IME 组合事件处理（可能是焦点问题的连锁反应）    | 输入法卡顿       |

### 1.2 关键发现（来自 JetBrains 源码分析）

**OSR 模式焦点同步的真正机制**：

```
JBCefBrowser.java:78-92 中的实现：

if (isOffScreenRendering()) {
    // FocusAdapter 负责焦点同步（而非 CefFocusHandler）
    myCefBrowser.getUIComponent().addFocusListener(new FocusAdapter() {
        public void focusGained(FocusEvent e) {
            myCefBrowser.setFocus(true);  // Swing 焦点 → Chromium
        }
        public void focusLost(FocusEvent e) {
            myCefBrowser.setFocus(false); // Swing 焦点丢失 → Chromium
        }
    });
}
```

**重要结论**：

- 在 OSR 模式下，`CefFocusHandler.onSetFocus()` **直接返回 `false`**，不做任何 Swing 焦点处理
- 真正的焦点同步由 **FocusAdapter** 负责
- 当前项目 `requestBrowserFocus()` 只调用 Swing `requestFocus()`，**从未调用 `browser.cefBrowser.setFocus(true)` 通知 Chromium**

### 1.3 FocusSource 枚举（仅 2 个值）

| 枚举值                    | 说明                       |
| ------------------------- | -------------------------- |
| `FOCUS_SOURCE_NAVIGATION` | 键盘导航触发（Tab 键）     |
| `FOCUS_SOURCE_SYSTEM`     | 系统事件触发（窗口激活等） |

> ⚠️ 注意：某些文档列出 5 个值（ACTIVATION, PROGRAMMATIC, BY_SYSTEM, OTHER），但 JetBrains JCEF Java 绑定实际只有 2 个值。

### 1.4 刷新页面能修复的原因

1. Chromium 渲染状态重置（重建渲染管道）
2. IME 上下文重建
3. 焦点状态重新同步

### 1.5 GPU 渲染参数对 IME 的影响

当前配置（`MyToolWindowFactory.kt:47-48`）：

```kotlin
Registry.get("ide.browser.jcef.gpu.disable").setValue(false)
System.setProperty("ide.browser.jcef.extra.args", "--enable-gpu-compositing,--use-gl=angle,--use-angle=metal")
```

**潜在问题**：

- `--enable-gpu-compositing` 创建独立渲染线程，可能引入 IME 事件处理延迟
- JetBrains 源码 `JBCefNativeOsrHandler.java` 中有 `FORCE_USE_SOFTWARE_RENDERING` workaround（IJPL-161293, IJPL-182455）
- 默认使用软件渲染，用户需显式 `-Djcef.remote.enable_hardware_rendering=true` 启用 GPU

### 1.6 已知 Bug

| Bug                              | 文件                               | Workaround                     |
| -------------------------------- | ---------------------------------- | ------------------------------ |
| CEF #1437: 浏览器矩形 0x0 崩溃   | `CefBrowserOsr.java:44`            | 初始化为 1x1 矩形              |
| macOS Java 8 Retina 缩放因子错误 | `CefBrowserOsr.java:92-114`        | 反射获取真实缩放因子           |
| macOS 不在显示时自动请求焦点     | `CefBrowserWr.java:294`            | 注释掉 `setFocus(true)`        |
| IJPL-161293/182455: GPU 渲染问题 | `JBCefNativeOsrHandler.java:24-26` | `FORCE_USE_SOFTWARE_RENDERING` |

---

## 二、IDEA 插件与 opencode 联动

### 2.1 联动机会（按优先级）

| 优先级 | 功能               | 复杂度 | 价值 | 依赖 API          |
| ------ | ------------------ | ------ | ---- | ----------------- |
| 🔴 P0  | 代码上下文自动注入 | 低     | 高   | PsiElement        |
| 🔴 P0  | 代码导航集成       | 中     | 高   | FileEditorManager |
| 🔴 P0  | Git 工作流集成     | 中     | 高   | Git4Idea          |
| 🟡 P1  | 自动化重构集成     | 高     | 高   | Refactoring API   |
| 🟡 P1  | 调试信息集成       | 高     | 中   | XDebugger API     |
| 🟡 P1  | 代码审查集成       | 中     | 中   | File API          |
| 🟢 P2  | 终端命令集成       | 中     | 中   | Terminal API      |
| 🟢 P2  | LSP 诊断集成       | 低     | 低   | LSP API           |

### 2.2 OpenCode API 核心端点

| 方法 | 路径                               | 功能             |
| ---- | ---------------------------------- | ---------------- |
| POST | `/session/:sessionID/message`      | 发送消息（流式） |
| POST | `/session/:sessionID/prompt_async` | 异步发送消息     |
| POST | `/session/:sessionID/abort`        | 中止会话         |
| GET  | `/event`                           | SSE 实时事件     |
| GET  | `/find`                            | 文本搜索         |
| GET  | `/file/content`                    | 读取文件         |
| GET  | `/session/status`                  | 获取会话状态     |

### 2.3 事件系统

**双层架构**：

- `SyncEvent`（事件溯源 + 持久化）→ `BusEvent`（实时通知 + SSE）

**关键事件**：

- `session.status` - 会话状态变化 (idle/busy/retry)
- `message.updated` - 消息更新
- `file.edited` - 文件编辑
- `permission.asked` - 权限请求

---

## 三、其他技术要点

### 3.1 工具系统

- `Tool.define` 模式定义工具
- 内置 12+ 工具（read/write/edit/grep/shell/task 等）
- 支持自定义工具和插件工具

### 3.2 Agent 系统

- 支持 primary/subagent/all 三种模式
- 基于 `Permission.Ruleset` 的权限控制
- 支持自定义 Agent（Markdown 文件）

### 3.3 配置系统

**多层合并顺序**：

```
well-known → 全局 → 项目 → .opencode → 环境变量 → Console → MDM
```

### 3.4 MCP/LSP

- MCP：标准化 AI 与外部工具通信
- LSP：代码智能（补全、跳转、诊断）

### 3.5 安全

- 凭据使用 `PasswordSafe` API
- HTTPS 强制 TLS 1.2+
- 输入验证和日志脱敏

### 3.6 部署

- Gradle IntelliJ Plugin 构建
- JetBrains Marketplace 发布
- Plugin Verifier 验证兼容性

### 3.7 国际化

- 使用自定义 `DynamicPluginBundle`
- `@PropertyKey` 注解提供编译时验证
- Unicode 转义非 ASCII 字符

---

## 四、实施计划

### 阶段一：JCEF 核心修复（1-2 天）— 最高优先级

**任务 1：实现 FocusAdapter 注册**

在 `BrowserPanel.kt` 的 `createMainTab` 中添加：

```kotlin
// 1. 注册 FocusAdapter 到 OSR 组件
val osrComponent = createdBrowser.cefBrowser.uiComponent
osrComponent?.addFocusListener(object : java.awt.event.FocusAdapter() {
    override fun focusGained(e: java.awt.event.FocusEvent) {
        createdBrowser.cefBrowser.setFocus(true)  // Swing → Chromium
        thisLogger().debug("[JCEF Focus] focusGained → setFocus(true)")
    }
    override fun focusLost(e: java.awt.event.FocusEvent) {
        createdBrowser.cefBrowser.setFocus(false) // Swing → Chromium
        thisLogger().debug("[JCEF Focus] focusLost → setFocus(false)")
    }
})
```

**任务 2：修改 requestBrowserFocus**

在 `MyToolWindow.kt` 的 `requestBrowserFocus()` 中添加 Chromium 通知：

```kotlin
fun requestBrowserFocus() {
    ApplicationManager.getApplication().invokeLater {
        try {
            val browser = browserPanel.getBrowser() ?: return@invokeLater
            val osrComponent = browser.cefBrowser.uiComponent

            osrComponent?.let { comp ->
                if (comp.isFocusable) {
                    comp.requestFocus()
                }
            }

            // ⚠️ 新增：通知 Chromium 获得焦点
            browser.cefBrowser.setFocus(true)

        } catch (e: Exception) {
            thisLogger().warn("Failed to request browser focus: ${e.message}")
        }
    }
}
```

**任务 3：添加焦点诊断日志**

在 FocusAdapter 回调中记录详细状态：

```kotlin
override fun focusGained(e: java.awt.event.FocusEvent) {
    val focusOwner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    thisLogger().info("[JCEF Focus] focusGained: focusOwner=${focusOwner?.javaClass?.simpleName}")
    createdBrowser.cefBrowser.setFocus(true)
}

override fun focusLost(e: java.awt.event.FocusEvent) {
    val focusOwner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    thisLogger().info("[JCEF Focus] focusLost: newFocusOwner=${focusOwner?.javaClass?.simpleName}")
    createdBrowser.cefBrowser.setFocus(false)
}
```

### 阶段二：im-select 配置化（0.5 天）

在 `OpenCodeConstants.kt` 中添加配置：

```kotlin
// 新增配置项
const val IM_SELECT_PATH_KEY = "opencode.im-select.path"
const val IM_SELECT_ARG_EN_KEY = "opencode.im-select.arg.en"

// 默认值
const val DEFAULT_IM_SELECT_PATH = "/usr/local/bin/im-select"
const val DEFAULT_IM_SELECT_ARG_EN = "com.apple.keylayout.ABC"
```

修改 `AddToPromptAction.kt` 读取配置而非硬编码。

### 阶段三：GPU 参数测试（0.5 天）

在 `MyToolWindowFactory.kt` 中测试禁用 GPU：

```kotlin
// 测试：禁用 GPU 观察 IME 是否改善
Registry.get("ide.browser.jcef.gpu.disable").setValue(true)
// 移除 GPU 参数
// System.setProperty("ide.browser.jcef.extra.args", ...)
```

---

## 五、调研质量总结

| 维度     | 评分       | 说明                 |
| -------- | ---------- | -------------------- |
| 覆盖度   | ⭐⭐⭐⭐⭐ | 覆盖所有关键领域     |
| 深度     | ⭐⭐⭐⭐   | JCEF 源码分析深入    |
| 一致性   | ⭐⭐⭐⭐   | 无重大冲突（已解决） |
| 可执行性 | ⭐⭐⭐⭐⭐ | 大多数建议有代码示例 |
| 重复度   | ⭐⭐⭐     | 存在重复（已整合）   |

**总结**：调研非常全面。**核心问题（JCEF 焦点）的根因已定位到源码级别**。下一步应该**停止产出新调研文档，转向实施验证**。一个 FocusAdapter 的实际实现 + 测试，比再写 10 份分析文档更有价值。
