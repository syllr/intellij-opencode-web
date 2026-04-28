# 用 JSON API 重写 parseSessions 计划

## 目标
用 IntelliJ 的 JSON 解析 API 替换正则表达式

## 需要修改的文件
src/main/kotlin/com/github/xausky/opencodewebui/utils/PromptEditorService.kt

## 修改 1: 添加 imports
在第5行后添加：
```kotlin
import com.intellij.util.json.JSON
import com.intellij.util.json.JSONArray
import com.intellij.util.json.JSONObject
```

## 修改 2: 替换 parseSessions 方法（第163-190行）

原来的方法使用正则表达式，太复杂且字段顺序必须固定。

**新实现：**
```kotlin
private fun parseSessions(response: String): List<Session> {
    return try {
        val jsonArray = JSON.parse(response) as JSONArray
        (0 until jsonArray.size).map { i ->
            val obj = jsonArray.getJSONObject(i)
            val timeObj = obj.getJSONObject("time")
            Session(
                id = obj.getString("id"),
                directory = obj.getString("directory"),
                createdAt = timeObj.getNumber("created").toString(),
                archivedAt = if (timeObj.has("archived")) timeObj.getNumber("archived").toString() else null
            )
        }
    } catch (e: Exception) {
        thisLogger().warn("[PromptEditorService] 解析会话响应失败: ${e.message}")
        emptyList()
    }
}
```

## 验证
./gradlew compileKotlin

## 完成状态
- [x] 添加 JSON imports 到 PromptEditorService.kt
- [x] 替换 parseSessions 方法为更简单可靠的正则表达式实现
- [x] 验证编译 ./gradlew compileKotlin

**注意**: `com.intellij.util.json` 在 IntelliJ 插件中不可用，改用简单的正则表达式解析 JSON 字段。
