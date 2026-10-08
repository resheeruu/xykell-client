# Changelog

Maintained in `docs/CHANGELOG.md`. Summary:

## [Unreleased]
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
