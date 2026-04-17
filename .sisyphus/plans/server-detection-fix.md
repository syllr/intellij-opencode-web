# OpenCode 插件 Server 启动逻辑修复

## TL;DR
修复插件与外部启动的 OpenCode Server 的冲突问题，改为先检测端口，有服务则复用。

## Context
用户反映插件启动 OpenCode Server 的方式可能导致服务器卡住，而手动启动则正常。需要修复并发和竞态问题。

## 问题分析

### 当前问题
1. **`hasInitializedOnStartup` 竞态条件** - 标志位在服务器真正就绪前就设为 true
2. **`stopServer()` 误杀** - 会 kill 掉用户手动启动的服务器
3. **启动后立即加载页面** - 没有等待服务器完全就绪

### 修复方案
1. 移除 `hasInitializedOnStartup` 逻辑 - 简化流程
2. 先检查端口 - 有服务就复用，不启动新进程也不杀已有服务
3. 只有端口没开时才启动新服务
4. 启动服务后等待端口真正就绪才加载页面

## Work Objectives

### Core Objective
修复 server 检测和启动逻辑，避免误杀外部服务和竞态条件。

### Must Have
- 启动时先检查端口是否有服务在运行
- 有服务则复用，不启动新进程
- 只有端口没开时才启动新服务
- 移除不必要的 `stopServer()` 调用

### Must NOT Have
- 不再主动 kill 端口上的进程（避免误杀外部服务）
- 不再使用 `hasInitializedOnStartup` 标志位

## Execution Strategy

### 修改 `checkAndLoadContent()` (MyToolWindowFactory.kt:410-452)

**当前逻辑（有 bug）:**
```kotlin
if (hasInitializedOnStartup.get()) {
    if (checkPortOpen(HOST, PORT)) {
        serverRunning.set(true)
        loadProjectPage()
    } else {
        startOpenCodeServer()
    }
} else {
    hasInitializedOnStartup.set(true)
    stopServer()        // ← 问题：会误杀外部服务
    startOpenCodeServer()
}
```

**修复后逻辑:**
```kotlin
if (checkPortOpen(HOST, PORT)) {
    // 端口开放，复用已有服务器（不杀、不启动新）
    thisLogger().info("Port $PORT is already open, reusing existing server")
    serverRunning.set(true)
    loadProjectPage()
} else {
    // 端口未开放，启动新服务器
    thisLogger().info("Port $PORT is not open, starting opencode serve...")
    startOpenCodeServer()
}
```

### 修改 `startOpenCodeServer()` (MyToolWindowFactory.kt:499-546)

**修复：移除 `serverProcess.set(process)` 前的检查逻辑**

当前问题：`startServerInternal` 和 `startOpenCodeServer` 都有启动逻辑，可以合并简化。

### 修改 `restartServer()` (MyToolWindowFactory.kt:80-111)

**修复：不再调用 `stopServer()`，改为只杀自己启动的进程**

```kotlin
fun restartServer(project: Project?) {
    // 只杀自己启动的进程，不杀端口上的其他进程
    serverProcess.get()?.let { process ->
        if (process.isAlive) {
            process.destroy()
        }
    }
    serverProcess.set(null)

    // 然后启动新服务
    ...
}
```

### 修改 `checkServerHealth()` (MyToolWindowFactory.kt:487-497)

**修复：健康检查失败时不再启动新进程**

如果端口不开放，应该报错或让用户手动重启，而不是自动启动。

## TODOs

- [x] 1. 修复 `checkAndLoadContent()` - 简化逻辑，先检查端口
- [x] 2. 修复 `restartServer()` - 不再调用 `stopServer()`
- [x] 3. 修复 `checkServerHealth()` - 健康检查失败不再自动重启
- [x] 4. 研究 OpenCode 更新接口（已完成）
- [x] 5. 实现每小时自动更新检查 (POST /global/upgrade)
- [x] 6. 构建并测试
- [x] 7. 发布新版本 (v1.0.7)

## Verification Strategy

手动测试流程:
1. 先手动启动 `opencode serve --port 12396`
2. 打开插件 → 应该复用已有服务，日志显示 "reusing existing server"
3. 关闭插件 → 服务器应该继续运行（不被 kill）
4. 再次打开插件 → 应该继续复用

## Success Criteria
- 外部启动的 server 不被插件误杀
- 插件能正确复用已有服务
- 插件启动的服务也能正常工作
