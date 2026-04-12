# Copy as Prompt Action 实施计划

## TL;DR
在 IntelliJ 编辑器右键菜单添加 "Copy as Prompt" 选项，将选中文本格式化后复制到剪贴板。

**输出格式**: `/path/to/file.md:10-111\n选中文本`

---

## Context

### 需求来源
用户在 IDEA 截图中的 Copy/Paste 菜单添加选项，实现：
1. 选中代码/文本后右键
2. 点击 "Copy as Prompt"
3. 格式化内容复制到剪贴板，用户可粘贴到 OpenCode

### 技术实现
- **IntelliJ API**: `CommonDataKeys.EDITOR` → `selectionModel.selectedText`
- **文件路径**: `psiFile.virtualFile.path`
- **行号**: `document.getLineNumber(offset)` + 1
- **剪贴板**: `Toolkit.getDefaultToolkit().systemClipboard`
- **Menu Group**: `EditorPopupMenu` (在 `$Paste` 之后)
- **⚠️ 注意**: IntelliJ `EditorPopupMenu` 中的 Paste action ID 是 `$Paste`，不是 `EditorPaste`

---

## 实现状态

### 完成的功能
- ✅ Action 类实现 (AnAction)
- ✅ plugin.xml 注册
- ✅ 剪贴板复制逻辑
- ✅ 选中文本 + 文件路径 + 行号 获取
- ✅ 菜单位置在 Paste 选项之后

### 实现方式变更
由于 `/tui/append-prompt` API 需要 OpenCode 服务器运行，且调用链路复杂，改为**复制到剪贴板**方式。

---

## 文件变更

### 新增/修改文件
- `src/main/kotlin/.../actions/AddToOpenCodeAction.kt` - 复制到剪贴板
- `src/main/resources/META-INF/plugin.xml` - text="Copy as Prompt"

---

## Success Criteria

- [x] 右键菜单出现 "Copy as Prompt" 选项
- [x] 菜单位置在 Paste 选项之后
- [x] 选中文字后点击，内容复制到剪贴板
- [x] 无选中文本时菜单项不显示
- [x] 构建成功 `./gradlew buildPlugin`
