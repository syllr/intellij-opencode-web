# json-parse-sessions-plan learnings

## Date: 2026-04-27

## Issue
- `parseSessions` 方法返回 0 个 session，但 API 返回了正确的 JSON 数据
- 原因是正则表达式太严格，无法正确匹配 JSON 对象

## Root Cause
原正则 `\{id.*?directory.*?time}` 要求字段顺序必须是 `id` -> `directory` -> `time`，但实际 API 返回的 JSON 字段顺序是 `id` -> `slug` -> `projectID` -> `directory` -> `title` -> `version` -> `time`

## Attempted Solutions

### 1. IntelliJ JSON API (FAILED)
```kotlin
import com.intellij.util.json.JSON
import com.intellij.util.json.JSONArray
import com.intellij.util.json.JSONObject
```
**Result**: 编译失败，`com.intellij.util.json` 在 IntelliJ 插件中不可用

### 2. Simple Regex (SUCCESS)
用简单的正则表达式分别匹配每个字段：
```kotlin
val idRegex = Regex(""""id"\s*:\s*"([^"]+)"""")
val directoryRegex = Regex(""""directory"\s*:\s*"([^"]+)"""")
val createdRegex = Regex(""""created"\s*:\s*(\d+)""")
val archivedRegex = Regex(""""archived"\s*:\s*(\d+)""")
val sessionRegex = Regex("""\{[^{}]*"id"[^{}]*"directory"[^{}]*"time"[^{}]*\}""")
```

## Key Learnings
1. `com.intellij.util.json` 在 IntelliJ Platform 插件中不可用
2. 正则表达式需要灵活处理 JSON 字段顺序
3. 分别匹配每个字段比用一个大正则更可靠

## Test Command
```bash
curl -s "http://127.0.0.1:12396/session?directory=%2FUsers%2Fyutao%2FIdeaProjects%2Fhelloworld"
```
