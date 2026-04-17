# 添加 IntelliJ Platform 参考文档

## TL;DR
创建 `references/intellij-platform/` 目录，存放 IntelliJ Platform 插件开发的官方参考文档

## Context
用户需要将 IntelliJ 官方文档信息添加到插件项目的参考文件夹中，方便日后开发查阅。

## Work Objectives

### 必须创建的文件
1. `references/intellij-platform/README.md` - 参考文档索引
2. `references/intellij-platform/PLATFORM_ACTIONS_SUMMARY.md` - PlatformActions.xml 关键信息摘要

### 可选：下载的源码文件
- PlatformActions.xml（较大，可选是否保存原始文件）
- IdeActions.java 关键信息

## Definition of Done
- [ ] `references/intellij-platform/README.md` 存在且内容完整
- [ ] 包含所有官方文档 URL
- [ ] 包含常用 Group ID 列表
- [ ] AGENTS.md 已更新，添加 references 目录说明

## Must Have
- IntelliJ 官方文档 URL 列表
- 常用 Group ID 参考表（MainMenu, EditMenu, ToolsMenu, CutCopyPasteGroup 等）
- 使用示例

## Must NOT Have
- 不需要下载完整的 PlatformActions.xml（太大且可在线查看）
- 不需要修改现有代码

## Verification Strategy
- 手动检查文件是否存在
- 检查 AGENTS.md 是否已更新

## Execution Strategy

### Wave 1: 创建目录和文档
1. 创建 `references/intellij-platform/` 目录
2. 创建 `references/intellij-platform/README.md`
3. 创建 `references/intellij-platform/PLATFORM_ACTIONS_SUMMARY.md`
4. 更新 `AGENTS.md` 添加 references 目录说明

### Agent Dispatch
- **创建文档**: `quick` agent（简单文件创建）

## TODOs

- [x] 1. 创建 `references/intellij-platform/` 目录

- [x] 2. 创建 `references/intellij-platform/README.md`
  参考文档索引，包含：
  - 官方文档 URL 列表
  - 源码文件 URL
  - 常用 Group ID 表格

- [x] 3. 创建 `references/intellij-platform/PLATFORM_ACTIONS_SUMMARY.md`
  PlatformActions.xml 关键信息摘要：
  - MainMenu 定义（约第 3227 行）
  - EditMenu 定义（约第 3373 行）
  - ToolsMenu 定义（约第 3811 行）
  - HelpMenu 定义（约第 4105 行）
  - CutCopyPasteGroup 定义

- [x] 4. 更新 `AGENTS.md`
  在"关键位置"或"参考资料"部分添加：
  ```
  | IntelliJ Platform 参考 | references/intellij-platform/ | 官方文档、Group ID、Action ID |
  ```

## Commit Strategy
- Commit: YES
- Message: `docs: 添加 IntelliJ Platform 官方参考文档`
- Files: `references/`, `AGENTS.md`
- Pre-commit: 无

## Success Criteria
- references/intellij-platform/ 目录存在
- README.md 包含完整的官方文档链接
- PLATFORM_ACTIONS_SUMMARY.md 包含常用 Group ID
- AGENTS.md 已更新
