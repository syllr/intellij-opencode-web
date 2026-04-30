# opencode-file-sync learnings

## Date: 2026-04-30

## Task 1: 添加 okhttp-eventsource 依赖 + SSE 消费者

### 依赖变更
- `gradle/libs.versions.toml`: 新增 `okhttpEventsource = "4.1.0"` 版本和 library 声明
- `build.gradle.kts`: 新增 `implementation(libs.okhttpEventsource)`

### API 发现：okhttp-eventsource 4.1.0 的重大变化
初始 spec 使用的 `EventHandler` / `StubConnectionHandler` 在 4.1.0 中**不存在**。

**4.1.0 实际 API**：
- 回调模式使用 `BackgroundEventSource` + `BackgroundEventHandler`（而非 `EventSource` + `EventHandler`）
- `BackgroundEventHandler` 接口方法：
  - `onOpen() throws Exception`
  - `onMessage(String eventName, MessageEvent event) throws Exception`
  - `onComment(String comment) throws Exception`
  - `onClosed() throws Exception`
  - `onError(Throwable error)`
- `MessageEvent.data` 获取 SSE 数据（非 nullable String）
- 配置超时需要通过 `ConnectStrategy.http(uri).connectTimeout(...).readTimeout(...)` 而非 `EventSource.Builder` 直接设置
- `ErrorStrategy.alwaysContinue()` 依然存在且 API 相同

**构建流程**：
```kotlin
val connectStrategy = ConnectStrategy.http(uri)
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
val esBuilder = EventSource.Builder(connectStrategy).errorStrategy(ErrorStrategy.alwaysContinue())
val bgBuilder = BackgroundEventSource.Builder(this, esBuilder)
val bgEventSource = bgBuilder.build()
bgEventSource.start()
```

### 构建验证
- 唯一编译错误：`Unresolved reference 'OpenCodeDiffRefresher'` — 这是预期的前向引用，Task 2 创建该类后解决

### 文件清单
- `gradle/libs.versions.toml` — 第 6 行和第 19 行
- `build.gradle.kts` — 第 37 行
- `src/main/kotlin/com/github/xausky/opencodewebui/listeners/OpenCodeSSEConsumer.kt` — 新文件
