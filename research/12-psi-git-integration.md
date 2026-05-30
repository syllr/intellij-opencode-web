# IntelliJ PSI 和 Git 集成详解

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件与 opencode 联动提供 PSI 和 Git 集成技术指导

---

## 1. PSI (Program Structure Interface) 概述

### 1.1 PSI 是什么

PSI 是 IntelliJ 平台的核心 API，用于表示和操作代码的语法和语义结构。它提供了一个树形结构来表示源代码，每个节点代表代码中的一个元素（如类、方法、变量等）。

### 1.2 核心组件

| 组件                 | 说明                        | 用途                       |
| -------------------- | --------------------------- | -------------------------- |
| `PsiFile`            | 表示整个文件                | 获取文件信息、解析文件结构 |
| `PsiElement`         | 表示代码元素                | 遍历代码结构、获取元素信息 |
| `PsiDocumentManager` | 管理 PSI 和 Document 的映射 | 在 PSI 和编辑器之间转换    |
| `PsiManager`         | 管理 PSI 文件               | 获取 PSI 文件、监听变化    |
| `PsiReference`       | 表示代码引用                | 查找引用、导航到定义       |

---

## 2. 获取代码上下文

### 2.1 获取当前文件信息

```kotlin
fun getFileContext(editor: Editor, project: Project): FileContext? {
    val psiFile = PsiDocumentManager.getInstance(project)
        .getPsiFile(editor.document) ?: return null

    val virtualFile = psiFile.virtualFile ?: return null
    val offset = editor.caretModel.offset
    val element = psiFile.findElementAt(offset) ?: return null

    return FileContext(
        filePath = virtualFile.path,
        fileName = virtualFile.name,
        packageName = getPackageName(psiFile),
        language = psiFile.language.id,
        line = editor.document.getLineNumber(offset) + 1,
        column = offset - editor.document.getLineStartOffset(editor.document.getLineNumber(offset)),
        elementText = element.text,
        elementType = element.javaClass.simpleName,
        parentElement = element.parent?.javaClass?.simpleName
    )
}

data class FileContext(
    val filePath: String,
    val fileName: String,
    val packageName: String,
    val language: String,
    val line: Int,
    val column: Int,
    val elementText: String,
    val elementType: String,
    val parentElement: String?
)

private fun getPackageName(psiFile: PsiFile): String {
    val packageStatement = psiFile.children.filterIsInstance<PsiPackageStatement>()
        .firstOrNull()
    return packageStatement?.packageName ?: ""
}
```

### 2.2 获取代码结构

```kotlin
fun getCodeStructure(psiFile: PsiFile): CodeStructure {
    val classes = mutableListOf<ClassInfo>()
    val methods = mutableListOf<MethodInfo>()
    val variables = mutableListOf<VariableInfo>()

    psiFile.accept(object : PsiRecursiveElementVisitor() {
        override fun visitElement(element: PsiElement) {
            when (element) {
                is PsiClass -> classes.add(getClassInfo(element))
                is PsiMethod -> methods.add(getMethodInfo(element))
                is PsiVariable -> variables.add(getVariableInfo(element))
            }
            super.visitElement(element)
        }
    })

    return CodeStructure(classes, methods, variables)
}

data class CodeStructure(
    val classes: List<ClassInfo>,
    val methods: List<MethodInfo>,
    val variables: List<VariableInfo>
)

data class ClassInfo(
    val name: String,
    val qualifiedName: String,
    val superClass: String?,
    val interfaces: List<String>,
    val fields: List<FieldInfo>,
    val methods: List<MethodInfo>,
    val line: Int
)

data class MethodInfo(
    val name: String,
    val returnType: String,
    val parameters: List<ParameterInfo>,
    val modifiers: List<String>,
    val line: Int,
    val bodyLength: Int
)

data class VariableInfo(
    val name: String,
    val type: String,
    val modifiers: List<String>,
    val line: Int
)
```

### 2.3 获取代码引用和依赖

```kotlin
fun getCodeReferences(element: PsiElement): List<CodeReference> {
    val references = mutableListOf<CodeReference>()

    element.accept(object : PsiRecursiveElementVisitor() {
        override fun visitElement(element: PsiElement) {
            // 获取方法调用引用
            if (element is PsiMethodCallExpression) {
                val method = element.resolveMethod()
                if (method != null) {
                    references.add(CodeReference(
                        type = "method_call",
                        name = method.name,
                        qualifiedName = method.containingClass?.qualifiedName ?: "",
                        line = element.lineNumber
                    ))
                }
            }

            // 获取类引用
            if (element is PsiReferenceExpression) {
                val resolved = element.resolve()
                if (resolved is PsiClass) {
                    references.add(CodeReference(
                        type = "class_reference",
                        name = resolved.name ?: "",
                        qualifiedName = resolved.qualifiedName ?: "",
                        line = element.lineNumber
                    ))
                }
            }

            super.visitElement(element)
        }
    })

    return references
}

data class CodeReference(
    val type: String,
    val name: String,
    val qualifiedName: String,
    val line: Int
)
```

### 2.4 导航到符号定义

```kotlin
fun navigateToDefinition(project: Project, element: PsiElement): PsiElement? {
    // 获取引用
    val reference = element.reference ?: return null

    // 解析引用
    val resolved = reference.resolve() ?: return null

    // 导航到定义
    val navigationElement = resolved.navigationElement
    if (navigationElement is PsiElement) {
        navigationElement.navigate(true)
        return navigationElement
    }

    return null
}

fun findDefinition(project: Project, element: PsiElement): PsiElement? {
    val reference = element.reference ?: return null
    return reference.resolve()
}
```

---

## 3. Git 集成

### 3.1 获取 Git 仓库状态

```kotlin
fun getGitStatus(project: Project): GitStatus? {
    val git = Git.getInstance()
    val repository = git.getRepository(project) ?: return null

    return GitStatus(
        branch = repository.currentBranchName,
        isClean = git.status(repository).isClean,
        changes = getChanges(repository),
        stagedChanges = getStagedChanges(repository)
    )
}

data class GitStatus(
    val branch: String,
    val isClean: Boolean,
    val changes: List<GitChange>,
    val stagedChanges: List<GitChange>
)

data class GitChange(
    val filePath: String,
    val changeType: ChangeType,
    val diff: String?
)

enum class ChangeType {
    MODIFIED, ADDED, DELETED, RENAMED, COPIED
}

private fun getChanges(repository: GitRepository): List<GitChange> {
    val git = Git.getInstance()
    val status = git.status(repository)

    return status.changedFiles.map { change ->
        GitChange(
            filePath = change.virtualFile?.path ?: "",
            changeType = when (change.type) {
                Change.Type.MODIFICATION -> ChangeType.MODIFIED
                Change.Type.NEW_FILE -> ChangeType.ADDED
                Change.Type.DELETED -> ChangeType.DELETED
                Change.Type.MOVED -> ChangeType.RENAMED
                else -> ChangeType.MODIFIED
            },
            diff = getDiff(repository, change)
        )
    }
}
```

### 3.2 获取文件差异

```kotlin
fun getFileDiff(repository: GitRepository, filePath: String): String? {
    val git = Git.getInstance()

    // 获取工作区差异
    val diffCommand = git.createDiffCommand(repository)
        .withFilePath(filePath)
        .withContentRevisionFactory(null)

    val diffResult = diffCommand.run()

    return diffResult.diffs.joinToString("\n") { diff ->
        buildString {
            appendLine("--- a/$filePath")
            appendLine("+++ b/$filePath")
            diff hunks.forEach { hunk ->
                appendLine("@@ -${hunk.startLineBefore},${hunk.lineCountBefore} +${hunk.startLineAfter},${hunk.lineCountAfter} @@")
                hunk.lines.forEach { line ->
                    appendLine("${line.type}${line.text}")
                }
            }
        }
    }
}
```

### 3.3 生成 Commit Message

```kotlin
fun generateCommitMessage(changes: List<GitChange>): String {
    val addedFiles = changes.filter { it.changeType == ChangeType.ADDED }
    val modifiedFiles = changes.filter { it.changeType == ChangeType.MODIFIED }
    val deletedFiles = changes.filter { it.changeType == ChangeType.DELETED }

    return buildString {
        // 第一行：简短描述
        when {
            addedFiles.isNotEmpty() -> append("Add ${addedFiles.size} new file(s)")
            modifiedFiles.isNotEmpty() -> append("Update ${modifiedFiles.size} file(s)")
            deletedFiles.isNotEmpty() -> append("Remove ${deletedFiles.size} file(s)")
            else -> append("Update code")
        }

        // 详细描述
        appendLine()
        appendLine()

        if (addedFiles.isNotEmpty()) {
            appendLine("Added files:")
            addedFiles.forEach { appendLine("- ${it.filePath}") }
        }

        if (modifiedFiles.isNotEmpty()) {
            appendLine("Modified files:")
            modifiedFiles.forEach { appendLine("- ${it.filePath}") }
        }

        if (deletedFiles.isNotEmpty()) {
            appendLine("Deleted files:")
            deletedFiles.forEach { appendLine("- ${it.filePath}") }
        }
    }
}
```

### 3.4 应用 Patch

```kotlin
fun applyPatch(project: Project, patch: String): Boolean {
    val git = Git.getInstance()
    val repository = git.getRepository(project) ?: return false

    return try {
        // 创建临时文件保存 patch
        val tempFile = File.createTempFile("patch", ".diff")
        tempFile.writeText(patch)

        // 应用 patch
        val applyCommand = git.createPatchCommand(repository)
            .withPatchFile(tempFile)
            .withCheckClean(false)

        val result = applyCommand.run()

        // 清理临时文件
        tempFile.delete()

        result.isSuccess
    } catch (e: Exception) {
        false
    }
}
```

---

## 4. IDEA 插件与 opencode 联动

### 4.1 代码上下文注入到 opencode

```kotlin
class CodeContextInjector(private val apiClient: OpenCodeApiClient) {

    suspend fun injectCodeContext(
        project: Project,
        editor: Editor,
        sessionID: String
    ) {
        val psiFile = PsiDocumentManager.getInstance(project)
            .getPsiFile(editor.document) ?: return

        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return

        // 构建上下文信息
        val context = buildString {
            appendLine("当前代码上下文:")
            appendLine("文件: ${psiFile.virtualFile?.path}")
            appendLine("行号: ${editor.document.getLineNumber(offset) + 1}")
            appendLine("符号: ${element.text}")
            appendLine("类型: ${element.javaClass.simpleName}")
            appendLine("父元素: ${element.parent?.javaClass?.simpleName ?: "无"}")

            // 获取代码结构
            val codeStructure = getCodeStructure(psiFile)
            if (codeStructure.classes.isNotEmpty()) {
                appendLine("\n代码结构:")
                codeStructure.classes.forEach { classInfo ->
                    appendLine("类: ${classInfo.name} (${classInfo.qualifiedName})")
                    classInfo.methods.forEach { method ->
                        appendLine("  方法: ${method.name}() -> ${method.returnType}")
                    }
                }
            }
        }

        // 发送到 opencode
        apiClient.sendMessage(sessionID, context, project.basePath!!)
    }
}
```

### 4.2 Git 工作流集成

```kotlin
class GitWorkflowIntegration(private val apiClient: OpenCodeApiClient) {

    suspend fun generateCommitMessage(
        project: Project,
        sessionID: String
    ): String? {
        val gitStatus = getGitStatus(project) ?: return null

        if (gitStatus.isClean) {
            return null
        }

        // 构建 Git 上下文
        val gitContext = buildString {
            appendLine("Git 状态:")
            appendLine("分支: ${gitStatus.branch}")
            appendLine("变更文件:")
            gitStatus.changes.forEach { change ->
                appendLine("- ${change.changeType}: ${change.filePath}")
                change.diff?.let { diff ->
                    appendLine("  差异:")
                    appendLine(diff.take(500)) // 限制长度
                }
            }
        }

        // 发送到 opencode 生成 commit message
        val response = apiClient.sendMessage(
            sessionID,
            "根据以下 Git 变更生成 commit message:\n$gitContext",
            project.basePath!!
        )

        return response.parts?.firstOrNull { it.type == "text" }?.text
    }

    suspend fun reviewChanges(
        project: Project,
        sessionID: String
    ): String? {
        val gitStatus = getGitStatus(project) ?: return null

        if (gitStatus.isClean) {
            return null
        }

        // 构建代码审查上下文
        val reviewContext = buildString {
            appendLine("代码审查:")
            appendLine("分支: ${gitStatus.branch}")
            appendLine("变更文件:")
            gitStatus.changes.forEach { change ->
                appendLine("\n文件: ${change.filePath}")
                appendLine("类型: ${change.changeType}")
                change.diff?.let { diff ->
                    appendLine("差异:")
                    appendLine(diff)
                }
            }
        }

        // 发送到 opencode 进行代码审查
        val response = apiClient.sendMessage(
            sessionID,
            "请审查以下代码变更并提供改进建议:\n$reviewContext",
            project.basePath!!
        )

        return response.parts?.firstOrNull { it.type == "text" }?.text
    }
}
```

---

## 5. 测试场景

### 5.1 PSI 集成测试

| 场景           | 操作                               | 预期结果                         |
| -------------- | ---------------------------------- | -------------------------------- |
| 获取文件上下文 | 在编辑器中点击，执行代码上下文注入 | 正确获取文件路径、行号、符号信息 |
| 获取代码结构   | 分析当前文件的类和方法             | 正确列出所有类和方法             |
| 获取代码引用   | 分析当前元素的引用                 | 正确找到所有引用                 |
| 导航到定义     | 双击方法调用，导航到定义           | 正确跳转到方法定义               |

### 5.2 Git 集成测试

| 场景                | 操作                        | 预期结果               |
| ------------------- | --------------------------- | ---------------------- |
| 获取 Git 状态       | 获取当前仓库状态            | 正确获取分支、变更信息 |
| 获取文件差异        | 获取某个文件的差异          | 正确显示差异内容       |
| 生成 Commit Message | 根据变更生成 commit message | 生成准确的描述         |
| 应用 Patch          | 应用一个 patch 文件         | 正确应用变更           |

---

## 6. 性能优化

### 6.1 PSI 缓存

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

### 6.2 Git 操作缓存

```kotlin
class GitCache {
    private val statusCache = ConcurrentHashMap<String, GitStatus>()
    private val cacheTimeout = 1000L // 1秒

    fun getGitStatus(project: Project): GitStatus? {
        val key = project.basePath ?: return null
        val cached = statusCache[key]

        if (cached != null) {
            return cached
        }

        val status = getGitStatusFromRepository(project)
        if (status != null) {
            statusCache[key] = status
        }

        return status
    }

    fun invalidate(project: Project) {
        val key = project.basePath ?: return
        statusCache.remove(key)
    }
}
```

---

## 7. 参考资源

| 资源                      | URL                                                              |
| ------------------------- | ---------------------------------------------------------------- |
| IntelliJ PSI 文档         | https://plugins.jetbrains.com/docs/intellij/psi.html             |
| Git4Idea API              | https://plugins.jetbrains.com/docs/intellij/git-integration.html |
| IntelliJ SDK Code Samples | https://github.com/JetBrains/intellij-sdk-code-samples           |
| PsiElement 文档           | https://plugins.jetbrains.com/docs/intellij/psi-elements.html    |

---

## 8. 待深入调研

- [ ] PSI 性能优化的最佳实践
- [ ] Git4Idea API 的高级用法
- [ ] 多模块项目的 PSI 处理
- [ ] 大型文件的 PSI 分析优化
- [ ] PSI 变化监听和增量更新
