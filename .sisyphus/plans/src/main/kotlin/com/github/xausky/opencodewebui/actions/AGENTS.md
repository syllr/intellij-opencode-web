# IDE ACTIONS DOMAIN

**Generated:** 2026-04-10
**Parent:** ../AGENTS.md

## OVERVIEW
Actions accessible via Find Action (Cmd/Ctrl+Shift+A).

## STRUCTURE
```
actions/
├── RestartServerAction.kt    # Restart server
├── PassToJcefAction.kt       # Keyboard shortcut forwarding
└── ToggleOpenCodeAction.kt   # Toggle tool window
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Restart server | RestartServerAction.kt | Kill & restart |
| Shortcuts | PassToJcefAction.kt | ESC + Emacs keys |
| Toggle | ToggleOpenCodeAction.kt | Show/hide window |

## CONVENTIONS
- Implements IntelliJ AnAction
- Registered in plugin.xml
- Uses ActionManager

## ANTI-PATTERNS
- None