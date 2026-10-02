# Release process (M1.5)

## Build route
- Phone (ARM64) cannot run stock `aapt2` (x86-64) → APK assembly runs on
  GitHub Actions (`.github/workflows/android-release.yml`, ubuntu-latest).
- Phone responsibilities: edit, git, push, inspect. CI: NDK native build,
  resource packaging, APK assembly, verification, artifact.

## CI pipeline (verified by reading, not yet run — runs on first push)
1. Checkout → JDK 17 → Android SDK → SDK packages
   (`platform-tools`, `platforms;android-35`, `build-tools;35.0.0`) →
   NDK r28c → CMake native build (arm64-v8a, API 28, MinSizeRel) →
   verify `.so` (aarch64 + `PLGetModRegistration`) → stage into
   `app/src/main/jniLibs/arm64-v8a/` (CI-generated, gitignored) →
   Gradle 8.10.2 `:app:assembleDebug` → verify APK contains
   `lib/arm64-v8a/libxykell.so` with the entry symbol → size + sha256 →
   upload artifact `XykellClient-debug`.

## Signing
- Development: standard debug signing (auto-generated debug key). No keys in repo.
- Release (future): create a dedicated workflow using GitHub Secrets
  (`XYKELL_KEYSTORE_BASE64`, `XYKELL_KEY_ALIAS`, `XYKELL_KEY_PASSWORD`,
  `XYKELL_STORE_PASSWORD`). Documented here so no secret is ever committed.
  Release signing is NOT configured yet — no keys exist.

## Artifact
- Name: `XykellClient-debug` → `app-debug.apk`. Size/sha256 recorded in
  `docs/M1.5-RESULTS.md` after the first green run.
