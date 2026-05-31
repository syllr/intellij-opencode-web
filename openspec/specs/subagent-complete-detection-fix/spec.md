# subagent-complete-detection-fix

## Purpose

[TBD]

## Requirements

### Requirement: Subagent session tracking must persist through session.deleted

The system SHALL maintain subagent session tracking across `session.deleted` events to ensure `session.status(idle)` events for subagent sessions are correctly identified.

#### Scenario: session.deleted arrives before session.status(idle)

- **WHEN** a `session.deleted` event arrives for a subagent session (previously tracked via `session.created` with `parentID`)
- **THEN** the system SHALL NOT remove the subagent session ID from the tracking set, preserving the association for subsequent `session.status(idle)` events

#### Scenario: Correctly map subagent session idle to subagent_complete

- **WHEN** a `session.status(idle)` event arrives for a subagent session (session ID present in the tracking set)
- **THEN** the system SHALL dispatch a `subagent_complete` event (not `complete`), regardless of whether a `session.deleted` event was previously received for that session

### Requirement: Clear tracking state on SSE reconnect

The system SHALL clear all subagent session tracking when SSE connection is re-established.

#### Scenario: After SSE reconnect, tracking is reset

- **WHEN** the SSE connection to OpenCode server is re-established (`onClosed()` followed by `start()`)
- **THEN** all subagent session tracking SHALL be cleared, ensuring fresh tracking after reconnect
