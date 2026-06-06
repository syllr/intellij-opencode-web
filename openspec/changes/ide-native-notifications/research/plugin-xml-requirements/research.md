# plugin.xml 改动需求调研(代码同步版)

> **状态说明**:本文件是 2026-05-15 调研结果。原 plan 中"<notificationGroup> 已正确注册 (displayType="BALLOON")"的事实陈述**已过时**(commit `60ebd1f` 修改为 `TOOL_WINDOW`)。本文档已与代码现状同步。

## 调研目标

确定实现 SSE 通知 + Settings UI + 通知服务需要修改 plugin.xml 的哪些部分。

## 信息来源

- IntelliJ Platform SDK 官方文档:https://plugins.jetbrains.com/docs/intellij/settings-guide.html
- IntelliJ SDK Code Samples (settings):https://github.com/JetBrains/intellij-sdk-code-samples/tree/main/settings
- 当前 plugin.xml:`src/main/resources/META-INF/plugin.xml`

## 当前 plugin.xml(代码现状)

```xml
<idea-plugin>
    <id>com.shenyuanlaolarou.opencodewebui</id>
    <vendor email="shenyuanlaolarou@users.noreply.github.com" url="https://github.com/syllr/intellij-opencode-web">shenyuanlaolarou</vendor>

    <depends>com.intellij.modules.platform</depends>

    <resource-bundle>messages.MyBundle</resource-bundle>

    <actions>
        <!-- Copy as Prompt -->
        <action id="com.shenyuanlaolarou.opencodewebui.CopyAsPrompt" .../>
        <!-- Add to Prompt -->
        <action id="com.shenyuanlaolarou.opencodewebui.AddToPrompt" .../>
    </actions>

    <extensions defaultExtensionNs="com.intellij">
        <toolWindow factoryClass="com.shenyuanlaolarou.opencodewebui.toolWindow.MyToolWindowFactory" id="OpenCodeWeb" anchor="right" icon="/icons/ocw.svg" displayName="OpenCodeWeb"/>
        <notificationGroup id="OpenCodeWeb.notifications" displayType="TOOL_WINDOW" toolWindowId="OpenCodeWeb"/>
        <postStartupActivity implementation="com.shenyuanlaolarou.opencodewebui.toolWindow.OpenCodeProjectActivity"/>
    </extensions>
</idea-plugin>
```

## 调研结论(代码同步版)

### 当前状态

| 项目                              | 原 plan   | 实际代码                                         | 状态           |
| --------------------------------- | --------- | ------------------------------------------------ | -------------- |
| `<notificationGroup displayType>` | `BALLOON` | **`TOOL_WINDOW` + `toolWindowId="OpenCodeWeb"`** | 🟠 偏离原 plan |
| `<applicationConfigurable>`       | 需要添加  | **未添加**                                       | 🔴 未实施      |
| `<depends>`                       | 不需要    | 保持 `com.intellij.modules.platform`             | ✅ 符合        |
| `<applicationService>`            | 不需要    | 未注册                                           | ✅ 符合        |
| `<projectService>`                | 不需要    | 未注册                                           | ✅ 符合        |
| `<listeners>`                     | 不需要    | 未注册                                           | ✅ 符合        |
| `<postStartupActivity>`           | 未提及    | 已注册 `OpenCodeProjectActivity`                 | ➕ 实际新增    |

### 已实施的部分

**`<notificationGroup>`(displayType 偏离)**:

- ✅ 已注册 `id="OpenCodeWeb.notifications"`
- 🟠 `displayType` 从原 plan 的 `BALLOON` 改为 **`TOOL_WINDOW`**
- 🟠 额外加 `toolWindowId="OpenCodeWeb"`(原 plan 无此属性)
- **影响**:通知在 OpenCodeWeb 工具窗口按钮旁显示,任何按键/点击立即消失;不进 macOS 系统通知中心
- **commit 溯源**: `60ebd1f` (fix: change notification display type to tool window)

### 未实施的部分

**`<applicationConfigurable>`(Settings UI 入口)**:

- ❌ **未添加**
- 原 plan 设计:
  ```xml
  <applicationConfigurable parentId="tools"
                           instance="com.shenyuanlaolarou.opencodewebui.config.OpenCodeConfigurable"
                           id="com.shenyuanlaolarou.opencodewebui.config"
                           displayName="OpenCode"/>
  ```
- 实际:**无此条目**
- `OpenCodeConfigurable.kt` 类**不存在**

## 后续变更建议(非本 research 范围)

1. **如果实施 Settings UI**:按原 plan 添加 `<applicationConfigurable>` + 创建 `OpenCodeConfigurable` 类
2. **如果修复通知停留时间**:把 `displayType` 从 `TOOL_WINDOW` 改为 `STICKY_BALLOON` 或 `BALLOON`(见 `specs/notification-service/spec.md` 末尾)

## 参考

- 官方 Settings 示例:https://github.com/JetBrains/intellij-sdk-code-samples/tree/main/settings
- `applicationConfigurable` parentId 选项:tools, appearance, build, language, editor 等
