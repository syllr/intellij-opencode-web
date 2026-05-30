# Plan: IDE 原生通知功能 (ide-native-notifications)

## TL;DR

用 IntelliJ 原生通知替代 `@mohak34/opencode-notifier`。11 种通知事件通过 SSE 获取，使用双模式（BALLOON + SystemNotifications）发送通知，macOS 上点击可跳转到对应 IDE。提供 Setting UI 支持按事件类型配置。

> See [proposal.md](openspec/changes/ide-native-notifications/proposal.md)

## Context

当前插件只处理 SSE 中的文件事件，通知依赖服务端插件发送系统级通知，导致 macOS 上无法正确跳转。SSE 通道实际包含 permission.asked、session.status、session.error、session.next.tool.called 等所有通知事件。配置通过 PropertiesComponent 持久化。

> See [proposal.md](openspec/changes/ide-native-notifications/proposal.md)

## Work Objectives

1. **notification-events**: SSE 通知事件监听与解析
   - SyncEvent V2 解析（去 version 后缀）
   - 11 种事件类型映射
   - eventID 去重（LinkedHashMap LRU）
   - subagent 本地追踪
   > See [spec.md](openspec/changes/ide-native-notifications/specs/notification-events/spec.md)

2. **notification-service**: 双模式通知发送
   - Router 多项目路由（canonicalPath 规范化）
   - BALLOON（前台）+ SystemNotifications（后台）
   - 工具窗口聚焦抑制 + 多显示器焦点检测
   - 差异化点击
   > See [spec.md](openspec/changes/ide-native-notifications/specs/notification-service/spec.md)

3. **notification-settings**: 配置 UI
   - PropertiesComponent 持久化
   - 11 种事件开关 + 消息模板编辑
   > See [spec.md](openspec/changes/ide-native-notifications/specs/notification-settings/spec.md)

## Verification Strategy

- 每个 spec requirement 有 WHEN/THEN scenario
- 前置验证 Wave 0：curl 实测 SSE + HTTP API
- 通知功能通过 runIde 手动验证
- 构建通过 `./gradlew check`
> See [specs](openspec/changes/ide-native-notifications/specs/)

## Execution Strategy

关键决策（详见 design.md）：
- SSE 连接复用，扩展 onMessage()
- Router 多项目路由（ProjectManagerListener + createToolWindowContent 双保险注册）
- 双模式通知：前台 BALLOON + 后台 SystemNotifications
- 工具窗口聚焦抑制 + 多显示器焦点检测
- PropertiesComponent 配置持久化
- File.canonicalPath 路径规范化
- LinkedHashMap LRU 去重
> See [design.md](openspec/changes/ide-native-notifications/design.md)
> See [research/](openspec/changes/ide-native-notifications/research/)

## Tasks

### Wave 0: 前置验证

- [ ] 0.1 SSE + HTTP API 实测
  验证 session.next.tool.called 可达性 + GET /session/:id 格式
- [ ] 0.2 扩展 OpenCodeApi
  新增 getSession(sessionID) 方法

### Wave 1: SSE 事件监听扩展

- [ ] 1.1 SSEEventParser SyncEvent 解析 + 去重
- [ ] 1.2 OpenCodeSSEConsumer 通知分发 + subagent 追踪

### Wave 2: 通知核心服务

- [ ] 2.1 OpenCodeNotificationRouter（双保险注册）
- [ ] 2.2 OpenCodeNotificationService（双模式通知）
- [ ] 2.3 OpenCodeConfig（PropertiesComponent）

### Wave 3: Settings UI

- [ ] 3.1 OpenCodeConfigurable
- [ ] 3.2 plugin.xml 更新

### Wave 4: 集成与验证

- [ ] 4.1 全链路集成测试
- [ ] 4.2 ./gradlew check

## Final Verification Wave

- [ ] F1. **Plan Compliance Audit** — oracle
  检查所有 task 是否覆盖 spec。Output: "Must Have [N/N] | VERDICT"
- [ ] F2. **Code Quality** — unspecified-high
  Build + lint。Output: "Build | Lint | VERDICT"
- [ ] F3. **Manual QA** — unspecified-high
  执行 QA scenarios。Output: "Scenarios [N/N] | VERDICT"
- [ ] F4. **Scope Fidelity** — deep
  实现 diff 对比 specs。Output: "Tasks [N/N] | VERDICT"

## Commit Strategy

- 0.2 → `OpenCodeApi.kt` → `feat(api): add getSession method`
- 1.1 → `SSEEventParser.kt` → `feat(sse): add SyncEvent parsing`
- 1.2 → `OpenCodeSSEConsumer.kt` → `feat(notifications): dispatch SSE events`
- 2.1 → `OpenCodeNotificationRouter.kt` → `feat(notifications): add event router`
- 2.2 → `OpenCodeNotificationService.kt` → `feat(notifications): add notification service`
- 2.3 → `OpenCodeConfig.kt` → `feat(settings): add PropertiesComponent config`
- 3.1 → `OpenCodeConfigurable.kt` → `feat(settings): add settings UI`
- 3.2 → `plugin.xml` → `chore: register configurable`

## Success Criteria

- [ ] 全部 acceptance criteria 通过
- [ ] 全部 QA scenarios 通过
- [ ] Scope clean
- [ ] `./gradlew check` 通过
