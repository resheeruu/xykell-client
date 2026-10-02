# Android notes

- Min: Android 9 (API 28)+, ARM64 (arm64-v8a) first, legit Play copy of Bedrock required.
- Pattern: Kotlin/Java launcher (Gradle) + C++ preloader `.so` (`preload-native` .levipack: manifest.json + lib*.so + config/).
- Per Levi docs: version isolation on, test native mods against isolated versions, pin preloader SDK tags.
- Storage (this machine): ~1.5G free — no giant SDKs without asking; incremental/cached builds; compressed assets.
- No local Chromium (bionic) — browser testing via remote/CDP or cloud only.
- Never delete user data to free space; no destructive cleanup without confirmation.
