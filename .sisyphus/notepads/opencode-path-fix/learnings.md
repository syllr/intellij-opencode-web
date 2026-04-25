# opencode-path-fix 学习记录

## 任务概述
修复 macOS GUI 启动时找不到 nvm 安装的 opencode 的问题

## 遇到的问题

### 1. 代码重复问题
**问题**：Oracle 写的测试辅助类 `OpenCodePathFinder` 与 `MyToolWindowFactory` 各自实现了相同的 `parseOpenCodeVersion` 和 `ComparableVersion` 逻辑。

**解决方案**：
1. 将 `OpenCodePathFinder` 从 test 目录移到 main 目录
2. 在 `OpenCodePathFinder` 中添加 `getCandidatePaths()` 方法，包含 NVM 扫描逻辑
3. 让 `MyToolWindowFactory.findOpenCodePath()` 调用 `OpenCodePathFinder` 的方法

## 最终代码结构

```
src/main/kotlin/.../toolWindow/
├── MyToolWindowFactory.kt      # 调用 OpenCodePathFinder
├── OpenCodePathFinder.kt       # 共享的路径查找逻辑
└── OpenCodeApi.kt

src/test/kotlin/.../toolWindow/
└── FindOpenCodePathTest.kt    # 测试 OpenCodePathFinder
```

## 关键逻辑

1. **收集候选路径** - 硬编码路径 + NVM 扫描
2. **单路径优化** - 只有一个路径时直接返回
3. **多路径版本比较** - 执行 `--version`，选择版本最高的
4. **Fast fail** - 找不到时通知用户并抛出异常

## 版本比较陷阱

**必须用整数比较，不是字符串比较**：
- `"1.14.22" > "1.9.1"` 字符串比较会失败（因为 "1" < "9"）
- 需要解析为 `ComparableVersion(1, 14, 22)` 才能正确比较

## 环境限制

- 当前环境没有 Java Runtime，无法运行 `./gradlew test`
- 必须在用户的机器上运行测试验证