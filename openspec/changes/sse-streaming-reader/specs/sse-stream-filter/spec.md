## ADDED Requirements

### Requirement: SSE Event Type Whitelist Filtering

The SSE event consumer MUST apply a type-based whitelist to filter incoming events. Only events whose `type` field is in the whitelist SHALL be fully parsed via Gson; all other events MUST be skipped at the Reader stage to avoid unnecessary object allocation and JSON parsing overhead.

#### Scenario: Whitelisted event is fully parsed

- **WHEN** the SSE consumer receives an event whose `type` field is in the whitelist
- **THEN** the system MUST fully read the Reader, build a Map via Gson, and dispatch the event to the corresponding `when (eventType)` branch
- **AND** the system MUST NOT close the Reader early

#### Scenario: Non-whitelisted event is skipped at Reader stage

- **WHEN** the SSE consumer receives an event whose `type` field is not in the whitelist
- **THEN** the system MUST close the Reader without building a Gson Map
- **AND** the system MUST NOT dispatch the event to any handler

#### Scenario: Whitelist contains exactly 9 event types

- **WHEN** the whitelist is initialized
- **THEN** the whitelist MUST contain exactly the following 9 wire-level eventType values: `session.idle`, `session.status`, `permission.asked`, `question.asked`, `session.created`, `message.part.updated`, `file.edited`, `file.watcher.updated`, `session.diff`

#### Scenario: High-frequency `message.part.delta` is skipped

- **WHEN** the SSE consumer receives a `message.part.delta` event at 100+ Hz
- **THEN** the system MUST close the Reader without Gson parsing
- **AND** the system MUST NOT trigger any notification or file-refresh handler

#### Scenario: 25+ noise events are all skipped

- **WHEN** the SSE consumer receives any of `server.heartbeat`, `server.instance.disposed`, `session.next.*` (25+ sub-events), `project.updated`, `mcp.*`, `installation.*`, `auth.*`, `plugin.*`, `model.updated`, `lsp.*`, `message.part.removed`, `message.removed`
- **THEN** the system MUST close the Reader without Gson parsing for each of these events

### Requirement: Stream-based JSON Reader

The SSE consumer MUST use LaunchDarkly's `MessageEvent.getDataReader()` to consume event data, replacing the previous `messageEvent.data` (String) approach. The Builder MUST enable `streamEventData(true)`.

#### Scenario: StreamEventData mode is enabled

- **WHEN** the `EventSource.Builder` chain is constructed
- **THEN** the chain MUST include `.streamEventData(true)`

#### Scenario: Reader is consumed within onMessage handler

- **WHEN** the SSE consumer receives a `MessageEvent`
- **THEN** the system MUST read the data via `messageEvent.getDataReader()` inside the `onMessage` handler
- **AND** the system MUST consume the Reader before the handler returns (using `use { }` block or explicit close)
- **AND** the system MUST NOT defer Reader consumption to a later thread

#### Scenario: BackgroundEventSource async dispatch safety

- **WHEN** `BackgroundEventSource` dispatches the `MessageEvent` to the `onMessage` handler on the events executor thread
- **THEN** the Reader MUST still be valid and readable during the handler call
- **AND** if streaming mode is enabled, the stream thread MUST NOT advance past the current event before the handler consumes the Reader

### Requirement: Wire Format Without Outer Wrapper

The SSE parser MUST adapt to the `/event?directory=...` wire format, which emits events as flat objects `{id, type, properties}` without an outer `{directory, project, workspace, payload}` wrapper. The legacy `{payload: {type: "sync", syncEvent: {type, data}}}` SyncEvent wrapping MUST NOT be supported.

#### Scenario: Payload field is the root

- **WHEN** the SSE parser receives an event from the `/event?directory=...` endpoint
- **THEN** the system MUST treat `id`, `type`, and `properties` as top-level fields of the event
- **AND** the system MUST NOT extract a `directory` field from the event itself (server already routes by directory)

#### Scenario: No SyncEvent wrapper parsing

- **WHEN** the SSE parser receives any event
- **THEN** the system MUST NOT check for `payloadType == "sync"` or extract `syncEvent.type` / `syncEvent.data`
- **AND** the system MUST NOT preserve the `SYNC_EVENT_TYPE_VERSION_REGEX` version-suffix stripping

#### Scenario: extractSessionID handles 3 levels of fallback

- **WHEN** the system extracts a sessionID from a parsed event
- **THEN** the system MUST check, in order: `properties.sessionID`, `properties.info.sessionID`, `properties.info.id`
- **AND** the system MUST NOT rely on the legacy `parsedMap["payload"]` nested path
- **AND** the system MUST NOT check `syncEvent.aggregateID` (SyncEvent parsing is removed in this design)

### Requirement: Whitelist Type-Specific Handlers

The `OpenCodeSSEConsumer` MUST handle the 9 whitelisted event types with dedicated logic, dispatching notifications for the 4 notification types and triggering file-refresh / subagent-tracking logic for the 5 business types.

#### Scenario: 4 notification types trigger notifications

- **WHEN** a whitelisted notification event arrives (`session.idle`, `session.status` with type=idle, `permission.asked`, `question.asked`)
- **THEN** the system MUST dispatch the corresponding notification (`complete`, `complete`, `permission`, `question`) to `OpenCodeNotificationService.send()`

#### Scenario: session.status with non-idle status is parsed but no notification dispatched

- **WHEN** a `session.status` event arrives with `status.type` not equal to `idle` (e.g., `busy`, `retry`)
- **THEN** the system MUST fully parse the event (because `session.status` is in the whitelist)
- **AND** the system MUST NOT dispatch any notification (only `status.type == "idle"` triggers a notification)

#### Scenario: 5 business types trigger non-notification handlers

- **WHEN** a whitelisted business event arrives (`session.created`, `message.part.updated`, `file.edited`, `file.watcher.updated`, `session.diff`)
- **THEN** the system MUST route to the corresponding non-notification handler: `subagentSessionIds.add(sid)` for `session.created`; `BashCommandHandler.handleBashEvent()` for `message.part.updated`; `FullRefreshCoordinator.request()` for `file.edited` / `file.watcher.updated` / `session.diff`
- **AND** the system MUST NOT trigger any notification

#### Scenario: 4 legacy notification cases are removed

- **WHEN** the system receives a `server.connected`, `session.error`, `session.next.tool.called`, or `message.updated` event
- **THEN** the system MUST NOT dispatch any notification (these cases are removed)

#### Scenario: session.created dispatchNotification for parent session is removed

- **WHEN** a `session.created` event with no `parentID` arrives (top-level session)
- **THEN** the system MUST NOT dispatch any `session_started` notification
- **AND** the system MUST only update internal `subagentSessionIds` when `parentID` is present

### Requirement: Bash and Notification Service Adapted to New Wire Format

The `BashCommandHandler` and `OpenCodeNotificationService` MUST remove all `parsedMap["payload"]` nested access. They MUST access `parsedMap["properties"]` directly.

#### Scenario: BashCommandHandler uses properties directly

- **WHEN** `BashCommandHandler.handleBashEvent` receives a `message.part.updated` event
- **THEN** the system MUST access `parsedMap["properties"]` to get the part
- **AND** the system MUST NOT access `parsedMap["payload"]["properties"]`

#### Scenario: OpenCodeNotificationService uses properties directly

- **WHEN** `OpenCodeNotificationService` extracts sessionID or any field from a parsed event
- **THEN** the system MUST access `parsedMap["properties"]` directly
- **AND** the system MUST NOT access `parsedMap["payload"]["properties"]`
