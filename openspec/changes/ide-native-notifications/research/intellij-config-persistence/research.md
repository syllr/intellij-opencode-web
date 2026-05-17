# IntelliJ 配置持久化机制调研

## 调研目标

确定 IntelliJ 插件最佳配置持久化方案（12 个事件开关 + 4 个通用设置 + 12 个消息模板）。

## 信息来源

- IntelliJ Platform SDK 官方文档：https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html
- IntelliJ Platform SDK 官方文档：https://plugins.jetbrains.com/docs/intellij/settings-tutorial.html
- IntelliJ Platform SDK 官方文档：https://plugins.jetbrains.com/docs/intellij/settings-guide.html
- IntelliJ SDK Code Samples：https://github.com/JetBrains/intellij-sdk-code-samples/tree/main/settings

## 方案对比

### PropertiesComponent

```kotlin
// 应用级别（跨项目共享）
PropertiesComponent.getInstance()
// 项目级别
PropertiesComponent.getInstance(project)
```

**支持的类型**：String、int、boolean、double（底层全部存为字符串）

**线程安全**：✅ 所有方法都是线程安全的

**存储位置**：

- Linux/macOS: `~/.config/JetBrains/<IDE>/options/workspace.xml`
- 所有插件共享同一个 namespace

**示例**：

```kotlin
PropertiesComponent.getInstance().setValue("myPlugin.enabled", true)
val enabled = PropertiesComponent.getInstance().getBoolean("myPlugin.enabled", false)
```

### PersistentStateComponent

```kotlin
@State(
    name = "com.example.Settings",
    storages = @Storage("MySettings.xml")
)
class MySettings : PersistentStateComponent<MySettings.State> {
    data class State(
        var enabled: Boolean = false,
        var count: Int = 0,
    )
    // getState() / loadState() 实现
}
```

**优势**：支持嵌套类型、List、Map
**劣势**：需要 @State/@Storage 注解、getState/loadState 实现、适合复杂对象

### 选择结论

**使用 PropertiesComponent**：

1. **配置可展平**：28 个 key-value（11 事件开关 + 11 消息模板 + 4 通用 + 2 预留），PropertiesComponent 天然适合
2. **零开销读取**：每次通知前读 PropertiesComponent 是内存操作，无需文件 I/O
3. **避免 PersistentStateComponent 的陷阱**：
   - @State 默认使用 project 级别存储，改为 IDE 级别需额外配置
   - Map 类型在 XML 中生成嵌套标签，不够直观
   - 配置热加载需额外实现通知机制
4. **开发效率**：15 行 getter/setter 搞定

## Key 命名规范

```
opencode.settings.notification        # 全局通知开关 (Boolean, 默认 true)
opencode.settings.showProjectName     # 显示项目名 (Boolean, 默认 true)
opencode.settings.showSessionTitle    # 显示 Session 标题 (Boolean, 默认 true)
opencode.settings.minDuration         # 最短会话时长秒 (Int, 默认 0)
opencode.event.{type}.enabled         # 事件开关 (Boolean)，如 opencode.event.permission.enabled
opencode.message.{type}               # 消息模板 (String)
```

## 配置变更通知

**不做额外通知机制**。PropertiesComponent 本身就是内存缓存，每次通知前直接读取：

```kotlin
// OpenCodeNotificationService.notify() 中
if (!PropertiesComponent.getInstance().getBoolean("opencode.event.$type.enabled", false)) return
```

用户 Apply → PropertiesComponent 写入 → 下次通知自动用新配置。零耦合。

## 引用

| 资源                           | 链接                                                                            |
| ------------------------------ | ------------------------------------------------------------------------------- |
| Persisting State of Components | https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html |
| Settings Tutorial              | https://plugins.jetbrains.com/docs/intellij/settings-tutorial.html              |
| Settings Guide                 | https://plugins.jetbrains.com/docs/intellij/settings-guide.html                 |
| SDK Code Samples (settings)    | https://github.com/JetBrains/intellij-sdk-code-samples/tree/main/settings       |
