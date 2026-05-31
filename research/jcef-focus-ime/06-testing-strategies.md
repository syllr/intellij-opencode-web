# JCEF 修复测试策略

**调研时间**: 2026-05-30
**调研目标**: 为 JCEF 焦点/IME 修复制定完整的测试策略

---

## 1. 测试目标

### 1.1 核心测试目标

| 目标       | 说明                          |
| ---------- | ----------------------------- |
| 焦点同步   | Swing ↔ Chromium 焦点完全同步 |
| IME 稳定性 | 中文输入法无卡顿、无丢失      |
| 快捷键正确 | ESC/Cmd+K/Cmd+, 正确传递      |
| 平台兼容   | macOS/Linux/Windows 行为一致  |
| 性能稳定   | 长时间使用无性能下降          |

### 1.2 测试范围

| 测试类型   | 范围                 | 优先级 |
| ---------- | -------------------- | ------ |
| 单元测试   | CefFocusHandler 逻辑 | 高     |
| 集成测试   | JCEF 焦点切换        | 高     |
| 手动测试   | IME 输入法           | 高     |
| 性能测试   | 长时间使用           | 中     |
| 兼容性测试 | 多平台               | 中     |

---

## 2. 单元测试

### 2.1 CefFocusHandler 测试

```kotlin
class CefFocusHandlerTest {

    @Test
    fun `onSetFocus should accept focus from NAVIGATION source`() {
        // 准备
        val handler = createFocusHandler()
        val browser = mockk<CefBrowser>()

        // 执行
        val result = handler.onSetFocus(browser, FocusSource.FOCUS_SOURCE_NAVIGATION)

        // 验证
        assertFalse(result) // 应该接受焦点
    }

    @Test
    fun `onSetFocus should accept focus from PROGRAMMATIC source`() {
        val handler = createFocusHandler()
        val browser = mockk<CefBrowser>()

        val result = handler.onSetFocus(browser, FocusSource.FOCUS_SOURCE_PROGRAMMATIC)

        assertFalse(result)
    }

    @Test
    fun `onGotFocus should update focus state`() {
        val handler = createFocusHandler()
        val browser = mockk<CefBrowser>()

        handler.onGotFocus(browser)

        assertTrue(handler.hasFocus())
    }

    @Test
    fun `onTakeFocus should clear focus state`() {
        val handler = createFocusHandler()
        val browser = mockk<CefBrowser>()

        handler.onGotFocus(browser)
        handler.onTakeFocus(browser, true)

        assertFalse(handler.hasFocus())
    }
}
```

### 2.2 CefCompositionHandler 测试

```kotlin
class CefCompositionHandlerTest {

    @Test
    fun `onSetComposition should track composition state`() {
        val handler = createCompositionHandler()
        val browser = mockk<CefBrowser>()

        handler.onSetComposition(
            browser,
            "测试",
            mutableMapOf(),
            0,
            0,
            2
        )

        assertTrue(handler.isComposing())
        assertEquals("测试", handler.getCompositionText())
    }

    @Test
    fun `onResetComposition should clear composition state`() {
        val handler = createCompositionHandler()
        val browser = mockk<CefBrowser>()

        handler.onSetComposition(browser, "测试", mutableMapOf(), 0, 0, 2)
        handler.onResetComposition(browser)

        assertFalse(handler.isComposing())
    }
}
```

### 2.3 焦点管理器测试

```kotlin
class FocusManagerTest {

    @Test
    fun `requestFocus should synchronize Swing and Chromium focus`() {
        val manager = createFocusManager()
        val browser = createTestBrowser()

        manager.requestFocus(browser)

        // 验证 Swing 焦点
        assertTrue(browser.component.hasFocus())

        // 验证 Chromium 焦点（通过 mock 验证）
        verify { browser.cefBrowser.setFocus(true) }
    }

    @Test
    fun `focus restoration should work after tool window switch`() {
        val manager = createFocusManager()
        val browser = createTestBrowser()

        // 模拟焦点丢失
        manager.onFocusLost(browser)
        assertFalse(manager.hasFocus(browser))

        // 恢复焦点
        manager.requestFocus(browser)
        assertTrue(manager.hasFocus(browser))
    }
}
```

---

## 3. 集成测试

### 3.1 JCEF 焦点切换测试

```kotlin
class JcefFocusIntegrationTest : BasePlatformTestCase() {

    fun testEditorToJcefFocusSwitch() {
        // 打开工具窗口
        val toolWindow = myFixture.getProjectToolWindow("OpenCodeWeb")!!
        toolWindow.activate(null)

        // 等待 JCEF 加载
        Thread.sleep(2000)

        // 获取浏览器组件
        val browser = MyToolWindowFactory.getMainBrowser(project)!!
        val browserComponent = browser.component

        // 验证焦点在 JCEF
        assertTrue(browserComponent.hasFocus())

        // 切换到编辑器
        val editor = myFixture.editor
        editor.contentComponent.requestFocusInWindow()

        // 验证焦点在编辑器
        assertTrue(editor.contentComponent.hasFocus())

        // 切换回 JCEF
        toolWindow.activate(null)
        Thread.sleep(500)

        // 验证焦点恢复到 JCEF
        assertTrue(browserComponent.hasFocus())
    }

    fun testKeyboardShortcutPassthrough() {
        // 打开工具窗口
        val toolWindow = myFixture.getProjectToolWindow("OpenCodeWeb")!!
        toolWindow.activate(null)
        Thread.sleep(2000)

        // 模拟按键
        val browser = MyToolWindowFactory.getMainBrowser(project)!!
        val browserComponent = browser.component

        // 发送 ESC 键
        val escEvent = KeyEvent(
            browserComponent,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_ESCAPE,
            KeyEvent.CHAR_UNDEFINED
        )
        browserComponent.dispatchEvent(escEvent)

        // 验证 ESC 被正确处理（不会导致焦点丢失）
        assertTrue(browserComponent.hasFocus())
    }
}
```

### 3.2 IME 输入测试

```kotlin
class ImeIntegrationTest : BasePlatformTestCase() {

    fun testChineseInputInJcef() {
        // 打开工具窗口
        val toolWindow = myFixture.getProjectToolWindow("OpenCodeWeb")!!
        toolWindow.activate(null)
        Thread.sleep(2000)

        // 获取浏览器组件
        val browser = MyToolWindowFactory.getMainBrowser(project)!!
        val browserComponent = browser.component

        // 模拟中文输入法组合事件
        val compositionEvent = InputMethodEvent(
            InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            System.currentTimeMillis()
        )

        // 验证组合事件被正确处理
        // （需要实际的输入法支持）
    }

    fun testImeSwitching() {
        // 测试输入法切换
        // 1. 切换到中文输入法
        // 2. 输入一些文本
        // 3. 切换到英文输入法
        // 4. 验证输入正常
    }
}
```

---

## 4. 手动测试

### 4.1 焦点测试场景

| 场景         | 操作                            | 预期结果                   | 验证方法 |
| ------------ | ------------------------------- | -------------------------- | -------- |
| 工具窗口切换 | 在编辑器和 OpenCodeWeb 之间切换 | 焦点正确跟随               | 手动验证 |
| ESC 按键     | 在 JCEF 中按 ESC                | ESC 传递给网页，焦点不丢失 | 手动验证 |
| Cmd+K        | 在 JCEF 中按 Cmd+K              | 快捷键传递给网页           | 手动验证 |
| Cmd+,        | 在 JCEF 中按 Cmd+,              | 快捷键传递给网页           | 手动验证 |
| 窗口最小化   | 最小化 IDE 后恢复               | 焦点状态正确恢复           | 手动验证 |
| 多项目切换   | 在多个项目之间切换              | 焦点状态正确               | 手动验证 |

### 4.2 IME 测试场景

| 场景         | 操作                     | 预期结果             | 验证方法 |
| ------------ | ------------------------ | -------------------- | -------- |
| 中文输入     | 在 JCEF 输入框中输入中文 | 无卡顿，输入流畅     | 手动测试 |
| 输入法切换   | 切换中英文输入法         | 切换正常，无残留状态 | 手动测试 |
| 长文本输入   | 输入长段中文文本         | 无延迟，响应及时     | 手动测试 |
| 输入法候选词 | 显示候选词列表           | 候选词正确显示和选择 | 手动测试 |
| 组合文本     | 输入过程中显示组合文本   | 组合文本正确显示     | 手动测试 |

### 4.3 稳定性测试场景

| 场景       | 操作                    | 预期结果         | 验证方法 |
| ---------- | ----------------------- | ---------------- | -------- |
| 长时间使用 | 连续使用 2 小时         | 无性能下降       | 手动测试 |
| 多会话切换 | 在多个 session 之间切换 | 焦点状态正确     | 手动测试 |
| 服务器重启 | 重启 opencode 服务器    | 焦点状态正确恢复 | 手动测试 |
| 内存泄漏   | 连续使用 4 小时         | 无内存泄漏       | 内存监控 |

---

## 5. 性能测试

### 5.1 焦点切换性能

```kotlin
class FocusPerformanceTest {

    @Test
    fun testFocusSwitchLatency() {
        val browser = createTestBrowser()
        val iterations = 100
        val latencies = mutableListOf<Long>()

        repeat(iterations) {
            val start = System.nanoTime()

            // 切换焦点
            browser.component.requestFocusInWindow()

            val end = System.nanoTime()
            latencies.add((end - start) / 1000000) // 转换为毫秒
        }

        val avgLatency = latencies.average()
        val maxLatency = latencies.max()

        println("平均焦点切换延迟: ${avgLatency}ms")
        println("最大焦点切换延迟: ${maxLatency}ms")

        // 验证性能要求
        assertTrue(avgLatency < 10) // 平均延迟小于 10ms
        assertTrue(maxLatency < 50) // 最大延迟小于 50ms
    }
}
```

### 5.2 IME 输入性能

```kotlin
class ImePerformanceTest {

    @Test
    fun testImeInputLatency() {
        val browser = createTestBrowser()
        val iterations = 50
        val latencies = mutableListOf<Long>()

        repeat(iterations) {
            val start = System.nanoTime()

            // 模拟输入法组合事件
            val compositionEvent = createCompositionEvent("测试文本")
            browser.cefBrowser.sendKeyEvent(compositionEvent)

            val end = System.nanoTime()
            latencies.add((end - start) / 1000000)
        }

        val avgLatency = latencies.average()
        println("平均 IME 输入延迟: ${avgLatency}ms")

        assertTrue(avgLatency < 20) // 平均延迟小于 20ms
    }
}
```

---

## 6. 兼容性测试

### 6.1 平台兼容性矩阵

| 平台    | 版本         | 状态 | 备注         |
| ------- | ------------ | ---- | ------------ |
| macOS   | 14.0+        | 必须 | 主要开发平台 |
| macOS   | 13.0         | 应该 | 兼容性测试   |
| Linux   | Ubuntu 22.04 | 必须 | CI 环境      |
| Linux   | Fedora 38    | 应该 | 社区测试     |
| Windows | 11           | 必须 | CI 环境      |
| Windows | 10           | 应该 | 社区测试     |

### 6.2 IntelliJ 版本兼容性

| IntelliJ 版本 | 状态 | 备注         |
| ------------- | ---- | ------------ |
| 2026.1        | 必须 | 当前开发版本 |
| 2025.3        | 应该 | 上一主要版本 |
| 2025.2        | 可选 | 社区测试     |

### 6.3 JCEF 版本兼容性

| JCEF 版本 | 状态 | 备注       |
| --------- | ---- | ---------- |
| 最新      | 必须 | 当前使用   |
| 前一版本  | 应该 | 兼容性测试 |

---

## 7. 测试工具和环境

### 7.1 测试工具

| 工具                          | 用途      |
| ----------------------------- | --------- |
| JUnit 4                       | 单元测试  |
| IntelliJ BasePlatformTestCase | 集成测试  |
| MockK                         | Mock 框架 |
| Robolectric                   | UI 测试   |
| JMH                           | 性能测试  |

### 7.2 测试环境

```kotlin
// 测试配置
object TestConfig {
    const val TEST_HOST = "127.0.0.1"
    const val TEST_PORT = 12396
    const val TEST_TIMEOUT_MS = 5000L
    const val TEST_FOCUS_DELAY_MS = 500L
}
```

### 7.3 测试数据

```
src/test/testData/
├── focus/
│   ├── focus-switch-test.kt
│   ├── focus-restoration-test.kt
│   └── keyboard-shortcut-test.kt
├── ime/
│   ├── chinese-input-test.kt
│   ├── ime-switching-test.kt
│   └── composition-test.kt
└── performance/
    ├── focus-performance-test.kt
    └── ime-performance-test.kt
```

---

## 8. 测试报告模板

### 8.1 测试结果报告

```markdown
# JCEF 修复测试报告

**测试日期**: 2026-05-30
**测试人员**: [姓名]
**测试环境**: macOS 14.0, IntelliJ 2026.1

## 测试结果摘要

| 测试类型 | 总数 | 通过 | 失败 | 跳过 |
| -------- | ---- | ---- | ---- | ---- |
| 单元测试 | 20   | 18   | 2    | 0    |
| 集成测试 | 10   | 8    | 2    | 0    |
| 手动测试 | 15   | 13   | 2    | 0    |

## 关键问题

1. **焦点循环问题**: 在特定场景下出现焦点循环
   - 复现步骤: [描述]
   - 影响: [描述]
   - 修复建议: [描述]

2. **IME 候选词位置错误**: 在高 DPI 屏幕上候选词位置偏移
   - 复现步骤: [描述]
   - 影响: [描述]
   - 修复建议: [描述]

## 性能数据

| 指标         | 目标值  | 实际值 | 状态 |
| ------------ | ------- | ------ | ---- |
| 焦点切换延迟 | <10ms   | 8ms    | ✅   |
| IME 输入延迟 | <20ms   | 15ms   | ✅   |
| 内存使用增长 | <10MB/h | 5MB/h  | ✅   |

## 结论

测试通过，可以发布。
```

---

## 9. 持续集成

### 9.1 CI 配置

```yaml
# .github/workflows/test.yml
name: JCEF Fix Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: "21"
          distribution: "temurin"
      - name: Run unit tests
        run: ./gradlew test

  integration-tests:
    runs-on: ${{ matrix.os }}
    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest, windows-latest]
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: "21"
          distribution: "temurin"
      - name: Run integration tests
        run: ./gradlew integrationTest
```

### 9.2 测试覆盖率

```kotlin
// build.gradle.kts
tasks.withType<Test> {
    useJUnitPlatform()

    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED"
    )

    finalizedBy jacocoTestReport
}

jacocoTestReport {
    dependsOn test
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
```

---

## 10. 测试清单

### 10.1 发布前测试清单

- [ ] 所有单元测试通过
- [ ] 所有集成测试通过
- [ ] 手动测试完成
- [ ] 性能测试通过
- [ ] 兼容性测试完成
- [ ] 测试报告生成
- [ ] 代码覆盖率 > 80%
- [ ] 无 P0/P1 缺陷

### 10.2 回归测试清单

- [ ] 焦点切换测试
- [ ] IME 输入测试
- [ ] 快捷键测试
- [ ] 多项目测试
- [ ] 长时间使用测试
