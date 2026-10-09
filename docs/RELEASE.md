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

**Release (0.2.1+): persistent local keystore.** `scripts/build-apk.sh` defaults
to `XYKELL_SIGN_MODE=release`, which signs with
`~/.config/xykell/release.keystore` (override with `XYKELL_KEYSTORE_DIR`).

The password is **required, never auto-generated**, and comes from the
environment:

```bash
export XYKELL_KEYSTORE_PASS='...'   # store password
export XYKELL_KEY_PASSWORD='...'    # key password (may be the same)
export XYKELL_KEY_ALIAS=xykell      # optional; this is the default
bash scripts/build-apk.sh
```

The keystore is created once, `chmod 600`, and is never committed — `.gitignore`
covers `*.keystore` and `*.jks`.

**Keep the keystore and its passwords.** Without them Android treats every later
build as a different app: it will refuse to upgrade over an installed copy and
force a reinstall that discards app data. This is exactly why the script refuses
to invent a password — a key whose password lands in a log the operator then
loses is a key that cannot be reused, which recreates the original problem.

**Debug mode** (`XYKELL_SIGN_MODE=debug`) keeps the old throwaway per-build key
for CI scratch builds that need no lasting identity.

For CI, use GitHub Secrets instead of a local key:
`XYKELL_KEYSTORE_BASE64`, `XYKELL_KEY_ALIAS`, `XYKELL_KEY_PASSWORD`,
`XYKELL_STORE_PASSWORD`. Documented here so no secret is ever committed.

## Artifact
- Name: `XykellClient-debug` → `app-debug.apk`. Size/sha256 recorded in
  `docs/M1.5-RESULTS.md` after the first green run.
