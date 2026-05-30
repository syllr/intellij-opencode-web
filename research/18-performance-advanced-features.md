# IntelliJ 插件性能优化和高级功能详解

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件提供性能优化和高级功能技术指导

---

## 1. 性能优化概述

### 1.1 性能瓶颈

| 瓶颈类型 | 说明            | 影响         |
| -------- | --------------- | ------------ |
| 内存泄漏 | 未释放资源      | 内存持续增长 |
| PSI 遍历 | 频繁遍历 PSI 树 | CPU 占用高   |
| 网络请求 | 同步网络请求    | UI 卡顿      |
| 文件 I/O | 频繁文件读写    | 响应慢       |

### 1.2 性能指标

| 指标     | 目标值  | 说明            |
| -------- | ------- | --------------- |
| 内存使用 | < 100MB | 插件内存占用    |
| 响应时间 | < 100ms | 操作响应时间    |
| CPU 使用 | < 10%   | 空闲时 CPU 占用 |
| 启动时间 | < 1s    | 插件启动时间    |

---

## 2. 内存优化

### 2.1 避免内存泄漏

```kotlin
class MyComponent : Disposable {

    private val listeners = mutableListOf<Listener>()
    private val resources = mutableListOf<Disposable>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun addResource(resource: Disposable) {
        resources.add(resource)
    }

    override fun dispose() {
        // 释放所有监听器
        listeners.clear()

        // 释放所有资源
        resources.forEach { it.dispose() }
        resources.clear()
    }
}

// 使用示例
val component = MyComponent()
try {
    // 使用组件
} finally {
    component.dispose() // 确保释放
}
```

### 2.2 使用弱引用

```kotlin
class MyCache {

    private val cache = WeakHashMap<String, Any>()

    fun get(key: String): Any? {
        return cache[key]
    }

    fun put(key: String, value: Any) {
        cache[key] = value
    }
}

// 或使用 WeakReference
class MyWeakCache {

    private val cache = ConcurrentHashMap<String, WeakReference<Any>>()

    fun get(key: String): Any? {
        return cache[key]?.get()
    }

    fun put(key: String, value: Any) {
        cache[key] = WeakReference(value)
    }
}
```

### 2.3 及时释放资源

```kotlin
class MyService {

    private var connection: Connection? = null

    fun connect() {
        connection = createConnection()
    }

    fun disconnect() {
        connection?.close()
        connection = null
    }

    fun execute(command: String) {
        val conn = connection ?: throw Exception("Not connected")

        try {
            conn.execute(command)
        } catch (e: Exception) {
            // 错误处理
        } finally {
            // 不在这里关闭连接，由调用者决定
        }
    }
}
```

---

## 3. PSI 优化

### 3.1 缓存 PSI 结果

```kotlin
class PsiCache {

    private val cache = ConcurrentHashMap<String, PsiFile>()
    private val cacheTimeout = 5000L // 5秒

    fun getPsiFile(project: Project, virtualFile: VirtualFile): PsiFile? {
        val key = "${project.name}:${virtualFile.path}"
        val cached = cache[key]

        if (cached != null && !cached.isValid) {
            cache.remove(key)
            return null
        }

        if (cached != null) {
            return cached
        }

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        if (psiFile != null) {
            cache[key] = psiFile
        }

        return psiFile
    }

    fun invalidate(virtualFile: VirtualFile) {
        cache.entries.removeIf { it.key.endsWith(":${virtualFile.path}") }
    }
}
```

### 3.2 优化 PSI 遍历

```kotlin
class PsiTraversalOptimizer {

    // 使用 Visitor 模式而非递归遍历
    fun traverseWithVisitor(psiFile: PsiFile, visitor: PsiElementVisitor) {
        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                visitor.visitElement(element)
                super.visitElement(element)
            }
        })
    }

    // 提前终止遍历
    fun findFirstElement(psiFile: PsiFile, predicate: (PsiElement) -> Boolean): PsiElement? {
        var result: PsiElement? = null

        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (result != null) return // 已找到，停止遍历

                if (predicate(element)) {
                    result = element
                    stopWalking() // 停止遍历
                }

                super.visitElement(element)
            }
        })

        return result
    }
}
```

### 3.3 使用 Read Action

```kotlin
class PsiReadActionOptimizer {

    fun <T> runReadAction(action: () -> T): T {
        return ApplicationManager.getApplication().runReadAction(action)
    }

    fun <T> runReadActionWithTimeout(timeoutMs: Long, action: () -> T): T {
        return Computable.runInSwingDispatchThread {
            ApplicationManager.getApplication().runReadAction(action)
        }
    }
}
```

---

## 4. 网络优化

### 4.1 异步网络请求

```kotlin
class AsyncNetworkClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun <T> makeRequest(
        request: Request,
        parser: (String) -> T
    ): T? {
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        parser(response.body?.string() ?: "")
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
```

### 4.2 连接池

```kotlin
class PooledNetworkClient {

    private val connectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .build()

    fun close() {
        connectionPool.evictAll()
    }
}
```

### 4.3 请求缓存

```kotlin
class CachedNetworkClient {

    private val cache = Cache(
        directory = File(System.getProperty("java.io.tmpdir"), "http-cache"),
        maxSize = 10L * 1024 * 1024 // 10MB
    )

    private val client = OkHttpClient.Builder()
        .cache(cache)
        .build()

    fun makeCachedRequest(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.Builder()
                .maxAge(5, TimeUnit.MINUTES) // 缓存5分钟
                .build())
            .build()

        return client.newCall(request).execute().use { response ->
            response.body?.string()
        }
    }
}
```

---

## 5. 文件 I/O 优化

### 5.1 批量文件操作

```kotlin
class BatchFileOperations {

    fun readFiles(files: List<File>): Map<File, String> {
        return files.associateWith { file ->
            file.readText()
        }
    }

    fun writeFiles(files: Map<File, String>) {
        files.forEach { (file, content) ->
            file.writeText(content)
        }
    }
}
```

### 5.2 异步文件操作

```kotlin
class AsyncFileOperations {

    suspend fun readFileAsync(file: File): String? {
        return withContext(Dispatchers.IO) {
            try {
                file.readText()
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun writeFileAsync(file: File, content: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                file.writeText(content)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
```

---

## 6. 高级功能

### 6.1 代码补全集成

```kotlin
class CodeCompletionIntegration {

    suspend fun getCompletions(
        project: Project,
        editor: Editor,
        offset: Int
    ): List<CompletionItem> {
        val psiFile = PsiDocumentManager.getInstance(project)
            .getPsiFile(editor.document) ?: return emptyList()

        val element = psiFile.findElementAt(offset) ?: return emptyList()

        // 获取补全建议
        val completions = mutableListOf<CompletionItem>()

        // 基于 PSI 元素获取补全
        if (element is PsiReferenceExpression) {
            val resolved = element.resolve()
            if (resolved is PsiClass) {
                completions.addAll(getClassCompletions(resolved))
            }
        }

        return completions
    }

    private fun getClassCompletions(psiClass: PsiClass): List<CompletionItem> {
        return psiClass.methods.map { method ->
            CompletionItem(
                label = method.name,
                kind = CompletionItemKind.Method,
                detail = method.returnType?.presentableText,
                documentation = "Method: ${method.name}"
            )
        }
    }
}

data class CompletionItem(
    val label: String,
    val kind: CompletionItemKind,
    val detail: String?,
    val documentation: String?
)

enum class CompletionItemKind {
    Method, Function, Variable, Class, Interface, Module, Property
}
```

### 6.2 代码重构集成

```kotlin
class RefactoringIntegration {

    suspend fun renameSymbol(
        project: Project,
        element: PsiElement,
        newName: String
    ): Boolean {
        return try {
            val renameRefactoring = element.parent?.let { parent ->
                RefactoringFactory.getInstance(project)
                    .createRename(parent, newName)
            }

            renameRefactoring?.run()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun extractMethod(
        project: Project,
        editor: Editor,
        startOffset: Int,
        endOffset: Int,
        methodName: String
    ): Boolean {
        return try {
            val extractMethodRefactoring = ExtractMethodHandler.extractMethod(
                project,
                editor,
                startOffset,
                endOffset,
                methodName
            )

            extractMethodRefactoring?.run()
            true
        } catch (e: Exception) {
            false
        }
    }
}
```

### 6.3 调试集成

```kotlin
class DebugIntegration {

    suspend fun getStackFrames(
        project: Project,
        debugSession: XDebugSession
    ): List<StackFrame> {
        val frames = mutableListOf<StackFrame>()

        val process = debugSession.process
        val threads = process.threads

        threads.forEach { thread ->
            val stackFrames = thread.stackFrames
            stackFrames.forEach { frame ->
                frames.add(StackFrame(
                    name = frame.name,
                    file = frame.source?.path,
                    line = frame.line,
                    column = frame.col
                ))
            }
        }

        return frames
    }

    suspend fun getVariables(
        project: Project,
        stackFrame: XStackFrame
    ): List<Variable> {
        val variables = mutableListOf<Variable>()

        val children = stackFrame.variables
        children.forEach { variable ->
            variables.add(Variable(
                name = variable.name,
                value = variable.value,
                type = variable.type
            ))
        }

        return variables
    }
}

data class StackFrame(
    val name: String,
    val file: String?,
    val line: Int,
    val column: Int
)

data class Variable(
    val name: String,
    val value: String,
    val type: String
)
```

---

## 7. 测试场景

### 7.1 性能测试

| 场景     | 操作            | 预期结果         |
| -------- | --------------- | ---------------- |
| 内存使用 | 运行插件 1 小时 | 内存增长 < 10MB  |
| 响应时间 | 执行操作        | 响应时间 < 100ms |
| CPU 使用 | 空闲状态        | CPU 使用 < 10%   |
| 启动时间 | 启动插件        | 启动时间 < 1s    |

### 7.2 功能测试

| 场景     | 操作       | 预期结果     |
| -------- | ---------- | ------------ |
| 代码补全 | 输入代码   | 显示补全建议 |
| 代码重构 | 重命名符号 | 正确重构     |
| 调试功能 | 调试代码   | 正确显示信息 |

---

## 8. 参考资源

| 资源                      | URL                                                                |
| ------------------------- | ------------------------------------------------------------------ |
| IntelliJ Performance      | https://plugins.jetbrains.com/docs/intellij/performance.html       |
| PSI Optimization          | https://plugins.jetbrains.com/docs/intellij/psi_optimization.html  |
| Memory Management         | https://plugins.jetbrains.com/docs/intellij/memory_management.html |
| IntelliJ SDK Code Samples | https://github.com/JetBrains/intellij-sdk-code-samples             |

---

## 9. 待深入调研

- [ ] 内存泄漏检测工具
- [ ] PSI 性能分析工具
- [ ] 网络请求优化策略
- [ ] 文件 I/O 优化技术
- [ ] 高级代码分析功能
