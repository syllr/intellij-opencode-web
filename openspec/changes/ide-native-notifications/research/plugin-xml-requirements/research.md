# plugin.xml 改动需求调研

## 调研目标

确定实现 SSE 通知 + Settings UI + 通知服务需要修改 plugin.xml 的哪些部分。

## 信息来源

- IntelliJ Platform SDK 官方文档：https://plugins.jetbrains.com/docs/intellij/settings-guide.html
- IntelliJ SDK Code Samples (settings)：https://github.com/JetBrains/intellij-sdk-code-samples/tree/main/settings
- 当前 plugin.xml：`src/main/resources/META-INF/plugin.xml`

## 当前 plugin.xml

```xml
<idea-plugin>
    <id>com.shenyuanlaolarou.opencodewebui</id>
    <depends>com.intellij.modules.platform</depends>
    <extensions defaultExtensionNs="com.intellij">
        <toolWindow .../>
        <notificationGroup id="OpenCodeWeb.notifications" displayType="BALLOON"/>
    </extensions>
</idea-plugin>
```

## 调研结论

### 需要改动的部分

**1. `<applicationConfigurable>` — 必须添加**

```xml
<applicationConfigurable parentId="tools"
                         instance="com.shenyuanlaolarou.opencodewebui.config.OpenCodeConfigurable"
                         id="com.shenyuanlaolarou.opencodewebui.config"
                         displayName="OpenCode"/>
```

- `parentId="tools"`：页面放在 Settings → Tools 下
- `instance`：实现 `Configurable` 接口的完整类名
- `id`：唯一标识

### 不需要改动的部分

| 项目                   | 原因                                                                 |
| ---------------------- | -------------------------------------------------------------------- |
| `<notificationGroup>`  | 已正确注册（`"OpenCodeWeb.notifications"`, `displayType="BALLOON"`） |
| `<depends>`            | `com.intellij.modules.platform` 已包含全部所需 API                   |
| `<applicationService>` | OpenCodeConfig 为普通 object，不注册为 Service                       |
| `<projectService>`     | OpenCodeNotificationService 由 SSE Consumer 管理生命周期             |
| `<listeners>`          | 无需监听器，每次通知前直接读 PropertiesComponent                     |

### 已验证的 API 可用性

| API                        | 所属模块 | 状态        |
| -------------------------- | -------- | ----------- |
| `NotificationGroupManager` | platform | ✅ 已有依赖 |
| `PropertiesComponent`      | platform | ✅ 已有依赖 |
| `Configurable`             | platform | ✅ 已有依赖 |
| `ToolWindowManager`        | platform | ✅ 已有依赖 |

## 结论

**只需在 `<extensions>` 中增加一行**。其他全部不需要动。

## 参考

- 官方 Settings 示例：https://github.com/JetBrains/intellij-sdk-code-samples/tree/main/settings
- `applicationConfigurable` parentId 选项：tools, appearance, build, language, editor 等
