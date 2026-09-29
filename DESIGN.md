# DESIGN.md — Copy as Prompt 设计文档

> **本文档定位**:项目级**架构设计文档**。定义"如何实现"，不重复"必须满足什么"（那在 `SPEC.md`）。
>
> **阅读顺序**:`AGENTS.md` → `SPEC.md` → `DESIGN.md`

---

## 0. 项目概述

一个约 350 行的 JetBrains IDE 插件（2 个 action），把 IDE 信息格式化成 prompt 文本写入剪贴板。

**设计目标**（按优先级）：

1. **零外部依赖** — 不联网、不起进程、不读配置文件
2. **零打扰** — 复制是同步动作，不弹通知 / dialog / 进度条
3. **格式可被完整测试** — 输出格式是纯函数，单测直接断言字符串
4. **诊断来源必须是"用户看到的那一份"** — 不额外触发一次分析，采集 daemon 已有结果

---

## 1. 分两层

```
src/main/kotlin/com/shenyuanlaolarou/copyasprompt/
├── format/
│   └── PromptFormat.kt        ← 纯函数：格式化规则（唯一实现处）
└── actions/
    ├── CopyAsPromptAction.kt      ← 平台接线：选区
    ├── CopyDiagnosticsAction.kt   ← 平台接线：诊断
    └── EditorPromptContext.kt     ← 平台 API → 纯数据的转换
```

| 层        | 允许                                          | 禁止                          | 测试方式                       |
| --------- | --------------------------------------------- | ----------------------------- | ------------------------------ |
| `format/` | 字符串操作                                    | 平台 import、IO、环境变量     | 纯 JUnit 4（秒级）             |
| `actions/`| 平台 API（PSI、Editor、MarkupModel、剪贴板）  | 字符串格式化                  | `BasePlatformTestCase`（慢）   |

**为什么这层边界最重要**: 输出格式是本插件唯一的对外契约（用户会把它贴进既有对话上下文，格式突变会让历史 prompt 不一致）。把格式做成纯函数，意味着**改格式的成本 = 改一个函数 + 改断言**，不需要启动 IDE、不需要构造 `AnActionEvent`。

---

## 2. 关键决策

### 决策 1:诊断来源用 document 级 markup model(不是重跑 inspection)

**问题**: 怎么拿到"当前文件的错误/警告"？

候选方案：

| 方案                                              | 结论                                                                    |
| ------------------------------------------------- | ----------------------------------------------------------------------- |
| `DaemonCodeAnalyzerImpl.getHighlights(...)`       | ❌ 带 **`@TestOnly`** 注解，生产代码不可用                              |
| 自己跑一遍 `InspectionManager` 的 inspection      | ❌ 慢（秒级）、会重复 daemon 的工作、还会引入"跑全项目分析"的对话框      |
| 读 editor 的 `editor.markupModel`                 | ❌ **读不到**——daemon 不往这里放（实测得到空列表，且不报错，静默失败）   |
| 读 document 级 `DocumentMarkupModel`              | ✅ 采用                                                                 |

采用路径：

```kotlin
val markup = DocumentMarkupModel.forDocument(document, project, false) as? MarkupModelEx
markup.processRangeHighlightersOverlappingWith(0, document.textLength) { highlighter ->
    val info = HighlightInfo.fromRangeHighlighter(highlighter) // 内部读 getErrorStripeTooltip()
    if (info != null && info.severity >= HighlightSeverity.WARNING) infos += info
    true
}
```

这条路径是**平台自己用的**：`HighlightInfoUpdaterImpl` 里的 `getInfosFromMarkup` 就是遍历 document 级模型 + `HighlightInfo.fromRangeHighlighter`。我们与平台保持一致。

三个实践约束：

1. `processRangeHighlightersOverlappingWith` 的 processor **在 MarkupModel 锁内执行**：只允许"过滤 + 入列"，禁止格式化 / IO / 改模型。所以先收集 `HighlightInfo`，出了 processor 再做行号、消息、排序等计算。
2. `HighlightInfo.fromRangeHighlighter` 读的是 `getErrorStripeTooltip()`，因此**只有 daemon 生成的高亮能被还原**；编辑器里其它高亮（搜索命中、引用高亮、symbol type 提示）自动被排除，不需要额外过滤。
3. `forDocument(..., create = false)`：只读已存在的容器，不为只读操作创建平台对象。

### 决策 2:严重级别用 `>= WARNING` 阈值而不是枚举白名单

平台上同一位置会注册多种高亮：诊断（ERROR/WARNING）+ 非诊断（`SYMBOL_TYPE_SEVERITY` 等）。用 `severity >= HighlightSeverity.WARNING` 一个条件就同时完成"过滤非诊断"和"分级映射"：

```
>= ERROR   → ERROR
>= WARNING → WARNING
其余        → 丢弃
```

好处：插件自定义 severity（第三方 inspection 注册的 ERROR 级 severity）也能被正确分类，不需要维护白名单。

### 决策 3:诊断范围是整个文件,不跟随选区

诊断天然是文件级信息。按选区裁剪会出现"我明明选中有错误的这段，为什么只列了 1 条"的困惑，而**少给信息**比**多给信息**在这个场景下代价更高（用户看到多余的一条会自己忽略，看不到需要的一条则会得出错误结论）。

### 决策 4:路径优先相对项目根

绝对路径（`/Users/xxx/IdeaProjects/foo/src/Bar.kt`）把本机目录结构带进 prompt，分享出去是噪音；相对路径（`src/Bar.kt`）更短且对 agent 同样无歧义（工作目录就是项目根）。文件不在项目内时（如外部库、scratch 文件）回退绝对路径——此时相对路径无意义。

### 决策 5:代码围栏带语言标识

` ```kotlin ` 让渲染端做语法高亮，也让 agent 少一次猜测。取自 PSI 语言 id 并转小写（平台的 id 多为首字母大写，如 `JAVA`）。

### 决策 6:剪贴板直接用 AWT,不用平台 `CopyPasteManager`

与系统其它应用行为一致（纯文本 `StringSelection`）。`CopyPasteManager` 会带上平台的内部/富文本剪贴板语义，粘贴到浏览器输入框时可能带格式。

### 决策 7:不用 `object` 单例,不引入 i18n

两个 action 都无状态（每次调用从 `AnActionEvent` 取数据），不需要单例。文案硬编码在 `plugin.xml`：只有 2 个菜单项，为两个字符串维护 bundle 文件 + `DynamicBundle` 不划算。

### 决策 8:版本从 0.1.0 起步,插件 id 与包名一致

这是**新插件**（新 Marketplace 页面、新仓库），不是老 `opencode-web-ui` 的续作，因此版本线与 id 都另起：`com.shenyuanlaolarou.copyasprompt` / `0.1.0`。

---

## 3. 关键流程

### 3.1 `Copy as Prompt`

```
右键菜单渲染 → update(e)
    editor == null || !hasSelection || selectedText 全空白 → enabled = false

点击 → actionPerformed(e)
    project ?: return / editor ?: return / selectedText 全空白 → return
    psiFile = PsiDocumentManager.getPsiFile(editor.document) ?: return
    filePath = promptFilePath(project, psiFile) ?: return           ← 相对项目根优先
    startLine = document.getLineNumber(selectionStart) + 1
    endLine   = document.getLineNumber(lastSelectedOffset) + 1      ← 见下
    formatAsPrompt(filePath, startLine, endLine, selectedText, languageTagOf(psiFile))
        └→ 系统剪贴板
    selectionModel.removeSelection()
```

`lastSelectedOffset = if (endOffset > startOffset) endOffset - 1 else startOffset`

**为什么 `-1`**：选区恰好结束在某行行首时（例如从第 10 行选到第 12 行行首），`endOffset` 指向第 12 行的第一个字符——它并没有被选中。用 `endOffset - 1` 反查末行才能得到真实覆盖的末行（第 11 行）。直接取 `endOffset` 会把未选中的那一行算进行号范围。

### 3.2 `Copy Diagnostics as Prompt`

```
右键菜单渲染 → update(e)
    project == null || editor == null → enabled = false
    hasDiagnostics(project, document)   ← 命中第一条 ERROR/WARNING 即短路返回
    → enabled

点击 → actionPerformed(e)
    project ?: return / editor ?: return
    psiFile / filePath 取不到 → return
    diagnostics = collectDiagnostics(project, editor.document)
        1) 读 document 级 markup model，遍历高亮 → List<HighlightInfo>（锁内只入列）
        2) 每条 → Diagnostic(severity, line, message, codeLine)
           · line    = document.getLineNumber(startOffset) + 1
           · message = stripHtml(description) → 单行纯文本
           · codeLine= 该行原文 trim
        3) distinct()（注入片段可能与宿主重复）
        4) sortedForPrompt()（行号 → severity → 消息）
    diagnostics.isEmpty() → return
    formatDiagnostics(filePath, diagnostics) → 系统剪贴板
```

**消息净化**（`plainMessage`）：inspection 的描述可能带 HTML（`<code>` 等）与换行，直接塞进 prompt 会破坏 Markdown 列表结构。因此 `StringUtil.stripHtml(description, convertBreaks = true)` 把 `<br>` 转成换行，再按行 trim + 用空格拼成单行，最后把 `&nbsp;` 换成普通空格。刻意不使用正则——只需要字符替换。

---

## 4. 验证策略

| 层次             | 测试                            | 覆盖                                                                |
| ---------------- | ------------------------------- | ------------------------------------------------------------------- |
| 格式契约（纯）   | `format/PromptFormatTest`（11 例） | 两个契约的完整形态、语言标识大小写、缺失语言标识、出错行为空、排序稳定性 |
| 平台集成（真环境）| `actions/EditorDiagnosticsTest`（4 例） | Java 文件里的编译错误能被采集到；干净文件采集为空；结果按行号有序     |

平台集成测试存在的唯一理由：**decision 1 选错了不会报错**。如果哪天平台把 daemon 高亮搬到别处，`collectDiagnostics` 会静静地返回空列表——用户看到的是"菜单灰着，点不了"。这个测试会先失败。

**测试环境注意**：测试沙箱默认只加载平台，必须有 `platformBundledPlugins = com.intellij.java` 才能在测试里得到一个"能产生诊断"的 `.java` 文件；否则 `.java` 会被当成 PLAIN_TEXT，测试会**假通过**（0 条诊断 vs 期望 0 条）。`EditorDiagnosticsTest` 里显式断言"至少一条诊断"，就是为了堵住这个假通过。

**测试方法命名**：`BasePlatformTestCase` 继承 JUnit 3 的 `TestCase`，JUnit 4 会用 `JUnit38ClassRunner` 运行它，`@Test` 注解不生效。方法名必须是 `testXxx`，否则被静默跳过。

---

## 5. 文件清单

| 文件                                                       | 层   | 说明                                                        |
| ---------------------------------------------------------- | ---- | ----------------------------------------------------------- |
| `src/main/kotlin/.../format/PromptFormat.kt`               | 纯   | `formatAsPrompt` / `formatDiagnostics` / `Diagnostic` / `sortedForPrompt` / `TRAILING_BLANK` |
| `src/main/kotlin/.../actions/EditorPromptContext.kt`        | 平台 | `copyToClipboard` / `promptFilePath` / `languageTagOf` / `hasDiagnostics` / `collectDiagnostics` |
| `src/main/kotlin/.../actions/CopyAsPromptAction.kt`         | 平台 | 选区 action                                                  |
| `src/main/kotlin/.../actions/CopyDiagnosticsAction.kt`      | 平台 | 诊断 action                                                  |
| `src/main/resources/META-INF/plugin.xml`                    | —    | 插件描述 + 2 个 action 注册                                  |
| `src/main/resources/META-INF/EULA.txt` / `PRIVACY_POLICY.txt` | —  | Marketplace 法务文本（未在 plugin.xml 引用）                 |
| `src/test/kotlin/.../format/PromptFormatTest.kt`            | 纯   | 格式契约                                                     |
| `src/test/kotlin/.../actions/EditorDiagnosticsTest.kt`      | 平台 | 诊断采集                                                     |
| `.github/workflows/build.yml`                               | —    | CI：test / build / verify / publish(tag)                     |

---

## 6. 资源管理

无长期持有的资源：无进程、无连接、无线程、无缓存、无 `Disposable`。

每次调用新建的临时对象（`StringSelection`、字符串、`Diagnostic` 列表）由 GC 回收；剪贴板内容的所有权交给系统剪贴板服务。markup model 的遍历只读，不注册监听器，因此**不需要**注销。

---

## 附录 A:已知限制与权衡

| 项                                  | 现状                                                     | 说明 / 未来                                                        |
| ----------------------------------- | -------------------------------------------------------- | ------------------------------------------------------------------ |
| 诊断只到文件级                      | 不做选区裁剪                                             | 有意为之（决策 3）                                                 |
| 不含 inspection 的 quick-fix 建议   | 只输出消息 + 出错行                                      | `HighlightInfo` 有 quick fix 列表，但平台 API 偏 internal；有真实需求再评估 |
| 注入片段（如 Java 里的 SQL）诊断    | 一并采集 + 去重，可能与宿主文件产生同类条目              | 去重兜底已够用；若要精确区分需读 `isFromInjection()`               |
| 依赖 `HighlightInfo`（impl 包）     | 已确认非 `@Internal`，且无公开替代                       | 若未来被废弃，改 `collectDiagnostics` 一处即可                     |
| 无 `.properties`/`.json` 之类的翻译 | 文案硬编码在 `plugin.xml`                                | 决策 7                                                             |
| 未启用 UI 测试                      | 只做单测 + 平台集成测试                                  | 菜单项 enabled 行为靠 `update()` 单测（暂缺）+ 手动 `runIde` 验证   |
