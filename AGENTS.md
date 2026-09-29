# AGENTS.md

JetBrains IDE 插件 (Kotlin)：把 IDE 里的信息（选中代码、编译错误/警告）格式化成 **prompt 文本** 复制到剪贴板，供粘贴到任意 LLM 对话界面。

不联网、不起进程、不读配置文件；装完即用，卸载无残留。

## 项目级元文档

| 文档                         | 关注                                 | 何时读                               |
| ---------------------------- | ------------------------------------ | ------------------------------------ |
| **[AGENTS.md](./AGENTS.md)** | 怎么开发（流程规则、强制约束、踩坑） | **第一份必读** — 任何任务开始前      |
| **[SPEC.md](./SPEC.md)**     | 必须满足什么（输出格式契约、硬规则） | 改输出格式 / 改行为 / Code Review 前 |
| **[DESIGN.md](./DESIGN.md)** | 怎么实现（分层、关键决策、平台坑）   | 加 action / 排查平台 API 问题时      |

## STACK

- Gradle 9.3.1 · JDK 21 · Kotlin 2.3.20 · IntelliJ Platform Gradle Plugin 2.14.0
- 目标平台: 2026.1（`pluginSinceBuild=261`，`pluginUntilBuild` 留空）
- **运行时依赖: 无** — 只用 IntelliJ Platform API + JDK 自带（`java.awt` / `java.util`）
- 测试依赖: JUnit 4.13.2、opentest4j 1.3.0、IntelliJ Platform TestFramework
- `platformBundledPlugins = com.intellij.java` — **仅测试需要**（平台集成测试要一个能产生诊断的高亮来源）；生产代码不引用 Java API，`plugin.xml` 里也**没有** `<depends>com.intellij.java>`
- 静态分析: Qodana（linter `jetbrains/qodana-jvm-community:2024.3`，Gradle plugin `2025.3.1`）+ Kover 0.9.5 + CodeCov
- `CHANGELOG.md` 由 `org.jetbrains.changelog` Gradle 插件（`patchChangelog` 任务）管理，**不要手改版本段落结构**

### 四个容易混淆的"名字"

| 位置                                     | 值                                  | 作用                                                       |
| ---------------------------------------- | ----------------------------------- | ---------------------------------------------------------- |
| `plugin.xml` `<id>`                      | `com.shenyuanlaolarou.copyasprompt` | 插件 id（市场唯一标识）。**发布后永久不可改**              |
| `gradle.properties` `pluginName`         | `Copy as Prompt`                    | 构建时 patch 到 `plugin.xml` `<name>` 的**显示名**         |
| `settings.gradle.kts` `rootProject.name` | `copy-as-prompt`                    | 决定产物名 `build/distributions/copy-as-prompt-<版本>.zip` |
| Kotlin 包名                              | `com.shenyuanlaolarou.copyasprompt` | 与插件 id 保持一致                                         |

## STRUCTURE

源码根: `src/main/kotlin/com/shenyuanlaolarou/copyasprompt/`

严格两层，职责不混：

| 目录       | 文件                                                                            | 职责                                                             |
| ---------- | ------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `format/`  | `PromptFormat.kt`                                                               | **纯函数**：输出格式的唯一实现处。无平台依赖、无 IO → 可直接单测 |
| `actions/` | `CopyAsPromptAction.kt` / `CopyDiagnosticsAction.kt` / `EditorPromptContext.kt` | 平台接线：取 IDE 数据、判定 enabled、写剪贴板。**不做格式化**    |

测试根: `src/test/kotlin/com/shenyuanlaolarou/copyasprompt/`

| 测试                               | 类型                           | 覆盖                                       |
| ---------------------------------- | ------------------------------ | ------------------------------------------ |
| `format/PromptFormatTest.kt`       | 纯 JUnit 4（11 例）            | 两个格式契约 + 稳定排序                    |
| `actions/EditorDiagnosticsTest.kt` | `BasePlatformTestCase`（4 例） | 诊断采集能真的从 markup model 捞出编译错误 |

## WHERE TO LOOK

| 想做的事                               | 改哪里                                                                            |
| -------------------------------------- | --------------------------------------------------------------------------------- |
| 改 prompt 输出格式                     | `format/PromptFormat.kt` + 同步 `PromptFormatTest` + `SPEC.md §1/§2`              |
| 改菜单项文案 / 出现位置                | `src/main/resources/META-INF/plugin.xml` 的 `<action>` / `<add-to-group>`         |
| 改 action 的启用条件                   | 各 action 的 `update()`                                                           |
| 改文件路径写法（相对/绝对）            | `actions/EditorPromptContext.kt` 的 `promptFilePath()`                            |
| 改语言标识（代码围栏的 ` ```kotlin `） | `actions/EditorPromptContext.kt` 的 `languageTagOf()`                             |
| 改诊断采集范围 / 过滤规则              | `actions/EditorPromptContext.kt` 的 `collectDiagnostics()`（先读 `DESIGN.md §3`） |
| 加一个新的 action                      | `actions/` 新建类 + `plugin.xml` 注册；格式化逻辑放 `format/`                     |
| 改插件显示名 / 版本号                  | `gradle.properties` 的 `pluginName` / `pluginVersion`                             |

## HARD RULES

- **包名 = `com.shenyuanlaolarou.copyasprompt`**: 也是 `pluginGroup` / plugin id 的命名空间。新代码必须用 `com.shenyuanlaolarou.*`
- **格式化逻辑必须放 `format/` 且是纯函数**: 无平台依赖、无 IO、不读环境。平台接线放 `actions/`。这条是"格式可被单测完整断言"的前提
- **禁止使用 `@TestOnly` 的平台 API**: 典型陷阱是 `DaemonCodeAnalyzerImpl.getHighlights()`（见 `DESIGN.md §3.1`）
- **禁止静态全局可变状态**: 不要在 `object` 里挂 `var`
- **Type 抑制**: 禁止 `as Any` / Kotlin 等价的 type erase 绕过 / 滥用 `!!`
- **日志**: 用 IntelliJ Platform 的 `thisLogger().info/warn/error()`，不要 `println` / `e.printStackTrace()`
- **Git 提交/push**: AI 禁止自动 commit/push，必须用户显式调用（受 OpenCode 全局 `git-commit-block` rule 约束）
- **发布**: AI 禁止自动 `publishPlugin`，必须用户显式调用
- **本机持久性变更**: 禁止 AI 擅自安装软件或改本机配置文件

## COMMANDS

```bash
./gradlew test                    # 单元测试（纯函数 + 平台集成）
./gradlew test --tests "*PromptFormatTest*"   # 只跑格式契约（秒级，改格式时用这个）
./gradlew runIde                  # 启动带插件的 IDE（手动验证 UI 行为）
./gradlew buildPlugin             # 构建插件 zip（输出 build/distributions/）
./gradlew check                   # 单元测试 + Kover + Qodana
./gradlew verifyPlugin            # Plugin Verifier（下载目标 IDE，慢，需网络）
./gradlew patchChangelog          # CHANGELOG.md → publishPlugin 自动依赖
./gradlew publishPlugin           # 发布到 Marketplace（需 env: PUBLISH_TOKEN 等）
```

**离线 / 本地缓存**：`--offline` 可用（平台产物已在 Gradle 缓存里）。

**构建缓存陷阱**：本项目 `org.gradle.caching=true`。删除源文件后如果不 `rm -rf build` 或加 `--no-build-cache`，打出的 jar 可能残留旧包的**空目录条目**。验证产物用：

```bash
rm -rf build && ./gradlew buildPlugin --offline --no-build-cache
unzip -l build/distributions/copy-as-prompt-*.zip
```

**签名/发布 env**（`local.properties` 已 gitignore）: `PUBLISH_TOKEN`、`CERTIFICATE_CHAIN`、`PRIVATE_KEY`、`PRIVATE_KEY_PASSWORD`。**绝对不能**进 git。

## TESTING + CI

| 类型     | 框架                               | 位置                                            |
| -------- | ---------------------------------- | ----------------------------------------------- |
| 单元测试 | JUnit 4                            | `src/test/.../format/PromptFormatTest.kt`       |
| 集成测试 | `BasePlatformTestCase`             | `src/test/.../actions/EditorDiagnosticsTest.kt` |
| UI 测试  | Robot Server（`runIdeForUiTests`） | 未启用                                          |

CI: `.github/workflows/build.yml`（本仓库内，已从 `.gitignore` 放出）

| job       | 触发             | 内容                                         |
| --------- | ---------------- | -------------------------------------------- |
| `test`    | push / PR / 手动 | `./gradlew test` + 上传测试报告              |
| `build`   | push / PR / 手动 | `./gradlew buildPlugin` + 上传 zip           |
| `verify`  | push / PR / 手动 | `./gradlew verifyPlugin`（下载 IDE，较慢）   |
| `publish` | 打 `v*` tag      | `./gradlew publishPlugin`（需 4 个 Secrets） |

## UNIQUE STYLES / 约定

- **`BasePlatformTestCase` 的测试方法必须以 `test` 开头**（不是 `@Test`）：该类继承 JUnit 3 的 `TestCase`，JUnit 4 会用 `JUnit38ClassRunner` 跑它，`@Test` 注解不生效——方法名不对会被**静默跳过**
- **输出格式的结尾永远是 `TRAILING_BLANK`（`\n` + 两个空格）**：见 `format/PromptFormat.kt` 的 KDoc，不要"顺手清理尾随空格"
- **诊断只取 ERROR / WARNING**：`HighlightSeverity >= WARNING`。同一位置上平台还会注册 `SYMBOL_TYPE_SEVERITY` 之类的高亮，它们不是诊断
- **路径优先相对项目根**：`promptFilePath()` 能算相对路径就用相对路径，否则回退绝对路径
- 剪贴板只走 `Toolkit.getDefaultToolkit().systemClipboard`（不引入平台 `CopyPasteManager`，理由见 `DESIGN.md §2.2`）
- Action 文案硬编码在 `plugin.xml`（无 i18n bundle；只有 2 个 action，为一个字符串维护 bundle 不划算）

## 注意事项（容易踩的坑）

- **`HighlightInfo` 在 `...daemon.impl` 包里，但它只是 `@ApiStatus.NonExtendable`，不是 `@Internal`** —— 可以用，且是当前唯一可行的诊断读取路径
- **daemon 高亮存在 document 级 `DocumentMarkupModel`，不是 `editor.markupModel`**。读错地方会得到空列表（不报错，静默失败）
- **测试沙箱默认只有平台**：不设 `platformBundledPlugins` 的话 `.java` 文件会被当成 PLAIN_TEXT，诊断测试"成功"地采集到 0 条
- **`local.properties` 已 gitignore 但可能含明文私钥/令牌**。优先用环境变量（`build.gradle.kts` 已支持 `providers.environmentVariable()`）
- **Marketplace 新插件 id 的首个版本必须网页手动上传**，之后 `publishPlugin` 才能推新版本
- **`research/archive/`**（若存在）是已归档的规划文档，不要当作当前代码参考

## REFERENCES

- 官方开发指南: https://plugins.jetbrains.com/docs/intellij/
- 平台源码: `~/.gradle/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/idea/<版本>/*-sources.jar`（核对平台 API 签名最可靠的来源）
- 本仓库: https://github.com/syllr/copy-as-prompt
