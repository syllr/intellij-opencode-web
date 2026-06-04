## ADDED Requirements

### Requirement: Per-Project SSE Connection

The plugin MUST maintain a separate SSE connection for each open IDE project, replacing the previous single-instance `OpenCodeSSEConsumer` model. The `OpenCodeServerManager` MUST use a `ConcurrentHashMap<Project, OpenCodeSSEConsumer>` keyed by Project.

#### Scenario: Multiple projects each get their own SSE connection

- **WHEN** 2 IDE projects are open simultaneously
- **THEN** the system MUST create 2 distinct `OpenCodeSSEConsumer` instances (one per project)
- **AND** each consumer MUST have its own SSE connection to the opencode server
- **AND** each consumer MUST have its own 30s watchdog thread

#### Scenario: Project switching does not stop other consumers

- **WHEN** the user switches focus from Project A to Project B
- **THEN** the system MUST NOT stop Project A's `OpenCodeSSEConsumer`
- **AND** both Project A and Project B consumers MUST continue to receive events independently

#### Scenario: computeIfAbsent ensures thread-safe consumer creation

- **WHEN** multiple threads simultaneously call `getOrCreateConsumer(sameProject)`
- **THEN** the system MUST create exactly one `OpenCodeSSEConsumer` for that project
- **AND** concurrent callers MUST receive the same consumer instance

#### Scenario: disposeForProject removes only the target consumer

- **WHEN** `disposeForProject(project)` is called for Project A
- **THEN** the system MUST stop and remove only Project A's consumer
- **AND** the system MUST NOT affect Project B's consumer

#### Scenario: gracefulShutdown stops all consumers

- **WHEN** `gracefulShutdown()` is called (e.g., IDE shutdown)
- **THEN** the system MUST stop all `OpenCodeSSEConsumer` instances in the map
- **AND** the system MUST clear the map after stopping all consumers

### Requirement: SSE Endpoint Switch to Per-Instance Path

The SSE consumer MUST connect to `/event?directory=<url-encoded-project-path>` instead of `/global/event`. The `directory` query parameter MUST be the URL-encoded absolute project path (not base64).

#### Scenario: SSE URL uses event endpoint

- **WHEN** the SSE consumer starts a connection
- **THEN** the system MUST construct the URL as `http://127.0.0.1:12396/event?directory=<url-encoded-path>`
- **AND** the system MUST NOT use `/global/event`

#### Scenario: directory query parameter is URL-encoded

- **WHEN** the system encodes the project base path for the `directory` query parameter
- **THEN** the system MUST use `URLEncoder.encode(path, "UTF-8")` or equivalent URL-encoding
- **AND** the system MUST NOT use base64 encoding

#### Scenario: Server-side filtering removes need for client-side comparison

- **WHEN** the SSE consumer receives an event from the per-instance endpoint
- **THEN** the server MUST have already filtered the event to match the requesting project's directory
- **AND** the client MUST NOT perform any `eventDir` vs `projectBasePath` comparison
- **AND** the client MUST NOT call `Paths.get(eventDir).toRealPath()` for filtering purposes

### Requirement: Notification Router Simplified

The `OpenCodeNotificationRouter` MUST be simplified since the per-project consumer model removes the need for path-based project lookup. The notification dispatch MUST use the `Project` instance directly from the consumer.

#### Scenario: projectRegistry is removed

- **WHEN** the new SSE consumer design is implemented
- **THEN** the system MUST remove the `projectRegistry: ConcurrentHashMap<String, Project>` field
- **AND** the system MUST remove the `register()` and `unregister()` methods
- **AND** the system MUST remove the `notify()` method's path-normalization logic

#### Scenario: notification dispatch uses consumer's project directly

- **WHEN** a notification is triggered by the SSE consumer
- **THEN** the system MUST pass the consumer's `project: Project` instance directly to `OpenCodeNotificationService.send()`
- **AND** the system MUST NOT look up the project by directory path

### Requirement: 30s Watchdog with Per-Consumer Thread

Each `OpenCodeSSEConsumer` MUST maintain its own 30s watchdog thread that forces a reconnect if no event is received within 30s. The watchdog MUST be implemented as a daemon thread named `SSE-Watchdog`.

#### Scenario: Watchdog forces reconnect after 30s idle

- **WHEN** no SSE event has been received for 30s (configurable via `SSE_IDLE_TIMEOUT_MS`)
- **THEN** the system MUST trigger a reconnect by closing the current `BackgroundEventSource` and starting a new connection
- **AND** the system MUST NOT silently drop the connection

#### Scenario: Watchdog thread is daemon

- **WHEN** the IDE shuts down
- **THEN** the system MUST NOT block IDE shutdown waiting for the watchdog thread
- **AND** the daemon thread MUST be interruptible via `Thread.interrupt()`

#### Scenario: Multiple consumers have independent watchdogs

- **WHEN** 2 projects are open
- **THEN** the system MUST run 2 separate `SSE-Watchdog` daemon threads
- **AND** each watchdog MUST independently monitor its own consumer

### Requirement: Stream Event Data Mode for SSE Reader

The SSE consumer MUST use LaunchDarkly's `MessageEvent.getDataReader()` for streaming event data consumption. The `EventSource.Builder` chain MUST include `.streamEventData(true)`. The consumer MUST consume the Reader within the `onMessage` handler to avoid race conditions with the stream thread.

#### Scenario: StreamEventData mode enabled in Builder

- **WHEN** the SSE consumer constructs the `EventSource.Builder` chain
- **THEN** the system MUST include `.streamEventData(true)` in the chain
- **AND** the system MUST NOT use the default buffered mode

#### Scenario: Reader consumed within onMessage handler

- **WHEN** the `onMessage` handler is invoked with a `MessageEvent`
- **THEN** the system MUST call `messageEvent.getDataReader()` to obtain the Reader
- **AND** the system MUST consume the Reader (e.g., via `use { }` block) before returning from the handler
- **AND** the system MUST NOT defer Reader consumption to another thread

#### Scenario: OkHttp Eventsource 4.3.0 dependency

- **WHEN** the project's `gradle/libs.versions.toml` is configured
- **THEN** the `okhttpEventsource` version MUST be `4.3.0`
- **AND** the system MUST NOT use any version below 4.1.0
