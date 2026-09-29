# Copy as Prompt

![Build](https://github.com/syllr/copy-as-prompt/workflows/Build/badge.svg)

JetBrains IDE 插件：**把 IDE 里的信息变成 prompt 文本**，一键复制到剪贴板，粘贴即可丢给任意 LLM 对话界面。

相比手动描述位置、或直接复制裸文本，它替你把"AI 需要的上下文"组装好：

| Action                         | 做什么                                | 输出长什么样 |
| ------------------------------ | ------------------------------------- | ------------ |
| **Copy as Prompt**             | 把选中的代码带上文件路径与行号        | 见下         |
| **Copy Diagnostics as Prompt** | 把当前文件的编译错误 / 警告整理成清单 | 见下         |

<!-- Plugin description -->

## Plugin Description

> **Note:** This is an unofficial, community-maintained plugin. Not affiliated with any AI vendor.

### Features

- **Copy as Prompt** — select code, right-click, and get it on your clipboard together with the file path, the exact line range and a fenced code block with a language tag:

  ````
  location:src/main/kotlin/Foo.kt:10-20
  content:
  ```kotlin
  val a = 1
  val b = 2
  ```

  ````

- **Copy Diagnostics as Prompt** — turn the errors and warnings of the current file into a compact, LLM-friendly list (severity, line, message, and the offending source line):

  ```
  location:src/main/kotlin/Foo.kt
  diagnostics:
  - [ERROR] line 12: Cannot resolve method 'foo'
    foo();
  - [WARNING] line 14: 'x' is never used
    val x = 1

  ```

- **Trailing blank line, on purpose** — every output ends with a line holding two spaces, so the target chat box renders a visible empty line after the content instead of swallowing your next keystrokes into a code block.
- **Project-relative paths** — no leaking of your home directory into shared prompts.
- **Stable, test-covered format** — the produced text is treated as a cross-version contract and is asserted by unit tests.
- **Zero dependencies at runtime** — no server to start, no network access, no config files, no background processes. Just platform API and the system clipboard.
- **Zero interruption** — no notifications, dialogs or progress bars.
- **Any JetBrains IDE** — only requires the platform (`com.intellij.modules.platform`).

<!-- Plugin description end -->

## 安装

- 使用 IDE 内置插件系统：

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>搜索 "Copy as Prompt"</kbd> >
  <kbd>Install</kbd>

- 手动安装：

  下载[最新版本](https://github.com/syllr/copy-as-prompt/releases/latest)，然后
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>从磁盘安装插件...</kbd>

## 使用说明

### 1. 复制选中代码（Copy as Prompt）

1. 在编辑器中**选中**一段代码（单行或多行均可）
2. **右键** → **Copy as Prompt**（也在 Copy / Paste 附近的菜单分组里）
3. 内容已进剪贴板，编辑器选区自动取消
4. 到任意 LLM 对话界面 <kbd>Cmd/Ctrl+V</kbd>

输出示例（选中 `src/main/kotlin/Foo.kt` 第 10-20 行）：

````text
location:src/main/kotlin/Foo.kt:10-20
content:
```kotlin
val a = 1
val b = 2
```

````

### 2. 复制当前文件的错误与警告（Copy Diagnostics as Prompt）

1. 把光标放在任意位置（**不需要选中**任何东西）
2. **右键** → **Copy Diagnostics as Prompt**
3. 该文件当前所有的 ERROR / WARNING 会按行号整理成清单进剪贴板

输出示例：

```text
location:src/main/kotlin/Foo.kt
diagnostics:
- [ERROR] line 12: Cannot resolve method 'foo'
  foo();
- [WARNING] line 14: 'x' is never used
  val x = 1

```

> **菜单项是灰的？** 说明当前文件没有 error / warning（这也是它在告诉你"这个文件没问题"）。**Copy as Prompt** 则是没选中非空白文本时为灰。

## 配置

**无需任何配置。** 插件不读写配置文件、不读取环境变量、不联网、不启动任何外部进程。

## 设计要点

- **相对路径优先**：文件在项目内时用相对项目根的路径；不在项目内（外部库、scratch 文件）才回退绝对路径
- **语言标识**：代码围栏带语言（` ```kotlin `），取自文件的语言
- **诊断范围是整个文件**，不跟随选区——少给一条信息比多给一条的代价更高
- **诊断来源是"你已经看到的那一份"**：只读 daemon 已经算好的高亮，不会额外触发一次分析
- **结尾两个空格**：让目标输入框渲染出可见空行，避免后续输入被吞进代码块

## 故障排除

**问题：右键菜单里找不到这两个 action**

- 确认插件已启用（<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd>）
- 确认焦点在**编辑器**内（这两个 action 只出现在编辑器右键菜单）
- **Copy as Prompt** 需要选中非空白文本；**Copy Diagnostics as Prompt** 需要文件里有 error/warning

**问题：诊断只列出了 part 的问题 / 少了几条**

- 插件只采集 daemon 已经算出的高亮。如果某个文件刚打开、后台分析还没跑完，可能还没生成全部诊断——稍等片刻或让编辑器获得焦点后再复制
- 插件只输出 **ERROR / WARNING** 两档（不含 INFO、弱警告、拼写提示等）

**问题：粘贴后末尾的空行不见了**

- 输出的末尾是"换行 + 两个空格"。粘到纯文本编辑器里这两个空格不可见是正常的；在 contenteditable 类输入框（多数 LLM 网页界面）里才会渲染成空行

**问题：文件路径是绝对的**

- 只有文件不在当前项目内时才会用绝对路径

## 开发

```bash
./gradlew test                     # 单元测试（纯函数 + 平台集成）
./gradlew runIde                   # 启动带插件的 IDE
./gradlew buildPlugin              # 构建插件 zip
```

目录结构、强制约束与平台 API 踩坑见 [`AGENTS.md`](./AGENTS.md)；行为契约见 [`SPEC.md`](./SPEC.md)；架构与关键决策见 [`DESIGN.md`](./DESIGN.md)。

## 许可证

本项目采用 MIT 许可证。

## 免责声明

本插件是社区维护的非官方插件。使用本插件产生的任何问题，请通过 GitHub Issues 反馈。

---

> 💡 如果你觉得这个插件有帮助，欢迎给个 Star 🌟
