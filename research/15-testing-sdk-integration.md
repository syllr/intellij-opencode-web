# IntelliJ 插件测试模式和 SDK 集成详解

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件提供测试模式和 SDK 集成技术指导

---

## 1. IntelliJ 插件测试概述

### 1.1 测试类型

| 测试类型 | 说明         | 用途           |
| -------- | ------------ | -------------- |
| 单元测试 | 测试单个组件 | 验证组件逻辑   |
| 集成测试 | 测试组件协作 | 验证集成正确性 |
| UI 测试  | 测试用户界面 | 验证用户体验   |
| 性能测试 | 测试性能指标 | 验证性能要求   |

### 1.2 测试框架

| 框架                          | 说明         | 用途             |
| ----------------------------- | ------------ | ---------------- |
| JUnit 4                       | 单元测试框架 | 编写测试用例     |
| Mockito                       | Mock 框架    | 模拟依赖对象     |
| AssertJ                       | 断言库       | 编写测试断言     |
| IntelliJ BasePlatformTestCase | 集成测试基类 | 编写平台集成测试 |

---

## 2. 单元测试

### 2.1 基本测试结构

```kotlin
class MyComponentTest {

    private lateinit var component: MyComponent

    @Before
    fun setUp() {
        component = MyComponent()
    }

    @Test
    fun testSomething() {
        // 准备
        val input = "test input"

        // 执行
        val result = component.process(input)

        // 验证
        assertThat(result).isEqualTo("expected output")
    }

    @After
    fun tearDown() {
        // 清理
    }
}
```

### 2.2 使用 Mockito

```kotlin
class MyServiceTest {

    @Mock
    private lateinit var dependency: Dependency

    @InjectMocks
    private lateinit var service: MyService

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
    }

    @Test
    fun testWithMock() {
        // 准备
        `when`(dependency.getData()).thenReturn("mock data")

        // 执行
        val result = service.processData()

        // 验证
        assertThat(result).isEqualTo("processed mock data")
        verify(dependency).getData()
    }
}
```

### 2.3 测试 PsiElement

```kotlin
class PsiElementTest : BasePlatformTestCase() {

    fun testPsiElement() {
        // 准备
        val psiFile = myFixture.configureByText("Test.kt", """
            class MyClass {
                fun myMethod() {}
            }
        """)

        // 执行
        val element = psiFile.findElementAt(0)

        // 验证
        assertNotNull(element)
        assertTrue(element is PsiClass)
    }
}
```

---

## 3. 集成测试

### 3.1 BasePlatformTestCase

```kotlin
class MyPluginIntegrationTest : BasePlatformTestCase() {

    fun testToolWindowCreation() {
        // 准备
        val toolWindowManager = ToolWindowManager.getInstance(project)

        // 执行
        val toolWindow = toolWindowManager.getToolWindow("OpenCodeWeb")

        // 验证
        assertNotNull(toolWindow)
        assertTrue(toolWindow.isVisible)
    }

    fun testEditorIntegration() {
        // 准备
        myFixture.configureByText("Test.kt", """
            class MyClass {
                fun myMethod() {}
            }
        """)

        // 执行
        val editor = myFixture.editor
        val psiFile = myFixture.psiFile

        // 验证
        assertNotNull(editor)
        assertNotNull(psiFile)
    }
}
```

### 3.2 测试 Action

```kotlin
class MyActionTest : BasePlatformTestCase() {

    fun testAction() {
        // 准备
        myFixture.configureByText("Test.kt", """
            class MyClass {
                fun myMethod() {}
            }
        """)

        // 执行
        val action = MyAction()
        val event = AnActionEvent.createFromDataContext(
            ActionPlaces.EDITORPopup,
            null,
            DataContext.EMPTY_CONTEXT
        )

        // 验证
        action.update(event)
        assertTrue(event.presentation.isEnabled)
    }
}
```

### 3.3 测试 Notification

```kotlin
class NotificationTest : BasePlatformTestCase() {

    fun testNotification() {
        // 准备
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("OpenCodeWeb.notifications")

        // 执行
        val notification = notificationGroup.createNotification(
            "Test Title",
            "Test Content",
            NotificationType.INFORMATION
        )

        // 验证
        assertNotNull(notification)
    }
}
```

---

## 4. Mock IntelliJ 服务

### 4.1 Mock Project

```kotlin
class MyServiceTest {

    @Test
    fun testWithMockProject() {
        // 准备
        val project = mockk<Project>()
        val service = MyService(project)

        // 执行
        val result = service.doSomething()

        // 验证
        assertNotNull(result)
    }
}
```

### 4.2 Mock Editor

```kotlin
class EditorTest {

    @Test
    fun testWithMockEditor() {
        // 准备
        val editor = mockk<Editor>()
        val document = mockk<Document>()

        every { editor.document } returns document
        every { document.text } returns "test content"

        // 执行
        val service = EditorService(editor)
        val result = service.getContent()

        // 验证
        assertThat(result).isEqualTo("test content")
    }
}
```

### 4.3 Mock FileEditorManager

```kotlin
class FileEditorManagerTest {

    @Test
    fun testWithMockFileEditorManager() {
        // 准备
        val project = mockk<Project>()
        val fileEditorManager = mockk<FileEditorManager>()

        every { FileEditorManager.getInstance(project) } returns fileEditorManager
        every { fileEditorManager.selectedTextEditor } returns mockk()

        // 执行
        val service = ProjectService(project)
        val editor = service.getCurrentEditor()

        // 验证
        assertNotNull(editor)
    }
}
```

---

## 5. 测试最佳实践

### 5.1 测试命名规范

```kotlin
// 好的命名
class MyComponentTest {
    @Test
    fun testProcessInput_withValidInput_returnsProcessedOutput() { }

    @Test
    fun testProcessInput_withInvalidInput_throwsException() { }

    @Test
    fun testProcessInput_withEmptyInput_returnsDefaultOutput() { }
}

// 不好的命名
class MyComponentTest {
    @Test
    fun test1() { }

    @Test
    fun testProcess() { }
}
```

### 5.2 测试结构（AAA 模式）

```kotlin
@Test
fun testSomething() {
    // Arrange（准备）
    val input = "test input"
    val expected = "expected output"

    // Act（执行）
    val result = component.process(input)

    // Assert（验证）
    assertThat(result).isEqualTo(expected)
}
```

### 5.3 测试隔离

```kotlin
class MyComponentTest {

    private lateinit var component: MyComponent

    @Before
    fun setUp() {
        // 每个测试前重新创建组件
        component = MyComponent()
    }

    @After
    fun tearDown() {
        // 每个测试后清理
        component.cleanup()
    }
}
```

### 5.4 测试数据管理

```kotlin
class MyComponentTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupClass() {
            // 类级别设置
        }

        @JvmStatic
        @AfterClass
        fun teardownClass() {
            // 类级别清理
        }
    }
}
```

---

## 6. 性能测试

### 6.1 基本性能测试

```kotlin
class PerformanceTest {

    @Test
    fun testPerformance() {
        val iterations = 1000
        val startTime = System.currentTimeMillis()

        repeat(iterations) {
            // 执行操作
            component.process("test input")
        }

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        // 验证性能
        assertThat(duration).isLessThan(1000) // 1秒内完成
    }
}
```

### 6.2 内存测试

```kotlin
class MemoryTest {

    @Test
    fun testMemoryUsage() {
        val runtime = Runtime.getRuntime()

        // 记录初始内存
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()

        // 执行操作
        repeat(1000) {
            component.process("test input")
        }

        // 强制垃圾回收
        System.gc()

        // 记录最终内存
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryIncrease = finalMemory - initialMemory

        // 验证内存使用
        assertThat(memoryIncrease).isLessThan(1024 * 1024) // 增加不超过 1MB
    }
}
```

---

## 7. 测试配置

### 7.1 build.gradle.kts 配置

```kotlin
plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.assertj:assertj-core:3.25.1")

    // IntelliJ Platform 测试依赖
    intellijPlatform {
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

tasks.test {
    useJUnitPlatform()

    // 测试报告
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
}
```

### 7.2 测试数据目录

```
src/test/
├── java/
│   └── com/github/xausky/opencodewebui/
│       ├── toolWindow/
│       │   ├── MyToolWindowTest.kt
│       │   └── BrowserPanelTest.kt
│       ├── actions/
│       │   ├── CopyAsPromptActionTest.kt
│       │   └── AddToPromptActionTest.kt
│       └── utils/
│           └── OpenCodeApiTest.kt
├── testData/
│   ├── focus/
│   │   ├── focus-switch-test.kt
│   │   └── focus-restoration-test.kt
│   └── ime/
│       ├── chinese-input-test.kt
│       └── ime-switching-test.kt
└── resources/
    └── test-plugin.xml
```

---

## 8. SDK 集成

### 8.1 opencode SDK 集成

```kotlin
class OpenCodeSdkClient {

    private val baseUrl: String
    private val directory: String

    constructor(baseUrl: String, directory: String) {
        this.baseUrl = baseUrl
        this.directory = directory
    }

    suspend fun getSession(sessionId: String): SessionInfo? {
        val url = "$baseUrl/session/$sessionId"
        val response = httpClient.get(url) {
            header("X-Opencode-Directory", directory)
        }

        return if (response.status == HttpStatusCode.OK) {
            response.body<SessionInfo>()
        } else {
            null
        }
    }

    suspend fun sendMessage(sessionId: String, message: String): MessageResponse? {
        val url = "$baseUrl/session/$sessionId/message"
        val response = httpClient.post(url) {
            header("X-Opencode-Directory", directory)
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "parts" to listOf(mapOf("type" to "text", "text" to message))
            ))
        }

        return if (response.status == HttpStatusCode.OK) {
            response.body<MessageResponse>()
        } else {
            null
        }
    }
}
```

### 8.2 SSE 事件集成

```kotlin
class OpenCodeEventConsumer(
    private val baseUrl: String,
    private val directory: String
) {

    private val eventSource = AtomicReference<EventSource?>(null)

    fun start(handler: (OpenCodeEvent) -> Unit) {
        val uri = URI.create("$baseUrl/event")
        val connectStrategy = ConnectStrategy.http(uri)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)

        val esBuilder = EventSource.Builder(connectStrategy)
            .errorStrategy(ErrorStrategy.alwaysContinue())

        val bgBuilder = BackgroundEventSource.Builder(object : BackgroundEventHandler {
            override fun onOpen() {
                println("SSE connection opened")
            }

            override fun onMessage(event: String, messageEvent: MessageEvent) {
                val eventData = Gson().fromJson(messageEvent.data, OpenCodeEvent::class.java)
                handler(eventData)
            }

            override fun onClosed() {
                println("SSE connection closed")
            }

            override fun onError(error: Throwable) {
                println("SSE error: ${error.message}")
            }

            override fun onComment(comment: String) {
                // 忽略注释
            }
        }, esBuilder)

        val bgEventSource = bgBuilder.build()
        eventSource.set(bgEventSource)
        bgEventSource.start()
    }

    fun stop() {
        eventSource.getAndSet(null)?.close()
    }
}
```

---

## 9. 测试场景

### 9.1 单元测试场景

| 场景       | 操作         | 预期结果     |
| ---------- | ------------ | ------------ |
| 组件初始化 | 创建组件     | 正确初始化   |
| 数据处理   | 处理输入数据 | 正确输出     |
| 错误处理   | 输入无效数据 | 正确处理错误 |

### 9.2 集成测试场景

| 场景         | 操作         | 预期结果 |
| ------------ | ------------ | -------- |
| 工具窗口创建 | 打开工具窗口 | 正确创建 |
| 编辑器集成   | 打开文件     | 正确集成 |
| 通知显示     | 触发通知     | 正确显示 |

### 9.3 性能测试场景

| 场景     | 操作     | 预期结果    |
| -------- | -------- | ----------- |
| 响应时间 | 执行操作 | < 100ms     |
| 内存使用 | 执行操作 | < 10MB 增长 |
| 并发处理 | 并发执行 | 正确处理    |

---

## 10. 参考资源

| 资源                      | URL                                                      |
| ------------------------- | -------------------------------------------------------- |
| IntelliJ Testing          | https://plugins.jetbrains.com/docs/intellij/testing.html |
| JUnit 4                   | https://junit.org/junit4/                                |
| Mockito                   | https://site.mockito.org/                                |
| AssertJ                   | https://assertj.github.io/doc/                           |
| IntelliJ SDK Code Samples | https://github.com/JetBrains/intellij-sdk-code-samples   |

---

## 11. 待深入调研

- [ ] 测试覆盖率工具集成
- [ ] 自动化测试流水线
- [ ] 性能测试基准
- [ ] UI 测试自动化
- [ ] 测试数据生成
