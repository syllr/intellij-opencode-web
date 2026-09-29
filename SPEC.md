# SPEC.md — Copy as Prompt 规格

> **本文档定位**:项目级**行为规范**。定义"插件必须满足什么"，不描述"如何实现"（那在 `DESIGN.md`）。
>
> **三元组关系**:`AGENTS.md`（怎么开发）→ `SPEC.md`（必须满足什么，本文档）→ `DESIGN.md`（怎么实现）

---

## 0. 系统概览

本插件把 IDE 里的信息格式化成 prompt 文本写入系统剪贴板，供用户粘贴到任意 LLM 对话界面。

| Action                       | 输入                     | 输出                                        |
| ---------------------------- | ------------------------ | ------------------------------------------- |
| `Copy as Prompt`             | 编辑器选中代码           | 文件路径 + 行号 + 代码块                    |
| `Copy Diagnostics as Prompt` | 当前文件的 ERROR/WARNING | 文件路径 + 诊断列表（行号 + 消息 + 出错行） |

**明确的非目标**（不要"顺手加回来"）：

- 不启动 / 不管理任何进程（无 opencode server、无浏览器）
- 不连接任何服务（无 HTTP / SSE / WebSocket）
- 不读写配置文件与环境变量
- 不与特定 agent 耦合：产物是纯文本，任何对话界面都能消费

**数据流**（两个 action 同构）：

```
IDE 数据（选区 / 诊断）
    │  actions/：平台 API 取数据，转成纯数据
    ▼
format/：纯函数 → 字符串
    ▼
Toolkit 系统剪贴板
```

---

## 1. 契约 A：选区 → prompt（`Copy as Prompt`）

### 1.1 输出格式(HARD CONTRACT)

```text
location:<文件路径>:<行号或起止行号>
content:
```

<语言标识>
<选中代码>

```
␠␠
```

（最后一行是**两个空格**，见 §1.3）

| 项       | 规则                                                                                   |
| -------- | -------------------------------------------------------------------------------------- |
| 路径     | 优先**相对项目根**的路径（`src/Foo.kt`）；文件不在项目内时回退为绝对路径               |
| 行号     | 1-based，与编辑器状态栏一致。单行 → `10`；跨行 → `10-20`（首尾行均包含）               |
| 行号边界 | 选区恰好结束在某行行首时，末行取**最后一个被选中的字符**所在行（不把未选中的行算进去） |
| 语言标识 | 取自 PSI 语言 id 并**转小写**（`JAVA` → `java`）；取不到时输出裸围栏                   |
| 选中代码 | **原样**：不 trim、不重排缩进、不转义                                                  |
| 换行符   | `\n`                                                                                   |

**Spec**:

- 系统 MUST 由纯函数 `formatAsPrompt` 一次性生成该字符串，调用方不做后处理
- 系统 MUST 由 `PromptFormatTest` 覆盖：单行 / 跨行 / 大小写语言标识 / 缺失语言标识 / 代码含反引号 / 结尾形态

### 1.2 触发与启用条件

- **WHEN** 编辑器存在且选中非空白文本 → enabled，点击后写剪贴板并**清空选区**
- **WHEN** 无编辑器 / 无选区 / 选区全空白 → disabled；`actionPerformed` MUST 静默 return
- **WHEN** `project` / `Editor` / `PsiFile` / `virtualFile` 任一为 null → MUST 静默 return
- 系统 MUST NOT 在复制成功时弹通知或 dialog

### 1.3 结尾空行机制(HARD CONTRACT)

输出 MUST 以 `\n` + **两个空格** 结尾（`TRAILING_BLANK`）。

**原因**: 目标输入框是 contenteditable 类实现，只渲染"有内容的行"。纯 `\n` 结尾不产生可见空行，用户粘贴后光标紧贴代码块末尾，继续输入会被吞进代码块。两个空格让末行"有内容"从而渲染出可见空行，视觉上不可见。

**MUST NOT** 省略、改成单个换行、或"清理尾随空格"。

---

## 2. 契约 B：诊断 → prompt（`Copy Diagnostics as Prompt`）

### 2.1 输出格式(HARD CONTRACT)

```text
location:<文件路径>
diagnostics:
- [ERROR] line 12: <消息>
  <出错行原文>
- [WARNING] line 14: <消息>
  <出错行原文>
␠␠
```

| 项       | 规则                                                                                          |
| -------- | --------------------------------------------------------------------------------------------- |
| 路径     | 同 §1.1                                                                                       |
| 严重级别 | 只有 `ERROR` / `WARNING` 两档（映射规则见 §2.3）                                              |
| 行号     | 1-based；诊断起点所在行                                                                       |
| 消息     | **单行纯文本**：HTML 已剥离（`<br>` 转空格），换行折叠为空格，首尾 trim                       |
| 出错行   | 该行原文（trim 后），以**两个空格缩进**（使其归属 Markdown 列表项）；该行无可见文本时整行省略 |
| 排序     | 行号升序 → 同行 ERROR 在前 → 消息字典序（保证输出可复现）                                     |
| 结尾     | 同 §1.3 的 `TRAILING_BLANK`                                                                   |
| 空诊断   | MUST NOT 产生输出（action 为 disabled）                                                       |

**Spec**:

- 系统 MUST 由纯函数 `formatDiagnostics` 生成列表部分，排序由 `sortedForPrompt()` 保证稳定
- 系统 MUST 由 `PromptFormatTest` 覆盖：多档混合 / 出错行为空 / 结尾形态 / 排序稳定性

### 2.2 采集范围与内容

- 范围是**整个文件**（不是选区）：诊断本身是文件级信息，按选区裁剪会让用户困惑"为什么少了几条"
- 采集来源 MUST 是 daemon 已生成的诊断（即用户在编辑器里看到的红/黄波浪线），MUST NOT 触发一次额外的 inspection 全量分析
- 完全相同的诊断 MUST 去重（注入片段可能与其宿主文件产生重复项）

### 2.3 严重级别映射

| 平台 `HighlightSeverity`                                           | 输出           |
| ------------------------------------------------------------------ | -------------- |
| `>= ERROR`                                                         | `ERROR`        |
| `>= WARNING` 且 `< ERROR`                                          | `WARNING`      |
| `< WARNING`（`INFO` / `WEAK_WARNING` / `SYMBOL_TYPE_SEVERITY` 等） | 过滤掉，不输出 |

**Spec**: 系统 MUST NOT 把非诊断类高亮（符号类型提示、搜索命中、引用高亮等）计入输出。

### 2.4 启用条件

- **WHEN** 编辑器存在且**文件内至少有一条 ERROR/WARNING** → enabled
- **WHEN** 无编辑器，或没有任何诊断 → disabled（同时也是"告诉用户这个文件没问题"的信号）
- 判定 MUST 在命中第一条诊断时立即短路，避免菜单渲染时遍历全部高亮

---

## 3. 安全与代码规范(HARD RULES)

### 3.1 包名与标识

- **MUST** 使用 `com.shenyuanlaolarou.copyasprompt` 包名（也是 `pluginGroup` / plugin id 命名空间）
- **MUST NOT** 使用其他包名

### 3.2 分层(HARD RULE)

- **MUST** 把输出格式逻辑放在 `format/` 且实现为**纯函数**（无平台依赖、无 IO、不读环境）
- **MUST NOT** 在 `actions/` 里做字符串格式化；`actions/` 只负责"平台数据 → 纯数据"的转换

### 3.3 平台 API 使用

- **MUST NOT** 使用 `@TestOnly` 标注的平台 API（典型：`DaemonCodeAnalyzerImpl.getHighlights()`）
- 读取 daemon 诊断 MUST 走 document 级 markup model（见 `DESIGN.md §3`）

### 3.4 自动化约束

| 规则                                  | 说明                                                             |
| ------------------------------------- | ---------------------------------------------------------------- |
| AI 禁止自动 `git commit` / `git push` | 必须用户显式授权（`/git-commit` 或 prompt 中 `commit` / `push`） |
| AI 禁止自动 `publishPlugin`           | 必须用户显式授权                                                 |
| AI 禁止修改 `local.properties` 凭证   | 发布令牌 / 私钥等**绝对**不能进 git                              |

### 3.5 Type 安全

- **MUST NOT** 使用 `as Any` / Kotlin 等价的 type erase 绕过 / 滥用 `!!`
- **MUST** 用 `thisLogger().info/warn/error()` 记录日志，不用 `println` / `e.printStackTrace()`

### 3.6 静态全局状态

- **MUST NOT** 在 `object` 里挂 `var` 或非常量的 `MutableMap`
- 当前实现**无任何** `object` 单例

### 3.7 依赖最小化

- **MUST NOT** 引入第三方运行时依赖
- 新增依赖 MUST 在 §4.2 登记并说明不可替代的理由

### 3.8 隐私

插件**完全不联网**、不写用户文件。唯一的外部系统交互面是系统剪贴板。写入剪贴板的内容可能包含用户源码，**MUST NOT** 把剪贴板内容写入日志。

---

## 4. 部署与环境约束

### 4.1 分发

- 插件 MUST 通过 JetBrains Marketplace 分发
- 产物: `./gradlew buildPlugin` → `build/distributions/copy-as-prompt-<版本>.zip`
- **首次发布 MUST 网页手动上传**（Marketplace 对未创建过的插件 id 不接受 Gradle 上传）
- 调试运行: `./gradlew runIde`

### 4.2 依赖

| 依赖                             | 版本              | 说明                                                  |
| -------------------------------- | ----------------- | ----------------------------------------------------- |
| Kotlin                           | 2.3.20            | —                                                     |
| Gradle                           | 9.3.1             | —                                                     |
| JDK                              | 21                | 编译/运行（`jvmToolchain(21)`）                       |
| IntelliJ Platform                | 2026.1            | `pluginSinceBuild=261`，`pluginUntilBuild` 留空       |
| JUnit                            | 4.13.2            | 测试                                                  |
| opentest4j                       | 1.3.0             | 测试断言                                              |
| IntelliJ Platform TestFramework  | 2026.1            | 平台集成测试                                          |
| `com.intellij.java`（bundled）   | —                 | **仅测试**（诊断测试需要语言支持）；不进 `plugin.xml` |
| Qodana（linter / Gradle plugin） | 2024.3 / 2025.3.1 | 静态分析                                              |
| Kover                            | 0.9.5             | 覆盖率                                                |

### 4.3 平台与系统约束

| 项       | 约束                                                                           |
| -------- | ------------------------------------------------------------------------------ |
| 操作系统 | 无平台限制（纯平台 API + AWT）                                                 |
| 目标 IDE | 所有带 `com.intellij.modules.platform` 的 IDE（IDEA / PyCharm / WebStorm / …） |
| 外部命令 | 无                                                                             |
| 用户配置 | 无（不读写任何配置文件）                                                       |

---

## 5. 日志约定

- **MUST** 用 `thisLogger().info/warn/error()`；前缀格式 `[<类名>] <消息>`
- **MUST NOT** 输出凭证、剪贴板内容、源码内容
- 当前实现的成功路径无日志（复制本身即反馈）

---

## 版本与变更

| 版本  | 日期   | 变更说明                                                                                                                         |
| ----- | ------ | -------------------------------------------------------------------------------------------------------------------------------- |
| 0.1.0 | 未发布 | 首个版本：`Copy as Prompt`（选区 → prompt，含语言标识与相对路径）+ `Copy Diagnostics as Prompt`（文件级 ERROR/WARNING → prompt） |
