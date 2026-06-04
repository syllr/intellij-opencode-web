## ADDED Requirements

### Requirement: 1s Session-Dimension Notification Deduplication

The `OpenCodeNotificationService` MUST apply a 1-second deduplication window to all notification dispatches. The deduplication key MUST be the tuple `(sessionID, eventType)`. If the same `(sessionID, eventType)` tuple was dispatched within the last 1000ms, the system MUST suppress the second dispatch.

#### Scenario: Same session same event within 1s is suppressed

- **WHEN** a notification with key `(sessionABC, complete)` is dispatched at time T
- **AND** another notification with key `(sessionABC, complete)` is dispatched at time T+500ms
- **THEN** the system MUST dispatch the first notification normally
- **AND** the system MUST suppress the second notification

#### Scenario: Different event types within 1s are both dispatched

- **WHEN** a notification with key `(sessionABC, permission)` is dispatched at time T
- **AND** a notification with key `(sessionABC, question)` is dispatched at time T+500ms
- **THEN** the system MUST dispatch both notifications normally
- **AND** the system MUST NOT suppress the question notification

#### Scenario: Different sessions within 1s are both dispatched

- **WHEN** a notification with key `(sessionABC, complete)` is dispatched at time T
- **AND** a notification with key `(sessionXYZ, complete)` is dispatched at time T+500ms
- **THEN** the system MUST dispatch both notifications normally
- **AND** the system MUST NOT suppress the second notification

#### Scenario: Same key after 1s window is dispatched

- **WHEN** a notification with key `(sessionABC, complete)` is dispatched at time T
- **AND** another notification with key `(sessionABC, complete)` is dispatched at time T+1500ms
- **THEN** the system MUST dispatch both notifications normally
- **AND** the system MUST NOT suppress the second notification

### Requirement: Deduplication LRU Map for Memory Bounds

The deduplication state MUST be maintained in a thread-safe LRU Map with a maximum capacity of 1000 entries. When the map exceeds 1000 entries, the least-recently-used entry MUST be evicted.

#### Scenario: LRU map evicts oldest entry at 1000+1

- **WHEN** the deduplication map contains 1000 entries
- **AND** a new entry is inserted
- **THEN** the system MUST evict the least-recently-used entry
- **AND** the system MUST NOT exceed 1000 entries in the map

#### Scenario: LRU map is thread-safe

- **WHEN** multiple threads simultaneously call `send()` with different session/event pairs
- **THEN** the system MUST safely handle concurrent reads and writes
- **AND** the system MUST NOT lose or duplicate entries due to race conditions

### Requirement: Three Real Notification Types

The system MUST only dispatch notifications for the 3 real notification types: `permission`, `complete`, and `question`. All other notification types from previous versions (`error`, `subagent_complete`, `user_cancelled`, `plan_exit`, `user_message`, `session_started`, `client_connected`, `interrupted`) MUST NOT be dispatched.

#### Scenario: Only permission/complete/question trigger notifications

- **WHEN** any of the 4 notification-trigger events arrive (`session.idle`, `session.status` with type=idle, `permission.asked`, `question.asked`)
- **THEN** the system MUST dispatch notifications with eventType `complete` (for `session.idle` and `session.status(idle)`), `permission` (for `permission.asked`), or `question` (for `question.asked`)

#### Scenario: Subagent sessions do not trigger notifications

- **WHEN** a `session.idle` or `session.status(idle)` event arrives for a sessionID that exists in `subagentSessionIds`
- **THEN** the system MUST NOT dispatch any notification
- **AND** the system MUST return from `handleSessionIdle` immediately

### Requirement: Replace 2s idleLastFired with 1s Service-Level Dedup

The previous `OpenCodeSSEConsumer.idleLastFired` 2-second window (which only covered `handleSessionIdle`) MUST be removed and replaced by the 1s service-level deduplication in `OpenCodeNotificationService.send()`. The 2s `idleLastFired` state MUST NOT be maintained.

#### Scenario: idleLastFired is removed from OpenCodeSSEConsumer

- **WHEN** the new design is implemented
- **THEN** the system MUST NOT contain a `private val idleLastFired` field in `OpenCodeSSEConsumer`
- **AND** the system MUST NOT contain a 2s `idleDedupWindowMs` constant
- **AND** the system MUST NOT perform the 2s time-window check in `handleSessionIdle`

#### Scenario: handleSessionIdle is simplified

- **WHEN** `handleSessionIdle` is called
- **THEN** the system MUST first check `if (sessionID in subagentSessionIds) return`
- **AND** the system MUST NOT check `sessionIdleFired`
- **AND** the system MUST dispatch the `complete` notification directly without a 2s window check

### Requirement: NotificationService Removes Event-Type Specific Configuration

The `OpenCodeNotificationService` MUST remove all `isEventEnabled` and `getMessageTemplate` calls. The notification format MUST be hard-coded via `when (eventType)` branches in `formatMessage`.

#### Scenario: isEventEnabled is removed

- **WHEN** `OpenCodeNotificationService.send()` is called
- **THEN** the system MUST NOT call `OpenCodeConfig.isEventEnabled(eventType)`
- **AND** the system MUST NOT skip notifications based on per-event configuration

#### Scenario: getMessageTemplate is replaced with hard-coded when

- **WHEN** `OpenCodeNotificationService.formatMessage` constructs the message body
- **THEN** the system MUST use a `when (eventType)` branch to select the template
- **AND** the system MUST hard-code the 3 templates: "权限申请: {sessionTitle}" for permission, "回答完成: {sessionTitle}" for complete, "询问用户: {sessionTitle}" for question

#### Scenario: subagent_complete minDuration check is removed

- **WHEN** `OpenCodeNotificationService.send()` checks minDuration
- **THEN** the system MUST only check minDuration for `eventType == "complete"`
- **AND** the system MUST NOT include `subagent_complete` in the minDuration check (subagent_complete is fully removed)

### Requirement: Settings UI Fully Removed

The IDE Settings panel for OpenCode MUST be completely removed. The `OpenCodeConfigurable` class MUST be deleted and the `applicationConfigurable` extension in `plugin.xml` MUST be unregistered.

#### Scenario: OpenCodeConfigurable class is deleted

- **WHEN** the implementation is complete
- **THEN** the system MUST NOT contain a file at `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/settings/OpenCodeConfigurable.kt`
- **AND** the `settings/` package directory MUST be empty (or removed if no other classes remain)

#### Scenario: applicationConfigurable extension is removed

- **WHEN** `plugin.xml` is checked
- **THEN** the `<applicationConfigurable parentId="tools" instance="...OpenCodeConfigurable" id="...opencode.config" displayName="OpenCode"/>` element MUST NOT exist
- **AND** the system MUST NOT register any other configurable for the OpenCode plugin

#### Scenario: Four general-purpose var getters remain functional

- **WHEN** `OpenCodeConfig.notificationEnabled`, `showProjectName`, `showSessionTitle`, or `minDuration` is accessed
- **THEN** the system MUST return the value stored in `PropertiesComponent` (if any)
- **AND** the system MUST return the code default value (`true` / `true` / `true` / `0`) if no stored value exists
- **AND** the system MUST persist any setter calls to `PropertiesComponent`

#### Scenario: No UI exposes the four getters

- **WHEN** the user opens IDE Settings
- **THEN** the system MUST NOT display any OpenCode configuration panel under Tools
- **AND** the user MUST NOT be able to modify `notificationEnabled`, `showProjectName`, `showSessionTitle`, or `minDuration` via UI

### Requirement: OpenCodeConfig Simplified

The `OpenCodeConfig` object MUST remove the 4 event-type-specific functions (`isEventEnabled`, `setEventEnabled`, `getMessageTemplate`, `setMessageTemplate`) and the 3 top-level definitions (`ALL_EVENT_TYPES`, `defaultEvents()`, `defaultMessages()`). Only the 4 general-purpose var getters MUST remain.

#### Scenario: Event-type specific functions are removed

- **WHEN** `OpenCodeConfig` is checked
- **THEN** the system MUST NOT contain `isEventEnabled`, `setEventEnabled`, `getMessageTemplate`, or `setMessageTemplate` methods
- **AND** the system MUST NOT contain `ALL_EVENT_TYPES`, `defaultEvents()`, or `defaultMessages()` definitions

#### Scenario: 4 general-purpose var getters remain

- **WHEN** `OpenCodeConfig` is checked
- **THEN** the system MUST contain `notificationEnabled: Boolean`, `showProjectName: Boolean`, `showSessionTitle: Boolean`, and `minDuration: Int` var getters
- **AND** all 4 MUST persist to `PropertiesComponent` via `props().getBoolean()` / `getInt()` / `setValue()`
