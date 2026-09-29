<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Copy as Prompt Changelog

## [Unreleased]

## [0.1.0] - 2026-09-29

### Added

- **Copy as Prompt** — 把编辑器选中的代码复制为 prompt 文本：`location:<路径>:<行号或起止行号>` + 带语言标识的代码围栏 + 选中内容原样。路径优先使用相对项目根的写法；选区恰好结束在某行行首时按真实覆盖的末行输出行号，不把未选中的行算进去。
- **Copy Diagnostics as Prompt** — 把当前文件全部的 ERROR / WARNING 复制为 prompt 文本：`location:<路径>` + 诊断列表（`[严重级别] line <行号>: <消息>` 与缩进的出错行原文）。诊断按行号 → 严重级别 → 消息稳定排序，相同诊断去重，inspection 描述里的 HTML 与换行会被净化成单行纯文本。
- 两个 action 的输出都以「换行 + 两个空格」结尾，使目标输入框（contenteditable 类实现）渲染出可见空行，避免粘贴后的后续输入被吞进代码块。
- 诊断采集走 document 级 `DocumentMarkupModel` + `HighlightInfo.fromRangeHighlighter`（与平台内部实现一致），只读 daemon 已经算好的高亮，不额外触发全量 inspection 分析；只保留 `HighlightSeverity >= WARNING` 的诊断，符号类型提示等非诊断高亮被排除。
- 测试：`PromptFormatTest`（11 例，纯函数格式契约）+ `EditorDiagnosticsTest`（4 例，`BasePlatformTestCase` 验证诊断真的能从 markup model 采集到）。
- GitHub Actions CI（`.github/workflows/build.yml`）：`test` / `build` / `verify`，以及打 `v*` tag 时的 `publish`。
- 项目元文档：`AGENTS.md`（开发约束与平台 API 踩坑）、`SPEC.md`（两个输出格式契约）、`DESIGN.md`（分层与关键决策）。

[Unreleased]: https://github.com/syllr/copy-as-prompt/compare/0.1.0...HEAD
[0.1.0]: https://github.com/syllr/copy-as-prompt/commits/0.1.0
