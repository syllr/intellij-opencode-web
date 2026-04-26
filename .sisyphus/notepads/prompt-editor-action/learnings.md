# Prompt Editor Action Learnings

## 2026-04-26

### Action Pattern
- Existing actions in `actions/` folder extend `AnAction()`
- Get project via `e.project` or `e.getData(CommonDataKeys.PROJECT)`
- Use `CommonDataKeys.EDITOR` for editor access
- Dialog: `Messages.showChoiceDialog` or `Messages.showMessageDialog`

### Session Selector
- Need to use `Messages.showChoiceDialog` for selecting from a list
- Returns selected session or null if cancelled

### References
- CopyAsPromptAction.kt - reference implementation
- IntelliJ Action System docs

## PromptEditorTab Implementation (T4)

### Created File
- `toolWindow/PromptEditorTab.kt`

### Key Components

#### TextEditorWithToolbar
- Extends `SimpleToolWindowPanel` for toolbar support
- Uses `JBTextArea` (IntelliJ's styled text area) for text input
- Uses `ActionToolbar` with `DefaultActionGroup` for toolbar buttons
- Send button implemented as `AnAction` with `update()` method for state management

#### Send Button State Management
- `SendButtonState` enum: DEFAULT, LOADING, SUCCESS, ERROR
- Action's `update()` method controls button enabled state
- When LOADING/SUCCESS: button disabled, text area read-only
- When ERROR/DEFAULT: button enabled, text area editable

### IntelliJ Platform APIs Used
- `com.intellij.openapi.actionSystem.ActionManager` - for creating action toolbar
- `com.intellij.openapi.actionSystem.ActionToolbar` - toolbar component
- `com.intellij.openapi.actionSystem.AnAction` - base action class
- `com.intellij.openapi.actionSystem.DefaultActionGroup` - group actions in toolbar
- `com.intellij.openapi.ui.SimpleToolWindowPanel` - panel with built-in toolbar support
- `com.intellij.ui.components.JBTextArea` - IntelliJ styled text area
- `com.intellij.openapi.application.ApplicationManager` - for background threads

### Common Issues
- `JBLoadingDecorator` is NOT available - use simple state management instead
- `com.intellij.openapi.ui.components.BorderLayout` does NOT exist - use `java.awt.BorderLayout`
- `ActionManager.invokeLater()` does NOT exist - use `ApplicationManager.getApplication().invokeLater()`
- Remember to use `executeOnPooledThread` for network operations, then `invokeLater` for UI updates

# PromptEditorService Implementation Learnings

## Created File
- `src/main/kotlin/com/github/xausky/opencodewebui/utils/PromptEditorService.kt`

## Key Decisions

### JSON Parsing Approach
Initially used `org.json.JSONObject` and `JSONArray` but discovered project has no JSON library dependency.
**Solution**: Used regex parsing instead, following the pattern from `SessionHelper.kt`.

### Regex Pattern for Session Parsing
```kotlin
Regex("""\{"id"\s*:\s*"([^"]+)",\s*"directory"\s*:\s*"([^"]*)",\s*"time"\s*:\s*\{([^}]*)\}\}""")
```
- Extracts `id`, `directory`, and `time` content
- Then separate regex to extract `created` and `archived` from time object

### Active Session Filtering
Active sessions have no `time.archived` field; archived sessions have `time.archived` timestamp.
**Implementation**: `Session.isArchived = archivedAt != null`, filter with `filter { !it.isArchived }`

### Message Sending Body Format
```json
{"parts": [{"type": "text", "text": "..."}]}
```
Using string interpolation instead of JSON library.

## Pre-existing Build Issues
Build fails due to errors in other files (PromptEditorTabFactory.kt, PromptActions.kt) - not related to PromptEditorService.kt.

## OpenPromptEditorAction Implementation (T5)

### Created File
- `actions/OpenPromptEditorAction.kt`

### Action Pattern
- Extends `AnAction()`
- Gets ToolWindow via `ToolWindowManager.getInstance(project).getToolWindow("OpenCodeWeb")`
- Uses `PromptEditorTabFactory.showPromptEditor(toolWindow)` to show/focus the tab
- If tool window not visible, shows it first

### Registration in plugin.xml
- Action ID: `com.shenyuanlaolarou.opencodewebui.OpenPromptEditor`
- Added to `ToolsMenu` group with anchor `last`
- Build verification: `./gradlew buildPlugin` - BUILD SUCCESSFUL

## AppendToPromptAction Implementation (T6 variant)

### Created Files
- `actions/AppendToPromptAction.kt`

### Modified Files
- `toolWindow/PromptEditorTab.kt` - Added `getCurrentInstance()` to `TextEditorWithToolbarFactory`
- `plugin.xml` - Registered `AppendToPrompt` action

### AppendToPromptAction Pattern
- Extends `AnAction()`
- Gets selected text from `CommonDataKeys.EDITOR`
- Formats selection with file path and line range (like CopyAsPromptAction)
- Uses `TextEditorWithToolbarFactory.getCurrentInstance()` to access prompt editor
- Calls `appendText()` if editor exists and is not read-only
- Shows/focuses prompt editor tab after appending

### Action Registration
- Action ID: `com.shenyuanlaolarou.opencodewebui.AppendToPrompt`
- Added to `EditorPopupMenu` after `$Paste`
- Added to `CutCopyPasteGroup` after `$Copy`

### GutterIconRenderer Note
- Attempted `gutter/PromptGutterIconRenderer.kt` but removed due to API complexity
- `GutterIconRenderer` requires `equals()` and `hashCode()` implementation
- Showing icons globally (on all files/lines) is not the typical gutter use case
- IntelliJ gutter icons are typically line-specific markers
- The `AppendToPromptAction` in EditorPopupMenu provides similar functionality

### Build Verification
- `./gradlew buildPlugin` - BUILD SUCCESSFUL

## OpenPromptEditorAction Fix - Editor Tab Instead of ToolWindow

### Problem
- Original implementation opened prompt editor in ToolWindow instead of Editor Tab
- Task requirement was to open in Editor area (top of IDE)

### Solution
- Renamed `TextEditorWithToolbarFactory` to `PromptEditorTabManager`
- Added `openEditor()` method that uses `FileEditorManager` to open a temp file
- Uses `LocalFileSystem` to find/create VirtualFile from temp file
- `OpenPromptEditorAction` now calls `PromptEditorTabManager.openEditor()` directly
- `AppendToPromptAction` updated to use `PromptEditorTabManager.getCurrentInstance()`

### Key APIs
- `File.createTempFile()` - creates temporary file for editor
- `LocalFileSystem.getInstance().findFileByIoFile()` - converts File to VirtualFile
- `FileEditorManager.getInstance(project).openFile(virtualFile, true)` - opens file in editor

### Build Verification
- `./gradlew buildPlugin` - BUILD SUCCESSFUL
