# 项目知识库

**Generated:** 2026-04-12
**Commit:** d8e733e
**Branch:** main

## 项目概述
JetBrains IntelliJ Platform 插件，用于 OpenCode Web UI 集成（fork版本，包含 Emacs 快捷键、自动重启、ESC 修复）。

## 项目结构
```
intellij-opencode-web/
├── src/main/kotlin/com/github/xausky/opencodewebui/
│   ├── toolWindow/          # 核心 JCEF + 服务器管理
│   ├── actions/             # IDE actions（重启、切换、快捷键）
│   ├── services/            # 平台服务
│   ├── listeners/           # 生命周期监听器
│   ├── startup/             # 启动活动
│   └── utils/               # Session 辅助工具
├── src/main/resources/       # 图标、plugin.xml、消息
├── .github/workflows/        # CI/CD（构建、发布、UI测试）
└── build.gradle.kts         # Gradle 配置
```

## 关键位置
| 任务 | 位置 | 备注 |
|------|------|------|
| 核心逻辑 | toolWindow/MyToolWindowFactory.kt | JCEF + 服务器管理 |
| IDE actions | actions/ | RestartServer、PassToJcef、CopyAsPrompt |
| CI/CD | .github/workflows/ | 构建、发布、UI测试 |
| 构建配置 | build.gradle.kts, gradle.properties | 依赖、版本 |
| 测试 | src/test/ | 单元测试 |
| Action 注册 | plugin.xml | Action 的 add-to-group 配置 |

## 代码规范
- Gradle 版本目录 (`gradle/libs.versions.toml`)
- IntelliJ Platform 最佳实践
- JDK 21, Kotlin 2.3.0
- Qodana + Kover 代码质量
- 插件描述从 `README.md` 的 `<!-- Plugin description -->` 提取
- SemVer 版本格式，支持预发布标签
- **Git 提交规则**: Agent 禁止自动提交 git，必须等待用户显式调用才能提交

## 调试日志
IntelliJ 插件日志通过 `thisLogger().info/warn/error()` 输出到 IDE 日志窗口。

**控制台打印（推荐用于快速调试）**:
```kotlin
println("=== DEBUG: variable = $variable")
```

**查看日志**:
- 日志文件位置: `build/idea-sandbox/IU-2025.3.4/log/`

**注意**: 调试完成后应移除临时日志语句。

## 反模式（此项目问题）
- 包名不匹配：`com.github.xausky.opencodewebui` 源码 vs `com.shenyuanlaolarou` pluginGroup
- 单个 630 行核心文件（MyToolWindowFactory.kt，可拆分）
- ~~静态全局服务器状态~~ → 已修复：使用 AtomicReference/AtomicBoolean
- ~~弃用的 JBCefBrowser 构造函数~~ → 已修复：使用 JBCefBrowserBuilder
- 使用 SQLite JDBC 进行会话管理（对插件来说不常见）

## 特色功能
- Emacs 风格 JCEF 键盘快捷键（Ctrl+A/E/B/F/N/P）
- 首次打开工具窗口时自动重启服务器
- ESC 键焦点修复（JCEF）
- 通过 Find Action 手动重启（Cmd+Shift+A）
- 端口 12396（非标准 10086）
- **中文输入法修复**：移除 `e.consume()`，解决 JCEF 中中文输入滞后问题
- **外部链接处理**：点击外部链接（GitHub、文档等）在系统浏览器打开，而非 JCEF 内部
- **右键菜单**：支持"在浏览器中打开"选项
- **会话恢复**：自动恢复上次会话（从 SQLite 数据库读取 session ID）
- **Copy as Prompt**：编辑器右键菜单复制选中文本为 Prompt 格式（输出格式：`location:/path/file:10-20\ncontent:\n```\n选中文本\n```）

## 常用命令
```bash
./gradlew buildPlugin          # 构建插件
./gradlew check                 # 测试 + Qodana
./gradlew verifyPlugin          # 验证插件结构
./gradlew runIde                # 运行带插件的 IDE
./gradlew publishPlugin         # 发布到 Marketplace
./gradlew qodana                # 代码质量检查
```

## 发布插件
```bash
# 1. 升级版本号（gradle.properties 中的 pluginVersion）

# 2. 设置 token（方式一：环境变量）
export PUBLISH_TOKEN="your-jetbrains-marketplace-token"

# 3. 发布
./gradlew publishPlugin

# 或方式二：使用 Gradle property
./gradlew publishPlugin -PpublishToken="your-token"
```

**Token 获取方式**：
1. 登录 [JetBrains Marketplace](https://plugins.jetbrains.com/)
2. 进入插件管理页面 → Access Tokens
3. 创建一个新的 Publishing token
4. 设置环境变量或直接使用

## 备注
- Fork 自 xausky/intellij-opencode-web-ui 的增强版本
- 插件 ID: `com.shenyuanlaolarou.opencodewebui`

## 参考资料

### IntelliJ Platform 插件开发
| 资源 | URL | 类型 |
|------|-----|------|
| **DevGuide（官方）** | https://plugins.jetbrains.com/docs/intellij/ | ⭐ 官方 |
| **插件模板** | https://github.com/JetBrains/intellij-platform-plugin-template | ⭐ 官方 |
| **SDK 代码示例** | https://github.com/JetBrains/intellij-sdk-code-samples | ⭐ 官方 |
| **SDK 文档源码** | https://github.com/JetBrains/intellij-sdk-docs | ⭐ 官方 |
| **Gradle 插件** | https://github.com/JetBrains/gradle-intellij-plugin | ⭐ 官方 |
| **Marketplace** | https://plugins.jetbrains.com/ | ⭐ 官方 |

### JCEF 框架
| 资源 | URL | 类型 |
|------|-----|------|
| **JCEF 官方文档** | https://chromiumembedded.github.io/java-cef/ | ⭐ 官方 |
| **JCEF GitHub** | https://github.com/chromiumembedded/java-cef | ⭐ 官方 |
| **CEF 父项目** | https://github.com/chromiumembedded/cef | ⭐ 官方 |

### JCEF 在 IntelliJ Platform 中的使用
| 资源 | URL | 类型 |
|------|-----|------|
| **JCEF in IntelliJ（官方）** | https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html | ⭐ 官方 |
| **IntelliJ JCEF 源码** | https://github.com/JetBrains/intellij-community/tree/master/platform/ui.jcef/jcef | ⭐ 官方 |
| **CefKeyboardHandler** | https://github.com/chromiumembedded/java-cef/blob/master/java/org/cef/handler/CefKeyboardHandler.java | ⭐ 官方 |
| **JBCefBrowserBuilder** | https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/src/JBCefBrowserBuilder.java | ⭐ 官方 |

### 关键文档页面
| 主题 | URL |
|------|-----|
| ToolWindow | https://plugins.jetbrains.com/docs/intellij/tool-window.html |
| Actions | https://plugins.jetbrains.com/docs/intellij/working-with-actions.html |
| Services | https://plugins.jetbrains.com/docs/intellij/plugin-services.html |
| 插件测试 | https://plugins.jetbrains.com/docs/intellij/testing.html |
| 插件签名 | https://plugins.jetbrains.com/docs/intellij/plugin-signing.html |
| 发布 | https://plugins.jetbrains.com/docs/intellij/publishing.html |

### 示例项目
| 项目 | URL |
|------|-----|
| **sample-intellij-idea-plugin** | https://github.com/ilkeratik/sample-intellij-idea-plugin |
| **intellij-ui-dataviz** | https://github.com/JetBrains/intellij-ui-dataviz |

### OpenCode 项目（本地源码）
| 资源 | 位置 |
|------|------|
| **OpenCode 源码** | `/Users/yutao/GolandProjects/opencode` |
| **核心引擎** | `packages/opencode/src/` |
| **Web 前端** | `packages/web/src/` |
| **IDE 集成** | `packages/opencode/src/ide/` |
| **Agent 配置** | `.opencode/agent/` |
| **命令定义** | `.opencode/command/` |

### OpenCode 官方资源
| 资源 | URL |
|------|-----|
| **官网** | https://opencode.ai |
| **官方文档** | https://opencode.ai/docs |
| **GitHub** | https://github.com/anomalyco/opencode |
| **Discord** | https://discord.gg/opencode |
