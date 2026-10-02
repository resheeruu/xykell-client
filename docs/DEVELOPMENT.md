# Development

See /docs/ARCHITECTURE.md + /docs/RESEARCH.md first.

## Order (Phase 24)
1. repo + arch + build system 2. Android compat research 3. core (registry/events/config/profiles) 4. touch UI + ClickGUI 5. FPS/CPS/coords/keystroke HUDs 6. PerformanceManager 7. version detection 8. crash handling → first Android test build. Advanced modules only after.

## Rules
- Inspect before modifying; smallest safe change; reuse abstractions.
- No fake/placeholder modules claimed as working. Blocked → `[BLOCKED — PLATFORM LIMITATION]` + reason. Unknown → `[RESEARCH REQUIRED]`.
- Every feature: implementation + unit tests where applicable + compat check + docs + changelog entry.
- One concern per commit; `main` stays clean (`feature/*`, `fix/*`, `research/*` branches).
- Phone constraints: lightweight deps only, incremental builds, no giant downloads without asking.
