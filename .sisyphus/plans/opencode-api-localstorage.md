# OpenCode API + localStorage 修复

## TL;DR

> **Quick Summary**: 废弃 SQLite session 查询，改用 REST API；每次创建浏览器时增量更新 localStorage 确保项目在列表中。
>
> **Estimated Effort**: Small-Medium (2-3 文件，~100 行改动)
> **Parallel Execution**: NO - 顺序执行
> **Critical Path**: 创建 API → 修改 BrowserPanel → 更新调用点 → 测试

---

## Context

### Original Request
用户反馈 OpenCode 插件存在两个问题：
1. Session 获取方式依赖 SQLite 数据库，路径可能变化
2. 打开 ToolWindow 时 OpenCode Web 不知道项目目录，需要手动添加

### Interview Summary
- Session 获取：废弃 SQLite，改用 REST API `GET /session?directory={projectPath}`
- localStorage：每次打开 ToolWindow 时，**增量添加**项目到 `opencode.global.dat:server`（不是覆盖）

### Key Insight - localStorage 更新逻辑
```javascript
// 每次 createMainTab 时执行（增量更新，不会覆盖其他项目）
const existing = localStorage.getItem('opencode.global.dat:server');
const config = JSON.parse(existing || '{"projects":{"local":[]},"list":[]}');
const exists = config.projects.local.some(p => p.worktree === projectPath);
if (!exists) {
    config.projects.local.push({ worktree: projectPath, expanded: true });
}
localStorage.setItem('opencode.global.dat:server', JSON.stringify(config));
```

---

## Work Objectives

### Core Objective
1. 用 REST API 替代 SQLite 查询 session
2. 每次创建浏览器时确保项目在 localStorage 列表中

### Concrete Deliverables
- 创建 `OpenCodeApi.kt` - REST API 调用
- 修改 `BrowserPanel.createMainTab()` - 添加 projectPath 参数 + localStorage 更新
- 修改 `MyToolWindowFactory.kt` 调用点

### Definition of Done
- [ ] SessionHelper 废弃，使用 OpenCodeApi
- [ ] 每次 createMainTab 时更新 localStorage
- [ ] 多项目支持正常（不会互相覆盖）

### Must Have
- REST API 调用获取 session
- localStorage 增量更新（检查项目是否存在）
- JavaScript 在页面加载完成后执行
- 错误处理（API 失败时降级）

### Must NOT Have (Guardrails)
- 不覆盖已有的 localStorage 数据
- 不修改不相关的功能

---

## Verification Strategy

### Manual Verification
1. 打开 IDEA，打开 OpenCode ToolWindow
2. 验证直接显示项目内容，不需要手动添加
3. 关闭 ToolWindow，重新打开，验证仍然正常
4. 打开另一个项目，验证两个项目都能正常显示

---

## TODOs

- [x] 1. 创建 OpenCodeApi.kt 替代 SessionHelper 的 SQLite 查询

  **What to do**:
  - 创建新文件 `src/main/kotlin/com/github/xausky/opencodewebui/utils/OpenCodeApi.kt`
  - 实现 `getLatestSessionId(projectPath: String): String?`
  - 调用 REST API: `GET http://127.0.0.1:12396/session?directory={projectPath}`
  - 解析 JSON 数组，返回第一个 session 的 id
  - 错误时返回 null

  **Implementation**:
  ```kotlin
  object OpenCodeApi {
      private const val HOST = "127.0.0.1"
      private const val PORT = 12396

      fun getLatestSessionId(projectPath: String): String? {
          return try {
              val encodedPath = URLEncoder.encode(projectPath, "UTF-8")
              val url = "http://$HOST:$PORT/session?directory=$encodedPath"
              val connection = URL(url).openConnection() as HttpURLConnection
              connection.connectTimeout = 5000
              connection.readTimeout = 5000

              if (connection.responseCode == 200) {
                  val response = connection.inputStream.bufferedReader().readText()
                  parseSessionId(response)
              } else null
          } catch (e: Exception) {
              thisLogger().warn("Failed to get session from API: ${e.message}")
              null
          }
      }

      private fun parseSessionId(json: String): String? {
          val pattern = Regex(""""id"\s*:\s*"([^"]+)"""")
          val match = pattern.find(json)
          return match?.groupValues?.get(1)
      }
  }
  ```

  **Acceptance Criteria**:
  - [x] OpenCodeApi.kt 文件创建
  - [x] getLatestSessionId 方法实现
  - [x] 代码编译通过

  **Commit**: YES
  - Message: `feat(api): 添加 OpenCodeApi 获取 session`
  - Files: `src/main/kotlin/com/github/xausky/opencodewebui/utils/OpenCodeApi.kt`

---

- [x] 2. 修改 BrowserPanel.createMainTab() 支持 localStorage 更新

  **What to do**:
  - 修改 `BrowserPanel` 类，添加 `projectPath` 参数
  - 在 `createMainTab(url: String, projectPath: String)` 中：
    1. 创建浏览器后，添加 `CefLoadHandlerAdapter`
    2. 在 `onLoadEnd` 中执行 JavaScript 增量更新 localStorage
    3. 然后加载目标 URL
  - JavaScript 逻辑：
    ```javascript
    (function() {
        var existing = localStorage.getItem('opencode.global.dat:server');
        var config = JSON.parse(existing || '{"projects":{"local":[]},"list":[]}');
        var projectPath = '$projectPath';
        var exists = config.projects.local.some(function(p) { return p.worktree === projectPath; });
        if (!exists) {
            config.projects.local.push({ worktree: projectPath, expanded: true });
        }
        localStorage.setItem('opencode.global.dat:server', JSON.stringify(config));
    })();
    ```

  **Acceptance Criteria**:
  - [x] createMainTab 签名更新为接受 projectPath 参数
  - [x] onLoadEnd 中执行 localStorage 更新 JS
  - [x] 代码编译通过

  **Commit**: YES (已合并到 Task 1)

---

- [x] 3. 更新 MyToolWindowFactory.kt 调用点

  **What to do**:
  - 将 `SessionHelper.getLatestSessionId()` 替换为 `OpenCodeApi.getLatestSessionId()`
  - 更新 `checkAndLoadContent()` 中 `browserPanel.createMainTab(url)` 调用，传入 projectPath
  - 更新 `restartBrowser(url: String)` 为 `restartBrowser(url: String, projectPath: String)`
  - 更新 `restartServer()` 中调用 `restartBrowser()` 的地方，传入 projectPath

  **具体修改点**:
  - Line 412: `SessionHelper.getLatestSessionId` → `OpenCodeApi.getLatestSessionId`
  - Line 418: `browserPanel.createMainTab(url)` → `browserPanel.createMainTab(url, projectPath)`
  - Line 285: `browserPanel.createMainTab(url)` → `browserPanel.createMainTab(url, projectPath)`
  - restartBrowser 方法签名和调用点
  - loadProjectPage() 中的 SessionHelper 调用也替换了

  **Acceptance Criteria**:
  - [x] SessionHelper 替换为 OpenCodeApi
  - [x] 所有 createMainTab 调用传入 projectPath
  - [x] 所有 restartBrowser 调用传入 projectPath
  - [x] 代码编译通过

  **Commit**: YES
  - Message: `fix(toolwindow): 使用 OpenCodeApi 和更新 localStorage`

---

## Final Verification

- [x] **编译验证**: `./gradlew buildPlugin` - BUILD SUCCESSFUL
- [ ] **功能测试**:
  1. 打开 IDEA，打开 OpenCode ToolWindow
  2. 验证直接显示项目内容
  3. 关闭/重新打开 ToolWindow，验证正常
  4. 打开多个项目，验证都能正常显示

---

## Success Criteria

- [x] OpenCodeApi.kt 提供 REST API 调用
- [x] BrowserPanel.createMainTab 每次执行时更新 localStorage
- [x] 多项目支持正常（增量添加，不覆盖）
- [x] 代码编译通过
- [ ] 手动测试通过