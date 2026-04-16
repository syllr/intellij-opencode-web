# Server Detection Fix - Learnings

## OpenCode Update API Research

### Key Findings

1. **No dedicated check-update API exists**
   - OpenCode doesn't have a `/check-update` or `/api/update` endpoint
   - Must implement version comparison logic manually

2. **Available Endpoints**
   - `GET /global/health` - returns `{ healthy: true, version: "x.x.x" }`
   - `POST /global/upgrade` - upgrades to specified or latest version
     - Request: `{ target?: string }`
     - Response: `{ success: true/false, version: "x.x.x", error?: string }`

3. **SSE Event for Updates**
   - `installation.update-available` event via `/global/event`
   - Properties include `{ version: "x.x.x" }`

4. **Version Check Approach**
   - Call `GET /global/health` to get current version
   - Call `POST /global/upgrade` without target to check if upgrade available (or parse latest from response)
   - Compare versions to determine if update needed

## Server Detection Fix Changes

1. **checkAndLoadContent()**: Simplified - check port first, reuse if available
2. **restartServer()**: Only kills its own process, not port-based kill
3. **checkServerHealth()**: No longer auto-restarts, just logs warning

## Files Modified
- MyToolWindowFactory.kt: ~+17 -21 lines
