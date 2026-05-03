# Thread Safety Fixes (P0/P1/P2)

## TL;DR

三条线程安全+架构修复，每次修复后 Oracle review 通过后才能继续下一个。

## TODOs

### P0 — 递归重试加深度限制

- [x] 1. 在 `JcefJsInjector.appendTextToEditor()` 中加深度计数器，每次 `invokeLater` 二次校验失败重入队时深度+1，超限（如 3 次）后放弃
- [x] → `@oracle` review
- [x] → Oracle 批准

### P1 — invokeLater 加 ModalityState

- [x] 2. `JcefJsInjector.kt` 中 `invokeLater { ... }` 改为 `invokeLater(ModalityState.any()) { ... }`
- [x] → `@oracle` review
- [x] → Oracle 批准

### P2 — myToolWindowInstance 改为 Map<Project, MyToolWindow>

- [x] 3. 将 `MyToolWindowFactory.myToolWindowInstance` 从单一 `var` 改为 `ConcurrentHashMap<Project, MyToolWindow>`
- [x] → `@oracle` review
- [x] → Oracle 批准
