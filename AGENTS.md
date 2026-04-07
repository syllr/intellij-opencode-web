# AGENTS.md

## 项目概览
- **类型**: JetBrains IntelliJ Platform 插件
- **语言**: Kotlin
- **构建工具**: Gradle (Kotlin DSL)
- **JDK**: 21
- **平台版本**: 2025.2.5 (sinceBuild 252)

## 关键 Gradle 命令
```bash
./gradlew buildPlugin          # 构建插件
./gradlew check                 # 运行测试和检查
./gradlew verifyPlugin          # 验证插件结构
./gradlew runIde                # 运行带插件的 IDE
./gradlew publishPlugin         # 发布到 JetBrains Marketplace
./gradlew patchChangelog        # 更新变更日志
```

## CI/CD 工作流
- **build.yml**: 构建、测试、检查、验证、创建草稿发布（push/main 和 PR 时触发）
- **release.yml**: 发布插件到 Marketplace（发布时触发）
- **run-ui-tests.yml**: UI 测试

## 代码质量
- 已配置 Qodana 检查
- Kover 代码覆盖率（上传到 Codecov）
- IntelliJ Platform 兼容性插件验证器

## 项目结构
```
src/main/kotlin/com/github/xausky/opencodewebui/
├── toolWindow/          # 侧边栏工具窗口，带 JCEF 浏览器
├── services/            # 项目服务
├── listeners/           # 应用/项目监听器
├── startup/             # 启动活动
└── MyBundle.kt          # 国际化 Bundle
```

## 关键实现说明
- 使用 JBCefBrowser 进行 Web UI 集成
- 管理 `opencode serve` 进程（127.0.0.1:4096）
- 自动启动服务器，每 30 秒进行健康检查
- 通过 MyApplicationDisposable 在 IDE 关闭时清理资源
- 项目路径以 Base64 编码在 URL 中

## 重要配置
- **pluginVersion**: 在 gradle.properties 中管理
- **README.md**: 从 `<!-- Plugin description -->` 注释中提取插件描述
- **CHANGELOG.md**: 由 Gradle Changelog Plugin 管理

## 依赖
- 版本目录: `gradle/libs.versions.toml`
- IntelliJ Platform Plugin: 2.11.0
- Kotlin: 2.3.0
