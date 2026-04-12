# OpenCode Web Refactor Learnings

## Completed Fixes (2026-04-12)

### Wave 1: Configuration Fixes
- T1.1: Added `pluginUntilBuild = 254.*` to gradle.properties
- T1.2: Added JBCefApp.isSupported() check in createToolWindowContent
- T1.3: Changed invokeAndWait to invokeLater in scheduler callback

### Wave 2: Core Resource Management
- T2.1: Added Disposer.register(project, browser) for JBCefBrowser lifecycle
- T2.2: Changed all static mutable vars to AtomicReference/AtomicBoolean

## Skipped Tasks

### T3.1: Listener Cleanup
- Requires tracking all listener references (HierarchyListener, WindowFocusListener, KeyListener)
- Would need to refactor MyToolWindow to hold listener references
- Complexity: High, Risk: Medium

### T3.2: Scheduler Shutdown
- Would need to expose scheduler.shutdownNow() via companion object method
- Called from MyApplicationDisposable.dispose()
- Alternative: JVM cleanup on exit is acceptable
- Complexity: Medium, Impact: Low

## Key Patterns Discovered

1. **JBCefBrowser disposal**: Use Disposer.register(project, browser)
2. **invokeAndWait in background thread**: Always use invokeLater instead
3. **Thread-safe static state**: Use AtomicReference<T> and AtomicBoolean
4. **AtomicReference null check**: Use .get() == null instead of == null

## Files Modified
- gradle.properties: +1 line (pluginUntilBuild)
- AGENTS.md: +1 line (git commit rule)
- MyToolWindowFactory.kt: 73 lines changed
