# Performance (rules + measurements)

Rules (phone-first, enforced by review): no per-frame allocations where
avoidable, no repeated signature scans (scan once, cache validated pointer),
no repeated JSON parsing (registry parsed once, cached), no disk writes in
callbacks, bounded entity/chunk scans with configured limits, module budgets
(`FrameBudget` measures; `checkBudget` enforces world-query limits).

Measurements (2026-10-02, Termux, ARM64):
- Host unit suite: 17 suites, minutes total (17 clang++ invocations, debug).
- `.so`: 544,608 B unstripped → ~313–347 KB stripped in levipack.
- APK: ~9–13.6 MB across batches (see M1.5-RESULTS.md).
- Runtime frame/memory impact: UNMEASURED (no device run yet — no claims).
- `HttpClient` etc. live in the game, not in Xykell; Xykell creates no
  threads, no timers, no background work (verified by construction: grep for
  `std::thread`/timers in native/ returns nothing but tests).
