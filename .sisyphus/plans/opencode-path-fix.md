# 修复 opencode 路径查找问题

## TL;DR

> **问题**：macOS GUI 启动时无法找到通过 nvm 安装的 opencode
> **解决方案**：扩展路径搜索逻辑，支持 nvm 多版本管理

## Context

### 问题描述
用户换到新 Mac 后，插件启动报错：
```
Cannot run program "opencode" (in directory "/Users/yutao"): Exec failed, error: 2 (No such file or directory)
```

### 根本原因
- `findOpenCodePath()` 只检查 `/opt/homebrew/bin/opencode` 和 `/usr/local/bin/opencode`
- nvm 安装的 opencode 在 `/opt/homebrew/opt/nvm/versions/node/v24.12.0/bin/opencode`
- macOS GUI 应用不加载 shell 环境变量

### 用户需求
1. 默认添加常用 opencode 路径
2. 扫描 nvm 多版本目录，选择最高版本的 opencode

---

## Work Objectives

### Must Have
- [x] 扩展 `findOpenCodePath()` 支持更多默认路径
- [x] 添加 nvm 版本扫描逻辑，找到最高版本的 opencode
- [x] 保持向后兼容（已有的硬编码路径检查）

### Must NOT Have
- 不要修改其他无关功能
- 不要改变 opencode 启动参数

---

## Execution Strategy

### Wave 1
- [x] Task 1: 实现 findOpenCodePath() 逻辑
- [x] Task 2: **Oracle 根据逻辑编写 UT**

### Wave 2 (Task 1 完成后)
- [x] Task 3: Oracle 校验 UT 覆盖率

### Wave 3 (Task 2, 3 完成后)
- [x] Task 4: 运行 UT 验证 (环境无Java，跳过)

### 重构 (修复代码重复)
- [x] Task 5: 将 OpenCodePathFinder 移到 main 目录
- [x] Task 6: MyToolWindowFactory 调用 OpenCodePathFinder

---

## TODOs

- [x] 1. 实现 findOpenCodePath() 逻辑

  **What to do** (逻辑描述，不写具体代码):

  1. **收集候选路径**
     - 硬编码路径：`/opt/homebrew/bin/opencode`, `/usr/local/bin/opencode`, `/usr/bin/opencode`
     - NVM 路径：扫描 `/opt/homebrew/opt/nvm/versions/node/*/bin/opencode`
     - 其他多版本管理器路径（fnm, volta 等）

   2. **过滤存在的路径**
      - 检查每个候选路径是否存在
      - 如果都不存在，通知用户并 fast fail（不回退）

  3. **单路径优化**
     - 如果只有一个路径存在，直接返回该路径
     - 跳过版本比较，避免不必要的进程启动

   4. **多路径版本比较**
      - 对每个存在的路径执行 `<path> --version` 获取 opencode 版本
      - 使用语义化版本比较 (如 1.14.22 > 1.13.0)
      - 返回版本最高的路径

   5. **版本解析（单独函数，让 Oracle 写 UT）**
      - 解析 "x.y.z" 格式的版本号
      - 支持 major.minor.patch 三段比较
      - **本质是整数比较，不是字符串比较**
      - 单独函数 `parseOpenCodeVersion(version: String): ComparableVersion`

   6. **通知用户（fast fail）**
      - 如果没有找到任何 opencode 路径
      - 使用 IntelliJ Notification Balloon 显示友好错误提示
      - 不回退到 "opencode"，直接报错并终止启动流程
      - 建议用户提供安装指南链接

  **Must NOT do**:
  - 不要修改 getFullEnvironment() 方法
  - 不要修改 opencode 启动参数
  - 不要写具体实现代码（逻辑由实现者自行决定）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Reason**: 单一方法修改，逻辑清晰

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: Task 2 (UT 依赖实现)
  - **Blocked By**: None

  **References**:
  - `src/main/kotlin/.../MyToolWindowFactory.kt:729-733` - findOpenCodePath() 当前实现

  **Acceptance Criteria**:
  - [ ] 能找到 nvm 安装的 opencode (`/opt/homebrew/opt/nvm/versions/node/*/bin/opencode`)
  - [ ] 只有一个路径时直接返回，不调用 --version
  - [ ] 多个路径时选择版本最高的
  - [ ] **没有路径时通知用户并 fast fail（不回退）**
  - [ ] **版本解析是整数比较，不是字符串比较**

  **QA Scenarios**:

  ```
  Scenario: 验证实现能找到 nvm 安装的 opencode
    Tool: Bash
    Steps:
      1. 读取 MyToolWindowFactory.kt 新的 findOpenCodePath() 实现
      2. 验证逻辑包含 NVM 路径扫描
    Expected Result: 实现包含 NVM 路径扫描逻辑
    Evidence: .sisyphus/evidence/task-1-logic-review.md
  ```

- [x] 2. Oracle 根据逻辑编写 UT

  **What to do**:

  请 Oracle 根据 Task 1 的逻辑描述，编写完整的单元测试：

  1. **testSinglePath_ReturnsDirectly**
     - 逻辑：只有一个路径存在时直接返回，不调用 --version
     - 验证：Mock 文件系统，该路径存在，返回该路径

  2. **testMultiplePaths_SelectsHighestVersion**
     - 逻辑：多个路径时执行 --version，选择版本最高的
     - 验证：Mock 3 个路径，版本分别是 1.13.0, 1.14.22, 1.12.0
     - 期望：返回 1.14.22 对应的路径

   3. **testNoPath_NotifiesAndFails**
      - 逻辑：没有路径存在时通知用户并 fast fail
      - 验证：Mock 所有路径都不存在，验证通知被触发，不回退

   4. **testParseOpenCodeVersion**
      - 逻辑：解析 "x.y.z" 版本号进行三段比较
      - **注意：必须是整数比较，不是字符串比较**
      - 验证：1.14.22 > 1.14.21, 1.15.0 > 1.14.22, 2.0.0 > 1.99.99
      - 验证字符串陷阱："1.14.22" 不应该小于 "1.9.1"（字符串比较会错误）

   5. **testVersionComparisonEdgeCases**
      - 边界情况：相同版本、版本号缺失部分、预发布版本等
      - 验证字符串陷阱："1.14.22" > "1.9.1"（容易出错！）

  **Must NOT do**:
  - 不要写具体实现代码
  - 只写测试用例代码

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Reason**: 需要理解逻辑并设计完整的测试覆盖

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: None
  - **Blocked By**: Task 1 (依赖逻辑描述)

  **References**:
  - `src/test/kotlin/com/github/xausky/opencodewebui/actions/FormatAsPromptTest.kt` - 测试风格参考
  - `src/main/kotlin/.../MyToolWindowFactory.kt:729-733` - findOpenCodePath() 当前实现

  **Acceptance Criteria**:
  - [ ] Oracle 编写了完整的 UT 代码
  - [ ] 测试覆盖所有逻辑分支
  - [ ] 边界情况有对应测试

  **QA Scenarios**:

  ```
  Scenario: Oracle 编写 UT
    Tool: Oracle
    Steps:
      1. 阅读 Task 1 的逻辑描述
      2. 根据逻辑设计测试用例
      3. 编写完整的 FindOpenCodePathTest.kt
    Expected Result: UT 代码完整，覆盖所有分支
    Evidence: .sisyphus/evidence/task-2-ut-code.md
  ```

---

## Commit Strategy

- **Commit**: YES
- **Message**: `fix: support nvm multi-version opencode path detection`
- **Files**: `src/main/kotlin/.../MyToolWindowFactory.kt`
- **Pre-commit**: `./gradlew buildPlugin` 验证编译通过