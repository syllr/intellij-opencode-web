# OpenCode 工具系统和 Agent 系统详解

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件与 opencode 联动提供工具系统和 Agent 系统技术指导

---

## 1. 工具系统架构

### 1.1 核心组件

| 组件           | 文件                  | 说明             |
| -------------- | --------------------- | ---------------- |
| `Tool.define`  | `tool/tool.ts`        | 工具定义核心接口 |
| `Tool.Context` | `tool/tool.ts`        | 工具执行上下文   |
| `ToolRegistry` | `tool/registry.ts`    | 工具注册中心     |
| `Permission`   | `permission/index.ts` | 权限系统         |

### 1.2 工具定义模式

```typescript
// tool/tool.ts
export interface Def<Parameters, M extends Metadata = Metadata> {
  id: string; // 工具唯一标识
  description: string; // 工具描述（供 LLM 理解）
  parameters: Parameters; // Effect Schema 参数定义
  jsonSchema?: JSONSchema7; // JSON Schema（供外部使用）
  execute(args, ctx): Effect.Effect<ExecuteResult<M>>; // 执行函数
}

// 使用 Tool.define 创建工具
export const MyTool = Tool.define(
  "my_tool",
  Effect.gen(function* () {
    const fs = yield* AppFileSystem.Service;

    return {
      description: "工具描述",
      parameters: Schema.Struct({
        /* 参数定义 */
      }),
      execute: (params, ctx) =>
        Effect.gen(function* () {
          // 执行逻辑
          return { title, output, metadata };
        }),
    };
  }),
);
```

### 1.3 内置工具列表

| 工具 ID     | 文件                | 功能           |
| ----------- | ------------------- | -------------- |
| `read`      | `tool/read.ts`      | 读取文件/目录  |
| `write`     | `tool/write.ts`     | 写入文件       |
| `edit`      | `tool/edit.ts`      | 智能文本编辑   |
| `glob`      | `tool/glob.ts`      | glob 模式匹配  |
| `grep`      | `tool/grep.ts`      | 正则搜索       |
| `shell`     | `tool/shell.ts`     | Shell 命令执行 |
| `task`      | `tool/task.ts`      | 子 Agent 任务  |
| `skill`     | `tool/skill.ts`     | Skill 加载     |
| `todo`      | `tool/todo.ts`      | Todo 列表管理  |
| `lsp`       | `tool/lsp.ts`       | LSP 语言服务   |
| `webfetch`  | `tool/webfetch.ts`  | Web 内容获取   |
| `websearch` | `tool/websearch.ts` | Web 搜索       |

---

## 2. Agent 系统架构

### 2.1 Agent 定义结构

```typescript
// agent/agent.ts
export const Info = Schema.Struct({
  name: Schema.String,
  description: Schema.optional(Schema.String),
  mode: Schema.Literals(["subagent", "primary", "all"]),
  native: Schema.optional(Schema.Boolean),
  hidden: Schema.optional(Schema.Boolean),
  permission: Permission.Ruleset,
  model: Schema.optional(
    Schema.Struct({
      modelID: ModelID,
      providerID: ProviderID,
    }),
  ),
  prompt: Schema.optional(Schema.String),
  options: Schema.Record(Schema.String, Schema.Unknown),
  steps: Schema.optional(Schema.Finite),
});
```

### 2.2 Agent 分类

| Agent     | 模式     | 用途                     |
| --------- | -------- | ------------------------ |
| `build`   | primary  | 默认执行 Agent           |
| `plan`    | primary  | 计划模式（禁用编辑工具） |
| `general` | subagent | 通用多步骤任务           |
| `explore` | subagent | 代码库探索               |
| `scout`   | subagent | 文档和依赖源专家         |

### 2.3 Agent 配置来源

**配置文件** (`opencode.json`):

```json
{
  "agent": {
    "code-review": {
      "description": "代码审查专家",
      "mode": "subagent",
      "permission": {
        "read": "allow",
        "bash": "allow",
        "edit": "deny"
      }
    }
  }
}
```

**Markdown 文件** (`{agent,agents}/code-review.md`):

```markdown
---
name: code-review
description: 代码审查专家
mode: subagent
permission:
  read: allow
  bash: allow
  edit: deny
---

你是代码审查专家...
```

---

## 3. 权限系统

### 3.1 权限规则结构

```typescript
// permission/index.ts
export const Rule = Schema.Struct({
  permission: Schema.String, // 工具名称
  pattern: Schema.String, // glob 模式
  action: Action, // "allow" | "deny" | "ask"
});

export type Ruleset = Rule[];
```

### 3.2 权限评估流程

```typescript
const ask = Effect.fn("Permission.ask")(function* (input: AskInput) {
  for (const pattern of request.patterns) {
    const rule = evaluate(request.permission, pattern, ruleset, approved);
    if (rule.action === "deny") {
      return yield* new DeniedError({ ruleset });
    }
    if (rule.action === "allow") continue;
    needsAsk = true;
  }
  // 发布 permission.asked 事件等待用户响应
});
```

### 3.3 子 Agent 权限派生

```typescript
// agent/subagent-permissions.ts
export function deriveSubagentSessionPermission(input: {
  parentSessionPermission: Permission.Ruleset;
  parentAgent: Agent.Info | undefined;
  subagent: Agent.Info;
}): Permission.Ruleset {
  // 1. 继承父 Agent 的 edit 类拒绝规则
  // 2. 继承父 Session 的 deny 和 external_directory 规则
  // 3. 如果子 agent 没有权限，拒绝 todowrite 和 task
  return [
    ...parentAgentDenies,
    ...input.parentSessionPermission.filter(
      (rule) => rule.permission === "external_directory" || rule.action === "deny",
    ),
  ];
}
```

---

## 4. 创建自定义工具

### 4.1 最小工具示例

```typescript
// my-tool.ts
import { Effect, Schema } from "effect";
import * as Tool from "./tool";
import { AppFileSystem } from "@opencode-ai/core/filesystem";

export const Parameters = Schema.Struct({
  message: Schema.String.annotate({ description: "要显示的消息" }),
});

export const MyTool = Tool.define(
  "my_custom_tool",
  Effect.gen(function* () {
    const fs = yield* AppFileSystem.Service;

    return {
      description: "这是一个自定义工具",
      parameters: Parameters,
      execute: (params, ctx) =>
        Effect.gen(function* () {
          return {
            title: "操作成功",
            output: `你发送的消息是: ${params.message}`,
            metadata: { custom: true },
          };
        }).pipe(Effect.orDie),
    };
  }),
);
```

### 4.2 工具放置位置

```
{project-root}/
└── {tool,tools}/           # 自定义工具目录
    └── my-tool.ts          # 自动加载
```

---

## 5. 文件系统交互

### 5.1 Read 工具架构

```typescript
// tool/read.ts
execute: (params, ctx) =>
  Effect.gen(function* () {
    const instance = yield* InstanceState.context;
    let filepath = params.filePath;

    // 路径解析
    if (!path.isAbsolute(filepath)) {
      filepath = path.resolve(instance.directory, filepath);
    }

    // 权限检查
    yield* ctx.ask({
      permission: "read",
      patterns: [path.relative(instance.worktree, filepath)],
      always: ["*"],
      metadata: {},
    });

    // 文件类型检测
    const stat = yield* fs.stat(filepath);

    if (stat.type === "Directory") {
      const items = yield* fs.readDirectoryEntries(filepath);
      return { title, output, metadata };
    }

    // 流式读取大文件
    const lines = yield* lines(filepath, { limit, offset });
    return { title, output: content, metadata };
  });
```

### 5.2 Edit 工具架构

```typescript
// tool/edit.ts - 多种匹配策略
const replacers = [
  SimpleReplacer, // 精确匹配
  LineTrimmedReplacer, // 去除空白后匹配
  BlockAnchorReplacer, // 块锚点匹配
  WhitespaceNormalizedReplacer, // 空白符归一化
  IndentationFlexibleReplacer, // 缩进灵活匹配
];

// 文件锁机制
const locks = new Map<string, Semaphore.Semaphore>();

// Diff 生成与权限请求
const diff = createTwoFilesPatch(filepath, filepath, oldContent, newContent);
yield *
  ctx.ask({
    permission: "edit",
    patterns: [path.relative(instance.worktree, filepath)],
    metadata: { filepath, diff },
  });
```

---

## 6. IDE 集成要点

### 6.1 权限桥接

```kotlin
// IDEA 插件中的权限处理
class PermissionBridge {
    fun handlePermissionRequest(request: PermissionRequest): PermissionReply {
        // 显示权限请求对话框
        val dialog = PermissionDialog(request)
        val result = dialog.showAndGet()

        return when (result) {
            PermissionResult.ALWAYS -> Reply("always")
            PermissionResult.ONCE -> Reply("once")
            PermissionResult.REJECT -> Reply("reject")
        }
    }
}
```

### 6.2 Session 管理

```kotlin
// IDEA 插件中的 Session 管理
class SessionManager(private val apiClient: OpenCodeApiClient) {
    suspend fun createSession(project: Project): String {
        val response = apiClient.createSession(project.basePath!!)
        return response.id
    }

    suspend fun sendMessage(sessionID: String, message: String): MessageResponse {
        return apiClient.sendMessage(sessionID, message, project.basePath!!)
    }

    suspend fun getMessages(sessionID: String): List<MessageResponse> {
        return apiClient.getMessages(sessionID, project.basePath!!)
    }
}
```

### 6.3 工具执行

```kotlin
// IDEA 插件中的工具执行
class ToolExecutor(private val apiClient: OpenCodeApiClient) {
    suspend fun executeTool(sessionID: String, toolName: String, args: Map<String, Any>): ToolResult {
        val response = apiClient.executeTool(sessionID, toolName, args, project.basePath!!)
        return ToolResult(
            title = response.title,
            output = response.output,
            metadata = response.metadata
        )
    }
}
```

---

## 7. 测试场景

### 7.1 工具系统测试

| 场景     | 操作           | 预期结果           |
| -------- | -------------- | ------------------ |
| 工具注册 | 加载自定义工具 | 正确注册到工具列表 |
| 工具执行 | 调用工具       | 返回正确结果       |
| 权限检查 | 访问受限资源   | 正确请求权限       |

### 7.2 Agent 系统测试

| 场景       | 操作             | 预期结果       |
| ---------- | ---------------- | -------------- |
| Agent 创建 | 定义自定义 Agent | 正确创建 Agent |
| Agent 执行 | 运行 Agent 任务  | 正确执行任务   |
| 权限继承   | 子 Agent 权限    | 正确继承父权限 |

---

## 8. 参考资源

| 资源         | URL                                                                      |
| ------------ | ------------------------------------------------------------------------ |
| Tool.define  | /Users/yutao/Projects/opencode/packages/opencode/src/tool/tool.ts        |
| ToolRegistry | /Users/yutao/Projects/opencode/packages/opencode/src/tool/registry.ts    |
| Agent.Info   | /Users/yutao/Projects/opencode/packages/opencode/src/agent/agent.ts      |
| Permission   | /Users/yutao/Projects/opencode/packages/opencode/src/permission/index.ts |
| TaskTool     | /Users/yutao/Projects/opencode/packages/opencode/src/tool/task.ts        |

---

## 9. 待深入调研

- [ ] 工具输出截断机制
- [ ] Agent 生成系统
- [ ] Skill 与 Agent 协作
- [ ] 插件系统扩展
- [ ] 多模型支持
