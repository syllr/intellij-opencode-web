# IntelliJ Platform 官方参考文档

本目录存放 IntelliJ Platform 插件开发的官方参考文档。

## 核心源码文件

### PlatformActions.xml
**URL**: https://github.com/JetBrains/intellij-community/blob/master/platform/platform-resources/src/idea/PlatformActions.xml
**原始 URL**: https://raw.githubusercontent.com/JetBrains/intellij-community/refs/heads/master/platform/platform-resources/src/idea/PlatformActions.xml

**说明**: 定义了 IntelliJ Platform 所有标准的 Group IDs（菜单组 ID）

### IdeActions.java
**URL**: https://github.com/JetBrains/intellij-community/blob/master/platform/ide-core/src/com/intellij/openapi/actionSystem/IdeActions.java

**说明**: 定义了所有标准 Action IDs

### ActionPlaces.java
**URL**: https://github.com/JetBrains/intellij-community/blob/master/platform/ide-core/src/com/intellij/openapi/actionSystem/ActionPlaces.java

**说明**: 定义了 Action 出现的位置常量

---

## 官方文档

| 资源 | URL | 类型 |
|------|-----|------|
| **DevGuide（官方）** | https://plugins.jetbrains.com/docs/intellij/ | ⭐ 官方 |
| **Action System** | https://plugins.jetbrains.com/docs/intellij/action-system.html | 官方 |
| **Plugin Configuration** | https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html | 官方 |
| **Gradle Plugin** | https://github.com/JetBrains/gradle-intellij-plugin | ⭐ 官方 |
| **Plugin Template** | https://github.com/JetBrains/intellij-platform-plugin-template | ⭐ 官方 |
| **SDK Code Samples** | https://github.com/JetBrains/intellij-sdk-code-samples | ⭐ 官方 |
| **SDK Docs 源码** | https://github.com/JetBrains/intellij-sdk-docs | ⭐ 官方 |
| **Marketplace** | https://plugins.jetbrains.com/ | ⭐ 官方 |

---

## 常用 Group IDs（从 PlatformActions.xml）

### 主菜单组
| Group ID | 用途 |
|----------|------|
| `MainMenu` | 主菜单栏 |
| `FileMenu` | 文件菜单 |
| `EditMenu` | 编辑菜单 |
| `ToolsMenu` | 工具菜单 |
| `HelpMenu` | 帮助菜单 |

### EditMenu 子组
| Group ID | 用途 |
|----------|------|
| `CutCopyPasteGroup` | 剪切/复制/粘贴组 |
| `PasteGroup` | 粘贴组 |
| `FindMenuGroup` | 查找菜单组 |

### 其他常用组
| Group ID | 用途 |
|----------|------|
| `EditorPopup` | 编辑器右键菜单 |
| `ProjectViewPopupMenu` | 项目视图右键菜单 |
| `MainMenu` | 主菜单 |

---

## 使用方法

### 添加 Action 到 EditMenu（编辑菜单）

在 plugin.xml 中：
```xml
<add-to-group group-id="EditMenu" relative-to-action="$Undo" anchor="after"/>
```

### 添加 Action 到 EditMenu 的 CutCopyPasteGroup（复制旁边）

```xml
<add-to-group group-id="CutCopyPasteGroup" relative-to-action="$Copy" anchor="after"/>
```

### 查看完整 Group ID 列表

1. 克隆 intellij-community 仓库：
   ```bash
   git clone --depth 1 https://github.com/JetBrains/intellij-community.git
   ```

2. 查看 PlatformActions.xml：
   ```bash
   cat intellij-community/platform/platform-resources/src/idea/PlatformActions.xml
   ```

3. 使用 UI Inspector：
   在 IDE 中启用 internal mode（`idea.is.internal=true`），然后使用 UI Inspector 查看任何 UI 元素对应的 Action ID 和 Group ID。
