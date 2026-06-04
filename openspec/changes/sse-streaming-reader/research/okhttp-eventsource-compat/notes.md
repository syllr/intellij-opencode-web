# okhttp-eventsource 4.1.0 → 4.3.0 兼容性研究报告

> **日期**: 2026-06-04
> **目标**: 确认 `com.launchdarkly:okhttp-eventsource` 从 4.1.0 升级到 4.3.0 的 API 兼容性、新增功能及升级风险

---

## 1. 版本演进概览

| 版本 | 日期 | 关键变更 |
|------|------|---------|
| **4.0.0** | 2022-12-19 | **破坏性重写**: 同步 I/O 模型、新增 `background` 包、API 大改 |
| **4.0.1** | 2023-01-09 | 修复 streaming 模式 EOF 处理、新增 `StreamClosedWithIncompleteMessageException` |
| **4.1.0** | 2023-01-09 | 与 4.0.1 相同（语义化版本修正），当前项目所用版本 |
| **4.1.1** | 2023-06-27 | 升级 Guava 版本修复 CVE |
| **4.2.0** | 2026-01-22 | **新增 `ResponseHeaders` 支持 (#97)** |
| **4.3.0** | 2026-03-31 | **升级 OkHttp 到 4.12.0** |

> **来源**: [CHANGELOG.md](https://github.com/launchdarkly/okhttp-eventsource/blob/main/CHANGELOG.md)

---

## 2. Breaking Change 清单

### 结论: 4.1.0 → 4.3.0 无 breaking change

4.0.0 是最后（也是唯一）的破坏性版本。**4.1.0 → 4.2.0 → 4.3.0 全为向后兼容的 additive change**：

#### 当前项目使用的 API 在 4.3.0 中的状态

| API | 4.1.0 状态 | 4.3.0 状态 | 兼容性 |
|-----|-----------|-----------|--------|
| `BackgroundEventHandler` | ✅ 可用 | ✅ 不变 | ✅ |
| `BackgroundEventSource.Builder(handler, EventSource.Builder)` | ✅ 可用 | ✅ 不变 | ✅ |
| `EventSource.Builder(ConnectStrategy)` | ✅ 可用 | ✅ 不变 | ✅ |
| `ConnectStrategy.http(URI)` | ✅ 可用 | ✅ 不变 | ✅ |
| `ConnectStrategy.http(URI).connectTimeout(...)` | ✅ 可用 | ✅ 不变 | ✅ |
| `ConnectStrategy.http(URI).readTimeout(...)` | ✅ 可用 | ✅ 不变 | ✅ |
| `ErrorStrategy.alwaysContinue()` | ✅ 可用 | ✅ 不变 | ✅ |
| `EventSource.Builder.errorStrategy(ErrorStrategy)` | ✅ 可用 | ✅ 不变 | ✅ |
| `MessageEvent.getData()` / `messageEvent.data` | ✅ 可用 | ✅ 不变 | ✅ |
| `MessageEvent.getEventName()` | ✅ 可用 | ✅ 不变 | ✅ |
| `BackgroundEventHandler.onOpen()` | ✅ 可用 | ✅ 不变 | ✅ |
| `BackgroundEventHandler.onMessage(String, MessageEvent)` | ✅ 可用 | ✅ 不变 | ✅ |
| `BackgroundEventHandler.onClosed()` | ✅ 可用 | ✅ 不变 | ✅ |
| `BackgroundEventHandler.onComment(String)` | ✅ 可用 | ✅ 不变 | ✅ |
| `BackgroundEventHandler.onError(Throwable)` | ✅ 可用 | ✅ 不变 | ✅ |
| `ConnectionErrorHandler` / `ConnectionErrorHandler.Action` | ✅ 可用 | ✅ 不变 | ✅ |

> **代码验证**: 所有上述 API 签名已通过读取 [4.3.0 tag 源码](https://github.com/launchdarkly/okhttp-eventsource/tree/4.3.0) 逐类确认。

---

## 3. 4.2.0 / 4.3.0 新增 API 清单

### 3.1 4.2.0 — `ResponseHeaders` 支持

#### `ResponseHeaders` 接口

```java
// package com.launchdarkly.eventsource
// @since 4.2.0
public interface ResponseHeaders {
    int size();
    Header get(int index);        // 按序访问，保留重复 header 名
    String value(String name);   // 按名取值（case-insensitive），取第一个
    boolean isEmpty();

    public static final class Header {
        public String getName();
        public String getValue();
    }
}
```

#### `MessageEvent.getHeaders()`

```java
MessageEvent event = ...;
ResponseHeaders headers = event.getHeaders();
if (headers != null) {
    String contentType = headers.value("Content-Type");
}
```

新增的 `MessageEvent` 构造器（带 headers 参数）:

```java
// 字符串 data 版
public MessageEvent(String eventName, String data, String lastEventId, URI origin, ResponseHeaders headers)

// Reader data 版（streaming 模式）
public MessageEvent(String eventName, Reader dataReader, String lastEventId, URI origin, ResponseHeaders headers)
```

#### `StreamHttpErrorException.getHeaders()`

```java
try {
    eventSource.readMessage();
} catch (StreamHttpErrorException e) {
    ResponseHeaders headers = e.getHeaders();
    int statusCode = e.getStatusCode();
}
```

### 3.2 4.3.0 — OkHttp 升级

- 仅有依赖变更：**OkHttp 从 4.9.x/4.10.x → 4.12.0**
- 无 API 变更，无新增接口

---

## 4. `streamEventData(true)` + `getDataReader()` 详解

### 4.1 API 签名（4.3.0 确认可用）

```java
// EventSource.Builder
public Builder streamEventData(boolean enabled);  // @since 2.6.0
public Builder expectFields(Set<String> fields);   // @since 2.6.0

// MessageEvent
public Reader getDataReader();       // @since 2.6.0
public boolean isStreamingData();    // @since 2.6.0
public void close();                 // 释放 streaming 流
```

### 4.2 内部实现原理（4.3.0 源码分析）

`streamEventData(true)` 启用后，`EventParser` 不再将 data 行缓冲到 `ByteArrayOutputStream`，而是立刻创建一个 `IncrementalMessageDataInputStream`（`InputStream` 的子类），将其包装为 `InputStreamReader` 后传给 `MessageEvent`。

核心源码位于 `EventParser.java` 的 `IncrementalMessageDataInputStream` 内部类:

- 读取时回调 `EventParser.getNextChunk()` 从 HTTP 流中继续读取下一块数据
- 自动拼接多行 `data:` 字段，行间以 `\n` 分隔
- 遇到空行（SSE 消息结束标记）返回 `-1`（正常 EOF）
- 如果连接在消息未完整前断开，抛出 `StreamClosedWithIncompleteMessageException`

### 4.3 典型用法（非 streaming 模式，默认）

```kotlin
// 当前项目使用方式 — 完全兼容 4.3.0
class MyHandler : BackgroundEventHandler {
    override fun onMessage(event: String, messageEvent: MessageEvent) {
        val data = messageEvent.data  // 调用 getData()，已完全缓冲
        // ... 处理 data
    }
}
```

### 4.4 streaming 模式用法（需显式启用）

```kotlin
val esBuilder = EventSource.Builder(connectStrategy)
    .streamEventData(true)  // 启用 streaming
    .errorStrategy(ErrorStrategy.alwaysContinue())

val bgEventSource = BackgroundEventSource.Builder(handler, esBuilder).build()
```

```kotlin
// Handler 中消费 streaming data
override fun onMessage(event: String, messageEvent: MessageEvent) {
    val reader = messageEvent.dataReader  // java.io.Reader
    // 必须在 onMessage 返回前消费完 Reader！
    reader.use { r ->
        r.readText().let { data -> /* 处理大 payload */ }
    }
    // 或直接调用 getData() — 内部也会通过 Reader 全部读出
}
```

---

## 5. `getDataReader()` 在 `BackgroundEventSource` 异步派发中的安全性

### 5.1 关键发现

**在默认模式（`streamEventData(false)`）下**: ✅ **完全安全**

- `getDataReader()` 返回 `StringReader(data)`，数据已完全缓冲在内存中
- 无论 handler 在哪个线程执行，Reader 始终可读

**在 streaming 模式（`streamEventData(true)`）下**: ⚠️ **有约束**

1. `EventParser.IncrementalMessageDataInputStream` 读取的是**原始 HTTP 响应流**
2. `BackgroundEventSource` 的事件循环：
   - 流线程（streamExecutor）通过 `eventSource.readAnyEvent()` 阻塞读取事件
   - 拿到事件后通过 `dispatchEvent()` 丢给事件线程（eventsExecutor）
   - 流线程**立即**回到循环，调用 `readAnyEvent()` 读取下一条 SSE 消息
3. 流线程调用下一个 `readAnyEvent()` 时，`tryNextEvent()` 检测到上一个消息的 `IncrementalMessageDataInputStream` 尚未被消费：
   - 自动关闭旧流（`obsoleteStream.close()`）
   - 设置 `skipRestOfMessage = true`，跳过剩余数据
4. 事件线程中的 handler 如果此时还在读 Reader，会因为底层流已被关闭而数据丢失

### 5.2 约束总结

| 场景 | Reader 是否可读 | 要求 |
|------|---------------|------|
| 默认模式 + `BackgroundEventSource` | ✅ 完全安全 | Reader 基于 StringReader，不受流线程影响 |
| streaming 模式 + `BackgroundEventSource` | ⚠️ 可读但需及时 | **必须在 `onMessage()` 返回前消费完 Reader** |
| streaming 模式 + 同步 `EventSource` | ✅ 完全安全 | 同一线程顺序读取，Reader 在读取下个事件前有效 |

### 5.3 建议

对于当前项目（SSE 事件主要为 JSON payload，通常 < 10KB），**不需要启用 `streamEventData(true)`**。默认模式就够用，且与 `BackgroundEventSource` 配合最安全。

如果将来确实需要处理超大 SSE payload（> MB 级别），启用 streaming 模式时应确保:
1. Handler 在 `onMessage()` 中同步读完 `getDataReader()`
2. 或改为使用同步 `EventSource` + `readMessage()` 循环，自己控制线程

---

## 6. 源码级 API 签名对比

### `EventSource.Builder`（4.3.0，关键方法）

```java
// 构造器 (4.0.0+)
public Builder(ConnectStrategy connectStrategy)
public Builder(URI uri)
public Builder(URL url)
public Builder(HttpUrl url)

// 配置方法
public Builder errorStrategy(ErrorStrategy errorStrategy)
public Builder retryDelayStrategy(RetryDelayStrategy retryDelayStrategy)
public Builder retryDelay(long retryDelay, TimeUnit timeUnit)
public Builder retryDelayResetThreshold(long retryDelayResetThreshold, TimeUnit timeUnit)
public Builder lastEventId(String lastEventId)
public Builder readBufferSize(int readBufferSize)
public Builder streamEventData(boolean streamEventData)     // ✅ 4.3.0 可用
public Builder expectFields(Set<String> expectFields)        // ✅ 4.3.0 可用
public Builder logger(LDLogger logger)

// 构建
public EventSource build()
```

### `BackgroundEventSource.Builder`（4.3.0）

```java
// 构造器 — 与 4.1.0 完全一致
public Builder(BackgroundEventHandler handler, EventSource.Builder eventSourceBuilder)

// 配置方法
public Builder connectionErrorHandler(ConnectionErrorHandler handler)
public Builder eventsExecutor(Executor eventsExecutor)
public Builder streamExecutor(Executor streamExecutor)
public Builder maxEventTasksInFlight(int maxEventTasksInFlight)
public Builder threadBaseName(String threadBaseName)
public Builder threadPriority(Integer threadPriority)

// 构建
public BackgroundEventSource build()
```

### `BackgroundEventHandler` 接口（4.3.0）

```java
// 与 4.1.0 完全一致
public interface BackgroundEventHandler {
    void onOpen() throws Exception;
    void onClosed() throws Exception;
    void onMessage(String event, MessageEvent messageEvent) throws Exception;
    void onComment(String comment) throws Exception;
    void onError(Throwable t);
}
```

> **证据**: 所有接口签名已验证自 [4.3.0 tag 源码](https://github.com/launchdarkly/okhttp-eventsource/tree/4.3.0/src/main/java/com/launchdarkly/eventsource)

---

## 7. 升级风险评估

### 风险评估表

| 维度 | 评估 | 详情 |
|------|------|------|
| **API 兼容性** | ✅ **无风险** | 当前项目使用的所有 API 在 4.3.0 签名未变 |
| **行为变化** | ✅ **无风险** | 4.2.0/4.3.0 无语义变更，仅 additive change |
| **OkHttp 升级** | ✅ **低风险** | 4.3.0 依赖 OkHttp 4.12.0；当前项目用 IDEA 2026.1 平台（捆绑 OkHttp 4.x），无冲突 |
| **Guava 升级** | ✅ **低风险** | 4.1.1 已升级 Guava 修复 CVE，4.3.0 继承此修复 |
| **SSE 解析** | ✅ **无变更** | SSE 解析逻辑自 4.0.0 以来未变 |
| **构建兼容性** | ✅ **无风险** | Gradle 依赖声明不变，只需改版本号 |
| **测试** | ⚠️ **建议回归** | 建议运行插件的 SSE 相关集成测试，验证连接建立/事件消费/重连正常 |

### 升级操作

**仅需修改**: `gradle/libs.versions.toml` 第 6 行:

```diff
- okhttpEventsource = "4.1.0"
+ okhttpEventsource = "4.3.0"
```

**无需修改任何源码**。

### 升级收益

1. **更稳定的 OkHttp 4.12.0** — 包含大量 bug 修复和安全更新
2. **`ResponseHeaders` API** — 将来可用来读取 HTTP 响应头（如 `Retry-After`、`Content-Type`）
3. **更小的技术债** — 从 2023 年版本升级到 2026 年最新版

---

## 8. 参考链接

- [okhttp-eventsource GitHub](https://github.com/launchdarkly/okhttp-eventsource)
- [CHANGELOG.md (main)](https://github.com/launchdarkly/okhttp-eventsource/blob/main/CHANGELOG.md)
- [4.3.0 Release](https://github.com/launchdarkly/okhttp-eventsource/releases/tag/4.3.0)
- [4.2.0 Release](https://github.com/launchdarkly/okhttp-eventsource/releases/tag/4.2.0)
- [EventSource.java (4.3.0)](https://github.com/launchdarkly/okhttp-eventsource/blob/4.3.0/src/main/java/com/launchdarkly/eventsource/EventSource.java)
- [MessageEvent.java (4.3.0)](https://github.com/launchdarkly/okhttp-eventsource/blob/4.3.0/src/main/java/com/launchdarkly/eventsource/MessageEvent.java)
- [BackgroundEventSource.java (4.3.0)](https://github.com/launchdarkly/okhttp-eventsource/blob/4.3.0/src/main/java/com/launchdarkly/eventsource/background/BackgroundEventSource.java)
- [BackgroundEventHandler.java (4.3.0)](https://github.com/launchdarkly/okhttp-eventsource/blob/4.3.0/src/main/java/com/launchdarkly/eventsource/background/BackgroundEventHandler.java)
- [EventParser.java (4.3.0)](https://github.com/launchdarkly/okhttp-eventsource/blob/4.3.0/src/main/java/com/launchdarkly/eventsource/EventParser.java)
- [ResponseHeaders.java (4.3.0)](https://github.com/launchdarkly/okhttp-eventsource/blob/4.3.0/src/main/java/com/launchdarkly/eventsource/ResponseHeaders.java)
