# 工作计划：实现 Session 恢复功能

## 用户需求
用户希望插件启动后，如果已经有存在的老 session，直接打开它而不是创建新的 session。

---

## ✅ 已验证的实现方案

### 数据库结构（已确认）

**数据库路径**：`~/.local/share/opencode/opencode.db`

**session 表结构**：
```sql
CREATE TABLE session (
    id TEXT PRIMARY KEY,           -- Session ID (格式: ses_xxx)
    directory TEXT NOT NULL,        -- 项目目录路径
    title TEXT NOT NULL,            -- 对话标题
    time_created INTEGER NOT NULL,  -- 创建时间 (Unix timestamp)
    time_updated INTEGER NOT NULL,  -- 更新时间
    ...
)
```

**示例数据**：
```
ses_289e3c5f6ffeG6UQ70kEptBBoa | /Users/yutao/IdeaProjects/intellij-opencode-web | Test OpenCode session API
```

### URL 格式（已测试确认）

**正确格式**：
```
http://127.0.0.1:12396/{base64-encoded-path}?session={session-id}
```

**示例**：
```
http://127.0.0.1:12396/L1VzZXJzL3l1dGFvL0lkZWFQcm9qZWN0cy9pbnRlbGxpai1vcGVuY29kZS13ZWI=?session=ses_289e3c5f6ffeG6UQ70kEptBBoa
```

---

## 实现步骤

### TODO

- [x] 1. 添加 SQLite JDBC 依赖到 build.gradle.kts
- [x] 2. 创建 SessionHelper 对象，实现数据库查询逻辑
- [x] 3. 修改 MyToolWindowFactory.kt 的 loadProjectPage() 函数
- [x] 4. 修改 restartServer() 函数也添加 session 参数
- [x] 5. 运行构建检查，确保编译通过
- [x] 6. 提交代码更改

---

## 详细实现

### 任务 1：添加 SQLite JDBC 依赖

**文件**：`build.gradle.kts`

**修改**：在 `dependencies` 块中添加：
```kotlin
dependencies {
    // ... 现有依赖 ...
    
    // SQLite JDBC for session management
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
}
```

---

### 任务 2：创建 SessionHelper 对象

**文件**：`src/main/kotlin/com/github/xausky/opencodewebui/utils/SessionHelper.kt`（新建）

**完整代码**：
```kotlin
package com.github.xausky.opencodewebui.utils

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.sql.DriverManager

/**
 * Helper object for managing OpenCode sessions
 */
object SessionHelper {
    
    /**
     * Get the latest session ID for a specific project path
     * 
     * @param projectPath The project directory path
     * @return The latest session ID, or null if not found
     */
    fun getLatestSessionId(projectPath: String): String? {
        val homeDir = System.getenv("HOME") ?: return null
        val dbPath = File(homeDir, ".local/share/opencode/opencode.db")
        
        if (!dbPath.exists()) {
            thisLogger().info("OpenCode database not found at ${dbPath.absolutePath}")
            return null
        }
        
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbPath.absolutePath}").use { conn ->
                val sql = """
                    SELECT id FROM session 
                    WHERE directory = ? 
                    ORDER BY time_created DESC 
                    LIMIT 1
                """.trimIndent()
                
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, projectPath)
                    val rs = stmt.executeQuery()
                    
                    if (rs.next()) {
                        val sessionId = rs.getString("id")
                        thisLogger().info("Found session for project $projectPath: $sessionId")
                        return sessionId
                    }
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to query OpenCode session: ${e.message}")
        }
        
        return null
    }
}
```

---

### 任务 3：修改 loadProjectPage() 函数

**文件**：`src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt`

**当前代码**（约第 449-455 行）：
```kotlin
private fun loadProjectPage() {
    val projectPath = project.basePath ?: return
    val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
    val url = "http://$HOST:$PORT/$encodedPath"
    thisLogger().info("Loading page: $url")
    browser.loadURL(url)
}
```

**修改后**：
```kotlin
import com.github.xausky.opencodewebui.utils.SessionHelper
import java.util.Base64
import java.nio.charset.StandardCharsets

private fun loadProjectPage() {
    val projectPath = project.basePath ?: return
    val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
    
    // Try to get the latest session for this project
    val sessionId = SessionHelper.getLatestSessionId(projectPath)
    
    // Build URL with session parameter if available
    val url = if (sessionId != null) {
        "http://$HOST:$PORT/$encodedPath?session=$sessionId"
    } else {
        "http://$HOST:$PORT/$encodedPath"
    }
    
    thisLogger().info("Loading page: $url")
    browser.loadURL(url)
}
```

**需要添加的导入**（在文件顶部）：
```kotlin
import com.github.xausky.opencodewebui.utils.SessionHelper
import java.util.Base64
import java.nio.charset.StandardCharsets
```

---

### 任务 4：添加 Session ID 本地持久化（可选增强）

**文件**：`src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt`

**添加持久化函数**（在 companion object 中）：
```kotlin
import com.intellij.ide.util.PropertiesComponent

companion object {
    private const val SESSION_ID_KEY = "opencode.current.session.id"
    
    private fun saveCurrentSessionId(project: Project, sessionId: String) {
        PropertiesComponent.getInstance(project).setValue(SESSION_ID_KEY, sessionId)
    }
    
    private fun getCurrentSessionId(project: Project): String? {
        return PropertiesComponent.getInstance(project).getValue(SESSION_ID_KEY)
    }
}
```

**修改 loadProjectPage() 使用持久化**：
```kotlin
private fun loadProjectPage() {
    val projectPath = project.basePath ?: return
    val encodedPath = Base64.getEncoder().encodeToString(projectPath.toByteArray(StandardCharsets.UTF_8))
    
    // Priority 1: Check local persistence first
    var sessionId = getCurrentSessionId(project)
    
    // Priority 2: Query from database if not found locally
    if (sessionId == null) {
        sessionId = SessionHelper.getLatestSessionId(projectPath)
        if (sessionId != null) {
            saveCurrentSessionId(project, sessionId)
        }
    }
    
    // Build URL with session parameter if available
    val url = if (sessionId != null) {
        "http://$HOST:$PORT/$encodedPath?session=$sessionId"
    } else {
        "http://$HOST:$PORT/$encodedPath"
    }
    
    thisLogger().info("Loading page: $url (session: $sessionId)")
    browser.loadURL(url)
}
```

---

### 任务 5：测试流程

**测试步骤**：
1. 启动 IDE 并打开 OpenCode 工具窗口
2. 检查日志输出：`Loading page: http://127.0.0.1:12396/...?session=ses_xxx`
3. 验证是否恢复了之前的对话历史
4. 重启 IDE，再次打开工具窗口
5. 确认仍然恢复到同一个 session

**验证命令**：
```bash
# 检查当前项目的 session
sqlite3 ~/.local/share/opencode/opencode.db \
  "SELECT id FROM session WHERE directory = '$(pwd)' ORDER BY time_created DESC LIMIT 1"

# 查看 IDE 日志
# 在 IDE 中: Help -> Show Log in Finder/Explorer
```

---

## 实现要点

### ✅ 优势
1. **无网络依赖**：直接读取本地数据库，速度快
2. **可靠性高**：SQLite 是稳定的本地存储方案
3. **用户无感知**：自动恢复最近的对话，无需手动操作

### ⚠️ 注意事项
1. **数据库路径**：假设 OpenCode 使用默认路径 `~/.local/share/opencode/opencode.db`
2. **多项目支持**：通过 `directory` 字段区分不同项目的 session
3. **错误处理**：数据库查询失败时降级到无 session 模式

---

## 执行说明

运行 `/start-work session-resume` 让 Sisyphus 执行器实现功能。