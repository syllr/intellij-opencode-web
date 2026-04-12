# OpenCode Web UI 插件重构工作计划

## TL;DR

> **目标**: 修复 IntelliJ 插件代码审查中发现的严重问题（JCEF 资源泄漏、线程安全、静态全局状态）
>
> **交付物**: 可编译、可打包的插件，无破坏性变更
>
> **预估工作量**: 中等（约 4-6 小时，分 3 个 Wave）

---

## 上下文

### 审查发现的问题

| 严重程度 | 问题 | 影响 |
|---------|------|------|
| 🔴 CRITICAL | JBCefBrowser 创建前未检查 `JBCefApp.isSupported()` | 在不支持 JCEF 的环境中崩溃 |
| 🔴 CRITICAL | JBCefBrowser 资源从未释放 | 内存泄漏 |
| 🔴 CRITICAL | `invokeAndWait` 在后台线程调用 | 可能死锁 |
| 🔴 CRITICAL | 多线程访问可变静态变量无同步 | 数据竞争 |
| 🟡 MEDIUM | 监听器添加后从不移除 | 累积内存泄漏 |
| 🟡 MEDIUM | 缺少 `pluginUntilBuild` | 插件可能安装在不兼容 IDE 版本 |
| 🟢 LOW | keymap ID `Mac OS X` 过时 | 新版 IDE 快捷键失效 |

### Oracle 咨询结论

1. **JBCefBrowser 释放**: 使用 `Disposer.register(project, browser)` 绑定到项目生命周期
2. **invokeAndWait**: 必须改为 `invokeLater`，或在 EDT 外调用 `browser.dispose()`
3. **线程安全**: 暂时用 `AtomicBoolean`/`AtomicReference`，最终迁移到 `ProjectService`
4. **键盘处理**: 当前 Swing KeyListener 足够，CefKeyboardHandler 用于拦截内部快捷键

---

## 工作目标

### 必须修复 (Must Fix)
- [ ] JBCefBrowser 资源泄漏
- [ ] 线程安全问题
- [ ] invokeAndWait 死锁风险
- [ ] 缺少 pluginUntilBuild

### 应该修复 (Should Fix)
- [ ] JBCefApp.isSupported() 检查
- [ ] 监听器移除

### 可选修复 (Nice to Have)
- [ ] 重构为 ProjectService
- [ ] Actions 菜单注册
- [ ] keymap ID 更新

---

## 执行策略

### 编译安全保障

1. **每个 Wave 独立可编译**: 每阶段修改后立即 `./gradlew buildPlugin` 验证
2. **小步提交**: 每个子任务完成后提交，保持可回滚
3. **保留备份**: 修改前记录原始代码结构

### Wave 划分原则

- Wave 1: 最小风险修复（不影响核心逻辑）
- Wave 2: 核心逻辑修复（线程安全、资源释放）
- Wave 3: 配置和清理

---

## Wave 1: 配置修复 + 最小风险修复

### T1.1: 添加 pluginUntilBuild

**文件**: `gradle.properties`

**修改**:
```properties
# 在 pluginSinceBuild 后添加
pluginUntilBuild = 254.*
```

**验证**: `./gradlew buildPlugin` && `./gradlew verifyPlugin`

**提交**: `fix: 添加 pluginUntilBuild 限制兼容 IDE 版本范围`

---

### T1.2: 添加 JBCefApp.isSupported() 检查

**文件**: `MyToolWindowFactory.kt` (约 line 200-220)

**修改**:
```kotlin
// 在 MyToolWindow 构造函数中添加
init {
    if (!JBCefApp.isSupported()) {
        // 显示错误消息或使用备用方案
        showJcefNotSupportedMessage()
        return
    }
    // ... 原有初始化逻辑
}

private fun showJcefNotSupportedMessage() {
    // 显示友好错误消息
}
```

**验证**: `./gradlew buildPlugin`

**提交**: `fix: 添加 JBCefApp.isSupported() 检查`

---

### T1.3: 将 invokeAndWait 改为 invokeLater

**文件**: `MyToolWindowFactory.kt` (约 line 381)

**修改**:
```kotlin
// 原来
ApplicationManager.getApplication().invokeAndWait {
    checkServerHealth()
}

// 改为
ApplicationManager.getApplication().invokeLater {
    checkServerHealth()
}
```

**注意**: `invokeLater` 不阻塞，但检查结果可能是异步的。这是可接受的，因为健康检查只是更新 UI 状态。

**验证**: `./gradlew buildPlugin`

**提交**: `fix: invokeAndWait 改为 invokeLater 避免死锁`

---

## Wave 2: 核心资源管理和线程安全

### T2.1: 添加 JBCefBrowser Disposer 注册

**文件**: `MyToolWindowFactory.kt` (约 line 212-219)

**修改**:
```kotlin
// 需要新增 import
import com.intellij.openapi.util.Disposer

inner class MyToolWindow(toolWindow: ToolWindow) {
    private val browser: JBCefBrowser
    
    init {
        browser = JBCefBrowser()
        // 注册到 Disposer，在项目关闭时自动释放
        Disposer.register(toolWindow.project, browser)
        
        browserInstance = browser
        setupWindowFocusListener(toolWindow)
        // ...
    }
}
```

**验证**: `./gradlew buildPlugin`

**提交**: `fix: JBCefBrowser 注册到 Disposer 自动管理生命周期`

---

### T2.2: 将静态可变变量改为原子变量

**文件**: `MyToolWindowFactory.kt` (约 line 36-44)

**新增 import**:
```kotlin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
```

**修改 companion object**:
```kotlin
companion object {
    private const val PORT = 12396
    private const val HOST = "127.0.0.1"

    // 改为原子变量
    private val browserInstance = AtomicReference<JBCefBrowser?>(null)
    private val serverRunning = AtomicBoolean(false)
    private val serverProcess = AtomicReference<Process?>(null)
    private var checkScheduledFuture: ScheduledFuture<*>? = null
    private val hasInitializedOnStartup = AtomicBoolean(false)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "OpenCode-Server-Checker")
    }
    
    // 更新所有引用处
    fun stopServer() {
        // ...
        serverRunning.set(false)
        serverProcess.set(null)
        // ...
    }
    
    fun restartServer(project: Project?) {
        // ...
        if (browserInstance.get() == null) {  // 改 .get()
        // ...
        browserInstance.get()?.cefBrowser?.reload()  // 改 .get()
    }
}
```

**重要**: 需要更新所有引用这些变量的地方，从 `var` 访问改为 `.get()`/`.set()` 调用。

**验证**: `./gradlew buildPlugin` → 如果编译错误，检查是否有遗漏的变量引用

**提交**: `refactor: 静态可变变量改为 AtomicBoolean/AtomicReference`

---

### T2.3: 添加关键位置的 volatile（仅用于 Boolean 标志）

如果 T2.2 之后仍有编译问题，且问题与可见性相关，可以在特定变量上加 `volatile`:
```kotlin
@volatile private var hasInitializedOnStartup = false
```

但最好使用 AtomicBoolean，因为它们提供了更好的 API。

---

## Wave 3: 监听器管理和清理

### T3.1: 跟踪并移除监听器

**问题**: 监听器添加到组件后从未移除。

**分析**: 需要先理解当前监听器添加逻辑，然后在 MyToolWindow dispose 时移除。

**修改** (约 line 223-233):
```kotlin
inner class MyToolWindow(toolWindow: ToolWindow) : Disposable {
    
    private val listeners = mutableListOf<java.util.EventListener>()
    
    init {
        // ...
    }
    
    // 添加一个方法来清理所有监听器
    private fun cleanupListeners() {
        // 移除 hierarchyListener
        // 移除 windowFocusListener
        // 移除 keyListener
    }
    
    override fun dispose() {
        cleanupListeners()
        // browser 已被 Disposer 管理，这里不需要手动 dispose
    }
}
```

**验证**: `./gradlew buildPlugin`

**提交**: `fix: 添加监听器清理逻辑`

---

### T3.2: Scheduler 正确关闭

**文件**: `MyApplicationDisposable.kt` 或创建新的 Disposable

**修改**: 在应用关闭时关闭 scheduler:
```kotlin
class MyApplicationDisposable : Disposable {
    override fun dispose() {
        MyToolWindowFactory.scheduler.shutdownNow()
    }
}
```

**验证**: `./gradlew buildPlugin`

**提交**: `fix: 应用关闭时正确关闭 Scheduler`

---

## 最终验证

### 验证命令

```bash
# 1. 编译和打包
./gradlew buildPlugin

# 2. 验证插件结构
./gradlew verifyPlugin

# 3. 运行检查（测试 + Qodana）
./gradlew check

# 4. 本地运行测试
./gradlew runIde
```

### 成功标准

- [ ] `./gradlew buildPlugin` 成功，无编译错误
- [ ] `./gradlew verifyPlugin` 无警告或错误
- [ ] `./gradlew check` 测试全部通过
- [ ] 插件可以在本地 IDE 中正常加载

---

## 风险缓解

### 如果编译失败

1. **保留原始文件备份**: 在每个子任务前记录 `git diff`
2. **小步回滚**: `git checkout -- file` 回滚到上一个可编译状态
3. **分步调试**: 逐个子任务验证，不要一次性修改太多

### 回滚计划

如果 Wave 2 引入编译错误且无法快速修复:
1. `git stash` 暂存 Wave 2 修改
2. 回退到 Wave 1 状态
3. 逐个引入 T2.x 修改，定位问题

---

## 时间估算

| Task | 预估时间 | 风险 |
|------|---------|------|
| T1.1 pluginUntilBuild | 5 min | 低 |
| T1.2 isSupported 检查 | 15 min | 低 |
| T1.3 invokeAndWait | 5 min | 低 |
| T2.1 Disposer 注册 | 15 min | 中 |
| T2.2 原子变量 | 60-90 min | 中 |
| T2.3 volatile (如需要) | 15 min | 低 |
| T3.1 监听器清理 | 30 min | 中 |
| T3.2 Scheduler 关闭 | 15 min | 低 |

**总计**: 约 3-4 小时（不包括调试时间）

---

## 提交信息规范

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

类型: `fix`, `refactor`, `config`, `docs`

示例:
```
fix(toolWindow): 修复 JBCefBrowser 资源泄漏

- 添加 JBCefApp.isSupported() 检查
- 注册 browser 到 Disposer 自动管理生命周期
- 静态变量改为 AtomicReference/AtomicBoolean
- invokeAndWait 改为 invokeLater
```

---

## 后续建议（非本次范围）

1. **创建 OpenCodeWebService**: 替代 companion object 静态状态
2. **拆分 MyToolWindowFactory**: 482 行拆分为多个类
3. **Actions 菜单注册**: 添加 `<add-to-group>` 元素
4. **更新 keymap ID**: `Mac OS X` → `macOS`
5. **SQLite 迁移**: 使用 IntelliJ 持久化机制替代 JDBC
