# TOOL WINDOW DOMAIN

**Generated:** 2026-04-10
**Parent:** ../AGENTS.md

## OVERVIEW
Core JCEF + OpenCode server management.

## STRUCTURE
```
toolWindow/
└── MyToolWindowFactory.kt    # 471 lines, all core logic
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Server mgmt | MyToolWindowFactory.kt | start/stop/health checks |
| JCEF setup | MyToolWindowFactory.kt | browser + keyboard fixes |
| State | MyToolWindowFactory.kt | static server/browser state |

## CONVENTIONS
- JBCefBrowser Web UI integration
- opencode serve on 127.0.0.1:10086
- 30s auto health checks
- IDE shutdown cleanup via MyApplicationDisposable

## ANTI-PATTERNS
- Static global state
- Single-file logic
- Port inconsistency