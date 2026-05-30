# IntelliJ 插件国际化和 OpenCode 项目管理详解

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件与 opencode 联动提供国际化和项目管理技术指导

---

## 1. IntelliJ 插件国际化

### 1.1 核心组件：DynamicBundle

```kotlin
private const val BUNDLE = "messages.ActionBundles"

object MessageUtils: DynamicBundle(BUNDLE) {
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getMessage(key, *params)
}
```

### 1.2 资源文件结构

```
src/main/resources/
└── messages/
    ├── ActionBundles.properties        # 默认（英文）
    ├── ActionBundles_zh.properties     # 中文
    ├── ActionBundles_zh_CN.properties # 中文（中国）
    └── ActionBundles_ja.properties     # 日文
```

### 1.3 第三方插件的 DynamicBundle 问题

**解决方案**：创建自定义的 `DynamicPluginBundle`

```java
public class DynamicPluginBundle extends DynamicBundle {
    public DynamicPluginBundle(@NotNull String path) {
        super(path);
    }

    @NotNull
    @Override
    protected ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }
}
```

### 1.4 使用方式

```kotlin
val greeting = MessageUtils.message("demo.greet-msg", "butterfly")
// 输出中文环境: "你好，butterfly"
// 输出英文环境: "Hello, butterfly"
```

### 1.5 最佳实践

| 实践                      | 说明                                                            |
| ------------------------- | --------------------------------------------------------------- |
| 使用 @PropertyKey 注解    | 提供编译时验证和 IDE 代码补全                                   |
| Unicode 转义非 ASCII 字符 | `中文` → `\u4e2d\u6587`                                         |
| 按模块分离消息文件        | `messages_Actions.properties`, `messages_ToolWindow.properties` |
| 使用自定义 DynamicBundle  | 第三方插件必须，否则无法被语言包识别                            |

### 1.6 Actions 国际化

```kotlin
class MyAction: AnAction() {
    init {
        val text = lazy { MessageUtils.message("action.myAction.text") }
        val desc = lazy { MessageUtils.message("action.myAction.description") }
        super.init(text, desc, null)
    }
}
```

---

## 2. OpenCode 项目管理

### 2.1 项目定义

```typescript
// project.ts
export const Info = Schema.Struct({
  id: ProjectID,
  worktree: Schema.String, // Git worktree 根目录
  vcs: optionalOmitUndefined(ProjectVcs), // "git" | undefined
  name: optionalOmitUndefined(Schema.String),
  icon: optionalOmitUndefined(ProjectIcon),
  commands: optionalOmitUndefined(ProjectCommands),
  time: ProjectTime,
  sandboxes: Schema.Array(Schema.String),
});
```

### 2.2 项目服务接口

```typescript
export interface Interface {
  readonly init: () => Effect.Effect<void>;
  readonly fromDirectory: (directory: string) => Effect.Effect<{ project: Info; sandbox: string }>;
  readonly discover: (input: Info) => Effect.Effect<void>;
  readonly list: () => Effect.Effect<Info[]>;
  readonly get: (id: ProjectID) => Effect.Effect<Info | undefined>;
  readonly update: (input: UpdateInput) => Effect.Effect<Info, NotFoundError>;
  readonly initGit: (input: { directory: string; project: Info }) => Effect.Effect<Info>;
  readonly setInitialized: (id: ProjectID) => Effect.Effect<void>;
  readonly sandboxes: (id: ProjectID) => Effect.Effect<string[]>;
  readonly addSandbox: (id: ProjectID, directory: string) => Effect.Effect<void>;
  readonly removeSandbox: (id: ProjectID, directory: string) => Effect.Effect<void>;
}
```

### 2.3 项目切换机制

```typescript
// InstanceStore
export interface Interface {
  readonly load: (input: LoadInput) => Effect.Effect<InstanceContext>;
  readonly reload: (input: LoadInput) => Effect.Effect<InstanceContext>;
  readonly dispose: (ctx: InstanceContext) => Effect.Effect<void>;
  readonly disposeAll: () => Effect.Effect<void>;
  readonly provide: <A, E, R>(
    input: LoadInput,
    effect: Effect.Effect<A, E, R>,
  ) => Effect.Effect<A, E, R>;
}
```

### 2.4 项目与会话关系

```typescript
// session.sql.ts
export const SessionTable = sqliteTable("session", {
  id: text().$type<SessionID>().primaryKey(),
  project_id: text()
    .$type<ProjectID>()
    .notNull()
    .references(() => ProjectTable.id, { onDelete: "cascade" }),
  workspace_id: text().$type<WorkspaceID>(),
  directory: text().notNull(),
  path: text(),
  title: text().notNull(),
});
```

### 2.5 配置加载顺序

```
1. 远程 well-known 配置
2. 全局配置 (~/.config/opencode/)
3. OPENCODE_CONFIG 环境变量
4. 项目级配置 (worktree 目录)
5. .opencode 目录配置
6. OPENCODE_CONFIG_CONTENT 环境变量
7. 组织配置 (Console 托管)
8. MDM 托管偏好设置
```

---

## 3. 参考资源

| 资源                   | URL                                                                                                               |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------- |
| JetBrains i18n 文档    | https://www.jetbrains.com/help/idea/internationalization-and-localization.html                                    |
| DynamicBundle 源码     | https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/DynamicBundle.java |
| OpenCode Project       | /Users/yutao/Projects/opencode/packages/opencode/src/project/project.ts                                           |
| OpenCode InstanceStore | /Users/yutao/Projects/opencode/packages/opencode/src/project/instance-store.ts                                    |

---

## 4. 下一步行动

1. **实现 i18n** - 创建自定义 DynamicPluginBundle
2. **实现项目管理** - 使用 InstanceStore 管理项目
3. **实现配置加载** - 支持多层配置合并
4. **测试国际化** - 验证不同语言环境
