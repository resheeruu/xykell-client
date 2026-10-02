# M1 environment (Task 0 — observed 2026-10-02, Termux on-device)

Commands used: `getprop`, `uname -m`, `free -m`, `df -h`, `java -version`,
`which gradle cmake ninja git python3 xmake clang make`, `cmake --version`,
`git --version`, `make --version`, `ls ~/android-sdk/...`,
`pm list packages | grep -iE mojang|levi|xykell`,
`git ls-remote` (LeviLaunchroid HEAD, preloader-android tags),
GitHub commits API (HEAD date), probe compile with Termux clang.

## Device
- Android: 16, ABI: arm64-v8a (`aarch64`). RAM: 7.3 GB total / ~1.7 GB available. Swap 8.2 GB.
- Storage: 225 GB total, **4.1 GB free (99% used)** — installs need size estimates first.

## Toolchain (exact)
- JDK: OpenJDK 17.0.20 (Termux). NOTE: LeviLaunchroid itself wants JDK 21+ — building the
  launcher from source is out of M1 scope; M1 builds only the Xykell `.so`.
- Gradle: NOT installed. AGP: n/a. (Needed only if/when we build launcher-side code; Task 5 will decide.)
- CMake: 4.4.3 (system). Ninja: NOT installed. Make: GNU Make 4.4.1 (generators: Unix Makefiles).
- Git: 2.55.0. Python: system python3 present (version not pinned — only needed for scripts).
- xmake: NOT installed (BedrockTools uses it; M1 uses CMake + Termux clang, no download).

## Android SDK (existing, 2.8 GB — reuse, do not duplicate)
- `~/android-sdk`: build-tools 34.0.0 + 35.0.0, platforms android-35 + android-36,
  cmdline-tools (latest), platform-tools, licenses, ndk/26.1.10909125.
- NDK 26.1.10909125: **x86_64 prebuilt only — `Exec format error` on this ARM64 phone. UNUSABLE directly.**
- aapt (build-tools): x86-64 binary — unusable on-device. No on-device APK inspection via aapt.

## Proven phone-first compile path (evidence)
- Termux clang 21.1.8 (`Target: aarch64-unknown-linux-android24`) compiled a trivial
  `probe.c` → `libprobe.so`: `ELF 64-bit LSB shared object, ARM aarch64, for Android 24` (5,288 bytes).
- M1 native builds: CMake (Unix Makefiles generator) + Termux clang
  (`-target aarch64-linux-android24`), zero new downloads, ~KB-scale artifacts.

## Levi pin (Task 0.1)
- LeviLaunchroid HEAD: `a2e3fa22192d431531591578dfa5823327d48355` (2026-09-30,
  "chore: prepare v1.5.25 changelog"). Launcher line v1.5.25: MC 1.26.50 inbuilt-mod support,
  touch handling, native-mod menu open/close API.
- preloader-android: latest tag **`0.2.3`** (`92a5b2d4…`; 0.2.2 = `6a9f36ef…`). M1 pins **0.2.3**.
- Relevant API surface (from docs, to verify against tag at Task 1): `manifest.json`
  (`type=preload-native`), `PL_REGISTER_MOD`, typed config, Mod Menu integration,
  hook/patch handles as mod-owned state, `examples/full-cpp-mod` reference.
- LeviLaunchroid APK is NOT installed on this device → Levi-load test needs the release APK
  (download size to check before fetching; storage is critical).

## Bedrock baseline (Task 0.2)
- `com.mojang.minecraftpe` IS installed (base.apk + `split_config.arm64_v8a.apk` confirmed via `pm path`).
- Exact version string: NOT retrievable from Termux shell (no `dumpsys`, aapt is x86-only,
  `/data/app` unreadable). Marked **[RESEARCH REQUIRED]** — read from in-game Settings
  (or Levi version screen) at Task 6 device test. Do not fabricate.
- Xykell target: the installed build, whatever it reports; floor policy (≥1.21.80 per Levi) stands.

## Build feasibility (Task 0.3)
1. Can the phone build the Xykell native module? **YES — proven** (probe `.so` above).
2. NDK installed? Present but **wrong arch for execution**; Termux clang replaces it for M1.
3. CMake? Yes (4.4.3) + Make 4.4.1. No Ninja needed (Makefiles generator).
4. Gradle? No — not needed for the `.so`; deferred to Task 5 launcher-shell decision.
5. Incremental? Yes — CMake + Make, small tree, artifacts outside Git (`.gitignore` covers `*.so`, `build/`).
6. Storage for build: KB-scale; only new cost is the Levi release APK (to measure before download).
7. Artifacts outside Git: yes (`build/`, `*.so`, `*.levipack` ignored).
8. Reusable: SDK platforms/build-tools (if Gradle ever needed), system clang/cmake/make.

## Gate decision
Task 0: **PASS with documented limits** (Levi APK absent, exact MC version pending device read).
Proceeding to Task 1 (native skeleton, host-compiled, no Levi load claimed until Task 6).
