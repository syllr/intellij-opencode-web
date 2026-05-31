# IntelliJ 插件部署和 OpenCode 插件系统详解

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件与 opencode 联动提供部署和插件系统技术指导

---

## 1. IntelliJ 插件部署

### 1.1 插件打包

**使用 Gradle IntelliJ Plugin 构建**：

```kotlin
// build.gradle.kts
plugins {
    id("org.jetbrains.intellij") version "2.0.0"
}

intellij {
    version = "2026.1"
    type = "IC"
    plugins = listOf("java", "git")
}

tasks {
    patchPluginXml {
        sinceBuild.set("242")
        untilBuild.set("261.*")
    }
    buildPlugin {
        // 输出到 build/libs/*.zip
    }
}
```

### 1.2 发布到 JetBrains Marketplace

```kotlin
// build.gradle.kts
tasks {
    publishPlugin {
        token.set(System.getenv("JETBRAINS_MARKETPLACE_TOKEN"))
    }
}
```

### 1.3 版本管理

| 版本号格式 | 说明   |
| ---------- | ------ |
| `231`      | 2023.1 |
| `242`      | 2024.2 |
| `261`      | 2026.1 |

### 1.4 兼容性验证

```bash
./gradlew verifyPlugin
```

---

## 2. OpenCode 插件系统

### 2.1 插件定义

```typescript
// 插件入口函数类型
export type Plugin = (input: PluginInput, options?: PluginOptions) => Promise<Hooks>;

// 插件接收的输入上下文
export type PluginInput = {
  client: ReturnType<typeof createOpencodeClient>;
  project: Project;
  directory: string;
  worktree: string;
  experimental_workspace: {
    register(type: string, adapter: WorkspaceAdapter): void;
  };
  serverUrl: URL;
  $: BunShell;
};
```

### 2.2 Hooks 系统

```typescript
export interface Hooks {
  dispose?: () => Promise<void>;
  event?: (input: { event: Event }) => Promise<void>;
  config?: (input: Config) => Promise<void>;
  tool?: { [key: string]: ToolDefinition };
  auth?: AuthHook;
  provider?: ProviderHook;
  "chat.message"?: (input, output) => Promise<void>;
  "chat.params"?: (input, output) => Promise<void>;
  "permission.ask"?: (input, output) => Promise<void>;
  "tool.execute.before"?: (input, output) => Promise<void>;
  "tool.execute.after"?: (input, output) => Promise<void>;
}
```

### 2.3 工具定义

```typescript
export type ToolContext = {
  sessionID: string;
  messageID: string;
  agent: string;
  directory: string;
  worktree: string;
  abort: AbortSignal;
  metadata(input: { title?: string; metadata?: { [key: string]: any } }): void;
  ask(input: AskInput): Promise<void>;
};

export type ToolResult =
  | string
  | {
      title?: string;
      output: string;
      metadata?: { [key: string]: any };
      attachments?: ToolAttachment[];
    };
```

### 2.4 插件配置

```json
// opencode.json
{
  "plugin": [
    "my-plugin",
    "my-plugin@1.0.0",
    "file://./my-local-plugin",
    ["my-plugin", { "option": "value" }]
  ]
}
```

---

## 3. 参考资源

| 资源                   | URL                                                                                    |
| ---------------------- | -------------------------------------------------------------------------------------- |
| JetBrains Marketplace  | https://plugins.jetbrains.com/developers                                               |
| Gradle IntelliJ Plugin | https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html |
| OpenCode Plugin API    | /Users/yutao/Projects/opencode/packages/plugin/src/index.ts                            |
| OpenCode Plugin Loader | /Users/yutao/Projects/opencode/packages/opencode/src/plugin/loader.ts                  |

---

## 4. 下一步行动

1. **实现插件打包** - 使用 Gradle IntelliJ Plugin
2. **配置 Marketplace 发布** - 设置 CI/CD 自动发布
3. **创建 OpenCode 插件** - 实现自定义工具和 Hook
4. **测试插件兼容性** - 使用 Plugin Verifier 验证
