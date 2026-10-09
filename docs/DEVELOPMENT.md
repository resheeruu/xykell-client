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

## Building the APK (0.2.6+)

`scripts/build-apk.sh` is **no longer the APK build path.** It assembled APKs
by hand and shipped four releases that installed but could not load their own
Activity (0.2.2 - 0.2.5). Use Gradle:

```sh
gradle assembleRelease      # signed with the managed key, upgrade-compatible
gradle assembleDebug        # debug key, for local iteration
```

Two device-specific settings are already committed:

- `android.aapt2FromMavenOverride` points AGP at Termux's native aapt2. The
  x86-64 binary AGP downloads cannot execute on aarch64.
- The NDK is not installed. `externalNativeBuild` is disabled and AGP packages
  the libraries that `scripts/build-apk.sh` prebuilds with clang++ from
  `app/src/main/jniLibs/arm64-v8a/`.

`scripts/build-apk.sh` still builds the native libraries and still runs the
host test suites; it no longer packages or signs the APK.

### Signing

`app/build.gradle.kts` reads the managed keystore from
`~/.config/xykell/managed.keystore` (override with `XYKELL_KEYSTORE`), with
`XYKELL_KEYSTORE_PASS`, `XYKELL_KEY_ALIAS` and `XYKELL_KEY_PASSWORD`. Signing is
skipped, not failed, when the keystore is absent.

The key is debug-grade: fine for sideloading, not for any store. See
`docs/SECURITY.md`.
