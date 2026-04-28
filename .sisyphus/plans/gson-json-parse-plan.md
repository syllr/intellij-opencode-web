# 使用 Gson 解析 JSON 计划

## 目标
使用 Gson 库替换正则表达式来解析 JSON

## 需要修改的文件

### 1. gradle/libs.versions.toml
添加 Gson 依赖：
```toml
[versions]
gson = "2.10.1"

[libraries]
gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }
```

### 2. build.gradle.kts
添加 Gson 依赖：
```kotlin
implementation(libs.gson)
```

### 3. src/main/kotlin/.../PromptEditorService.kt
使用 Gson 解析 JSON：
```kotlin
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private val gson = Gson()

private fun parseSessions(response: String): List<Session> {
    return try {
        val type = object : TypeToken<List<SessionResponse>>() {}.type
        val sessions: List<SessionResponse> = gson.fromJson(response, type)
        sessions.map { it.toSession() }
    } catch (e: Exception) {
        thisLogger().warn("[PromptEditorService] 解析会话响应失败: ${e.message}")
        emptyList()
    }
}

private data class SessionResponse(
    val id: String,
    val directory: String,
    val time: TimeData
) {
    data class TimeData(
        val created: Long,
        val archived: Long? = null
    )

    fun toSession() = Session(
        id = id,
        directory = directory,
        createdAt = time.created.toString(),
        archivedAt = time.archived?.toString()
    )
}
```

## 验证
./gradlew compileKotlin
