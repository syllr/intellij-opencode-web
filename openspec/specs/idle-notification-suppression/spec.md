# idle-notification-suppression

## Purpose

[TBD]

## Requirements

### Requirement: Suppress repeated complete notifications during agent task loop

The system SHALL suppress `complete` notification for a parent session when the session has already sent a `complete` notification in the current user interaction cycle.

#### Scenario: First idle after user message triggers notification

- **WHEN** a user sends a new message and the parent session becomes idle for the first time
- **THEN** the system SHALL dispatch a `complete` notification

#### Scenario: Subsequent idles during agent loop are suppressed

- **WHEN** the parent session becomes idle again (e.g., after a task() call completes) and a `complete` notification has already been sent for this session in the current user interaction cycle
- **THEN** the system SHALL NOT dispatch a `complete` notification

### Requirement: Reset suppression marker on user message

The system SHALL reset the suppression marker when a user sends a new message (`message.updated(role=user)` event), allowing the next idle to trigger a `complete` notification.

#### Scenario: Next user message re-enables notification

- **WHEN** a user sends a new message (`message.updated` with `role=user`)
- **THEN** the system SHALL clear the suppression marker for that session, allowing the next idle to dispatch a `complete` notification

#### Scenario: session.status(busy) does not reset marker

- **WHEN** the parent session transitions to `busy` state (e.g., agent processing a tool call or subagent result)
- **THEN** the system SHALL NOT reset the suppression marker

### Requirement: Apply suppression to both session.status(idle) and session.idle

The system SHALL apply the same suppression logic to both `session.status(idle)` (new format) and `session.idle` (deprecated format) events.

#### Scenario: session.status(idle) is suppressed

- **WHEN** a `session.status(idle)` event arrives and the session's suppression marker is set
- **THEN** the suppression SHALL apply

#### Scenario: session.idle (deprecated) is suppressed

- **WHEN** a `session.idle` event arrives and the session's suppression marker is set
- **THEN** the suppression SHALL apply

### Requirement: Clear suppression state on SSE reconnect

The system SHALL clear all suppression markers when SSE connection is re-established.

#### Scenario: After SSE reconnect, suppression state is cleared

- **WHEN** the SSE connection to OpenCode server is re-established (`onClosed()` followed by `start()`)
- **THEN** all suppression markers SHALL be cleared, so the next user message → idle cycle dispatches a `complete` notification
