# Tool Window Reactive Server State

Tool window 与 opencode server 的状态同步完全由 SSE 4 个回调(onOpen / onMessage / onError / onClosed)驱动,且失联/重建两个方向都有回调。无独立的 5s 轮询线程。

## ADDED Requirements

### Requirement: stop() synchronously invokes onConnectionLost when previously connected

When the SSE consumer is intentionally stopped while `connected == true`, the system MUST capture `wasConnected` first and then synchronously invoke `onConnectionLost()` inside `stop()` (the invocation queues UI notification via `invokeLater` and returns immediately — whether `connected` is set to `false` before or after the invocation does not affect correctness, because the captured `wasConnected` is the sole gate). This ensures graceful shutdown paths immediately notify the tool window to switch to the empty-state UI, bypassing the 5s × 3 = 15s HealthMonitor debounce slow channel. The system MUST NOT rely on LaunchDarkly's `onError` callback ordering (which reads `wasConnected = connected = false` due to the `stop()`-then-`close()` order) for this notification.

#### Scenario: stop() invokes onConnectionLost when previously connected

- **WHEN** `OpenCodeSSEConsumer.stop()` is called while `connected == true`
- **THEN** the system captures `wasConnected = true`, sets `connected = false`, and synchronously invokes `onConnectionLost()` (which wraps `MyToolWindow.showServerNotRunning()` via `invokeLater`)

#### Scenario: stop() does not invoke onConnectionLost when never connected

- **WHEN** `OpenCodeSSEConsumer.stop()` is called while `connected == false`
- **THEN** the system does NOT invoke `onConnectionLost()` (no callback, no UI switch — important for idempotency of re-entrant `disposeForProject` path)

#### Scenario: stop() does not invoke onConnectionLost twice on re-entrant call

- **WHEN** `OpenCodeSSEConsumer.stop()` is called twice in succession (e.g., via `disposeForProject → consumer.stop()` chain triggered by the first `onConnectionLost`)
- **THEN** the second call's `wasConnected` is `false` (already cleared by the first call), so `onConnectionLost()` is NOT invoked a second time — preventing recursive UI switch storms

### Requirement: reconnect() synchronously invokes onConnectionLost to fix SSE_WATCHDOG deadlock

When the SSE watchdog (30s idle) triggers `reconnect()` while the consumer is still in `connected == true` state, the system MUST synchronously invoke `onConnectionLost()` before `startSseConnection()` returns. This fixes the deadlock where the new consumer's `onError` would silently bypass UI notification due to the `wasConnected = connected = false` gate. The system MUST NOT depend on the new consumer's `onError` to deliver the disconnect signal in this path.

#### Scenario: reconnect() invokes onConnectionLost when currently connected

- **WHEN** `OpenCodeSSEConsumer.reconnect()` is called while `connected == true` (SSE_WATCHDOG 30s idle timeout)
- **THEN** the system invokes `onConnectionLost()` synchronously, before `startSseConnection()` returns

#### Scenario: reconnect() zombie new EventSource is recycled by async stop()

- **WHEN** `reconnect()` invokes `onConnectionLost()` and the tool window asynchronously calls `showServerNotRunning → disposeForProject → consumer.stop()` (second stop call)
- **THEN** the new `BackgroundEventSource` created by `startSseConnection()` is closed by the second `stop()` call's `eventSourceRef.getAndSet(null)?.close()` (zombie recycling, functionally correct)

#### Scenario: reconnect() does not invoke onConnectionLost when not connected

- **WHEN** `OpenCodeSSEConsumer.reconnect()` is called while `connected == false`
- **THEN** the system does NOT invoke `onConnectionLost()`

### Requirement: onOpen() invokes onConnectionEstablished with 1.5s debounce

The system MUST invoke `onConnectionEstablished()` from `OpenCodeSSEConsumer.onOpen()` to notify the tool window to reload the web UI, debounced by 1.5s since the previous `onOpen()` to prevent flicker on transient reconnects. The 1.5s threshold is calibrated against LaunchDarkly's `alwaysContinue` first retry (≈ 1s + jitter). The `MyToolWindow` listener MUST invoke `loadProjectPage()` (with `hasLoaded` guard) when `onConnectionEstablished()` fires.

#### Scenario: onOpen() invokes onConnectionEstablished on initial connect

- **WHEN** `onOpen()` fires for the first time after `startSseConnection()` (i.e., `lastEstablishedAt` is 0L or > 1.5s in the past)
- **THEN** the system updates `lastEstablishedAt = now` and synchronously invokes `onConnectionEstablished()`

#### Scenario: onOpen() skips onConnectionEstablished within 1.5s debounce window

- **WHEN** `onOpen()` fires within 1.5s of the previous `onOpen()` (transient reconnect within LaunchDarkly's first retry window)
- **THEN** the system does NOT invoke `onConnectionEstablished()` and does NOT update `lastEstablishedAt` — preventing UI flicker on short network glitches

#### Scenario: onConnectionEstablished triggers loadProjectPage via invokeLater

- **WHEN** `onConnectionEstablished()` fires
- **THEN** the registered `MyToolWindow` lambda calls `loadProjectPage()` via `ApplicationManager.invokeLater` on the EDT, restoring the web UI

#### Scenario: loadProjectPage hasLoaded guard prevents duplicate loads

- **WHEN** `loadProjectPage()` is called multiple times in succession (e.g., transition period when both `HealthMonitor.onRecovered` and `onConnectionEstablished` can fire)
- **THEN** only the first call executes the actual `createMainTab` / `hideStartButton`; subsequent calls return early at the `hasLoaded` guard

#### Scenario: loadProjectPage guards against disposed project

- **WHEN** `loadProjectPage()` is called after the project is disposed (in-flight `onConnectionEstablished` callback landing in a disposed tool window during multi-project `gracefulShutdown`)
- **THEN** the system returns early at the `if (project.isDisposed) return` guard without throwing

### Requirement: No independent health-polling thread

The system MUST NOT maintain an independent health-polling thread (the legacy `HealthMonitor` class). All server-state transitions MUST be observed via SSE library callbacks (onOpen / onError / onClosed) plus the `SSE_WATCHDOG` 30s idle reconnect mechanism. The `OpenCodeSSEConsumer` lifecycle (`stop()` / `reconnect()` / `onError()`) is the single source of truth for server state.

#### Scenario: HealthMonitor class and HEALTH*CHECK*\* constants are removed

- **WHEN** the change is fully deployed (all 4 parts)
- **THEN** `src/main/kotlin/com/shenyuanlaolarou/opencodewebui/toolWindow/HealthMonitor.kt` is deleted, and `OpenCodeConstants.kt` no longer contains `HEALTH_CHECK_INTERVAL_MS` / `HEALTH_CHECK_START_DELAY_MS` / `HEALTH_CHECK_POLL_INTERVAL_MS` / `HEALTH_CHECK_INITIAL_DELAY_MS`

#### Scenario: tool window uses SSE callback registration only

- **WHEN** the tool window starts a new SSE consumer via `OpenCodeServerManager.getOrCreateConsumer(...)`
- **THEN** it registers only `onConnectionLost` and `onConnectionEstablished` callbacks; no `HealthMonitor` is instantiated; `MyToolWindow` no longer holds a `healthMonitor: HealthMonitor?` field

#### Scenario: getOrCreateConsumer supports callback re-registration via computeIfPresent

- **WHEN** the tool window re-registers callbacks on an existing consumer (e.g., after tool window close → reopen in same project session, where `consumers.computeIfAbsent` returns the existing consumer and would otherwise silently ignore the new lambdas)
- **THEN** the system atomically updates the existing consumer's `@Volatile var onConnectionLost` and `@Volatile var onConnectionEstablished` fields with the newly passed lambdas (e.g., via `.also { existingConsumer -> existingConsumer.onConnectionLost = newLost; existingConsumer.onConnectionEstablished = newEst }` chained after the `consumers.computeIfAbsent(...)` call returns the existing consumer) — fixing the lambda lock-in problem by ensuring new `MyToolWindow` instances' lambdas take effect on tool window re-registration

### Requirement: Unit tests cover all new behaviors

The system MUST have unit tests in `src/test/kotlin/com/shenyuanlaolarou/opencodewebui/listeners/OpenCodeSSEConsumerTest.kt` covering the new behaviors. Since the repo currently has zero existing test coverage for `OpenCodeSSEConsumer`, the tests MUST be created from scratch. Tests MUST also cover the pre-existing `onError` `wasConnected` gate (L288-304) — this gate is the prerequisite of Part A/B's fix, and a subsequent refactor MUST NOT silently break it.

#### Scenario: stop() invokes onConnectionLost in connected=true state

- **WHEN** `OpenCodeSSEConsumer.stop()` is called with `connected = true` and a registered `onConnectionLost` callback
- **THEN** the test verifies `onConnectionLost` is invoked exactly once, synchronously, after `wasConnected` has been captured (and regardless of whether `connected` is set to `false` before or after the invocation)

#### Scenario: stop() does not invoke onConnectionLost in connected=false state

- **WHEN** `OpenCodeSSEConsumer.stop()` is called with `connected = false`
- **THEN** the test verifies `onConnectionLost` is NOT invoked

#### Scenario: reconnect() invokes onConnectionLost in connected=true state

- **WHEN** `OpenCodeSSEConsumer.reconnect()` is called with `connected = true` and a registered `onConnectionLost` callback
- **THEN** the test verifies `onConnectionLost` is invoked, and that `startSseConnection()` is also called (the reconnect path completes the EventSource recreation, not aborts it)

#### Scenario: onOpen() invokes onConnectionEstablished on first call

- **WHEN** `onOpen()` is called for the first time (`lastEstablishedAt` is 0L)
- **THEN** the test verifies `onConnectionEstablished` is invoked exactly once and `lastEstablishedAt` is updated to `now`

#### Scenario: onOpen() skips onConnectionEstablished within 1.5s debounce

- **WHEN** `onOpen()` is called twice in succession with the second call within 1.5s of the first
- **THEN** the test verifies `onConnectionEstablished` is invoked only on the FIRST call, NOT on the second

#### Scenario: pre-existing onError wasConnected gate is preserved

- **WHEN** `onError(throwable)` is called while `connected = false` (e.g., error before initial onOpen completes, or stop()-then-error race)
- **THEN** the test verifies `onConnectionLost` is NOT invoked (the pre-existing `if (wasConnected)` gate at L288-304 is intact, and a future refactor of the gate MUST NOT silently change this semantic)

#### Scenario: getOrCreateConsumer atomically updates callbacks on existing consumer

- **WHEN** `getOrCreateConsumer(project, onConnectionLost = lambdaA, onConnectionEstablished = lambdaB)` is called and the consumer already exists in the `consumers` map
- **THEN** the test verifies that the existing consumer's `onConnectionLost` field is now `lambdaA` and `onConnectionEstablished` field is now `lambdaB` (atomically updated via `computeIfPresent`, fixing the lambda lock-in)

## MODIFIED Requirements

(none — this is a new capability; no existing `openspec/specs/<capability>/spec.md` is modified)
