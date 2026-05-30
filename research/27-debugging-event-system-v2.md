# IntelliJ 插件调试和 OpenCode 事件系统 v2 详解

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件与 opencode 联动提供调试和事件系统技术指导

---

## 1. IntelliJ 插件调试

### 1.1 远程调试配置

**使用 gradle-intellij-plugin 的 runIde 任务**：

```kotlin
// build.gradle.kts
intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                    )
                }
            }
        }
    }
}
```

**运行调试模式**：

```bash
./gradlew runIde --debug
```

### 1.2 IntelliJ 内置调试工具

**日志系统**：

```kotlin
import com.intellij.openapi.diagnostic.logger
private val LOG = logger<MyClass>()

// 或使用 thisLogger() 便捷方法
thisLogger().error("some code failed", e)
```

**日志级别启用**：

- 默认：`INFO` 及以上写入 `idea.log`
- 启用 `DEBUG`/`TRACE`：Help → Diagnostic Tools → Debug Log Settings

**日志文件位置**：

- 开发实例：`build/idea-sandbox/IU-2026.1/log/`
- 用户实例：Help → Show Log in Finder/Explorer

### 1.3 Internal Mode（内部模式）

```
# 创建 .idea/idea.vmoptions 或在 IDE 配置中添加
idea.is.internal=true
```

**可用工具**：

- Help → Debug → UI Inspector：查看 UI 元素的 Action ID 和 Group ID
- Help → Open Log in Editor：直接在编辑器中打开当前运行的 IDE 日志

### 1.4 断点类型

| 类型                  | 用途          | 性能影响                  |
| --------------------- | ------------- | ------------------------- |
| Line Breakpoints      | 普通断点      | 低                        |
| Method Breakpoints    | 方法入口/出口 | ⚠️ **高**，会显著拖慢调试 |
| Exception Breakpoints | 捕获异常      | 中                        |
| Field Watchpoints     | 字段访问      | 中                        |

> ⚠️ **警告**：`Method breakpoints may dramatically slow down debugging`

### 1.5 JCEF 调试

**JCEF 日志启用**：

```kotlin
// 在运行配置中添加
-Dcef.logSeverity=verbose
-Dchromium.logFile=cef_debug.log
-Dchromium.logLevel=999
```

**JCEF DevTools 远程调试**：

```kotlin
// 启用 DevTools
browser.devToolsProtocolConnection?.openDevToolsFrame()
```

### 1.6 性能问题调试

**线程转储分析**：

```bash
# 找到 IDE 进程
jps -l | grep idea

# 生成线程转储
jstack -l <PID> > thread_dump.txt
```

**CPU 性能分析**：

```bash
# 1. 找到最高 CPU 的线程
top -Hp <PID>

# 2. 转换线程 ID 为十六进制
printf "%x\n" <TID>

# 3. 查看具体堆栈
jstack <PID> | grep <HEX_TID> -A 30
```

**内存分析**：

```bash
# 生成堆转储
jmap -dump:format=b,file=heap.bin <PID>

# 或使用 jcmd
jcmd <PID> GC.heap_dump heap.bin
```

---

## 2. OpenCode 事件系统 v2

### 2.1 v1 vs v2 核心差异

| 维度         | v1 (Bus/SyncEvent)                                | v2 (EventV2)                                                    |
| ------------ | ------------------------------------------------- | --------------------------------------------------------------- |
| **架构**     | 分散：Bus + SyncEvent + GlobalBus 各自独立        | 统一：EventV2 为核心 + Bridge 桥接旧系统                        |
| **事件定义** | `BusEvent.define(type, schema)` 独立注册          | `EventV2.define({ type, version, aggregate, schema })` 聚合信息 |
| **订阅方式** | `Bus.subscribe(def, callback)` / `subscribeAll()` | `EventV2.subscribe(def)` 返回 Stream / `sync(handler)` 全局钩子 |
| **重连支持** | 无                                                | Location 机制（workspaceID + directory）上下文感知路由          |
| **类型安全** | 事件体无 schema                                   | Payload 定义内嵌完整 data schema，端到端类型推断                |
| **版本控制** | 无                                                | 支持 version + aggregate 字段，事件溯源                         |

### 2.2 EventV2 核心架构

```typescript
// packages/core/src/event.ts
export const AgentSwitched = EventV2.define({
  type: "session.next.agent.switched",
  aggregate: "sessionID",
  version: 1,
  schema: {
    timestamp: V2Schema.DateTimeUtcFromMillis,
    sessionID: Session.ID,
    agent: Schema.String,
  },
});

// 事件 Payload 结构
export type Payload<D extends Definition> = {
  readonly id: ID;
  readonly type: D["type"];
  readonly data: Data<D>;
  readonly version?: number;
  readonly location?: Location.Ref;
  readonly metadata?: Record<string, unknown>;
};
```

### 2.3 订阅方式

**v2 订阅方式（推荐）**：

```typescript
import { EventV2 } from "@opencode-ai/core/event";
import { SessionEvent } from "@opencode-ai/core/session-event";

// 方式1：订阅特定类型（强类型）
const stream = EventV2.subscribe(SessionEvent.AgentSwitched);
Effect.runPromise(Stream.runCollect(stream)).then((events) => {
  events.forEach((evt) => {
    console.log(evt.data.agent); // 完全类型安全
  });
});

// 方式2：全局 sync 钩子（所有事件）
const unsubscribe =
  yield *
  EventV2.sync((event) => {
    console.log(`[${event.type}]`, event.data);
  });
```

**v1 订阅方式（向后兼容）**：

```typescript
import { Bus } from "@/bus";

// 订阅特定事件
Bus.subscribe(Updated, (event) => {
  console.log(event.properties.info.title);
});

// 通配符订阅（所有事件）
Bus.subscribeAll((event) => {
  console.log(event.type, event.properties);
});
```

### 2.4 Session 事件 v2 定义

```typescript
// Session 生命周期事件
SessionEvent.Prompted; // 用户发送 prompt
SessionEvent.Synthetic; // 合成消息

// Step 事件
SessionEvent.Step.Started; // Step 开始
SessionEvent.Step.Ended; // Step 结束
SessionEvent.Step.Failed; // Step 失败

// Text 流事件
SessionEvent.Text.Started; // 文本开始
SessionEvent.Text.Delta; // 文本增量
SessionEvent.Text.Ended; // 文本结束

// Reasoning 事件
SessionEvent.Reasoning.Started;
SessionEvent.Reasoning.Delta;
SessionEvent.Reasoning.Ended;

// Tool 事件
SessionEvent.Tool.Input.Started;
SessionEvent.Tool.Input.Delta;
SessionEvent.Tool.Input.Ended;
SessionEvent.Tool.Called; // 工具被调用
SessionEvent.Tool.Success; // 工具成功
SessionEvent.Tool.Failed; // 工具失败
```

### 2.5 重连机制

```typescript
// Location 上下文机制
export const Ref = Schema.Struct({
  directory: Schema.String,
  workspaceID: Schema.optional(Schema.String),
});

// Location 传播通过 InstanceRef + WorkspaceRef 实现
// idleTimeToLive: 5 minutes
```

### 2.6 SSE 重连策略

```typescript
// 客户端重连逻辑
class EventSource {
  private retryDelay = 1000;
  private maxRetryDelay = 30000;

  connect() {
    const es = new EventSource("/api/event/subscribe");

    es.onopen = () => {
      this.retryDelay = 1000; // 重置延迟
    };

    es.onerror = () => {
      setTimeout(() => this.connect(), this.retryDelay);
      this.retryDelay = Math.min(this.retryDelay * 2, this.maxRetryDelay);
    };
  }
}
```

---

## 3. IDE 集成推荐模式

### 3.1 事件监听

```typescript
// 使用 Bus.subscribeAll 进行 IDE 事件监听
class MyPlugin {
  private unsubscribe?: () => void;

  initialize() {
    this.unsubscribe = Bus.subscribeAll((event) => {
      this.handleEvent(event);
    });
  }

  handleEvent(event: any) {
    switch (event.type) {
      case "session.next.step.ended":
        this.onStepEnded(event.properties);
        break;
      case "session.next.tool.called":
        this.onToolCalled(event.properties);
        break;
    }
  }
}
```

### 3.2 事件处理注意事项

```typescript
// ✅ 推荐：使用 Stream 进行背压处理
const events = EventV2.subscribe(SessionEvent.Text.Delta);
Stream.runForEach(events, (event) => Effect.sync(() => updateUI(event.data.delta)));

// ✅ 推荐：使用 addFinalizer 清理资源
yield * Effect.addFinalizer(() => Effect.sync(() => cleanup()));

// ✅ 推荐：订阅时指定完整类型
EventV2.subscribe(SessionEvent.Tool.Success).pipe(Stream.map((event) => event.data.callID));
```

---

## 4. 参考资源

| 资源                   | URL                                                                     |
| ---------------------- | ----------------------------------------------------------------------- |
| IntelliJ Platform SDK  | https://plugins.jetbrains.com/docs/intellij/                            |
| gradle-intellij-plugin | https://github.com/JetBrains/gradle-intellij-plugin                     |
| JCEF Documentation     | https://chromiumembedded.github.io/java-cef/                            |
| EventV2 Core           | /Users/yutao/Projects/opencode/packages/core/src/event.ts               |
| SessionEvent v2        | /Users/yutao/Projects/opencode/packages/core/src/session-event.ts       |
| EventV2Bridge          | /Users/yutao/Projects/opencode/packages/opencode/src/event-v2-bridge.ts |
| Bus System             | /Users/yutao/Projects/opencode/packages/opencode/src/bus/index.ts       |

---

## 5. 下一步行动

1. **实现调试配置** - 设置 runIde 调试模式
2. **启用详细日志** - 配置 DEBUG 日志级别
3. **实现事件监听** - 使用 Bus.subscribeAll 监听事件
4. **测试 SSE 重连** - 验证重连策略
