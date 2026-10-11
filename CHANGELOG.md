# Changelog

## 0.5.0 — 2026-10-11

Motion system, and a full audit pass that found thirteen defects — including
two in code this project added in the previous two releases.

### Added
- `ui/Motion.kt` — one source for durations, curves and the reduced-motion
  rule. Before this, durations were literals scattered across fragments and
  reduced motion was checked separately in two files, which is how a UI ends up
  with six different fades and a setting only some screens honour.
  Material emphasis curves expressed as cubic-bezier control points, so motion
  composed from these sits next to platform transitions without a seam.
  Staggered entrance for list content, capped at the first few rows so a long
  list does not take longer to arrive than the user waits.

### Fixed — found by audit
- **Replay buttons did nothing.** `startReplay`, `stopReplay`, `renderReplay`
  and `renderOnboarding` existed and were never called from `onViewCreated`.
  The 0.4.0 release shipped controls that rendered and went nowhere. Found by
  cross-checking every `R.id` reference against the fragment that inflates it.
- **`levitate` and `movementCorrection` ignored packet direction.** Both take a
  direction and neither checked it, while their own documentation says
  "scoped to TO_SERVER". MovePlayer is `bound: both`, so `levitate` was raising
  every *other* player's position by 1.5 blocks on this device, and
  `movementCorrection` was swallowing other entities' server teleports. Their
  tests asserted the buggy behaviour on the clientbound leg; corrected, plus a
  regression test that the clientbound leg is byte-identical.
- **HUD overlay froze permanently on one bad frame.** `View.invalidate()` was
  called from the overlay `HandlerThread` — documented as not thread-safe — and
  the surrounding catch returned without rescheduling, so any throw stopped the
  refresh loop for good. Now uses `postInvalidate()` and always reschedules.
- **`ObservedState.trimChat` evicted the wrong id.** It removed the head line's
  id *after* `removeFirst()`, so every dropped line leaked its id and a
  redelivery of the retained head line appeared twice.
- **`EntityTable` leaked history.** `RemoveEntity` dropped the entity but kept
  its 20-entry ring; nothing else pruned it, so a long session grew without
  bound.
- **`VisualModules` sized an array from an untrusted varint before checking
  it.** A four-byte PlayerFog could request a multi-gigabyte allocation before
  the loop that would reject it ran.
- **`ScriptRuntime.install` was a no-op on the partial-acceptance path** —
  `installed = installed` discarded the computed set. A throwing rule also
  counted twice against `scriptFailures`.
- **"App startup" showed a meaningless number.** It subtracted wall-clock epoch
  time from uptime, on two different clocks, recomputed on every fragment
  construction. Now one monotonic measurement taken once per process.
- **`RakNetEndpoint`** let 17 concurrent splits exist against a constant
  documented as 16.

### Fixed — accessibility
- **Reduced motion was inverted.** Both call sites tested
  `fontScale > 1.3f` — a large-text check wearing a reduced-motion name — which
  disabled page swiping for users who had enlarged their fonts, the opposite of
  what they need. Now reads the system animator scale.

### Removed
- `PerformanceStore.frameCount` and `ReplayRecorder.startedAtMs`: written,
  never read.

### Verification
- `run-kotlin-unit.sh` — 71/71 suites pass.
- `run-kotlin-typecheck.sh` — 140 main and 73 test sources.
- `check-i18n.py` — 7 locales x 558 keys.

## 0.4.1 — 2026-10-11

The app was displaying a decode limit it was not enforcing.

### Fixed
`BedrockBatch.MAX_INFLATED` was a local `10 * 1024 * 1024`, while
`DecodeLimits.MAX_PACKET_BYTES` was 16 MB — and the diagnostics screen and the
`/limits` command both reported the `DecodeLimits` figure. So the UI showed a
16 MB policy on a path that actually refused anything over 10 MB.

That is the defect the two-constants situation causes: not a style complaint
but a screen that cannot be trusted to describe the running system.

The batch ceiling now lives in `DecodeLimits.MAX_INFLATED_BATCH_BYTES` and
`BedrockBatch` reads it, so the displayed policy is the applied one. It is kept
separate from `MAX_PACKET_BYTES` on purpose — a batch is a container of packets
and decompresses to more than one packet's worth — and three tests now pin the
relationship so the two cannot drift apart again unnoticed.

### Verification
- `run-kotlin-unit.sh` — 70/70 suites pass.
- `run-kotlin-typecheck.sh` — 138 main and 72 test sources.
- `check-i18n.py` — 7 locales x 558 keys.
- `build-gradle.sh assembleDebug` — BUILD SUCCESSFUL.

## 0.4.0 — 2026-10-11

Wires the remaining ported subsystems into the UI. In 0.3.0 four of them were
tested library code that nothing called; this release makes three reachable and
marks the fourth honestly.

### Wired
- **Chat screen** gained a local command console with a command input row.
  Built-ins: `/status`, `/pos`, `/speed`, `/chat`, `/limits`, `/help`. Every one
  is a reader. Relay facts are injected as parameters rather than read from
  `RelayService`, because that type is an Android `Service` and importing it
  would drag the framework into a class meant to stay host-testable.
- **Chat render path** now sanitises observed messages with
  `ComponentSanitizer.sanitizePlain`, so control bytes never reach the HUD.
- **Session screen** gained a launch-mode card backed by `Onboarding` and
  `OnboardingStore`, written atomically so a half-written document cannot
  restore as "first run" and re-show the introduction.

### Marked, not hidden
- `runtime/relay/BlockPalette.kt` is **[RESEARCH REQUIRED]**: nothing constructs
  one at runtime, because no code path reads a block palette out of a start-game
  handshake yet. It is left unwired on purpose. Showing it as "none loaded"
  would present a placeholder as a state; the repository's rule is that a
  capability is either reachable or explicitly marked.

### Fixed
- `ObservedCommands` importing `RelayService` would have made a host-JVM class
  depend on the Android framework. Replaced with injected parameters.

### Verification
- `run-kotlin-unit.sh` — 70/70 suites pass (69 before).
- `run-kotlin-typecheck.sh` — 138 main and 72 test sources.
- `check-i18n.py` — 7 locales x 558 keys.

## 0.3.0 — 2026-10-11

Ports the reference-client analysis into this tree, and wires it into the app.
Everything below was previously tested library code that nothing called.

### Added
- `runtime/replay/` — session replay. `ReplayStore` (versioned JSON model,
  bounded on frames, inputs per frame, label length and document size),
  `ReplayRecorder` (frames exist only for coordinates the observation layer
  actually produced), `ReplayPlayback` (time-indexed; returns observation data
  only and never emits input).
- `runtime/command/CommandConsole.kt` — local command console over observed
  state. Deliberately local: it cannot reach the game, because
  `ObservedState` exposes no way to send anything.
- `runtime/relay/DecodeLimits.kt` — explicit bounded decode limits per
  untrusted length, plus a per-packet byte budget. Setting a decode limit to
  "whatever fits in memory" reads as a decision rather than an omission, so
  the bounds are named and justified.
- `runtime/relay/CodecDiagnostics.kt` — protocol sanity and device-vs-upstream
  leg comparison. Reports observed values; claims no support matrix, because
  this build generates its packet table from a single vendored schema.
- `runtime/relay/BlockPalette.kt` — indexed and hashed block network-id modes.
- `runtime/observation/ComponentSanitizer.kt` — bounded text-component
  rendering; interactive payloads are dropped, never rendered.
- `runtime/network/RelayTuning.kt` — socket buffer/MTU policy, thread priority,
  and a caching upstream resolver.
- `runtime/packs/PackArchive.kt` — `.mcpack` scanning with entry-count,
  total-size and per-entry caps; reports traversal names, never extracts.
- `runtime/Onboarding.kt` — first-run state and launch-mode policy.
- `ui/ThemeColors` HSV re-tint (`hsvToArgb`/`hsvToHex`/`withHsv`).
- `docs/REFERENCE-CLIENT-ANALYSIS.md`, `docs/REFERENCE-CLIENT-SURVEY.md`.

### Wired
- Session screen records, stops, lists and describes replays.
- Content screen imports `.mcpack` archives, not just a bare `manifest.json`,
  closing a hole the original code documented in a comment.
- Diagnostics shows live relay values: negotiated protocol, requested vs
  granted socket buffers, active decode limits.

### Fixed
- `RelaySessionDriver` resolved its upstream host in its constructor, so
  starting the relay performed a blocking DNS lookup on the caller's thread.
- `RelaySessionDriver.loop` could die on a socket or resolution error while the
  service still reported running; it now closes the session, matching the
  class's own documented contract.
- `build-gradle.sh` invoked `build-apk.sh` to "build the native libs first",
  but that script assembles a whole APK into a temp directory and never writes
  to `app/src/main/jniLibs`. Gradle packages only what is under `jniLibs`, so
  every Gradle-built APK shipped with no `libxykellcore.so` and every
  `System.loadLibrary("xykellcore")` failed. It stayed invisible because each
  JNI facade catches `UnsatisfiedLinkError`. `scripts/build/build-jni-bridge.sh`
  now does the one job that step was supposed to do, and fails loudly.
- Launcher label reads `@string/app_name` so manifest and resource cannot drift.

### Verification
- `run-kotlin-unit.sh` — 69/69 suites pass (58 before).
- `run-kotlin-typecheck.sh` — 136 main and 71 test sources.
- `check-i18n.py` — 7 locales x 552 keys.
- `build-gradle.sh assembleDebug` — BUILD SUCCESSFUL, with `libxykellcore.so`
  (46 JNI exports) and `libc++_shared.so` packaged.

Maintained in `docs/CHANGELOG.md`. Summary:

## [0.2.9]
**Fixes Client sub-screens being slow and intermittently not appearing.** The
module registry was read, parsed and inflated into ~1,550 views on the UI
thread, which blocked it long enough that the caller's async fragment
transaction looked like a dropped tap.

## [0.2.8]
**Fixes the splash never being dismissed; locks the UI to landscape.** The
splash was added into the FrameLayout that wraps the pager and never removed,
so it covered a working UI with a static "Ready" screen the whole session.

## [0.2.7]
**Fixes a permanent splash overlay covering the whole UI.** A `splash_screen`
view was the last child of the root FrameLayout, opaque and never hidden by
any code, so the app opened to a static screen with no reachable content.

## [0.2.6]
**The app launches.** Builds moved from a hand-rolled packaging script
to the canonical Android Gradle Plugin; the hand-rolled path shipped
four consecutive fixes that each passed their own checks and none of
which worked. Also fixes the HUD overlay reading the wrong profile
directory, adds a visible switch to every registry entry, and adds a
guided relay setup. Still no live Bedrock session.

## [0.2.3]
**Fixes `ClassNotFoundException: MainActivity`.**

`classes.dex` was being deflated by the packaging step. Gradle has always
stored dex uncompressed so ART can memory-map it straight out of the APK; a
hand-rolled `zip` defaults to deflate, and on Android 16 the loader then cannot
find classes that are plainly present.

The failure looks like a packaging mistake that dropped the class, which is why
it is worth writing down what it was not: the dex is well-formed
(`dex\n039`, header_size 112, endian tag valid, file_size matching), contains
`Ldev/xykell/client/MainActivity;` as a real `class_def`, and holds 48,403
method ids against a 65,536 limit. Nothing was missing. The dex was just
compressed.

`zip -0` now stores it, and the build asserts the method afterwards so this
cannot regress silently.

## [0.2.2]
**Fixes the startup crash.**

`libxykellcore.so` was linked with `NEEDED libc++_shared.so`, but the APK
packaged only `libxykellcore.so`. Android does not hand the NDK C++ runtime to
third-party apps; it has to ship inside the APK. Without it,
`System.loadLibrary("xykellcore")` fails at startup and the app dies on launch.
This was never a permissions problem — it would have crashed on every version
regardless of what Play Protect thought.

Two faults, found and fixed together:
1. The C++ runtime was never packaged.
2. The first fix picked the **32-bit ARM** copy from the NDK sysroot, next to a
   64-bit library. The build now takes the aarch64 one and *asserts* that the
   runtime and the library share a class, so this cannot regress silently.

Verified by dlopen-ing the packaged library the way the app does, and touching
the symbols the startup path calls. Also a build-time guard: the build now reads
every `NEEDED` entry and fails if the APK does not contain it, so a future
dependency cannot reintroduce this.

## [0.2.1]
Fixes the install problem reported against 0.2.0.

- **The app now has an icon.** The manifest pointed at
  `@android:drawable/sym_def_app_icon` -- Android's placeholder -- so the app
  had never had launcher art. Now an adaptive icon (`mipmap-anydpi-v26`, which
  covers everything at minSdk 28), drawn as vectors so there is no rasterisation
  to drift: two teal `#4FD8C7` chevron bars crossing, a light diamond at the
  crossing, on a dark slate gradient. Colours are the app's own theme tokens. A
  monochrome layer is included for Android 13+ themed icons.
- **Signing is stable.** The keystore was generated per build inside the work
  directory, so every rebuild produced a *different* signature and Android saw
  a different app each time. Three modes now: `release` (persistent key,
  password from env, never committed), `managed` (one persistent key, no
  password to manage, never committed, not for Play distribution), and `debug`
  (per-build scratch key for CI). Default is `managed`.
- **The script refuses to invent a keystore password.** My first attempt
  auto-generated one and printed it to stderr; I lost it, and the key was
  unusable afterwards -- a key you cannot reuse is a key that recreates the
  original problem. It now fails with instructions instead.
- **`docs/PERMISSIONS.md`**, because the app asks for the overlay +
  accessibility + screen-capture combination that scanners treat as a banking
  trojan. It documents each permission's single purpose and the commands to
  verify it against source. `FOREGROUND_SERVICE_MEDIA_PROJECTION` is *not*
  removable: Android 14+ requires it for a MediaProjection foreground service,
  and manifest permissions are static. Dropping it would break
  `misc.screenshot_share` and `misc.screenshot_tools`, so the reason it stays is
  documented rather than left looking like an oversight.

Gates: kotlin-unit 57/57, native 37/37, typecheck 124/58, i18n 7x536,
registry 258, jni 46 exports, audit no-fake, APK PASS.

## [0.2.0]
Tagged. Nine ids delivered and the HUD made visible for the first time — see
`docs/RELEASE-0.2.0.md` for the verification table, artifact hash, and the
limitations stated plainly (`SUPPORTED` is still 0 because no session has run).
Full narrative in `docs/WHATS-NEW.md`.

Also in this release:
- **Flaky gate fixed.** `RelayPipeTest` and `ObservationFeedServerTest` bound
  their own deadlines too tightly for a phone running 57 suites in one JVM, so
  working code failed under load. Reads are now 30 s with 60 s per-test limits.
  Verified over three consecutive full runs. Note the first attempt at this
  raised socket timeouts alone and made things *worse*, because the real limit
  was JUnit's own `@Test(timeout = 8000)`.
- **Repository consolidated to `main`.** `feature/batch1-hud-sources` and
  `stage20-validation` are merged and deleted. The first produced 40 conflicts
  (many `add/add`) because main's files were strictly newer, so it was merged
  with the `ours` strategy: the branch is recorded as merged, main's content is
  untouched, and the merge commit changes zero files.

## [Unreleased]
- **`player.death_position` delivered**, and with it an honest correction to the
  plan I gave you. I claimed W5's sub-chunk palettes carry block *names* and
  that W6's `input_data` was decodable. Both were wrong, and the registry's own
  packet table says so: `LevelChunk.payload` is opaque `ByteArray`,
  `UpdateBlock` carries a **numeric** `block_runtime_id` whose name mapping
  lives in the game's resource pack rather than on the wire, and this build's
  `PlayerAuthInput` has **no `input_data` field at all**. So W5's decoder alone
  would have unlocked none of its 17 ESP ids, and W6's 10 were gated on an
  `Action` enum the repo does not carry. That is a deliberate design stance in
  this codebase, not an oversight.
  `DeathInfo 0xbd` was picked instead because it is genuinely complete: one
  cause string and a string array, no enum and no opaque field. `DeathTable`
  decodes it and records where the player was — from their **own** outbound
  `MovePlayer`, never a server-supplied position, for the same reason "where am
  I" is never taken from the server. The packet id is read as a real LEB128
  varint because `0xbd`'s high bit makes its header two bytes: masking the
  first byte is the same one-byte assumption that once made the knockback id
  drift, and the test caught exactly that.
- **`player.spam` delivered — the first id the relay authors rather than
  rewrites**, which is the decision you approved. `BedrockText.chat` builds a
  serverbound `Text 0x09` in the exact layout `BedrockPackets.text()` already
  decodes, and the two are pinned against each other by a round-trip test, so
  the encoder cannot drift from the verified decoder. That test earned its keep
  immediately: the encoder was missing the trailing filtered-message flag the
  decoder reads unconditionally, and without the round trip the packet would
  have been rejected wholesale by every server that reads it properly.
  The scope stays narrow on purpose — the user's own account says text the user
  configured, on a wall-clock cadence with a hard 1 s floor, fired on the
  outbound leg so nothing is sent while the session is idle, and the client's
  own packet still goes out first. Forging a UseItem, Interact or placement is
  still out of scope and each keeps its reason.
- **`hud.server_info` and `hud.ip_display` delivered** — the first two ids to
  come out of W3, and the cheapest kind: they read facts the relay's own
  handshake already establishes. `RelaySession` reports to `Observations` the
  moment the upstream reaches PLAY, carrying the configured upstream host:port
  and the protocol version parsed out of the client's own `LoginPacket 0x01`.
  Both render `--` until a connection exists, and a host with no protocol yet
  still renders `--`, because half a claim is not a claim. **No latency field
  was added**: nothing in this repo measures a round trip, so a ping readout
  would have to be invented. `hud.ping` stays NOT_IMPLEMENTED.
  `SessionEndpointObservation` is the name rather than `ConnectionObservation`
  because `runtime_provider.h` already owns a different struct by that name.
- **`hud.tab_list` delivered end-to-end, across both languages.** The roster
  comes from clientbound `PlayerList 0x3f`, which this repo did not decode. The
  chain is now: `PlayerListTable` decodes add/remove/clear and keys entries on
  UUID (a rename replaces rather than duplicates, and a name-keyed roster would
  leak the old row forever) → `ModuleRuntime` offers each change through
  `Observations` → the native `ObservationConsumer` keeps a bounded 128-entry
  join-ordered roster → `formatTabList` renders it → `ElementType::TabList`
  carries it to the overlay. An unreported roster renders `--`, never "0
  players": nobody in the world and nobody observed yet are different claims.
  `player_notifier`, `friend_alerts`, `nickname` and `mod_alerts` stay
  NOT_IMPLEMENTED — each needs alert or UI wiring that does not exist yet, and
  the roster alone does not deliver any of them.
- **The relay's own decode now reaches the HUD.** Worth stating plainly, because
  it was a real gap: the observation consumer was only ever fed by
  `ObservationService` from the external localhost feed, so anything the *relay*
  decoded stayed invisible no matter how correct it was. `ModuleRuntime` hands
  roster changes to the sink through an injected callback — injected because the
  production sink crosses JNI and a host JVM test cannot load it.
- **`combat.backtrack` delivered, and two capabilities added to unblock it.**
  Its `IMPOSSIBLE` reason was specific: "ctx.entities holds each entity's latest
  position and overwrites it on every move ... ModuleContext exposes no field in
  which a lookback ring could be kept." Both halves were true, so both were
  fixed rather than argued away. `EntityTable` now keeps a bounded 20-sample
  position ring per entity, dropped with the entity on eviction so a long
  session cannot grow it; `ModuleContext` gained an injected monotonic clock
  (`System.nanoTime`, not a wall clock that can jump), because `ctx.tick` counts
  packets and is not elapsed time. The transform rewrites clientbound
  `MovePlayer 0x13` to the position the entity held N ticks ago, leaves the
  outbound leg alone — the player's own movement must stay truthful or the
  server corrects it — and **forwards untouched when the entity has no history
  yet**, because silently freezing a first-seen target would look identical to a
  working module.
- **The HUD is now actually drawn over the game.** Until now `HudPreview` was
  structural text in the launcher and there was no overlay window at all, so no
  HUD value had ever been visible in a session. `HudOverlayService` paints a
  `TYPE_APPLICATION_OVERLAY` canvas at 4 Hz, fed by a new
  `NativeHud.renderHudLines` JNI export that renders through the *same* tested
  C++ renderer the game-side overlay uses and returns the lines as JSON. The
  provider snapshot is the same `ObservationConsumer` the JNI offers feed, so an
  unobserved value renders `--` and is pinned as such. Started and stopped by
  hand from the HUD screen; the special `SYSTEM_ALERT_WINDOW` grant is sent to
  system settings and re-read on resume rather than assumed. This adds draw
  calls *above* the game and cannot change what the game itself renders — xray,
  `gui_scale`, shaders, `free_cam` and the other renderer-side ids stay
  `REFERENCE_ONLY`.
- **Three input-gesture ids delivered** (`combat.afk_clicker`,
  `combat.double_click`, `misc.quick_drop`). Their `IMPOSSIBLE` reasons all named
  a missing *input surface*, which the tap runner made false. `MacroStep` gained
  an explicit `holdMs`: "wait then tap" and "press for N ms" are different
  instructions, and overloading one delay field would have made them the same
  number. `MacroStore` encodes the hold and still decodes the old 3-field format
  as a plain tap, so recorded macros keep replaying.
- **Relay now terminates both Bedrock legs at runtime.** `RelayService` drives a
  `RelaySession` through a new `RelaySessionDriver` (SERVER role toward the game,
  CLIENT upstream) instead of the transparent pipe, and installs a
  `RelayObservation -> ModuleRuntime` listener chain. Status distinguishes
  STOPPED / HANDSHAKING / ONLINE, because a bound socket answers pings long
  before the upstream login is rewritten.
- **48 module transforms are reachable.** They were written and host-tested but
  nothing called them: `RelaySession` was only ever constructed in its own test
  with `listener = RelayListener.PASS`, and the transparent pipe cannot decrypt,
  so no transform could run at all. `ModuleRuntime` owns the session
  `ModuleContext` and dispatches every implemented id in a fixed order;
  `ModuleFlags` reads the active profile; `ModuleTapRunner` replays the
  input-injection plans through the accessibility service, the same gesture
  surface a finger uses — no UseItem or Interact packet is ever forged.
- **Four HUD ids delivered from the observation path** (`hud.health`,
  `hud.low_health`, `hud.entity_counter`, `hud.tps`), all previously blocked on
  "no observation source". Vitals fields are optional and merge independently;
  ticks-per-second is derived from two SetTime samples and stays unavailable
  rather than reporting zero from one.
- **Two further dead-code bugs fixed.** `EntityTable.observe` was called from
  nowhere, so all 18 derived entity views saw zero entities; and the self id was
  only learned while a movement module happened to be enabled, which made the
  player count as their own target.
- Registry evidence corrected: `combat.velocity` claimed `SetActorMotion 0x1B`
  (that is EntityEvent, the id drift the suite exists to catch) and the wrong
  leg; corrected to `0x28`, server->game, with six other stale entries.
- Deleted the superseded raw-datagram `BedrockTap`; only its `Event` vocabulary
  survived, and it moved onto `TapTranslator`.
- Master-spec alignment: Levi-first foundation, capability classes, module API, M1 proof UI, expanded doc set.
- M1 implementation plan saved (`docs/superpowers/plans/2026-10-02-milestone-1-core.md`).

## 2026-10-02
- Approved Approach-A native spec + Phase 0 research baseline + repo skeleton (docs only, no modules).
