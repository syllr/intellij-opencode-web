# PROJECT KNOWLEDGE BASE

**Generated:** 2026-04-10
**Commit:** ce345c1
**Branch:** main

## OVERVIEW
JetBrains IntelliJ Platform plugin for OpenCode Web UI integration, written in Kotlin.

## STRUCTURE
```
/intellij-opencode-web/
├── src/main/kotlin/com/github/xausky/opencodewebui/
│   ├── toolWindow/          # Core JCEF + server management
│   ├── actions/             # IDE actions
│   ├── services/            # Platform services
│   ├── listeners/           # Lifecycle listeners
│   ├── startup/             # Startup activities
│   └── MyBundle.kt          # I18n bundle
├── src/main/resources/       # Icons, plugin.xml
├── .github/workflows/       # CI/CD workflows
└── build.gradle.kts        # Gradle config
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Core logic | src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt | 471 lines |
| IDE actions | src/main/kotlin/com/github/xausky/opencodewebui/actions/ | Restart, toggle, key passing |
| CI/CD | .github/workflows/ | Build, release, UI tests |
| Tests | src/test/ | Unit & UI tests |
| Build config | build.gradle.kts, libs.versions.toml | Dependencies, versions |

## CONVENTIONS
- Gradle version catalog for dependencies
- JetBrains IntelliJ Platform best practices
- Null-safe Kotlin
- CI runs on push/PR to main
- Kover code coverage to Codecov

## ANTI-PATTERNS
- Single large core file (MyToolWindowFactory.kt)
- Static global server state
- Port number inconsistency (10086 docs vs 12396 code)
- Unused sample service (MyProjectService.kt)

## UNIQUE FEATURES
- Emacs-style JCEF keyboard shortcuts
- Auto-server restart on IDE startup
- ESC key focus fix for JCEF
- Manual restart via Find Action

## COMMANDS
```bash
./gradlew buildPlugin          # Build plugin
./gradlew check                 # Run tests & checks
./gradlew runIde                # Run IDE with plugin
./gradlew publishPlugin         # Publish to Marketplace
./gradlew patchChangelog        # Update changelog
```

## NOTES
- Port inconsistency exists between docs and code
- Core logic should be refactored from MyToolWindowFactory