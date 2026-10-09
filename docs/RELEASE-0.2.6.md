# Release 0.2.6

**The app launches.** This is the first build where the player can open
Xykell and have it stay open.

## Why this release exists

0.2.2 through 0.2.5 each shipped a claimed fix for
`ClassNotFoundException: MainActivity`. All four passed their own
verification and none of them worked. The pattern was consistent enough to be
worth writing down: each release added a check, the check passed, and the APK
was still broken.

The checks were never wrong. They were inspecting artifacts of a packaging
script that had been reimplemented by hand, and no amount of verification of
those artifacts could tell us whether the assembly step itself was correct.

**Resolution: delete the variable.** `app/build.gradle.kts` is now the only
supported build path.

## What changed

| Area | Before | Now |
|---|---|---|
| Packaging | `scripts/build-apk.sh`, hand-rolled | Android Gradle Plugin |
| Dex layout | 1 dex, single unverified pass | AGP-emitted, verified complete |
| Signing | `apksigner` invoked manually | Gradle `signingConfigs` |
| Resources | `aapt2` link, hand-assembled | AGP resource merge |
| Native libs | built and zipped by script | prebuilt by script, packaged by AGP |
| aapt2 | Termux native | Termux native, via `aapt2FromMavenOverride` |

### Release signing

Signed with the same managed key as 0.2.x
(`529277551ecca4a9297de1fdd1c9be59bd8ee14d7cfc25f39e29aac09d435219`), so this
build installs **over** previous versions in place. No uninstall required.

The key lives at `~/.config/xykell/managed.keystore`, outside the repository.
It is debug-grade — adequate for sideloading, not for any store. See
`docs/SECURITY.md`.

### The NDK

Not installed, and not installed here: ~1.5 GB against a ~2 GB free disk.

`scripts/build-apk.sh` still compiles the native libraries with clang++ — that
part worked. They are copied into `app/src/main/jniLibs/arm64-v8a/` and AGP
packages them. `externalNativeBuild` is disabled so AGP does not try to run
CMake itself.

Note: AGP's `llvm-strip` step cannot run (x86-64 binary on aarch64) and logs a
syntax error during the release build. The build still succeeds; the libraries
are simply shipped unstripped.

## Corrections to earlier release notes

Two published diagnoses were wrong, and leaving them uncorrected would mislead
whoever debugs this next.

**0.2.3 — "the dex was compressed."** Wrong. `dexdump` showed the compressed
build containing `Ldev/xykell/client/MainActivity;` as a real `class_def`,
holding 48,403 method ids against a 65,536 limit, with the full superclass
chain resolving. Nothing was missing. Compression was real but not causal.

**0.2.5 — "Java 17 bytecode, which ART refuses."** Wrong, and shipped as a
confident root cause. The app built under this theory never emitted a single
log line on the device. It did not crash on a verification error; it never
started. The stated evidence was structural analysis of an artifact that was
not the thing failing.

Both theories were formed the same way: from structure, without a
reproduction. The lesson recorded in the changelog is that a diagnosis needs
an observed failure before it is written down as one.

## Feature state — unchanged and honest

**No live Bedrock session has ever completed.** The 9 delivered features remain
`PARTIAL`: verified on the host JVM against synthetic packets, never against a
real server stream.

Registry: 258 entries — 124 `PARTIAL`, 97 `REFERENCE_ONLY`, 22
`NOT_IMPLEMENTED`, 15 `DEVICE_LIMITED`, 0 `SUPPORTED`.

Every entry now shows a visible on/off switch, as requested. The switch writes
through the same native preference path either way and is harmless while inert.
The status label is rendered on every row and `supportsPreference` still
records whether an implementation exists, so a toggle cannot be mistaken for
working functionality.

## What to do

1. Install (upgrade in place; no uninstall).
2. **Client → Relay.**
3. Enter the real server under **Upstream host**.
4. **Start relay.** Wait for **ONLINE**, not merely RUNNING — RUNNING only
   means the socket is bound; a relay stuck in the handshake looks identical
   otherwise.
5. In Minecraft, add a server: address `127.0.0.1`, and the port shown in
   **Step 2** of the relay screen. That is the port the relay *actually*
   bound, which differs from the requested one when 19133 is occupied.
6. Join it.

Step 3 of the screen reports the live state. If it never leaves HANDSHAKING,
the status line carries the reason.

## Risk

Relaying your own Minecraft traffic violates the Minecraft EULA and risks an
account ban. This applies to the account you play on. That risk was accepted
before any of this was built and is unchanged by this release.
