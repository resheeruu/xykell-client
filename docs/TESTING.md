# Testing

## What runs where
- **Phone (Termux)**: native CMake build (`scripts/build/build-native.sh`,
  pipefail-verified), symbol checks (`nm -D`), levipack packaging check.
  Kotlin: `scripts/test/run-kotlin-unit.sh` (7 pure-JVM suites, 49 tests) and
  `scripts/test/run-kotlin-typecheck.sh` (all 35 main sources against
  android.jar + androidx + generated R stub). Both need the verified toolchain
  outside the repo (`~/local/opt/kotlinc`, `~/local/opt/jvm-jars`,
  `~/local/opt/androidx-jars`) — missing toolchain fails loudly, never skips.
  Phone cannot run Gradle or aapt2 (x86-64 prebuilts on ARM64), so APK
  assembly stays CI-only.
- **CI (GitHub Actions)**: `android-release.yml` rebuilds native with NDK r28c
  and asserts: aarch64 `.so`, `PLGetModRegistration` exported, APK exists,
  APK contains `lib/arm64-v8a/libxykell.so` with the symbol. Any failed
  assertion fails the run.
- **Device (human)**: M1 runbook in `docs/M1-RESULTS.md` (overlay, taps,
  toggles, restart). Nothing runtime is claimed without this.

## Minimum per subsystem (from here on)
Core init/shutdown, module register/enable/disable, event dispatch,
config serialize/recover, version detection, profile load, HUD state,
safe mode, launcher lifecycle — host or CI tests where possible, device
checklist where a runtime is required. Device-only claims stay marked
until observed.

## Test lessons (real bugs caught)
- `GuiController::visible()` returns by value: never hold pointers into the
  result across statements (use-after-free; phone libc++ tolerated it, CI
  g++ threw `length_error`). Bind to a local first.
- File-state fixtures start clean every run (`run-unit.sh` wipes them):
  aborted runs used to pollute "missing file" assertions.
- `-Werror` is target-scoped in CMake: third-party deps must not inherit it.
