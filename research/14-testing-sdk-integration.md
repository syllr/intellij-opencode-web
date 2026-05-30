# IntelliJ 插件测试模式和 SDK 集成详解

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件与 opencode 联动提供测试和 SDK 集成技术指导

---

## 1. 测试概述

### 1.1 测试类型

| 类型     | 说明         | 用途         |
| -------- | ------------ | ------------ |
| 单元测试 | 测试单个组件 | 验证业务逻辑 |
| 集成测试 | 测试组件交互 | 验证系统集成 |
| UI 测试  | 测试用户界面 | 验证用户体验 |
| 性能测试 | 测试性能指标 | 验证性能要求 |

### 1.2 测试框架

| 框架              | 说明         | 用途               |
| ----------------- | ------------ | ------------------ |
| JUnit 4           | 单元测试框架 | 编写测试用例       |
| Mockito           | Mock 框架    | 模拟依赖对象       |
| AssertJ           | 断言库       | 编写测试断言       |
| IntelliJ Platform | 集成测试框架 | 测试 IntelliJ 集成 |

---

## 2. 单元测试

### 2.1 基本测试结构

```kotlin
class MyServiceTest {

    private lateinit var service: MyService

    @Before
    fun setUp() {
        service = MyService()
    }

    @Test
    fun testProcessInput() {
        // 准备
        val input = "test input"

        // 执行
        val result = service.processInput(input)

        // 验证
        assertEquals("expected output", result)
    }

    @Test
    fun testProcessEmptyInput() {
        // 准备
        val input = ""

        // 执行
        val result = service.processInput(input)

        // 验证
        assertNull(result)
    }
}
```

### 2.2 使用 Mockito

```kotlin
class MyServiceTest {

    @Mock
    private lateinit var dependency: MyDependency

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
        val result = service.process()

        // 验证
        assertEquals("processed mock data", result)
        verify(dependency).getData()
    }
}
```

### 2.3 测试异常情况

```kotlin
class MyServiceTest {

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidInput() {
        service.processInput(null)
    }

    @Test
    fun testExceptionMessage() {
        try {
            service.processInput("invalid")
            fail("Expected exception")
        } catch (e: IllegalArgumentException) {
            assertEquals("Invalid input", e.message)
        }
    }
}
```

---

## 3. 集成测试

### 3.1 使用 BasePlatformTestCase

```kotlin
class MyPluginTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testMyFeature() {
        // 准备
        myFixture.configureByFile("testFile.kt")

        // 执行
        val action = myFixture.findActionById("MyAction.ID")
        myFixture.testAction(action)

        // 验证
        myFixture.checkResultByFile("expectedFile.kt")
    }

    fun testNavigation() {
        // 准备
        myFixture.configureByFile("navigationTest.kt")

        // 执行
        myFixture.completeBasic()

        // 验证
        myFixture.checkHighlighting()
    }
}
```

### 3.2 测试工具窗口

```kotlin
class MyToolWindowTest : BasePlatformTestCase() {

    fun testToolWindowCreation() {
        // 执行
        val toolWindow = myFixture.getProjectToolWindow("MyToolWindow")

        // 验证
        assertNotNull(toolWindow)
        assertTrue(toolWindow.isVisible)
    }

    fun testToolWindowContent() {
        // 准备
        val toolWindow = myFixture.getProjectToolWindow("MyToolWindow")!!

        // 执行
        val content = toolWindow.contentManager.selectedContent

        // 验证
        assertNotNull(content)
        assertNotNull(content.component)
    }
}
```

### 3.3 测试 Action

```kotlin
class MyActionTest : BasePlatformTestCase() {

    fun testActionPresentation() {
        // 准备
        myFixture.configureByFile("actionTest.kt")
        val action = myFixture.findActionById("MyAction.ID")

        // 执行
        val presentation = myFixture.testAction(action)

        // 验证
        assertTrue(presentation.isEnabled)
        assertEquals("My Action", presentation.text)
    }

    fun testActionExecution() {
        // 准备
        myFixture.configureByFile("actionExecutionTest.kt")
        val action = myFixture.findActionById("MyAction.ID")

        // 执行
        myFixture.testAction(action)

        // 验证
        myFixture.checkResultByFile("actionExecutionResult.kt")
    }
}
```

---

## 4. Mock IntelliJ 服务

### 4.1 Mock 项目服务

```kotlin
class MyServiceTest {

    @Test
    fun testWithMockProject() {
        // 创建 Mock 项目
        val project = mockk<Project>()
        every { project.basePath } returns "/test/project"
        every { project.isDisposed } returns false

        // 使用 Mock 项目
        val service = MyService(project)
        val result = service.process()

        // 验证
        assertNotNull(result)
    }
}
```

### 4.2 Mock 编辑器

```kotlin
class MyEditorTest {

    @Test
    fun testWithMockEditor() {
        // 创建 Mock 编辑器
        val editor = mockk<Editor>()
        val document = mockk<Document>()
        val caretModel = mockk<CaretModel>()
        val selectionModel = mockk<SelectionModel>()

        every { editor.document } returns document
        every { editor.caretModel } returns caretModel
        every { editor.selectionModel } returns selectionModel

        every { document.text } returns "test content"
        every { caretModel.offset } returns 10
        every { selectionModel.hasSelection() } returns true
        every { selectionModel.selectedText } returns "selected"

        // 使用 Mock 编辑器
        val service = MyEditorService(editor)
        val context = service.getEditorContext()

        // 验证
        assertEquals("test content", context.content)
        assertEquals(10, context.offset)
        assertTrue(context.hasSelection)
    }
}
```

### 4.3 Mock PSI 元素

```kotlin
class MyPsiTest {

    @Test
    fun testWithMockPsiElement() {
        // 创建 Mock PSI 元素
        val element = mockk<PsiElement>()
        val psiFile = mockk<PsiFile>()
        val virtualFile = mockk<VirtualFile>()

        every { element.text } returns "test element"
        every { element.javaClass } returns PsiClass::class.java
        every { element.parent } returns null
        every { element.containingFile } returns psiFile
        every { psiFile.virtualFile } returns virtualFile
        every { virtualFile.path } returns "/test/file.kt"

        // 使用 Mock PSI 元素
        val service = MyPsiService()
        val info = service.getElementInfo(element)

        // 验证
        assertEquals("test element", info.text)
        assertEquals("PsiClass", info.type)
    }
}
```

---

## 5. 测试数据管理

### 5.1 使用测试数据文件

```kotlin
class MyPluginTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testWithTestData() {
        // 加载测试数据
        myFixture.configureByFile("input.kt")

        // 执行测试
        myFixture.testAction(myAction)

        // 比较结果
        myFixture.checkResultByFile("expected.kt")
    }
}
```

### 5.2 测试数据目录结构

```
src/test/testData/
├── input/
│   ├── simple.kt
│   ├── complex.kt
│   └── edge_cases.kt
├── expected/
│   ├── simple_result.kt
│   ├── complex_result.kt
│   └── edge_cases_result.kt
└── fixtures/
    ├── action_test.xml
    └── tool_window_test.xml
```

### 5.3 内联测试数据

```kotlin
class MyPluginTest : BasePlatformTestCase() {

    fun testInline() {
        // 内联测试数据
        myFixture.configureByText("test.kt", """
            fun main() {
                <caret>// 测试代码
            }
        """.trimIndent())

        // 执行测试
        myFixture.completeBasic()

        // 验证结果
        myFixture.checkResult("""
            fun main() {
                // 测试代码
                // 自动补全结果
            }
        """.trimIndent())
    }
}
```

---

## 6. 集成 opencode SDK

### 6.1 HTTP 客户端

```kotlin
class OpenCodeClient(private val host: String, private val port: Int) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun getSession(sessionID: String): SessionInfo? {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("http://$host:$port/session/$sessionID")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    gson.fromJson(response.body?.string(), SessionInfo::class.java)
                } else {
                    null
                }
            }
        }
    }

    suspend fun sendMessage(sessionID: String, message: String): MessageResponse? {
        return withContext(Dispatchers.IO) {
            val body = mapOf(
                "parts" to listOf(mapOf("type" to "text", "text" to message))
            )

            val request = Request.Builder()
                .url("http://$host:$port/session/$sessionID/message")
                .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    gson.fromJson(response.body?.string(), MessageResponse::class.java)
                } else {
                    null
                }
            }
        }
    }
}
```

### 6.2 SSE 事件处理

```kotlin
class OpenCodeSseClient(private val host: String, private val port: Int) {

    private var eventSource: EventSource? = null

    fun connect(handler: (SseEvent) -> Unit) {
        val uri = URI.create("http://$host:$port/global/event")

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
                val eventData = gson.fromJson(messageEvent.data, SseEvent::class.java)
                handler(eventData)
            }

            override fun onClosed() {
                println("SSE connection closed")
            }

            override fun onError(error: Throwable) {
                println("SSE error: ${error.message}")
            }

            override fun onComment(comment: String) {
                // 处理注释
            }
        }, esBuilder)

        val bgEventSource = bgBuilder.build()
        eventSource = bgEventSource
        bgEventSource.start()
    }

    fun disconnect() {
        eventSource?.close()
        eventSource = null
    }
}
```

### 6.3 认证处理

```kotlin
class OpenCodeAuthClient(private val host: String, private val port: Int) {

    private var authToken: String? = null

    suspend fun login(username: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            val credentials = "$username:$password"
            val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())

            val request = Request.Builder()
                .url("http://$host:$port/auth/login")
                .addHeader("Authorization", "Basic $encoded")
                .post("".toRequestBody(null))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    authToken = response.header("X-Auth-Token")
                    true
                } else {
                    false
                }
            }
        }
    }

    suspend fun <T> authenticatedRequest(request: Request, parser: (String) -> T): T? {
        return withContext(Dispatchers.IO) {
            val authenticatedRequest = request.newBuilder()
                .addHeader("Authorization", "Bearer $authToken")
                .build()

            client.newCall(authenticatedRequest).execute().use { response ->
                if (response.isSuccessful) {
                    parser(response.body?.string() ?: "")
                } else {
                    null
                }
            }
        }
    }
}
```

---

## 7. 测试场景

### 7.1 单元测试场景

| 场景     | 操作              | 预期结果     |
| -------- | ----------------- | ------------ |
| 输入验证 | 测试有效/无效输入 | 正确验证输入 |
| 业务逻辑 | 测试核心业务逻辑  | 正确处理业务 |
| 异常处理 | 测试异常情况      | 正确处理异常 |
| 边界条件 | 测试边界条件      | 正确处理边界 |

### 7.2 集成测试场景

| 场景        | 操作           | 预期结果        |
| ----------- | -------------- | --------------- |
| Action 测试 | 执行 Action    | 正确执行 Action |
| 工具窗口    | 测试工具窗口   | 正确显示内容    |
| 编辑器集成  | 测试编辑器交互 | 正确交互        |
| PSI 分析    | 测试 PSI 分析  | 正确分析代码    |

### 7.3 API 集成测试

| 场景     | 操作          | 预期结果     |
| -------- | ------------- | ------------ |
| 会话管理 | 测试会话 CRUD | 正确管理会话 |
| 消息发送 | 测试消息发送  | 正确发送消息 |
| SSE 连接 | 测试 SSE 连接 | 正确接收事件 |
| 认证     | 测试认证流程  | 正确处理认证 |

---

## 8. 性能测试

### 8.1 基准测试

```kotlin
class MyServiceBenchmark {

    @Benchmark
    fun testProcessInput() {
        service.processInput("test input")
    }

    @Benchmark
    fun testProcessLargeInput() {
        val largeInput = "x".repeat(10000)
        service.processInput(largeInput)
    }
}
```

### 8.2 内存测试

```kotlin
class MyServiceMemoryTest {

    @Test
    fun testMemoryUsage() {
        val runtime = Runtime.getRuntime()

        // 记录初始内存
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()

        // 执行操作
        repeat(1000) {
            service.processInput("test $it")
        }

        // 强制 GC
        System.gc()

        // 记录最终内存
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()

        // 验证内存增长
        val memoryGrowth = finalMemory - initialMemory
        assertTrue("Memory growth too large: $memoryGrowth bytes", memoryGrowth < 1024 * 1024) // 小于 1MB
    }
}
```

---

## 9. 持续集成

### 9.1 GitHub Actions 配置

```yaml
# .github/workflows/test.yml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: "21"
          distribution: "temurin"

      - name: Run tests
        run: ./gradlew test

      - name: Generate coverage report
        run: ./gradlew jacocoTestReport

      - name: Upload coverage
        uses: codecov/codecov-action@v3
        with:
          file: ./build/reports/jacoco/test/html/index.html
```

### 9.2 测试覆盖率

```kotlin
// build.gradle.kts
tasks.withType<Test> {
    useJUnitPlatform()

    finalizedBy(jacocoTestReport)
}

jacocoTestReport {
    dependsOn(test)

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
```

---

## 10. 参考资源

| 资源                      | URL                                                                    |
| ------------------------- | ---------------------------------------------------------------------- |
| IntelliJ Testing          | https://plugins.jetbrains.com/docs/intellij/testing.html               |
| BasePlatformTestCase      | https://plugins.jetbrains.com/docs/intellij/testing_light_editors.html |
| JUnit 4                   | https://junit.org/junit4/                                              |
| Mockito                   | https://site.mockito.org/                                              |
| IntelliJ SDK Code Samples | https://github.com/JetBrains/intellij-sdk-code-samples                 |

---

## 11. 待深入调研

- [ ] 测试数据生成工具
- [ ] 测试覆盖率分析
- [ ] 性能测试工具
- [ ] UI 测试框架
- [ ] 测试最佳实践
