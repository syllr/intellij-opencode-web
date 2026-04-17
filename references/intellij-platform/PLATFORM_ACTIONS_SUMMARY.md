# PlatformActions.xml 关键信息摘要

本文档汇总了 IntelliJ Platform `PlatformActions.xml` 中的关键 Group ID 定义。

**来源**: https://github.com/JetBrains/intellij-community/blob/master/platform/platform-resources/src/idea/PlatformActions.xml

---

## MainMenu（第 3227 行）

主菜单栏，包含 FileMenu、EditMenu、ToolsMenu、HelpMenu 等子菜单。

```xml
<group id="MainMenu">
  <group id="FileMenu" popup="true">
    <!-- File → Open, Close, Save, Settings, Exit -->
  </group>
  <group id="EditMenu" popup="true">
    <!-- Edit → Undo, Redo, Cut, Copy, Paste, Find -->
  </group>
  <group id="ToolsMenu" popup="true" compact="true">
    <!-- Tools → 工具菜单 -->
  </group>
  <group id="HelpMenu" popup="true">
    <!-- Help → 帮助菜单 -->
  </group>
</group>
```

---

## EditMenu（第 3373 行）

编辑菜单，包含撤销、重做、剪切、复制、粘贴、查找等功能。

### 主要结构
```xml
<group id="EditMenu" popup="true">
  <action id="$Undo" .../>        <!-- 撤销 -->
  <action id="$Redo" .../>        <!-- 重做 -->
  <separator/>
  <group id="CutCopyPasteGroup">
    <action id="$Cut" .../>        <!-- 剪切 -->
    <action id="$Copy" .../>       <!-- 复制 -->
    <action id="CopyPaths" .../>   <!-- 复制路径 -->
    <group id="PasteGroup" popup="true">
      <action id="$Paste" .../>     <!-- 粘贴 -->
    </group>
  </group>
  <separator/>
  <group id="FindMenuGroup" popup="true">
    <action id="Find" .../>         <!-- 查找 -->
    <action id="Replace" .../>      <!-- 替换 -->
  </group>
</group>
```

### CutCopyPasteGroup 详情
| Action ID | 用途 |
|-----------|------|
| `$Cut` | 剪切（Ctrl+X / Cmd+X） |
| `$Copy` | 复制（Ctrl+C / Cmd+C） |
| `CopyPaths` | 复制文件路径 |
| `$Paste` | 粘贴（Ctrl+V / Cmd+V） |

---

## ToolsMenu（第 3811 行）

工具菜单，通常放置插件提供的工具入口。

```xml
<group id="ToolsMenu" popup="true" compact="true" class="com.intellij.ide.IdeDependentActionGroup">
  <group id="ToolsMenu.Services" popup="true"/>
  <!-- 其他工具菜单项 -->
</group>
```

**注意**: `compact="true"` 表示菜单项在不启用时会被隐藏。

---

## HelpMenu（第 4105 行）

帮助菜单。

```xml
<group id="HelpMenu" popup="true">
  <!-- 帮助菜单项 -->
</group>
```

---

## 其他常用 PopupMenu Group IDs

| Group ID | 用途 | 出现位置 |
|----------|------|----------|
| `EditorPopup` | 编辑器右键菜单 | 编辑器中右键 |
| `ProjectViewPopupMenu` | 项目视图右键菜单 | 项目面板右键 |
| `CommanderPopupMenu` | 导航栏右键菜单 | 导航栏右键 |
| `Diff.EditorPopupMenu` | 差异比较编辑器菜单 | 差异对比窗口 |

---

## 添加 Action 到菜单的示例

### 1. 添加到 EditMenu（在撤销之后）

```xml
<add-to-group group-id="EditMenu" relative-to-action="$Undo" anchor="after"/>
```

### 2. 添加到 CutCopyPasteGroup（在复制之后）

```xml
<add-to-group group-id="CutCopyPasteGroup" relative-to-action="$Copy" anchor="after"/>
```

### 3. 添加到 ToolsMenu

```xml
<add-to-group group-id="ToolsMenu" relative-to-action="GenerateJavadoc" anchor="after"/>
```

### 4. 添加到编辑器右键菜单

```xml
<add-to-group group-id="EditorPopup" relative-to-action="EditorCopy" anchor="after"/>
```

---

## 如何查找正确的 Group ID

### 方法 1：查看 PlatformActions.xml
```bash
# 克隆仓库
git clone --depth 1 https://github.com/JetBrains/intellij-community.git

# 搜索特定 Group
grep -n 'group id="YourGroup"' intellij-community/platform/platform-resources/src/idea/PlatformActions.xml
```

### 方法 2：使用 UI Inspector
1. 在 IDE 中启用 internal mode：创建 `.idea/idea.vmoptions` 文件，添加 `idea.is.internal=true`
2. 重启 IDE
3. 使用 Help → Debug → UI Inspector 查看 UI 元素的 Action ID 和 Group ID

### 方法 3：使用 Action ID 代码补全
在 `plugin.xml` 中输入 `group-id="` 时，IDE 会自动提示可用的 Group ID（需要安装 Plugin DevKit）。